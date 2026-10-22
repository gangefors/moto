// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use rusqlite::Connection;

use super::super::{MIGRATIONS, SCHEMA_VERSION, migrate, user_version};
use super::*;
use crate::region::format::Surface;
use crate::surface::tests::sample;

const T0: i64 = 1_790_000_000;

fn store() -> Store {
    Store::open_in_memory().unwrap()
}

fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
    WaySpan {
        way_id,
        from_idx,
        to_idx,
    }
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

/// A Gravel correction on `ways` along a short line at `lat`.
fn on(ways: Vec<WaySpan>, lat: f64) -> NewSurfaceCorrection {
    NewSurfaceCorrection {
        ways,
        geometry: vec![ll(lat, 13.40), ll(lat, 13.41)],
        ..sample(Surface::Gravel)
    }
}

fn rows(s: &Store, table: &str) -> i64 {
    s.conn
        .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))
        .unwrap()
}

#[test]
fn migration_9_to_10_keeps_data_and_adds_the_tables() {
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, &MIGRATIONS[..9]).unwrap();
    conn.execute_batch(
        "INSERT INTO sections (rider_id, name, rating, direction, source, status, created_at,
            updated_at, min_lat, min_lon, max_lat, max_lon, geometry)
         VALUES ('local', 'Old road', 2, 0, 0, 0, 1, 1, 0, 0, 1, 1, x'00');",
    )
    .unwrap();
    migrate(&mut conn, MIGRATIONS).unwrap();
    assert_eq!(user_version(&conn).unwrap(), 10);
    assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
    let (name, surface): (String, Option<i64>) = conn
        .query_row("SELECT name, surface FROM sections", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert_eq!((name.as_str(), surface), ("Old road", None));
    for table in ["surface_corrections", "surface_correction_ways"] {
        let n: i64 = conn
            .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 0, "{table}");
    }
    // Only Paved and Gravel fit the new columns.
    assert!(conn.execute("UPDATE sections SET surface = 6", []).is_ok());
    assert!(conn.execute("UPDATE sections SET surface = 1", []).is_err());
}

#[test]
fn a_database_from_schema_11_is_refused() {
    let mut conn = Connection::open_in_memory().unwrap();
    conn.execute_batch("PRAGMA user_version = 11").unwrap();
    assert!(matches!(
        migrate(&mut conn, MIGRATIONS),
        Err(CoreError::Storage(_))
    ));
}

#[test]
fn adds_lists_gets_and_deletes() {
    let mut s = store();
    let saved = s
        .add_surface_correction(&sample(Surface::Gravel), None, T0)
        .unwrap();
    assert!(saved.replaced.is_empty());
    let c = &saved.correction;
    assert_eq!((c.status, c.created_at, c.updated_at), (Status::Ok, T0, T0));
    assert_eq!(c.surface, Surface::Gravel);
    assert_eq!(s.surface_correction(c.id).unwrap().as_ref(), Some(c));
    let paved = s
        .add_surface_correction(&on(vec![span(7, 0, 1)], 55.0), None, T0 + 1)
        .unwrap()
        .correction;
    let all = s.surface_corrections().unwrap();
    assert_eq!(
        all.iter().map(|c| c.id).collect::<Vec<_>>(),
        [c.id, paved.id]
    );
    assert!(s.delete_surface_correction(c.id).unwrap());
    assert!(!s.delete_surface_correction(c.id).unwrap());
    assert_eq!(s.surface_correction(c.id).unwrap(), None);
    assert_eq!(rows(&s, "surface_correction_ways"), 1, "spans go with it");
}

#[test]
fn refuses_what_is_not_a_correction() {
    let mut s = store();
    for surface in [Surface::Unknown, Surface::Asphalt, Surface::Dirt] {
        assert!(matches!(
            s.add_surface_correction(&sample(surface), None, T0),
            Err(CoreError::InvalidArgument(_))
        ));
    }
    let mut bad = sample(Surface::Paved);
    bad.ways[0].way_id = 0;
    assert!(s.add_surface_correction(&bad, None, T0).is_err());
    let mut bad = sample(Surface::Paved);
    bad.geometry[0].lon = f64::NAN;
    assert!(s.add_surface_correction(&bad, None, T0).is_err());
    assert_eq!(rows(&s, "surface_corrections"), 0);
}

