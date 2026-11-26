/* SPDX-License-Identifier: GPL-2.0-only */
// Runtime state for path visibility.
#ifndef CAIRN_PATH_RUNTIME_H
#define CAIRN_PATH_RUNTIME_H
struct cpr_table;
struct cp_runtime {
 unsigned long (*lock)(void *);
 void (*unlock)(void *,unsigned long);
 void *(*allocate)(unsigned long);
 void (*release)(void *);
 unsigned long (*copy_user)(void *,const void *,unsigned long);
 char *(*resolve)(const void *,char *,int);
 void *lock_state;
 struct cpr_table *active;
#ifdef CP_KERNEL_TRACE
 unsigned int trace_remaining;
 unsigned int generation;
#endif
};
#ifdef CP_KERNEL_TRACE
_Static_assert(sizeof(struct cp_runtime)==72,"ARM64 diagnostic runtime ABI");
#else
_Static_assert(sizeof(struct cp_runtime)==64,"ARM64 runtime ABI");
#endif
#endif
