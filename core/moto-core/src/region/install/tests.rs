// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use std::path::PathBuf;

use flate2::Compression;
use flate2::write::GzEncoder;

use super::*;
use crate::fixture;

/// A fresh directory for one test.
fn dir(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("moto-install-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn gzip(bytes: &[u8]) -> Vec<u8> {
    let mut e = GzEncoder::new(Vec::new(), Compression::best());
    e.write_all(bytes).unwrap();
    e.finish().unwrap()
}

fn region_bytes() -> Vec<u8> {
    fixture::region().to_bytes().unwrap()
}

/// An offer matching `gz` for a region of `raw` bytes.
fn offer_for(gz: &[u8], raw: &[u8]) -> RegionOffer {
    RegionOffer {
        id: "sweden".into(),
        name: "Sweden".into(),
        gz_bytes: gz.len() as u64,
        gz_sha256: Sha256::digest(gz).into(),
        region_bytes: raw.len() as u64,
        osm_timestamp: 1_790_471_426,
        south_west: LatLon {
            lat: 55.0,
            lon: 10.5,
        },
        north_east: LatLon {
            lat: 69.2,
            lon: 24.3,
        },
    }
}

fn manifest(regions: &str) -> String {
    format!(r#"{{"format_major": {VERSION_MAJOR}, "regions": [{regions}]}}"#)
}

fn sweden(sha: &str) -> String {
    format!(
        r#"{{"id": "sweden", "name": "Sweden", "gz_bytes": 155056303, "gz_sha256": "{sha}",
            "region_bytes": 413483008, "osm_timestamp": 1790471426,
            "south_west": [55.0, 10.5], "north_east": [69.2, 24.3]}}"#
    )
}

#[test]
fn reads_a_manifest() {
    let sha = "ab".repeat(32);
    let offers = parse_manifest(manifest(&sweden(&sha)).as_bytes()).unwrap();
    assert_eq!(offers.len(), 1);
    let o = &offers[0];
    assert_eq!((o.id.as_str(), o.name.as_str()), ("sweden", "Sweden"));
    assert_eq!((o.gz_bytes, o.region_bytes), (155_056_303, 413_483_008));
    assert_eq!(o.gz_sha256, [0xab; 32]);
    assert_eq!(o.file_name(), format!("sweden-v{VERSION_MAJOR}.region.gz"));
    assert_eq!(
        manifest_file_name(),
        format!("regions-v{VERSION_MAJOR}.json")
    );
    // Upper-case hex is fine too; no regions is an empty list.
    assert!(parse_manifest(manifest(&sweden(&"AB".repeat(32))).as_bytes()).is_ok());
    assert_eq!(parse_manifest(manifest("").as_bytes()).unwrap(), []);
}

