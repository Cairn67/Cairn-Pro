/* SPDX-License-Identifier: GPL-2.0-only
 * Cairn Pro bounded access policy core. No hooks are installed by this header.
 * Input MUST be a canonical, referenced VFS path, never syscall path text.
 * Callers must synchronize table lifetime and validate it before publication.
 */
// Access rule structures.
#ifndef CAIRN_ACCESS_RULES_H
#define CAIRN_ACCESS_RULES_H
#define CAR_MAGIC 0x43415231U
#define CAR_MAX 32U
#define CAR_PATH 256U
#define CAR_READ 1U
#define CAR_WRITE 2U
#define CAR_SEARCH 4U
#define CAR_EXEC 8U
#define CAR_META 16U
#define CAR_MAP 32U
#define CAR_ALL 63U
struct car_rule { unsigned int uid, ops, length, reserved; char path[CAR_PATH]; };
struct car_table { unsigned int magic, version, count, reserved; struct car_rule rules[CAR_MAX]; };
static int car_path_valid(const char *p, unsigned int n) {
 if (!p || n<2 || n>=CAR_PATH || p[0]!='/' || p[n]!=0 || p[n-1]=='/') return 0;
 unsigned int start=1;
 for(unsigned int i=1;i<=n;i++) {
  if(i<n && ((unsigned char)p[i]<32 || (unsigned char)p[i]==127))return 0;
  if(i==n || p[i]=='/') {
   unsigned int len=i-start;
   if(!len || (len==1 && p[start]=='.') || (len==2 && p[start]=='.' && p[start+1]=='.'))return 0;
   start=i+1;
  }
 }
 return 1;
}
static int car_validate(const struct car_table *t) {
 if(!t || t->magic!=CAR_MAGIC || t->version!=1 || t->count>CAR_MAX || t->reserved)return -1;
 for(unsigned int i=0;i<t->count;i++) {
  const struct car_rule *r=&t->rules[i];
  if(!r->uid || !r->ops || (r->ops & ~CAR_ALL) || r->reserved || !car_path_valid(r->path,r->length))return -1;
  for(unsigned int j=0;j<i;j++) {
   const struct car_rule *s=&t->rules[j];
   if(r->uid!=s->uid || r->length!=s->length)continue;
   unsigned int k=0;while(k<r->length && r->path[k]==s->path[k])k++;
   if(k==r->length)return -1;
  }
 }
 return 0;
}

static int car_allowed(const struct car_table *t,unsigned int uid,unsigned int euid,
 unsigned int fsuid,const char *p,unsigned int n,unsigned int op) {
 if(!t || t->count>CAR_MAX || uid!=euid || uid!=fsuid || !uid || !op || (op & ~CAR_ALL) || !car_path_valid(p,n))return 0;
 unsigned int best=0, granted=0;
 for(unsigned int i=0;i<t->count;i++) {
  const struct car_rule *r=&t->rules[i];
  if(r->uid!=uid || r->length>n || r->length<=best)continue;
  unsigned int j=0;while(j<r->length && r->path[j]==p[j])j++;
  if(j!=r->length || (n>j && p[j]!='/'))continue;
  best=r->length;granted=r->ops;
 }
 return best && (granted & op)==op;
}
#endif
