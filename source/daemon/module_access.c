/* SPDX-License-Identifier: GPL-2.0-only
 * Declarative module access grants. No policy mutation and no shell evaluation.
 */
// Access helpers for module files.
struct ca_stat {u64 dev,ino;u32 mode,nlink,uid,gid;u64 rdev,pad; i64 size;u32 blksize,pad2;u64 blocks; i64 atime;u64 ansec;i64 mtime;u64 mnsec;i64 ctime;u64 cnsec;u32 unused[2];};
static int ca_trusted(int fd,unsigned int type){
 struct ca_stat st; if(sc2(SYS_fstat,fd,(i64)&st)<0)return 0;
 return st.uid==0 && !(st.mode&0022) && (st.mode&0170000)==type;
}
static int ca_boot_key(int provision){
 if(sc0(SYS_getuid)||sc0(SYS_geteuid))return -1;
 if(ca_store_open()){ca_store_close();return -1;}
 int rc=-1;
 if(provision){
  if(!g_wl_key_valid || pv_prctl(CA_OP_CAPABILITIES,0)!=CA_CAP_ACTIVE_V1)goto out;
  sc3(SYS_unlinkat,ca_directory,(i64)"boot-key.tmp",0);
  i64 fd=sc4(SYS_openat,ca_directory,(i64)"boot-key.tmp",O_WRONLY|O_CREAT|0x80|O_CLOEXEC|0x20000,0600);
  if(fd<0)goto out;
  rc=sc3(SYS_write,fd,(i64)g_wl_key,16)==16 && sc1(82,fd)==0?0:-1;sc1(SYS_close,fd);
  if(!rc)rc=sc4(SYS_renameat,ca_directory,(i64)"boot-key.tmp",ca_directory,(i64)"boot-key.bin")<0?-1:0;
  if(!rc)rc=sc1(82,ca_directory)<0?-1:0;
 }else{
  i64 fd=sc4(SYS_openat,ca_directory,(i64)"boot-key.bin",O_RDONLY|O_CLOEXEC|0x20000|0x800,0);
  if(fd<0)goto out;
  struct ca_stat st;
  if(ca_trusted(fd,0100000) && sc2(SYS_fstat,fd,(i64)&st)==0 && !(st.mode&0077) && st.size==16 && sc3(SYS_read,fd,(i64)g_wl_key,16)==16){g_wl_key_valid=1;rc=0;}
  sc1(SYS_close,fd);
 }
 out:ca_store_close();return rc;
}

static int ca_parse_fd(int fd,const char *id){
 char line[1024];unsigned int used=0,number=1,total=0;int rc=0;struct cap_file format={0};
 for(;;){char c;long n=sc3(SYS_read,fd,(i64)&c,1);
  if(n==-4)continue;if(n<0){rc=-1;break;}
  if(n&&++total>32768){rc=-1;break;}
  if(!n||c=='\n'){
   line[used]=0;
   if(cap_line(&ca_bundle,&format,line)){rc=-1;break;}
   used=0;number++;if(!n)break;
  }else{if(!c||used>=sizeof(line)-1){rc=-1;break;}line[used++]=c;}
 }
 if(rc){loga_str("[access] invalid rules:",id);loga_num("[access] line=",number);loga("\n");}
 return rc;
}
static int ca_module_file(int dir,const char *id){
 i64 fd=sc4(SYS_openat,dir,(i64)"cairn.access",O_RDONLY|O_CLOEXEC|0x20000|0x800,0);
 if(fd==-2)return 0;if(fd<0)return -1;
 if(!ca_trusted(fd,0100000)){sc1(SYS_close,fd);return -1;}
 int rc=ca_parse_fd(fd,id);sc1(SYS_close,fd);return rc;
}
static int ca_load_text(const char *path){
 i64 fd=sc4(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY|O_CLOEXEC|0x20000|0x800,0);
 if(fd<0)return -1;
 if(!ca_trusted(fd,0100000)){sc1(SYS_close,fd);return -1;}
 int rc=ca_parse_fd(fd,"manual");sc1(SYS_close,fd);return rc||cav_validate(&ca_bundle)?-1:0;
}
static int ca_marker(int dir,const char *name){
 
 long f=sc4(SYS_openat,dir,(i64)name,0x200000|0x20000|O_CLOEXEC,0);
 if(f>=0){sc1(SYS_close,f);return 1;}return f==-2?0:1;
}
static int ca_reload_modules(void){
 if(sc0(SYS_getuid)||sc0(SYS_geteuid)||!g_wl_key_valid)return ca_output("ACCESS_ERROR=root-and-auth-required\n",3);
 if(ca_store_open()){ca_store_close();return 7;}
 int rc=2;
 ca_reset();
 long saved=sc4(SYS_openat,ca_directory,(i64)"rules.bin",0x200000|0x20000|O_CLOEXEC,0);
 if(saved>=0){sc1(SYS_close,saved);if(ca_load_saved())goto out;}else if(saved!=-2)goto out;
 long root=sc4(SYS_openat,AT_FDCWD,(i64)CAIRN_MODULES_DIR,O_RDONLY|O_DIRECTORY|O_CLOEXEC|0x20000,0);
 if(root<0){if(root==-2){rc=ca_apply_saved(0);}goto out;}
 if(!ca_trusted(root,0040000)){sc1(SYS_close,root);goto out;}
 union{struct dirent64_ d;char raw[512];}buf;long n;
 while((n=sc3(SYS_getdents64,root,(i64)buf.raw,sizeof(buf.raw)))>0){
  for(long off=0;off<n;){struct dirent64_ *de=(void *)(buf.raw+off);if(!de->d_reclen||off+de->d_reclen>n){sc1(SYS_close,root);goto out;}off+=de->d_reclen;
   const char *id=de->d_name;if(id[0]=='.'||!mod_id(id)||mod_pending(id))continue;
   long dir=sc4(SYS_openat,root,(i64)id,O_RDONLY|O_DIRECTORY|O_CLOEXEC|0x20000,0);
   if(dir<0){sc1(SYS_close,root);goto out;}
   int skip=ca_marker(dir,"disable")||ca_marker(dir,"remove");
   int bad=!ca_trusted(dir,0040000)||(!skip&&ca_module_file(dir,id));sc1(SYS_close,dir);
   if(bad){sc1(SYS_close,root);goto out;}
  }
 }
 sc1(SYS_close,root);if(n<0)goto out;
 rc=ca_apply_saved(0);
 out:ca_store_close();if(rc)ca_output("ACCESS_ERROR=module-reload-rejected\n",rc);return rc;
}
static int ca_stage_access(void){
 if(!g_wl_key_valid&&ca_boot_key(0)){loga("[access] boot credential unavailable; module grants not loaded\n");return 1;}
 int rc=ca_reload_modules();loga_num("[access] module reload exit=",rc);loga("\n");return rc;
}
