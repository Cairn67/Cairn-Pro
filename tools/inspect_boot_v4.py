#!/usr/bin/env python3
"""Read Android boot v4 metadata without selecting a profile or changing the image."""
import argparse
import hashlib
import json
import os
import struct

PAGE = 4096
HEADER = 1584


def align(n):
    return (n + PAGE - 1) // PAGE * PAGE


def inspect(path):
    with open(path, "rb") as f:
        size = os.fstat(f.fileno()).st_size
        header = f.read(PAGE)
        if len(header) != PAGE or header[:8] != b"ANDROID!":
            raise ValueError("not an Android boot image with a complete header page")
        kernel, ramdisk = struct.unpack_from("<II", header, 8)
        header_size = struct.unpack_from("<I", header, 20)[0]
        version = struct.unpack_from("<I", header, 40)[0]
        if version != 4 or header_size != HEADER:
            raise ValueError(f"expected boot v4 header ({HEADER} bytes), got v{version}, {header_size} bytes")
        signature = struct.unpack_from("<I", header, 1580)[0]
        if kernel < 64 or PAGE + align(kernel) + align(ramdisk) + signature > size:
            raise ValueError("declared boot sections are missing or extend past end of file")
        first = f.read(64)
        if len(first) != 64:
            raise ValueError("truncated kernel header")
        is_arm64 = first[56:60] == b"ARM\x64"
        f.seek(0)
        return {
            "boot_header_version": version,
            "boot_bytes": size,
            "boot_sha256": hashlib.file_digest(f, "sha256").hexdigest(),
            "kernel_bytes": kernel,
            "arm64_image_header": is_arm64,
            "arm64_runtime_image_bytes": struct.unpack_from("<Q", first, 16)[0] if is_arm64 else None,
            "ramdisk_bytes": ramdisk,
            "boot_v4_signature_bytes": signature,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args.image), indent=2))
    except (OSError, ValueError) as exc:
        parser.exit(2, f"{parser.prog}: {exc}\n")


if __name__ == "__main__":
    main()
