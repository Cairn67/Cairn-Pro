/* SPDX-License-Identifier: GPL-2.0-only */
// Runtime state for access evaluation.
#ifndef CAIRN_ACCESS_RUNTIME_H
#define CAIRN_ACCESS_RUNTIME_H
#include "access_v2.h"
struct ca_runtime {
 unsigned long (*lock)(void *);
 void (*unlock)(void *,unsigned long);
 void *(*allocate)(unsigned long);
 void (*release)(void *);
 unsigned long (*copy_user)(void *,const void *,unsigned long);
 void *lock_state;
 struct car_table *active;
 char *(*resolve)(const void *,char *,int);
 struct cav_active *scoped;
};
long ca_replace(struct ca_runtime *,unsigned int,unsigned int,int,const void *);
int ca_check(struct ca_runtime *,unsigned int,unsigned int,unsigned int,const char *,unsigned int,unsigned int);
int ca_targeted(struct ca_runtime *,unsigned int);
long ca_replace_v2(struct ca_runtime *,unsigned int,unsigned int,int,const void *);
int cav_avc_allowed(unsigned int,unsigned int,unsigned int,unsigned int,const void *,const void *);
int cav_root_subject(const void *);
void cav_fill_decision(void *,unsigned int);
unsigned int cav_policy_sequence(void);
int cav_resolve_context(const char *,unsigned int,unsigned int *);
#if defined(__aarch64__)
_Static_assert(sizeof(struct ca_runtime)==72,"access runtime ARM64 ABI");
_Static_assert(__builtin_offsetof(struct ca_runtime,active)==48,"access active ABI");
_Static_assert(__builtin_offsetof(struct ca_runtime,resolve)==56,"access resolve ABI");
#endif
#endif
