// SPDX-License-Identifier: GPL-2.0-only
// Replaces the kernel_init_freeable call to wait_for_initramfs.
// Run after unpack completes and before userspace init/policy loading.
// Early bootstrap entry.
.section .text
.balign 4
.global cl_boot_entry
cl_boot_entry:
 paciasp
 stp x29,x30,[sp,#-32]!
 mov x29,sp
 bl cl_wait_initramfs
 str x0,[sp,#16]
 adr x0,cl_state_delta
 ldr x9,[x0]
 add x0,x0,x9
 adr x1,cl_anchor
 adr x9,cl_kernel_delta
 ldr x9,[x9]
 sub x1,x1,x9
 bl cl_boot_load
 ldr x0,[sp,#16]
 ldp x29,x30,[sp],#32
 autiasp
 ret
