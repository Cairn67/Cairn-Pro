/* SPDX-License-Identifier: GPL-2.0-only
 * Literal cairn.access parser shared with host tests. No shell evaluation.
 */
// Parser for the access rule payload.
#ifndef CAIRN_ACCESS_PARSER_H
#define CAIRN_ACCESS_PARSER_H
#include "../kernel-builder/access_v2.h"
struct cap_file {unsigned int version,started;};
static void cap_zero(void *out,unsigned long n){unsigned char *p=out;while(n--)*p++=0;}
static int cap_eq(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return *a==*b;}
static unsigned int cap_len(const char *s){unsigned int n=0;while(s[n])n++;return n;}
static int cap_number(const char *s,unsigned int *v){
 unsigned long n=0;if(!*s)return -1;
 for(;*s;s++){if(*s<'0'||*s>'9')return -1;n=n*10+(*s-'0');if(n>4294967295UL)return -1;}
 *v=(unsigned int)n;return 0;
}
static int cap_copy(char *to,unsigned int size,const char *from){unsigned int n=cap_len(from);if(n>=size)return -1;for(unsigned int i=0;i<=n;i++)to[i]=from[i];return 0;}
static void cap_init(struct cav_request *r){
 cap_zero(r,sizeof(*r));r->magic=CAV_MAGIC;r->version=CAV_VERSION;r->bytes=sizeof(*r);
 r->legacy.magic=CAR_MAGIC;r->legacy.version=1;
}
static int cap_legacy(struct cav_request *r,char *s){
 char *u=s;while(*s>='0'&&*s<='9')s++;
 if(*s!=' '&&*s!='\t')return -1;
 *s++=0;
 unsigned int uid,ops=0;if(cap_number(u,&uid)||!uid)return -1;
 while(*s==' '||*s=='\t')s++;
 while(*s&&*s!=' '&&*s!='\t'){
  unsigned int b=*s=='r'?CAR_READ:*s=='w'?CAR_WRITE:*s=='s'?CAR_SEARCH:*s=='x'?CAR_EXEC:*s=='m'?CAR_META:*s=='p'?CAR_MAP:0;
  if(!b||(ops&b))return -1;
  ops|=b;s++;
 }
 while(*s==' '||*s=='\t')s++;
 unsigned int n=cap_len(s);if(!ops||!car_path_valid(s,n))return -1;
 for(unsigned int i=0;i<r->legacy.count;i++){
  struct car_rule *v=&r->legacy.rules[i];if(v->uid==uid&&cap_eq(v->path,s)){v->ops|=ops;return 0;}
 }
 if(r->legacy.count==CAR_MAX)return -1;
 struct car_rule *v=&r->legacy.rules[r->legacy.count++];cap_zero(v,sizeof(*v));
 v->uid=uid;v->ops=ops;v->length=n;return cap_copy(v->path,sizeof(v->path),s);
}

static int cap_field(char **cursor,char **key,char **value){
 char *p=*cursor;while(*p==' '||*p=='\t')p++;if(!*p){*cursor=p;return 0;}
 *key=p;while(*p&&*p!='='&&*p!=' '&&*p!='\t')p++;
 if(*p!='=')return -1;
 *p++=0;
 if(*p=='"'){
  *value=++p;while(*p&&*p!='"')p++;
  if(*p!='"')return -1;
  *p++=0;
  if(*p&&*p!=' '&&*p!='\t')return -1;
 }else{
  *value=p;while(*p&&*p!=' '&&*p!='\t'){if(*p=='"')return -1;p++;}
  if(*p)*p++=0;
 }
 if(!**value)return -1;
 *cursor=p;return 1;
}
static int cap_permissions(const struct cav_class *cl,char *s,unsigned int *out){
 unsigned int ops=0;for(;;){
  char *end=s;while(*end&&*end!=',')end++;
  int more=*end!=0;*end=0;unsigned int bit=0;
  for(unsigned int i=0;i<32&&cl->perms[i][0];i++)if(cap_eq(s,cl->perms[i])){bit=1U<<i;break;}
  if(!bit||(ops&bit))return -1;
  ops|=bit;if(!more)break;s=end+1;
 }
 *out=ops;return 0;
}
static int cap_v2(struct cav_request *request,char *s){
 if(cap_len(s)<6)return -1;
 if(s[0]!='a'||s[1]!='l'||s[2]!='l'||s[3]!='o'||s[4]!='w'||(s[5]!=' '&&s[5]!='\t'))return -1;
 s+=5;struct cav_rule r;cap_zero(&r,sizeof(r));unsigned int seen=0;
 char *permissions=0,*classname=0,*match=0,*k,*v;int result;
 while((result=cap_field(&s,&k,&v))>0){
  unsigned int bit=cap_eq(k,"uid")?1:cap_eq(k,"subject")?2:cap_eq(k,"object")?4:cap_eq(k,"class")?8:cap_eq(k,"perms")?16:cap_eq(k,"path")?32:cap_eq(k,"match")?64:cap_eq(k,"source")?128:0;
  if(!bit||(seen&bit))return -1;
  seen|=bit;
  if(bit==1){if(cap_number(v,&r.uid))return -1;}
  else if(bit==2){if(cap_copy(r.subject,sizeof(r.subject),v))return -1;}
  else if(bit==4){if(cap_copy(r.object,sizeof(r.object),v))return -1;}
  else if(bit==8)classname=v;
  else if(bit==16)permissions=v;
  else if(bit==32){if(cap_copy(r.path,sizeof(r.path),v))return -1;}
  else if(bit==64)match=v;
  else if(cap_copy(r.source,sizeof(r.source),v))return -1;
 }
 if(result<0||(seen&31)!=31)return -1;
 if(!(seen&128)&&cap_copy(r.source,sizeof(r.source),r.subject))return -1;
 const struct cav_class *cl=0;for(unsigned int i=0;i<CAV_CLASSES;i++)if(cap_eq(classname,cav_classes[i].name)){cl=&cav_classes[i];break;}
 if(!cl||cap_permissions(cl,permissions,&r.permissions))return -1;
 r.tclass=cl->id;
 if(seen&32){r.match=match?(cap_eq(match,"exact")?CAV_EXACT:cap_eq(match,"subtree")?CAV_SUBTREE:99):CAV_EXACT;}
 else if(match)return -1;
 if(cav_validate_rule(&r))return -1;
 for(unsigned int i=0;i<request->count;i++)if(cav_same_scope(&request->rules[i],&r)){request->rules[i].permissions|=r.permissions;return 0;}
 if(request->count==CAV_MAX)return -1;
 unsigned char *out=(unsigned char *)&request->rules[request->count++];
 const unsigned char *in=(const unsigned char *)&r;
 for(unsigned int i=0;i<sizeof(r);i++)out[i]=in[i];
 return 0;
}
static int cap_line(struct cav_request *r,struct cap_file *file,char *line){
 char *s=line;while(*s==' '||*s=='\t')s++;if(!*s||*s=='#')return 0;
 unsigned int n=cap_len(s);if(n&&s[n-1]=='\r')s[--n]=0;
 while(n&&(s[n-1]==' '||s[n-1]=='\t'))s[--n]=0;
 if(!n)return 0;
 if(cap_eq(s,"version=2")||cap_eq(s,"version=1")){
  if(file->started)return -1;
  file->started=1;file->version=s[8]=='2'?2:1;return 0;
 }
 if(!file->started){file->started=1;file->version=1;}
 return file->version==2?cap_v2(r,s):cap_legacy(r,s);
}
#endif
