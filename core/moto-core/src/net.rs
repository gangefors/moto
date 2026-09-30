// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The road network of the open regions, linked at their borders
//! (ADR-0009). Every algorithm in the core works on a [`Net`]: one region
//! or several, with global node, edge and geometry ids.
//!
//! Ids are the regions' own ids offset by the counts of the regions before
//! them, so one region's ids are its file's ids. Regions are joined where
//! a road crosses the border: each file keeps the road up to the first
//! node past it, a *stub* (see `region::format::BorderNode`). A stub
//! stands for the node with the same OSM id inside the neighbour; the net
//! replaces it by that node wherever an edge ends there, and hands out the
//! stub's edges as leaving that node. A stub with no twin (the neighbour
//! isn't open, or its data is from another month and the road changed)
//! stays a dead end: that crossing is closed, everything else links.

use std::collections::HashMap;
use std::ops::Range;

use crate::CoreError;
use crate::region::format::{
    BBoxE7, CurvatureMetrics, Edge, GeometryName, NO_NAME, Place, PointE7, Surface, WayRef,
    border_flags,
};
use crate::region::{Region, coverage_contains, merge_coverage};

/// Most regions open at once (ADR-0009).
pub const MAX_REGIONS: usize = 16;

/// One or more regions as one road network.
#[derive(Debug)]
pub struct Net {
    parts: Vec<Region>,
    /// Per region the first global node, edge and geometry id, and the
    /// totals as the last entry.
    node_base: Vec<u32>,
    edge_base: Vec<u32>,
    geom_base: Vec<u32>,
    /// Stubs linked to a twin: stub → the twin (global node ids).
    canon: HashMap<u32, u32>,
    /// Per twin, the global ids of the edges leaving and entering its
    /// stubs in other regions.
    extra_out: HashMap<u32, Vec<u32>>,
    extra_in: HashMap<u32, Vec<u32>>,
    /// One bit per global node, set for the nodes in `canon`, `extra_out`
    /// or `extra_in`: nearly every node is in none of them, and a search
    /// asks about every edge it looks at, so the maps are only consulted
    /// for the few hundred border nodes (a hash lookup per edge made
    /// searches over linked regions several times slower).
    linked: Vec<u64>,
    /// The highest speed of any edge, and of any unpaved edge, km/h: the
    /// route search's estimate needs both to stay a lower bound.
    max_speed_kmh: f64,
    max_unpaved_kmh: f64,
    /// Several regions' coverage as one outline (offsets, points), so
    /// neighbours join at their border instead of overlapping.
    merged: Option<(Vec<u32>, Vec<PointE7>)>,
}

/// The edges leaving (or entering) a node: its own, then those of the
/// stubs that stand for it.
pub struct EdgeIds<'a> {
    own: Range<u32>,
    base: u32,
    list: Option<&'a [u32]>,
    extra: std::slice::Iter<'a, u32>,
}

impl Iterator for EdgeIds<'_> {
    type Item = u32;

    #[inline]
    fn next(&mut self) -> Option<u32> {
        if let Some(i) = self.own.next() {
            return Some(match self.list {
                Some(l) => self.base + l[i as usize],
                None => self.base + i,
            });
        }
        self.extra.next().copied()
    }
}

impl Net {
    /// One region: the net is the region itself, ids unchanged.
    pub fn single(region: Region) -> Net {
        let (n, m, g) = counts(&region);
        let (max_speed_kmh, max_unpaved_kmh) = speed_bounds(std::slice::from_ref(&region));
        Net {
            parts: vec![region],
            node_base: vec![0, n],
            edge_base: vec![0, m],
            geom_base: vec![0, g],
            canon: HashMap::new(),
            extra_out: HashMap::new(),
            extra_in: HashMap::new(),
            linked: Vec::new(),
            max_speed_kmh,
            max_unpaved_kmh,
            merged: None,
        }
    }

