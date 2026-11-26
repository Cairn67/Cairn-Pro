// SPDX-License-Identifier: GPL-3.0-only
//! Kernel payload compression, so a boot image that ships a compressed Image can
//! be patched in place.

// Kernel image compression and decompression.
use std::os::raw::{c_int, c_uchar};

extern "C" {
    fn cairn_lz4_legacy_compress(
        src: *const c_uchar,
        src_len: usize,
        dst: *mut c_uchar,
        dst_cap: usize,
        level: c_int,
    ) -> i64;
    fn cairn_lz4_legacy_compress_bound(src_len: usize) -> usize;
    fn cairn_lz4_legacy_decompress(
        src: *const c_uchar,
        src_len: usize,
        dst: *mut c_uchar,
        dst_cap: usize,
        produced: *mut usize,
    ) -> c_int;
}

const LZ4_LEGACY_MAGIC: u32 = 0x184c_2102;

const LZ4_FRAME_MAGIC: [u8; 4] = [0x04, 0x22, 0x4d, 0x18];

const GZIP_MAGIC: [u8; 3] = [0x1f, 0x8b, 0x08];

const ZSTD_MAGIC: [u8; 4] = [0x28, 0xb5, 0x2f, 0xfd];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compression {
    
    None,
    
    Lz4Legacy,
}

