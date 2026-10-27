package xie.fa.gram.helpers.icons

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.ContextCompat
import org.telegram.messenger.ApplicationLoader
import xie.fa.gram.InuConfig

object IconHelper {
    private const val TAG = "InuIconHelper"

    @JvmStatic
    fun getMappedResId(resId: Int): Int {
        if (resId == 0) return 0
        val pack = InuConfig.ICON_REPLACEMENT.value
        val mappedId = when (pack) {
            InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(resId)
            InuConfig.IconReplacementItem.VKUI -> VkIconPack.map(resId)
            else -> resId
        }

        if (pack != InuConfig.IconReplacementItem.OFF) {
            if (mappedId == 0) {
                Log.d(TAG, "Icon pack $pack returned 0 for resId 0x${Integer.toHexString(resId)}, falling back to base")
                return resId
            }
            if (mappedId == resId && pack == InuConfig.IconReplacementItem.SOLAR) {
                Log.d(TAG, "Solar pack has no mapping for resId 0x${Integer.toHexString(resId)}")
            }
        }
        return mappedId
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @JvmStatic
    fun getDrawable(context: Context?, resId: Int): Drawable? {
        if (resId == 0) return null
        val mappedId = getMappedResId(resId)
        val ctx = context ?: ApplicationLoader.applicationContext

        val drawable = loadDrawable(ctx, mappedId)
        if (drawable != null) {
            return drawable
        }

        if (mappedId != resId) {
            Log.d(TAG, "Failed to load mapped drawable 0x${Integer.toHexString(mappedId)} for base 0x${Integer.toHexString(resId)}, falling back to base")
            val fallback = loadDrawable(ctx, resId)
            if (fallback != null) {
                return fallback
            }
            Log.d(TAG, "Failed to load fallback base drawable 0x${Integer.toHexString(resId)}")
        } else {
            Log.d(TAG, "Failed to load drawable 0x${Integer.toHexString(resId)}")
        }

        return null
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
