// Editable draft of the per-app visibility rules before they are applied.
package com.cairn.manager;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.cairn.manager.glass.GlassCard;
import com.cairn.manager.glass.GlassComponents;
import java.util.*;

final class AppVisibilityDraft {
 static int dp(Activity a,int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
 static TextView text(Activity a,String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
 static void showForApp(Activity c,AppList.App caller,List<AppList.App> apps,Runnable onSaved){
  SharedPreferences prefs=c.getSharedPreferences("visibility_per_app",0);
  Set<String> selected=new HashSet<>(prefs.getStringSet(caller.pkg,Collections.emptySet()));
  ArrayList<AppList.App> targets=new ArrayList<>();for(AppList.App a:apps)if(!a.pkg.equals(caller.pkg))targets.add(a);
  targets.sort((a,b)->a.label.compareToIgnoreCase(b.label));
  FrameLayout host=c.findViewById(android.R.id.content);
  FrameLayout overlay=new FrameLayout(c);overlay.setBackgroundColor(0xB0080D16);overlay.setClickable(true);overlay.setFocusableInTouchMode(true);
  GlassCard shell=GlassComponents.card(c,28f,0xF21A2230,false);
  shell.setHighlightAlpha(0).setStrokeColor(0x223F536D);FrameLayout.LayoutParams shellLp=new FrameLayout.LayoutParams(-1,-1);shellLp.setMargins(dp(c,12),dp(c,44),dp(c,12),dp(c,24));overlay.addView(shell,shellLp);
  LinearLayout panel=new LinearLayout(c);panel.setOrientation(1);panel.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,12));shell.addView(panel,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout heading=new LinearLayout(c);heading.setGravity(Gravity.CENTER_VERTICAL);panel.addView(heading);
  TextView title=text(c,Language.text(c,"App visibility","应用可见性"),23,0xffedf3ff);title.setTypeface(null,Typeface.BOLD);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  GlassCard close=GlassComponents.button(c,Language.text(c,"Close","关闭"),16,0x18ffffff,0xffd9e4f5);heading.addView(close,new LinearLayout.LayoutParams(dp(c,68),dp(c,48)));
  TextView subtitle=text(c,caller.label+"\n"+caller.pkg,13,0xffa6b5cb);subtitle.setPadding(0,dp(c,8),0,dp(c,14));panel.addView(subtitle);
  GlassCard notice=GlassComponents.card(c,16,0x19E9B667,false);TextView warning=text(c,Language.text(c,"System backend not connected · rules are inactive.\nSelect apps to hide from this caller. This does not restrict access to app data.","系统后端未接入 · 当前规则不会生效\n选择不希望被此应用看见的目标，不限制应用数据访问。"),12,0xffefca8a);warning.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));notice.addView(warning);notice.setHighlightAlpha(0).setStrokeColor(0);panel.addView(notice);
  GlassCard searchBg=GlassComponents.card(c,16,0x10ffffff,false);LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,dp(c,52));searchLp.topMargin=dp(c,12);searchBg.setHighlightAlpha(0).setStrokeColor(0);panel.addView(searchBg,searchLp);
  EditText search=new EditText(c);search.setBackground(null);search.setSingleLine(true);search.setTextColor(0xffedf3ff);search.setHintTextColor(0xff899ab2);search.setTextSize(14);search.setHint(Language.text(c,"Search apps or packages","搜索应用或包名"));search.setPadding(dp(c,16),0,dp(c,16),0);searchBg.addView(search,new FrameLayout.LayoutParams(-1,-1));
  TextView counter=text(c,"",12,0xff9fb0c8);counter.setPadding(0,dp(c,12),0,dp(c,8));panel.addView(counter);
  ListView list=new ListView(c);list.setBackgroundColor(Color.TRANSPARENT);list.setDivider(null);list.setDividerHeight(dp(c,8));list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  ArrayList<AppList.App> visible=new ArrayList<>(targets);Runnable update=()->counter.setText(Language.text(c,"Selected: ","已选 ")+selected.size()+Language.text(c," / Visible: "," 项   /   当前 ")+visible.size()+Language.text(c," · Draft"," 项 · 草稿"));
  BaseAdapter adapter=new BaseAdapter(){public int getCount(){return visible.size();}public Object getItem(int i){return visible.get(i);}public long getItemId(int i){return i;}
   public View getView(int i,View unused,ViewGroup parent){AppList.App a=visible.get(i);boolean checked=selected.contains(a.pkg);
    GlassCard card=GlassComponents.card(c,18,checked?0x253FAF9D:0x0Cffffff,false);LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));card.addView(row,new FrameLayout.LayoutParams(-1,-2));
    ImageView icon=new ImageView(c);icon.setImageDrawable(a.icon);row.addView(icon,new LinearLayout.LayoutParams(dp(c,38),dp(c,38)));
    LinearLayout labels=new LinearLayout(c);labels.setOrientation(1);labels.setPadding(dp(c,12),0,dp(c,8),0);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
    TextView name=text(c,a.label,14,0xffeaf1fc);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);labels.addView(name);TextView pkg=text(c,a.pkg,11,0xff91a1b9);pkg.setSingleLine(true);pkg.setTextDirection(View.TEXT_DIRECTION_LTR);pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);labels.addView(pkg);
    View mark=new View(c){Paint p=new Paint(3);protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(checked?0xff76dbc5:0xff61738e);canvas.drawCircle(getWidth()/2f,getHeight()/2f,10*d,p);if(checked){Path path=new Path();float x=getWidth()/2f,y=getHeight()/2f;path.moveTo(x-5*d,y);path.lineTo(x-1*d,y+4*d);path.lineTo(x+6*d,y-4*d);canvas.drawPath(path,p);}}};row.addView(mark,new LinearLayout.LayoutParams(dp(c,32),dp(c,40)));
    card.setHighlightAlpha(0).setStrokeColor(checked?0x404FD4B6:0);card.setClickable(true);card.setFocusable(true);com.cairn.manager.redesign.RedesignPress.install(card);card.setContentDescription(a.label+(checked?Language.text(c,", selected","，已选择"):Language.text(c,", not selected","，未选择")));card.setOnClickListener(v->{if(checked)selected.remove(a.pkg);else selected.add(a.pkg);notifyDataSetChanged();update.run();});return card;}
  };list.setAdapter(adapter);
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int d){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int before,int n){String q=s.toString().toLowerCase(Locale.ROOT);visible.clear();for(AppList.App x:targets)if((x.label+" "+x.pkg).toLowerCase(Locale.ROOT).contains(q))visible.add(x);adapter.notifyDataSetChanged();update.run();}});
  LinearLayout actions=new LinearLayout(c);actions.setPadding(0,dp(c,12),0,0);panel.addView(actions);
  GlassCard clear=GlassComponents.button(c,Language.text(c,"Clear selection","清空选择"),18,0x14ffffff,0xffd7e3f5);LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(c,52),1);left.setMarginEnd(dp(c,14));actions.addView(clear,left);
  GlassCard save=GlassComponents.button(c,Language.text(c,"Save draft","保存草稿"),18,0x334FD4B6,0xffb5f2e4);actions.addView(save,new LinearLayout.LayoutParams(0,dp(c,52),1));
  boolean[] closing={false};Runnable dismiss=()->{if(closing[0])return;closing[0]=true;android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)c.getSystemService(Activity.INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(search.getWindowToken(),0);UiMotion.exit(shell,()->host.removeView(overlay));};
  close.setOnClickListener(v->dismiss.run());clear.setOnClickListener(v->{selected.clear();adapter.notifyDataSetChanged();update.run();});save.setOnClickListener(v->{prefs.edit().putStringSet(caller.pkg,new HashSet<>(selected)).apply();onSaved.run();dismiss.run();});
  overlay.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)dismiss.run();return true;}return false;});host.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.requestFocus();UiMotion.enter(shell);update.run();
 }
 private AppVisibilityDraft(){}
}
