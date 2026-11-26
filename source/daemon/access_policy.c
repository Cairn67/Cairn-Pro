/* SPDX-License-Identifier: GPL-2.0-only
 * Experimental access CLI. Never report success without ACTIVE device capability.
 * replace-one replaces the ENTIRE runtime table, not an additive grant.
 * Independent persistent storage; sync is manual, not an installed boot service.
 */
// Applies access rules received from the manager.
#include "access_parser.h"
#define CA_OP_REPLACE 6UL
#define CA_OP_CAPABILITIES 7UL
#define CA_CAP_ACTIVE_V1 0x43410101UL
static struct cav_request ca_bundle;
#define ca_request (ca_bundle.legacy)
static void ca_reset(void){cap_init(&ca_bundle);}
static int ca_output(const char *s,int rc){sc3(SYS_write,rc?2:1,(i64)s,slen(s));return rc;}
static int ca_number(const char *s,unsigned int *out){
 u64 n=0;if(!s || !*s)return -1;
 for(;*s;s++){if(*s<'0'||*s>'9')return -1;n=n*10+(*s-'0');if(n>4294967295UL)return -1;}
 *out=(unsigned int)n;return 0;
}
static int ca_read_table(const char *path){
 i64 fd=sc4(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY|O_CLOEXEC|0x20000|0x800,0);
 if(fd<0)return (int)fd;
 ca_reset();unsigned long offset=0,wanted=4;unsigned int magic=0;
 while(offset<wanted){
  void *buffer=magic==CAR_MAGIC?(void *)&ca_request:(void *)&ca_bundle;
  i64 n=sc3(SYS_read,fd,(i64)((unsigned char *)buffer+offset),wanted-offset);
  if(n==-4)continue;
  if(n<=0){sc1(SYS_close,fd);return -1;}offset+=(unsigned long)n;
  if(offset==4&&!magic){
   magic=ca_bundle.magic;
   if(magic==CAR_MAGIC){ca_reset();ca_request.magic=magic;wanted=sizeof(ca_request);}
   else if(magic==CAV_MAGIC)wanted=sizeof(ca_bundle);
   else {sc1(SYS_close,fd);return -1;}
  }
 }
 unsigned char extra;i64 tail;do{tail=sc3(SYS_read,fd,(i64)&extra,1);}while(tail==-4);
 sc1(SYS_close,fd);
 return tail==0&&!cav_validate(&ca_bundle)?0:-1;
}
#define CA_DIRECTORY CAIRN_BASE "/access"
static i64 ca_directory=-1,ca_lockfd=-1;
static int ca_store_open(void){
 i64 rc=sc3(SYS_mkdirat,AT_FDCWD,(i64)CA_DIRECTORY,0700);
 if(rc<0 && rc!=-17)return -1;
 ca_directory=sc4(SYS_openat,AT_FDCWD,(i64)CA_DIRECTORY,O_RDONLY|O_DIRECTORY|O_CLOEXEC|0x20000,0);
 if(ca_directory<0)return -1;
 if(sc2(SYS_fchmod,ca_directory,0700)<0)return -1;
 ca_lockfd=sc4(SYS_openat,ca_directory,(i64)"rules.lock",O_CREAT|O_WRONLY|O_CLOEXEC|0x20000,0600);
 if(ca_lockfd<0 || sc3(32,ca_lockfd,2,0)<0)return -1;
 return 0;
}
static void ca_store_close(void){
 if(ca_lockfd>=0){sc1(SYS_close,ca_lockfd);ca_lockfd=-1;}
 if(ca_directory>=0){sc1(SYS_close,ca_directory);ca_directory=-1;}
}
static int ca_persist(void){
 
 sc3(SYS_unlinkat,ca_directory,(i64)"rules.tmp",0);
 i64 fd=sc4(SYS_openat,ca_directory,(i64)"rules.tmp",O_WRONLY|O_CREAT|0x80 |O_CLOEXEC|0x20000,0600);
 if(fd<0)return -1;
 const void *table=ca_bundle.count?(const void *)&ca_bundle:(const void *)&ca_request;
 unsigned long bytes=ca_bundle.count?sizeof(ca_bundle):sizeof(ca_request);
 unsigned long offset=0;int failure=0;
 while(offset<bytes){
  i64 n=sc3(SYS_write,fd,(i64)((const unsigned char *)table+offset),bytes-offset);
  if(n==-4)continue;if(n<=0){failure=1;break;}offset+=(unsigned long)n;
 }
 if(!failure && sc1(82 ,fd)<0)failure=1;
 sc1(SYS_close,fd);
 if(failure){sc3(SYS_unlinkat,ca_directory,(i64)"rules.tmp",0);return -1;}
 if(sc4(38 ,ca_directory,(i64)"rules.tmp",ca_directory,(i64)"rules.bin")<0)return -1;
 return sc1(82,ca_directory)<0?-1:0;
}
static int ca_load_saved(void){
 
 char location[160];char number[24];char *end=stpcpy_(location,"/proc/self/fd/");
 utoa((u32)ca_directory,number);end=stpcpy_(end,number);stpcpy_(end,"/rules.bin");
 return ca_read_table(location);
}
static int ca_apply_saved(int persist){
 if(cav_validate(&ca_bundle))return ca_output("ACCESS_ERROR=invalid-rule-file\n",2);
 long version=pv_prctl(CAV_OP_CAPABILITIES,0),rc;
 if(version==CAV_CAP)rc=pv_prctl(CAV_OP_REPLACE,&ca_bundle);
 else {
  if(ca_bundle.count)return ca_output("ACCESS_APPLIED=false\nACCESS_ERROR=kernel-v2-required\n",6);
  if(pv_prctl(CA_OP_CAPABILITIES,0)!=CA_CAP_ACTIVE_V1)return ca_output("ACCESS_APPLIED=false\nACCESS_ERROR=kernel-capability-unavailable\n",6);
  rc=pv_prctl(CA_OP_REPLACE,&ca_request);
 }
 if(rc)return ca_output("ACCESS_APPLIED=false\nACCESS_ERROR=kernel-rejected-update\n",6);
 if(persist&&ca_persist())return ca_output("ACCESS_APPLIED=true\nACCESS_PERSISTED=false\nACCESS_ERROR=persistence-failed\n",7);
 return ca_output(persist?"ACCESS_APPLIED=true\nACCESS_PERSISTED=true\n":"ACCESS_APPLIED=true\nACCESS_RESTORED=true\n",0);
}
static int ca_load_text(const char *path);
#include "module_access.c"
static int ca_cmd(long argc,char **argv){
 if(argc==3 && str_eq(argv[2],"reload-modules")){if(!g_wl_key_valid&&ca_boot_key(0))return ca_output("ACCESS_ERROR=boot-credential-unavailable\n",3);return ca_reload_modules();}
 if(argc==3 && str_eq(argv[2],"provision-boot")){int rc=ca_boot_key(1);return ca_output(rc?"ACCESS_BOOT_CREDENTIAL=false\n":"ACCESS_BOOT_CREDENTIAL=true\n",rc?3:0);}
 if(sc0(SYS_getuid)!=0 || sc0(SYS_geteuid)!=0 || !g_wl_key_valid)return ca_output("ACCESS_ERROR=root-and-auth-required\n",3);
 if(argc==3 && str_eq(argv[2],"status")){
  i64 cap=pv_prctl(CA_OP_CAPABILITIES,0);
  if(pv_prctl(CAV_OP_CAPABILITIES,0)==CAV_CAP)return ca_output("ACCESS_ACTIVE=true\nACCESS_FORMAT=2\nROOT_UID0_AVC=true\n",0);
  return ca_output(cap==CA_CAP_ACTIVE_V1?"ACCESS_ACTIVE=true\n":"ACCESS_ACTIVE=false\n",cap==CA_CAP_ACTIVE_V1?0:6);
 }
 if(argc==4 && str_eq(argv[2],"validate")){int rc=ca_read_table(argv[3]);return ca_output(rc?"ACCESS_VALIDATION=failed\n":"ACCESS_VALIDATION=ok\n",rc?2:0);}
 if(argc==4&&str_eq(argv[2],"validate-text")){ca_reset();int rc=ca_load_text(argv[3]);return ca_output(rc?"ACCESS_VALIDATION=failed\n":"ACCESS_VALIDATION=ok\n",rc?2:0);}
 int text=argc==4&&str_eq(argv[2],"apply-text");
 int restore=argc==3 && str_eq(argv[2],"sync");
 int import=argc==4 && str_eq(argv[2],"apply");
 int one=argc==6 && str_eq(argv[2],"replace-one");
 int clear=argc==3 && str_eq(argv[2],"clear");
 if(!restore && !import && !one && !clear && !text)return ca_output("Usage: cnd access status | validate FILE | apply FILE | validate-text FILE | apply-text FILE | sync | clear | replace-one UID OPS PATH | provision-boot | reload-modules\napply and replace-one replace the entire table.\n",2);
 if(import && ca_read_table(argv[3]))return ca_output("ACCESS_ERROR=invalid-rule-file\n",2);
 if(text){ca_reset();if(ca_load_text(argv[3]))return ca_output("ACCESS_ERROR=invalid-text-rule-file\n",2);}
 if(one || clear){
  ca_reset();
  ca_request.magic=CAR_MAGIC;ca_request.version=1;ca_request.count=one?1:0;
  if(one){
   unsigned int uid,ops;if(ca_number(argv[3],&uid)||ca_number(argv[4],&ops))return ca_output("ACCESS_ERROR=invalid-number\n",2);
   unsigned long n=slen(argv[5]);if(n>=CAR_PATH)return ca_output("ACCESS_ERROR=path-too-long\n",2);
   ca_request.rules[0].uid=uid;ca_request.rules[0].ops=ops;ca_request.rules[0].length=n;
   for(unsigned long i=0;i<=n;i++)ca_request.rules[0].path[i]=argv[5][i];
  }
 }
 if(!restore && cav_validate(&ca_bundle))return ca_output("ACCESS_ERROR=invalid-rule\n",2);
 if(ca_store_open()){ca_store_close();return ca_output("ACCESS_ERROR=storage-lock-failed\n",7);}
 int rc;
 if(restore && ca_load_saved())rc=ca_output("ACCESS_ERROR=saved-rules-missing-or-invalid\n",2);
 else rc=ca_apply_saved(!restore);
 ca_store_close();return rc;
}