    /// Several regions, linked at their borders. At most [`MAX_REGIONS`],
    /// and their ids must fit in 32 bits together.
    pub fn linked(parts: Vec<Region>) -> Result<Net, CoreError> {
        if parts.is_empty() || parts.len() > MAX_REGIONS {
            return Err(CoreError::InvalidArgument(format!(
                "open 1 to {MAX_REGIONS} regions, not {}",
                parts.len()
            )));
        }
        let too_big = || CoreError::Region("the regions together are too large".into());
        let (mut node_base, mut edge_base, mut geom_base) = (vec![0u32], vec![0u32], vec![0u32]);
        for r in &parts {
            let (n, m, g) = counts(r);
            let add = |v: &mut Vec<u32>, x: u32| -> Result<(), CoreError> {
                let next = v.last().copied().unwrap_or(0).checked_add(x);
                // Ids stay below u32::MAX, which some code uses as "none".
                v.push(next.filter(|&t| t < u32::MAX).ok_or_else(too_big)?);
                Ok(())
            };
            add(&mut node_base, n)?;
            add(&mut edge_base, m)?;
            add(&mut geom_base, g)?;
        }
        let mut net = Net {
            parts,
            node_base,
            edge_base,
            geom_base,
            canon: HashMap::new(),
            extra_out: HashMap::new(),
            extra_in: HashMap::new(),
            linked: Vec::new(),
            max_speed_kmh: 0.0,
            max_unpaved_kmh: 0.0,
            merged: None,
        };
        (net.max_speed_kmh, net.max_unpaved_kmh) = speed_bounds(&net.parts);
        net.link();
        net.merge_coverage();
        Ok(net)
    }

    /// Links every stub to the node with its OSM id that lies inside
    /// another region (the first such region, so the result doesn't depend
    /// on anything but the order the regions are given in).
    fn link(&mut self) {
        if self.parts.len() < 2 {
            return;
        }
        for (r, region) in self.parts.iter().enumerate() {
            for stub in region
                .border_nodes()
                .iter()
                .filter(|b| b.flags & border_flags::OUTSIDE != 0)
            {
                let twin = self.parts.iter().enumerate().find_map(|(o, other)| {
                    if o == r {
                        return None;
                    }
                    let list = other.border_nodes();
                    let i = list.binary_search_by_key(&stub.osm_id, |b| b.osm_id).ok()?;
                    let b = list[i];
                    (b.flags & border_flags::OUTSIDE == 0).then_some(self.node_base[o] + b.node)
                });
                let Some(twin) = twin else { continue };
                let s = self.node_base[r] + stub.node;
                self.canon.insert(s, twin);
                let base = self.edge_base[r];
                let out = region.out_edges(stub.node).map(|e| base + e);
                self.extra_out.entry(twin).or_default().extend(out);
                let inn = region.in_edges(stub.node).iter().map(|&e| base + e);
                self.extra_in.entry(twin).or_default().extend(inn);
            }
        }
        self.linked = vec![0; self.node_count().div_ceil(64)];
        let nodes: Vec<u32> = self
            .canon
            .keys()
            .chain(self.extra_out.keys())
            .chain(self.extra_in.keys())
            .copied()
            .collect();
        for n in nodes {
            if let Some(w) = self.linked.get_mut(n as usize / 64) {
                *w |= 1 << (n % 64);
            }
        }
    }

    /// Whether `node` has an entry in `canon`, `extra_out` or `extra_in`.
    #[inline]
    fn is_linked(&self, node: u32) -> bool {
        self.linked
            .get(node as usize / 64)
            .is_some_and(|w| w >> (node % 64) & 1 == 1)
    }

    /// Joins the regions' coverage into one outline. Overlapping outlines
    /// (each region's reaches a little past its border, along the roads
    /// it keeps up to the first node beyond) would otherwise be drawn as
    /// overlapping holes in the veil, which the map fills again.
    fn merge_coverage(&mut self) {
        if self.parts.len() < 2 || self.parts.iter().any(|r| r.coverage().is_empty()) {
            return;
        }
        let rings: Vec<Vec<&[PointE7]>> = self.parts.iter().map(|r| r.coverage()).collect();
        self.merged = merge_coverage(&rings, self.bbox());
    }

    /// The open regions, in order.
    pub fn regions(&self) -> &[Region] {
        &self.parts
    }

