// Main daemon: privileged operations, allowlist, and module lifecycle.
#include "cairn_proto.h"
#include "cairn_sys.h"

#define MAX_WORKERS         32
#define READ_TIMEOUT_MS     5000
#define CONNECT_BACKLOG     16

static u32 g_manager_uid = 0;
static u64 g_wl_key[2];
static int g_wl_key_valid;
static int key_hex(char c) {
    if (c >= '0' && c <= '9') return c-'0';
    if (c >= 'a' && c <= 'f') return c-'a'+10;
    if (c >= 'A' && c <= 'F') return c-'A'+10;
    return -1;
}
static void load_wl_key(char **envp) {
    if (!envp) return;
    for (; *envp; envp++) {
        char *s = *envp;
        const char *prefix = "CAIRN_KEY=";
        int j=0;
        while (prefix[j] && s[j] == prefix[j]) j++;
        if (prefix[j]) continue;
        s += j;
        if (slen(s) != 32) return;
        g_wl_key[0]=g_wl_key[1]=0;
        for (int i=0; i<16; i++) {
            int a=key_hex(s[i*2]), b=key_hex(s[i*2+1]);
            if (a<0 || b<0) return;
            g_wl_key[i/8] |= (u64)((a<<4)|b) << ((i&7)*8);
        }
        g_wl_key_valid=1;
        for (int i=0; i<32; i++) s[i]='0';
        return;
    }
}

static void ensure_dir(const char *path)
{
    sc3(SYS_mkdirat, AT_FDCWD, (i64)path, 0700);
}

static int exists(const char *path)
{
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)path, O_RDONLY, 0);
    if (fd < 0) return 0;
    sc1(SYS_close, fd);
    return 1;
}

#include "allowlist.h"
#include "modules.c"
static int ca_stage_access(void);
#include "stage.c"

static int module_lifecycle(long argc,char **argv) {
    if(argc!=4)return 2;
    if(sc0(SYS_geteuid)!=0)return 1;
    const char *op=argv[2],*id=argv[3];
    if(!mod_id(id)||id[0]=='.')return 2;
    char path[256];mod_path(path,id,"/module.prop");if(!mod_exists(path))return 4;
    char update[256];char *u=stpcpy_(update,"/data/adb/modules_update/");stpcpy_(u,id);
    if(mod_exists(update)||mod_pending(id))return 3;
    mod_path(path,id,"/.cairn_operation");
    long lock=sc4(SYS_openat,AT_FDCWD,(i64)path,O_WRONLY|O_CREAT,0600);
    if(lock<0)return 5;
    if(sc2(32,lock,2|4)<0){sc1(SYS_close,lock);return 3;}
    int rc=0;
    if(str_eq(op,"action")){
        mod_path(path,id,"/disable");if(mod_exists(path)){rc=3;goto done;}
        mod_path(path,id,"/remove");if(mod_exists(path)){rc=3;goto done;}
    }
    mod_path(path,id,str_eq(op,"action")?"/action.sh":"/uninstall.sh");
    if(mod_exists(path))rc=stage_exec_script(path);
    else if(str_eq(op,"action"))rc=4;
    
    if(rc==0 && str_eq(op,"uninstall")){
        mod_path(path,id,"");if(mod_delete(path,0)<0)rc=5;
    }
done:
    sc1(SYS_close,lock);return rc;
}

#define CAIRN_WL_MAX 128
static u32 g_wl_buf[1 + CAIRN_WL_MAX * 4];

static i64 prctl_wl_write(u32 count)
{
    if (!g_wl_key_valid) return -1;
    register long x8 __asm__("x8") = SYS_prctl;
    register long x0 __asm__("x0") = 0x43414952L;   
    register long x1 __asm__("x1") = (long)g_wl_key[0];
    register long x2 __asm__("x2") = (long)g_wl_key[1];
    register long x3 __asm__("x3") = 2;             
    register long x4 __asm__("x4") = (long)g_wl_buf;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    (void)count;
    return x0;
}

