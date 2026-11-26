// SPDX-License-Identifier: GPL-2.0-only
// Open hook for path visibility.
#include "layout.h"

.section .text
.balign 4
.global cp_open_entry
cp_open_entry:
 paciasp
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 str x0,[sp,#32]
 mov x29,sp
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cp_open_continue
 ldr w20,[x9,#CA_CRED_UID]
 mov w10,#10000
 cmp w20,w10
 b.lo cp_open_continue
 adr x9,cp_slot_delta
 ldr x10,[x9]
 add x9,x9,x10
 ldar x19,[x9]
 cbz x19,cp_open_continue
 mov x0,x19
 mov w1,w20
 ldr x2,[sp,#32]
 add x2,x2,#CA_FILE_F_PATH
 bl cp_check_allocated
 cbnz x0,cp_open_deny
cp_open_continue:
 ldr x0,[sp,#32]
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
.global cp_open_tail
cp_open_tail:
 b 0
cp_open_deny:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 autiasp
 ret
