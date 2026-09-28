// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Downloaded regions (ADR-0008): the manifest that lists them, and the
//! installer that checks a downloaded file and puts it in place.
//!
//! Everything here is hostile input until checked: the manifest is parsed
//! strictly, file names are never read from it, the download's size and
//! SHA-256 are checked before anything is unpacked, unpacking stops at
//! the size the manifest promised, and the unpacked file must pass the
//! full region check before it replaces the installed one.

use std::fs::{self, File};
use std::io::{self, BufReader, Read, Write};
use std::path::Path;

use flate2::read::GzDecoder;
use serde::Deserialize;
use sha2::{Digest, Sha256};

use super::format::VERSION_MAJOR;
use crate::{CoreError, LatLon};

/// Largest manifest read.
pub const MAX_MANIFEST_BYTES: usize = 64 * 1024;
/// Most regions one manifest may offer.
pub const MAX_REGIONS: usize = 50;
/// Largest compressed region accepted.
pub const MAX_GZ_BYTES: u64 = 1 << 30;
/// Largest unpacked region accepted.
pub const MAX_REGION_BYTES: u64 = 2 << 30;
const MAX_ID_CHARS: usize = 32;
const MAX_NAME_CHARS: usize = 64;

/// A region offered for download.
#[derive(Debug, Clone, PartialEq)]
pub struct RegionOffer {
    /// Short name, `[a-z0-9-]`, 1–32 characters; the file names are made
    /// from it.
    pub id: String,
    /// Name to show, e.g. "Sweden".
    pub name: String,
    pub gz_bytes: u64,
    pub gz_sha256: [u8; 32],
    pub region_bytes: u64,
    /// Timestamp of the OSM data, seconds since the Unix epoch.
    pub osm_timestamp: i64,
    pub south_west: LatLon,
    pub north_east: LatLon,
}

impl RegionOffer {
    /// The compressed file's name in the release: `<id>-v<major>.region.gz`.
    pub fn file_name(&self) -> String {
        format!("{}-v{VERSION_MAJOR}.region.gz", self.id)
    }
}

