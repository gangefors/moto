// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Which OSM ways a motorcycle may use, and their edge attributes.

use moto_core::region::format::{RoadClass, Surface, edge_flags};

/// Travel direction allowed on a way, relative to its node order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Oneway {
    No,
    Forward,
    Backward,
}

/// Edge attributes derived from a way's tags.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WayAttrs {
    pub class: RoadClass,
    pub speed_kmh: u8,
    pub surface: Surface,
    /// [`edge_flags`] bits, without `REVERSED`.
    pub flags: u8,
    pub oneway: Oneway,
}

/// Returns the attributes of a motorcycle-routable way, or `None`.
pub fn classify(tags: &[(&str, &str)]) -> Option<WayAttrs> {
    let tag = |k: &str| tags.iter().find(|(key, _)| *key == k).map(|(_, v)| *v);
    let yes = |v: Option<&str>| matches!(v, Some("yes" | "designated" | "permissive"));

    if tag("area") == Some("yes") {
        return None;
    }
    let mut flags = 0u8;
    let (class, link) = if tag("route") == Some("ferry") {
        // Only ferries that explicitly take motor vehicles.
        let motor = ["motorcycle", "motor_vehicle", "motorcar"];
        if !motor.iter().any(|k| yes(tag(k))) {
            return None;
        }
        flags |= edge_flags::FERRY;
        (RoadClass::Ferry, false)
    } else {
        let (class, link) = match tag("highway")? {
            "motorway" => (RoadClass::Motorway, false),
            "motorway_link" => (RoadClass::Motorway, true),
            "trunk" => (RoadClass::Trunk, false),
            "trunk_link" => (RoadClass::Trunk, true),
            "primary" => (RoadClass::Primary, false),
            "primary_link" => (RoadClass::Primary, true),
            "secondary" => (RoadClass::Secondary, false),
            "secondary_link" => (RoadClass::Secondary, true),
            "tertiary" => (RoadClass::Tertiary, false),
            "tertiary_link" => (RoadClass::Tertiary, true),
            "unclassified" => (RoadClass::Unclassified, false),
            "residential" => (RoadClass::Residential, false),
            "living_street" => (RoadClass::LivingStreet, false),
            "service" => (RoadClass::Service, false),
            "track" => (RoadClass::Track, false),
            _ => return None,
        };
        if class == RoadClass::Service
            && matches!(
                tag("service"),
                Some(
                    "parking_aisle" | "driveway" | "drive-through" | "emergency_access" | "parking"
                )
            )
        {
            return None;
        }
        // Tracks are mostly closed to motor traffic unless tagged open.
        if class == RoadClass::Track && !(yes(tag("motorcycle")) || yes(tag("motor_vehicle"))) {
            return None;
        }
        (class, link)
    };
    if link {
        flags |= edge_flags::LINK;
    }

    // Most specific access tag wins.
    let access = ["motorcycle", "motor_vehicle", "vehicle", "access"]
        .iter()
        .find_map(|k| tag(k));
    match access {
        None | Some("yes" | "designated" | "permissive" | "official") => {}
        Some("destination" | "customers") => flags |= edge_flags::DESTINATION,
        Some(_) => return None,
    }
    if class == RoadClass::Ferry {
        flags &= !edge_flags::DESTINATION;
    }

    let roundabout = matches!(tag("junction"), Some("roundabout" | "circular"));
    if roundabout {
        flags |= edge_flags::ROUNDABOUT;
    }
    if tag("toll") == Some("yes") {
        flags |= edge_flags::TOLL;
    }

    let oneway = match tag("oneway:motorcycle").or(tag("oneway")) {
        Some("yes" | "true" | "1") => Oneway::Forward,
        Some("-1" | "reverse") => Oneway::Backward,
        Some("no" | "false" | "0") => Oneway::No,
        Some("reversible" | "alternating") => return None,
        _ if roundabout || (class == RoadClass::Motorway) => Oneway::Forward,
        _ => Oneway::No,
    };

    let speed_kmh = tag("maxspeed")
        .and_then(parse_maxspeed)
        .unwrap_or_else(|| default_speed(class, link));

    Some(WayAttrs {
        class,
        speed_kmh,
        surface: surface(tag("surface")),
        flags,
        oneway,
    })
}

/// Default speeds in km/h, from Swedish general limits.
fn default_speed(class: RoadClass, link: bool) -> u8 {
    if link {
        return 60;
    }
    match class {
        RoadClass::Motorway => 110,
        RoadClass::Trunk => 90,
        RoadClass::Primary => 80,
        RoadClass::Secondary => 70,
        RoadClass::Tertiary => 60,
        RoadClass::Unclassified => 50,
        RoadClass::Residential => 40,
        RoadClass::LivingStreet => 7,
        RoadClass::Service => 20,
        RoadClass::Track => 20,
        RoadClass::Ferry => 15,
    }
}

/// Parses `maxspeed` values like `70`, `30 mph`, `SE:rural`.
fn parse_maxspeed(v: &str) -> Option<u8> {
    let kmh = match v.trim() {
        "SE:urban" => 50.0,
        "SE:rural" => 70.0,
        "SE:motorway" => 110.0,
        "walk" | "SE:walk" => 7.0,
        v => {
            if let Some(mph) = v.strip_suffix("mph") {
                mph.trim().parse::<f64>().ok()? * 1.609_344
            } else {
                v.trim_end_matches("km/h").trim().parse::<f64>().ok()?
            }
        }
    };
    (kmh.is_finite() && kmh >= 1.0).then(|| kmh.round().min(250.0) as u8)
}

