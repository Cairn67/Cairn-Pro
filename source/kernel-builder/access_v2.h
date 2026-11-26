/* SPDX-License-Identifier: GPL-2.0-only */
// Current access rule wire format.
#ifndef CAIRN_ACCESS_V2_H
#define CAIRN_ACCESS_V2_H
#include "access_rules.h"
#include "access_classes.h"
#define CAV_MAGIC 0x43415632U
#define CAV_VERSION 2U
#define CAV_MAX 32U
#define CAV_CONTEXT 128U
#define CAV_OP_REPLACE 11UL
#define CAV_OP_CAPABILITIES 12UL
#define CAV_CAP 0x43410201UL
#define CAV_EXACT 1U
#define CAV_SUBTREE 2U
struct cav_rule {
 unsigned int uid,tclass,permissions,match,reserved;
 char subject[CAV_CONTEXT],object[CAV_CONTEXT],source[CAV_CONTEXT],path[CAR_PATH];
};

struct cav_request {
 unsigned int magic,version,bytes,count,reserved[2];
 struct car_table legacy;
 struct cav_rule rules[CAV_MAX];
};
struct cav_resolved {unsigned int subject,object,source;};
struct cav_active {
 struct cav_request request;
 unsigned int policy_sequence,reserved;
 struct cav_resolved sids[CAV_MAX];
};
static int cav_string(const char *s,unsigned int cap){
 unsigned int n=0;while(n<cap&&s[n]){unsigned char c=s[n];if(c<=32||c>=127||c=='*'||c=='?'||c=='"'||c=='\'')return 0;n++;}
 if(!n||n==cap)return 0;
 for(unsigned int i=n+1;i<cap;i++)if(s[i])return 0;
 return 1;
}
static int cav_context_valid(const char *s){
 if(!cav_string(s,CAV_CONTEXT))return 0;
 unsigned int colons=0;for(unsigned int i=0;s[i];i++)if(s[i]==':')colons++;
 return colons>=3;
}
static int cav_same(const char *a,const char *b,unsigned int cap){for(unsigned int i=0;i<cap;i++){if(a[i]!=b[i])return 0;if(!a[i])return 1;}return 0;}
static int cav_same_scope(const struct cav_rule *a,const struct cav_rule *b){
 return a->uid==b->uid&&a->tclass==b->tclass&&a->match==b->match&&
 cav_same(a->subject,b->subject,CAV_CONTEXT)&&cav_same(a->object,b->object,CAV_CONTEXT)&&cav_same(a->source,b->source,CAV_CONTEXT)&&cav_same(a->path,b->path,CAR_PATH);
}
static int cav_validate_rule(const struct cav_rule *r){
 if(!r->uid||r->reserved||!r->permissions||(r->permissions&~cav_valid_mask(r->tclass))||!cav_context_valid(r->subject)||!cav_context_valid(r->object)||!cav_context_valid(r->source))return -1;
 
 if(!cav_same(r->source,r->subject,CAV_CONTEXT) && (r->tclass!=6 || (r->permissions&~64U)))return -1;
 if(r->match>CAV_SUBTREE)return -1;
 if(!r->match){for(unsigned int i=0;i<CAR_PATH;i++)if(r->path[i])return -1;}
 else {
  if(r->tclass!=7&&r->tclass!=8&&r->tclass!=11&&r->tclass!=13&&r->tclass!=14)return -1;
  unsigned int n=0;while(n<CAR_PATH&&r->path[n])n++;
  if(!car_path_valid(r->path,n))return -1;
  for(unsigned int i=n+1;i<CAR_PATH;i++)if(r->path[i])return -1;
  
  if(r->permissions&~0x00004206U)return -1; 
 }
 return 0;
}
static int cav_validate(const struct cav_request *r){
 if(!r||r->magic!=CAV_MAGIC||r->version!=CAV_VERSION||r->bytes!=sizeof(*r)||r->count>CAV_MAX||r->reserved[0]||r->reserved[1]||car_validate(&r->legacy))return -1;
 for(unsigned int i=0;i<r->count;i++){
  if(cav_validate_rule(&r->rules[i]))return -1;
  for(unsigned int j=0;j<i;j++)if(cav_same_scope(&r->rules[i],&r->rules[j]))return -1;
 }
 return 0;
}
static int cav_path_matches(const struct cav_rule *r,const char *path,unsigned int n){
 if(!r->match)return 1;
 if(!path||!car_path_valid(path,n))return 0;
 unsigned int i=0;while(i<CAR_PATH&&r->path[i]){if(i>=n||r->path[i]!=path[i])return 0;i++;}
 return i<CAR_PATH&&(i==n||(r->match==CAV_SUBTREE&&path[i]=='/'));
}
static int cav_allowed(const struct cav_active *a,unsigned int epoch,unsigned int uid,unsigned int euid,unsigned int fsuid,
 unsigned int current_sid,unsigned int ssid,unsigned int tsid,unsigned int tclass,unsigned int requested,const char *path,unsigned int length){
 if(!a||!uid||uid!=euid||uid!=fsuid||!ssid||!current_sid||!tsid||!requested||a->policy_sequence!=epoch)return 0;
 if(requested&~cav_valid_mask(tclass))return 0;
 unsigned int allowed=0;
 for(unsigned int i=0;i<a->request.count;i++){
  const struct cav_rule *r=&a->request.rules[i];const struct cav_resolved *s=&a->sids[i];
  if(r->uid==uid&&s->subject==current_sid&&s->source==ssid&&s->object==tsid&&r->tclass==tclass&&cav_path_matches(r,path,length))allowed|=r->permissions;
 }
 return (allowed&requested)==requested;
}
#endif
