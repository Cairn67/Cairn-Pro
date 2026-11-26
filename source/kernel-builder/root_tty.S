// SPDX-License-Identifier: GPL-2.0-only
// TTY-related root hooks.
#include "layout.h"

.section .text
.balign 4
.global cr_tty_entry,cr_tty_original
cr_tty_entry:
 
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cr_tty_fallback
 
#if CA_CRED_UID % 8 == 0 && CA_CRED_EUID % 8 == 0
 ldp x10,x11,[x9,#CA_CRED_UID]
 orr x10,x10,x11
 ldp x11,x12,[x9,#CA_CRED_EUID]
 orr x10,x10,x11
 orr x10,x10,x12
#else
 ldp w10,w11,[x9,#CA_CRED_UID]
 ldp w12,w13,[x9,#CA_CRED_EUID]
 orr w10,w10,w11
 orr w10,w10,w12
 orr w10,w10,w13
#endif
 cbnz x10,cr_tty_fallback
 
 ldr x10,[x9,#CA_CRED_CAP_EFF]
 mov x11,#0xffff
 movk x11,#0xffff,lsl #16
 movk x11,#0x1ff,lsl #32
 cmp x10,x11
 b.ne cr_tty_fallback
 
 mov w9,#0x5400
 sub w9,w1,w9
 cmp w9,#1
 b.lo cr_tty_fallback
 cmp w9,#4
 b.ls cr_tty_candidate
 cmp w9,#0xf
 b.eq cr_tty_candidate
 cmp w9,#0x10
 b.eq cr_tty_candidate
 cmp w9,#0x13
 b.eq cr_tty_candidate
 cmp w9,#0x14
 b.eq cr_tty_candidate
 cmp w9,#0x29
 b.ne cr_tty_fallback
cr_tty_candidate:
 cbz x0,cr_tty_fallback
 ldr x9,[x0,#CA_FILE_F_INODE]
 cbz x9,cr_tty_fallback
 ldrh w9,[x9]
 and w9,w9,#0xf000
 cmp w9,#0x2000
 b.ne cr_tty_fallback
 stp x29,x30,[sp,#-160]!
 stp x0,x1,[sp,#16]
 add x0,x0,#CA_FILE_F_PATH
 add x1,sp,#32
 mov x2,#128
 bl cp_resolve_target
 add x9,sp,#32
 sub x10,x0,x9
 cmp x10,#118
 b.hi cr_tty_reject
 ldr x10,[x0]
 mov x11,#0x642f
 movk x11,#0x7665,lsl #16
 movk x11,#0x702f,lsl #32
 movk x11,#0x7374,lsl #48
 cmp x10,x11
 b.ne cr_tty_reject
 ldrb w10,[x0,#8]
 cmp w10,#47
 b.ne cr_tty_reject
 add x0,x0,#9
 ldrb w10,[x0]
 cbz w10,cr_tty_reject
cr_tty_digit:
 sub w10,w10,#48
 cmp w10,#9
 b.hi cr_tty_reject
 add x0,x0,#1
 add x9,sp,#160
 cmp x0,x9
 b.hs cr_tty_reject
 ldrb w10,[x0]
 cbnz w10,cr_tty_digit
 ldp x29,x30,[sp],#160
 mov w0,wzr
 ret
cr_tty_reject:
 ldp x0,x1,[sp,#16]
 ldp x29,x30,[sp],#160
cr_tty_fallback:
 paciasp
cr_tty_original:
 b 0
