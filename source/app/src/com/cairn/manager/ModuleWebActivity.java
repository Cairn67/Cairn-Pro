// Web view page for module-provided content.
package com.cairn.manager;

import android.app.*;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ModuleWebActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Language.wrap(base));}
    private static final String ORIGIN="https://cairn-module.invalid";
    private String id,base; private CairnCore core; private long k0,k1;
    private WebView web; private volatile boolean active=true;
    private final Object ioLock=new Object();
    private volatile boolean logEnabled=false;
    private final java.util.concurrent.ThreadPoolExecutor logWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<Runnable>(128),new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private String logPath(){return "/data/adb/cairn/logs/webui/"+id+".log";}
    private void logEvent(String level,String message){
        if(!active||!logEnabled)return;
        String clean=message==null?"":message.replace("\r","\\r").replace("\n","\\n");
        if(clean.length()>4096)clean=clean.substring(0,4096)+" [truncated]";
        final String line=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",java.util.Locale.US).format(new Date())+" "+level+" "+clean;
        final long a=k0,b=k1;
        logWorker.execute(()->{String q=ModulePage.q(logPath());core.runRoot(a,b,"umask 077\nmkdir -p /data/adb/cairn/logs/webui || exit 1\nchmod 0700 /data/adb/cairn/logs /data/adb/cairn/logs/webui\nif [ -f "+q+" ] && [ $(stat -c%s "+q+") -gt 1048576 ]; then mv -f "+q+" "+ModulePage.q(logPath()+".1")+"; fi\nprintf '%s\\n' "+ModulePage.q(line)+" >> "+q+"\nchmod 0600 "+q+"\n");});
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);id=getIntent().getStringExtra("module_id");
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,62}")){finish();return;}
        try{String key=new KeyStoreBox(this).load();long[] k=CairnCore.keyToLongs(key);k0=k[0];k1=k[1];core=new CairnCore(this);}
        catch(Exception e){CairnDialog.tip(this,Language.text(this,"Cannot access the manager key","无法读取管理器密钥"));finish();return;}
        base="/data/adb/modules/"+id;
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setBackgroundColor(0xff101319);
        TextView title=new TextView(this);title.setText(Language.text(this,"‹ Back     ","‹ 返回     ")+id+" · WebUI");title.setTextColor(-1);title.setPadding(24,48,24,20);title.setOnClickListener(v->finish());page.addView(title);
        LinearLayout tools=new LinearLayout(this);tools.setPadding(20,4,20,12);page.addView(tools);
        com.cairn.manager.glass.GlassCard toggle=com.cairn.manager.glass.GlassComponents.button(this,Language.text(this,"Enable WebUI logs","开启 WebUI 日志"),18f,0x14ffffff,0xffe6edf3);
        com.cairn.manager.glass.GlassCard view=com.cairn.manager.glass.GlassComponents.button(this,Language.text(this,"View logs","查看日志"),18f,0x14ffffff,0xffe6edf3);
        tools.addView(toggle,new LinearLayout.LayoutParams(0,110,1));LinearLayout.LayoutParams vl=new LinearLayout.LayoutParams(0,110,1);vl.leftMargin=12;tools.addView(view,vl);
        toggle.setOnClickListener(v->{logEnabled=!logEnabled;logEvent("INFO","WebUI logging enabled");
            android.view.ViewGroup parent=(android.view.ViewGroup)toggle.getParent();int pos=parent.indexOfChild(toggle);
            
            updateLogLabel(toggle,Language.text(this,logEnabled?"Disable WebUI logs":"Enable WebUI logs",logEnabled?"关闭 WebUI 日志":"开启 WebUI 日志"));
            
        });
        view.setOnClickListener(v->{view.setEnabled(false);new Thread(()->{CairnCore.Res res=run("tail -n 300 "+ModulePage.q(logPath())+" 2>&1\n");runOnUiThread(()->{view.setEnabled(true);if(!active)return;new CairnDialog.Builder(this).setTitle(Language.text(this,"WebUI logs","WebUI 日志")).setMessage(logPath()+"\n\n"+res.text()).setPositiveButton(Language.text(this,"OK","确定"),null).show();});}).start();});
        web=new WebView(this);page.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setDownloadListener((u,ua,cd,m,n)->{});
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){logEvent("JS_"+m.messageLevel(),m.sourceId()+":"+m.lineNumber()+" "+m.message());return true;}@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
        web.addJavascriptInterface(new Api(),"Cairn Pro");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){logEvent("INFO","Page started: "+url);}
            @Override public void onPageFinished(WebView v,String url){logEvent("INFO","Page finished: "+url);v.evaluateJavascript("(function(){if(window.__cairnErrorLog)return;window.__cairnErrorLog=true;window.addEventListener('error',function(e){console.error('Uncaught error: '+e.message+' at '+e.filename+':'+e.lineno);});window.addEventListener('unhandledrejection',function(e){console.error('Unhandled rejection: '+String(e.reason));});})()",null);}
            @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError err){logEvent("ERROR","Resource failed: "+req.getUrl()+" code="+err.getErrorCode()+" "+err.getDescription());}

            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !local(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(!local(r.getUrl())||!"GET".equals(r.getMethod()))return response(403,"text/plain","Blocked");
                String path=r.getUrl().getPath();if(path==null||path.equals("/"))path="/index.html";
                if(!safe(path.substring(1)))return response(403,"text/plain","Invalid path");
                String type=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".json")?"application/json":path.endsWith(".svg")?"image/svg+xml":"text/plain";
                String content=readOwn("webroot"+path);if(content==null)logEvent("ERROR","Local resource denied: "+path);return content==null?response(403,"text/plain","Module unavailable or resource denied"):response(200,type,content);
            }
        });
        web.loadUrl(ORIGIN+"/index.html");
    }
    private void updateLogLabel(android.view.View v,String label){if(v instanceof TextView)((TextView)v).setText(label);else if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)updateLogLabel(g.getChildAt(i),label);}}
    private static boolean local(Uri u){return "https".equals(u.getScheme())&&"cairn-module.invalid".equals(u.getHost())&&u.getPort()==-1;}
    private static boolean safe(String p){if(p==null||p.length()>512||p.startsWith("/")||p.contains("\\")||p.contains("\n")||p.contains("\r"))return false;for(String s:p.split("/",-1))if(s.equals("..")||s.equals(".")||s.isEmpty()||s.startsWith(".cairn"))return false;return true;}
    private CairnCore.Res run(String cmd){synchronized(ioLock){return core.runRoot(k0,k1,cmd);}}
    private boolean enabled(){if(!active)return false;CairnCore.Res r=run("/data/adb/cnd module list");for(String line:r.text().split("\n"))if(line.equals(id+"\tenabled"))return r.rc==0;return false;}
    private String readOwn(String path){if(!safe(path)||!enabled())return null;
        String q=ModulePage.q(base),p=ModulePage.q(base+"/"+path);
        CairnCore.Res r=run("b=$(readlink -f "+q+") || exit 1\np=$(readlink -f "+p+") || exit 1\ncase \"$p\" in \"$b\"/*) ;; *) exit 1;; esac\n[ -f \"$p\" ] || exit 1\n[ $(stat -c%s \"$p\") -le 65536 ] || exit 1\ncat \"$p\"\n");return r.rc==0?r.text():null;
    }
    private static WebResourceResponse response(int code,String mime,String body){Map<String,String> h=new HashMap<>();h.put("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-src 'none'; child-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'");h.put("Cache-Control","no-store");h.put("X-Content-Type-Options","nosniff");return new WebResourceResponse(mime,"UTF-8",code,code==200?"OK":"Forbidden",h,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));}
    private String result(String value,String error){try{JSONObject j=new JSONObject();j.put("ok",error==null);if(error==null)j.put("data",value);else j.put("error",error);return j.toString();}catch(Exception e){return "{\"ok\":false}";}}
    private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(4);
    private final java.util.concurrent.ConcurrentHashMap<String,String> jobs=new java.util.concurrent.ConcurrentHashMap<>();
    private String path(String p){if(p==null||p.indexOf(0)>=0)throw new IllegalArgumentException("invalid-path");return p.startsWith("/")?p:base+"/"+p;}
    private String submit(String command,int timeout){
        if(!active)return result(null,"session-closed");
        if(jobs.size()>=64)return result(null,"too-many-uncollected-jobs");
        String job=java.util.UUID.randomUUID().toString();logEvent("INFO","Task submitted: "+job);jobs.put(job,"{\"ok\":true,\"state\":\"running\"}");
        workers.execute(()->{
            try{
                if(!enabled()){jobs.put(job,result(null,"module-disabled-or-session-closed"));return;}
                String body="cd "+ModulePage.q(base)+" || exit 1\n";
                body+=(timeout>0?"/system/bin/timeout "+timeout+" ":"")+"/system/bin/sh -c "+ModulePage.q(command)+" 2>&1\n";
                CairnCore.Res res=core.runRoot(k0,k1,body);
                logEvent("INFO","Task completed: "+job+" exit="+res.rc);JSONObject j=new JSONObject();j.put("ok",true);j.put("state","done");j.put("exitCode",res.rc);j.put("output",res.text());
                if(active)jobs.put(job,j.toString());
            }catch(Exception e){if(active)jobs.put(job,result(null,e.toString()));}
        });
        return result(job,null);
    }
    public final class Api {
        @JavascriptInterface public String version(){return "2";}
        @JavascriptInterface public String status(){return enabled()?result(id+":enabled",null):result(null,"module-disabled-or-session-closed");}
        @JavascriptInterface public String exec(String command,String options){
            try{if(command==null||command.indexOf(0)>=0)throw new IllegalArgumentException("invalid-command");JSONObject o=options==null||options.isEmpty()?new JSONObject():new JSONObject(options);int timeout=o.optInt("timeoutSeconds",120);if(timeout<0||timeout>86400)throw new IllegalArgumentException("invalid-timeout");return submit(command,timeout);}catch(Exception e){return result(null,e.toString());}
        }
        @JavascriptInterface public String poll(String job){String value=jobs.get(job);return value==null?result(null,"unknown-job"):value;}
        @JavascriptInterface public String forget(String job){jobs.remove(job);return result("forgotten",null);}
        @JavascriptInterface public String readFile(String relativePath){if(!active||!enabled())return result(null,"module-disabled-or-session-closed");try{CairnCore.Res r=run("cat "+ModulePage.q(path(relativePath))+"\n");return r.rc==0?result(r.text(),null):result(null,r.text());}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String writeFile(String filename,String content){try{String encoded=android.util.Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);return submit("printf %s "+ModulePage.q(encoded)+" | /system/bin/base64 -d > "+ModulePage.q(path(filename)),120);}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String listDir(String filename){try{return submit("ls -la "+ModulePage.q(path(filename)),120);}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String stat(String filename){try{return submit("stat "+ModulePage.q(path(filename)),120);}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String mkdir(String filename){try{return submit("mkdir -p "+ModulePage.q(path(filename)),120);}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String remove(String filename,boolean recursive){try{return submit("rm "+(recursive?"-rf ":"-f ")+"-- "+ModulePage.q(path(filename)),120);}catch(Exception e){return result(null,e.toString());}}
        @JavascriptInterface public String getConfig(){return readFile("config.json");}
        @JavascriptInterface public String setConfig(String json){try{new JSONObject(json);return writeFile("config.json",json);}catch(Exception e){return result(null,"invalid-json-object");}}
        @JavascriptInterface public String runAction(){return submit("/data/adb/cnd module action "+ModulePage.q(id),120);}
        @JavascriptInterface public String getModuleInfo(){return readFile("module.prop");}
        @JavascriptInterface public String getSystemInfo(){return submit("id; uname -a; getprop ro.build.version.release; /data/adb/cnd --version",120);}
    }
    @Override protected void onDestroy(){active=false;logEnabled=false;logWorker.shutdown();workers.shutdownNow();jobs.clear();if(web!=null){web.removeJavascriptInterface("Cairn Pro");web.stopLoading();web.destroy();}k0=k1=0;super.onDestroy();}
}
