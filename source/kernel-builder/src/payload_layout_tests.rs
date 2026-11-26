// SPDX-License-Identifier: GPL-3.0-only
// Does the Rust-side layout guard agree with the payload actually shipped?
//

// Tests for the payload layout definitions.
use crate::btf;
use crate::interactive::payload_layout_for_tests;

const SAMPLES: &[&str] = &["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"];

fn sample(name: &str) -> Option<Vec<u8>> {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
    std::fs::read(root.join("../../../../research/three-device-dynamic").join(name).join("kernel.bin")).ok()
}

#[test]
fn every_offset_the_payload_uses_is_guarded() {
    let guarded = payload_layout_for_tests();
    
    assert_eq!(guarded.len(), 16, "payload layout must cover layout.h");
    
    assert!(guarded.iter().any(|(s, f, _)| *s == "path" && *f == "dentry"));
    assert!(guarded.iter().any(|(s, f, _)| *s == "common_audit_data" && *f == "u"));
}

#[test]
fn guard_matches_the_6_6_reference_layout() {
    
    let Some(kernel) = sample("2178360d572d1f63") else { return };
    let layout = btf::read_layout(&kernel).expect("read layout");
    for (s, f, want) in payload_layout_for_tests() {
        let got = layout.get(s, f);
        assert_eq!(got, Some(*want), "{s}.{f}: guard says {want}, kernel says {got:?}");
    }
}

#[test]
fn guard_rejects_the_other_two_kernels() {
    
    let mut differing = 0;
    for name in ["1ce5a4f8b813ed21", "888332748bd958be"] {
        let Some(kernel) = sample(name) else { continue };
        let layout = btf::read_layout(&kernel).expect("read layout");
        let off: Vec<_> = payload_layout_for_tests()
            .iter()
            .filter(|(s, f, want)| layout.get(s, f) != Some(*want))
            .map(|(s, f, _)| format!("{s}.{f}"))
            .collect();
        assert!(!off.is_empty(), "{name} unexpectedly matches the 6.6 payload layout");
        differing += 1;
    }
    assert!(differing > 0, "no sample kernels available to check");
}

#[test]
fn a_retargeted_guard_accepts_its_own_kernel() {
    
    let Some(kernel) = sample("1ce5a4f8b813ed21") else { return };
    let layout = btf::read_layout(&kernel).expect("read layout");
    for (s, f, _) in payload_layout_for_tests() {
        assert!(layout.get(s, f).is_some(), "{s}.{f} must resolve on the 6.1 kernel");
    }
    
    assert_eq!(layout.get("task_struct", "cred"), Some(2104));
    assert_eq!(layout.get("cred", "uid"), Some(4));
    assert_eq!(layout.get("file", "f_inode"), Some(32));
}
