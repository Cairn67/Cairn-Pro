// SPDX-License-Identifier: GPL-2.0-only
// Kernel entry and call-site adapters.
// Hook sites for file access checks.
.section .text
.balign 4
.global ca_generic_entry,ca_generic_tail,ca_selinux_inode_entry
.balign 4
.macro ca_mmap_file_load n
 mov x0, x\n
.endm
.global ca_file_avc_entry,ca_file_avc_tail,ca_getattr_entry,ca_getattr_tail
.global ca_runtime_peek
ca_runtime_peek:
 adr x9,ca_slot_delta
 ldr x10,[x9]
 add x9,x9,x10
 ldar x0,[x9]
 ret

ca_generic_entry:
 paciasp
 stp x29,x30,[sp,#-48]!
 stp x0,x1,[sp,#16]
 str x2,[sp,#32]
 mov x29,sp
 mov x0,x1
 mov w1,w2
 mrs x2,sp_el0
 bl ca_inode_allowed
 cbz w0,ca_generic_original
 cmp w0,#1
 csel w0,wzr,w0,eq
 ldp x29,x30,[sp],#48
 autiasp
 ret
ca_generic_original:
 ldr x2,[sp,#32]
 ldp x0,x1,[sp,#16]
 ldp x29,x30,[sp],#48
ca_generic_tail:
 b 0

ca_selinux_inode_entry:
 stp x29,x30,[sp,#-32]!
 stp x0,x1,[sp,#16]
 mov x29,sp
 mrs x2,sp_el0
 bl ca_inode_allowed
 cbz w0,ca_selinux_original
 cmp w0,#1
 csel w0,wzr,w0,eq
 ldp x29,x30,[sp],#32
 autiasp
 ret
ca_selinux_original:
 ldp x0,x1,[sp,#16]
 ldp x29,x30,[sp],#32
 mov x16,#0
 b cairn_chk

ca_file_avc_entry:
 paciasp
 sub sp,sp,#80
 stp x29,x30,[sp]
 stp x0,x1,[sp,#16]
 stp x2,x3,[sp,#32]
 stp x4,x5,[sp,#48]
 stp x6,x7,[sp,#64]
 mov x29,sp
 mov x0,x19
 mrs x1,sp_el0
 bl ca_file_allowed
 cbnz w0,ca_file_granted
 
 ldp x0,x1,[sp,#16]
 ldp x2,x3,[sp,#32]
 ldr x4,[sp,#48]
 mrs x5,sp_el0
 bl cav_avc_allowed
 cbz w0,ca_file_original
ca_file_granted:
 
 ldr w9,[x21,#12]
 mvn w9,w9
 str w9,[x21,#12]
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
 mov w0,wzr
 ret
ca_file_original:
 ldp x0,x1,[sp,#16]
 ldp x2,x3,[sp,#32]
 ldp x4,x5,[sp,#48]
 ldp x6,x7,[sp,#64]
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
ca_file_avc_tail:
 b 0

.global ca_file_data_entry,ca_file_data_tail
ca_file_data_entry:
 paciasp
 sub sp,sp,#80
 stp x29,x30,[sp]
 stp x0,x1,[sp,#16]
 stp x2,x3,[sp,#32]
 stp x4,x5,[sp,#48]
 stp x6,x7,[sp,#64]
 
 ldr x9,[x29,#8]
 .inst 0xdac143e9 
 adr x10,ca_mmap_return_delta
 ldr x11,[x10]
 add x10,x10,x11
 cmp x9,x10
 
 mov w1,w3
 cset w3,eq
 mov x29,sp
 mov x0,x21
 mrs x2,sp_el0
 bl ca_file_data_allowed
 cbz w0,ca_file_data_original
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
 mov w0,wzr
 ret
ca_file_data_original:
 ldp x0,x1,[sp,#16]
 ldp x2,x3,[sp,#32]
 ldp x4,x5,[sp,#48]
 ldp x6,x7,[sp,#64]
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
ca_file_data_tail:
 b 0

ca_getattr_entry:
 paciasp
 stp x29,x30,[sp,#-32]!
 str x0,[sp,#16]
 mov x29,sp
 mov w1,#16
 mrs x2,sp_el0
 bl ca_path_allowed
 cbz w0,ca_getattr_original
 ldp x29,x30,[sp],#32
 autiasp
 mov w0,wzr
 ret
ca_getattr_original:
 ldr x0,[sp,#16]
 ldp x29,x30,[sp],#32
ca_getattr_tail:
 b 0

.balign 4
.global ca_mmap_avc_entry,ca_mmap_avc_tail
ca_mmap_avc_entry:
 paciasp
 sub sp,sp,#80
 stp x29,x30,[sp]
 stp x0,x1,[sp,#16]
 stp x2,x3,[sp,#32]
 stp x4,x5,[sp,#48]
 stp x6,x7,[sp,#64]
 mov x29,sp
#ifdef CAIRN_MMAP_FILE_SRC
 
 ca_mmap_file_load CAIRN_MMAP_FILE_SRC
#else
 mov x0,x20
#endif
 mrs x1,sp_el0
 bl ca_file_map_allowed
 cbz w0,ca_mmap_original
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
 mov w0,wzr
 ret
ca_mmap_original:
 ldp x0,x1,[sp,#16]
 ldp x2,x3,[sp,#32]
 ldp x4,x5,[sp,#48]
 ldp x6,x7,[sp,#64]
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
ca_mmap_avc_tail:
 b 0
.balign 8
.global ca_mmap_return_delta
ca_mmap_return_delta:
 .quad 0
