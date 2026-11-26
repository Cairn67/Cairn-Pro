// Shared motion constants and animation helpers.
package com.cairn.manager;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

final class UiMotion {
    static final Interpolator SETTLE = new PathInterpolator(.20f, .76f, .26f, 1f);
    private static final Interpolator DISMISS = new PathInterpolator(.40f, 0f, 1f, 1f);
    private static final Interpolator SOFT_SPRING = new PathInterpolator(.25f, .77f, .33f, 1.12f);

    private UiMotion() {}

    static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }

    private static float dp(View v, float distance) {
        return distance * v.getResources().getDisplayMetrics().density;
    }

    static void reset(View v) {
        v.animate().cancel();
        v.animate().setStartDelay(0L);
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    static void pageIn(View page, int logicalDirection) {
        reset(page);
        if (!enabled()) return;
        int physicalDirection = page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? -logicalDirection : logicalDirection;
        page.setAlpha(.50f);
        page.setTranslationX(dp(page, 16f) * physicalDirection);
        page.setTranslationY(dp(page, 6f));
        page.animate().alpha(1f).translationX(0f).translationY(0f)
                .setDuration(260L).setInterpolator(SETTLE).start();
    }

    static void revealSections(View... sections) {
        if (!enabled() || sections == null || sections.length == 0) return;
        int animationIndex = 0;
        for (View section : sections) {
            if (section == null) continue;
            reset(section);
            section.setAlpha(.15f);
            section.setTranslationY(dp(section, 10f + 2f * animationIndex));
            section.animate().alpha(1f).translationY(0f).setStartDelay(animationIndex * 36L)
                    .setDuration(320L).setInterpolator(SETTLE).start();
            animationIndex++;
        }
    }

    static void statusIn(View... lines) {
        if (!enabled()) return;
        for (int i = 0; i < lines.length; i++) {
            View line = lines[i];
            reset(line);
            if (line.getVisibility() != View.VISIBLE) continue;
            line.setAlpha(.60f);
            line.setTranslationY(dp(line, 5f));
            line.animate().alpha(1f).translationY(0f).setStartDelay(i * 24L)
                    .setDuration(250L).setInterpolator(SETTLE).start();
        }
    }

    static void settleMark(View mark) {
        if (!enabled()) { reset(mark); return; }
        mark.animate().cancel();
        mark.setScaleX(.90f);
        mark.setScaleY(.90f);
        mark.animate().scaleX(1f).scaleY(1f).setStartDelay(0L)
                .setDuration(330L).setInterpolator(SOFT_SPRING).start();
    }

    static void enter(View v) {
        reset(v);
        if (!enabled()) return;
        v.setAlpha(.15f);
        v.setTranslationY(dp(v, 14f));
        v.setScaleX(.97f);
        v.setScaleY(.97f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(290L).setInterpolator(SETTLE).start();
    }

    static void exit(View v, Runnable end) {
        v.animate().cancel();
        if (!enabled()) { end.run(); return; }
        v.animate().alpha(0f).translationY(dp(v, 8f)).scaleX(.98f).scaleY(.98f)
                .setStartDelay(0L).setDuration(155L).setInterpolator(DISMISS)
                .withEndAction(end).start();
    }
}
