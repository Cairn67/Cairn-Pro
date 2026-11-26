// Module list page and its lifecycle actions.
package com.cairn.manager;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.cairn.manager.redesign.RedesignModules;
import com.cairn.manager.redesign.RedesignTokens;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class ModulePage {
 final Activity a; final CairnCore core; final java.util.function.Supplier<long[]> keys;
 static final int COUNT_LOCKED = -1, COUNT_LOADING = -2, COUNT_FAILED = -3;
 final android.widget.FrameLayout root; final LinearLayout rows; final TextView status;
 private final DashboardUi dashboard;
 private final java.util.function.BooleanSupplier authenticated;
 private final java.util.function.IntConsumer onModuleCount;
 private final View installAction;
  
 private int requestId;
 private boolean loadingRows, loadingSummary;
 private int lastCount = COUNT_LOCKED;
 int dp(float n){return (int)(n*a.getResources().getDisplayMetrics().density+0.5f);}
 TextView text(String label,int size,int color){TextView t=new TextView(a);t.setText(label);t.setTextSize(size);t.setTextColor(color);return t;}
 void space(LinearLayout parent,int h){View v=new View(a);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
 com.cairn.manager.glass.GlassCard button(String label,int tint){
  var view=com.cairn.manager.glass.GlassComponents.button(a,label,15f,
          tint == 0x304c8dff ? DashboardUi.ACCENT : DashboardUi.RAISED,
          tint == 0x304c8dff ? DashboardUi.ON_ACCENT : DashboardUi.INK);
  view.setHighlightAlpha(0f);
  return view;
 }
 final android.widget.ScrollView scroll;
 ModulePage(Activity a, CairnCore core, java.util.function.Supplier<long[]> keys,
            java.util.function.BooleanSupplier authenticated, java.util.function.IntConsumer onModuleCount) {
  this.a=a; this.core=core; this.keys=keys;
  this.authenticated=authenticated; this.onModuleCount=onModuleCount;
  dashboard=new DashboardUi(a);
root=new android.widget.FrameLayout(a);
   LinearLayout body=dashboard.column();
   scroll = dashboard.scroller(body);
   root.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
body.addView(RedesignModules.pageHeader(a,
           Language.text(a,"Modules","模块"),
           ""));
installAction=dashboard.button("+", true, v -> {
    if (!authenticated.getAsBoolean()) return;
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);a.startActivityForResult(i,710);
   });
   installAction.setContentDescription(Language.text(a,"Install module","安装模块"));
   installAction.setEnabled(false);
   android.widget.FrameLayout.LayoutParams fab = new android.widget.FrameLayout.LayoutParams(dp(58),dp(58),android.view.Gravity.BOTTOM|android.view.Gravity.END);
   fab.setMargins(0,0,dp(20),dp(92));
   root.addView(installAction,fab);
  status=dashboard.text(Language.text(a,"Authenticate in the overview to see modules.","在概览完成认证后即可查看模块。"),13,DashboardUi.MUTED,true);
  dashboard.append(body,status,8);
  rows=dashboard.column();
  dashboard.append(body,rows,0);
 }
 static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
 CairnCore.Res run(String cmd){long[] k=keys.get();return core.runRoot(k[0],k[1],cmd);}
 void refreshSummary() { readModules(false); }
 void refresh() { readModules(true); }

 void showLocked() {
  requestId++;
  loadingRows = loadingSummary = false;
  lastCount = COUNT_LOCKED;
  installAction.setEnabled(false);
  rows.removeAllViews();
  status.setText(Language.text(a,"Authenticate in the overview to see modules.","在概览完成认证后即可查看模块。"));
  onModuleCount.accept(lastCount);
 }

 private void readModules(boolean full) {
  if (!authenticated.getAsBoolean()) { showLocked(); return; }
  if (loadingRows || (!full && loadingSummary)) return;
  int request=++requestId;
  loadingRows=full;
  loadingSummary=!full;
  installAction.setEnabled(true);
  long[] auth=keys.get();
  if (lastCount < 0) onModuleCount.accept(COUNT_LOADING);
  if (full) status.setText(Language.text(a,"Loading modules…","正在读取模块…"));
  new Thread(()->{
   CairnCore.Res r=core.runRoot(auth[0],auth[1],"/data/adb/cnd module list");
   if (!full) {
    int count=r.rc==0?moduleCount(r.text()):COUNT_FAILED;
    a.runOnUiThread(()->{
     if (!currentRequest(request)) return;
     loadingSummary=false;
     publishCount(count);
    });
    return;
   }
 final HashSet<String> webModules=new HashSet<>(); for(String line:r.text().split("\n")){String[] item=line.split("\t");if(item.length!=2||!item[0].matches("[A-Za-z][A-Za-z0-9_.-]{0,62}"))continue; CairnCore.Res check=run("test -f "+q("/data/adb/modules/"+item[0]+"/webroot/index.html"));if(check.rc==0)webModules.add(item[0]);}
   a.runOnUiThread(()->{
    if (!currentRequest(request)) return;
    loadingRows=false;
    publishCount(r.rc==0?moduleCount(r.text()):COUNT_FAILED);
    if (r.rc!=0) {
     rows.removeAllViews();
     status.setText(Language.text(a,"Could not load modules. Reopen this tab to retry.","暂时无法读取模块，请重新进入此页。"));
     return;
    }
 rows.removeAllViews();int count=0;
 for(String[] p:moduleRows(r.text()).values()){count++;
 boolean off=p[1].equals("disabled"),pending=p[1].equals("pending");int color=off?0xff8b95a5:pending?0xfffbbf24:0xff4ade80;
  LinearLayout box=dashboard.panel(16,RedesignTokens.palette(a).surface,RedesignTokens.palette(a).line);
  box.setPadding(dp(15),dp(13),dp(15),dp(13));
  LinearLayout top=new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
  TextView name=text(p[0],16,RedesignTokens.palette(a).primary); name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
  top.addView(name,new LinearLayout.LayoutParams(0,-2,1f));
  TextView stateText=text(off?Language.text(a,"Disabled","已禁用"):pending?Language.text(a,"Pending","待重启"):Language.text(a,"Enabled","已启用"),12,color);
  top.addView(stateText,new LinearLayout.LayoutParams(-2,dp(28))); box.addView(top);
  TextView description=text(off?Language.text(a,"Skipped on subsequent boots.","下次启动将跳过此模块。"):pending?Language.text(a,"Takes effect after reboot.","重启后生效。"):Language.text(a,"Eligible for boot-stage execution.","允许在启动阶段执行。"),12,RedesignTokens.palette(a).secondary);
  box.addView(description,new LinearLayout.LayoutParams(-1,-2)); space(box,10);
  com.cairn.manager.glass.GlassCard toggle=button(off?Language.text(a,"Enable · reboot required","恢复启用 · 重启生效"):Language.text(a,"Disable module","禁用模块"),off?0x304c8dff:0x14ffffff);
  toggle.setOnClickListener(v->new CairnDialog.Builder(a).setTitle(off?Language.text(a,"Enable module?","恢复启用？"):Language.text(a,"Disable module?","禁用模块？")).setMessage(off?Language.text(a,"Reboot to apply this change.","恢复后需重启生效。"):Language.text(a,"Prevents future execution; does not undo changes already made during this boot.","阻止后续执行；不会撤销本次开机已经执行的效果。")).setNegativeButton(Language.text(a,"Cancel","取消"),null).setPositiveButton(Language.text(a,"Confirm","确认"),(d,which)->new Thread(()->{CairnCore.Res x=run("/data/adb/cnd module "+(off?"enable ":"disable ")+q(p[0]));a.runOnUiThread(()->{CairnDialog.tip(a,x.rc==0?Language.text(a,"Done","操作成功"):x.text());refresh();});}).start()).show());
  box.addView(toggle,new LinearLayout.LayoutParams(-1,dp(46)));
  for(String op:new String[]{"action","uninstall"}){
   if(op.equals("action")&&(off||pending))continue;
   space(box,10);com.cairn.manager.glass.GlassCard extra=button(op.equals("action")?Language.text(a,"Run action script","执行操作脚本"):Language.text(a,"Uninstall module","卸载模块"),0x14ffffff);
   extra.setOnClickListener(v->new CairnDialog.Builder(a).setTitle(op.equals("action")?Language.text(a,"Run action.sh as root?","以 root 执行 action.sh？"):Language.text(a,"Run cleanup and remove this module?","运行卸载清理并删除模块？")).setMessage(Language.text(a,"Module scripts run as root without a sandbox. Reboot pending updates first. Failed operations retain the module; output is shown here.","模块脚本以 root 执行，没有沙箱。待更新模块请先重启。失败时保留模块；输出将在对话框显示。")).setNegativeButton(Language.text(a,"Cancel","取消"),null).setPositiveButton(Language.text(a,"Confirm","确认"),(d,w)->{
    extra.setEnabled(false);new Thread(()->{CairnCore.Res result=run("/system/bin/timeout -s KILL 120 /data/adb/cnd module "+op+" "+q(p[0]));a.runOnUiThread(()->{extra.setEnabled(true);new CairnDialog.Builder(a).setTitle(Language.text(a,"Exit code: ","退出码 ")+result.rc).setMessage(result.text()).setPositiveButton(Language.text(a,"OK","确定"),null).show();refresh();});}).start();
   }).show());box.addView(extra,new LinearLayout.LayoutParams(-1,dp(46)));
  }

 if(!off&&!pending&&webModules.contains(p[0])){space(box,10);com.cairn.manager.glass.GlassCard ui=button(Language.text(a,"Open WebUI","打开 WebUI"),0x304c8dff);ui.setOnClickListener(v->new Thread(()->{CairnCore.Res check=run("test -f "+q("/data/adb/modules/"+p[0]+"/webroot/index.html"));a.runOnUiThread(()->{if(check.rc!=0){CairnDialog.tip(a,Language.text(a,"This module has no WebUI entry point","模块没有 WebUI 入口"));refresh();return;}Intent intent=new Intent(a,ModuleWebActivity.class);intent.putExtra("module_id",p[0]);a.startActivity(intent);});}).start());box.addView(ui,new LinearLayout.LayoutParams(-1,dp(46)));}
 dashboard.append(rows,box,9);
 }
 status.setText(Language.text(a,"Installed: ","已安装 ")+count+Language.text(a," modules"," 个模块"));
 if(count==0){
  LinearLayout empty = dashboard.panel(20,DashboardUi.SURFACE,16);
  empty.addView(dashboard.heading(Language.text(a,"No modules yet","暂无模块"),17));
  dashboard.append(empty,dashboard.text(Language.text(a,"Choose a module ZIP to get started.","可选择 ZIP 安装模块。"),13,DashboardUi.MUTED,false),5);
  dashboard.append(rows,empty,8);
 }
 });}).start();}

 private boolean currentRequest(int request) {
  return request==requestId && !a.isFinishing() && !a.isDestroyed() && authenticated.getAsBoolean();
 }

 private void publishCount(int count) { lastCount=count; onModuleCount.accept(count); }

 private static java.util.Map<String,String[]> moduleRows(String text) {
  java.util.Map<String,String[]> entries=new java.util.LinkedHashMap<>();
  for(String line:text.split("\n")) {
   String[] row=line.split("\t",-1);
   if(row.length==2 && row[0].matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}") && !row[1].isEmpty())
    entries.put(row[0],row);
  }
  return entries;
 }

 private static int moduleCount(String text) { return moduleRows(text).size(); }
 void install(Uri uri){InstallConsole.start(a);InstallConsole.append("[1/4] Validating and extracting module files");status.setText(Language.text(a,"Checking ZIP…","正在检查 ZIP…"));new Thread(()->{File temp=new File(a.getFilesDir(),"module-unpack-"+System.nanoTime());temp.mkdirs();try{
  long total=0;int count=0;HashSet<String> names=new HashSet<>();
  try(ZipInputStream z=new ZipInputStream(a.getContentResolver().openInputStream(uri))){ZipEntry e;byte[] buf=new byte[16384];while((e=z.getNextEntry())!=null){String n=e.getName();if(++count>2048||n.startsWith("/")||n.contains("\\")||!names.add(n))throw new IOException("Invalid ZIP path or duplicate entry");for(String part:n.split("/"))if(part.equals("..")||part.startsWith(".cairn")||part.equals(".install-source.zip"))throw new IOException("Invalid path");if(n.contains("\n")||n.contains("\r"))throw new IOException("Invalid path");
   String low=n.toLowerCase(Locale.ROOT);String base=low.substring(low.lastIndexOf('/')+1);
   if(base.equals("sepolicy.rule")||base.equals("post-mount.sh")||low.startsWith("system/")||low.startsWith("vendor/")||low.startsWith("product/")||low.startsWith("system_ext/")||low.startsWith("odm/"))throw new IOException("Unsupported module feature: "+n);
   if(low.startsWith("meta-inf/"))continue;
   File f=new File(temp,n);if(!f.getCanonicalPath().startsWith(temp.getCanonicalPath()+"/"))throw new IOException("Path traversal rejected");if(e.isDirectory()){f.mkdirs();continue;}f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){int read;while((read=z.read(buf))!=-1){total+=read;if(total>32*1024*1024)throw new IOException("Module exceeds 32MiB");out.write(buf,0,read);}}
  }}
  InstallConsole.append("- Extracted size: "+String.format(java.util.Locale.US,"%.2f MiB",total/1048576.0));
  Properties props=new Properties();try(InputStream in=new FileInputStream(new File(temp,"module.prop"))){props.load(new InputStreamReader(in,"UTF-8"));}String id=props.getProperty("id","").trim();if(!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,62}")||id.endsWith("."))throw new IOException("Invalid module.prop id");
  InstallConsole.append("- Installing to /data/adb/modules_update/"+id);
  InstallConsole.append("- Module: "+props.getProperty("name",id)+" · "+props.getProperty("version","?"));
  String target="/data/adb/cairn/staging/"+id;
  File helper=new File(a.getFilesDir(),"module-install-"+System.nanoTime()+".sh");
  try(InputStream input=a.getAssets().open("module-install.sh");FileOutputStream output=new FileOutputStream(helper)){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)output.write(b,0,n);}
  File archive=new File(temp,".install-source.zip");
  try(InputStream input=a.getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(archive)){byte[] b=new byte[16384];int n;long limit=0;while((n=input.read(b))!=-1){limit+=n;if(limit>32*1024*1024)throw new IOException("ZIP exceeds size limit");output.write(b,0,n);}}
  InstallConsole.append("[2/4] Creating isolated staging directory: "+id);
  CairnCore.Res copy=run("mkdir -p /data/adb/cairn/staging\nif [ -e "+q(target)+" ] || [ -e "+q("/data/adb/modules_update/"+id)+" ]; then echo 'A pending update exists. Reboot before reinstalling.'; exit 1; fi\ncp -R "+q(temp.getAbsolutePath())+" "+q(target)+"\n");
  InstallConsole.append(copy.text());if(copy.rc!=0)throw new IOException("Cannot create staging directory");
  InstallConsole.append("[3/4] Running module installer · live output · 120s timeout");
  File live=new File(a.getFilesDir(),"install-log-"+System.nanoTime()+".txt");InstallConsole.liveLog=live;
  CairnCore.Res custom=run("umask 022\n/system/bin/timeout -s KILL 120 /data/adb/cairn/bin/sh "+q(helper.getAbsolutePath())+" "+q(target)+" "+q(target+"/.install-source.zip")+" > "+q(live.getAbsolutePath())+" 2>&1\n");
  try(FileInputStream log=new FileInputStream(live)){byte[] b=new byte[(int)Math.min(live.length(),131072)];int n=log.read(b);if(n>0)InstallConsole.append(new String(b,0,n,"UTF-8"));}catch(Exception ignored){}InstallConsole.liveLog=null;
  InstallConsole.append(custom.text());helper.delete();
  if(custom.rc!=0){run("rm -rf "+q(target));throw new IOException("customize.sh failed; existing version retained: "+custom.rc);}
  InstallConsole.append("[4/4] Queuing installation in modules_update");
  CairnCore.Res r=run("rm -f "+q(target+"/.install-source.zip")+"\n/data/adb/cnd module install "+q(id)+"\n");
  InstallConsole.append(r.text());if(r.rc!=0)throw new IOException("Cannot queue update: "+r.rc);
  InstallConsole.append("✓ Installation complete. Reboot to activate. Existing version retained until reboot.");
  a.runOnUiThread(()->{status.setText(Language.text(a,"Installed. Reboot to activate.","安装成功，重启后生效"));refresh();});
 }catch(Exception e){InstallConsole.append("✗ Installation failed: "+e.getMessage());a.runOnUiThread(()->status.setText(Language.text(a,"Installation failed: ","安装失败：")+e.getMessage()));}finally{InstallConsole.running=false;remove(temp);}}).start();}
 static void remove(File f){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)remove(x);f.delete();}
}