#[test]
fn refuses_one_more_than_the_cap() {
    let mut s = store();
    {
        let tx = s.conn.transaction().unwrap();
        for i in 0..MAX_SURFACE_CORRECTIONS {
            let c = on(vec![span(i as i64 + 1, 0, 1)], 55.0);
            insert_correction(&tx, &c, T0, T0, Status::Ok).unwrap();
        }
        tx.commit().unwrap();
    }
    let next = on(vec![span(999_999, 0, 1)], 55.0);
    assert!(matches!(
        s.add_surface_correction(&next, None, T0),
        Err(CoreError::InvalidArgument(_))
    ));
    assert_eq!(
        rows(&s, "surface_corrections"),
        MAX_SURFACE_CORRECTIONS as i64
    );
    // Replacing one keeps the count.
    let saved = s.add_surface_correction(&next, Some(1), T0).unwrap();
    assert_eq!(saved.replaced, [1]);
}

#[test]
fn replaces_the_edited_one_and_fully_covered_older_ones() {
    let mut s = store();
    let a = s
        .add_surface_correction(&on(vec![span(5, 2, 4)], 55.0), None, T0)
        .unwrap()
        .correction
        .id;
    let b = s
        .add_surface_correction(&on(vec![span(5, 6, 5), span(6, 0, 2)], 55.0), None, T0)
        .unwrap()
        .correction
        .id;
    // Only partly covered by what comes next: kept.
    let partial = s
        .add_surface_correction(&on(vec![span(5, 8, 12)], 55.0), None, T0)
        .unwrap()
        .correction
        .id;
    let edited = s
        .add_surface_correction(&on(vec![span(9, 0, 1)], 56.0), None, T0)
        .unwrap()
        .correction
        .id;
    let saved = s
        .add_surface_correction(
            &on(vec![span(5, 10, 0), span(6, 3, 0)], 55.0),
            Some(edited),
            T0 + 5,
        )
        .unwrap();
    let mut want = vec![a, b, edited];
    want.sort_unstable();
    assert_eq!(saved.replaced, want);
    let left: Vec<i64> = s
        .surface_corrections()
        .unwrap()
        .iter()
        .map(|c| c.id)
        .collect();
    assert_eq!(left, [partial, saved.correction.id]);
    // A missing id saves as new.
    let again = s
        .add_surface_correction(&on(vec![span(77, 0, 1)], 57.0), Some(12345), T0)
        .unwrap();
    assert!(again.replaced.is_empty());
}

#[test]
fn waiting_corrections_are_not_replaced_by_cover() {
    let mut s = store();
    let a = s
        .add_surface_correction(&on(vec![span(5, 2, 4)], 55.0), None, T0)
        .unwrap()
        .correction
        .id;
    s.flag_corrections_for_rematch().unwrap();
    let saved = s
        .add_surface_correction(&on(vec![span(5, 0, 9)], 55.0), None, T0)
        .unwrap();
    assert!(saved.replaced.is_empty());
    assert!(s.surface_correction(a).unwrap().is_some());
}

#[test]
fn a_failed_save_rolls_everything_back() {
    let mut s = store();
    let old = s
        .add_surface_correction(&on(vec![span(5, 2, 4)], 55.0), None, T0)
        .unwrap()
        .correction;
    s.conn
        .execute_batch(
            "CREATE TEMP TRIGGER fail BEFORE INSERT ON surface_correction_ways
             BEGIN SELECT RAISE(ABORT, 'disk on fire'); END;",
        )
        .unwrap();
    let err = s
        .add_surface_correction(&on(vec![span(5, 0, 9)], 55.0), Some(old.id), T0 + 1)
        .unwrap_err();
    assert!(matches!(err, CoreError::Storage(ref m) if m.contains("disk on fire")));
    assert_eq!(s.surface_corrections().unwrap(), [old]);
}