fn surface(v: Option<&str>) -> Surface {
    match v {
        Some("asphalt" | "chipseal") => Surface::Asphalt,
        Some("concrete" | "concrete:plates" | "concrete:lanes") => Surface::Concrete,
        Some("paved" | "metal") => Surface::Paved,
        Some("sett" | "cobblestone" | "unhewn_cobblestone" | "paving_stones" | "bricks") => {
            Surface::Sett
        }
        Some("compacted") => Surface::Compacted,
        Some("gravel" | "fine_gravel" | "pebblestone" | "unpaved") => Surface::Gravel,
        Some(
            "dirt" | "ground" | "earth" | "grass" | "sand" | "mud" | "woodchips" | "grass_paver",
        ) => Surface::Dirt,
        _ => Surface::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(tags: &[(&str, &str)]) -> Option<WayAttrs> {
        classify(tags)
    }

    #[test]
    fn keeps_ordinary_roads_with_defaults() {
        let a = c(&[("highway", "secondary")]).unwrap();
        assert_eq!(a.class, RoadClass::Secondary);
        assert_eq!(a.speed_kmh, 70);
        assert_eq!(a.surface, Surface::Unknown);
        assert_eq!(a.oneway, Oneway::No);
        assert_eq!(a.flags, 0);
    }

    #[test]
    fn drops_non_roads_and_closed_roads() {
        for tags in [
            &[("highway", "footway")][..],
            &[("highway", "cycleway")],
            &[("highway", "path")],
            &[("highway", "construction")],
            &[("building", "yes")],
            &[("highway", "residential"), ("access", "private")],
            &[("highway", "residential"), ("motor_vehicle", "no")],
            &[("highway", "tertiary"), ("motorcycle", "no")],
            &[("highway", "service"), ("service", "parking_aisle")],
            &[("highway", "service"), ("service", "driveway")],
            &[("highway", "track")],
            &[("highway", "pedestrian")],
            &[("highway", "residential"), ("area", "yes")],
            &[("highway", "primary"), ("oneway", "reversible")],
            &[("route", "ferry")],
        ] {
            assert_eq!(c(tags), None, "{tags:?}");
        }
    }

    #[test]
    fn specific_access_overrides_general() {
        assert!(
            c(&[
                ("highway", "residential"),
                ("access", "no"),
                ("motorcycle", "yes")
            ])
            .is_some()
        );
        assert!(c(&[("highway", "track"), ("motor_vehicle", "yes")]).is_some());
        let d = c(&[("highway", "service"), ("access", "destination")]).unwrap();
        assert_eq!(d.flags & edge_flags::DESTINATION, edge_flags::DESTINATION);
    }

    #[test]
    fn oneway_rules() {
        let one = |tags: &[(&str, &str)]| c(tags).unwrap().oneway;
        assert_eq!(
            one(&[("highway", "primary"), ("oneway", "yes")]),
            Oneway::Forward
        );
        assert_eq!(
            one(&[("highway", "primary"), ("oneway", "-1")]),
            Oneway::Backward
        );
        assert_eq!(one(&[("highway", "motorway")]), Oneway::Forward);
        assert_eq!(one(&[("highway", "motorway_link")]), Oneway::Forward);
        assert_eq!(
            one(&[("highway", "motorway"), ("oneway", "no")]),
            Oneway::No
        );
        let r = c(&[("highway", "tertiary"), ("junction", "roundabout")]).unwrap();
        assert_eq!(r.oneway, Oneway::Forward);
        assert_eq!(r.flags & edge_flags::ROUNDABOUT, edge_flags::ROUNDABOUT);
        assert_eq!(
            one(&[
                ("highway", "residential"),
                ("oneway", "yes"),
                ("oneway:motorcycle", "no")
            ]),
            Oneway::No
        );
    }

    #[test]
    fn links_ferries_and_tolls_are_flagged() {
        let l = c(&[("highway", "trunk_link")]).unwrap();
        assert_eq!(
            (l.class, l.flags, l.speed_kmh),
            (RoadClass::Trunk, edge_flags::LINK, 60)
        );
        let f = c(&[("route", "ferry"), ("motor_vehicle", "yes")]).unwrap();
        assert_eq!((f.class, f.flags), (RoadClass::Ferry, edge_flags::FERRY));
        let t = c(&[("highway", "primary"), ("toll", "yes")]).unwrap();
        assert_eq!(t.flags, edge_flags::TOLL);
    }

    #[test]
    fn parses_maxspeed() {
        assert_eq!(parse_maxspeed("70"), Some(70));
        assert_eq!(parse_maxspeed("30 mph"), Some(48));
        assert_eq!(parse_maxspeed("SE:rural"), Some(70));
        assert_eq!(parse_maxspeed("SE:urban"), Some(50));
        assert_eq!(parse_maxspeed("50 km/h"), Some(50));
        assert_eq!(parse_maxspeed("none"), None);
        assert_eq!(parse_maxspeed("0"), None);
        assert_eq!(parse_maxspeed("signals"), None);
        let s = c(&[("highway", "tertiary"), ("maxspeed", "80")]).unwrap();
        assert_eq!(s.speed_kmh, 80);
    }

    #[test]
    fn maps_surfaces() {
        assert_eq!(surface(Some("asphalt")), Surface::Asphalt);
        assert_eq!(surface(Some("gravel")), Surface::Gravel);
        assert_eq!(surface(Some("unpaved")), Surface::Gravel);
        assert_eq!(surface(Some("cobblestone")), Surface::Sett);
        assert_eq!(surface(Some("dirt")), Surface::Dirt);
        assert_eq!(surface(Some("weird")), Surface::Unknown);
        assert_eq!(surface(None), Surface::Unknown);
    }
}
