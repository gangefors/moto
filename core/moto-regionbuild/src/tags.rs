// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Which OSM ways a motorcycle may use, and their edge attributes.

use moto_core::region::format::{PlaceKind, RoadClass, Surface, edge_flags};

use crate::speed::{self, Country, RoadKind};

/// Travel direction allowed on a way, relative to its node order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Oneway {
    No,
    Forward,
    Backward,
}

/// Edge attributes derived from a way's tags.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WayAttrs {
    pub class: RoadClass,
    pub speed_kmh: u8,
    pub surface: Surface,
    /// [`edge_flags`] bits, without `REVERSED`.
    pub flags: u8,
    pub oneway: Oneway,
}

/// Returns the attributes of a motorcycle-routable way in `country`, or
/// `None`.
pub fn classify(tags: &[(&str, &str)], country: Country) -> Option<WayAttrs> {
    let tag = |k: &str| tags.iter().find(|(key, _)| *key == k).map(|(_, v)| *v);
    let yes = |v: Option<&str>| matches!(v, Some("yes" | "designated" | "permissive"));

    if tag("area") == Some("yes") {
        return None;
    }
    let mut flags = 0u8;
    let (class, link) = if tag("route") == Some("ferry") {
        // Only ferries that explicitly take motor vehicles.
        let motor = ["motorcycle", "motor_vehicle", "motorcar"];
        if !motor.iter().any(|k| yes(tag(k))) {
            return None;
        }
        flags |= edge_flags::FERRY;
        (RoadClass::Ferry, false)
    } else {
        let (class, link) = match tag("highway")? {
            "motorway" => (RoadClass::Motorway, false),
            "motorway_link" => (RoadClass::Motorway, true),
            "trunk" => (RoadClass::Trunk, false),
            "trunk_link" => (RoadClass::Trunk, true),
            "primary" => (RoadClass::Primary, false),
            "primary_link" => (RoadClass::Primary, true),
            "secondary" => (RoadClass::Secondary, false),
            "secondary_link" => (RoadClass::Secondary, true),
            "tertiary" => (RoadClass::Tertiary, false),
            "tertiary_link" => (RoadClass::Tertiary, true),
            "unclassified" => (RoadClass::Unclassified, false),
            "residential" => (RoadClass::Residential, false),
            "living_street" => (RoadClass::LivingStreet, false),
            "service" => (RoadClass::Service, false),
            "track" => (RoadClass::Track, false),
            _ => return None,
        };
        if class == RoadClass::Service
            && matches!(
                tag("service"),
                Some(
                    "parking_aisle" | "driveway" | "drive-through" | "emergency_access" | "parking"
                )
            )
        {
            return None;
        }
        // Tracks are mostly closed to motor traffic unless tagged open.
        if class == RoadClass::Track && !(yes(tag("motorcycle")) || yes(tag("motor_vehicle"))) {
            return None;
        }
        (class, link)
    };
    if link {
        flags |= edge_flags::LINK;
    }

    // Most specific access tag wins.
    let access = ["motorcycle", "motor_vehicle", "vehicle", "access"]
        .iter()
        .find_map(|k| tag(k));
    match access {
        None | Some("yes" | "designated" | "permissive" | "official") => {}
        Some("destination" | "customers") => flags |= edge_flags::DESTINATION,
        Some(_) => return None,
    }
    if class == RoadClass::Ferry {
        flags &= !edge_flags::DESTINATION;
    }

    let roundabout = matches!(tag("junction"), Some("roundabout" | "circular"));
    if roundabout {
        flags |= edge_flags::ROUNDABOUT;
    }
    if motorcycle_toll(tags, class, country) == Some(true) {
        flags |= edge_flags::TOLL;
    }

    let oneway = match tag("oneway:motorcycle").or(tag("oneway")) {
        Some("yes" | "true" | "1") => Oneway::Forward,
        Some("-1" | "reverse") => Oneway::Backward,
        Some("no" | "false" | "0") => Oneway::No,
        Some("reversible" | "alternating") => return None,
        _ if roundabout || (class == RoadClass::Motorway) => Oneway::Forward,
        _ => Oneway::No,
    };

    // The signed number, else the limit an implicit code names, else the
    // road class's typical speed within the country's legal limit.
    let code = || {
        ["maxspeed:type", "source:maxspeed", "zone:maxspeed"]
            .iter()
            .find_map(|k| tag(k).and_then(speed::code_limit))
    };
    let kind = if class == RoadClass::Motorway {
        RoadKind::Motorway
    } else if tag("motorroad") == Some("yes") {
        RoadKind::Motorroad
    } else if class == RoadClass::LivingStreet {
        RoadKind::LivingStreet
    } else {
        RoadKind::Rural
    };
    let speed_kmh = tag("maxspeed")
        .and_then(parse_maxspeed)
        .or_else(code)
        .unwrap_or_else(|| {
            let typical = default_speed(class, link);
            if class == RoadClass::Ferry {
                typical
            } else {
                typical.min(country.limits().of(kind))
            }
        });

    // Gravel and other unpaved roads never above the country's limit for
    // unsigned rural roads (2026-09-30): nobody rides gravel
    // faster, whatever the sign says, and a few are tagged 90-120.
    let surface = surface(tag("surface"));
    let speed_kmh = if surface.is_paved() || class == RoadClass::Ferry {
        speed_kmh
    } else {
        speed_kmh.min(country.limits().rural)
    };

    Some(WayAttrs {
        class,
        speed_kmh,
        surface,
        flags,
        oneway,
    })
}