#[test]
fn tampered_rows_are_storage_errors_not_panics() {
    let damage = [
        "UPDATE surface_corrections SET surface = 0",
        "UPDATE surface_corrections SET surface = 1",
        "UPDATE surface_corrections SET surface = 7",
        "UPDATE surface_corrections SET surface = 8",
        "UPDATE surface_corrections SET surface = 255",
        "UPDATE surface_corrections SET status = 2",
        "UPDATE surface_corrections SET status = -1",
        "UPDATE surface_corrections SET geometry = x'0102'",
        "UPDATE surface_corrections SET geometry = x'ffffff7fffffff7fffffff7fffffff7f'",
        "UPDATE surface_correction_ways SET from_idx = -1",
        "UPDATE surface_correction_ways SET to_idx = 4294967296",
        "UPDATE surface_correction_ways SET way_id = 0",
    ];
    for sql in damage {
        let mut s = store();
        let id = s
            .add_surface_correction(&sample(Surface::Gravel), None, T0)
            .unwrap()
            .correction
            .id;
        s.conn
            .execute_batch("PRAGMA ignore_check_constraints = ON")
            .unwrap();
        s.conn.execute_batch(sql).unwrap();
        assert!(
            matches!(s.surface_correction(id), Err(CoreError::Storage(_))),
            "{sql}"
        );
        assert!(s.surface_corrections().is_err(), "{sql}");
    }
}

#[test]
fn looks_up_the_correction_at_a_point() {
    let mut s = store();
    // East–west along lat 55.70; 20 m north of it is about 0.00018°.
    let line = |lat| on(vec![span(5, 0, 3)], lat);
    let old = s
        .add_surface_correction(&line(55.70), None, T0)
        .unwrap()
        .correction;
    let near = ll(55.70018, 13.405);
    let far = ll(55.70027, 13.405);
    let at = s
        .surface_correction_at(near, crate::surface::CORRECTION_REACH_M)
        .unwrap();
    assert_eq!(at, Some(CorrectionAt::Standalone(old.clone())));
    assert_eq!(s.surface_correction_at(far, 25.0).unwrap(), None);
    // The newest of two there wins.
    let mut paved = line(55.70);
    paved.surface = Surface::Paved;
    paved.ways = vec![span(6, 0, 1)];
    let new = s
        .add_surface_correction(&paved, None, T0 + 10)
        .unwrap()
        .correction;
    assert_eq!(
        s.surface_correction_at(near, 25.0).unwrap(),
        Some(CorrectionAt::Standalone(new))
    );
    // Waiting ones are left out.
    s.flag_corrections_for_rematch().unwrap();
    assert_eq!(s.surface_correction_at(near, 25.0).unwrap(), None);
    assert!(s.surface_correction_at(ll(f64::NAN, 0.0), 25.0).is_err());
    assert!(s.surface_correction_at(near, f64::NAN).is_err());
}

#[test]
fn saves_a_rematch_and_keeps_the_rest() {
    let mut s = store();
    let c = s
        .add_surface_correction(&sample(Surface::Paved), None, T0)
        .unwrap()
        .correction;
    s.flag_corrections_for_rematch().unwrap();
    assert_eq!(
        s.surface_corrections_with_status(Status::NeedsRematch)
            .unwrap()
            .len(),
        1
    );
    let ways = vec![span(43, 9, 1)];
    let line = vec![ll(56.0, 12.0), ll(56.01, 12.0)];
    assert!(s.save_correction_match(c.id, &ways, &line).unwrap());
    assert!(!s.save_correction_match(c.id + 1, &ways, &line).unwrap());
    let back = s.surface_correction(c.id).unwrap().unwrap();
    assert_eq!(back.status, Status::Ok);
    assert_eq!(back.ways, ways);
    assert_eq!(back.geometry, line);
    assert_eq!((back.surface, back.updated_at), (Surface::Paved, T0));
    assert!(s.save_correction_match(c.id, &ways, &line[..1]).is_err());
}

#[test]
fn reach_box_is_safe_near_the_poles_and_the_date_line() {
    let (sw, ne) = reach_box(ll(90.0, 180.0), 25.0);
    assert!(sw.0 <= ne.0 && sw.1 <= ne.1);
    let (sw, ne) = reach_box(ll(-89.9999, -180.0), 1e9);
    assert!(sw.0 >= -900_000_000 && ne.0 <= 900_000_000 && sw.1 >= -1_800_000_000);
}

// --- A favourite's own surface ---

