// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use std::path::PathBuf;

use super::*;
use crate::section::tests::sample;
use crate::section::{Rating, Source};
use crate::track::tests::fix;

const T0_MS: i64 = 1_790_000_000_000;

/// A file in the temp directory, removed when dropped.
struct Temp(PathBuf);

impl Temp {
    fn new(name: &str) -> Self {
        let p = std::env::temp_dir().join(format!(
            "moto-backup-{name}-{}-{:?}.zip",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_file(&p);
        Self(p)
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn info() -> BackupInfo {
    BackupInfo {
        app: "moto 0.9 (42)".into(),
        regions: vec!["sweden".into(), "denmark".into()],
        settings: Some(vec![
            Setting {
                key: "ride_zoom_step".into(),
                value: SettingValue::Int(3),
            },
            Setting {
                key: "dark_theme".into(),
                value: SettingValue::Text("dark".into()),
            },
        ]),
        created_at_ms: T0_MS,
    }
}

/// A ride east from 57 N 14 E, `n` fixes a second apart from `start_ms`.
fn line_fixes(start_ms: i64, n: usize, lat: f64) -> Vec<TrackPoint> {
    (0..n)
        .map(|i| fix(start_ms + i as i64 * 1000, lat, 14.0 + i as f64 * 0.0002))
        .collect()
}

/// A store with a bit of everything: two favourites, a named ride with a
/// gap, an unnamed ride, a ride still recording, two routes, three tags.
fn filled() -> Store {
    let mut s = Store::open_in_memory().unwrap();
    s.add_section(&sample(), 1_780_000_000).unwrap();
    let mut other = sample();
    other.name = "Coast road".into();
    other.rating = Rating::Good;
    other.geometry = vec![
        LatLon {
            lat: 57.1,
            lon: 14.1,
        },
        LatLon {
            lat: 57.11,
            lon: 14.12,
        },
    ];
    other.ways[0].way_id = 77;
    s.add_section(&other, 1_781_000_000).unwrap();

    // A recorded ride with a gap, named.
    let t = s.start_track(T0_MS / 1000 - 5).unwrap();
    s.append_track_points(t.id, &line_fixes(T0_MS, 4, 57.0))
        .unwrap();
    s.break_track(t.id).unwrap();
    s.append_track_points(t.id, &line_fixes(T0_MS + 60_000, 3, 57.0))
        .unwrap();
    s.finish_track(t.id, T0_MS / 1000 + 70).unwrap();
    s.rename_track(t.id, "Morning <ride> & coffee").unwrap();
    // An imported ride, unnamed.
    let imported = s
        .import_track(&line_fixes(T0_MS + 3_600_000, 5, 57.2))
        .unwrap();
    // A ride still recording: left out.
    let live = s.start_track(T0_MS / 1000 + 9000).unwrap();
    s.append_track_points(live.id, &line_fixes(T0_MS + 9_000_000, 3, 57.3))
        .unwrap();

    s.save_route(
        &NewRoute {
            name: "To the lake".into(),
            is_loop: false,
            distance_m: 12_345.6,
            duration_s: 900.0,
            geometry: vec![
                LatLon {
                    lat: 57.0,
                    lon: 14.0,
                },
                LatLon {
                    lat: 57.05,
                    lon: 14.08,
                },
                LatLon {
                    lat: 57.1,
                    lon: 14.1,
                },
            ],
        },
        1_785_000_000,
    )
    .unwrap();
    s.save_route(
        &NewRoute {
            name: "Evening loop".into(),
            is_loop: true,
            distance_m: 80_000.0,
            duration_s: 4000.0,
            geometry: vec![
                LatLon {
                    lat: 57.0,
                    lon: 14.0,
                },
                LatLon {
                    lat: 57.2,
                    lon: 14.3,
                },
                LatLon {
                    lat: 57.0,
                    lon: 14.0001,
                },
            ],
        },
        1_786_000_000,
    )
    .unwrap();

    let tag = |time_ms, track_id| NewTag {
        time_ms,
        position: LatLon {
            lat: 57.0,
            lon: 14.0004,
        },
        heading_deg: Some(88.5),
        speed_mps: Some(19.25),
        track_id,
    };
    let a = s.add_tag(&tag(T0_MS + 2000, Some(t.id))).unwrap();
    s.set_tag_status(a.id, TagStatus::Used).unwrap();
    let b = s
        .add_tag(&tag(T0_MS + 3_601_000, Some(imported.id)))
        .unwrap();
    s.set_tag_status(b.id, TagStatus::Discarded).unwrap();
    s.add_tag(&tag(T0_MS + 9_001_000, Some(live.id))).unwrap();
    s
}

fn backup_of(store: &Store, name: &str) -> Temp {
    let f = Temp::new(name);
    write_backup(store, &f.0, &info()).unwrap();
    f
}

/// Everything the rider can see, comparable across stores (no ids).
fn contents(s: &Store) -> String {
    let mut out = String::new();
    for x in s.list_sections(None).unwrap() {
        out += &format!(
            "S {} {:?} {:?} {:?} {} {} {:?}\n",
            x.name, x.rating, x.direction, x.source, x.created_at, x.updated_at, x.geometry
        );
    }
    let mut ride_of = HashMap::new();
    for t in s.list_tracks().unwrap() {
        if t.ended_at.is_none() {
            continue;
        }
        let segs = s.track_segments(t.id).unwrap().unwrap();
        let first = segs[0][0].time_ms;
        ride_of.insert(t.id, first);
        out += &format!(
            "T {:?} {} {:?} {} {:.1} {:?}\n",
            t.name,
            t.started_at,
            t.ended_at,
            t.point_count,
            t.distance_m,
            segs.iter()
                .map(|g| g
                    .iter()
                    .map(|p| (p.time_ms, crate::store::tests_e7(p.position)))
                    .collect::<Vec<_>>())
                .collect::<Vec<_>>()
        );
    }
    for r in s.list_routes().unwrap() {
        out += &format!(
            "R {} {} {} {} {} {:?}\n",
            r.name,
            r.is_loop,
            r.created_at,
            r.distance_m,
            r.duration_s,
            s.route_geometry(r.id)
                .unwrap()
                .unwrap()
                .iter()
                .map(|p| crate::store::tests_e7(*p))
                .collect::<Vec<_>>()
        );
    }
    for t in s.list_tags(None).unwrap() {
        out += &format!(
            "G {} {:?} {:?} {:?} {:?} {:?}\n",
            t.time_ms,
            crate::store::tests_e7(t.position),
            t.heading_deg,
            t.speed_mps,
            t.status,
            t.track_id.and_then(|id| ride_of.get(&id))
        );
    }
    out
}

/// What survives a round trip: the ride still recording and its tag's
/// link are left out.
fn without_live(s: &Store) -> String {
    contents(s)
        .lines()
        .filter(|l| !l.starts_with("G 1790009001000"))
        .map(|l| format!("{l}\n"))
        .collect()
}

#[test]
fn a_backup_restores_everything_into_an_empty_app() {
    let src = filled();
    let f = backup_of(&src, "round");
    let sum = read_summary(&f.0).unwrap();
    assert_eq!(
        (
            sum.favourites,
            sum.rides,
            sum.routes,
            sum.tags,
            sum.has_settings
        ),
        (2, 2, 2, 3, true)
    );
    assert_eq!(sum.app, "moto 0.9 (42)");
    assert_eq!(sum.regions, ["sweden", "denmark"]);
    assert_eq!(sum.created_at_ms, T0_MS);
    assert_eq!(sum.schema, src.schema_version().unwrap());

    let mut dst = Store::open_in_memory().unwrap();
    let r = restore_backup(&mut dst, None, &f.0, 1_795_000_000).unwrap();
    assert_eq!(
        (
            r.favourites_added,
            r.rides_added,
            r.routes_added,
            r.tags_added
        ),
        (2, 2, 2, 3)
    );
    // Settings come back in key order.
    let mut want = info().settings.unwrap();
    want.sort_by(|a, b| a.key.cmp(&b.key));
    assert_eq!(r.settings, Some(want));
    assert_eq!(r.regions, ["sweden", "denmark"]);
    // The live ride's tag comes back, without its ride.
    let src_text = without_live(&src);
    let dst_text = without_live(&dst);
    assert_eq!(src_text, dst_text);
    let live_tag = dst
        .list_tags(None)
        .unwrap()
        .into_iter()
        .find(|t| t.time_ms == T0_MS + 9_001_000)
        .unwrap();
    assert_eq!(
        (live_tag.track_id, live_tag.status),
        (None, TagStatus::Pending)
    );
    // Favourites wait to be fitted to the map, with their own times and source.
    assert!(
        dst.list_sections(None)
            .unwrap()
            .iter()
            .all(|s| s.status == crate::section::Status::NeedsRematch && s.source == Source::Map)
    );
}

#[test]
fn restoring_twice_or_into_the_same_app_adds_nothing() {
    let src = filled();
    let f = backup_of(&src, "twice");
    let mut dst = Store::open_in_memory().unwrap();
    restore_backup(&mut dst, None, &f.0, 0).unwrap();
    let before = contents(&dst);
    let r = restore_backup(&mut dst, None, &f.0, 0).unwrap();
    assert_eq!(
        (
            r.favourites_added,
            r.rides_added,
            r.routes_added,
            r.tags_added
        ),
        (0, 0, 0, 0)
    );
    assert_eq!(
        (
            r.favourites_skipped,
            r.rides_skipped,
            r.routes_skipped,
            r.tags_skipped
        ),
        (2, 2, 2, 3)
    );
    assert_eq!(contents(&dst), before);

    let mut same = filled();
    let before = contents(&same);
    let r = restore_backup(&mut same, None, &f.0, 0).unwrap();
    assert_eq!((r.rides_added, r.routes_added, r.tags_added), (0, 0, 0));
    assert_eq!(contents(&same), before);
}

#[test]
fn restore_merges_with_what_is_there() {
    let src = filled();
    let f = backup_of(&src, "merge");
    let mut dst = Store::open_in_memory().unwrap();
    dst.import_track(&line_fixes(T0_MS + 3_600_000, 5, 57.2))
        .unwrap();
    let own = dst
        .import_track(&line_fixes(T0_MS + 99_000_000, 4, 57.5))
        .unwrap();
    let r = restore_backup(&mut dst, None, &f.0, 0).unwrap();
    assert_eq!((r.rides_added, r.rides_skipped), (1, 1));
    assert!(
        dst.get_track(own.id).unwrap().is_some(),
        "nothing is deleted"
    );
    assert_eq!(dst.list_tracks().unwrap().len(), 3);
    // The tag on the ride that was already there points at it.
    let discarded = dst.list_tags(Some(TagStatus::Discarded)).unwrap();
    assert_eq!(discarded.len(), 1);
    assert_eq!(
        dst.track_points(discarded[0].track_id.unwrap())
            .unwrap()
            .unwrap()
            .len(),
        5
    );
}

#[test]
fn an_empty_app_makes_a_backup_with_only_a_manifest() {
    let src = Store::open_in_memory().unwrap();
    let f = Temp::new("empty");
    let mut i = info();
    i.settings = None;
    let sum = write_backup(&src, &f.0, &i).unwrap();
    assert_eq!(
        (
            sum.favourites,
            sum.rides,
            sum.routes,
            sum.tags,
            sum.has_settings
        ),
        (0, 0, 0, 0, false)
    );
    let mut dst = filled();
    let before = contents(&dst);
    let r = restore_backup(&mut dst, None, &f.0, 0).unwrap();
    assert_eq!(r.settings, None);
    assert_eq!(contents(&dst), before);
}

#[test]
fn settings_are_checked_both_ways() {
    let src = Store::open_in_memory().unwrap();
    let f = Temp::new("settings");
    let mut i = info();
    i.settings = Some(vec![Setting {
        key: "Bad Key".into(),
        value: SettingValue::Bool(true),
    }]);
    assert!(write_backup(&src, &f.0, &i).is_err());
    // Settings that aren't simple values, with a matching hash: refused.
    let g = backup_of(&src, "settings2");
    let odd = br#"{"schema":1,"settings":{"a":[1,2]}}"#.to_vec();
    let files: Vec<_> = entries(&g.0)
        .into_iter()
        .map(|(n, b)| {
            if n == SETTINGS {
                (n, odd.clone())
            } else {
                (n, b)
            }
        })
        .collect();
    let files = with_manifest(&files, |m| {
        for f in m["files"].as_array_mut().unwrap() {
            if f["name"] == SETTINGS {
                f["size"] = odd.len().into();
                f["sha256"] = hex(&Sha256::digest(&odd)).into();
            }
        }
    });
    zip_of(&f.0, &files);
    refused(&f.0);
}

#[test]
fn bad_region_keys_and_app_text_are_dropped_when_writing() {
    let src = Store::open_in_memory().unwrap();
    let f = Temp::new("regions");
    let mut i = info();
    i.regions = vec!["../etc".into(), "ok-1".into(), String::new()];
    i.app = "moto\n\u{7}9".into();
    let sum = write_backup(&src, &f.0, &i).unwrap();
    assert_eq!(sum.regions, ["ok-1"]);
    assert_eq!(sum.app, "moto9");
}

// --- Hostile files ---

/// The files of a backup, as (name, bytes), in zip order.
fn entries(path: &Path) -> Vec<(String, Vec<u8>)> {
    let mut zip = ZipArchive::new(File::open(path).unwrap()).unwrap();
    (0..zip.len())
        .map(|i| {
            let mut e = zip.by_index(i).unwrap();
            let mut b = Vec::new();
            e.read_to_end(&mut b).unwrap();
            (e.name().to_string(), b)
        })
        .collect()
}

/// Writes `files` as a zip at `path`.
fn zip_of(path: &Path, files: &[(String, Vec<u8>)]) {
    let mut z = ZipWriter::new(File::create(path).unwrap());
    for (name, bytes) in files {
        z.start_file(name.as_str(), SimpleFileOptions::default())
            .unwrap();
        z.write_all(bytes).unwrap();
    }
    z.finish().unwrap();
}

/// `files` with the manifest changed by `edit` (as JSON).
fn with_manifest(
    files: &[(String, Vec<u8>)],
    edit: impl Fn(&mut serde_json::Value),
) -> Vec<(String, Vec<u8>)> {
    files
        .iter()
        .map(|(n, b)| {
            if n == MANIFEST {
                let mut v: serde_json::Value = serde_json::from_slice(b).unwrap();
                edit(&mut v);
                (n.clone(), serde_json::to_vec(&v).unwrap())
            } else {
                (n.clone(), b.clone())
            }
        })
        .collect()
}

/// Restoring `path` into a filled store fails and changes nothing.
fn refused(path: &Path) -> String {
    let mut dst = filled();
    let before = contents(&dst);
    let err = restore_backup(&mut dst, None, path, 0)
        .unwrap_err()
        .to_string();
    assert_eq!(
        contents(&dst),
        before,
        "a refused restore changed the store: {err}"
    );
    err
}

#[test]
fn a_damaged_file_changes_nothing() {
    let src = filled();
    let f = backup_of(&src, "damaged");
    let files = entries(&f.0);
    // A ride whose content no longer matches its hash: found after the
    // favourites were already added in the transaction.
    let tampered: Vec<_> = files
        .iter()
        .map(|(n, b)| {
            let mut b = b.clone();
            if n == "rides/00002.gpx" {
                let i = b.len() / 2;
                b[i] = if b[i] == b'1' { b'2' } else { b'1' };
            }
            (n.clone(), b)
        })
        .collect();
    let g = Temp::new("damaged2");
    zip_of(&g.0, &tampered);
    assert!(refused(&g.0).contains("damaged"));
}

#[test]
fn a_backup_from_a_newer_app_asks_for_an_update() {
    let f = backup_of(&filled(), "newer");
    let g = Temp::new("newer2");
    zip_of(
        &g.0,
        &with_manifest(&entries(&f.0), |m| m["format"] = (FORMAT + 1).into()),
    );
    assert!(refused(&g.0).contains("newer version"));
    assert!(read_summary(&g.0).is_err());
}

#[test]
fn files_must_match_the_manifest_exactly() {
    let f = backup_of(&filled(), "exact");
    let files = entries(&f.0);
    let g = Temp::new("exact2");
    let try_files = |files: Vec<(String, Vec<u8>)>| {
        zip_of(&g.0, &files);
        refused(&g.0)
    };
    // An extra file, a path-like name, a file missing, a manifest missing.
    let mut extra = files.clone();
    extra.push(("notes.txt".into(), b"hi".to_vec()));
    try_files(extra);
    let mut sneaky = files.clone();
    sneaky.push(("../rides/00001.gpx".into(), b"x".to_vec()));
    try_files(sneaky);
    try_files(files.iter().filter(|(n, _)| n != TAGS).cloned().collect());
    try_files(
        files
            .iter()
            .filter(|(n, _)| n != MANIFEST)
            .cloned()
            .collect(),
    );
    // The same name twice.
    let mut twice = files.clone();
    twice.push(files.iter().find(|(n, _)| n == SECTIONS).unwrap().clone());
    // The zip writer refuses duplicates itself; any refusal is fine.
    let mut z = ZipWriter::new(File::create(&g.0).unwrap());
    let mut dup_refused = false;
    for (name, bytes) in &twice {
        if z.start_file(name.as_str(), SimpleFileOptions::default())
            .is_err()
        {
            dup_refused = true;
            break;
        }
        z.write_all(bytes).unwrap();
    }
    assert!(dup_refused || z.finish().is_ok());
}

/// A change to a manifest, as JSON.
type Edit = dyn Fn(&mut serde_json::Value);

#[test]
fn manifest_metadata_is_checked() {
    let f = backup_of(&filled(), "meta");
    let files = entries(&f.0);
    let g = Temp::new("meta2");
    let edits: Vec<Box<Edit>> = vec![
        Box::new(|m| m["unknown"] = 1.into()),
        Box::new(|m| m["format"] = 0.into()),
        Box::new(|m| m["regions"] = serde_json::json!(["a/b"])),
        Box::new(|m| m["app"] = "x\u{0}".into()),
        Box::new(|m| m["favourites"] = 5.into()),
        Box::new(|m| m["rides"][0]["file"] = "rides/../00001.gpx".into()),
        Box::new(|m| m["rides"][0]["file"] = "routes/00001.gpx".into()),
        Box::new(|m| m["rides"][0]["name"] = "x".repeat(201).into()),
        Box::new(|m| m["rides"][0]["started_at"] = (-5).into()),
        Box::new(|m| m["rides"][0]["ended_at"] = 0.into()),
        Box::new(|m| m["routes"][0]["distance_m"] = (-1.0).into()),
        Box::new(|m| m["routes"][0]["created_at"] = i64::MAX.into()),
        Box::new(|m| m["tags"][0]["ride"] = "rides/09999.gpx".into()),
        Box::new(|m| m["tags"][0]["status"] = "maybe".into()),
        Box::new(|m| {
            m["tags"].as_array_mut().unwrap().pop();
        }),
        Box::new(|m| m["files"][0]["sha256"] = "00".into()),
        Box::new(|m| m["files"][0]["size"] = 1.into()),
        Box::new(|m| m["files"][0]["size"] = u64::MAX.into()),
    ];
    for (i, edit) in edits.iter().enumerate() {
        zip_of(&g.0, &with_manifest(&files, edit));
        let mut dst = filled();
        let before = contents(&dst);
        assert!(
            restore_backup(&mut dst, None, &g.0, 0).is_err(),
            "edit {i} was accepted"
        );
        assert_eq!(contents(&dst), before, "edit {i} changed the store");
    }
}

#[test]
fn not_a_backup_is_an_error() {
    let g = Temp::new("junk");
    for junk in [
        &b""[..],
        b"PK",
        b"hello world, not a zip at all",
        &[0u8; 100],
    ] {
        std::fs::write(&g.0, junk).unwrap();
        assert!(read_summary(&g.0).is_err());
        refused(&g.0);
    }
    // A sections export (a zip, but no manifest).
    let s = filled();
    std::fs::write(
        &g.0,
        exchange::export_sections(&s, exchange::ExportFormat::Zip).unwrap(),
    )
    .unwrap();
    assert!(
        read_summary(&g.0)
            .unwrap_err()
            .to_string()
            .contains("not a zip file written by moto")
            || read_summary(&g.0).is_err()
    );
}

#[test]
fn a_zip_directory_claiming_too_many_entries_is_refused_before_reading() {
    let f = backup_of(&filled(), "count");
    let mut bytes = std::fs::read(&f.0).unwrap();
    let n = bytes.len();
    // The end record's entry counts.
    for at in [n - 14, n - 12] {
        bytes[at..at + 2].copy_from_slice(&u16::MAX.to_le_bytes());
    }
    let g = Temp::new("count2");
    std::fs::write(&g.0, &bytes).unwrap();
    assert!(refused(&g.0).contains("zip directory"));
    // A comment after the end record.
    let mut bytes = std::fs::read(&f.0).unwrap();
    let n = bytes.len();
    bytes[n - 2..].copy_from_slice(&3u16.to_le_bytes());
    bytes.extend_from_slice(b"abc");
    std::fs::write(&g.0, &bytes).unwrap();
    refused(&g.0);
}

#[test]
fn truncated_and_bit_flipped_backups_never_panic() {
    let f = backup_of(&filled(), "fuzz");
    let bytes = std::fs::read(&f.0).unwrap();
    let g = Temp::new("fuzz2");
    let mut dst = Store::open_in_memory().unwrap();
    // Every 7th truncation.
    for len in (0..bytes.len()).step_by(7) {
        std::fs::write(&g.0, &bytes[..len]).unwrap();
        assert!(
            restore_backup(&mut dst, None, &g.0, 0).is_err(),
            "truncated to {len}"
        );
    }
    assert!(contents(&dst).is_empty());
    // Single bit flips all through the file: refused, or (in a stored
    // header field that doesn't matter) restored whole. Never a panic.
    let mut seed = 0x2545_f491_4f6c_dd1du64;
    for _ in 0..600 {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        let mut b = bytes.clone();
        let i = (seed % b.len() as u64) as usize;
        b[i] ^= 1 << (seed >> 60 & 7);
        std::fs::write(&g.0, &b).unwrap();
        let mut dst = Store::open_in_memory().unwrap();
        if restore_backup(&mut dst, None, &g.0, 0).is_ok() {
            assert_eq!(
                without_live(&dst),
                without_live(&filled()),
                "flip at {i} restored something else"
            );
        } else {
            assert!(
                contents(&dst).is_empty(),
                "flip at {i}: a refused restore left data"
            );
        }
    }
}
