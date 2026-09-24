// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `--match`: map-matches a ride exported from the app (GPX) against a
//! region file and reports how well it fits, to check the matcher on real
//! rides. Optionally writes GeoJSON of the ride and the matched pieces to
//! look at on a map.

use std::path::Path;
use std::time::Instant;

use moto_core::matching::{MAX_TRACK_POINTS, MatchedTrack};
use moto_core::{Engine, LatLon};

/// Largest GPX file read: 200 000 fixes at about 200 bytes each.
const MAX_GPX_BYTES: u64 = 64 * 1024 * 1024;

/// The positions of a GPX file's `<trkpt>` elements, in order. The file is
/// untrusted: anything that isn't a well-formed point with valid lat/lon
/// attributes is skipped, and size and count are capped.
pub fn gpx_points(text: &str) -> Result<Vec<LatLon>, String> {
    let mut points = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find("<trkpt") {
        rest = &rest[start + "<trkpt".len()..];
        // A tag ends at '>'; a '<' first means it was cut off.
        let Some(end) = rest.find(['>', '<']) else {
            break;
        };
        let tag = &rest[..end];
        rest = &rest[end..];
        // "<trkpt" must be the whole element name, not "<trkptx".
        if !tag.starts_with(char::is_whitespace) {
            continue;
        }
        let (Some(lat), Some(lon)) = (attr(tag, "lat"), attr(tag, "lon")) else {
            continue;
        };
        if let Ok(p) = LatLon::new(lat, lon) {
            if points.len() == MAX_TRACK_POINTS {
                return Err(format!("more than {MAX_TRACK_POINTS} track points"));
            }
            points.push(p);
        }
    }
    Ok(points)
}

/// The number in attribute `name="…"` (or `'…'`) of a tag's text.
fn attr(tag: &str, name: &str) -> Option<f64> {
    let mut search = tag;
    loop {
        let i = search.find(name)?;
        let before = search[..i].chars().next_back();
        let after = &search[i + name.len()..];
        search = after;
        if !before.is_some_and(char::is_whitespace) {
            continue; // part of a longer name, e.g. "xlat"
        }
        let after = after.trim_start().strip_prefix('=')?.trim_start();
        let quote = after.chars().next().filter(|&c| c == '"' || c == '\'')?;
        let value = &after[1..];
        let close = value.find(quote)?;
        return value[..close]
            .trim()
            .parse()
            .ok()
            .filter(|v: &f64| v.is_finite());
    }
}

/// Runs `--match`.
pub fn run(region: &Path, gpx: &Path, geojson: Option<&Path>) -> Result<(), String> {
    let size = std::fs::metadata(gpx)
        .map_err(|e| format!("{}: {e}", gpx.display()))?
        .len();
    if size > MAX_GPX_BYTES {
        return Err(format!(
            "{} is larger than {MAX_GPX_BYTES} bytes",
            gpx.display()
        ));
    }
    let text = std::fs::read(gpx).map_err(|e| format!("{}: {e}", gpx.display()))?;
    let points = gpx_points(&String::from_utf8_lossy(&text))?;
    let engine = Engine::open(region).map_err(|e| e.to_string())?;
    let t = Instant::now();
    let m = engine.match_track(&points).map_err(|e| e.to_string())?;
    let ms = t.elapsed().as_secs_f64() * 1e3;
    for line in report(&points, &m, ms) {
        println!("{line}");
    }
    if let Some(out) = geojson {
        std::fs::write(out, to_geojson(&points, &m))
            .map_err(|e| format!("{}: {e}", out.display()))?;
        println!("geojson   {}", out.display());
    }
    Ok(())
}

/// Summary lines: fixes, length, matched share, pieces and gaps.
pub fn report(points: &[LatLon], m: &MatchedTrack, ms: f64) -> Vec<String> {
    let ridden: f64 = points.windows(2).map(|w| w[0].distance_m(&w[1])).sum();
    // An empty float sum is -0.0; adding 0.0 prints it as 0.0.
    let matched: f64 = m.pieces.iter().map(|p| p.distance_m).sum::<f64>() + 0.0;
    let share = if ridden > 0.0 {
        matched / ridden * 100.0
    } else {
        0.0
    };
    let mut lines = vec![
        format!(
            "ride      {} fixes, {:.1} km (fix to fix)",
            points.len(),
            ridden / 1000.0
        ),
        format!(
            "matched   {:.1} km ({share:.1} %) in {} pieces, {} OSM way spans, {ms:.1} ms",
            matched / 1000.0,
            m.pieces.len(),
            m.pieces.iter().map(|p| p.ways.len()).sum::<usize>()
        ),
    ];
    for (i, p) in m.pieces.iter().enumerate() {
        lines.push(format!(
            "piece {i:<3} fixes {}–{}, {:.2} km, {} way spans",
            p.first_point,
            p.last_point,
            p.distance_m / 1000.0,
            p.ways.len()
        ));
    }
    for w in m.pieces.windows(2) {
        let (a, b) = (w[0].last_point, w[1].first_point);
        lines.push(format!(
            "gap       fixes {a}–{b} not matched ({} fixes)",
            b.saturating_sub(a)
        ));
    }
    lines
}

