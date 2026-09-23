// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::section::tests::sample;
use crate::section::{Direction, LOCAL_RIDER, Rating, SectionUpdate, Status};

const T0: i64 = 1_790_000_000;

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn store() -> Store {
    Store::open_in_memory().unwrap()
}

/// A database file in the temp directory, removed (with its WAL files)
/// when dropped.
struct TempDb(std::path::PathBuf);

impl TempDb {
    fn new(name: &str) -> Self {
        let p = std::env::temp_dir().join(format!("moto-store-{name}-{}.db", std::process::id()));
        let db = Self(p);
        db.remove();
        db
    }

    fn remove(&self) {
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", self.0.display()));
        }
    }
}

impl Drop for TempDb {
    fn drop(&mut self) {
        self.remove();
    }
}

#[test]
fn new_database_gets_the_current_schema() {
    let s = store();
    assert_eq!(s.schema_version().unwrap(), SCHEMA_VERSION);
    assert!(s.list_sections(None).unwrap().is_empty());
}

#[test]
fn saves_and_reads_back_a_section() {
    let mut s = store();
    let saved = s.add_section(&sample(), T0).unwrap();
    assert!(saved.id > 0);
    assert_eq!((saved.created_at, saved.updated_at), (T0, T0));
    assert_eq!(saved.status, Status::Ok);
    assert_eq!(saved.rider_id, LOCAL_RIDER);
    let read = s.get_section(saved.id).unwrap().unwrap();
    assert_eq!(read, saved);
    assert_eq!(read.ways, sample().ways);
    assert_eq!(s.get_section(saved.id + 100).unwrap(), None);
}

#[test]
fn geometry_is_stored_at_osm_precision() {
    let mut s = store();
    let mut new = sample();
    new.geometry[0] = ll(56.123_456_789, 12.987_654_321);
    let saved = s.add_section(&new, T0).unwrap();
    assert_eq!(saved.geometry[0], ll(56.123_456_8, 12.987_654_3));
    assert_eq!(
        s.get_section(saved.id).unwrap().unwrap().geometry,
        saved.geometry
    );
}

#[test]
fn keeps_way_order_and_reverse_spans() {
    let mut s = store();
    let mut new = sample();
    new.ways = vec![
        WaySpan {
            way_id: 9,
            from_idx: 5,
            to_idx: 1,
        },
        WaySpan {
            way_id: 3,
            from_idx: 0,
            to_idx: u32::MAX,
        },
        WaySpan {
            way_id: 7,
            from_idx: 2,
            to_idx: 2,
        },
    ];
    let id = s.add_section(&new, T0).unwrap().id;
    assert_eq!(s.get_section(id).unwrap().unwrap().ways, new.ways);
}

#[test]
fn lists_sections_by_area() {
    let mut s = store();
    let north = s.add_section(&sample(), T0).unwrap(); // around 56.30, 12.45
    let mut south = sample();
    south.geometry = vec![ll(55.40, 13.80), ll(55.41, 13.83)];
    let south = s.add_section(&south, T0 + 1).unwrap();

    let all = s.list_sections(None).unwrap();
    assert_eq!(
        all.iter().map(|x| x.id).collect::<Vec<_>>(),
        [north.id, south.id]
    );

    let near_ystad = s
        .list_sections(Some((ll(55.3, 13.7), ll(55.5, 13.9))))
        .unwrap();
    assert_eq!(
        near_ystad.iter().map(|x| x.id).collect::<Vec<_>>(),
        [south.id]
    );
    // A box that only overlaps the section's bounding box partly.
    let edge = s
        .list_sections(Some((ll(56.305, 12.455), ll(57.0, 13.0))))
        .unwrap();
    assert_eq!(edge.iter().map(|x| x.id).collect::<Vec<_>>(), [north.id]);
    assert!(
        s.list_sections(Some((ll(60.0, 15.0), ll(61.0, 16.0))))
            .unwrap()
            .is_empty()
    );
    assert!(
        s.list_sections(Some((ll(91.0, 0.0), ll(0.0, 0.0))))
            .is_err()
    );
}