fn favourite_at(lat: f64) -> crate::section::NewSection {
    crate::section::NewSection {
        geometry: vec![ll(lat, 13.40), ll(lat, 13.41)],
        ..crate::section::tests::sample()
    }
}

#[test]
fn an_update_sets_or_clears_a_favourites_own_surface() {
    use crate::section::{SectionUpdate, SurfaceSetting};
    let mut s = store();
    let id = s.add_section(&favourite_at(55.0), T0).unwrap().id;
    assert_eq!(s.get_section(id).unwrap().unwrap().surface, None);
    for (setting, want) in [
        (Some(SurfaceSetting::Gravel), Some(Surface::Gravel)),
        (None, Some(Surface::Gravel)),
        (Some(SurfaceSetting::Paved), Some(Surface::Paved)),
        (Some(SurfaceSetting::Map), None),
        (None, None),
    ] {
        let up = SectionUpdate {
            surface: setting,
            rating: Some(crate::section::Rating::Good),
            direction: Some(crate::section::Direction::Forward),
            ..Default::default()
        };
        let got = s.update_section(id, &up, T0 + 1).unwrap().unwrap();
        assert_eq!(got.surface, want, "{setting:?}");
        assert_eq!(got.rating, crate::section::Rating::Good);
    }
    // A new favourite can carry one; not Unknown or Dirt.
    let mut with = favourite_at(55.1);
    with.surface = Some(Surface::Paved);
    assert_eq!(
        s.add_section(&with, T0).unwrap().surface,
        Some(Surface::Paved)
    );
    for bad in [Surface::Unknown, Surface::Dirt] {
        let mut b = favourite_at(55.2);
        b.surface = Some(bad);
        assert!(s.add_section(&b, T0).is_err(), "{bad:?}");
    }
}

#[test]
fn a_tampered_favourite_surface_is_a_storage_error() {
    for code in [0, 1, 7, 99] {
        let mut s = store();
        let id = s.add_section(&favourite_at(55.0), T0).unwrap().id;
        s.conn
            .execute_batch("PRAGMA ignore_check_constraints = ON")
            .unwrap();
        s.conn
            .execute("UPDATE sections SET surface = ?1", [code])
            .unwrap();
        assert!(
            matches!(s.get_section(id), Err(CoreError::Storage(_))),
            "{code}"
        );
        assert!(s.list_sections(None).is_err(), "{code}");
        assert!(
            s.surface_correction_at(ll(55.0, 13.405), 25.0).is_err(),
            "{code}"
        );
    }
}

#[test]
fn a_favourites_own_surface_is_found_before_a_standalone_one() {
    use crate::section::{SectionUpdate, SurfaceSetting};
    let mut s = store();
    s.add_surface_correction(&on(vec![span(5, 0, 3)], 55.0), None, T0 + 50)
        .unwrap();
    let at = ll(55.0001, 13.405);
    assert!(matches!(
        s.surface_correction_at(at, 25.0).unwrap(),
        Some(CorrectionAt::Standalone(_))
    ));
    let id = s.add_section(&favourite_at(55.0), T0).unwrap().id;
    // Map: not a correction.
    assert!(matches!(
        s.surface_correction_at(at, 25.0).unwrap(),
        Some(CorrectionAt::Standalone(_))
    ));
    let up = SectionUpdate {
        surface: Some(SurfaceSetting::Paved),
        ..Default::default()
    };
    s.update_section(id, &up, T0 + 1).unwrap();
    assert_eq!(
        s.surface_correction_at(at, 25.0).unwrap(),
        Some(CorrectionAt::Favourite {
            section_id: id,
            surface: Surface::Paved
        })
    );
    // Waiting for a re-match: not applied, so not found.
    s.set_status(id, Status::NeedsRematch).unwrap();
    assert!(matches!(
        s.surface_correction_at(at, 25.0).unwrap(),
        Some(CorrectionAt::Standalone(_))
    ));
    // Deleted with the favourite.
    s.set_status(id, Status::Ok).unwrap();
    s.delete_section(id).unwrap();
    assert!(matches!(
        s.surface_correction_at(at, 25.0).unwrap(),
        Some(CorrectionAt::Standalone(_))
    ));
}
