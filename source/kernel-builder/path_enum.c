/* SPDX-License-Identifier: GPL-2.0-only
 * Device ABI verified in iterate_dir: actor +0, position +8; file.f_path +168.
 * This adapter retains the caller's original context (including getdents state).
 */
// Enumeration filtering for path visibility.
struct cp_runtime;
extern long cp_check_entry(struct cp_runtime *,unsigned int,const void *,const char *,unsigned int,char *,unsigned int);

#include "path_runtime.h"
#include "layout.h"
struct cp_dir_context {
 int (*actor)(struct cp_dir_context *,const char *,int,long,unsigned long,unsigned int);
 long position;
};
struct cp_filtered_context {
 struct cp_dir_context context;
 struct cp_dir_context *original;
 struct cp_runtime *runtime;
 const void *directory;
 char *buffer;
 unsigned int uid;
 int error;
};
extern int cp_enum_actor(struct cp_dir_context *,const char *,int,long,unsigned long,unsigned int);
extern int cp_original_iterate(void *,struct cp_dir_context *);
int cp_enum_actor_impl(struct cp_dir_context *context,const char *name,int length,
                       long offset,unsigned long inode,unsigned int type){
 struct cp_filtered_context *filtered=(struct cp_filtered_context *)context;
 if(length<=0){filtered->error=-22;return 0;}
 long result=cp_check_entry(filtered->runtime,filtered->uid,filtered->directory,
                            name,(unsigned int)length,filtered->buffer,4096);
 if(result==-2)return 1; 
 if(result){filtered->error=(int)result;return 0;}
 filtered->original->position=context->position;
 int more=filtered->original->actor(filtered->original,name,length,offset,inode,type);
 context->position=filtered->original->position;
 return more;
}
int cp_iterate_apply(struct cp_runtime *runtime,unsigned int uid,void *file,struct cp_dir_context *original){
 if(!runtime || !file || !original || !original->actor)return -22;
 char *buffer=runtime->allocate(4096);if(!buffer)return -12;
 struct cp_filtered_context filtered={{cp_enum_actor,original->position},original,runtime,
                                     (const char *)file+CA_FILE_F_PATH,buffer,uid,0};
 int result=cp_original_iterate(file,&filtered.context);
 original->position=filtered.context.position;
 runtime->release(buffer);
 return filtered.error && result==0 ? filtered.error:result;
}
