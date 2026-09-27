// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `--manifest`: writes the manifest that lists downloadable regions
//! (ADR-0008) for their gzip-compressed files, then proves it the way the
//! app will use it: the core reads it back and installs every region from
//! its compressed file.

use std::fs;
use std::path::{Path, PathBuf};

use moto_core::region::Region;
use moto_core::region::format::VERSION_MAJOR;
use moto_core::region::install::{install_region, parse_manifest, sha256_file};
use serde_json::{Value, json};

/// One region to list: id, display name, the region file and its
/// gzip-compressed copy (the file published).
pub struct Entry {
    pub id: String,
    pub name: String,
    pub region: PathBuf,
    pub gz: PathBuf,
}

/// Parses `id name region gz` groups from the command line.
pub fn entries(args: &[String]) -> Result<Vec<Entry>, String> {
    let groups = args.as_chunks::<4>();
    if args.is_empty() || !groups.1.is_empty() {
        return Err(
            "--manifest needs groups of: <id> <name> <file.region> <file.region.gz>".into(),
        );
    }
    Ok(groups
        .0
        .iter()
        .map(|[id, name, region, gz]| Entry {
            id: id.clone(),
            name: name.clone(),
            region: region.into(),
            gz: gz.into(),
        })
        .collect())
}

fn offer(e: &Entry) -> Result<Value, String> {
    let region = Region::open(&e.region).map_err(|err| format!("{}: {err}", e.region.display()))?;
    let info = region.info();
    let b = info.bbox;
    let deg = |v: i32| f64::from(v) / 1e7;
    let sha: String = sha256_file(&e.gz)
        .map_err(|err| format!("{}: {err}", e.gz.display()))?
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let len = |p: &Path| {
        fs::metadata(p)
            .map(|m| m.len())
            .map_err(|err| format!("{}: {err}", p.display()))
    };
    Ok(json!({
        "id": e.id,
        "name": e.name,
        "gz_bytes": len(&e.gz)?,
        "gz_sha256": sha,
        "region_bytes": len(&e.region)?,
        "osm_timestamp": info.osm_timestamp,
        "south_west": [deg(b.min_lat), deg(b.min_lon)],
        "north_east": [deg(b.max_lat), deg(b.max_lon)],
    }))
}

/// Writes the manifest for `entries` to `out`, then reads it back with
/// the core and installs every region from its compressed file into
/// `scratch`, comparing the result with the original.
pub fn run(out: &Path, entries: &[Entry], scratch: &Path) -> Result<(), String> {
    let regions = entries.iter().map(offer).collect::<Result<Vec<_>, _>>()?;
    let manifest = json!({"format_major": VERSION_MAJOR, "regions": regions});
    let text = serde_json::to_string_pretty(&manifest).map_err(|e| e.to_string())? + "\n";
    let offers = parse_manifest(text.as_bytes()).map_err(|e| format!("manifest refused: {e}"))?;
    fs::create_dir_all(scratch).map_err(|e| e.to_string())?;
    for (o, e) in offers.iter().zip(entries) {
        let target = scratch.join(format!("{}.region", o.id));
        install_region(o, &e.gz, &target).map_err(|err| format!("{}: {err}", o.id))?;
        let same = fs::read(&target).map_err(|e| e.to_string())?
            == fs::read(&e.region).map_err(|e| e.to_string())?;
        fs::remove_file(&target).map_err(|e| e.to_string())?;
        if !same {
            return Err(format!("{}: installs to a different file", o.id));
        }
        println!(
            "{:<12} {} ({:.1} MiB to download, {:.1} MiB installed) as {}",
            o.id,
            o.name,
            o.gz_bytes as f64 / 1_048_576.0,
            o.region_bytes as f64 / 1_048_576.0,
            o.file_name()
        );
    }
    fs::write(out, text).map_err(|e| format!("{}: {e}", out.display()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use flate2::Compression;
    use flate2::write::GzEncoder;

    use super::*;
    use crate::test_support::built_fixture;

    fn gzip_to(src: &Path, dst: &Path) {
        let mut e = GzEncoder::new(Vec::new(), Compression::best());
        e.write_all(&fs::read(src).unwrap()).unwrap();
        fs::write(dst, e.finish().unwrap()).unwrap();
    }

    #[test]
    fn writes_a_manifest_the_core_installs_from() {
        let fixture = built_fixture("manifest");
        let region = fixture.path().to_path_buf();
        let dir = std::env::temp_dir().join(format!("moto-manifest-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let gz = dir.join("test.region.gz");
        gzip_to(&region, &gz);
        let args: Vec<String> = [
            "test",
            "Test region",
            &region.to_string_lossy(),
            &gz.to_string_lossy(),
        ]
        .map(String::from)
        .to_vec();
        let out = dir.join("regions.json");
        run(&out, &entries(&args).unwrap(), &dir.join("scratch")).unwrap();
        let offers = parse_manifest(&fs::read(&out).unwrap()).unwrap();
        assert_eq!(offers.len(), 1);
        assert_eq!(
            (offers[0].id.as_str(), offers[0].name.as_str()),
            ("test", "Test region")
        );
        assert_eq!(offers[0].region_bytes, fs::metadata(&region).unwrap().len());

        // A compressed file that is not the region's is caught before
        // anything is published.
        let other = dir.join("other.gz");
        let mut e = GzEncoder::new(Vec::new(), Compression::best());
        e.write_all(b"not a region").unwrap();
        fs::write(&other, e.finish().unwrap()).unwrap();
        let bad = vec![Entry {
            id: "test".into(),
            name: "Test".into(),
            region: region.clone(),
            gz: other,
        }];
        let _ = fs::remove_file(&out);
        assert!(run(&out, &bad, &dir.join("scratch")).is_err());
        assert!(!out.exists(), "nothing written");
        // Bad ids never make it into a manifest.
        let bad_id = vec![Entry {
            id: "../x".into(),
            name: "X".into(),
            region,
            gz,
        }];
        assert!(run(&out, &bad_id, &dir.join("scratch")).is_err());
        assert!(entries(&args[..3]).is_err());
        assert!(entries(&[]).is_err());
    }
}
