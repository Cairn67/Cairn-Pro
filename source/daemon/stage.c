// Stages files before they are moved into place.
#ifndef CAIRN_STAGE_C
#define CAIRN_STAGE_C

static int str_eq(const char *a, const char *b);

#ifndef SIGKILL_
#define SIGKILL_ 9
#endif
#ifndef SIGCHLD_
#define SIGCHLD_ 17
#endif

#define CAIRN_MODULES_DIR   "/data/adb/modules"
#define CAIRN_SH_PATH       "/data/adb/cairn/bin/sh"

#define STAGE_POST_FS_DATA  1
#define STAGE_SERVICE       2
#define STAGE_BOOT_COMPLETED 3

#define STAGE_TIMEOUT_POST_FS_DATA  40
#define STAGE_TIMEOUT_SERVICE       0   

static int stage_parse(const char *name)
{
    if (!name) return 0;
    if (str_eq(name, "post-fs-data")) return STAGE_POST_FS_DATA;
    if (str_eq(name, "service"))      return STAGE_SERVICE;
    if (str_eq(name, "boot-completed")) return STAGE_BOOT_COMPLETED;
    return 0;
}

static const char *stage_script_name(int stage)
{
    if (stage == STAGE_POST_FS_DATA) return "post-fs-data.sh";
    if (stage == STAGE_SERVICE)      return "service.sh";
    if (stage == STAGE_BOOT_COMPLETED) return "boot-completed.sh";
    return 0;
}

static u32 stage_timeout(int stage)
{
    if (stage == STAGE_POST_FS_DATA) return STAGE_TIMEOUT_POST_FS_DATA;
    if (stage == STAGE_SERVICE)      return STAGE_TIMEOUT_SERVICE;
    return 0;
}

static long stage_arm_timer(u32 sec)
{
    long pid = sc6(SYS_clone, (i64)SIGCHLD_, 0, 0, 0, 0, 0);
    if (pid < 0) return -1;
    if (pid == 0) {
        
        struct timespec_ ts;
        ts.tv_sec  = (long)sec;
        ts.tv_nsec = 0;
        
        sc4(SYS_clock_nanosleep, 0, 0, (i64)&ts, 0);
        
        sc2(SYS_kill, (i64)sc0(SYS_getppid), (i64)SIGKILL_);
        
        sc1(SYS_exit, 0);
        for (;;) ;
    }
    return pid;   
}

static void stage_cancel_timer(long timer_pid)
{
    if (timer_pid > 0)
        sc2(SYS_kill, (i64)timer_pid, (i64)SIGKILL_);
}

static int stage_wait(long pid)
{
    int status = 0;
    sc4(SYS_wait4, (i64)pid, (i64)&status, 0, 0);
    
    if ((status & 0x7f) == 0)
        return (int)((status >> 8) & 0xff);
    
    return 128 + (int)(status & 0x7f);
}

static int stage_exec_script(const char *script_path)
{
    long pid = sc6(SYS_clone, (i64)SIGCHLD_, 0, 0, 0, 0, 0);
    if (pid < 0) return -1;
    if (pid == 0) {
        
        char *argv[4];
        argv[0] = (char *)CAIRN_SH_PATH;
        argv[1] = (char *)script_path;
        argv[2] = 0;
        argv[3] = 0;
        char moddir[400];
        if(slen(script_path)+8>=(int)sizeof(moddir)){sc1(SYS_exit,2);for(;;);}
        char *end=stpcpy_(moddir,"MODDIR=");stpcpy_(end,script_path);
        char *last=0;for(char *t=end;*t;t++)if(*t=='/')last=t;
        if(last)*last=0;
        char *envp[9];
        envp[0] = (char *)"PATH=/data/adb/cairn/bin:/system/bin:/system/xbin:/vendor/bin";
        envp[1] = (char *)"CAIRN=true";
        envp[2] = (char *)"CAIRN_API=1";
        envp[3] = (char *)"CAIRN_VER=1.0.1";
        envp[4] = (char *)"CAIRN_VER_CODE=122";
        envp[5] = (char *)"BOOTMODE=true";
        envp[6] = moddir;
        envp[7] = (char *)"CAIRN_CLI_API=2";
        envp[8] = 0;
        sc3(SYS_execve, (i64)CAIRN_SH_PATH, (i64)argv, (i64)envp);
        
        sc1(SYS_exit, 127);
        for (;;) ;
    }
    return stage_wait(pid);
}

