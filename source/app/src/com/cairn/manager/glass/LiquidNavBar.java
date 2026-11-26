// Floating bottom navigation bar.
package com.cairn.manager.glass;
import android.content.Context;
import com.cairn.manager.ui.LiquidNavigation;

public class LiquidNavBar extends LiquidNavigation {
 public interface OnTabSelectedListener { void onTabSelected(int index); }
 public LiquidNavBar(Context c){super(c);}
 public LiquidNavBar setOnTabSelectedListener(OnTabSelectedListener l){setSelectionListener(l==null?null:i->l.onTabSelected(i));return this;}
 public LiquidNavBar selectTab(int i,boolean animate){setSelectedIndex(i);return this;}
 public void setItemAlpha(int i,float alpha){}
 public static float recommendedHeightDp(){return 64f;}
}
