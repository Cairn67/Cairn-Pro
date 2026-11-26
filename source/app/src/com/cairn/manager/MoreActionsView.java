// Text-free overflow control drawn as three dots.
package com.cairn.manager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.Button;

final class MoreActionsView extends View {
 private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
 MoreActionsView(Context c, int color) {
  super(c);paint.setColor(color);setClickable(true);setFocusable(true);
  GradientDrawable mask=new GradientDrawable();mask.setColor(0xffffffff);mask.setCornerRadius(100);
  setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888),null,mask));
 }
 @Override public void setPressed(boolean pressed){super.setPressed(pressed);animate().cancel();animate().scaleX(pressed?.86f:1f).scaleY(pressed?.86f:1f).rotation(pressed?12f:0f).setDuration(UiMotion.enabled()?170:0).start();}
 @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density;for(int i=-1;i<=1;i++)c.drawCircle(getWidth()/2f+i*7*d,getHeight()/2f,1.8f*d,paint);}
 @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
}
