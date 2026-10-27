package xie.fa.gram.helpers.icons

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import org.telegram.messenger.ApplicationLoader

object IconHelper {
    @JvmStatic
    fun getMappedResId(resId: Int): Int {
        return resId
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @JvmStatic
    fun getDrawable(context: Context?, resId: Int): Drawable? {
        if (resId == 0) return null
        val ctx = context ?: ApplicationLoader.applicationContext
        return loadDrawable(ctx, resId)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadDrawable(ctx: Context, id: Int): Drawable? {
        if (id == 0) return null
        return try {
            ContextCompat.getDrawable(ctx, id)
        } catch (e: Exception) {
            try {
                ctx.resources.getDrawable(id, ctx.theme)
            } catch (e2: Exception) {
                null
            }
        }
    }
}
