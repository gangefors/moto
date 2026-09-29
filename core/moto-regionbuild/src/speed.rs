// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Legal default speed limits by country (ADR-0005, ADR-0009): what a road
//! without a signed number allows. A way's speed is its `maxspeed` number
//! when it has one; else the limit its implicit code names (`DK:rural`,
//! also in `maxspeed:type`, `source:maxspeed` or `zone:maxspeed`); else a
//! typical speed for its road class, capped by the legal limit for the kind
//! of road it is in its country.
//!
//! The limits are read from each country's law (cited per country), with
//! the OSM wiki's table "Default speed limits" as the guide to the codes.
//! Only the four Nordic countries the regions cover are known; a region is
//! built for a known country only.

/// A country the regions cover.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Country {
    Sweden,
    Denmark,
    Norway,
    Finland,
}

impl Country {
    pub const ALL: [Country; 4] = [
        Country::Sweden,
        Country::Denmark,
        Country::Norway,
        Country::Finland,
    ];

    /// ISO 3166-1 alpha-2 code, as in OSM's implicit speed codes.
    pub fn code(self) -> &'static str {
        match self {
            Country::Sweden => "SE",
            Country::Denmark => "DK",
            Country::Norway => "NO",
            Country::Finland => "FI",
        }
    }

    /// The country with ISO code `code` (any case), if the regions cover it.
    pub fn from_code(code: &str) -> Option<Country> {
        Country::ALL
            .into_iter()
            .find(|c| c.code().eq_ignore_ascii_case(code))
    }

    /// The legal limits for motorcycles on roads without a signed number.
    pub fn limits(self) -> Limits {
        match self {
            // Trafikförordning (1998:1276) 3 kap. 17 §, 8 kap. 1 §:
            // 50 km/h in built-up areas, 70 elsewhere; 110 is the motorway
            // code's value; walking pace in living streets.
            Country::Sweden => Limits {
                urban: 50,
                rural: 70,
                motorroad: 70,
                motorway: 110,
                living_street: WALK,
            },
            // Færdselsloven §§ 42–43 a: 50 in built-up areas, 80 elsewhere
            // and on motorroads (motortrafikvej), 130 on motorways; living
            // streets (opholds- og legeområde) 15.
            Country::Denmark => Limits {
                urban: 50,
                rural: 80,
                motorroad: 80,
                motorway: 130,
                living_street: 15,
            },
            // Trafikkreglene (forskrift 1986-03-21 nr. 747) § 13: 50 in
            // built-up areas, 80 elsewhere; 110 is the motorway code's
            // value; walking pace in living streets (gatetun).
            Country::Norway => Limits {
                urban: 50,
                rural: 80,
                motorroad: 80,
                motorway: 110,
                living_street: WALK,
            },
            // Tieliikennelaki 729/2018 9 §, 43 §: 50 in built-up areas, 80
            // elsewhere; motorways have no own default (always signed), so
            // 80; living streets (pihakatu) 20.
            Country::Finland => Limits {
                urban: 50,
                rural: 80,
                motorroad: 80,
                motorway: 80,
                living_street: 20,
            },
        }
    }
}

/// Walking pace, km/h (`walk`, and living streets where the law says so).
pub const WALK: u8 = 7;

/// A country's legal default limits, km/h, by kind of road.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    pub urban: u8,
    pub rural: u8,
    pub motorroad: u8,
    pub motorway: u8,
    pub living_street: u8,
}

/// The kind of road the law tells apart, as far as the tags show it. Built
/// up or not is only known from a code; untagged roads count as rural (the
/// higher limit, so a cap never makes a road slower than it may be).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoadKind {
    Motorway,
    Motorroad,
    LivingStreet,
    Rural,
}

impl Limits {
    pub fn of(&self, kind: RoadKind) -> u8 {
        match kind {
            RoadKind::Motorway => self.motorway,
            RoadKind::Motorroad => self.motorroad,
            RoadKind::LivingStreet => self.living_street,
            RoadKind::Rural => self.rural,
        }
    }
}

/// The limit an implicit code names: `CC:urban`, `CC:rural`,
/// `CC:motorway`, `CC:motorroad`, `CC:living_street`, `CC:walk` or
/// `CC:zone30` / `CC:zone:30`, for a known country `CC`; `None` for
/// anything else.
pub fn code_limit(v: &str) -> Option<u8> {
    let (cc, rest) = v.trim().split_once(':')?;
    let l = Country::from_code(cc)?.limits();
    match rest {
        "urban" => Some(l.urban),
        "rural" => Some(l.rural),
        "motorway" => Some(l.motorway),
        "motorroad" => Some(l.motorroad),
        "living_street" => Some(l.living_street),
        "walk" => Some(WALK),
        zone => {
            let n = zone.strip_prefix("zone")?.trim_start_matches(':');
            let kmh: u8 = n.parse().ok()?;
            (1..=130).contains(&kmh).then_some(kmh)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn countries_by_code() {
        for c in Country::ALL {
            assert_eq!(Country::from_code(c.code()), Some(c));
        }
        assert_eq!(Country::from_code("dk"), Some(Country::Denmark));
        assert_eq!(Country::from_code("DE"), None);
        assert_eq!(Country::from_code(""), None);
    }

    #[test]
    fn built_up_is_50_everywhere_and_rural_differs() {
        for c in Country::ALL {
            assert_eq!(c.limits().urban, 50, "{c:?}");
        }
        assert_eq!(Country::Sweden.limits().rural, 70);
        assert_eq!(Country::Denmark.limits().rural, 80);
        assert_eq!(Country::Norway.limits().rural, 80);
        assert_eq!(Country::Finland.limits().rural, 80);
        assert_eq!(Country::Denmark.limits().motorway, 130);
        // No limit is below walking pace or above the fastest motorway.
        for c in Country::ALL {
            let l = c.limits();
            for v in [l.urban, l.rural, l.motorroad, l.motorway, l.living_street] {
                assert!((WALK..=130).contains(&v), "{c:?} {v}");
            }
        }
    }

    #[test]
    fn reads_implicit_codes() {
        assert_eq!(code_limit("SE:rural"), Some(70));
        assert_eq!(code_limit("SE:urban"), Some(50));
        assert_eq!(code_limit("SE:motorway"), Some(110));
        assert_eq!(code_limit("DK:rural"), Some(80));
        assert_eq!(code_limit("DK:motorway"), Some(130));
        assert_eq!(code_limit("DK:motorroad"), Some(80));
        assert_eq!(code_limit("NO:urban"), Some(50));
        assert_eq!(code_limit("NO:rural"), Some(80));
        assert_eq!(code_limit("FI:rural"), Some(80));
        assert_eq!(code_limit("FI:living_street"), Some(20));
        assert_eq!(code_limit("SE:walk"), Some(WALK));
        assert_eq!(code_limit("DK:zone40"), Some(40));
        assert_eq!(code_limit("FI:zone:30"), Some(30));
        assert_eq!(code_limit(" NO:rural "), Some(80));
    }

    #[test]
    fn ignores_unknown_and_malformed_codes() {
        for v in [
            "DE:rural",
            "SE:",
            ":rural",
            "SE:highway",
            "SE:zone",
            "SE:zone0",
            "SE:zone300",
            "SE:zone-5",
            "SE:zone:abc",
            "rural",
            "70",
            "",
            "SE:rural:extra",
        ] {
            assert_eq!(code_limit(v), None, "{v:?}");
        }
    }
}
