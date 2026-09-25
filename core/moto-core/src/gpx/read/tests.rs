// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::gpx::{iso_time, track_gpx};

const NOW: i64 = 1_790_000_000_000;

fn fix(time_ms: i64, lat: f64, lon: f64) -> TrackPoint {
    TrackPoint {
        time_ms,
        position: LatLon { lat, lon },
        accuracy_m: None,
        speed_mps: None,
        bearing_deg: None,
    }
}

#[test]
fn reads_back_the_apps_own_export() {
    let ride = vec![
        TrackPoint {
            accuracy_m: Some(4.5),
            speed_mps: Some(12.25),
            bearing_deg: Some(90.0),
            ..fix(1_790_000_000_123, 55.7, 13.2)
        },
        fix(1_790_000_001_123, 55.7001, 13.2002),
        TrackPoint {
            speed_mps: Some(13.0),
            ..fix(1_790_000_002_000, 55.7002, 13.2004)
        },
    ];
    let back = read_track(&track_gpx("ride & <more>", &ride), NOW).unwrap();
    assert_eq!(back, ride);
}

#[test]
fn reads_other_writers() {
    let text = "<gpx><trk><trkseg>\n\
        <trkpt lon='13.2'  lat = '55.7' ><ele>5</ele><time>2026-09-24T07:30:00Z</time></trkpt>\n\
        <trkpt\n lat=\"55.8\"\n lon=\"13.3\"><time> 2026-09-24T09:30:01.5+02:00 </time></trkpt>\n\
        </trkseg></trk></gpx>";
    let p = read_track(text, NOW).unwrap();
    assert_eq!(p.len(), 2);
    assert_eq!(
        p[0].position,
        LatLon {
            lat: 55.7,
            lon: 13.2
        }
    );
    assert_eq!(p[1].time_ms - p[0].time_ms, 1500);
    assert_eq!(iso_time(p[0].time_ms), "2026-09-24T07:30:00.000Z");
}

#[test]
fn points_without_times_are_a_second_apart() {
    let text = r#"<trkpt lat="55.1" lon="13.1"/><trkpt lat="55.2" lon="13.2"/>
        <trkpt lat="55.3" lon="13.3"><time>2026-09-24T07:30:00Z</time></trkpt>"#;
    let p = read_track(text, NOW).unwrap();
    // Not every point has a time: all are spaced from the first time.
    let t0 = parse_time_ms("2026-09-24T07:30:00Z").unwrap();
    assert_eq!(
        p.iter().map(|p| p.time_ms).collect::<Vec<_>>(),
        [t0, t0 + 1000, t0 + 2000]
    );
    // No times at all: from now.
    let p = read_track(
        r#"<trkpt lat="55.1" lon="13.1"/><trkpt lat="55.2" lon="13.2"/>"#,
        NOW,
    )
    .unwrap();
    assert_eq!((p[0].time_ms, p[1].time_ms), (NOW, NOW + 1000));
}

#[test]
fn times_never_go_back() {
    let text = r#"<trkpt lat="55.1" lon="13.1"><time>2026-09-24T07:30:05Z</time></trkpt>
        <trkpt lat="55.2" lon="13.2"><time>2026-09-24T07:30:05Z</time></trkpt>
        <trkpt lat="55.3" lon="13.3"><time>2026-09-24T07:30:01Z</time></trkpt>"#;
    let p = read_track(text, NOW).unwrap();
    assert_eq!(p[1].time_ms, p[0].time_ms + 1);
    assert_eq!(p[2].time_ms, p[0].time_ms + 2);
}

#[test]
fn a_route_is_read_when_there_is_no_track() {
    let text = r#"<rte><rtept lat="55.1" lon="13.1"/><rtept lat="55.2" lon="13.2"/></rte>"#;
    assert_eq!(read_track(text, NOW).unwrap().len(), 2);
    // A track wins over a route in the same file.
    let both = format!("{text}<trkpt lat=\"56\" lon=\"14\"/><trkpt lat=\"56.1\" lon=\"14\"/>");
    assert_eq!(read_track(&both, NOW).unwrap()[0].position.lat, 56.0);
}

#[test]
fn malformed_points_and_values_are_skipped() {
    let text = concat!(
        "<trkptx lat=\"1\" lon=\"1\">",
        "<trkpt xlat=\"1\" lon=\"1\">",
        "<trkpt lat=\"NaN\" lon=\"1\">",
        "<trkpt lat=\"95\" lon=\"1\">",
        "<trkpt lat=\"inf\" lon=\"1\">",
        "<trkpt lat=\"1\">",
        "<trkpt lat=1 lon=2>",
        "<trkpt lat=\"1\" lon=\"2",
        "<trkpt lat=\"55.1\" lon=\"13.1\"><extensions><moto:speed>900</moto:speed>",
        "<moto:bearing>360</moto:bearing><moto:accuracy>-1</moto:accuracy></extensions></trkpt>",
        "<trkpt lat=\"55.2\" lon=\"13.2\"><moto:speed>NaN</moto:speed></trkpt>",
        "<trkpt lat=\"55.3\" lon=\"13.3\""
    );
    let p = read_track(text, NOW).unwrap();
    assert_eq!(p.len(), 2);
    assert!(
        p.iter()
            .all(|p| p.speed_mps.is_none() && p.bearing_deg.is_none() && p.accuracy_m.is_none())
    );
}