/// A toll booth (`barrier=toll_booth`); automatic gantries
/// (`highway=toll_gantry`) never charge motorcycles in these countries.
pub fn is_toll_booth(tags: &[(&str, &str)]) -> bool {
    tags.contains(&("barrier", "toll_booth"))
}

/// Whether a motorcycle pays toll on a way of `class` with `tags` in
/// `country` (`edge_flags::TOLL`): `Some(true)` or `Some(false)` when the
/// tags settle it, `None` when a toll booth on the way would make it a
/// toll road (see [`booth_makes_toll`]). Automatic road charges don't
/// apply to motorcycles in Norway (AutoPASS, city toll rings) or Sweden
/// (congestion tax, the Motala, Sundsvall and Skuru bridge charges), so
/// there only private toll roads and the Öresund link count; in Denmark
/// and Finland every toll does. A ferry is a ferry, whatever its fare.
pub fn motorcycle_toll(tags: &[(&str, &str)], class: RoadClass, country: Country) -> Option<bool> {
    let tag = |k: &str| tags.iter().find(|(key, _)| *key == k).map(|(_, v)| *v);
    if class == RoadClass::Ferry {
        return Some(false);
    }
    // Tags for motorcycles win.
    match tag("toll:motorcycle") {
        Some("yes") => return Some(true),
        Some("no") => return Some(false),
        _ => {}
    }
    if let Some(charge) = tag("charge:motorcycle") {
        let free = !charge.chars().any(|c| c.is_ascii_digit() && c != '0');
        return Some(!free);
    }
    let tolled = tag("toll") == Some("yes") || tag("toll:motor_vehicle") == Some("yes");
    let main = matches!(
        class,
        RoadClass::Motorway | RoadClass::Trunk | RoadClass::Primary | RoadClass::Secondary
    );
    match country {
        Country::Norway if main => Some(false),
        // Private toll roads in Norway are often mapped by their booth alone.
        Country::Norway if !tolled => None,
        Country::Sweden if main => Some(
            tolled
                && tag("operator")
                    .is_some_and(|o| o.contains("Øresundsbro") || o.contains("Öresundsbro")),
        ),
        _ => Some(tolled),
    }
}

/// Longest name or road number kept, in characters; longer ones are cut.
pub const MAX_NAME_CHARS: usize = 60;

