// SPDX-License-Identifier: GPL-3.0-only
// Development-only cross-check: Rust symbol discovery vs the Python reference.
// Invoked with `--verify-symbols <kernel-or-boot>...`; not part of the patcher UI.
// Verifies that required symbols resolve on an image.
use crate::ksyms;

fn load_kernel(path: &str) -> Result<Vec<u8>, String> {
    let raw = std::fs::read(path).map_err(|e| format!("read {path}: {e}"))?;
    if raw.len() >= 4096 && &raw[..8] == b"ANDROID!" {
        let size = u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]) as usize;
        return raw
            .get(4096..4096 + size)
            .map(|s| s.to_vec())
            .ok_or_else(|| format!("{path}: truncated kernel section"));
    }
    Ok(raw)
}

pub fn run(paths: &[String]) -> i32 {
    let mut failures = 0;
    for path in paths {
        let kernel = match load_kernel(path) {
            Ok(k) => k,
            Err(e) => {
                println!("{e}");
                failures += 1;
                continue;
            }
        };
        match ksyms::discover(&kernel) {
            Ok(sym) => {
                let md5 = {
                    
                    let mut h: u64 = 0xcbf29ce484222325;
                    for name in &sym.names {
                        for b in name.bytes() {
                            h ^= b as u64;
                            h = h.wrapping_mul(0x100000001b3);
                        }
                    }
                    h
                };
                println!(
                    "OK {} family={:?} count={} names_fnv={:016x} first={} last={}",
                    path,
                    sym.family,
                    sym.count,
                    md5,
                    sym.names.first().map(String::as_str).unwrap_or(""),
                    sym.names.last().map(String::as_str).unwrap_or("")
                );
            }
            Err(e) => {
                println!("FAIL {} {e}", path);
                failures += 1;
            }
        }
    }
    if failures == 0 { 0 } else { 1 }
}
