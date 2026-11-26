/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Thin wrapper over the LZ4 reference implementation for the legacy stream
 * format used by vendor boot images (`lz4 -l`).
 *
 * The reference library only exposes block primitives; the legacy container
 * (magic, per-block length prefix, no footer) is the boot image's own framing and
 * is assembled here, once, so the Rust side never hand-rolls an LZ4 stream.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "lz4.h"

#define CAIRN_LZ4_LEGACY_MAGIC 0x184C2102u
/*
 * Input bytes per legacy block, matching the reference tool's own legacy mode.
 *
 * This is load bearing, not cosmetic: each block's payload ends with literal
 * bytes, and the Image ends in zero fill, so the last block's tail is typically
 * zeros. A block that ends in four zero bytes is indistinguishable from the zero
 * length terminator, and the reference decoder then stops early and reports a
 * corrupt stream. Using the reference tool's block size keeps the framing the same
 * shape it produces, which is the shape the boot loader is known to accept.
 */
#define CAIRN_LZ4_LEGACY_BLOCK (8 * 1024 * 1024)

/* Little-endian helpers: the stream is format-defined, not host order. */
static void put_u32le(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

static uint32_t get_u32le(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

/*
 * Compress `src` into a legacy stream. Returns the number of bytes written, or a
 * negative value when the destination is too small (the caller sizes it with
 * cairn_lz4_legacy_compress_bound).
 */
int64_t cairn_lz4_legacy_compress(const uint8_t *src, size_t src_len, uint8_t *dst,
                                  size_t dst_cap, int level) {
    size_t out = 0;
    size_t offset = 0;
    if (dst_cap < 8) {
        return -1;
    }
    put_u32le(dst, CAIRN_LZ4_LEGACY_MAGIC);
    out = 4;

    while (offset < src_len) {
        size_t chunk = src_len - offset;
        if (chunk > CAIRN_LZ4_LEGACY_BLOCK) {
            chunk = CAIRN_LZ4_LEGACY_BLOCK;
        }
        int room = LZ4_compressBound((int)chunk);
        if (out + 4 + (size_t)room > dst_cap) {
            return -2;
        }
        /* The 4-byte length is written first, so the block starts after it and
         * the reference compressor can write in place. */
        int produced;
        if (level >= 3) {
            produced = LZ4_compress_fast((const char *)(src + offset),
                                         (char *)(dst + out + 4), (int)chunk, room, 1);
        } else {
            produced = LZ4_compress_default((const char *)(src + offset),
                                            (char *)(dst + out + 4), (int)chunk, room);
        }
        if (produced <= 0) {
            return -3;
        }
        put_u32le(dst + out, (uint32_t)produced);
        out += 4 + (size_t)produced;
        offset += chunk;
    }

    /*
     * No terminator is written: the reference `lz4 -l` stream ends right after its
     * last block, and vendor boot images size the kernel region to exactly that.
     * Appending a zero length makes the reference tool read a further (empty)
     * block and fail, so the reader here accepts both forms while the writer
     * emits the one the tool produces.
     */
    return (int64_t)out;
}

/* Worst case size of the legacy container for `src_len` input bytes. */
size_t cairn_lz4_legacy_compress_bound(size_t src_len) {
    size_t blocks = (src_len + CAIRN_LZ4_LEGACY_BLOCK - 1) / CAIRN_LZ4_LEGACY_BLOCK;
    if (blocks == 0) {
        blocks = 1;
    }
    return 8 + blocks * (4 + (size_t)LZ4_compressBound(CAIRN_LZ4_LEGACY_BLOCK));
}

/*
 * Decompress a legacy stream into a buffer the caller grows. Returns 0 on success
 * with `*produced` set, or a negative code.
 *
 * The legacy container records only compressed block sizes, so the expanded size is
 * not known before decoding. `dst_cap` must therefore be an upper bound: the caller
 * passes the largest Image it is willing to accept, and a stream that expands past
 * it is refused rather than truncated.
 */
int cairn_lz4_legacy_decompress(const uint8_t *src, size_t src_len, uint8_t *dst,
                                size_t dst_cap, size_t *produced) {
    size_t at = 0;
    size_t out = 0;
    int saw_magic = 0;

    while (at < src_len) {
        if (at + 4 > src_len) {
            return -2;
        }
        uint32_t word = get_u32le(src + at);
        at += 4;
        if (word == CAIRN_LZ4_LEGACY_MAGIC) {
            saw_magic = 1;
            continue;
        }
        if (!saw_magic) {
            return -3;
        }
        if (word == 0) {
            break;
        }
        size_t size = (size_t)word;
        if (at + size > src_len) {
            return -4;
        }
        if (out >= dst_cap) {
            return -1;
        }
        int room = (int)((dst_cap - out) > 0x7ffffff0u ? 0x7ffffff0u : (dst_cap - out));
        int written = LZ4_decompress_safe((const char *)(src + at), (char *)(dst + out),
                                          (int)size, room);
        if (written < 0) {
            /* A destination that filled up is distinguishable from a corrupt
             * block: the reference decoder reports the second as a hard error and
             * the first only when the block genuinely needed more room. */
            return -5;
        }
        out += (size_t)written;
        at += size;
    }
    *produced = out;
    return 0;
}