// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The containers an export can come in: plain GeoJSON, gzip, zip or
//! tar.gz. Everything happens in memory: nothing from an archive is ever
//! written to disk, so entry names can't reach the file system (no path
//! traversal). Sizes are capped before and after decompression, so a
//! small archive can't expand into a memory bomb.

use std::io::{Cursor, Read, Write};

use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;

use crate::CoreError;

/// Largest file accepted for import, as picked (possibly compressed).
pub const MAX_IMPORT_BYTES: usize = 32 * 1024 * 1024;
/// Largest GeoJSON accepted after decompression.
pub const MAX_JSON_BYTES: usize = 64 * 1024 * 1024;
/// Most archive entries looked at.
const MAX_ENTRIES: usize = 1_000;
/// The GeoJSON file inside zip and tar exports.
pub const ENTRY_NAME: &str = "moto-sections.geojson";

/// How an export is packed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportFormat {
    /// Plain `.geojson`.
    GeoJson,
    /// `.geojson.gz`.
    Gzip,
    /// `.zip` holding one `.geojson`.
    Zip,
    /// `.tar.gz` holding one `.geojson`.
    TarGz,
}

impl ExportFormat {
    /// The usual file name extension.
    pub fn extension(self) -> &'static str {
        match self {
            Self::GeoJson => "geojson",
            Self::Gzip => "geojson.gz",
            Self::Zip => "zip",
            Self::TarGz => "tar.gz",
        }
    }
}

fn bad(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(msg.into())
}

fn io(e: std::io::Error) -> CoreError {
    CoreError::InvalidArgument(format!("unreadable file: {e}"))
}

/// Packs GeoJSON text in `format`.
pub fn pack(json: &[u8], format: ExportFormat) -> Result<Vec<u8>, CoreError> {
    let pack_err =
        |e: std::io::Error| CoreError::Storage(format!("could not pack the export: {e}"));
    match format {
        ExportFormat::GeoJson => Ok(json.to_vec()),
        ExportFormat::Gzip => gzip(json).map_err(pack_err),
        ExportFormat::Zip => {
            let mut out = zip::ZipWriter::new(Cursor::new(Vec::new()));
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            out.start_file(ENTRY_NAME, options)
                .map_err(|e| CoreError::Storage(format!("could not pack the export: {e}")))?;
            out.write_all(json).map_err(pack_err)?;
            let cursor = out
                .finish()
                .map_err(|e| CoreError::Storage(format!("could not pack the export: {e}")))?;
            Ok(cursor.into_inner())
        }
        ExportFormat::TarGz => {
            let mut builder = tar::Builder::new(Vec::new());
            let mut header = tar::Header::new_ustar();
            header.set_size(json.len() as u64);
            header.set_mode(0o644);
            header.set_entry_type(tar::EntryType::Regular);
            builder
                .append_data(&mut header, ENTRY_NAME, json)
                .map_err(pack_err)?;
            let tar = builder.into_inner().map_err(pack_err)?;
            gzip(&tar).map_err(pack_err)
        }
    }
}

fn gzip(data: &[u8]) -> std::io::Result<Vec<u8>> {
    let mut enc = GzEncoder::new(Vec::new(), Compression::default());
    enc.write_all(data)?;
    enc.finish()
}

/// The GeoJSON inside an import file in any of the export formats, told
/// apart by content, not by name.
pub fn unpack(bytes: &[u8]) -> Result<Vec<u8>, CoreError> {
    if bytes.len() > MAX_IMPORT_BYTES {
        return Err(bad(format!(
            "the file is larger than {} MiB",
            MAX_IMPORT_BYTES >> 20
        )));
    }
    if bytes.starts_with(b"PK\x03\x04") || bytes.starts_with(b"PK\x05\x06") {
        return from_zip(bytes);
    }
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let inner = read_capped(GzDecoder::new(bytes), MAX_JSON_BYTES)?;
        return if is_tar(&inner) {
            from_tar(&inner)
        } else {
            Ok(inner)
        };
    }
    if is_tar(bytes) {
        return from_tar(bytes);
    }
    if bytes.len() > MAX_JSON_BYTES {
        return Err(bad("the file is too large"));
    }
    Ok(bytes.to_vec())
}

/// Reads at most `limit` bytes; more is an error, not a truncation.
fn read_capped(reader: impl Read, limit: usize) -> Result<Vec<u8>, CoreError> {
    let mut out = Vec::new();
    reader
        .take(limit as u64 + 1)
        .read_to_end(&mut out)
        .map_err(io)?;
    if out.len() > limit {
        return Err(bad(format!(
            "the file unpacks to more than {} MiB",
            limit >> 20
        )));
    }
    Ok(out)
}

fn is_tar(bytes: &[u8]) -> bool {
    bytes.get(257..262) == Some(b"ustar")
}

/// Whether an archive entry name looks like our GeoJSON. Names are only
/// compared, never used as paths.
fn is_json_name(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    n.ends_with(".geojson") || n.ends_with(".json")
}

