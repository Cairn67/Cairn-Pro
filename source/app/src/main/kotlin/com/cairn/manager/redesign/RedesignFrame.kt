// Background shell: colour, saved image, and veil.
package com.cairn.manager.redesign

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

object RedesignFrame {
    private const val PREFS = "cairn_redesign"
    private const val BACKGROUND_URI = "background_uri"
    internal const val BACKGROUND_ID = 0x43_52_46_01

    @JvmStatic
    fun createBackground(context: Context): FrameLayout {
        val root = FrameLayout(context)
        root.setBackgroundColor(backgroundColor(context))

        val image = ImageView(context).apply {
            id = BACKGROUND_ID
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 1f
            loadSavedBackground(context)
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

        val veil = View(context).apply {
            setBackgroundColor(if (isDark(context)) Color.argb(100, 0, 0, 0) else Color.argb(110, 255, 255, 255))
            isClickable = false
        }
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))

        return root
    }

    @JvmStatic
    fun applyBackground(context: Context, root: View, uri: Uri?) {
        val image = root.findViewById<ImageView>(BACKGROUND_ID) ?: return
        if (uri == null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(BACKGROUND_URI).apply()
            image.setImageDrawable(null)
            RedesignFrost.refresh(root)
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BACKGROUND_URI, uri.toString()).apply()
        image.loadImage(uri)
        RedesignFrost.refresh(root)
    }

    private fun ImageView.loadSavedBackground(context: Context) {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BACKGROUND_URI, null) ?: return
        loadImage(Uri.parse(value))
    }

    private fun ImageView.loadImage(uri: Uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2560) {
                        decoder.setTargetSize((info.size.width.toLong() * 2560 / longest).toInt().coerceAtLeast(1),
                            (info.size.height.toLong() * 2560 / longest).toInt().coerceAtLeast(1))
                    }
                }
                setImageBitmap(bitmap)
            } else setImageURI(uri)
        } catch (_: Exception) { setImageDrawable(null) }
    }
    private fun backgroundColor(context: Context): Int = if (isDark(context)) Color.rgb(18, 20, 23) else Color.rgb(246, 247, 248)

    @JvmStatic
    fun pageBackground(context: Context): Int = backgroundColor(context)
    private fun isDark(context: Context): Boolean = (context.resources.configuration.uiMode and 0x30) == 0x20
    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