    /// How many stubs are linked to a node in another region.
    pub fn link_count(&self) -> usize {
        self.canon.len()
    }

    /// The highest speed of any edge, km/h (at least 1).
    pub fn max_speed_kmh(&self) -> f64 {
        self.max_speed_kmh
    }

    /// The highest speed of any unpaved edge, km/h (0 with none).
    pub fn max_unpaved_kmh(&self) -> f64 {
        self.max_unpaved_kmh
    }

    pub fn node_count(&self) -> usize {
        *self.node_base.last().unwrap_or(&0) as usize
    }

    pub fn edge_count(&self) -> usize {
        *self.edge_base.last().unwrap_or(&0) as usize
    }

    /// The region holding global id `id` of a kind with these `bases`,
    /// and the region's own id for it.
    #[inline]
    fn locate(bases: &[u32], id: u32) -> (usize, u32) {
        let r = bases.partition_point(|&b| b <= id).saturating_sub(1);
        let r = r.min(bases.len().saturating_sub(2));
        (r, id - bases[r])
    }

    /// The stub's twin, or the node itself.
    #[inline]
    fn canonical(&self, node: u32) -> u32 {
        if !self.is_linked(node) {
            node
        } else {
            self.canon.get(&node).copied().unwrap_or(node)
        }
    }

    /// Position of node `id`. Panics for an id out of range, like a slice.
    #[inline]
    pub fn node(&self, id: u32) -> PointE7 {
        if let [r] = self.parts.as_slice() {
            return r.nodes()[id as usize];
        }
        let (r, local) = Self::locate(&self.node_base, id);
        self.parts[r].nodes()[local as usize]
    }

    /// Edge `id` with global node and geometry ids (a stub's twin in place
    /// of the stub). Panics for an id out of range, like a slice.
    #[inline]
    pub fn edge(&self, id: u32) -> Edge {
        if let [r] = self.parts.as_slice() {
            return r.edges()[id as usize];
        }
        self.edge_multi(id)
    }

    fn edge_multi(&self, id: u32) -> Edge {
        let (r, local) = Self::locate(&self.edge_base, id);
        let mut e = self.parts[r].edges()[local as usize];
        e.tail = self.canonical(self.node_base[r] + e.tail);
        e.head = self.canonical(self.node_base[r] + e.head);
        e.geometry += self.geom_base[r];
        e
    }

    /// Edge `id`, or `None` when there is no such edge.
    #[inline]
    pub fn get_edge(&self, id: u32) -> Option<Edge> {
        ((id as usize) < self.edge_count()).then(|| self.edge(id))
    }