#[test]
fn refuses_a_bad_manifest() {
    let sha = "ab".repeat(32);
    let good = sweden(&sha);
    let cases: Vec<(String, &str)> = vec![
        ("not json".into(), "not JSON"),
        (
            format!(
                r#"{{"format_major": {}, "regions": []}}"#,
                VERSION_MAJOR + 1
            ),
            "another format",
        ),
        (
            format!(r#"{{"format_major": {VERSION_MAJOR}, "regions": [], "extra": 1}}"#),
            "unknown field",
        ),
        (manifest(&format!("{good}, {good}")), "listed twice"),
        (
            manifest(&good.replace("\"sweden\"", "\"../sweden\"")),
            "path in id",
        ),
        (
            manifest(&good.replace("\"sweden\"", "\"Sweden\"")),
            "upper case id",
        ),
        (manifest(&good.replace("\"sweden\"", "\"\"")), "empty id"),
        (
            manifest(&good.replace("\"sweden\"", &format!("\"{}\"", "a".repeat(33)))),
            "long id",
        ),
        (
            manifest(&good.replace("\"Sweden\"", "\"Swe\\nden\"")),
            "control in name",
        ),
        (
            manifest(&good.replace("\"Sweden\"", "\"  \"")),
            "blank name",
        ),
        (manifest(&good.replace("155056303", "0")), "empty file"),
        (
            manifest(&good.replace("155056303", "99999999999")),
            "huge file",
        ),
        (
            manifest(&good.replace("413483008", "99999999999")),
            "huge region",
        ),
        (manifest(&good.replace("155056303", "-1")), "negative size"),
        (manifest(&sweden(&"ab".repeat(31))), "short SHA-256"),
        (manifest(&sweden(&"zz".repeat(32))), "not hex"),
        (manifest(&good.replace("1790471426", "-5")), "negative time"),
        (
            manifest(&good.replace("[55.0, 10.5]", "[95.0, 10.5]")),
            "bad corner",
        ),
        (
            manifest(&good.replace("[55.0, 10.5]", "[70.0, 10.5]")),
            "corners swapped",
        ),
        (
            manifest(&good.replace("\"id\": \"sweden\", ", "")),
            "missing id",
        ),
    ];
    for (json, why) in cases {
        let r = parse_manifest(json.as_bytes());
        assert!(
            matches!(r, Err(CoreError::InvalidArgument(_))),
            "{why}: {r:?}"
        );
    }
    let too_many: Vec<String> = (0..=MAX_REGIONS)
        .map(|i| good.replace("\"sweden\"", &format!("\"r{i}\"")))
        .collect();
    assert!(parse_manifest(manifest(&too_many.join(",")).as_bytes()).is_err());
    let padded = format!("{}{}", manifest(&good), " ".repeat(MAX_MANIFEST_BYTES));
    assert!(parse_manifest(padded.as_bytes()).is_err());
}

#[test]
fn garbage_manifests_never_panic() {
    let base = manifest(&sweden(&"ab".repeat(32))).into_bytes();
    for i in 0..base.len() {
        for b in [0u8, b'"', b'{', b']', 0xff] {
            let mut m = base.clone();
            m[i] = b;
            let _ = parse_manifest(&m);
        }
        let _ = parse_manifest(&base[..i]);
    }
}

#[test]
fn installs_a_checked_region() {
    let d = dir("ok");
    let raw = region_bytes();
    let gz = gzip(&raw);
    let offer = offer_for(&gz, &raw);
    let (src, target) = (d.join("dl.gz"), d.join("sweden.region"));
    fs::write(&src, &gz).unwrap();
    fs::write(&target, b"the old region").unwrap();
    let fp = install_region(&offer, &src, &target).unwrap();
    assert_eq!(fs::read(&target).unwrap(), raw);
    assert!(!d.join("sweden.region.tmp").exists());
    super::super::Region::open(&target).unwrap();
    assert_eq!(fp, super::super::fingerprint(&target).unwrap());
    super::super::Region::open_fingerprinted(&target, &fp).unwrap();
}

#[test]
fn a_bad_download_leaves_the_installed_region() {
    let raw = region_bytes();
    let gz = gzip(&raw);
    let good = offer_for(&gz, &raw);

    // A region that unpacks fine but fails its own checks.
    let mut corrupt_raw = raw.clone();
    let (_, table) = super::super::parse_header(&raw).unwrap();
    let inside = usize::try_from(table[0].offset).unwrap() + 3;
    corrupt_raw[inside] ^= 0xff;
    let corrupt_gz = gzip(&corrupt_raw);

    let mut flipped = gz.clone();
    let mid = flipped.len() / 2;
    flipped[mid] ^= 0x55;
    let mut truncated = gz.clone();
    truncated.truncate(gz.len() - 10);
    let bomb_raw = vec![0u8; raw.len() * 4];
    let bomb = gzip(&bomb_raw);

    let cases: Vec<(&str, Vec<u8>, RegionOffer)> = vec![
        ("wrong size", truncated.clone(), good.clone()),
        (
            "wrong SHA-256",
            flipped.clone(),
            RegionOffer {
                gz_bytes: flipped.len() as u64,
                ..good.clone()
            },
        ),
        // The manifest agrees with the file, but the file is broken.
        ("broken gzip", flipped.clone(), offer_for(&flipped, &raw)),
        ("cut gzip", truncated.clone(), offer_for(&truncated, &raw)),
        ("unpacks too long", bomb.clone(), offer_for(&bomb, &raw)),
        (
            "unpacks too short",
            gz.clone(),
            RegionOffer {
                region_bytes: raw.len() as u64 + 1,
                ..good.clone()
            },
        ),
        (
            "fails the region check",
            corrupt_gz.clone(),
            offer_for(&corrupt_gz, &corrupt_raw),
        ),
        ("not gzip", raw.clone(), offer_for(&raw, &raw)),
    ];
    for (why, file, offer) in cases {
        let d = dir("bad");
        let (src, target) = (d.join("dl.gz"), d.join("sweden.region"));
        fs::write(&src, &file).unwrap();
        fs::write(&target, b"the old region").unwrap();
        let r = install_region(&offer, &src, &target);
        assert!(matches!(r, Err(CoreError::Region(_))), "{why}: {r:?}");
        assert_eq!(fs::read(&target).unwrap(), b"the old region", "{why}");
        assert!(!d.join("sweden.region.tmp").exists(), "{why}");
    }
}

#[test]
fn a_missing_download_is_a_storage_error() {
    let d = dir("missing");
    let raw = region_bytes();
    let offer = offer_for(&gzip(&raw), &raw);
    let r = install_region(&offer, &d.join("none.gz"), &d.join("sweden.region"));
    assert!(matches!(r, Err(CoreError::Storage(_))), "{r:?}");
}