#[test]
fn updates_name_rating_and_direction() {
    let mut s = store();
    let id = s.add_section(&sample(), T0).unwrap().id;
    let up = SectionUpdate {
        rating: Some(Rating::Good),
        direction: Some(Direction::Forward),
        ..Default::default()
    };
    let changed = s.update_section(id, &up, T0 + 60).unwrap().unwrap();
    assert_eq!(
        (changed.rating, changed.direction),
        (Rating::Good, Direction::Forward)
    );
    assert_eq!(changed.name, sample().name, "untouched fields stay");
    assert_eq!((changed.created_at, changed.updated_at), (T0, T0 + 60));

    let renamed = s
        .update_section(
            id,
            &SectionUpdate {
                name: Some("Söderåsen".into()),
                ..Default::default()
            },
            T0 + 61,
        )
        .unwrap()
        .unwrap();
    assert_eq!(renamed.name, "Söderåsen");
    assert_eq!(renamed.rating, Rating::Good);

    let bad = SectionUpdate {
        name: Some("x\u{7}".into()),
        ..Default::default()
    };
    assert!(s.update_section(id, &bad, T0).is_err());
    assert_eq!(
        s.update_section(id + 1, &SectionUpdate::default(), T0)
            .unwrap(),
        None
    );
}

#[test]
fn flags_and_deletes_sections() {
    let mut s = store();
    let id = s.add_section(&sample(), T0).unwrap().id;
    assert!(s.set_status(id, Status::NeedsRematch).unwrap());
    assert_eq!(
        s.get_section(id).unwrap().unwrap().status,
        Status::NeedsRematch
    );
    assert!(!s.set_status(id + 1, Status::Ok).unwrap());

    assert!(s.delete_section(id).unwrap());
    assert!(!s.delete_section(id).unwrap());
    assert_eq!(s.get_section(id).unwrap(), None);
    let orphans: i64 = s
        .conn
        .query_row("SELECT count(*) FROM section_ways", [], |r| r.get(0))
        .unwrap();
    assert_eq!(orphans, 0, "way spans go with their section");
}

#[test]
fn invalid_sections_are_not_saved() {
    let mut s = store();
    let mut bad = sample();
    bad.geometry.truncate(1);
    assert!(matches!(
        s.add_section(&bad, T0),
        Err(CoreError::InvalidArgument(_))
    ));
    assert!(s.list_sections(None).unwrap().is_empty());
}

#[test]
fn a_failed_save_leaves_nothing_behind() {
    let mut s = store();
    // Make the second insert (the way spans) fail half-way.
    s.conn
        .execute_batch(
            "CREATE TEMP TRIGGER fail BEFORE INSERT ON section_ways
             BEGIN SELECT RAISE(ABORT, 'disk on fire'); END;",
        )
        .unwrap();
    let err = s.add_section(&sample(), T0).unwrap_err();
    assert!(
        matches!(err, CoreError::Storage(ref m) if m.contains("disk on fire")),
        "{err:?}"
    );
    assert!(
        s.list_sections(None).unwrap().is_empty(),
        "the section row was rolled back"
    );
}

#[test]
fn survives_reopening_from_disk() {
    let db = TempDb::new("reopen");
    let id = {
        let mut s = Store::open(&db.0).unwrap();
        s.add_section(&sample(), T0).unwrap().id
    };
    let s = Store::open(&db.0).unwrap();
    assert_eq!(s.get_section(id).unwrap().unwrap().name, sample().name);
    let mode: String = s
        .conn
        .query_row("PRAGMA journal_mode", [], |r| r.get(0))
        .unwrap();
    assert_eq!(mode, "wal");
}

#[test]
fn runs_new_migrations_and_keeps_data() {
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, MIGRATIONS).unwrap();
    conn.execute(
        "INSERT INTO sections (rider_id, name, rating, direction, source, status, created_at,
            updated_at, min_lat, min_lon, max_lat, max_lon, geometry)
         VALUES ('local', 'old', 3, 0, 0, 0, 1, 1, 0, 0, 0, 0, x'')",
        [],
    )
    .unwrap();
    // A later build adds a column.
    let mut next = MIGRATIONS.to_vec();
    next.push("ALTER TABLE sections ADD COLUMN notes TEXT NOT NULL DEFAULT ''");
    migrate(&mut conn, &next).unwrap();
    assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION + 1);
    let (name, notes): (String, String) = conn
        .query_row("SELECT name, notes FROM sections", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert_eq!((name.as_str(), notes.as_str()), ("old", ""));
    // Running again is a no-op.
    migrate(&mut conn, &next).unwrap();
}

