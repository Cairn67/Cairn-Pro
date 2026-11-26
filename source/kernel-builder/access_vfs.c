// SPDX-License-Identifier: GPL-2.0-only
// VFS-level access decision path.
#include "access_runtime.h"
#include "layout.h"
extern struct ca_runtime *ca_runtime_peek(void);
static const void *ca_ptr(const void *p,unsigned int n){return *(const void *const *)((const char *)p+n);}
static unsigned int ca_u32(const void *p,unsigned int n){return *(const unsigned int *)((const char *)p+n);}
static struct ca_runtime *ca_subject(const void *task,const void **cred){
 if(!task)return (void *)0;
 *cred=ca_ptr(task,CA_TASK_CRED);if(!*cred)return (void *)0;
 unsigned int uid=ca_u32(*cred,CA_CRED_UID);
 if(!uid || uid!=ca_u32(*cred,CA_CRED_EUID) || uid!=ca_u32(*cred,CA_CRED_FSUID))return (void *)0;
 struct ca_runtime *r=ca_runtime_peek();return r && ca_targeted(r,uid)?r:(void *)0;
}
static int ca_resolved(struct ca_runtime *r,const void *cred,const void *path,unsigned int ops){
 if(!path || !ops || !r->resolve)return 0;
 char buffer[CAR_PATH];char *p=r->resolve(path,buffer,sizeof(buffer));
 unsigned long off=(unsigned long)p-(unsigned long)buffer;
 if(off>=sizeof(buffer))return 0;
 unsigned int n=0;while(off+n<sizeof(buffer) && p[n])n++;
 if(off+n>=sizeof(buffer))return 0;
 return ca_check(r,ca_u32(cred,CA_CRED_UID),ca_u32(cred,CA_CRED_EUID),ca_u32(cred,CA_CRED_FSUID),p,n,ops);
}
int ca_inode_allowed(const void *inode,unsigned int mask,const void *task){
 const void *cred;struct ca_runtime *r=ca_subject(task,&cred);
 if(!r || !inode || !(mask&7) || (mask&~0xbfU))return 0;
 
 if(mask&0x80)return -10;
 const void *nd=ca_ptr(task,CA_TASK_NAMEIDATA);if(!nd || (ca_u32(nd,CA_NAMEI_FLAGS)&0x40))return 0;
 const void *dentry=ca_ptr(nd,CA_PATH_DENTRY);if(!dentry || ca_ptr(dentry,CA_DENTRY_INODE)!=inode)return 0;
 unsigned int type=*(const unsigned short *)inode&0xf000U;
 if(type!=0x4000U && type!=0x8000U)return 0;
 unsigned int ops=0;
 if(mask&4)ops|=CAR_READ;
 if(mask&2)ops|=CAR_WRITE;
 if(mask&1)ops|=type==0x4000U?CAR_SEARCH:CAR_EXEC;
 if(mask&8)ops|=CAR_WRITE;
 return ca_resolved(r,cred,nd,ops);
}
int ca_path_allowed(const void *path,unsigned int ops,const void *task){
 const void *cred;struct ca_runtime *r=ca_subject(task,&cred);
 return r?ca_resolved(r,cred,path,ops):0;
}
static int ca_supported_file(const void *file){
 if(!file)return 0;
 const void *inode=ca_ptr(file,CA_FILE_F_INODE);if(!inode)return 0;
 unsigned int type=*(const unsigned short *)inode&0xf000U;
 return type==0x8000U || type==0x4000U;
}
int ca_file_allowed(const void *file,const void *task){
 if(!ca_supported_file(file))return 0;
 unsigned int mode=ca_u32(file,CA_FILE_F_MODE),ops=0;
 if(mode&1)ops|=CAR_READ;
 if(mode&2)ops|=CAR_WRITE;
 if(mode&32)ops|=CAR_EXEC;
 return ops?ca_path_allowed((const char *)file+CA_FILE_F_PATH,ops,task):0;
}

int ca_file_data_allowed(const void *file,unsigned int access,const void *task,int mapping){
 if(!ca_supported_file(file) || !access || (access&~(mapping?0x4006U:0x206U)))return 0;
 
 if(mapping && (*(const unsigned short *)ca_ptr(file,CA_FILE_F_INODE)&0xf000U)!=0x8000U)return 0;
 unsigned int ops=mapping?CAR_MAP:0;
 if(access&0x4000U)ops|=CAR_EXEC;
 if(access&2)ops|=CAR_READ;
 if(access&0x204U)ops|=CAR_WRITE;
 return ca_path_allowed((const char *)file+CA_FILE_F_PATH,ops,task);
}

int ca_file_map_allowed(const void *file,const void *task){
 if(!ca_supported_file(file) || (*(const unsigned short *)ca_ptr(file,CA_FILE_F_INODE)&0xf000U)!=0x8000U)return 0;
 return ca_path_allowed((const char *)file+CA_FILE_F_PATH,CAR_MAP|CAR_READ,task);
}
