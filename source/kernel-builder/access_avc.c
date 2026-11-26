// SPDX-License-Identifier: GPL-2.0-only
// Access decision handling for AVC checks.
#include "access_runtime.h"
#include "layout.h"
extern struct ca_runtime *ca_runtime_peek(void);
extern unsigned int cav_current_sid(void);
static const void *cv_ptr(const void *p,unsigned int n){return *(const void *const *)((const char *)p+n);}
static unsigned int cv_word(const void *p,unsigned int n){return *(const unsigned int *)((const char *)p+n);}
static const void *cv_cred(const void *task){return task?cv_ptr(task,CA_TASK_CRED):(void *)0;}
int cav_root_subject(const void *task){const void *c=cv_cred(task);return c&&!cv_word(c,CA_CRED_UID)&&!cv_word(c,CA_CRED_EUID)&&!cv_word(c,CA_CRED_FSUID);}
long ca_replace_v2(struct ca_runtime *r,unsigned int uid,unsigned int euid,int auth,const void *user){
 if(uid||euid||!auth)return -1;
 if(!r||!user)return -22;
 struct cav_active *next=r->allocate(sizeof(*next));if(!next)return -12;
 long error=-14;
 if(r->copy_user(&next->request,user,sizeof(next->request)))goto fail;
 error=-22;if(cav_validate(&next->request))goto fail;
 next->policy_sequence=cav_policy_sequence();next->reserved=0;
 for(unsigned int i=0;i<next->request.count;i++){
  struct cav_rule *rule=&next->request.rules[i];unsigned int n=0;
  next->sids[i].subject=next->sids[i].object=next->sids[i].source=0;
  while(rule->subject[n])n++;
  if(cav_resolve_context(rule->subject,n,&next->sids[i].subject)||!next->sids[i].subject)goto fail;
  n=0;while(rule->object[n])n++;
  if(cav_resolve_context(rule->object,n,&next->sids[i].object)||!next->sids[i].object)goto fail;
  n=0;while(rule->source[n])n++;
  if(cav_resolve_context(rule->source,n,&next->sids[i].source)||!next->sids[i].source)goto fail;
 }
 error=-11;if(next->policy_sequence!=cav_policy_sequence())goto fail;
 unsigned long flags=r->lock(r->lock_state);
 struct car_table *old=r->active;struct cav_active *scope=r->scoped;
 r->active=&next->request.legacy;r->scoped=next;
 r->unlock(r->lock_state,flags);
 if(scope)r->release(scope);else if(old)r->release(old);
 return 0;
 fail:r->release(next);return error;
}
int cav_avc_allowed(unsigned int ssid,unsigned int tsid,unsigned int tclass,unsigned int requested,
 const void *audit,const void *task){
 if(cav_root_subject(task))return 1;
 const void *cred=cv_cred(task);if(!cred)return 0;
 unsigned int uid=cv_word(cred,CA_CRED_UID),euid=cv_word(cred,CA_CRED_EUID),fsuid=cv_word(cred,CA_CRED_FSUID);
 if(!uid||uid!=euid||uid!=fsuid||!requested||(requested&~cav_valid_mask(tclass)))return 0;
 struct ca_runtime *r=ca_runtime_peek();if(!r)return 0;
 
 unsigned int sid=cav_current_sid(),epoch=cav_policy_sequence();
 unsigned long flags=r->lock(r->lock_state);int candidate=0,path_needed=0;
 if(r->scoped&&r->scoped->policy_sequence==epoch){
  for(unsigned int i=0;i<r->scoped->request.count;i++){
   const struct cav_rule *rule=&r->scoped->request.rules[i];
   const struct cav_resolved *s=&r->scoped->sids[i];
   if(rule->uid==uid&&s->subject==sid&&s->source==ssid&&s->object==tsid&&rule->tclass==tclass){candidate=1;path_needed|=rule->match;}
  }
 }
 r->unlock(r->lock_state,flags);if(!candidate)return 0;
 const char *path=(void *)0;unsigned int length=0;char buffer[CAR_PATH];
 if(path_needed&&audit&&*(const unsigned char *)audit==12&&r->resolve){
  const void *file=cv_ptr(audit,CA_AUDIT_U_FILE);
  if(file){
   char *p=r->resolve((const char *)file+CA_FILE_F_PATH,buffer,sizeof(buffer));
   unsigned long off=(unsigned long)p-(unsigned long)buffer;
   if(off<sizeof(buffer)){
    while(off+length<sizeof(buffer)&&p[length])length++;
    if(off+length<sizeof(buffer))path=p;else length=0;
   }
  }
 }
 epoch=cav_policy_sequence();
 flags=r->lock(r->lock_state);
 int allowed=cav_allowed(r->scoped,epoch,uid,euid,fsuid,sid,ssid,tsid,tclass,requested,path,length);
 r->unlock(r->lock_state,flags);
 return allowed;
}
void cav_fill_decision(void *decision,unsigned int requested){
 if(!decision)return;
 unsigned int *d=decision;
 d[0]=requested;d[1]=0;d[2]=0;d[3]=cav_policy_sequence();d[4]=0;
}