static i64 wl_sync(void)
{
    if (al_lock()<0) return -1;
    u32 n=0;
    if (al_load()<0) {
        al_count=0;
        loga("[wl] invalid allowlist: syncing empty table\n");
    }
    for(u32 i=0;i<al_count;i++) {
        u32 *e=g_wl_buf+1+n*4;
        e[0]=al_uid[i];e[1]=0;e[2]=1;e[3]=0;n++;
    }
    g_wl_buf[0] = n;
    i64 r = prctl_wl_write(n);
    loga("[wl] sync count=");
    loga_num("", n);
    loga(" rc=");
    loga_num("", (u32)r);
    loga("\n");
    al_unlock();
    return r;
}

static int load_manager_uid(void)
{
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)CAIRN_MGR_UID_FILE, O_RDONLY, 0);
    if (fd < 0) return -1;
    char buf[32];
    i64 n = sc3(SYS_read, fd, (i64)buf, (i64)sizeof(buf) - 1);
    sc1(SYS_close, fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    g_manager_uid = atou(buf);
    return g_manager_uid ? 0 : -1;
}

static char g_adm_out[4096];

static char *adm_put(char *p, const char *s)
{
    return stpcpy_(p, s);
}

static char *adm_put_num(char *p, u32 v)
{
    char b[12];
    utoa(v, b);
    return stpcpy_(p, b);
}

static void log_proc_line(const char *tag, const char *fmt_pid)
{
    char path[64];
    char *p = path;
    p = stpcpy_(p, fmt_pid);
    p = stpcpy_(p, "/cmdline");
    *p = 0;
    i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)path, O_RDONLY, 0);
    if (fd < 0) { loga_str(tag, "(unreadable)"); return; }
    char buf[128];
    i64 r = sc3(SYS_read, fd, (i64)buf, sizeof(buf) - 1);
    sc1(SYS_close, fd);
    if (r <= 0) { loga_str(tag, "(empty)"); return; }
    buf[r] = 0;
    
    for (i64 i = 0; i < r; i++) if (buf[i] == 0) buf[i] = ' ';
    buf[r] = 0;
    loga_str(tag, buf);
}

static void log_startup_source(void)
{
    char b1[12], b2[12], line[160];
    u32 ppid = (u32)sc0(SYS_getppid);

    log_proc_line("[init] self-cmdline: ", "/proc/self");
    
    utoa(ppid, b1);
    char pb[32];
    char *q = pb; q = stpcpy_(q, "/proc/"); q = stpcpy_(q, b1); *q = 0;
    log_proc_line("[init] parent-cmdline: ", pb);

    char *pp = line;
    pp = stpcpy_(pp, "[init] parent pid=");
    utoa(ppid, b2); pp = stpcpy_(pp, b2);
    stpcpy_(pp, "\n");
    loga(line);
}

