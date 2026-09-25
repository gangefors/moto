// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! GPX import: the track points of a file from another app or an earlier
//! export, as a ride. The file is untrusted: it is scanned for points
//! without an XML parser (no entities, no DTDs, nothing fetched), sizes
//! and counts are capped, and anything malformed is skipped.

use crate::track::{MAX_TRACK_POINTS, TrackPoint};
use crate::{CoreError, LatLon};

/// Largest GPX file read: 200 000 fixes at about 300 bytes each.
pub const MAX_GPX_BYTES: usize = 64 * 1024 * 1024;

/// The points of a GPX file's track (`<trkpt>`), or of its route
/// (`<rtept>`) if it has no track, in file order, as a ride. Times come
/// from `<time>` when every point has a valid one; later points never go
/// back in time (a repeated time is nudged 1 ms on). Without times, the
/// points are 1 s apart from the first valid time, or from `now_ms`.
/// Accuracy, speed and bearing come from the app's own `moto` extension
/// when present and valid. Fewer than two points is an error.
pub fn read_track(text: &str, now_ms: i64) -> Result<Vec<TrackPoint>, CoreError> {
    if text.len() > MAX_GPX_BYTES {
        return Err(CoreError::InvalidArgument(format!(
            "a GPX file may be at most {} MiB",
            MAX_GPX_BYTES >> 20
        )));
    }
    let mut raw = points(text, "trkpt")?;
    if raw.is_empty() {
        raw = points(text, "rtept")?;
    }
    if raw.len() < 2 {
        return Err(CoreError::InvalidArgument(
            "the GPX file has no track with at least two points".into(),
        ));
    }
    let timed = raw.iter().all(|p| p.time_ms.is_some());
    let start = raw.iter().find_map(|p| p.time_ms).unwrap_or(now_ms);
    let mut last = i64::MIN;
    let mut out = Vec::with_capacity(raw.len());
    for (i, r) in raw.into_iter().enumerate() {
        let time_ms = match r.time_ms {
            Some(t) if timed => t.max(last.saturating_add(1)),
            _ => start.saturating_add(i64::try_from(i).unwrap_or(i64::MAX).saturating_mul(1000)),
        };
        last = time_ms;
        let p = TrackPoint {
            time_ms,
            position: r.position,
            accuracy_m: r.accuracy_m,
            speed_mps: r.speed_mps,
            bearing_deg: r.bearing_deg,
        };
        p.validate()?;
        out.push(p);
    }
    Ok(out)
}

/// A point as found in the file.
struct Raw {
    position: LatLon,
    time_ms: Option<i64>,
    accuracy_m: Option<f64>,
    speed_mps: Option<f64>,
    bearing_deg: Option<f64>,
}

/// Every well-formed `<name lat=… lon=…>` element, with what its body
/// holds.
fn points(text: &str, name: &str) -> Result<Vec<Raw>, CoreError> {
    let open = format!("<{name}");
    let close = format!("</{name}");
    let mut out = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find(&open) {
        rest = &rest[start + open.len()..];
        // A tag ends at '>'; a '<' first means it was cut off.
        let Some(end) = rest.find(['>', '<']) else {
            break;
        };
        let tag = &rest[..end];
        rest = &rest[end..];
        // The element name must end here: "<trkpt", not "<trkptx".
        if !tag.starts_with(char::is_whitespace) {
            continue;
        }
        let (Some(lat), Some(lon)) = (attr(tag, "lat"), attr(tag, "lon")) else {
            continue;
        };
        let Ok(position) = LatLon::new(lat, lon) else {
            continue;
        };
        // The body runs to the closing tag or the next point, whichever
        // comes first; a self-closing tag has none.
        let body = if tag.ends_with('/') || !rest.starts_with('>') {
            ""
        } else {
            // Search for the close only up to the next point, so a file of
            // unclosed points is still read in linear time.
            let b = &rest[1..];
            let next = b.find(&open).unwrap_or(b.len());
            &b[..b[..next].find(&close).unwrap_or(next)]
        };
        if out.len() == MAX_TRACK_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "a ride may have at most {MAX_TRACK_POINTS} points"
            )));
        }
        out.push(Raw {
            position,
            time_ms: element(body, "time").and_then(parse_time_ms),
            accuracy_m: number(body, "moto:accuracy").filter(|v| (0.0..=10_000.0).contains(v)),
            speed_mps: number(body, "moto:speed").filter(|v| (0.0..=200.0).contains(v)),
            bearing_deg: number(body, "moto:bearing").filter(|v| (0.0..360.0).contains(v)),
        });
    }
    Ok(out)
}

