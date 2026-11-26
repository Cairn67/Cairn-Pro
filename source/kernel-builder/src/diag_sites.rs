// SPDX-License-Identifier: GPL-3.0-only
// Development-only dump: what does the Rust site resolver see for one kernel?
// Diagnostic dump of resolved hook sites.
use crate::ksyms::Symbols;
use crate::sites;

fn load_symbols(path: &str) -> Result<Symbols, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("read {path}: {e}"))?;
    let mut rows = Vec::new();
    for line in text.lines() {
        let mut it = line.splitn(3, ' ');
        let a = it.next().ok_or("short line")?;
        let kind = it.next().ok_or("short line")?;
        let n = it.next().ok_or("short line")?;
        rows.push((u32::from_str_radix(a, 16).map_err(|e| e.to_string())?, format!("{kind}{n}")));
    }
    rows.sort_by_key(|r| r.0);
    Ok(Symbols {
        family: crate::ksyms::Family::PostTokenImageRelativeU32,
        count: rows.len(),
        count_at: 0,
        names: rows.iter().map(|r| r.1.clone()).collect(),
        addresses: rows.iter().map(|r| r.0).collect(),
    })
}

pub fn run(kernel: &str, symbols: &str) -> i32 {
    let data = match std::fs::read(kernel) {
        Ok(d) => d,
        Err(e) => {
            println!("read {kernel}: {e}");
            return 2;
        }
    };
    let sym = match load_symbols(symbols) {
        Ok(s) => s,
        Err(e) => {
            println!("{e}");
            return 2;
        }
    };
    println!("kernel {} bytes, {} symbols", data.len(), sym.count);
    for spec in sites::HOOK_SITES.iter().chain(std::iter::once(&sites::BOOT_SITE)) {
        let hits = sym.addrs_of(spec.func);
        let base = hits.first().copied().unwrap_or(0) as usize;
        let first = if base + 4 <= data.len() {
            u32::from_le_bytes([data[base], data[base + 1], data[base + 2], data[base + 3]])
        } else {
            0
        };
        println!(
            "  {:<24} func={:<26} hits={:?} base=0x{:x} first={:08x}",
            spec.name, spec.func, hits, base, first
        );
    }
    match sites::resolve_all(&data, &sym) {
        Ok(list) => {
            for s in list {
                println!("OK   {:<24} 0x{:08x}  {:08x}", s.name, s.offset, s.word);
            }
            0
        }
        Err((name, e)) => {
            println!("FAIL {name}: {e}");
            1
        }
    }
}