#[test]
fn a_failed_migration_changes_nothing() {
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, MIGRATIONS).unwrap();
    let mut next = MIGRATIONS.to_vec();
    next.push("CREATE TABLE extra (x INTEGER); THIS IS NOT SQL;");
    assert!(migrate(&mut conn, &next).is_err());
    assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
    let extra: i64 = conn
        .query_row(
            "SELECT count(*) FROM sqlite_schema WHERE name = 'extra'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(extra, 0);
}

#[test]
fn refuses_a_database_from_a_newer_app() {
    let db = TempDb::new("newer");
    {
        let conn = Connection::open(&db.0).unwrap();
        conn.execute_batch(&format!("PRAGMA user_version = {}", SCHEMA_VERSION + 1))
            .unwrap();
    }
    let err = Store::open(&db.0).unwrap_err();
    assert!(
        matches!(err, CoreError::Storage(ref m) if m.contains("newer version")),
        "{err:?}"
    );
    let conn = Connection::open(&db.0).unwrap();
    assert_eq!(
        user_version(&conn).unwrap(),
        SCHEMA_VERSION + 1,
        "left untouched"
    );
}

#[test]
fn a_file_that_is_not_a_database_is_an_error() {
    let db = TempDb::new("junk");
    std::fs::write(&db.0, vec![0x5au8; 8192]).unwrap();
    assert!(matches!(Store::open(&db.0), Err(CoreError::Storage(_))));
    assert!(Store::open("/definitely/not/a/dir/moto.db").is_err());
}

#[test]
fn damaged_rows_are_errors_not_panics() {
    let damage = [
        "UPDATE sections SET geometry = x'0102'",
        "UPDATE sections SET geometry = x'00000000000000000000000000000000' || x'00'",
        "UPDATE sections SET geometry = x'ffffff7fffffff7fffffff7fffffff7f'",
        "UPDATE section_ways SET from_idx = -1",
    ];
    for sql in damage {
        let mut s = store();
        let id = s.add_section(&sample(), T0).unwrap().id;
        // CHECK constraints stop some damage outright; the rest must be
        // caught when reading.
        if s.conn.execute_batch(sql).is_ok() {
            assert!(
                matches!(s.get_section(id), Err(CoreError::Storage(_))),
                "{sql}"
            );
            assert!(s.list_sections(None).is_err(), "{sql}");
        }
    }
    // Values outside the enums cannot even be written.
    for bad in ["rating = 9", "direction = 5", "source = -1", "status = 7"] {
        let mut s = store();
        s.add_section(&sample(), T0).unwrap();
        let sql = format!("UPDATE sections SET {bad}");
        assert!(s.conn.execute_batch(&sql).is_err(), "{bad}");
    }
}

#[test]
fn text_is_never_interpreted_as_sql() {
    let mut s = store();
    let mut new = sample();
    new.name = "x'); DROP TABLE sections; --".into();
    let id = s.add_section(&new, T0).unwrap().id;
    assert_eq!(s.get_section(id).unwrap().unwrap().name, new.name);
    assert_eq!(s.list_sections(None).unwrap().len(), 1);
}

#[test]
fn defensive_settings_are_on() {
    let s = store();
    let fk: i64 = s
        .conn
        .query_row("PRAGMA foreign_keys", [], |r| r.get(0))
        .unwrap();
    let sd: i64 = s
        .conn
        .query_row("PRAGMA secure_delete", [], |r| r.get(0))
        .unwrap();
    assert_eq!((fk, sd), (1, 1));
    assert!(
        s.conn
            .db_config(DbConfig::SQLITE_DBCONFIG_DEFENSIVE)
            .unwrap()
    );
    assert!(
        !s.conn
            .db_config(DbConfig::SQLITE_DBCONFIG_TRUSTED_SCHEMA)
            .unwrap()
    );
    // Defensive mode refuses to let SQL corrupt the file directly.
    assert!(
        s.conn
            .execute_batch("PRAGMA writable_schema = ON; DELETE FROM sqlite_schema;")
            .is_err()
    );
}
