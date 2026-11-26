/* SPDX-License-Identifier: GPL-2.0-only
 * Authenticated path policy CLI. Apply requires an active-VFS capability.
 */
// Applies and persists path visibility rules.
#include "../visibility/path_rules.h"
#include "../visibility/path_protocol.h"
static struct cpr_table pv_request;

static void pv_log(const char *event,i64 value){
 char line[192],number[24];char *out=line;
 out=stpcpy_(out,"[path] pid=");utoa((u32)sc0(SYS_getpid),number);out=stpcpy_(out,number);
 out=stpcpy_(out," event=");out=stpcpy_(out,event);out=stpcpy_(out," value=");
 if(value<0){*out++='-';value=-(value+1);value++;}
 utoa((u32)value,number);out=stpcpy_(out,number);*out++='\n';*out=0;loga(line);
}
static int pv_output(const char *s,int rc){
 
 pv_log("output-begin",rc);loga(s);pv_log("end",rc);
 sc3(SYS_write,rc?2:1,(i64)s,slen(s));return rc;
}
static int pv_uid(const char *s,unsigned int *out){
 u64 v=0;if(!s||!*s)return -1;
 for(;*s;s++){if(*s<'0'||*s>'9')return -1;v=v*10+(*s-'0');if(v>4294967295UL)return -1;}
 if(v<10000)return -1;*out=(unsigned int)v;return 0;
}
static i64 pv_prctl(unsigned long operation,const void *argument){
 return sc5(SYS_prctl,0x43414952L,(i64)g_wl_key[0],(i64)g_wl_key[1],operation,(i64)argument);
}
static i64 pv_capability(void){
 if(!g_wl_key_valid)return -1;
 i64 result=pv_prctl(CP_OP_CAPABILITIES,0);pv_log("kernel-capability",result);return result;
}
static int pv_read_table(const char *path){
 
 i64 fd=sc4(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY|O_CLOEXEC|0x20000|0x800,0);
 if(fd<0)return (int)fd;
 unsigned long offset=0;
 while(offset<sizeof(pv_request)){
  i64 count=sc3(SYS_read,fd,(i64)((unsigned char *)&pv_request+offset),sizeof(pv_request)-offset);
  if(count==-4)continue;
  if(count<=0){sc1(SYS_close,fd);return -1;}
  offset+=(unsigned long)count;
 }
 unsigned char extra;
 i64 trailing;do{trailing=sc3(SYS_read,fd,(i64)&extra,1);}while(trailing==-4);
 sc1(SYS_close,fd);
 return trailing==0 && !cpr_validate(&pv_request)?0:-1;
}
#define PV_DIRECTORY CAIRN_BASE "/path_visibility"
static i64 pv_directory=-1,pv_lockfd=-1;
static int pv_store_open(void){
 i64 rc=sc3(SYS_mkdirat,AT_FDCWD,(i64)PV_DIRECTORY,0700);
 if(rc<0 && rc!=-17)return -1;
 pv_directory=sc4(SYS_openat,AT_FDCWD,(i64)PV_DIRECTORY,O_RDONLY|O_DIRECTORY|O_CLOEXEC|0x20000,0);
 if(pv_directory<0)return -1;
 if(sc2(SYS_fchmod,pv_directory,0700)<0)return -1;
 pv_lockfd=sc4(SYS_openat,pv_directory,(i64)"rules.lock",O_CREAT|O_WRONLY|O_CLOEXEC|0x20000,0600);
 if(pv_lockfd<0 || sc3(32,pv_lockfd,2,0)<0)return -1;
 return 0;
}
static void pv_store_close(void){
 if(pv_lockfd>=0){sc1(SYS_close,pv_lockfd);pv_lockfd=-1;}
 if(pv_directory>=0){sc1(SYS_close,pv_directory);pv_directory=-1;}
}
static int pv_persist(void){
 
 sc3(SYS_unlinkat,pv_directory,(i64)"rules.tmp",0);
 i64 fd=sc4(SYS_openat,pv_directory,(i64)"rules.tmp",O_WRONLY|O_CREAT|0x80 |O_CLOEXEC|0x20000,0600);
 if(fd<0)return -1;
 unsigned long offset=0;int failure=0;
 while(offset<sizeof(pv_request)){
  i64 n=sc3(SYS_write,fd,(i64)((unsigned char *)&pv_request+offset),sizeof(pv_request)-offset);
  if(n==-4)continue;if(n<=0){failure=1;break;}offset+=(unsigned long)n;
 }
 if(!failure && sc1(82 ,fd)<0)failure=1;
 sc1(SYS_close,fd);
 if(failure){sc3(SYS_unlinkat,pv_directory,(i64)"rules.tmp",0);return -1;}
 if(sc4(38 ,pv_directory,(i64)"rules.tmp",pv_directory,(i64)"rules.bin")<0)return -1;
 return sc1(82,pv_directory)<0?-1:0;
}
static int pv_load_saved(void){
 
 char location[160];char number[24];char *end=stpcpy_(location,"/proc/self/fd/");
 utoa((u32)pv_directory,number);end=stpcpy_(end,number);stpcpy_(end,"/rules.bin");
 return pv_read_table(location);
}
static int pv_apply_loaded_mode(int save){
 pv_log("validated-rule-count",pv_request.count);
 pv_log("request-bytes",sizeof(pv_request));
 i64 cap=pv_capability();
 if(cap!=(i64)(CP_CAP_INGEST_V1|CP_CAP_VFS_ACTIVE))
  return pv_output("PATH_ERROR=kernel-vfs-capability-unavailable\nPATH_APPLIED=false\n",6);
 pv_log("kernel-submit-op",CP_OP_REPLACE);
 i64 result=pv_prctl(CP_OP_REPLACE,&pv_request);
 pv_log("kernel-return",result);
 if(result)return pv_output("PATH_ERROR=kernel-rejected-update\nPATH_APPLIED=false\n",6);
 if(save && pv_persist())return pv_output("PATH_APPLIED=true\nPATH_PERSISTED=false\nPATH_ERROR=persistence-failed\n",7);
 return pv_output(save?"PATH_APPLIED=true\nPATH_PERSISTED=true\n":"PATH_APPLIED=true\nPATH_RESTORED=true\n",0);
}
static int pv_apply_loaded(void){
 if(pv_store_open()){pv_store_close();return pv_output("PATH_ERROR=storage-lock-failed\n",7);}
 int result=pv_apply_loaded_mode(1);pv_store_close();return result;
}
static int pv_saved_command(int restore){
 if(pv_store_open()){pv_store_close();return pv_output("PATH_ERROR=storage-lock-failed\n",7);}
 int loaded=pv_load_saved();if(loaded){pv_store_close();return pv_output(loaded==-2?"PATH_ERROR=saved-rules-missing\n":"PATH_ERROR=saved-rules-invalid\n",2);}
 if(restore){int rc=pv_apply_loaded_mode(0);pv_store_close();return rc;}
 
 const char *marker="PATH_RULES_HEX=";sc3(SYS_write,1,(i64)marker,slen(marker));
 char text[512];const char *digits="0123456789abcdef";
 for(unsigned long offset=0;offset<sizeof(pv_request);){
  unsigned long n=sizeof(pv_request)-offset;if(n>256)n=256;
  for(unsigned long i=0;i<n;i++){unsigned char c=((unsigned char *)&pv_request)[offset+i];text[2*i]=digits[c>>4];text[2*i+1]=digits[c&15];}
  i64 done=0;while(done<(i64)(n*2)){i64 w=sc3(SYS_write,1,(i64)(text+done),n*2-done);if(w==-4)continue;if(w<=0){pv_store_close();return 8;}done+=w;}
  offset+=n;
 }
 sc3(SYS_write,1,(i64)"\n",1);pv_log("export-rule-count",pv_request.count);pv_store_close();return 0;
}
static int pv_apply_file(const char *path){
 if(pv_read_table(path))return pv_output("PATH_ERROR=invalid-rule-file\n",2);
 return pv_apply_loaded();
}