#[test]
fn too_little_or_too_much_is_a_typed_error() {
    for text in [
        "",
        "<gpx/>",
        "<trkpt",
        r#"<trkpt lat="55" lon="13"/>"#,
        "\u{0}\u{ffff}<<<>>>",
    ] {
        assert!(
            matches!(read_track(text, NOW), Err(CoreError::InvalidArgument(_))),
            "{text:?}"
        );
    }
    let big = " ".repeat(MAX_GPX_BYTES + 1);
    assert!(read_track(&big, NOW).is_err());
    let many = r#"<trkpt lat="55" lon="13"/>"#.repeat(MAX_TRACK_POINTS + 1);
    assert!(read_track(&many, NOW).is_err());
}

#[test]
fn every_prefix_of_a_file_reads_or_fails_cleanly() {
    let ride: Vec<TrackPoint> = (0..20)
        .map(|i| fix(NOW + i * 1000, 55.7 + i as f64 * 1e-4, 13.2))
        .collect();
    let text = track_gpx("r", &ride);
    for end in (0..text.len()).filter(|&i| text.is_char_boundary(i)) {
        if let Ok(p) = read_track(&text[..end], NOW) {
            assert!(p.len() >= 2 && p.len() <= ride.len());
            assert!(p.windows(2).all(|w| w[1].time_ms > w[0].time_ms));
        }
    }
}

#[test]
fn random_bytes_never_panic() {
    let mut x: u64 = 0x2545_f491_4f6c_dd1d;
    let alphabet = b"<trkpt rtept lat=lon\"'>0123456789.-/ \n<time>TZ:+</time>moto:speed";
    for _ in 0..2000 {
        let len = (x % 400) as usize;
        let text: String = (0..len)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 7;
                x ^= x << 17;
                alphabet[(x % alphabet.len() as u64) as usize] as char
            })
            .collect();
        if let Ok(p) = read_track(&text, NOW) {
            assert!(p.len() >= 2);
        }
    }
}

#[test]
fn unclosed_points_are_read_in_linear_time() {
    let text = r#"<trkpt lat="55" lon="13"><time>x"#.repeat(MAX_TRACK_POINTS);
    let t = std::time::Instant::now();
    assert_eq!(read_track(&text, NOW).unwrap().len(), MAX_TRACK_POINTS);
    assert!(t.elapsed().as_secs() < 5, "{:?}", t.elapsed());
}

#[test]
fn times_parse_strictly() {
    let t = parse_time_ms;
    assert_eq!(t("1970-01-01T00:00:00Z"), Some(0));
    assert_eq!(
        t("2026-09-24T07:30:00.123Z"),
        Some(parse_time_ms("2026-09-24T07:30:00Z").unwrap() + 123)
    );
    assert_eq!(
        t("2026-09-24T07:30:00.1239Z"),
        t("2026-09-24T07:30:00.123Z")
    );
    assert_eq!(t("2026-09-24T09:30:00+02:00"), t("2026-09-24T07:30:00Z"));
    assert_eq!(t("2026-09-24T05:30:00-02:00"), t("2026-09-24T07:30:00Z"));
    assert_eq!(
        t("2024-02-29T00:00:00Z").map(iso_time).as_deref(),
        Some("2024-02-29T00:00:00.000Z")
    );
    for bad in [
        "",
        "2026-09-24",
        "2026-09-24T07:30:00",
        "2026-09-24T07:30:00ZZ",
        "2026-13-01T00:00:00Z",
        "2026-02-29T00:00:00Z",
        "2026-09-31T00:00:00Z",
        "2026-09-24T24:00:00Z",
        "2026-09-24T07:60:00Z",
        "2026-09-24T07:30:00.Z",
        "2026-09-24T07:30:00+2:00",
        "1969-12-31T23:59:59Z",
        "2101-01-01T00:00:00Z",
        "２026-09-24T07:30:00Z",
        "2026-09-24T07:30:00+24:00",
        "+2026-09-24T07:30:00Z",
    ] {
        assert_eq!(t(bad), None, "{bad}");
    }
    // Round trip over the whole range, a few hundred days apart.
    for day in (0..47_000).step_by(371) {
        let ms = day * 86_400_000 + 12_345_678;
        assert_eq!(t(&iso_time(ms)), Some(ms));
    }
}