impl Compression {
    pub fn name(self) -> &'static str {
        match self {
            Compression::None => "none",
            Compression::Lz4Legacy => "lz4",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Kernel {
    
    pub image: Vec<u8>,
    pub compression: Compression,
}

pub fn is_arm64_image(bytes: &[u8]) -> bool {
    bytes.len() > 0x3c && bytes[0x38..0x3c] == *b"ARM\x64"
}

pub fn detect(bytes: &[u8]) -> Result<Compression, String> {
    if is_arm64_image(bytes) {
        return Ok(Compression::None);
    }
    if bytes.len() >= 4 && u32::from_le_bytes(bytes[..4].try_into().unwrap()) == LZ4_LEGACY_MAGIC {
        return Ok(Compression::Lz4Legacy);
    }
    if bytes.len() >= 4 && bytes[..4] == LZ4_FRAME_MAGIC {
        return Err("LZ4 frame format is not supported (only the legacy stream is)".into());
    }
    if bytes.len() >= 3 && bytes[..3] == GZIP_MAGIC {
        return Err("gzip kernels are not supported".into());
    }
    if bytes.len() >= 4 && bytes[..4] == ZSTD_MAGIC {
        return Err("zstd kernels are not supported".into());
    }
    Err("not a recognised kernel wrapper and not an ARM64 Image".into())
}

pub fn open_boot(boot: &[u8]) -> Result<Kernel, String> {
    if boot.len() < 4096 || &boot[..8] != b"ANDROID!" {
        return Err("invalid or truncated Android boot image".into());
    }
    let version = u32::from_le_bytes(boot[40..44].try_into().unwrap());
    if !matches!(version, 3 | 4) {
        return Err("only boot header v3/v4 is supported".into());
    }
    let size = u32::from_le_bytes(boot[8..12].try_into().unwrap()) as usize;
    let end = 4096usize.checked_add(size).ok_or("kernel size overflow")?;
    let body = boot.get(4096..end).ok_or("truncated kernel section")?;
    open(body)
}

pub fn open(bytes: &[u8]) -> Result<Kernel, String> {
    let compression = detect(bytes)?;
    
    const MAX_IMAGE_BYTES: usize = 256 << 20;

    let image = match compression {
        Compression::None => bytes.to_vec(),
        Compression::Lz4Legacy => {
            
            let cap = MAX_IMAGE_BYTES.max(bytes.len());
            let mut out = vec![0u8; cap];
            let mut produced = 0usize;
            let rc = unsafe {
                cairn_lz4_legacy_decompress(
                    bytes.as_ptr(),
                    bytes.len(),
                    out.as_mut_ptr(),
                    out.len(),
                    &mut produced,
                )
            };
            if rc != 0 {
                return Err(format!("LZ4_LEGACY_DECOMPRESS_FAILED code={rc}"));
            }
            out.truncate(produced);
            out
        }
    };
    if !is_arm64_image(&image) {
        return Err(format!(
            "{} payload did not decompress to an ARM64 Image",
            compression.name()
        ));
    }
    Ok(Kernel { image, compression })
}

pub fn close(image: &[u8], compression: Compression) -> Result<Vec<u8>, String> {
    match compression {
        Compression::None => Ok(image.to_vec()),
        Compression::Lz4Legacy => {
            let bound = unsafe { cairn_lz4_legacy_compress_bound(image.len()) };
            let mut out = vec![0u8; bound];
            let written = unsafe {
                cairn_lz4_legacy_compress(
                    image.as_ptr(),
                    image.len(),
                    out.as_mut_ptr(),
                    out.len(),
                    3,
                )
            };
            if written < 0 {
                return Err(format!("LZ4_LEGACY_COMPRESS_FAILED code={written}"));
            }
            out.truncate(written as usize);
            Ok(out)
        }
    }
}

#[cfg(test)]
fn arm64_stub(size: usize) -> Vec<u8> {
    let mut v = vec![0u8; size];
    v[0x38..0x3c].copy_from_slice(b"ARM\x64");
    for (i, b) in v.iter_mut().enumerate().skip(0x40) {
        *b = ((i / 7) % 251) as u8;
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_roundtrip_is_exact() {
        for size in [0x1000usize, 0x10000, 0x300000] {
            let image = arm64_stub(size);
            let packed = close(&image, Compression::Lz4Legacy).expect("compress");
            let back = open(&packed).expect("decompress").image;
            assert_eq!(back, image, "size {size}");
        }
    }

    #[test]
    fn detects_wrappers_without_guessing() {
        let image = arm64_stub(0x100);
        assert_eq!(open(&image).unwrap().compression, Compression::None);
        let packed = close(&image, Compression::Lz4Legacy).unwrap();
        assert_eq!(open(&packed).unwrap().compression, Compression::Lz4Legacy);
        assert_eq!(open(&packed).unwrap().image, image);
        
        assert!(open(&[0x1f, 0x8b, 0x08, 0x00, 0x00]).unwrap_err().contains("gzip"));
        assert!(open(&LZ4_FRAME_MAGIC).unwrap_err().contains("frame"));
    }

    #[test]
    fn rejects_truncated_streams() {
        let image = arm64_stub(0x4000);
        let packed = close(&image, Compression::Lz4Legacy).unwrap();
        
        assert!(open(&packed[..packed.len() / 2]).is_err());
        
        assert!(open(&LZ4_LEGACY_MAGIC.to_le_bytes()).is_err());
    }

    #[test]
    fn reference_lz4_accepts_what_we_emit() {
        let image = arm64_stub(0x40000);
        let packed = close(&image, Compression::Lz4Legacy).unwrap();

        let dir = std::env::temp_dir().join("cairn-lz4-cross");
        std::fs::create_dir_all(&dir).unwrap();
        let src = dir.join("packed.lz4");
        let dst = dir.join("packed.out");
        let _ = std::fs::remove_file(&dst);
        std::fs::write(&src, &packed).unwrap();

        let out = std::process::Command::new("lz4")
            .arg("-d")
            .arg("-f")
            .arg(&src)
            .arg(&dst)
            .output();
        let Ok(out) = out else {
            println!("LZ4_CROSS_SKIPPED no host lz4");
            return;
        };
        assert!(
            out.status.success(),
            "reference lz4 rejected our stream: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        assert_eq!(std::fs::read(&dst).unwrap(), image, "reference decoder differs");
        println!("LZ4_CROSS packed={} original={}", packed.len(), image.len());
    }

    #[test]
    fn real_device_stream_opens() {
        let path = "/storage/emulated/0/boot/一加ace5Ultra 15.0.2.211 boot.img";
        let Ok(boot) = std::fs::read(path) else {
            println!("ULTRA_SKIPPED no device image");
            return;
        };
        let size = u32::from_le_bytes(boot[8..12].try_into().unwrap()) as usize;
        let body = &boot[4096..4096 + size];
        let kernel = open(body).expect("open");
        println!(
            "ULTRA_KERNEL compression={} packed={} image={}",
            kernel.compression.name(),
            size,
            kernel.image.len()
        );
        assert!(is_arm64_image(&kernel.image));

        let again = close(&kernel.image, kernel.compression).unwrap();
        assert_eq!(open(&again).expect("reopen").image, kernel.image);

        let dir = std::env::temp_dir().join("cairn-lz4-cross-ultra");
        std::fs::create_dir_all(&dir).unwrap();
        let src = dir.join("k.lz4");
        let dst = dir.join("k.out");
        let _ = std::fs::remove_file(&dst);
        std::fs::write(&src, &again).unwrap();
        if let Ok(out) = std::process::Command::new("lz4")
            .arg("-d")
            .arg("-f")
            .arg(&src)
            .arg(&dst)
            .output()
        {
            assert!(
                out.status.success(),
                "reference lz4 rejected the repacked device kernel: {}",
                String::from_utf8_lossy(&out.stderr)
            );
            assert_eq!(std::fs::read(&dst).unwrap(), kernel.image);
            println!("ULTRA_REPACK reference decoder ok, {} bytes", again.len());
        }
    }
}
