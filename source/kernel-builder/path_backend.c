/* SPDX-License-Identifier: GPL-2.0-only
 * Freestanding policy backend for the device adapter. Kernel synchronization,
 * allocation, copy and d_path functions are supplied by the verified adapter.
 * This source does not install hooks or advertise a live capability by itself.
 */
// Backend for path visibility decisions.
#include "../visibility/path_rules.h"
#define CP_EPERM (-1L)
#define CP_EFAULT (-14L)
#define CP_EINVAL (-22L)
#define CP_ENOMEM (-12L)
#define CP_ENOENT (-2L)
#include "path_runtime.h"
#ifdef CP_KERNEL_TRACE
extern int cp_kernel_log(const char *,...);
#define CP_LOG(...) cp_kernel_log(__VA_ARGS__)
#else
#define CP_LOG(...) ((void)0)
#endif

long cp_apply(struct cp_runtime *runtime,unsigned int uid,unsigned int euid,
              int authenticated,const void *user,unsigned long bytes){
 CP_LOG("\0016CairnPath apply uid=%u euid=%u bytes=%lu auth=%d\n",uid,euid,bytes,authenticated);
 if(uid || euid || !authenticated)return CP_EPERM;
 if(!runtime || !user || bytes!=sizeof(struct cpr_table))return CP_EINVAL;
 struct cpr_table *next=runtime->allocate(sizeof(*next));
 if(!next)return CP_ENOMEM;
 if(runtime->copy_user(next,user,sizeof(*next))){runtime->release(next);return CP_EFAULT;}
 if(cpr_validate(next)){runtime->release(next);return CP_EINVAL;}
 CP_LOG("\0016CairnPath validated count=%u\n",next->count);
#ifdef CP_KERNEL_TRACE
 for(unsigned int i=0;i<next->count;i++)CP_LOG("\0016CairnPath rule index=%u uid=%u flags=%u path=%s\n",i,next->rules[i].uid,next->rules[i].flags,next->rules[i].path);
#endif
 unsigned long flags=runtime->lock(runtime->lock_state);
#ifdef CP_KERNEL_TRACE
 runtime->trace_remaining=128;runtime->generation++;
 unsigned int generation=runtime->generation;
#endif
 struct cpr_table *old=runtime->active;runtime->active=next;
 runtime->unlock(runtime->lock_state,flags);
 CP_LOG("\0016CairnPath replaced generation=%u trace_budget=128\n",generation);
 if(old)runtime->release(old);
 return 0;
}
int cp_match(struct cp_runtime *runtime,unsigned int uid,const char *resolved,unsigned int length){
 if(uid<10000)return 0;
 unsigned long flags=runtime->lock(runtime->lock_state);
 int hidden=runtime->active ? cpr_hidden(runtime->active,uid,resolved,length):0;
#ifdef CP_KERNEL_TRACE
 int targeted=0;unsigned int generation=runtime->generation;
 if(runtime->active)for(unsigned int i=0;i<runtime->active->count;i++)if(runtime->active->rules[i].uid==uid){targeted=1;break;}
 int trace=targeted && runtime->trace_remaining;
 if(trace)runtime->trace_remaining--;
 unsigned int remaining=runtime->trace_remaining;
#endif
 runtime->unlock(runtime->lock_state,flags);
#ifdef CP_KERNEL_TRACE
 if(trace)CP_LOG("\0016CairnPath check generation=%u uid=%u hidden=%d remaining=%u path=%.*s\n",generation,uid,hidden,remaining,(int)length,resolved);
#endif
 return hidden;
}

long cp_check_path(struct cp_runtime *runtime,unsigned int uid,const void *path,
                    char *buffer,unsigned int capacity){
 if(uid<10000)return 0;
 if(!runtime || !path || !buffer || capacity<2 || capacity>4096)return CP_EINVAL;
 char *resolved=runtime->resolve(path,buffer,(int)capacity);
 if((unsigned long)resolved>=(unsigned long)-4095)return (long)resolved;
 
 unsigned long offset=(unsigned long)resolved-(unsigned long)buffer;
 if(offset>=capacity)return CP_EINVAL;
 unsigned int length=0;while(offset+length<capacity && resolved[length])length++;
 if(offset+length>=capacity)return CP_EINVAL;
 return cp_match(runtime,uid,resolved,length)?CP_ENOENT:0;
}

long cp_check_allocated(struct cp_runtime *runtime,unsigned int uid,const void *path){
 if(uid<10000)return 0;
 if(!runtime || !path)return CP_EINVAL;
 char *buffer=runtime->allocate(4096);
 if(!buffer)return CP_ENOMEM;
 long result=cp_check_path(runtime,uid,path,buffer,4096);
 runtime->release(buffer);
 return result;
}

long cp_check_entry(struct cp_runtime *runtime,unsigned int uid,const void *directory,
                    const char *name,unsigned int name_length,char *buffer,unsigned int capacity){
 if(uid<10000)return 0;
 if(!runtime || !directory || !name || !name_length || !buffer || capacity<3 || capacity>4096)return CP_EINVAL;
 if((name_length==1 && name[0]=='.') || (name_length==2 && name[0]=='.' && name[1]=='.'))return 0;
 for(unsigned int i=0;i<name_length;i++)if(!name[i] || name[i]=='/')return CP_EINVAL;
 char *resolved=runtime->resolve(directory,buffer,(int)capacity);
 if((unsigned long)resolved>=(unsigned long)-4095)return (long)resolved;
 unsigned long offset=(unsigned long)resolved-(unsigned long)buffer;
 if(offset>=capacity)return CP_EINVAL;
 unsigned int length=0;while(offset+length<capacity && resolved[length])length++;
 if(offset+length>=capacity)return CP_EINVAL;
 unsigned int slash=length && resolved[length-1]!='/';
 if(name_length>=capacity || length+slash>=capacity-name_length)return -36L;
 
 for(unsigned int i=0;i<length;i++)buffer[i]=resolved[i];
 if(slash)buffer[length++]='/';
 for(unsigned int i=0;i<name_length;i++)buffer[length+i]=name[i];
 length+=name_length;buffer[length]=0;
 return cp_match(runtime,uid,buffer,length)?CP_ENOENT:0;
}
