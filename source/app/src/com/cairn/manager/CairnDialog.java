// Small themed dialog helpers.
package com.cairn.manager;
import android.app.Activity;
import android.content.DialogInterface;
import android.view.*;
import android.widget.*;
import com.cairn.manager.glass.*;

final class CairnDialog {
 static int dp(Activity a,int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
 static class MotionDialog extends android.app.Dialog {
  View surface; boolean closing; MotionDialog(Activity a){super(a);}
  @Override public void dismiss(){if(closing)return;if(surface==null||!isShowing()){super.dismiss();return;}closing=true;UiMotion.exit(surface,()->{super.dismiss();closing=false;});}
 }
 static class Builder {
  final Activity a; String title="",message; View custom;String positive,negative,neutral;DialogInterface.OnClickListener yes,no,other;String[] items;int selected;DialogInterface.OnClickListener choice;DialogInterface.OnCancelListener cancel;
  Builder(Activity a){this.a=a;}
  Builder setTitle(String s){title=s;return this;}Builder setMessage(String s){message=s;return this;}Builder setView(View v){custom=v;return this;}
  Builder setPositiveButton(String s,DialogInterface.OnClickListener l){positive=s;yes=l;return this;}Builder setNegativeButton(String s,DialogInterface.OnClickListener l){negative=s;no=l;return this;}Builder setNegativeButton(int s,DialogInterface.OnClickListener l){return setNegativeButton(a.getString(s),l);}Builder setNeutralButton(String s,DialogInterface.OnClickListener l){neutral=s;other=l;return this;}
  Builder setSingleChoiceItems(String[] s,int i,DialogInterface.OnClickListener l){items=s;selected=i;choice=l;return this;}Builder setOnCancelListener(DialogInterface.OnCancelListener l){cancel=l;return this;}
  android.app.Dialog show(){MotionDialog d=new MotionDialog(a);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
   GlassCard shell=GlassComponents.card(a,26,0xFF19212D,false);shell.setHighlightAlpha(0).setStrokeColor(0x183F536D);d.surface=shell;LinearLayout content=new LinearLayout(a);content.setOrientation(1);content.setPadding(dp(a,20),dp(a,20),dp(a,20),dp(a,16));shell.addView(content,new FrameLayout.LayoutParams(-1,-2));
   TextView heading=new TextView(a);heading.setText(title);heading.setTextColor(0xffedf3ff);heading.setTextSize(21);heading.setTypeface(null,1);heading.setPadding(0,0,0,dp(a,16));content.addView(heading);
   ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);LinearLayout body=new LinearLayout(a);body.setOrientation(1);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
   if(message!=null){TextView text=new TextView(a);text.setText(message);text.setTextColor(0xffb7c6db);text.setTextSize(14);text.setTextIsSelectable(true);body.addView(text);}
   if(custom!=null)body.addView(custom);
   if(items!=null)for(int i=0;i<items.length;i++){final int index=i;GlassCard option=GlassComponents.button(a,(i==selected?"✓  ":"    ")+items[i],16,i==selected?0x304FD4B6:0x12ffffff,0xffe0ecfa);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(a,52));lp.bottomMargin=dp(a,8);option.setHighlightAlpha(0).setStrokeColor(0);lp.bottomMargin=dp(a,2);body.addView(option,lp);option.setOnClickListener(v->{if(choice!=null)choice.onClick(d,index);d.dismiss();});}
   LinearLayout actions=new LinearLayout(a);actions.setGravity(Gravity.END);actions.setPadding(0,dp(a,18),0,0);content.addView(actions);add(actions,d,neutral,other,DialogInterface.BUTTON_NEUTRAL);add(actions,d,negative,no,DialogInterface.BUTTON_NEGATIVE);add(actions,d,positive,yes,DialogInterface.BUTTON_POSITIVE);
   int width=Math.min(a.getResources().getDisplayMetrics().widthPixels-dp(a,32),dp(a,560));
   int screenHeight=a.getResources().getDisplayMetrics().heightPixels;
   android.graphics.Rect usable=new android.graphics.Rect();a.getWindow().getDecorView().getWindowVisibleDisplayFrame(usable);if(usable.height()>0)screenHeight=usable.height();
   int maxHeight=Math.max(dp(a,180),screenHeight-dp(a,80));
   shell.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
   int overhead=shell.getMeasuredHeight()-scroll.getMeasuredHeight();
   int bodyLimit=Math.max(dp(a,60),maxHeight-overhead);
   if(scroll.getMeasuredHeight()>bodyLimit)scroll.getLayoutParams().height=bodyLimit;
   shell.setAlpha(UiMotion.enabled()?0f:1f);
   d.setContentView(shell);if(cancel!=null)d.setOnCancelListener(cancel);
   Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setWindowAnimations(0);w.setGravity(Gravity.CENTER);w.setLayout(width,-2);w.setDimAmount(.65f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);}
   shell.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){shell.getViewTreeObserver().removeOnPreDrawListener(this);UiMotion.enter(shell);return true;}});
   d.show();if(w!=null)w.setLayout(width,-2);return d;
  }
  void add(LinearLayout row,android.app.Dialog d,String label,DialogInterface.OnClickListener listener,int which){if(label==null)return;GlassCard button=GlassComponents.button(a,label,16,which==-1?0x334FD4B6:0x14ffffff,0xffdeedf9);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(a,50),1);lp.setMarginStart(dp(a,6));lp.setMarginEnd(dp(a,6));button.setHighlightAlpha(0).setStrokeColor(0);row.addView(button,lp);button.setOnClickListener(v->{if(listener!=null)listener.onClick(d,which);d.dismiss();});}
 }
 static void tip(Activity a,String message){
  if(a.isFinishing()||a.isDestroyed())return;
  FrameLayout host=a.findViewById(android.R.id.content);if(host==null)return;
  Object old=host.getTag(0x7e000001);if(old instanceof View){((View)old).animate().cancel();host.removeView((View)old);}
  GlassCard card=GlassComponents.card(a,18,0xF2223043,false);TextView t=new TextView(a);t.setText(message);t.setTextSize(14);t.setTextColor(0xffe3efff);t.setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));card.addView(t);
  int top=dp(a,76);android.view.WindowInsets insets=host.getRootWindowInsets();if(insets!=null)top+=insets.getSystemWindowInsetTop();
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);lp.setMargins(dp(a,24),top,dp(a,24),0);card.setHighlightAlpha(0).setStrokeColor(0);host.addView(card,lp);UiMotion.enter(card);host.setTag(0x7e000001,card);
  boolean[] closing={false};Runnable[] timeout=new Runnable[1];Runnable dismiss=()->{if(closing[0])return;closing[0]=true;if(timeout[0]!=null)card.removeCallbacks(timeout[0]);card.setEnabled(false);UiMotion.exit(card,()->{host.removeView(card);if(host.getTag(0x7e000001)==card)host.setTag(0x7e000001,null);});};
  card.setClickable(true);card.setFocusable(true);card.setContentDescription(message+Language.text(a,". Tap to dismiss","。点击关闭"));com.cairn.manager.redesign.RedesignPress.install(card);card.setOnClickListener(v->dismiss.run());timeout[0]=dismiss;card.postDelayed(timeout[0],3500);
 }
}
