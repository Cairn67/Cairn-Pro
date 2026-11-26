/* SPDX-License-Identifier: GPL-2.0-only
 * Access policy runtime. Device hooks and prctl dispatch are NOT installed here.
 * Lock, allocation and user-copy callbacks must be supplied by a verified adapter.
 */
// Backend for access rule evaluation.
#include "access_runtime.h"
int ca_targeted(struct ca_runtime *r,unsigned int uid){
 if(!r || !uid)return 0;
 unsigned long flags=r->lock(r->lock_state);int found=0;
 if(r->active)for(unsigned int i=0;i<r->active->count;i++)if(r->active->rules[i].uid==uid){found=1;break;}
 r->unlock(r->lock_state,flags);return found;
}
long ca_replace(struct ca_runtime *r,unsigned int uid,unsigned int euid,int auth,const void *user){
 if(uid || euid || !auth)return -1;
 if(!r || !user)return -22;
 struct car_table *next=r->allocate(sizeof(*next));
 if(!next)return -12;
 if(r->copy_user(next,user,sizeof(*next))){r->release(next);return -14;}
 if(car_validate(next)){r->release(next);return -22;}
 unsigned long flags=r->lock(r->lock_state);
 struct car_table *old=r->active;struct cav_active *scope=r->scoped;r->active=next;r->scoped=(void *)0;
 r->unlock(r->lock_state,flags);
 if(scope)r->release(scope);else if(old)r->release(old);
 return 0;
}
int ca_check(struct ca_runtime *r,unsigned int uid,unsigned int euid,unsigned int fsuid,
 const char *resolved,unsigned int length,unsigned int ops){
 if(!r)return 0;
 unsigned long flags=r->lock(r->lock_state);
 int allowed=car_allowed(r->active,uid,euid,fsuid,resolved,length,ops);
 r->unlock(r->lock_state,flags);
 return allowed;
}