/// A name from OSM data, made safe to show: control and formatting
/// characters (bidi overrides, zero-width marks) dropped, runs of white
/// space made one space, trimmed, cut to [`MAX_NAME_CHARS`]; `None` when
/// nothing is left.
pub fn clean_name(v: &str) -> Option<String> {
    let mut out = String::new();
    let mut space = false;
    for c in v.chars() {
        if c.is_whitespace() {
            space = !out.is_empty();
            continue;
        }
        if c.is_control() || is_format_char(c) {
            continue;
        }
        if out.chars().count() >= MAX_NAME_CHARS {
            break;
        }
        if space {
            out.push(' ');
            space = false;
        }
        out.push(c);
    }
    (!out.is_empty()).then_some(out)
}

/// Unicode format characters that can reorder or hide text.
fn is_format_char(c: char) -> bool {
    matches!(c,
        '\u{00AD}' | '\u{061C}' | '\u{180E}' | '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}'
        | '\u{2060}'..='\u{2064}' | '\u{2066}'..='\u{206F}' | '\u{FEFF}' | '\u{FFF9}'..='\u{FFFB}')
}

/// A road's number and name (`ref`, `name`), cleaned. Several numbers
/// (`13;108`) keep the first.
pub fn road_names(tags: &[(&str, &str)]) -> (Option<String>, Option<String>) {
    let tag = |k: &str| tags.iter().find(|(key, _)| *key == k).map(|(_, v)| *v);
    let road_ref = tag("ref").and_then(|v| clean_name(v.split(';').next().unwrap_or(v)));
    (road_ref, tag("name").and_then(clean_name))
}

/// A named place worth naming a road by: a city, town, village or
/// hamlet (`place=*` with a `name`).
pub fn place(tags: &[(&str, &str)]) -> Option<(PlaceKind, String)> {
    let tag = |k: &str| tags.iter().find(|(key, _)| *key == k).map(|(_, v)| *v);
    let kind = match tag("place")? {
        "city" => PlaceKind::City,
        "town" => PlaceKind::Town,
        "village" => PlaceKind::Village,
        "hamlet" => PlaceKind::Hamlet,
        _ => return None,
    };
    Some((kind, clean_name(tag("name")?)?))
}

/// Typical speeds in km/h by road class, for roads without a signed
/// limit (measured on Swedish roads; capped by each country's legal limit).
fn default_speed(class: RoadClass, link: bool) -> u8 {
    if link {
        return 60;
    }
    match class {
        RoadClass::Motorway => 110,
        RoadClass::Trunk => 90,
        RoadClass::Primary => 80,
        RoadClass::Secondary => 70,
        RoadClass::Tertiary => 60,
        RoadClass::Unclassified => 50,
        RoadClass::Residential => 40,
        RoadClass::LivingStreet => 7,
        RoadClass::Service => 20,
        RoadClass::Track => 20,
        RoadClass::Ferry => 15,
    }
}

/// Parses `maxspeed` values like `70`, `30 mph`, `walk` or an implicit
/// code like `SE:rural` (see [`speed::code_limit`]).
fn parse_maxspeed(v: &str) -> Option<u8> {
    let v = v.trim();
    if let Some(kmh) = speed::code_limit(v) {
        return Some(kmh);
    }
    let kmh = if v == "walk" {
        f64::from(speed::WALK)
    } else if let Some(mph) = v.strip_suffix("mph") {
        mph.trim().parse::<f64>().ok()? * 1.609_344
    } else {
        v.trim_end_matches("km/h").trim().parse::<f64>().ok()?
    };
    (kmh.is_finite() && kmh >= 1.0).then(|| kmh.round().min(250.0) as u8)
}

