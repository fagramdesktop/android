package xie.fa.gram.helpers.lastfm

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils

data class LastFmPalette(
    val color1: Int = 0xFF2D3446.toInt(),
    val color2: Int = 0xFF191E2A.toInt(),
    val accentColor: Int = 0xFFDC5064.toInt()
)

object LastFmPaletteHelper {

    val DEFAULT_PALETTE = LastFmPalette()

    /**
     * Extracts palette from album art cover bitmap matching desktop:
     * - Downsamples to 32x32 thumbnail
     * - Splits along the diagonal into two halves (top-left vs bottom-right)
     * - Averages RGB for each half, boosts/clamps in HSL space for color1 and color2
     * - Finds the maximum-saturation pixel for accentColor (fallback: #DC5064)
     */
    @JvmStatic
    fun extract(source: Bitmap?): LastFmPalette {
        if (source == null || source.isRecycled) {
            return DEFAULT_PALETTE
        }

        val thumb = try {
            Bitmap.createScaledBitmap(source, 32, 32, true)
        } catch (e: Exception) {
            return DEFAULT_PALETTE
        }

        var r1 = 0L; var g1 = 0L; var b1 = 0L; var count1 = 0
        var r2 = 0L; var g2 = 0L; var b2 = 0L; var count2 = 0

        var maxSat = -1f
        var maxSatColor = DEFAULT_PALETTE.accentColor
        val hsl = FloatArray(3)

        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val pixel = thumb.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                if (x + y < 31) {
                    r1 += r; g1 += g; b1 += b; count1++
                } else {
                    r2 += r; g2 += g; b2 += b; count2++
                }

                ColorUtils.colorToHSL(pixel, hsl)
                if (hsl[1] > maxSat && hsl[2] in 0.15f..0.85f) {
                    maxSat = hsl[1]
                    maxSatColor = pixel
                }
            }
        }

        if (thumb != source) {
            thumb.recycle()
        }

        val avg1 = if (count1 > 0) {
            ((0xFF shl 24) or ((r1 / count1).toInt() shl 16) or ((g1 / count1).toInt() shl 8) or (b1 / count1).toInt())
        } else DEFAULT_PALETTE.color1

        val avg2 = if (count2 > 0) {
            ((0xFF shl 24) or ((r2 / count2).toInt() shl 16) or ((g2 / count2).toInt() shl 8) or (b2 / count2).toInt())
        } else DEFAULT_PALETTE.color2

        val color1 = clampAndBoostBg(avg1, 0.18f, 0.26f)
        val color2 = clampAndBoostBg(avg2, 0.12f, 0.20f)

        val accent = if (maxSat >= 0.15f) {
            clampAndBoostAccent(maxSatColor)
        } else {
            DEFAULT_PALETTE.accentColor
        }

        return LastFmPalette(color1 = color1, color2 = color2, accentColor = accent)
    }

    private fun clampAndBoostBg(color: Int, minL: Float, maxL: Float): Int {
        val h = FloatArray(3)
        ColorUtils.colorToHSL(color, h)
        h[1] = (h[1] * 1.25f).coerceIn(0.18f, 0.60f)
        h[2] = h[2].coerceIn(minL, maxL)
        return ColorUtils.HSLToColor(h)
    }

    private fun clampAndBoostAccent(color: Int): Int {
        val h = FloatArray(3)
        ColorUtils.colorToHSL(color, h)
        h[1] = (h[1] * 1.3f).coerceIn(0.55f, 1.0f)
        h[2] = h[2].coerceIn(0.44f, 0.60f)
        return ColorUtils.HSLToColor(h)
    }
}