/// The text of the first `<name>…</name>` in `body`, trimmed.
fn element<'a>(body: &'a str, name: &str) -> Option<&'a str> {
    let open = format!("<{name}>");
    let start = body.find(&open)? + open.len();
    let len = body[start..].find(&format!("</{name}>"))?;
    Some(body[start..start + len].trim())
}

/// The finite number in element `name`.
fn number(body: &str, name: &str) -> Option<f64> {
    element(body, name)?
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite())
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

/// Milliseconds since the Unix epoch of an ISO 8601 / RFC 3339 time as GPX
/// writes it: `YYYY-MM-DDTHH:MM:SS`, optional fraction, then `Z` or an
/// offset `±HH:MM`. `None` for anything else, or a time before 1970 or
/// after 2100.
pub(crate) fn parse_time_ms(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    let num = |from: usize, len: usize| -> Option<i64> {
        let d = b.get(from..from + len)?;
        d.iter().all(u8::is_ascii_digit).then(|| {
            d.iter()
                .fold(0i64, |acc, &c| acc * 10 + i64::from(c - b'0'))
        })
    };
    let sep = |at: usize, c: &[u8]| b.get(at).is_some_and(|x| c.contains(x));
    if !(sep(4, b"-") && sep(7, b"-") && sep(10, b"Tt ") && sep(13, b":") && sep(16, b":")) {
        return None;
    }
    let (y, mo, d) = (num(0, 4)?, num(5, 2)?, num(8, 2)?);
    let (h, mi, sec) = (num(11, 2)?, num(14, 2)?, num(17, 2)?);
    if !(1970..=2100).contains(&y)
        || !(1..=12).contains(&mo)
        || !(1..=days_in_month(y, mo)).contains(&d)
        || h > 23
        || mi > 59
        || sec > 60
    {
        return None;
    }
    let mut i = 19;
    let mut millis = 0;
    if sep(i, b".,") {
        i += 1;
        let digits = b[i..].iter().take_while(|c| c.is_ascii_digit()).count();
        if digits == 0 {
            return None;
        }
        // Milliseconds from the first three digits, the rest dropped.
        for k in 0..3 {
            millis = millis * 10
                + if k < digits {
                    i64::from(b[i + k] - b'0')
                } else {
                    0
                };
        }
        i += digits;
    }
    let offset_min = match b.get(i) {
        Some(b'Z' | b'z') if i + 1 == b.len() => 0,
        Some(&c @ (b'+' | b'-')) if i + 6 == b.len() && sep(i + 3, b":") => {
            let (oh, om) = (num(i + 1, 2)?, num(i + 4, 2)?);
            if oh > 23 || om > 59 {
                return None;
            }
            let m = oh * 60 + om;
            if c == b'+' { m } else { -m }
        }
        _ => return None,
    };
    let secs = days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + sec - offset_min * 60;
    let ms = secs * 1000 + millis;
    (0..=4_102_444_800_000).contains(&ms).then_some(ms)
}

fn days_in_month(y: i64, m: i64) -> i64 {
    match m {
        2 if (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's
/// algorithm, the inverse of `civil_from_days`).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[cfg(test)]
mod tests;