fn from_zip(bytes: &[u8]) -> Result<Vec<u8>, CoreError> {
    let mut archive = zip::ZipArchive::new(Cursor::new(bytes))
        .map_err(|e| bad(format!("not a readable zip file: {e}")))?;
    for i in 0..archive.len().min(MAX_ENTRIES) {
        let entry = archive
            .by_index(i)
            .map_err(|e| bad(format!("not a readable zip file: {e}")))?;
        if !entry.is_file() || !is_json_name(entry.name()) {
            continue;
        }
        if entry.size() > MAX_JSON_BYTES as u64 {
            return Err(bad("the zip entry is too large"));
        }
        return read_capped(entry, MAX_JSON_BYTES);
    }
    Err(bad("no .geojson or .json file in the zip"))
}

fn from_tar(bytes: &[u8]) -> Result<Vec<u8>, CoreError> {
    let mut archive = tar::Archive::new(Cursor::new(bytes));
    let entries = archive.entries().map_err(io)?;
    for entry in entries.take(MAX_ENTRIES) {
        let entry = entry.map_err(io)?;
        if entry.header().entry_type() != tar::EntryType::Regular {
            continue;
        }
        let name = entry.path_bytes();
        if !is_json_name(&String::from_utf8_lossy(&name)) {
            continue;
        }
        if entry.size() > MAX_JSON_BYTES as u64 {
            return Err(bad("the tar entry is too large"));
        }
        return read_capped(entry, MAX_JSON_BYTES);
    }
    Err(bad("no .geojson or .json file in the archive"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const JSON: &[u8] = br#"{"type":"FeatureCollection","features":[]}"#;

    #[test]
    fn every_format_round_trips() {
        for f in [
            ExportFormat::GeoJson,
            ExportFormat::Gzip,
            ExportFormat::Zip,
            ExportFormat::TarGz,
        ] {
            let packed = pack(JSON, f).unwrap();
            assert_eq!(unpack(&packed).unwrap(), JSON, "{f:?}");
        }
        assert_eq!(ExportFormat::TarGz.extension(), "tar.gz");
    }

    #[test]
    fn a_plain_tar_is_read_too() {
        let mut b = tar::Builder::new(Vec::new());
        let mut h = tar::Header::new_ustar();
        h.set_size(JSON.len() as u64);
        h.set_entry_type(tar::EntryType::Regular);
        b.append_data(&mut h, "x/sections.JSON", JSON).unwrap();
        assert_eq!(unpack(&b.into_inner().unwrap()).unwrap(), JSON);
    }

    #[test]
    fn archives_without_geojson_are_errors() {
        let mut z = zip::ZipWriter::new(Cursor::new(Vec::new()));
        z.start_file("readme.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        z.write_all(b"hello").unwrap();
        let bytes = z.finish().unwrap().into_inner();
        assert!(matches!(unpack(&bytes), Err(CoreError::InvalidArgument(_))));
    }

    #[test]
    fn decompression_bombs_are_refused() {
        // 100 MiB of zeros gzips to about 100 KiB.
        let zeros = vec![0u8; 100 * 1024 * 1024];
        let bomb = gzip(&zeros).unwrap();
        assert!(bomb.len() < MAX_IMPORT_BYTES);
        let err = unpack(&bomb).unwrap_err();
        assert!(
            matches!(err, CoreError::InvalidArgument(ref m) if m.contains("unpacks to more")),
            "{err:?}"
        );
        // Also inside a zip.
        let mut z = zip::ZipWriter::new(Cursor::new(Vec::new()));
        z.start_file(
            "big.geojson",
            zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .large_file(true),
        )
        .unwrap();
        z.write_all(&zeros).unwrap();
        let bytes = z.finish().unwrap().into_inner();
        assert!(unpack(&bytes).is_err());
    }

    #[test]
    fn oversized_files_are_refused_before_reading() {
        let big = vec![b' '; MAX_IMPORT_BYTES + 1];
        assert!(matches!(unpack(&big), Err(CoreError::InvalidArgument(_))));
    }

    #[test]
    fn corrupt_archives_never_panic() {
        let good = [
            pack(JSON, ExportFormat::Gzip).unwrap(),
            pack(JSON, ExportFormat::Zip).unwrap(),
            pack(JSON, ExportFormat::TarGz).unwrap(),
        ];
        let mut x: u64 = 0x2545_f491_4f6c_dd1d;
        for base in &good {
            for _ in 0..300 {
                let mut bytes = base.clone();
                // Flip a few bytes, or cut the file short.
                x ^= x << 13;
                x ^= x >> 7;
                x ^= x << 17;
                if x.is_multiple_of(4) {
                    bytes.truncate((x as usize / 7) % bytes.len());
                } else {
                    for k in 0..(x % 5 + 1) {
                        let i = ((x >> (k * 8)) as usize) % bytes.len();
                        bytes[i] ^= (x >> 3) as u8 | 1;
                    }
                }
                let _ = unpack(&bytes);
            }
        }
        let _ = unpack(b"PK\x03\x04");
        let _ = unpack(&[0x1f, 0x8b]);
        let _ = unpack(b"");
    }
}
