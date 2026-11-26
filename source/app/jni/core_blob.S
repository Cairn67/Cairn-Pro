// SPDX-License-Identifier: GPL-2.0-only
// Matched zero-key core is linked into the JNI library, not executed in userspace.
// The build supplies the kernel-builder directory through the assembler -I flag.
// Embeds the zero-key core template into the JNI library.
.section .rodata.cairn_core,"a",%progbits
.balign 8
.global cairn_core_blob,cairn_core_blob_end
.hidden cairn_core_blob,cairn_core_blob_end
.type cairn_core_blob,%object
cairn_core_blob:
.incbin "detached_core.cld"
cairn_core_blob_end:
.size cairn_core_blob,.-cairn_core_blob
.section .note.GNU-stack,"",%progbits