    /// The edges leaving node `node`.
    #[inline]
    pub fn out_edges(&self, node: u32) -> EdgeIds<'_> {
        let (r, local) = Self::locate(&self.node_base, node);
        EdgeIds {
            own: self.parts[r].out_edges(local),
            base: self.edge_base[r],
            list: None,
            extra: self.extras(&self.extra_out, node),
        }
    }

    /// The edges entering node `node`.
    #[inline]
    pub fn in_edges(&self, node: u32) -> EdgeIds<'_> {
        let (r, local) = Self::locate(&self.node_base, node);
        let list = self.parts[r].in_edges(local);
        EdgeIds {
            own: 0..list.len() as u32,
            base: self.edge_base[r],
            list: Some(list),
            extra: self.extras(&self.extra_in, node),
        }
    }

    fn extras<'a>(
        &'a self,
        map: &'a HashMap<u32, Vec<u32>>,
        node: u32,
    ) -> std::slice::Iter<'a, u32> {
        if !self.is_linked(node) {
            return [].iter();
        }
        map.get(&node).map_or([].iter(), |v| v.iter())
    }

    /// Curvature metrics of edge `id`.
    #[inline]
    pub fn curvature(&self, id: u32) -> CurvatureMetrics {
        if let [r] = self.parts.as_slice() {
            return r.curvature()[id as usize];
        }
        let (r, local) = Self::locate(&self.edge_base, id);
        self.parts[r].curvature()[local as usize]
    }

    /// The OSM way span of edge `id` (OSM ids are global already).
    #[inline]
    pub fn way_ref(&self, id: u32) -> WayRef {
        if let [r] = self.parts.as_slice() {
            return r.way_refs()[id as usize];
        }
        let (r, local) = Self::locate(&self.edge_base, id);
        self.parts[r].way_refs()[local as usize]
    }

    /// The shape of geometry `g`, both endpoints included.
    #[inline]
    pub fn geometry(&self, g: u32) -> &[PointE7] {
        if let [r] = self.parts.as_slice() {
            return r.geometry(g);
        }
        let (r, local) = Self::locate(&self.geom_base, g);
        self.parts[r].geometry(local)
    }

    /// The road number and name of geometry `g`, if the file has them.
    pub fn road_names(&self, g: u32) -> (Option<&str>, Option<&str>) {
        let (r, local) = Self::locate(&self.geom_base, g);
        let region = &self.parts[r];
        let GeometryName { road_ref, name } = region.geometry_name(local);
        let s = |i: u32| (i != NO_NAME).then(|| region.string(i)).flatten();
        (s(road_ref), s(name))
    }

    /// The named places of every region, each region's sorted by latitude,
    /// with the name.
    pub fn places(&self) -> impl Iterator<Item = (&Place, &str)> {
        self.parts.iter().flat_map(|r| {
            r.places()
                .iter()
                .filter_map(move |p| r.string(p.name).map(|n| (p, n)))
        })
    }

    /// The area the regions' roads cover as closed rings that never
    /// overlap (several regions' joined into one outline), empty when a
    /// region has none (format 1.0), whose box is all there is.
    pub fn coverage(&self) -> Vec<&[PointE7]> {
        if self.parts.iter().any(|r| r.coverage().is_empty()) {
            return Vec::new();
        }
        if let Some((offsets, points)) = &self.merged {
            return offsets
                .windows(2)
                .filter_map(|w| points.get(w[0] as usize..w[1] as usize))
                .collect();
        }
        self.parts.iter().flat_map(|r| r.coverage()).collect()
    }

    /// Whether `p` lies in the area the regions' roads cover (the joined
    /// outline, when there is one); `None` when a region has no coverage.
    pub fn covers(&self, p: PointE7) -> Option<bool> {
        if let Some((offsets, points)) = &self.merged {
            return Some(coverage_contains(offsets, points, p));
        }
        let mut any = false;
        for r in &self.parts {
            any |= r.covers(p)?;
        }
        Some(any)
    }

    /// The box around all regions.
    pub fn bbox(&self) -> BBoxE7 {
        let mut it = self.parts.iter().map(|r| r.info().bbox);
        let first = it.next().unwrap_or_default();
        it.fold(first, |a, b| BBoxE7 {
            min_lat: a.min_lat.min(b.min_lat),
            min_lon: a.min_lon.min(b.min_lon),
            max_lat: a.max_lat.max(b.max_lat),
            max_lon: a.max_lon.max(b.max_lon),
        })
    }

    /// The first global node, edge and geometry id of region `r`.
    pub(crate) fn bases(&self, r: usize) -> (u32, u32, u32) {
        (self.node_base[r], self.edge_base[r], self.geom_base[r])
    }
}

/// The highest speed of any edge and of any unpaved edge in `parts`,
/// km/h (at least 1, and 0 when there is no unpaved edge).
fn speed_bounds(parts: &[Region]) -> (f64, f64) {
    let (mut all, mut unpaved) = (1u8, 0u8);
    for e in parts.iter().flat_map(|r| r.edges().iter()) {
        all = all.max(e.speed_kmh);
        if !Surface::from_u8(e.surface).is_some_and(|s| s.is_paved()) {
            unpaved = unpaved.max(e.speed_kmh);
        }
    }
    (f64::from(all), f64::from(unpaved))
}

fn counts(r: &Region) -> (u32, u32, u32) {
    // The region format keeps all three below u32::MAX.
    let g = r.geometry_offsets().len().saturating_sub(1);
    (r.node_count() as u32, r.edge_count() as u32, g as u32)
}

#[cfg(test)]
mod tests;
