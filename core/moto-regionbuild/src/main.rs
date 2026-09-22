// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `moto-regionbuild`: OSM extract → routing region file (ADR-0001, PRD R12).
//!
//! Runs on desktop/CI, never on the phone. Skeleton only: the region file
//! format is still an open question, so this parses arguments and stops.

use std::path::PathBuf;
use std::process::ExitCode;

const USAGE: &str = "usage: moto-regionbuild <input.osm.pbf> <output.region>";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "-h" || a == "--help") {
        println!("{USAGE}");
        return ExitCode::SUCCESS;
    }
    let [input, output] = args.as_slice() else {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    };
    let (input, output) = (PathBuf::from(input), PathBuf::from(output));

    if !input.is_file() {
        eprintln!("error: input not found: {}", input.display());
        return ExitCode::FAILURE;
    }

    eprintln!(
        "error: {}",
        moto_core::CoreError::NotImplemented("region build (OSM parse → graph → region file)")
    );
    eprintln!("  input:  {}", input.display());
    eprintln!("  output: {}", output.display());
    ExitCode::FAILURE
}
