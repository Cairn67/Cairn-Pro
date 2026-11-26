// Jelly press: the public entry point every Cairn Pro view uses.
//

// Copyright (c) 2026 Mohammed Al-Jaf. Upstream is Flutter/Dart, so this is a

// Entry point for attaching the press engine to a view.
package com.cairn.manager.redesign

import android.view.View

object RedesignPress {
    
    @JvmStatic
    @JvmOverloads
    fun install(view: View, strength: Float = 1f, dark: Boolean = false): JellyPress {
        val engine = JellyPress.install(view)
        engine.strength(strength).dark(dark).reduceMotion(!android.animation.ValueAnimator.areAnimatorsEnabled())
        return engine
    }

    @JvmStatic
    fun of(view: View): JellyPress? = JellyPress.of(view)
}

internal fun View.addPressMotion(strength: Float = 1f, dark: Boolean = false): JellyPress =
    RedesignPress.install(this, strength, dark)
