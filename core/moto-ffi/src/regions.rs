// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Downloaded regions (ADR-0008): what the manifest offers, and installing
//! a downloaded file. The app fetches the bytes; every check is here.

use std::path::Path;

use moto_core::region::install as core;

use crate::{LatLon, MotoError};

/// A region offered for download.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RegionOffer {
    /// Short name, `[a-z0-9-]`; safe to use in file names.
    pub id: String,
    /// Name to show, e.g. "Sweden".
    pub name: String,
    /// The compressed file's name in the release, for this app's format.
    pub file_name: String,
    /// Size of the download.
    pub gz_bytes: u64,
    /// Size once installed.
    pub region_bytes: u64,
    /// Timestamp of the OSM data, seconds since the Unix epoch.
    pub osm_timestamp: i64,
    pub south_west: LatLon,
    pub north_east: LatLon,
}

impl From<core::RegionOffer> for RegionOffer {
    fn from(o: core::RegionOffer) -> Self {
        Self {
            file_name: o.file_name(),
            id: o.id,
            name: o.name,
            gz_bytes: o.gz_bytes,
            region_bytes: o.region_bytes,
            osm_timestamp: o.osm_timestamp,
            south_west: o.south_west.into(),
            north_east: o.north_east.into(),
        }
    }
}

/// The name of the manifest listing the regions this app can read.
#[uniffi::export]
pub fn region_manifest_file_name() -> String {
    core::manifest_file_name()
}

/// The regions a downloaded manifest offers; refuses the whole manifest if
/// anything in it is unexpected.
#[uniffi::export]
pub fn parse_region_manifest(manifest: Vec<u8>) -> Result<Vec<RegionOffer>, MotoError> {
    Ok(core::parse_manifest(&manifest)?
        .into_iter()
        .map(Into::into)
        .collect())
}

/// Installs region `id` of `manifest` from the downloaded file at
/// `gz_path` as `target_path`: size and SHA-256 checked against the
/// manifest before unpacking, unpacked to at most the promised size,
/// verified, then moved into place. The manifest is read again here, so
/// nothing about the download is taken from the caller but paths in its
/// own storage. Returns the installed file's fingerprint (as from
/// `region_fingerprint`), to keep for `Engine.open_fingerprinted`.
#[uniffi::export]
pub fn install_region(
    manifest: Vec<u8>,
    id: String,
    gz_path: String,
    target_path: String,
) -> Result<String, MotoError> {
    let offer = core::parse_manifest(&manifest)?
        .into_iter()
        .find(|o| o.id == id)
        .ok_or_else(|| {
            moto_core::CoreError::InvalidArgument(format!("no region {id} in the manifest"))
        })?;
    let fp = core::install_region(&offer, Path::new(&gz_path), Path::new(&target_path))?;
    Ok(hex(&fp))
}

/// The fingerprint of a region file, 64 hex digits. Take it only of a file
/// that just opened with `Engine.open` (fully checked), and keep it in
/// app-private storage: `Engine.open_fingerprinted` trusts that a file
/// matching it is the one that was checked.
#[uniffi::export]
pub fn region_fingerprint(path: String) -> Result<String, MotoError> {
    Ok(hex(&moto_core::region::fingerprint(path)?))
}

fn hex(bytes: &[u8; 32]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Reads a fingerprint made by [`hex`].
pub(crate) fn parse_fingerprint(s: &str) -> Result<[u8; 32], MotoError> {
    Ok(core::parse_sha256(s).ok_or_else(|| {
        moto_core::CoreError::InvalidArgument("fingerprint is not 64 hex digits".into())
    })?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offers_and_refuses_across_the_ffi() {
        let json = format!(
            r#"{{"format_major": {}, "regions": [{{"id": "sweden", "name": "Sweden",
                "gz_bytes": 10, "gz_sha256": "{}", "region_bytes": 20,
                "osm_timestamp": 1, "south_west": [55.0, 10.5], "north_east": [69.2, 24.3]}}]}}"#,
            moto_core::region::format::VERSION_MAJOR,
            "00".repeat(32)
        );
        let offers = parse_region_manifest(json.clone().into_bytes()).unwrap();
        assert_eq!(offers[0].id, "sweden");
        assert!(offers[0].file_name.starts_with("sweden-v"));
        assert!(region_manifest_file_name().starts_with("regions-v"));
        assert!(matches!(
            parse_region_manifest(b"{}".to_vec()),
            Err(MotoError::InvalidInput { .. })
        ));
        let dir = std::env::temp_dir();
        let missing = dir.join(format!("moto-ffi-none-{}.gz", std::process::id()));
        let target = dir.join(format!("moto-ffi-none-{}.region", std::process::id()));
        let path = |p: &std::path::Path| p.to_string_lossy().into_owned();
        assert!(matches!(
            install_region(
                json.clone().into_bytes(),
                "norway".into(),
                path(&missing),
                path(&target)
            ),
            Err(MotoError::InvalidInput { .. })
        ));
        assert!(matches!(
            install_region(
                json.into_bytes(),
                "sweden".into(),
                path(&missing),
                path(&target)
            ),
            Err(MotoError::Storage { .. })
        ));
    }
}
