// SPDX-License-Identifier: GPL-3.0-only
//! Compile the vendored LZ4 reference implementation and its wrapper.
//!

// Cargo build script: assembles payloads and embeds generated tables.
use std::path::PathBuf;
use std::process::Command;

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let vendored = manifest.join("vendor/lz4");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());

    let cc = std::env::var("CC").unwrap_or_else(|_| "cc".into());
    let mut objects = Vec::new();
    for name in ["lz4", "cairn_lz4"] {
        let src = vendored.join(format!("{name}.c"));
        let obj = out.join(format!("{name}.o"));
        
        let status = Command::new(&cc)
            .args(["-O2", "-std=c99", "-DXXH_NAMESPACE=CAIRN_", "-c"])
            .arg(&src)
            .arg("-I")
            .arg(&vendored)
            .arg("-o")
            .arg(&obj)
            .status()
            .unwrap_or_else(|e| panic!("failed to run {cc} for {}: {e}", src.display()));
        assert!(status.success(), "compiling {} failed", src.display());
        println!("cargo:rerun-if-changed={}", src.display());
        objects.push(obj);
    }

    let lib = out.join("libcairn_lz4.a");
    let _ = std::fs::remove_file(&lib);
    let status = Command::new("ar")
        .arg("crs")
        .arg(&lib)
        .args(&objects)
        .status()
        .unwrap_or_else(|e| panic!("failed to run ar: {e}"));
    assert!(status.success(), "archiving libcairn_lz4.a failed");

    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=cairn_lz4");
    println!("cargo:rerun-if-changed=vendor/lz4/cairn_lz4.c");
    println!("cargo:rerun-if-changed=vendor/lz4/lz4.h");
}