/// A FeatureCollection: the ride (`kind: ride`) and each matched piece
/// (`kind: matched`). Only numbers from the input reach the output.
pub fn to_geojson(points: &[LatLon], m: &MatchedTrack) -> String {
    fn line(ps: &[LatLon]) -> String {
        let coords: Vec<String> = ps
            .iter()
            .map(|p| format!("[{:.7},{:.7}]", p.lon, p.lat))
            .collect();
        format!(
            "{{\"type\":\"LineString\",\"coordinates\":[{}]}}",
            coords.join(",")
        )
    }
    let mut features = vec![format!(
        "{{\"type\":\"Feature\",\"properties\":{{\"kind\":\"ride\"}},\"geometry\":{}}}",
        line(points)
    )];
    for (i, p) in m.pieces.iter().enumerate() {
        features.push(format!(
            "{{\"type\":\"Feature\",\"properties\":{{\"kind\":\"matched\",\"piece\":{i}}},\"geometry\":{}}}",
            line(&p.geometry)
        ));
    }
    format!(
        "{{\"type\":\"FeatureCollection\",\"features\":[\n{}\n]}}\n",
        features.join(",\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_points_from_the_apps_gpx() {
        let gpx = moto_core::gpx::track_gpx(
            "ride",
            &[
                moto_core::track::TrackPoint {
                    time_ms: 0,
                    position: LatLon {
                        lat: 55.7,
                        lon: 13.2,
                    },
                    accuracy_m: Some(3.0),
                    speed_mps: None,
                    bearing_deg: None,
                },
                moto_core::track::TrackPoint {
                    time_ms: 1000,
                    position: LatLon {
                        lat: 55.7001,
                        lon: 13.2002,
                    },
                    accuracy_m: None,
                    speed_mps: None,
                    bearing_deg: None,
                },
            ],
        );
        let p = gpx_points(&gpx).unwrap();
        assert_eq!(
            p,
            [
                LatLon {
                    lat: 55.7,
                    lon: 13.2
                },
                LatLon {
                    lat: 55.7001,
                    lon: 13.2002
                }
            ]
        );
    }

    #[test]
    fn reads_other_gpx_writers() {
        let text = "<trkpt lon='13.2'  lat = '55.7' ><ele>5</ele></trkpt>\n\
                    <trkpt\n lat=\"55.8\"\n lon=\"13.3\"/>";
        assert_eq!(
            gpx_points(text).unwrap(),
            [
                LatLon {
                    lat: 55.7,
                    lon: 13.2
                },
                LatLon {
                    lat: 55.8,
                    lon: 13.3
                }
            ]
        );
    }

    #[test]
    fn skips_anything_malformed() {
        let text = concat!(
            "<trkptx lat=\"1\" lon=\"1\">",
            "<trkpt xlat=\"1\" lon=\"1\">",
            "<trkpt lat=\"NaN\" lon=\"1\">",
            "<trkpt lat=\"95\" lon=\"1\">",
            "<trkpt lat=\"inf\" lon=\"1\">",
            "<trkpt lat=\"1\">",
            "<trkpt lat=1 lon=2>",
            "<trkpt lat=\"1\" lon=\"2",
            "<trkpt lat=\"55.1\" lon=\"13.1\">",
            "<trkpt lat=\"55.2\" lon=\"13.2\""
        );
        assert_eq!(
            gpx_points(text).unwrap(),
            [LatLon {
                lat: 55.1,
                lon: 13.1
            }]
        );
        assert!(gpx_points("").unwrap().is_empty());
        assert!(gpx_points("<trkpt").unwrap().is_empty());
    }

    #[test]
    fn random_bytes_never_panic() {
        let mut x: u64 = 0x2545_f491_4f6c_dd1d;
        let alphabet = b"<trkpt lat=lon\"'>0123456789.-/ \n";
        for _ in 0..500 {
            let len = (x % 300) as usize;
            let text: String = (0..len)
                .map(|_| {
                    x ^= x << 13;
                    x ^= x >> 7;
                    x ^= x << 17;
                    alphabet[(x % alphabet.len() as u64) as usize] as char
                })
                .collect();
            let _ = gpx_points(&text);
        }
    }

    #[test]
    fn too_many_points_is_an_error() {
        let one = "<trkpt lat=\"55.7\" lon=\"13.2\"/>";
        assert!(gpx_points(&one.repeat(MAX_TRACK_POINTS + 1)).is_err());
    }

    #[test]
    fn reports_and_writes_geojson() {
        let points = [
            LatLon {
                lat: 55.7,
                lon: 13.2,
            },
            LatLon {
                lat: 55.7,
                lon: 13.21,
            },
        ];
        let m = MatchedTrack::default();
        let lines = report(&points, &m, 1.0);
        assert!(lines[0].contains("2 fixes, 0.6 km"), "{lines:?}");
        assert!(lines[1].contains("0.0 km (0.0 %) in 0 pieces"), "{lines:?}");
        let json = to_geojson(&points, &m);
        assert!(
            json.contains("[13.2000000,55.7000000],[13.2100000,55.7000000]"),
            "{json}"
        );
        assert!(json.starts_with("{\"type\":\"FeatureCollection\""));
    }
}
