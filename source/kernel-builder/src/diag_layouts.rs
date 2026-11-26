// SPDX-License-Identifier: GPL-3.0-only
// Diagnostic: which layouts does the Rust scanner accept for one kernel?
// Development-only; keeps the shipping path unchanged.
// Diagnostic dump of derived frame layouts.
use crate::ksyms;

pub fn run(path: &str) -> i32 {
    let data = match std::fs::read(path) {
        Ok(d) => d,
        Err(e) => {
            println!("read {path}: {e}");
            return 2;
        }
    };
    match ksyms::discover(&data) {
        Ok(s) => {
            println!(
                "ACCEPTED family={:?} count={} first={} last={}",
                s.family,
                s.count,
                s.names.first().map(String::as_str).unwrap_or(""),
                s.names.last().map(String::as_str).unwrap_or("")
            );
            0
        }
        Err(e) => {
            println!("REJECTED {e}");
            1
        }
    }
}
