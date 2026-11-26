/* SPDX-License-Identifier: GPL-2.0-only
 * Included by the isolated VM access integration harness. Never run on a phone.
 * The parent stays privileged; each probe runs in an untrusted UID/domain child.
 */
// Virtual machine tests for the mmap access path.
struct mm_result {
 int setup,opened,open_error,mapped,map_error,protect,protect_error,content,executed;
 int stage,retained,new_map,new_error,upgrade,upgrade_error,restored;
 uid_t uid,euid;char ctx[128];
};
static struct mm_result *mm;
static int mm_denied(int error){return error==EACCES||error==EPERM;}
static int mm_enter(unsigned int uid){
 if(textwrite("/proc/self/attr/current",appctx)||setgid(uid)||setuid(uid)){mm->setup=errno;return -1;}
 mm->uid=getuid();mm->euid=geteuid();if(context(mm->ctx,sizeof(mm->ctx)))mm->setup=errno;
 return mm->setup?-1:0;
}
static void mm_identity(unsigned int uid){ck(!mm->setup&&mm->uid==uid&&mm->euid==uid&&!strcmp(mm->ctx,appctx),"mmap-subject-identity-preserved");}
static void mm_probe(unsigned int uid,const char *path,int open_mode,int prot,int flags,int next_prot,int dirty){
 memset(mm,0,sizeof(*mm));pid_t p=fork();ck(p>=0,"mmap-probe-fork");
 if(!p){
  if(mm_enter(uid))_exit(0);
  errno=0;int f=open(path,open_mode);mm->opened=f>=0;mm->open_error=errno;
  if(f>=0){
   errno=0;unsigned char *v=mmap(NULL,4096,prot,flags,f,0);mm->mapped=v!=MAP_FAILED;mm->map_error=errno;
   if(v!=MAP_FAILED){
    if(prot&PROT_READ)mm->content=v[0]==0x40;
    if(dirty)v[0]=0x40;
    if(next_prot>=0){errno=0;mm->protect=mprotect(v,4096,next_prot)==0;mm->protect_error=errno;}
    if((next_prot<0&&(prot&PROT_EXEC))||(mm->protect&&(next_prot&PROT_EXEC)))mm->executed=((int (*)(void))v)();
    munmap(v,4096);
   }
   close(f);
  }
  _exit(0);
 }
 waitok(p);printf("MMAP_OBSERVATION uid=%u path=%s prot=%d flags=%d next=%d opened=%d open_errno=%d mapped=%d map_errno=%d protect=%d protect_errno=%d executed=%d\n",uid,path,prot,flags,next_prot,mm->opened,mm->open_error,mm->mapped,mm->map_error,mm->protect,mm->protect_error,mm->executed);mm_identity(uid);
}
static void mm_table(struct car_table *t,unsigned int permissions){
 memset(t,0,sizeof(*t));t->magic=CAR_MAGIC;t->version=1;
 rule(t,12001,CAR_SEARCH,"/mmap-grants");rule(t,12001,permissions,"/mmap-grants/code");
 rule(t,12002,CAR_SEARCH,"/mmap-grants");rule(t,12002,CAR_READ|CAR_META,"/mmap-grants/code");
 rule(t,12001,CAR_SEARCH,"/mmap-grants-other");rule(t,12001,CAR_READ|CAR_META,"/mmap-grants-other/code");
 rule(t,12001,CAR_SEARCH,"/mmap-noexec");rule(t,12001,CAR_ALL,"/mmap-noexec/code");
 ck(!car_validate(t)&&!op(6,t),"mmap-rule-publish");
}
static void mm_fixture(const char *path){
 unsigned char code[4096]={0x40,0x05,0x80,0x52,0xc0,0x03,0x5f,0xd6}; 
 int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC,0666);ck(fd>=0&&write(fd,code,sizeof(code))==sizeof(code),"mmap-code-fixture");close(fd);chmod(path,0666);label(path);
}
static int mm_anonymous_probe(void){
 memset(mm,0,sizeof(*mm));pid_t p=fork();ck(p>=0,"anonymous-execmem-probe-fork");
 if(!p){
  if(mm_enter(12001))_exit(0);
  errno=0;void *v=mmap(NULL,4096,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
  mm->mapped=v!=MAP_FAILED;mm->map_error=errno;
  if(v!=MAP_FAILED)munmap(v,4096);
  _exit(0);
 }
 waitok(p);mm_identity(12001);printf("ANONYMOUS_EXECMEM_OBSERVATION allowed=%d errno=%d\n",mm->mapped,mm->map_error);
 return mm->mapped?0:mm->map_error;
}
static void mm_wait(int stage){for(int i=0;i<5000;i++){if(__atomic_load_n(&mm->stage,__ATOMIC_ACQUIRE)==stage)return;usleep(1000);}puts("VM_FAIL mmap-stage-timeout");exit(86);}
static void mm_revoke(void){
 struct car_table full,readonly,empty={.magic=CAR_MAGIC,.version=1};
 mm_table(&full,CAR_READ|CAR_WRITE|CAR_MAP|CAR_EXEC|CAR_META);
 memset(mm,0,sizeof(*mm));pid_t p=fork();ck(p>=0,"mmap-revoke-fork");
 if(!p){
  int f=-1;unsigned char *v=MAP_FAILED;
  if(!mm_enter(12001)){f=open("/mmap-grants/code",O_RDWR);mm->opened=f>=0;if(f>=0)v=mmap(NULL,4096,PROT_READ,MAP_SHARED,f,0);mm->mapped=v!=MAP_FAILED;}
  __atomic_store_n(&mm->stage,1,__ATOMIC_RELEASE);mm_wait(2);
  if(v!=MAP_FAILED){errno=0;mm->upgrade=mprotect(v,4096,PROT_READ|PROT_WRITE)==0;mm->upgrade_error=errno;}
  __atomic_store_n(&mm->stage,3,__ATOMIC_RELEASE);mm_wait(4);
  if(v!=MAP_FAILED)mm->retained=v[0]==0x40;
  if(f>=0){errno=0;void *n=mmap(NULL,4096,PROT_READ,MAP_PRIVATE,f,0);mm->new_map=n!=MAP_FAILED;mm->new_error=errno;if(n!=MAP_FAILED)munmap(n,4096);}
  __atomic_store_n(&mm->stage,5,__ATOMIC_RELEASE);mm_wait(6);
  if(f>=0){void *n=mmap(NULL,4096,PROT_READ,MAP_PRIVATE,f,0);mm->restored=n!=MAP_FAILED;if(n!=MAP_FAILED)munmap(n,4096);close(f);}
  if(v!=MAP_FAILED)munmap(v,4096);
  _exit(0);
 }
 mm_wait(1);mm_identity(12001);ck(mm->opened&&mm->mapped,"mmap-held-fd-and-vma-created");
 mm_table(&readonly,CAR_READ|CAR_MAP|CAR_META);__atomic_store_n(&mm->stage,2,__ATOMIC_RELEASE);mm_wait(3);
 ck(!mm->upgrade&&mm_denied(mm->upgrade_error),"mprotect-held-rw-fd-write-grant-required");
 ck(!op(6,&empty),"mmap-clear-access-table");__atomic_store_n(&mm->stage,4,__ATOMIC_RELEASE);mm_wait(5);
 ck(mm->retained,"existing-mapping-remains-readable-known-limit");ck(!mm->new_map&&mm_denied(mm->new_error),"new-mapping-on-held-fd-denied-after-clear");
 ck(!op(6,&full),"mmap-restore-access-table");__atomic_store_n(&mm->stage,6,__ATOMIC_RELEASE);waitok(p);ck(mm->restored,"new-mapping-on-same-fd-restored");
 puts("KNOWN_LIMIT_EXISTING_VMA_REVOCATION unsupported=1 reason=grants-do-not-unmap-existing-mappings");
}
static void mm_module_parser_test(void){
 mkdir("/data/adb/modules",0755);mkdir("/data/adb/modules/cairn-mmap-vm",0755);
 const char *path="/data/adb/modules/cairn-mmap-vm/cairn.access";
 
 int fd=open(path,O_CREAT|O_WRONLY|O_TRUNC,0600);ck(fd>=0,"mmap-module-access-create");close(fd);
 ck(!textwrite(path,"12001 s /mmap-grants\n12001 rpm /mmap-grants/code\n"),"mmap-module-p-permission-fixture");
 ck(!cnd("reload-modules",NULL),"mmap-module-p-permission-accepted");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&mm->mapped,"mmap-module-grant-effective");
 ck(!textwrite(path,"12001 rpp /mmap-grants/code\n"),"mmap-module-invalid-permission-fixture");
 ck(cnd("reload-modules",NULL)==2,"mmap-module-duplicate-p-rejected");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&mm->mapped,"mmap-invalid-module-retains-old-table");
 ck(!unlink(path)&&!rmdir("/data/adb/modules/cairn-mmap-vm"),"mmap-smoke-module-removed");
 ck(!cnd("reload-modules",NULL),"mmap-module-removal-reload");
}
static void mmap_tests(const struct car_table *restore){
 mm=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);ck(mm!=MAP_FAILED,"mmap-probe-results");
 mkdir("/mmap-grants",0755);mkdir("/mmap-grants-other",0755);mkdir("/mmap-noexec",0755);
 label("/mmap-grants");label("/mmap-grants-other");
 ck(!mount("tmpfs","/mmap-noexec","tmpfs",MS_NOEXEC,"mode=0755"),"mmap-noexec-mount");label("/mmap-noexec");
 mm_fixture("/mmap-grants/code");mm_fixture("/mmap-grants-other/code");mm_fixture("/mmap-noexec/code");
 mm_module_parser_test();
 struct car_table t;mm_table(&t,CAR_READ|CAR_META);
 int native_execmem=mm_anonymous_probe();
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&!mm->mapped&&mm_denied(mm->map_error),"read-grant-does-not-imply-map");
 mm_table(&t,CAR_READ|CAR_MAP|CAR_META);
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&mm->mapped&&mm->content,"mmap-read-map-grant");
 ck(!chmod("/mmap-grants/code",0000),"mmap-dac-denied-mode");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&mm->mapped&&mm->content,"mmap-DAC-and-SELinux-grant");
 ck(!chmod("/mmap-grants/code",0666),"mmap-restore-fixture-mode");
 mm_probe(12002,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&!mm->mapped&&mm_denied(mm->map_error),"mmap-other-uid-no-map-grant");
 mm_probe(12001,"/mmap-grants-other/code",O_RDONLY,PROT_READ,MAP_PRIVATE,-1,0);ck(mm->opened&&!mm->mapped&&mm_denied(mm->map_error),"mmap-path-prefix-boundary");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ|PROT_WRITE,MAP_PRIVATE,-1,1);ck(mm->mapped&&mm->content,"private-cow-write-needs-no-file-write-grant");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ|PROT_EXEC,MAP_PRIVATE,-1,0);ck(mm->opened&&!mm->mapped&&mm_denied(mm->map_error),"mmap-execute-grant-required");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,PROT_READ|PROT_EXEC,0);ck(mm->mapped&&!mm->protect&&mm_denied(mm->protect_error),"mprotect-execute-grant-required");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,PROT_READ|PROT_WRITE,0);ck(mm->mapped&&mm->protect,"mprotect-private-cow-native-semantics");
 mm_table(&t,CAR_READ|CAR_WRITE|CAR_MAP|CAR_EXEC|CAR_META);
 ck(mm_anonymous_probe()==native_execmem,"file-grants-do-not-change-anonymous-execmem");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ|PROT_EXEC,MAP_PRIVATE,-1,0);ck(mm->mapped&&mm->executed==42,"mmap-granted-executable-code-runs");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ,MAP_PRIVATE,PROT_READ|PROT_EXEC,0);ck(mm->mapped&&mm->protect&&mm->executed==42,"mprotect-granted-executable-code-runs");
 mm_probe(12001,"/mmap-grants/code",O_RDWR,PROT_READ|PROT_WRITE,MAP_SHARED,-1,0);ck(mm->mapped&&mm->content,"mmap-shared-write-granted");
 mm_probe(12001,"/mmap-grants/code",O_RDWR,PROT_READ,MAP_SHARED,PROT_READ|PROT_WRITE,0);ck(mm->mapped&&mm->protect,"mprotect-shared-write-granted");
 mm_probe(12001,"/mmap-grants/code",O_RDONLY,PROT_READ|PROT_WRITE,MAP_PRIVATE,PROT_READ|PROT_EXEC,1);ck(mm->mapped&&!mm->protect&&mm_denied(mm->protect_error),"execmod-original-check-not-bypassed");
 mm_probe(12001,"/mmap-noexec/code",O_RDONLY,PROT_READ|PROT_EXEC,MAP_PRIVATE,-1,0);ck(mm->opened&&!mm->mapped&&mm_denied(mm->map_error),"mmap-noexec-mount-not-bypassed");
 mm_probe(12001,"/mmap-noexec/code",O_RDONLY,PROT_READ,MAP_PRIVATE,PROT_READ|PROT_EXEC,0);ck(mm->mapped&&!mm->protect&&mm_denied(mm->protect_error),"mprotect-noexec-mount-not-bypassed");
 mm_revoke();ck(!op(6,(void *)restore),"mmap-restore-unrelated-file-regression-rules");
 ck(!umount("/mmap-noexec"),"mmap-noexec-unmount");munmap(mm,4096);
 puts("MMAP_AUTHORIZATION_VM_PASS file_map=1 file_exec=1 shared_write=1 private_cow=1 mprotect=1 identity_preserved=1 noexec_preserved=1 execmod_preserved=1 new_map_revoke=1 existing_vma_revoke=0 execmem_unchanged=1 DAC=1");
}
