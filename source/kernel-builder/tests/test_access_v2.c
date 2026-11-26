/* SPDX-License-Identifier: GPL-2.0-only
 * Shared text parser + actual kernel snapshot/matcher failure tests.
 */
// Tests for access rule version 2.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../daemon/access_parser.h"
#include "../access_runtime.h"
static struct ca_runtime runtime;
static unsigned int epoch=7,current_sid=101;
static int fail_alloc,fail_copy,fail_context,change_epoch,allocations,resolve_calls,locked;
struct ca_runtime *ca_runtime_peek(void){return &runtime;}
unsigned int cav_current_sid(void){return current_sid;}
unsigned int cav_policy_sequence(void){return epoch;}
int cav_resolve_context(const char *s,unsigned int n,unsigned int *sid){
 assert(!locked&&n==strlen(s));
 if(fail_context)return -22;
 *sid=strstr(s,"system_server")?101:202;
 if(change_epoch){epoch++;change_epoch=0;}
 return 0;
}
static unsigned long lk(void *p){(void)p;assert(!locked);locked=1;return 9;}
static void ul(void *p,unsigned long f){(void)p;assert(locked&&f==9);locked=0;}
static void *al(unsigned long n){assert(!locked);if(fail_alloc)return NULL;void *p=malloc(n);assert(p);allocations++;return p;}
static void fr(void *p){assert(!locked);allocations--;free(p);}
static unsigned long cp(void *p,const void *v,unsigned long n){assert(!locked);if(fail_copy)return n;memcpy(p,v,n);return 0;}
static const char *actual_path="/dev/pts/2";
static char *resolve(const void *p,char *out,int cap){(void)p;assert(!locked);resolve_calls++;assert(strlen(actual_path)+1<(unsigned int)cap);strcpy(out,actual_path);return out;}
#include "../access_backend.c"
#include "../access_avc.c"
static int checks;
#define CK(v) do {checks++;assert(v);} while(0)
static int line(struct cav_request *r,struct cap_file *f,const char *s){char text[1100];assert(strlen(s)<sizeof(text));strcpy(text,s);return cap_line(r,f,text);}
static void pointer(void *p,unsigned int n,const void *v){memcpy((char *)p+n,&v,sizeof(v));}
static void word(void *p,unsigned int n,unsigned int v){memcpy((char *)p+n,&v,4);}
static struct cav_request request;
static const char *rule="allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:untrusted_app_all_devpts:s0:c105,c257,c512,c768 class=chr_file perms=write path=/dev/pts/2 match=exact";
int main(void){
 runtime=(struct ca_runtime){.lock=lk,.unlock=ul,.allocate=al,.release=fr,.copy_user=cp,.resolve=resolve};
 cap_init(&request);struct cap_file f={0};
 CK(!line(&request,&f,"# literal comment"));CK(!line(&request,&f,"version=2\r"));CK(!line(&request,&f,rule));CK(!cav_validate(&request));
 CK(request.count==1&&request.rules[0].permissions==4&&request.rules[0].match==CAV_EXACT);
 CK(!line(&request,&f,rule)&&request.count==1);CK(line(&request,&f,"version=2"));
 const char *bad[]={"allow", "version=3", "12001 r /tmp/x", "allow uid=1000", "allow uid=0 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=sock_file perms=bind", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write,write", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write unknown=yes", "allow uid=1000 uid=1001 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=sock_file perms=create path=/tmp/a", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=getattr path=/dev/pts/2", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write match=exact", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write path=/dev/../pts/2", "allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write path=\"/dev/pts/2\"suffix"};
 for(unsigned int i=0;i<sizeof(bad)/sizeof(bad[0]);i++){struct cav_request scratch;cap_init(&scratch);struct cap_file fmt={2,1};CK(line(&scratch,&fmt,bad[i])!=0);}
 CK(ca_replace_v2(&runtime,1000,0,1,&request)==-1);CK(ca_replace_v2(&runtime,0,0,0,&request)==-1);
 fail_alloc=1;CK(ca_replace_v2(&runtime,0,0,1,&request)==-12);fail_alloc=0;
 fail_copy=1;CK(ca_replace_v2(&runtime,0,0,1,&request)==-14&&!allocations);fail_copy=0;
 fail_context=1;CK(ca_replace_v2(&runtime,0,0,1,&request)==-22&&!allocations);fail_context=0;
 change_epoch=1;CK(ca_replace_v2(&runtime,0,0,1,&request)==-11&&!allocations);
 CK(!ca_replace_v2(&runtime,0,0,1,&request));CK(allocations==1&&runtime.scoped&&runtime.active==&runtime.scoped->request.legacy);
 _Alignas(8) unsigned char task[2128]={0},cred[160]={0},file[224]={0},audit[40]={0};pointer(task,2080,cred);word(cred,8,1000);word(cred,24,1000);word(cred,32,1000);audit[0]=12;pointer(audit,8,file);
 CK(cav_avc_allowed(101,202,11,4,audit,task));CK(!cav_avc_allowed(101,202,11,2,audit,task));CK(!cav_avc_allowed(101,202,13,4,audit,task));CK(!cav_avc_allowed(101,203,11,4,audit,task));
 actual_path="/dev/pts/20";CK(!cav_avc_allowed(101,202,11,4,audit,task));actual_path="/dev/pts/2";
 CK(!cav_avc_allowed(101,202,11,4,NULL,task));audit[0]=10;CK(!cav_avc_allowed(101,202,11,4,audit,task));audit[0]=12;
 current_sid=102;CK(!cav_avc_allowed(101,202,11,4,audit,task));current_sid=101;
 word(cred,32,1001);CK(!cav_avc_allowed(101,202,11,4,audit,task));word(cred,32,1000);
 epoch++;CK(!cav_avc_allowed(101,202,11,4,audit,task));CK(!ca_replace_v2(&runtime,0,0,1,&request)&&allocations==1);CK(cav_avc_allowed(101,202,11,4,audit,task));
 struct cav_active *old=runtime.scoped;request.rules[0].reserved=1;CK(ca_replace_v2(&runtime,0,0,1,&request)==-22&&runtime.scoped==old&&allocations==1);request.rules[0].reserved=0;
 struct car_table empty={.magic=CAR_MAGIC,.version=1};CK(!ca_replace(&runtime,0,0,1,&empty)&&!runtime.scoped);CK(!cav_avc_allowed(101,202,11,4,audit,task));
 word(cred,8,0);word(cred,24,0);word(cred,32,0);CK(cav_root_subject(task));CK(cav_avc_allowed(0,0,99,~0U,NULL,task));word(cred,32,1000);CK(!cav_root_subject(task));
 unsigned int decision[5]={0xaaaaaaaa,0xbbbbbbbb,0xcccccccc,0xdddddddd,0xeeeeeeee};cav_fill_decision(decision,0x200000);CK(decision[0]==0x200000&&!decision[1]&&!decision[2]&&decision[3]==epoch&&!decision[4]);
 cap_init(&request);f=(struct cap_file){0};CK(!line(&request,&f,"12001 rpm /module/file"));CK(!line(&request,&f,"12001 w /module/file"));CK(request.legacy.count==1&&request.legacy.rules[0].ops==(CAR_READ|CAR_WRITE|CAR_MAP|CAR_META));
 cap_init(&request);f=(struct cap_file){0};CK(!line(&request,&f,"version=2"));CK(!line(&request,&f,"allow uid=1000 subject=u:r:system_server:s0 object=u:r:logd:s0 class=unix_stream_socket perms=connectto"));CK(request.rules[0].permissions==0x200000);
 CK(!line(&request,&f,"allow uid=1000 subject=u:r:system_server:s0 object=u:object_r:adb_data_file:s0 class=sock_file perms=create,unlink,read,write,open,getattr,setattr"));CK(!cav_validate(&request));
 fr(runtime.active);runtime.active=NULL;CK(!allocations&&!locked);
 printf("ACCESS_V2_UNIT_PASS checks=%d parser=1 root=1 sid=1 path=1 atomic=1 epoch=1 cleanup=1\n",checks);return 0;
}