/// The manifest's name for the region format this build reads.
pub fn manifest_file_name() -> String {
    format!("regions-v{VERSION_MAJOR}.json")
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawManifest {
    format_major: u16,
    regions: Vec<RawOffer>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawOffer {
    id: String,
    name: String,
    gz_bytes: u64,
    gz_sha256: String,
    region_bytes: u64,
    osm_timestamp: i64,
    south_west: [f64; 2],
    north_east: [f64; 2],
}

fn bad(what: impl std::fmt::Display) -> CoreError {
    CoreError::InvalidArgument(format!("region manifest: {what}"))
}

/// Reads a downloaded manifest: the regions offered for the region format
/// this build reads. Anything unexpected refuses the whole manifest.
pub fn parse_manifest(bytes: &[u8]) -> Result<Vec<RegionOffer>, CoreError> {
    if bytes.len() > MAX_MANIFEST_BYTES {
        return Err(bad("too large"));
    }
    let raw: RawManifest = serde_json::from_slice(bytes).map_err(bad)?;
    if raw.format_major != VERSION_MAJOR {
        return Err(bad(format_args!(
            "format {} (this app reads {VERSION_MAJOR})",
            raw.format_major
        )));
    }
    if raw.regions.len() > MAX_REGIONS {
        return Err(bad("too many regions"));
    }
    let mut offers: Vec<RegionOffer> = Vec::with_capacity(raw.regions.len());
    for r in raw.regions {
        let offer = check_offer(r)?;
        if offers.iter().any(|o| o.id == offer.id) {
            return Err(bad(format_args!("region {} listed twice", offer.id)));
        }
        offers.push(offer);
    }
    Ok(offers)
}

fn check_offer(r: RawOffer) -> Result<RegionOffer, CoreError> {
    let id_ok = !r.id.is_empty()
        && r.id.len() <= MAX_ID_CHARS
        && r.id
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
    if !id_ok {
        return Err(bad("invalid region id"));
    }
    let id = r.id;
    let name_ok = !r.name.trim().is_empty()
        && r.name.chars().count() <= MAX_NAME_CHARS
        && !r.name.chars().any(char::is_control);
    if !name_ok {
        return Err(bad(format_args!("{id}: invalid name")));
    }
    if !(1..=MAX_GZ_BYTES).contains(&r.gz_bytes) {
        return Err(bad(format_args!("{id}: invalid compressed size")));
    }
    if !(1..=MAX_REGION_BYTES).contains(&r.region_bytes) {
        return Err(bad(format_args!("{id}: invalid region size")));
    }
    let gz_sha256 =
        parse_sha256(&r.gz_sha256).ok_or_else(|| bad(format_args!("{id}: invalid SHA-256")))?;
    if r.osm_timestamp < 0 {
        return Err(bad(format_args!("{id}: invalid OSM timestamp")));
    }
    let point = |[lat, lon]: [f64; 2]| {
        LatLon::new(lat, lon).map_err(|_| bad(format_args!("{id}: invalid bounding box")))
    };
    let (south_west, north_east) = (point(r.south_west)?, point(r.north_east)?);
    if south_west.lat >= north_east.lat || south_west.lon >= north_east.lon {
        return Err(bad(format_args!("{id}: invalid bounding box")));
    }
    Ok(RegionOffer {
        id,
        name: r.name,
        gz_bytes: r.gz_bytes,
        gz_sha256,
        region_bytes: r.region_bytes,
        osm_timestamp: r.osm_timestamp,
        south_west,
        north_east,
    })
}

/// 64 lowercase or uppercase hex digits.
pub fn parse_sha256(hex: &str) -> Option<[u8; 32]> {
    let bytes = hex.as_bytes();
    if bytes.len() != 64 {
        return None;
    }
    let digit = |b: u8| char::from(b).to_digit(16);
    let mut out = [0u8; 32];
    for (o, &[hi, lo]) in out.iter_mut().zip(bytes.as_chunks::<2>().0) {
        let (hi, lo) = (digit(hi)?, digit(lo)?);
        *o = u8::try_from(hi * 16 + lo).ok()?;
    }
    Some(out)
}

fn install_err(what: impl std::fmt::Display) -> CoreError {
    CoreError::Region(format!("downloaded region: {what}"))
}

fn io_err(e: io::Error) -> CoreError {
    CoreError::Storage(e.to_string())
}

/// SHA-256 of a file, read in pieces.
pub fn sha256_file(path: &Path) -> Result<[u8; 32], CoreError> {
    let mut hasher = Sha256::new();
    let mut input = File::open(path).map_err(io_err)?;
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = input.read(&mut buf).map_err(io_err)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().into())
}

/// Installs a downloaded region: checks `gz_path` against `offer` (size,
/// then SHA-256), unpacks it next to `target` refusing more than
/// `offer.region_bytes`, verifies the unpacked file (checksums and
/// structure) and only then renames it over `target`. On any failure the
/// installed region is left as it was and the temporary file is removed.
/// `gz_path` is left for the caller to delete. Returns the installed
/// file's [`fingerprint`](super::fingerprint), for
/// [`Region::open_fingerprinted`](super::Region::open_fingerprinted).
pub fn install_region(
    offer: &RegionOffer,
    gz_path: &Path,
    target: &Path,
) -> Result<[u8; 32], CoreError> {
    let size = fs::metadata(gz_path).map_err(io_err)?.len();
    if size != offer.gz_bytes {
        return Err(install_err(format_args!(
            "{size} bytes, expected {}",
            offer.gz_bytes
        )));
    }
    if sha256_file(gz_path)? != offer.gz_sha256 {
        return Err(install_err("SHA-256 does not match the manifest"));
    }

    let file_name = target
        .file_name()
        .ok_or_else(|| CoreError::InvalidArgument("target has no file name".into()))?;
    let mut tmp_name = file_name.to_os_string();
    tmp_name.push(".tmp");
    let tmp = target.with_file_name(tmp_name);
    let result = unpack(offer, gz_path, &tmp)
        .and_then(|()| super::verify_file(&tmp))
        .and_then(|()| super::fingerprint(&tmp))
        .and_then(|fp| fs::rename(&tmp, target).map_err(io_err).map(|()| fp));
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// Unpacks `gz_path` into `out`: exactly `offer.region_bytes`, with the
/// gzip trailer's checksum checked.
fn unpack(offer: &RegionOffer, gz_path: &Path, out: &Path) -> Result<(), CoreError> {
    let input = BufReader::new(File::open(gz_path).map_err(io_err)?);
    // One byte more than promised shows a file that is too long without
    // unpacking the rest of it.
    let mut decoder = GzDecoder::new(input).take(offer.region_bytes.saturating_add(1));
    let mut file = File::create(out).map_err(io_err)?;
    let written = io::copy(&mut decoder, &mut file).map_err(install_err)?;
    if written != offer.region_bytes {
        return Err(install_err(format_args!(
            "unpacks to {}{written} bytes, expected {}",
            if written > offer.region_bytes {
                "more than "
            } else {
                ""
            },
            offer.region_bytes
        )));
    }
    // Reading on to the end checks the gzip trailer (CRC32 and length).
    let mut rest = [0u8; 1];
    if decoder.into_inner().read(&mut rest).map_err(install_err)? != 0 {
        return Err(install_err("unpacks to more bytes than expected"));
    }
    file.flush().map_err(io_err)?;
    file.sync_all().map_err(io_err)?;
    Ok(())
}

#[cfg(test)]
mod tests;
