// Horizontal pager between the main tabs.
package com.cairn.manager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class SwipeContent extends FrameLayout {
    private final IntSupplier selected;
    private final Supplier<View[]> pages;
    private final IntConsumer select;
    private final int slop, maxVelocity;
    private float downX, downY, dragStart, offset;
    private boolean eligible, dragging, committing;
    private View current, preview;
    private int destination = -1;
    private VelocityTracker velocity;
    private ValueAnimator settle;
    private boolean accepted;
    private final Consumer<Float> onProgress;

    SwipeContent(Context context, IntSupplier selected, Supplier<View[]> pages, IntConsumer select, Consumer<Float> onProgress) {
        super(context);
        this.selected = selected;
        this.pages = pages;
        this.select = select;
        this.onProgress = onProgress;
        ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop();
        maxVelocity = config.getScaledMaximumFlingVelocity();
    }

    boolean isCommitting() { return committing; }
    void resetSwipe() {
        if (settle != null) { settle.removeAllListeners(); settle.cancel(); settle = null; }
        if (preview != null) { preview.setVisibility(INVISIBLE); preview.setTranslationX(0); }
        if (current != null) {
            current.setTranslationX(0);
            com.cairn.manager.redesign.RedesignFrost.invalidateCards(current);
        }
        current = preview = null;
        accepted = false;
        onProgress.accept(Float.NaN);
        destination = -1;
        offset = 0;
        dragging = eligible = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null) {
                    if (accepted) settle.end(); 
                    else resetSwipe();
                }
                downX = event.getX(); downY = event.getY();
                float edge = 24 * getResources().getDisplayMetrics().density;
                eligible = downX > edge && downX < getWidth() - edge;
                if (findFocus() instanceof android.widget.EditText) eligible = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!eligible) return false;
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) eligible = false;
                if (Math.abs(dx) <= slop || Math.abs(dx) <= Math.abs(dy) * 1.4f) return false;
                current = pages.get()[selected.getAsInt()];
                UiMotion.reset(current);
                dragStart = downX + Math.copySign(slop, dx);
                velocity = VelocityTracker.obtain(); velocity.addMovement(event);
                dragging = true;
                move(event.getX() - dragStart);
                return true;
            default:
                eligible = false;
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragging && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            
            if (onInterceptTouchEvent(event)) return true;
        }
        if (!dragging) return true;
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: move(event.getX() - dragStart); break;
            case MotionEvent.ACTION_UP: move(event.getX() - dragStart); finish(false); break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN: finish(true); break;
        }
        return true;
    }

    private void move(float delta) {
        int direction = delta < 0 ? 1 : -1;
        int next = selected.getAsInt() + direction * (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1);
        View[] list = pages.get();
        if (next < 0 || next >= list.length) next = -1;
        if (next != destination) {
            if (preview != null) { preview.setVisibility(INVISIBLE); preview.setTranslationX(0); }
            destination = next;
            preview = next < 0 ? null : list[next];
            if (preview != null && UiMotion.enabled()) { UiMotion.reset(preview); preview.setVisibility(VISIBLE); }
        }
        if (UiMotion.enabled()) position(preview == null ? delta * .18f : Math.max(-getWidth(), Math.min(getWidth(), delta)));
        else offset = delta;
    }

    private void position(float value) {
        offset = value;
        current.setTranslationX(value);
        int rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1;
        if (preview != null) {
            int logical = destination > selected.getAsInt() ? 1 : -1;
            preview.setTranslationX(value + getWidth() * logical * rtl);
            com.cairn.manager.redesign.RedesignFrost.invalidateCards(preview);
        }
        com.cairn.manager.redesign.RedesignFrost.invalidateCards(current);
        onProgress.accept(preview == null ? (float) selected.getAsInt()
                : selected.getAsInt() - rtl * value / Math.max(1, getWidth()));
    }

    private void finish(boolean cancel) {
        dragging = false;
        velocity.computeCurrentVelocity(1000, maxVelocity);
        float vx = velocity.getXVelocity();
        float dp = getResources().getDisplayMetrics().density;
        boolean commit = !cancel && preview != null && (Math.abs(offset) > getWidth() * .23f
                || (Math.abs(offset) > 18 * dp && Math.signum(vx) == Math.signum(offset) && Math.abs(vx) > 650 * dp));
        accepted = commit;
        int target = destination;
        Runnable done = () -> {
            settle = null;
            if (commit) {
                committing = true;
                try { select.accept(target); } finally { committing = false; }
            } else resetSwipe();
        };
        if (!UiMotion.enabled()) { done.run(); return; }
        settle = ValueAnimator.ofFloat(offset, commit ? Math.copySign(getWidth(), offset) : 0f);
        float remaining = commit ? 1f - Math.abs(offset) / Math.max(1, getWidth())
                : Math.abs(offset) / Math.max(1, getWidth());
        settle.setDuration(90L + Math.round(Math.max(0f, remaining) * 145f));
        settle.setInterpolator(UiMotion.SETTLE);
        settle.addUpdateListener(a -> position((float) a.getAnimatedValue()));
        settle.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) { done.run(); }
        });
        settle.start();
    }

    @Override protected void onDetachedFromWindow() { resetSwipe(); super.onDetachedFromWindow(); }
}
