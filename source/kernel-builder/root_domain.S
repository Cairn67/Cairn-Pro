// SPDX-License-Identifier: GPL-2.0-only
// Root credential domain hooks.
#include "layout.h"

.section .text
.balign 4
.global cr_shell_prepare, cr_context_call, cr_blob_delta
cr_shell_prepare:
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 mov x29,sp
 mov x19,x0
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cmp x19,x9
 b.eq cr_invalid
 ldr x20,[x19,#128]
 cbz x20,cr_invalid
 ldr x10,[x9,#CA_CRED_SECURITY]
 cmp x20,x10
 b.eq cr_invalid
 adr x0,cr_shell_context
 mov w1,#12
 add x2,sp,#32
 str wzr,[sp,#32]
cr_context_call:
 bl 0
 cbnz w0,cr_return
 ldr w10,[sp,#32]
 cbz w10,cr_invalid
 adr x9,cr_blob_delta
 ldr x11,[x9]
 add x9,x9,x11
 ldrsw x9,[x9]
 cmp x9,#0
 b.lt cr_invalid
 cmp x9,#4096
 b.hi cr_invalid
 tst x9,#3
 b.ne cr_invalid
 add x20,x20,x9
 str w10,[x20,#4]
 stp wzr,wzr,[x20,#8]
 stp wzr,wzr,[x20,#16]
 mov w0,wzr
 b cr_return
cr_invalid:
 mov x0,#-22
cr_return:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 ret
.balign 8
cr_blob_delta:
 .quad 0
cr_shell_context:
 .asciz "u:r:shell:s0"

.balign 4
.global cr_raise_private,cr_prepare_call,cr_commit_call,cr_abort_call
cr_raise_private:
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 mov x29,sp
cr_prepare_call:
 bl 0
 cbz x0,cr_raise_nomem
 mov x19,x0
 bl cr_shell_prepare
 cbnz w0,cr_raise_abort
 stp wzr,wzr,[x19,#8]
 stp wzr,wzr,[x19,#16]
 stp wzr,wzr,[x19,#24]
 stp wzr,wzr,[x19,#32]
 movz x8,#0xffff
 movk x8,#0xffff,lsl #16
 movk x8,#0x1ff,lsl #32
 stp x8,x8,[x19,#48]
 stp x8,x8,[x19,#64]
 mov x0,x19
cr_commit_call:
 bl 0
 b cr_raise_out
cr_raise_abort:
 sxtw x20,w0
 mov x0,x19
cr_abort_call:
 bl 0
 mov x0,x20
 b cr_raise_out
cr_raise_nomem:
 mov x0,#-12
cr_raise_out:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 ret