static int stage_run_modules(int stage)
{
    const char *script_name = stage_script_name(stage);
    if (!script_name) return 0;

    char dirpath[256];
    char *p = dirpath;
    p = stpcpy_(p, CAIRN_MODULES_DIR);
    stpcpy_(p, "/");

    int fd = sc3(SYS_openat, AT_FDCWD, (i64)dirpath, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        loga_str("[stage] modules dir missing:", dirpath);
        return 0;
    }

    int ran = 0;
    struct dirent64_ *de;
    
    long nread;
    
    union { struct dirent64_ d; char raw[512]; } u;
    (void)de;

    for (;;) {
        nread = sc3(SYS_getdents64, fd, (i64)u.raw, 512);
        if (nread <= 0) break;
        long off = 0;
        while (off < nread) {
            de = (struct dirent64_ *)(u.raw + off);
            const char *name = de->d_name;
            off += de->d_reclen;
            if (!name || !name[0]) continue;
            if (name[0]==46 || !mod_id(name)) continue;
            if(mod_pending(name)){loga_str("[stage] pending reboot, skipped:",name);continue;}

            char moddir[320];
            char *q = moddir;
            q = stpcpy_(q, CAIRN_MODULES_DIR);
            q = stpcpy_(q, "/");
            q = stpcpy_(q, name);

            char flagpath[352];
            char *r = flagpath;
            r = stpcpy_(r, moddir);
            r = stpcpy_(r, "/disable");
            int dflag = sc3(SYS_openat, AT_FDCWD, (i64)flagpath, O_RDONLY);
            if (dflag >= 0) {
                sc1(SYS_close, dflag);
                loga_str("[stage] module disabled, skipped:", name);
                continue;
            }

            r=stpcpy_(flagpath,moddir);stpcpy_(r,"/remove");
            if(mod_exists(flagpath))continue;
            
            char script[384];
            char *s = script;
            s = stpcpy_(s, moddir);
            s = stpcpy_(s, "/");
            s = stpcpy_(s, script_name);

            int sfd = sc3(SYS_openat, AT_FDCWD, (i64)script, O_RDONLY);
            if (sfd < 0) continue;      
            sc1(SYS_close, sfd);

            loga_str("[stage] running:", script);
            int rc = stage_exec_script(script);
            ran++;
            if (rc == 0) {
                loga_str("[stage] script OK:", name);
            } else {
                loga_num("[stage] script FAILED rc=", (u32)rc);
                loga_str("[stage] module:", name);
            }
        }
    }
    sc1(SYS_close, fd);
    loga_num("[stage] scripts executed:", (u32)ran);
    return ran;
}

static int stage_run(int stage)
{
    if(sc0(SYS_geteuid)!=0)return 1;
    if(stage==STAGE_POST_FS_DATA){modules_commit();if(ca_stage_access())return 1;}
    u32 timeout = stage_timeout(stage);
    long timer_pid = -1;

    loga_str("[stage] entering:", stage_script_name(stage) ? stage_script_name(stage) : "?");

    if (timeout > 0) {
        timer_pid = stage_arm_timer(timeout);
        if (timer_pid < 0)
            loga_str("[stage] timer clone failed:", "no watchdog");
        else
            loga_num("[stage] watchdog armed, timeout sec=", timeout);
    }

    int ran = stage_run_modules(stage);

    if (timer_pid > 0) {
        stage_cancel_timer(timer_pid);
        loga_str("[stage] watchdog cancelled:", "done");
    }

    loga_num("[stage] finished, scripts=", (u32)ran);
    return 0;
}

#endif 
