// Spring integrator, ported from liquid_design 0.4.3 (`lib/src/glass_spring.dart`),
// MIT, Copyright (c) 2026 Mohammed Al-Jaf.
//

// Damped spring used by the motion layers.
package com.cairn.manager.redesign

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class JellySpring @JvmOverloads constructor(
    private val stiffness: Double,
    private val damping: Double,
    private val mass: Double = 1.0,
) {
    
    var value: Float = 0f
        private set

    var velocity: Float = 0f
        private set

    private var target = 0f
    private var virtualTime = 0.0
    private var lastTime = 0.0

    fun position(): Float = value

    fun speed(): Float = velocity

    fun moveTo(to: Float) {
        target = to
        
        virtualTime = 0.0
        lastTime = 0.0
    }

    fun snap(to: Float) {
        value = to
        target = to
        velocity = 0f
    }

    fun advance(dtSeconds: Double): Boolean {
        if (dtSeconds <= 0.0) return !atRest()
        virtualTime += min(dtSeconds, MAX_STEP)
        val t = virtualTime - lastTime
        lastTime = virtualTime
        if (t <= 0.0) return !atRest()

        val x0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val omega0 = sqrt(stiffness)
        val zeta = damping / (2.0 * sqrt(stiffness * mass))

        val x: Double
        val v: Double
        if (abs(zeta - 1.0) < 1e-4) {
            
            val c1 = x0
            val c2 = v0 + omega0 * x0
            val e = Math.exp(-omega0 * t)
            x = (c1 + c2 * t) * e
            v = (c2 - omega0 * (c1 + c2 * t)) * e
        } else if (zeta < 1.0) {
            
            val omegaD = omega0 * sqrt(1.0 - zeta * zeta)
            val c1 = x0
            val c2 = (v0 + zeta * omega0 * x0) / omegaD
            val e = Math.exp(-zeta * omega0 * t)
            val cos = Math.cos(omegaD * t)
            val sin = Math.sin(omegaD * t)
            x = e * (c1 * cos + c2 * sin)
            v = e * (-zeta * omega0 * (c1 * cos + c2 * sin) + omegaD * (-c1 * sin + c2 * cos))
        } else {
            
            val gamma = omega0 * sqrt(zeta * zeta - 1.0)
            val r1 = -zeta * omega0 + gamma
            val r2 = -zeta * omega0 - gamma
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            val e1 = Math.exp(r1 * t)
            val e2 = Math.exp(r2 * t)
            x = c1 * e1 + c2 * e2
            v = c1 * r1 * e1 + c2 * r2 * e2
        }

        value = (x + target).toFloat()
        velocity = v.toFloat()
        if (atRest()) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }

    private fun atRest(): Boolean =
        abs(value - target) < 0.001f && abs(velocity) < 0.5f

    companion object {
        
        const val MAX_STEP: Double = 1.0 / 30.0

        const val STIFFNESS_PRESS = 420.0
        const val DAMPING_PRESS = 26.0
        const val STIFFNESS_RELEASE = 240.0
        const val DAMPING_RELEASE = 13.0
        const val STIFFNESS_FOLLOW = 520.0
        const val DAMPING_FOLLOW = 34.0
        const val STIFFNESS_FADE = 180.0
        const val DAMPING_FADE = 27.0
        
        const val STIFFNESS_SWITCH = 380.0
        const val DAMPING_SWITCH = 24.0
        
        const val STIFFNESS_LIFT = 500.0
        const val DAMPING_LIFT = 30.0
    }
}
