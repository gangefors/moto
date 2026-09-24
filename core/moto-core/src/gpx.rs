// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! GPX 1.1 export of recorded rides, for the rider to take a ride out of
//! the app (backup, other apps, or checking map matching on real data),
//! and of routes, to hand them to a nav app (PRD R9).

use std::fmt::Write;

use crate::LatLon;
use crate::track::TrackPoint;

const HEADER: &str = concat!(
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n",
    "<gpx version=\"1.1\" creator=\"moto\" xmlns=\"http://www.topografix.com/GPX/1/1\" ",
    "xmlns:moto=\"https://github.com/gangefors/moto/gpx/1\">\n",
);

/// A route as a GPX 1.1 document for a nav app: the route points as a
/// `<rte>` (for apps that route between points; see
/// [`crate::handoff::route_points`]) and the exact line as a `<trk>` (for
/// apps that follow a track), both named `name`.
pub fn route_gpx(name: &str, route_points: &[LatLon], line: &[LatLon]) -> String {
    let name = escape(name);
    let mut out = String::with_capacity(300 + (route_points.len() + line.len()) * 60);
    out.push_str(HEADER);
    let _ = writeln!(out, "<metadata><name>{name}</name></metadata>");
    let _ = writeln!(out, "<rte>\n<name>{name}</name>");
    for p in route_points {
        let _ = writeln!(out, "<rtept lat=\"{:.7}\" lon=\"{:.7}\"/>", p.lat, p.lon);
    }
    let _ = writeln!(out, "</rte>\n<trk>\n<name>{name}</name>\n<trkseg>");
    for p in line {
        let _ = writeln!(out, "<trkpt lat=\"{:.7}\" lon=\"{:.7}\"/>", p.lat, p.lon);
    }
    out.push_str("</trkseg>\n</trk>\n</gpx>\n");
    out
}

/// A ride as a GPX 1.1 document: one track, one segment, a point per fix
/// with its time. Speed, bearing and accuracy go in a `moto` extension so
/// the file can be read back without losing them.
pub fn track_gpx(name: &str, points: &[TrackPoint]) -> String {
    let mut out = String::with_capacity(200 + points.len() * 160);
    out.push_str(HEADER);
    out.push_str("<trk>\n");
    let _ = writeln!(out, "<name>{}</name>", escape(name));
    out.push_str("<trkseg>\n");
    for p in points {
        let _ = write!(
            out,
            "<trkpt lat=\"{:.7}\" lon=\"{:.7}\"><time>{}</time>",
            p.position.lat,
            p.position.lon,
            iso_time(p.time_ms)
        );
        if p.accuracy_m.is_some() || p.speed_mps.is_some() || p.bearing_deg.is_some() {
            out.push_str("<extensions>");
            for (tag, v) in [
                ("accuracy", p.accuracy_m),
                ("speed", p.speed_mps),
                ("bearing", p.bearing_deg),
            ] {
                if let Some(v) = v.filter(|v| v.is_finite()) {
                    let _ = write!(out, "<moto:{tag}>{v:.2}</moto:{tag}>");
                }
            }
            out.push_str("</extensions>");
        }
        out.push_str("</trkpt>\n");
    }
    out.push_str("</trkseg>\n</trk>\n</gpx>\n");
    out
}

/// Text safe inside an XML element: markup characters escaped and
/// characters XML 1.0 does not allow dropped.
fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            '\t' | '\n' | '\r' => out.push(c),
            c if (c as u32) < 0x20 || c == '\u{fffe}' || c == '\u{ffff}' => {}
            c => out.push(c),
        }
    }
    out
}

/// UTC time as ISO 8601 with milliseconds, e.g. `2026-09-24T07:30:00.000Z`.
fn iso_time(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let millis = ms.rem_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{millis:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

/// Year, month and day of a day count since 1970-01-01 (proleptic
/// Gregorian; Howard Hinnant's algorithm).
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::LatLon;

    fn fix(time_ms: i64) -> TrackPoint {
        TrackPoint {
            time_ms,
            position: LatLon {
                lat: 55.7,
                lon: 13.2,
            },
            accuracy_m: Some(4.5),
            speed_mps: None,
            bearing_deg: Some(90.0),
        }
    }

    #[test]
    fn formats_times_in_utc() {
        assert_eq!(iso_time(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(iso_time(951_782_400_000), "2000-02-29T00:00:00.000Z");
        assert_eq!(iso_time(1_790_000_000_123), "2026-09-21T14:13:20.123Z");
        assert_eq!(iso_time(4_102_444_799_999), "2099-12-31T23:59:59.999Z");
        assert_eq!(iso_time(-1), "1969-12-31T23:59:59.999Z");
    }

    #[test]
    fn writes_a_gpx_track() {
        let gpx = track_gpx("Ride <1> & \"more\"\u{0}", &[fix(0), fix(1000)]);
        assert!(
            gpx.starts_with("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"")
        );
        assert!(
            gpx.contains("<name>Ride &lt;1&gt; &amp; &quot;more&quot;</name>"),
            "{gpx}"
        );
        assert_eq!(gpx.matches("<trkpt ").count(), 2);
        assert!(gpx.contains(
            "<trkpt lat=\"55.7000000\" lon=\"13.2000000\"><time>1970-01-01T00:00:01.000Z</time>\
             <extensions><moto:accuracy>4.50</moto:accuracy><moto:bearing>90.00</moto:bearing>\
             </extensions></trkpt>"
        ));
        assert!(gpx.ends_with("</trkseg>\n</trk>\n</gpx>\n"));
    }

    #[test]
    fn fixes_without_extras_have_no_extensions() {
        let bare = TrackPoint {
            accuracy_m: None,
            bearing_deg: None,
            ..fix(0)
        };
        let gpx = track_gpx("", &[bare]);
        assert!(!gpx.contains("<extensions>"));
        assert!(gpx.contains("<name></name>"));
        assert_eq!(track_gpx("x", &[]).matches("<trkpt").count(), 0);
    }

    #[test]
    fn writes_a_gpx_route_and_track() {
        let ll = |lat, lon| LatLon { lat, lon };
        let line = [ll(55.7, 13.2), ll(55.71, 13.2), ll(55.72, 13.21)];
        let gpx = route_gpx("Route <1> & more", &[line[0], line[2]], &line);
        assert!(
            gpx.starts_with("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"")
        );
        assert!(gpx.ends_with("</trkseg>\n</trk>\n</gpx>\n"));
        assert_eq!(
            gpx.matches("<name>Route &lt;1&gt; &amp; more</name>")
                .count(),
            3
        );
        assert_eq!(gpx.matches("<rtept ").count(), 2);
        assert_eq!(gpx.matches("<trkpt ").count(), 3);
        assert!(gpx.contains("<rtept lat=\"55.7200000\" lon=\"13.2100000\"/>"));
        // The route points come before the track.
        assert!(gpx.find("<rte>").unwrap() < gpx.find("<trk>").unwrap());
    }
}
