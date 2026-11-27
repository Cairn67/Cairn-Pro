// Minimal JNI bindings between the manager app and the native privilege layer.
#include <stdint.h>

typedef int32_t jint;
typedef int64_t jlong;
typedef void *jobject;
typedef void *jstring;

typedef const struct JNINativeInterface_ *JNIEnv;
typedef const char *(*fn_get_utf)(JNIEnv *, jstring, unsigned char *);
typedef void (*fn_rel_utf)(JNIEnv *, jstring, const char *);

extern long cairn_authenticate(uint64_t k0, uint64_t k1);
extern long cairn_probe(void);
extern long cairn_getuid(void);
extern long cairn_spawn_root(uint64_t k0, uint64_t k1,
                             const char *cmd_path, const char *out_path,
                             const char *core_path);

jlong Java_com_cairn_manager_Native_auth(JNIEnv *env, jobject thiz,
                                         jlong k0, jlong k1)
{
    (void)env; (void)thiz;
    return (jlong)cairn_authenticate((uint64_t)k0, (uint64_t)k1);
}

jlong Java_com_cairn_manager_Native_probe(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return (jlong)cairn_probe();
}

jlong Java_com_cairn_manager_Native_getuid(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return (jlong)cairn_getuid();
}

extern const char *cairn_cmd_path;
extern const char *cairn_out_path;

jlong Java_com_cairn_manager_Native_spawnRoot(JNIEnv *env, jobject thiz,
                                              jlong k0, jlong k1,
                                              jstring jcmd, jstring jout)
{
    (void)thiz;
    if (!jcmd || !jout) return -3;
    const void **ftab = (const void **)(*env);
    fn_get_utf get_utf = (fn_get_utf)ftab[169];
    fn_rel_utf rel_utf = (fn_rel_utf)ftab[170];
    const char *cp = get_utf(env, jcmd, 0);
    const char *op = get_utf(env, jout, 0);
    if (!cp || !op) {
        if (cp) rel_utf(env, jcmd, cp);
        if (op) rel_utf(env, jout, op);
        return -3;
    }
    long ret = cairn_spawn_root((uint64_t)k0, (uint64_t)k1, cp, op, 0);
    rel_utf(env, jcmd, cp);
    rel_utf(env, jout, op);
    return (jlong)ret;
}

jlong Java_com_cairn_manager_Native_spawnRootCore(JNIEnv *env, jobject thiz,
                                                  jlong k0, jlong k1,
                                                  jstring jcmd, jstring jout,
                                                  jstring jcore)
{
    (void)thiz;
    if (!jcmd || !jout) return -3;
    const void **ftab = (const void **)(*env);
    fn_get_utf get_utf = (fn_get_utf)ftab[169];
    fn_rel_utf rel_utf = (fn_rel_utf)ftab[170];
    const char *cp = get_utf(env, jcmd, 0);
    const char *op = get_utf(env, jout, 0);
    if (!cp || !op) {
        if (cp) rel_utf(env, jcmd, cp);
        if (op) rel_utf(env, jout, op);
        return -3;
    }
    const char *cr = jcore ? get_utf(env, jcore, 0) : 0;
    long ret = cairn_spawn_root((uint64_t)k0, (uint64_t)k1, cp, op, cr);
    if (cr) rel_utf(env, jcore, cr);
    rel_utf(env, jcmd, cp);
    rel_utf(env, jout, op);
    return (jlong)ret;
}
