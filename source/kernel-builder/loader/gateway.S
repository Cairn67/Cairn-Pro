// SPDX-License-Identifier: GPL-2.0-only
// This hook replaces sys_prctl's first STP AFTER native PACIASP.
// Gateway trampolines into the payload.
.section .text
.balign 4
.global cl_prctl_entry,cl_auth,cl_copy_user
cl_prctl_entry:
 ldr w9,[x0]
 mov w10,#0x4952
 movk w10,#0x4341,lsl #16
 cmp w9,w10
 b.ne cl_prctl_dispatch
 ldr x9,[x0,#24]
 cmp x9,#8
 b.eq cl_request
 cmp x9,#9
 b.ne cl_prctl_dispatch
cl_request:
 adr x10,cl_auth
 ldp x11,x12,[x10]
 ldp x13,x14,[x0,#8]
 cmp x11,x13
 b.ne cl_denied
 cmp x12,x14
 b.ne cl_denied
 ldr x10,[x0,#32]
 adr x11,cl_state_delta
 ldr x12,[x11]
 add x2,x11,x12
 cmp x9,#9
 b.eq cl_status
 
 stp x29,x30,[sp,#-32]!
 str x0,[sp,#16]
 mov x29,sp
 mov x0,x10
 adr x11,cl_request_bytes
 ldr w1,[x11]
 adr x3,cl_anchor
 adr x11,cl_kernel_delta
 ldr x11,[x11]
 sub x3,x3,x11
 bl cl_load
 ldp x29,x30,[sp],#32
 autiasp
 ret
cl_status:
 add x11,x2,#8
 ldar x0,[x11]
 cbz x0,cl_return
 cmp x10,#1
 b.eq cl_return
 cmp x10,#2
 b.eq cl_status_loads
 ldr x0,[x2,#16]
 b cl_return
cl_status_loads:
 ldr x0,[x2,#24]
cl_return:
 autiasp
 ret
cl_denied:
 mov x0,#-13
 b cl_return
cl_prctl_dispatch:
 b cl_gateway_prctl
cl_copy_user:
 stp x29,x30,[sp,#-16]!
 mov x29,sp
 .inst 0xd500409f
 bl cl_native_copy
 .inst 0xd500419f
 ldp x29,x30,[sp],#16
 ret
.balign 8
cl_auth:
 .quad 0,0
.global cl_anchor,cl_kernel_delta
cl_anchor:
 nop
.balign 8
cl_kernel_delta:
 .quad 0
