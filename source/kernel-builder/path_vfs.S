// SPDX-License-Identifier: GPL-2.0-only
// VFS hook for path visibility.
#include "layout.h"

.section .text
.balign 4
.global cp_getattr_entry
cp_getattr_entry:
 paciasp
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 str x0,[sp,#32]
 mov x29,sp
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cp_getattr_continue
 ldr w20,[x9,#CA_CRED_UID]
 mov w10,#10000
 cmp w20,w10
 b.lo cp_getattr_continue
 
 adr x9,cp_slot_delta
 ldr x10,[x9]
 add x9,x9,x10
 ldar x19,[x9]
 cbz x19,cp_getattr_continue
 mov x0,x19
 mov w1,w20
 ldr x2,[sp,#32]
 bl cp_check_allocated
 cbnz x0,cp_getattr_deny
cp_getattr_continue:
 ldr x0,[sp,#32]
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
.global cp_getattr_tail
cp_getattr_tail:
 b 0
cp_getattr_deny:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 autiasp
 ret