fn surface(v: Option<&str>) -> Surface {
    match v {
        Some("asphalt" | "chipseal") => Surface::Asphalt,
        Some("concrete" | "concrete:plates" | "concrete:lanes") => Surface::Concrete,
        Some("paved" | "metal") => Surface::Paved,
        Some("sett" | "cobblestone" | "unhewn_cobblestone" | "paving_stones" | "bricks") => {
            Surface::Sett
        }
        Some("compacted") => Surface::Compacted,
        Some("gravel" | "fine_gravel" | "pebblestone" | "unpaved") => Surface::Gravel,
        Some(
            "dirt" | "ground" | "earth" | "grass" | "sand" | "mud" | "woodchips" | "grass_paver",
        ) => Surface::Dirt,
        _ => Surface::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(tags: &[(&str, &str)]) -> Option<WayAttrs> {
        classify(tags, Country::Sweden)
    }

    fn speed_in(country: Country, tags: &[(&str, &str)]) -> u8 {
        classify(tags, country).unwrap().speed_kmh
    }

    #[test]
    fn keeps_ordinary_roads_with_defaults() {
        let a = c(&[("highway", "secondary")]).unwrap();
        assert_eq!(a.class, RoadClass::Secondary);
        assert_eq!(a.speed_kmh, 70);
        assert_eq!(a.surface, Surface::Unknown);
        assert_eq!(a.oneway, Oneway::No);
        assert_eq!(a.flags, 0);
    }

    #[test]
    fn drops_non_roads_and_closed_roads() {
        for tags in [
            &[("highway", "footway")][..],
            &[("highway", "cycleway")],
            &[("highway", "path")],
            &[("highway", "construction")],
            &[("building", "yes")],
            &[("highway", "residential"), ("access", "private")],
            &[("highway", "residential"), ("motor_vehicle", "no")],
            &[("highway", "tertiary"), ("motorcycle", "no")],
            &[("highway", "service"), ("service", "parking_aisle")],
            &[("highway", "service"), ("service", "driveway")],
            &[("highway", "track")],
            &[("highway", "pedestrian")],
            &[("highway", "residential"), ("area", "yes")],
            &[("highway", "primary"), ("oneway", "reversible")],
            &[("route", "ferry")],
        ] {
            assert_eq!(c(tags), None, "{tags:?}");
        }
    }

    #[test]
    fn specific_access_overrides_general() {
        assert!(
            c(&[
                ("highway", "residential"),
                ("access", "no"),
                ("motorcycle", "yes")
            ])
            .is_some()
        );
        assert!(c(&[("highway", "track"), ("motor_vehicle", "yes")]).is_some());
        let d = c(&[("highway", "service"), ("access", "destination")]).unwrap();
        assert_eq!(d.flags & edge_flags::DESTINATION, edge_flags::DESTINATION);
    }

    #[test]
    fn oneway_rules() {
        let one = |tags: &[(&str, &str)]| c(tags).unwrap().oneway;
        assert_eq!(
            one(&[("highway", "primary"), ("oneway", "yes")]),
            Oneway::Forward
        );
        assert_eq!(
            one(&[("highway", "primary"), ("oneway", "-1")]),
            Oneway::Backward
        );
        assert_eq!(one(&[("highway", "motorway")]), Oneway::Forward);
        assert_eq!(one(&[("highway", "motorway_link")]), Oneway::Forward);
        assert_eq!(
            one(&[("highway", "motorway"), ("oneway", "no")]),
            Oneway::No
        );
        let r = c(&[("highway", "tertiary"), ("junction", "roundabout")]).unwrap();
        assert_eq!(r.oneway, Oneway::Forward);
        assert_eq!(r.flags & edge_flags::ROUNDABOUT, edge_flags::ROUNDABOUT);
        assert_eq!(
            one(&[
                ("highway", "residential"),
                ("oneway", "yes"),
                ("oneway:motorcycle", "no")
            ]),
            Oneway::No
        );
    }

    #[test]
    fn links_ferries_and_tolls_are_flagged() {
        let l = c(&[("highway", "trunk_link")]).unwrap();
        assert_eq!(
            (l.class, l.flags, l.speed_kmh),
            (RoadClass::Trunk, edge_flags::LINK, 60)
        );
        let f = c(&[("route", "ferry"), ("motor_vehicle", "yes")]).unwrap();
        assert_eq!((f.class, f.flags), (RoadClass::Ferry, edge_flags::FERRY));
        let t = c(&[("highway", "unclassified"), ("toll", "yes")]).unwrap();
        assert_eq!(t.flags, edge_flags::TOLL);
    }

    #[test]
    fn parses_maxspeed() {
        assert_eq!(parse_maxspeed("70"), Some(70));
        assert_eq!(parse_maxspeed("30 mph"), Some(48));
        assert_eq!(parse_maxspeed("SE:rural"), Some(70));
        assert_eq!(parse_maxspeed("SE:urban"), Some(50));
        assert_eq!(parse_maxspeed("50 km/h"), Some(50));
        assert_eq!(parse_maxspeed("none"), None);
        assert_eq!(parse_maxspeed("0"), None);
        assert_eq!(parse_maxspeed("signals"), None);
        assert_eq!(parse_maxspeed("walk"), Some(7));
        assert_eq!(parse_maxspeed("DK:rural"), Some(80));
        assert_eq!(parse_maxspeed("50;70"), None);
        let s = c(&[("highway", "tertiary"), ("maxspeed", "80")]).unwrap();
        assert_eq!(s.speed_kmh, 80);
    }

    #[test]
    fn a_signed_number_wins_everywhere() {
        for country in Country::ALL {
            assert_eq!(
                speed_in(country, &[("highway", "primary"), ("maxspeed", "100")]),
                100
            );
            // Even over a code that says otherwise.
            assert_eq!(
                speed_in(
                    country,
                    &[
                        ("highway", "primary"),
                        ("maxspeed", "60"),
                        ("maxspeed:type", "SE:rural")
                    ]
                ),
                60
            );
        }
    }

    #[test]
    fn codes_in_other_keys_name_the_limit() {
        for key in ["maxspeed:type", "source:maxspeed", "zone:maxspeed"] {
            assert_eq!(
                speed_in(
                    Country::Denmark,
                    &[("highway", "tertiary"), (key, "DK:rural")]
                ),
                80,
                "{key}"
            );
            assert_eq!(
                speed_in(
                    Country::Norway,
                    &[("highway", "primary"), (key, "NO:urban")]
                ),
                50,
                "{key}"
            );
        }
        // A code with no number of its own is passed over.
        assert_eq!(
            speed_in(
                Country::Finland,
                &[("highway", "tertiary"), ("source:maxspeed", "sign")]
            ),
            60
        );
    }

    #[test]
    fn untagged_roads_keep_their_typical_speed_within_the_law() {
        // Sweden allows 70 outside built-up areas: a primary road's
        // typical 80 is capped, a tertiary road's 60 stays.
        assert_eq!(speed_in(Country::Sweden, &[("highway", "primary")]), 70);
        assert_eq!(speed_in(Country::Sweden, &[("highway", "trunk")]), 70);
        assert_eq!(speed_in(Country::Sweden, &[("highway", "tertiary")]), 60);
        // The other three allow 80.
        for country in [Country::Denmark, Country::Norway, Country::Finland] {
            assert_eq!(
                speed_in(country, &[("highway", "primary")]),
                80,
                "{country:?}"
            );
            assert_eq!(
                speed_in(country, &[("highway", "trunk")]),
                80,
                "{country:?}"
            );
            assert_eq!(
                speed_in(country, &[("highway", "secondary")]),
                70,
                "{country:?}"
            );
        }
        // Motorways and motorroads by their own limits.
        assert_eq!(speed_in(Country::Sweden, &[("highway", "motorway")]), 110);
        assert_eq!(speed_in(Country::Denmark, &[("highway", "motorway")]), 110);
        assert_eq!(speed_in(Country::Finland, &[("highway", "motorway")]), 80);
        assert_eq!(
            speed_in(
                Country::Denmark,
                &[("highway", "trunk"), ("motorroad", "yes")]
            ),
            80
        );
        assert_eq!(
            speed_in(
                Country::Sweden,
                &[("highway", "trunk"), ("motorroad", "yes")]
            ),
            70
        );
        // Links and slow roads are below every limit.
        assert_eq!(
            speed_in(Country::Finland, &[("highway", "motorway_link")]),
            60
        );
        assert_eq!(
            speed_in(Country::Denmark, &[("highway", "living_street")]),
            7
        );
        // Ferries keep their crossing speed.
        assert_eq!(
            speed_in(
                Country::Norway,
                &[("route", "ferry"), ("motor_vehicle", "yes")]
            ),
            15
        );
    }

    #[test]
    fn maps_surfaces() {
        assert_eq!(surface(Some("asphalt")), Surface::Asphalt);
        assert_eq!(surface(Some("gravel")), Surface::Gravel);
        assert_eq!(surface(Some("unpaved")), Surface::Gravel);
        assert_eq!(surface(Some("cobblestone")), Surface::Sett);
        assert_eq!(surface(Some("dirt")), Surface::Dirt);
        assert_eq!(surface(Some("weird")), Surface::Unknown);
        assert_eq!(surface(None), Surface::Unknown);
    }

    #[test]
    fn cleans_names_from_osm() {
        assert_eq!(clean_name("  Kvärnbyvägen "), Some("Kvärnbyvägen".into()));
        assert_eq!(clean_name("Väg\t  13\n"), Some("Väg 13".into()));
        assert_eq!(clean_name("a\u{202E}b\u{200B}c\u{0}"), Some("abc".into()));
        assert_eq!(clean_name(" \u{200B} "), None);
        assert_eq!(clean_name(""), None);
        let long = clean_name(&"é".repeat(500)).unwrap();
        assert_eq!(long.chars().count(), MAX_NAME_CHARS);
    }

    #[test]
    fn reads_road_numbers_names_and_places() {
        assert_eq!(
            road_names(&[("ref", "13;108"), ("name", "Storgatan")]),
            (Some("13".into()), Some("Storgatan".into()))
        );
        assert_eq!(road_names(&[("highway", "track")]), (None, None));
        assert_eq!(
            place(&[("place", "town"), ("name", "Höör")]),
            Some((PlaceKind::Town, "Höör".into()))
        );
        assert_eq!(place(&[("place", "village")]), None);
        assert_eq!(place(&[("place", "island"), ("name", "Ven")]), None);
        assert_eq!(place(&[("place", "hamlet"), ("name", "\u{202E}")]), None);
    }

    #[test]
    fn tolls_count_where_a_motorcycle_pays() {
        use RoadClass::*;
        let toll = |country: Country, class: RoadClass, tags: &[(&str, &str)]| {
            motorcycle_toll(tags, class, country)
        };
        let yes = [("toll", "yes")];
        // Norway: AutoPASS on main roads is free for motorcycles; private
        // toll roads are not, and a booth decides an untagged small road.
        assert_eq!(toll(Country::Norway, Trunk, &yes), Some(false));
        assert_eq!(toll(Country::Norway, Secondary, &yes), Some(false));
        assert_eq!(toll(Country::Norway, Unclassified, &yes), Some(true));
        assert_eq!(toll(Country::Norway, Unclassified, &[]), None);
        assert_eq!(toll(Country::Norway, Motorway, &[]), Some(false));
        // Sweden: bridge charges and congestion tax are free; the Öresund
        // link is not.
        assert_eq!(toll(Country::Sweden, Motorway, &yes), Some(false));
        let oresund = [("toll", "yes"), ("operator", "Øresundsbro Konsortiet I/S")];
        assert_eq!(toll(Country::Sweden, Motorway, &oresund), Some(true));
        assert_eq!(toll(Country::Sweden, Unclassified, &yes), Some(true));
        assert_eq!(toll(Country::Sweden, Unclassified, &[]), Some(false));
        // Denmark and Finland: every toll.
        assert_eq!(toll(Country::Denmark, Motorway, &yes), Some(true));
        assert_eq!(toll(Country::Finland, Tertiary, &yes), Some(true));
        assert_eq!(
            toll(Country::Denmark, Motorway, &[("toll", "snowmobile")]),
            Some(false)
        );
        // Motorcycle tags win; a zero charge is free.
        assert_eq!(
            toll(Country::Norway, Trunk, &[("toll:motorcycle", "yes")]),
            Some(true)
        );
        assert_eq!(
            toll(
                Country::Denmark,
                Motorway,
                &[("toll", "yes"), ("toll:motorcycle", "no")]
            ),
            Some(false)
        );
        assert_eq!(
            toll(Country::Norway, Trunk, &[("charge:motorcycle", "NOK 31")]),
            Some(true)
        );
        assert_eq!(
            toll(
                Country::Denmark,
                Motorway,
                &[("toll", "yes"), ("charge:motorcycle", "0 DKK")]
            ),
            Some(false)
        );
        // A ferry is a ferry, whatever its fare.
        assert_eq!(toll(Country::Denmark, Ferry, &yes), Some(false));
        // Through classify: the flag on the edge.
        let t = classify(
            &[("highway", "motorway"), ("toll", "yes")],
            Country::Denmark,
        )
        .unwrap();
        assert_ne!(t.flags & edge_flags::TOLL, 0);
        let f = classify(
            &[
                ("route", "ferry"),
                ("motor_vehicle", "yes"),
                ("toll", "yes"),
            ],
            Country::Denmark,
        )
        .unwrap();
        assert_eq!(f.flags & edge_flags::TOLL, 0);
        assert!(is_toll_booth(&[("barrier", "toll_booth")]));
        assert!(!is_toll_booth(&[("highway", "toll_gantry")]));
    }

    #[test]
    fn gravel_roads_stay_within_the_unsigned_rural_limit() {
        let gravel = |speed: Option<&'static str>| {
            let mut tags = vec![("highway", "tertiary"), ("surface", "gravel")];
            if let Some(s) = speed {
                tags.push(("maxspeed", s));
            }
            tags
        };
        // Signed faster than the country's rural limit: capped to it.
        assert_eq!(speed_in(Country::Sweden, &gravel(Some("120"))), 70);
        assert_eq!(speed_in(Country::Norway, &gravel(Some("100"))), 80);
        assert_eq!(speed_in(Country::Finland, &gravel(Some("90"))), 80);
        assert_eq!(speed_in(Country::Denmark, &gravel(Some("90"))), 80);
        // Signed slower: kept.
        assert_eq!(speed_in(Country::Sweden, &gravel(Some("50"))), 50);
        // Unsigned: the class's typical speed within the rural limit.
        assert_eq!(speed_in(Country::Sweden, &gravel(None)), 60);
        let primary = [("highway", "primary"), ("surface", "dirt")];
        assert_eq!(speed_in(Country::Sweden, &primary), 70);
        assert_eq!(speed_in(Country::Norway, &primary), 80);
        // Paved roads, and roads of unknown surface, keep their signed speed.
        assert_eq!(
            speed_in(
                Country::Sweden,
                &[("highway", "tertiary"), ("maxspeed", "90")]
            ),
            90
        );
        assert_eq!(
            speed_in(
                Country::Sweden,
                &[
                    ("highway", "tertiary"),
                    ("surface", "asphalt"),
                    ("maxspeed", "90")
                ]
            ),
            90
        );
        // A built-up gravel street keeps its lower limit.
        assert_eq!(
            speed_in(
                Country::Sweden,
                &[
                    ("highway", "residential"),
                    ("surface", "gravel"),
                    ("maxspeed", "SE:urban")
                ]
            ),
            50
        );
    }
}