static int pv_hex_stdin(void){
 unsigned char buffer[512];unsigned long digits=0;int high=0;
 for(;;){
  i64 n=sc3(SYS_read,0,(i64)buffer,sizeof(buffer));
  if(n==-4)continue;if(n<0)return -1;if(!n)break;
  for(i64 i=0;i<n;i++){
   unsigned char c=buffer[i];
   if(c=='\n' || c=='\r'){if(digits!=sizeof(pv_request)*2)return -1;continue;}
   if(digits>=sizeof(pv_request)*2)return -1;
   int v= c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;
   if(v<0)return -1;
   if(!(digits&1))high=v;else ((unsigned char *)&pv_request)[digits/2]=(unsigned char)(high*16+v);
   digits++;
  }
 }
 return digits==sizeof(pv_request)*2 && !cpr_validate(&pv_request)?0:-1;
}
static int pv_cmd(long argc,char **argv){
 if(sc0(SYS_getuid)==0 && sc0(SYS_geteuid)==0)log_open();
 pv_log("begin",argc);pv_log("uid",sc0(SYS_getuid));
 
 const char *operation="unknown";
 if(argc>2) {
  if(str_eq(argv[2],"status"))operation="status";
  else if(str_eq(argv[2],"apply-file"))operation="apply-file";
  else if(str_eq(argv[2],"apply-hex-stdin"))operation="apply-hex-stdin";
  else if(str_eq(argv[2],"validate-hex-stdin"))operation="validate-hex-stdin";
  else if(str_eq(argv[2],"validate"))operation="validate";
  else if(str_eq(argv[2],"sync"))operation="sync";
 }
 pv_log(operation,0);
 if(argc==3 && str_eq(argv[2],"status"))
  {
  i64 cap=pv_capability();
  if(cap==(i64)(CP_CAP_INGEST_V1|CP_CAP_VFS_ACTIVE))
   return pv_output("path_rule_abi=1\npath_rule_limit=32\npath_enforcement=available\npath_apply_supported=true\n",0);
  return pv_output("path_rule_abi=1\npath_rule_limit=32\npath_enforcement=unavailable\npath_apply_supported=false\n",0);
 }
 if(sc0(SYS_getuid)!=0 || sc0(SYS_geteuid)!=0 || !g_wl_key_valid)
  return pv_output("PATH_ERROR=missing-auth\n",1);
 if(argc==3 && (str_eq(argv[2],"apply-hex-stdin") || str_eq(argv[2],"validate-hex-stdin"))){
  if(pv_hex_stdin())return pv_output("PATH_ERROR=invalid-rule-stream\n",2);
  if(str_eq(argv[2],"validate-hex-stdin"))return pv_output("PATH_VALIDATION=ok\nPATH_APPLIED=false\n",0);
  return pv_apply_loaded();
 }
 if(argc==4 && str_eq(argv[2],"apply-file"))return pv_apply_file(argv[3]);
 if(argc==3 && str_eq(argv[2],"sync"))return pv_saved_command(1);
 if(argc==3 && str_eq(argv[2],"export-hex"))return pv_saved_command(0);
 
 if(argc!=7 || !str_eq(argv[2],"validate"))
  return pv_output("Usage: cnd path-hide status|sync|apply-file TABLE|validate UID exact|subtree hide|allow /absolute/path\n",2);
 unsigned char *p=(unsigned char *)&pv_request;for(unsigned long i=0;i<sizeof(pv_request);i++)p[i]=0;
 pv_request.version=CPR_VERSION;pv_request.bytes=sizeof(pv_request);pv_request.count=1;
 struct cpr_rule *rule=&pv_request.rules[0];
 if(pv_uid(argv[3],&rule->uid))return pv_output("PATH_ERROR=invalid-uid\n",2);
 if(str_eq(argv[4],"subtree"))rule->flags|=CPR_SUBTREE;
 else if(!str_eq(argv[4],"exact"))return pv_output("PATH_ERROR=invalid-match\n",2);
 if(str_eq(argv[5],"allow"))rule->flags|=CPR_ALLOW;
 else if(!str_eq(argv[5],"hide"))return pv_output("PATH_ERROR=invalid-action\n",2);
 while(argv[6][rule->length]){
  if(rule->length>=CPR_PATH_BYTES-1)return pv_output("PATH_ERROR=path-too-long\n",2);
  rule->path[rule->length]=argv[6][rule->length];rule->length++;
 }
 if(cpr_validate(&pv_request))return pv_output("PATH_ERROR=invalid-path\n",2);
 
 if(cpr_hidden(&pv_request,rule->uid,rule->path,rule->length)!=!(rule->flags&CPR_ALLOW))
  return pv_output("PATH_ERROR=selector-check\n",2);
 return pv_output("PATH_VALIDATION=ok\nPATH_APPLIED=false\n",0);
}
