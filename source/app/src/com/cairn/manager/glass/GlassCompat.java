// Capability checks and fallbacks for the glass effects.
package com.cairn.manager.glass;

import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class GlassCompat {

    static final int TIER_REFRACTION = 3;
    
    static final int TIER_BLUR = 2;
    
    static final int TIER_SOLID = 1;

    private static int sTier = -1;

    private GlassCompat() {
    }

    public static int tier() {
        int t = sTier;
        if (t > 0) {
            return t;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            
            try {
                Class.forName("android.graphics.RuntimeShader");
                t = TIER_REFRACTION;
            } catch (Throwable e) {
                t = TIER_BLUR;
            }
        } else if (Build.VERSION.SDK_INT >= 31) {
            t = TIER_BLUR;
        } else {
            t = TIER_SOLID;
        }
        sTier = t;
        return t;
    }

    static boolean supportsRefraction() {
        return tier() >= TIER_REFRACTION;
    }

    static boolean supportsEffect() {
        return tier() >= TIER_BLUR;
    }

    static Object newShader(String agslSource) {
        if (!supportsRefraction()) {
            return null;
        }
        try {
            Class<?> cls = Class.forName("android.graphics.RuntimeShader");
            Constructor<?> ctor = cls.getConstructor(String.class);
            return ctor.newInstance(agslSource);
        } catch (Throwable e) {
            
            return null;
        }
    }

    static boolean setFloat(Object shader, String name, float value) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass().getMethod("setFloatUniform", String.class, float.class);
            m.invoke(shader, name, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean setFloat2(Object shader, String name, float x, float y) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setFloatUniform", String.class, float.class, float.class);
            m.invoke(shader, name, x, y);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean setFloat4(Object shader, String name, float x, float y, float z, float w) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setFloatUniform", String.class,
                            float.class, float.class, float.class, float.class);
            m.invoke(shader, name, x, y, z, w);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean setColor(Object shader, String name, float r, float g, float b, float a) {
        if (shader == null) {
            return false;
        }
        try {
            Method m = shader.getClass()
                    .getMethod("setColorUniform", String.class,
                            float.class, float.class, float.class, float.class);
            m.invoke(shader, name, r, g, b, a);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static Object newShaderEffect(Object shader, String shaderName) {
        if (shader == null) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createRuntimeShaderEffect", shader.getClass(), String.class);
            return m.invoke(null, shader, shaderName);
        } catch (Throwable e) {
            return null;
        }
    }

    static Object newColorControls(float brightness, float contrast, float saturation) {
        if (!supportsEffect()) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createColorFilterEffect",
                    Class.forName("android.graphics.ColorFilter"));
            Class<?> cf = Class.forName("android.graphics.ColorMatrixColorFilter");
            Constructor<?> ctor = cf.getConstructor(float[].class);

            float f4 = 1f - saturation;
            float f5 = 0.213f * f4;
            float f6 = 0.715f * f4;
            float f7 = 0.072f * f4;
            float f8 = ((0.5f - (contrast * 0.5f)) + brightness) * 255f;
            float f9 = contrast * f5;
            float f10 = contrast * f6;
            float f11 = contrast * f7;
            float f12 = contrast * saturation;

            float[] mat = {
                    f9 + f12, f10, f11, 0f, f8,
                    f9, f10 + f12, f11, 0f, f8,
                    f9, f10, f11 + f12, 0f, f8,
                    0f, 0f, 0f, 1f, 0f
            };
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(mat);
            Object filter = ctor.newInstance((Object) cm.getArray());
            return m.invoke(null, filter);
        } catch (Throwable e) {
            return null;
        }
    }

    static Object newVibrancy() {
        return newColorControls(0f, 1f, 1.5f);
    }

    static Object chain(Object inner, Object outer) {
        if (inner == null) {
            return outer;
        }
        if (outer == null) {
            return inner;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createChainEffect",
                    re, re);
            return m.invoke(null, outer, inner);
        } catch (Throwable e) {
            return outer;
        }
    }

    static Object newBlur(float radiusX, float radiusY) {
        if (!supportsEffect()) {
            return null;
        }
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Method m = re.getMethod("createBlurEffect",
                    float.class, float.class, Class.forName("android.graphics.Shader$TileMode"));
            Class<?> tileMode = Class.forName("android.graphics.Shader$TileMode");
            Object clamp = tileMode.getField("CLAMP").get(null);
            return m.invoke(null, radiusX, radiusY, clamp);
        } catch (Throwable e) {
            return null;
        }
    }

    static void applyToView(android.view.View view, Object effect) {
        if (view == null || !supportsEffect()) {
            return;
        }
        try {
            Method m = android.view.View.class.getMethod("setRenderEffect",
                    Class.forName("android.graphics.RenderEffect"));
            m.invoke(view, effect);
        } catch (Throwable e) {
        }
    }

    static Object newRenderNode(String name) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            return new android.graphics.RenderNode(name);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object newRenderNodeEffect(Object effect) {
        if (effect == null) {
            return null;
        }
        return effect;
    }

    static void setRenderEffectOnNode(Object node, Object effect) {
        if (node == null || effect == null || !supportsRefraction()) {
            return;
        }
        try {
            android.graphics.RenderNode rn = (android.graphics.RenderNode) node;
            rn.setRenderEffect((android.graphics.RenderEffect) effect);
        } catch (Throwable ignored) {
        }
    }
}