static int str_eq(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

static int base_is(const char *path, const char *name)
{
    if (!path || !path[0]) return 0;
    const char *b = path;
    const char *p = path;
    for (; *p; p++) if (*p == '/') b = p + 1;
    return str_eq(b, name);
}

static void selftest_run(char **envp)
{
    char buf[512];
    char *p = buf;
    p = stpcpy_(p, "SELFTEST=ok\n");
    p = stpcpy_(p, "uid=");
    utoa((u32)sc0(SYS_getuid), p);
    while (*p) p++;
    p = stpcpy_(p, "\nBASE=");
    p = stpcpy_(p, CAIRN_BASE);
    p = stpcpy_(p, "\nMODE=oneshot\n");
    *p = 0;

    sc3(SYS_write, 1, (i64)buf, slen(buf));

    if (envp) {
        const char *want = 0;
        for (int i = 0; envp[i]; i++) {
            const char *e = envp[i];
            const char *k = "CAIRN_SELFTEST_OUT=";
            int j = 0;
            while (k[j] && e[j] == k[j]) j++;
            if (!k[j]) { want = e + j; break; }
        }
        if (want) {
            i64 fd = sc4(SYS_openat, AT_FDCWD, (i64)want,
                         O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (fd >= 0) {
                sc3(SYS_write, fd, (i64)buf, slen(buf));
                sc1(SYS_close, fd);
            }
        }
    }
}

#define SYS_flock 32
#define LOCK_EX   2
#define LOCK_NB   4

#define SIGCHLD_NUM 17

#include "path_visibility.c"
#include "access_policy.c"

void cairnd_main(long argc, char **argv, char **envp)
{
    load_wl_key(envp);
    
    if(argc==2 && argv && argv[1] && str_eq(argv[1],"--build-id")) {
        const char *id="cnd-1.0.1-access-v2-1\n";
        sc3(SYS_write,1,(i64)id,slen(id));sc1(SYS_exit,0);for(;;);
    }
    if(argc>=2 && argv && argv[1] && str_eq(argv[1],"access")) {
        int rc=ca_cmd(argc,argv);sc1(SYS_exit,rc);for(;;);
    }
    if(argc>=2 && argv && argv[1] && str_eq(argv[1],"path-hide")) {
        int rc=pv_cmd(argc,argv);sc1(SYS_exit,rc);for(;;);
    }
    
    if (argc == 2 && (str_eq(argv[1], "--version") || str_eq(argv[1], "--version-code") || str_eq(argv[1], "--capabilities"))) {
        const char *out = str_eq(argv[1], "--version") ? "cnd 1.0.1 (Cairn Pro CLI 2)\n" :
            (str_eq(argv[1], "--version-code") ? "122\n" :
             "cli_api=2\nmodule_api=1\nroot_provider=cairn\nmodule_format=zip-script-subset\n"
             "stages=post-fs-data,service,boot-completed\nmodule_commands=list,info,path,install,enable,disable,action,uninstall\n"
             "module_mount=false\nsepolicy_rules=false\nmodule_access_rules=cairn.access\nmodule_access_formats=1,2\nroot_uid0_avc=true\naction_script=true\nuninstall_script=true\nboot_completed_script=true\n");
        sc3(SYS_write,1,(i64)out,slen(out));sc1(SYS_exit,0);for(;;);
    }
    if(argc>=2 && argv && argv[1] && str_eq(argv[1], "--sync-wl")) {
        if(sc0(SYS_geteuid)!=0 || !g_wl_key_valid) {
            const char *m="WL_SYNC_ERROR=missing-auth\n";
            sc3(SYS_write,2,(i64)m,slen(m));sc1(SYS_exit,1);for(;;);
        }
        log_open();
        i64 rc=wl_sync();
        const char *m=rc==0?"WL_SYNC_OK\n":"WL_SYNC_ERROR=kernel-or-file\n";
        sc3(SYS_write,rc==0?1:2,(i64)m,slen(m));
        sc1(SYS_exit,rc==0?0:1);for(;;);
    }

    if(argc>=2 && str_eq(argv[1], "module")){
        int rc=(argc>=3 && (str_eq(argv[2],"action")||str_eq(argv[2],"uninstall"))) ? module_lifecycle(argc,argv):modules_cmd(argc,argv);
        sc1(SYS_exit,rc);for(;;);
    }
    
    if (argc >= 2 && argv && argv[1] && str_eq(argv[1], "--selftest")) {
        selftest_run(envp);
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    if (argc >= 2 && argv && argv[1] && str_eq(argv[1], "--ping")) {
        const char *m = "alive\n";
        sc3(SYS_write, 1, (i64)m, slen(m));
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    if (argc >= 1 && argv && base_is(argv[0], "su")) {
        
        static char *envp_root[] = {
            "HOME=/data/adb",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/system/bin/sh",
            "PATH=/data/adb/ksu/bin:/system/bin:/system/xbin:/vendor/bin",
            "TERM=xterm-256color",
            0
        };
        const char *SH = "/system/bin/sh";

        const char *a1 = (argc >= 2 && argv[1]) ? argv[1] : 0;

        if (a1 && (str_eq(a1, "--help") || str_eq(a1, "-h") ||
                   str_eq(a1, "help") || str_eq(a1, "--version") ||
                   str_eq(a1, "-v"))) {
            static const char h[] =
                "su (Cairn Pro)\n"
                "Usage: su [options] [command]\n"
                "  su                Start an interactive root shell\n"
                "  su -c <command>   Execute a command as root\n"
                "  su <uid> <command> Execute as the specified identity (MT convention)\n"
                "  su --help         Show this help\n";
            sc3(SYS_write, 1, (i64)h, slen(h));
            sc1(SYS_exit, 0);
            for (;;) ;
        }

        const char *cmd = 0;
        if (argc >= 3 && argv[2] && argv[2][0] && a1) {
            
            if (str_eq(a1, "-c") || str_eq(a1, "0") || str_eq(a1, "--") ||
                (a1[0] >= '0' && a1[0] <= '9'))
                cmd = argv[2];
        }

        if (cmd) {
            
            static char *nargv[4];
            nargv[0] = (char *)"sh";
            nargv[1] = (char *)"-c";
            nargv[2] = (char *)cmd;
            nargv[3] = 0;
            i64 r1 = sc3(SYS_execve, (i64)SH, (i64)nargv, (i64)envp_root);
            loga("\n");
        } else {
            
            static char *nargv[2];
            nargv[0] = (char *)"sh";
            nargv[1] = 0;
            i64 r2 = sc3(SYS_execve, (i64)SH, (i64)nargv, (i64)envp_root);
            loga("\n");
        }

        {
            const char *m = "[CAIRN-SU] cannot exec /system/bin/sh\n";
            sc3(SYS_write, 2, (i64)m, slen(m));
        }
        sc1(SYS_exit, 127);
        for (;;) ;
    }

    if (argc < 2) {
        
        {
            u32 raw_ppid = (u32)sc0(SYS_getppid);
            log_open();
            loga("\n=== cairnd boot sync ===\n");
            loga_num("[boot] ppid=", raw_ppid);
            loga_num(" uid=", (u32)sc0(SYS_getuid));
            loga_num(" pid=", (u32)sc0(SYS_getpid));
            loga("\n");
            loga("[boot] launch=");
            if (raw_ppid <= 1)
                loga("init (exec redirect of /system/bin/cnd)\n");
            else
                loga("manager-or-shell\n");
            log_startup_source();
        }

        ensure_dir(CAIRN_BASE);
        ensure_dir(CAIRN_BIN_DIR);

        wl_sync();
        if (load_manager_uid() < 0)
            loga("[init] manager.uid missing\n");
        else
            loga_num("[init] manager uid=", g_manager_uid);
        loga("[init] one-shot done, exiting\n");
        sc1(SYS_exit, 0);
        for (;;) ;
    }

    {
        const char *c1 = (argc >= 2 && argv[1]) ? argv[1] : 0;

        if (!c1 || str_eq(c1, "--help") || str_eq(c1, "-h") ||
            str_eq(c1, "help") || str_eq(c1, "?")) {
            const char *h =
                "cnd - Cairn Pro daemon\n"
                "\n"
                "Usage: cnd <command> [args]\n"
                "\n"
                "  cnd grant <uid>       Allow uid to obtain root\n"
                "  cnd revoke <uid>      Revoke that permission\n"
                "  cnd list              List authorized uids\n"
                "  cnd status            Print runtime status\n"
                "  cnd --version         Print Cairn Pro version\n"
                "  cnd --version-code    Print numeric version\n"
                "  cnd --capabilities    Print implemented capabilities\n"
                "  cnd module help       Module command syntax\n"
                "  cnd --selftest        Run self-test and exit\n"
                "  cnd --ping            Liveness probe, exit\n"
                "  cnd --stage <phase>   Run an init phase (post-fs-data|service|boot-completed)\n"
                "  cnd --boot            Boot one-shot: sync allowlist and exit\n"
                "  cnd --help            Show this help\n"
                "\n"
                "Note: this binary is also the su target redirected by the kernel;\n"
                "the identity is chosen by the basename of argv[0].\n";
            sc3(SYS_write, 1, (i64)h, slen(h));
            sc1(SYS_exit, 0);
            for (;;) ;
        }

        if (str_eq(c1, "--stage")) {
            const char *phase = (argc >= 3 && argv[2]) ? argv[2] : 0;
            int st = stage_parse(phase);
            if (!st) {
                const char *m = "cnd: unknown stage (use post-fs-data, service or boot-completed)\n";
                sc3(SYS_write, 2, (i64)m, slen(m));
                sc1(SYS_exit, 2);
                for (;;) ;
            }
            log_open();
            loga_str("[stage] requested:", phase);
            int stage_rc=stage_run(st);
            loga_str("[stage] done:", phase);
            sc1(SYS_exit, stage_rc);
            for (;;) ;
        }
        int is_admin = str_eq(c1, "grant") || str_eq(c1, "revoke") ||
                       str_eq(c1, "list") || str_eq(c1, "status");
        if (is_admin) {
            log_open();
            if (sc0(SYS_getuid) != 0) {
                const char *m = "cnd: permission denied (need root)\n";
                sc3(SYS_write, 2, (i64)m, slen(m));
                loga("[adm] denied non-root caller\n");
                sc1(SYS_exit, 1);
                for (;;) ;
            }
            ensure_dir(CAIRN_BASE);

            load_manager_uid();

            if (str_eq(c1, "grant") || str_eq(c1, "revoke")) {
                const char *a2 = (argc >= 3 && argv[2]) ? argv[2] : 0;
                u32 target = a2 ? atou(a2) : 0;
                if (target < 10000) {
                    const char *m = "cnd: bad uid (need >= 10000)\n";
                    sc3(SYS_write, 2, (i64)m, slen(m));
                    loga("[adm] bad target uid\n");
                    sc1(SYS_exit, 1);
                    for (;;) ;
                }
                if (str_eq(c1, "grant")) {
                    const char *a3 = (argc >= 4 && argv[3]) ? argv[3] : 0;
                    int auto_mode = (a3 && (a3[0] == 'A' || a3[0] == 'a' ||
                                            a3[0] == '1')) ? 1 : 0;
                    if (do_grant_policy(target, auto_mode) < 0) {
                        const char *m = "cnd: grant failed\n";
                        sc3(SYS_write, 2, (i64)m, slen(m));
                        sc1(SYS_exit, 1);
                        for (;;) ;
                    }
                    audit(auto_mode ? "GRANT-A" : "GRANT-C",
                          (u32)sc0(SYS_getuid), target);
                    if (wl_sync()<0) {
                        const char *m="cnd: saved allowlist but kernel sync failed\n";
                        sc3(SYS_write,2,(i64)m,slen(m));
                        sc1(SYS_exit,1);for(;;);
                    }
                    char b[16];
                    int n = utoa(target, b);
                    sc3(SYS_write, 1, (i64)b, n);
                    sc3(SYS_write, 1, (i64)"\n", 1);
                } else {
                    if(do_revoke(target)<0){sc1(SYS_exit,1);for(;;);}
                    audit("REVOKE", (u32)sc0(SYS_getuid), target);
                    if (wl_sync()<0) {
                        const char *m="cnd: saved allowlist but kernel sync failed\n";
                        sc3(SYS_write,2,(i64)m,slen(m));
                        sc1(SYS_exit,1);for(;;);
                    }
                    char b[16];
                    int n = utoa(target, b);
                    sc3(SYS_write, 1, (i64)b, n);
                    sc3(SYS_write, 1, (i64)"\n", 1);
                }
                sc1(SYS_exit, 0);
                for (;;) ;
            }

            if (str_eq(c1, "list")) {
                u32 n=0;
                if(al_lock()<0 || al_load()<0) {
                    al_unlock();sc1(SYS_exit,1);for(;;);
                }
                for(u32 i=0;i<al_count;i++) {
                    n+=utoa(al_uid[i],g_adm_out+n);
                    g_adm_out[n++]=':';g_adm_out[n++]='2';g_adm_out[n++]='\n';
                }
                al_unlock();
                if (n) sc3(SYS_write, 1, (i64)g_adm_out, n);
                sc1(SYS_exit, 0);
                for (;;) ;
            }

            {
                char *p = g_adm_out;
                p = adm_put(p, "proto=");
                p = adm_put_num(p, CAIRN_PROTO_VER);
                p = adm_put(p, "\nuid=");
                p = adm_put_num(p, (u32)sc0(SYS_getuid));
                p = adm_put(p, "\nmanager_uid=");
                p = adm_put_num(p, g_manager_uid);
                p = adm_put(p, "\nmode=oneshot\n");
                sc3(SYS_write, 1, (i64)g_adm_out, (i64)(p - g_adm_out));
                sc1(SYS_exit, 0);
                for (;;) ;
            }
        }

        {
            const char *m = "cnd: unknown command, try `cnd --help`\n";
            sc3(SYS_write, 2, (i64)m, slen(m));
                        sc1(SYS_exit, 2);
            for (;;) ;
        }
}
}
