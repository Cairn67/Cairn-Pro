/* SPDX-License-Identifier: GPL-2.0-only
 * Proposed fixed-width path policy ABI. Not active until kernel ingestion and
 * VFS hooks are implemented. All fields are little-endian on supported arm64.
 * Separate from the root authorization list. Never dereference user memory here.
 */
// Rule structures for path visibility.
#ifndef CAIRN_PATH_RULES_H
#define CAIRN_PATH_RULES_H
#define CPR_VERSION 1U
#define CPR_MAX_RULES 32U
#define CPR_PATH_BYTES 256U
#define CPR_SUBTREE 1U
#define CPR_ALLOW 2U
struct cpr_rule {
    unsigned int uid;
    unsigned int flags;
    unsigned int length;
    unsigned int reserved;
    char path[CPR_PATH_BYTES];
};
struct cpr_table {
    unsigned int version;
    unsigned int bytes;
    unsigned int count;
    unsigned int reserved;
    struct cpr_rule rules[CPR_MAX_RULES];
};
_Static_assert(sizeof(unsigned int)==4,"32-bit ABI required");
_Static_assert(sizeof(struct cpr_rule)==272,"rule ABI mismatch");
_Static_assert(sizeof(struct cpr_table)==8720,"table ABI mismatch");

static int cpr_path_valid(const char *p,unsigned int n) {
    unsigned int i,start=1;
    if(n<2 || n>=CPR_PATH_BYTES || p[0]!='/' || p[n]!=0 || p[n-1]=='/')return 0;
    for(i=1;i<=n;i++) {
        if(i<n && ((unsigned char)p[i]<32 || (unsigned char)p[i]==127))return 0;
        if(i==n || p[i]=='/') {
            unsigned int len=i-start;
            if(!len || (len==1 && p[start]=='.') ||
               (len==2 && p[start]=='.' && p[start+1]=='.'))return 0;
            start=i+1;
        }
    }
    return 1;
}
static int cpr_validate(const struct cpr_table *t) {
    unsigned int i,j,k;
    if(t->version!=CPR_VERSION || t->bytes!=sizeof(*t) ||
       t->count>CPR_MAX_RULES || t->reserved)return -1;
    for(i=0;i<t->count;i++) {
        const struct cpr_rule *r=&t->rules[i];
        if(r->uid<10000 || r->reserved || (r->flags&~(CPR_SUBTREE|CPR_ALLOW)) ||
           !cpr_path_valid(r->path,r->length))return -1;
        for(k=r->length+1;k<CPR_PATH_BYTES;k++)if(r->path[k])return -1;
        for(j=0;j<i;j++) {
            const struct cpr_rule *q=&t->rules[j];
            if(q->uid!=r->uid || q->length!=r->length ||
               ((q->flags^r->flags)&CPR_SUBTREE))continue;
            for(k=0;k<r->length && q->path[k]==r->path[k];k++){}
            if(k==r->length)return -1; 
        }
    }
    
    for(i=t->count;i<CPR_MAX_RULES;i++) {
        const unsigned char *p=(const unsigned char *)&t->rules[i];
        for(k=0;k<sizeof(struct cpr_rule);k++)if(p[k])return -1;
    }
    return 0;
}

static int cpr_hidden(const struct cpr_table *t,unsigned int uid,
                      const char *path,unsigned int length) {
    unsigned int i,j,best=0;int hidden=0;
    if(uid<10000)return 0;
    for(i=0;i<t->count;i++) {
        const struct cpr_rule *r=&t->rules[i];
        unsigned int rank;
        if(uid!=r->uid || length<r->length)continue;
        for(j=0;j<r->length && path[j]==r->path[j];j++){}
        if(j!=r->length)continue;
        if(length!=r->length && (!(r->flags&CPR_SUBTREE) || path[r->length]!='/'))continue;
        rank=r->length*2+((r->flags&CPR_SUBTREE)?0:1);
        if(rank>best){best=rank;hidden=!(r->flags&CPR_ALLOW);}
    }
    return hidden;
}
#endif
