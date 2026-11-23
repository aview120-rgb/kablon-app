package com.kablon.automation.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.kablon.automation.core.Prefs
import java.io.File

data class OverlayOptions(
    val caption: String,
    val captionAtTop: Boolean,
    val captionSizePct: Int,
    /** 0=למעלה-ימין 1=למעלה-שמאל 2=למטה-ימין 3=למטה-שמאל */
    val logoCorner: Int,
    val logoScalePct: Int,
    val logoMarginPct: Int,
) {
    companion object {
        fun from(prefs: Prefs, caption: String) = OverlayOptions(
            caption, prefs.captionAtTop, prefs.captionSizePct,
            prefs.logoCorner, prefs.logoScalePct, prefs.logoMarginPct,
        )
    }
}

/** הוספת לוגו קבוע בפינה + כיתוב (תומך בעברית/RTL) על תמונה או על שכבת וידאו. */
object MediaComposer {

    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val w = info.size.width; val h = info.size.height
                val m = maxOf(w, h)
                if (m > maxDim) {
                    val s = maxDim.toFloat() / m
                    decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalArgumentException("לא ניתן לפענח את התמונה")
        }
        return if (bmp.isMutable && bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun loadLogo(prefs: Prefs): Bitmap? {
        val p = prefs.logoPath
        if (p.isBlank() || !File(p).exists()) return null
        return BitmapFactory.decodeFile(p)
    }

    /** מצייר לוגו וכיתוב על Canvas בגודל w×h. */
    fun drawOverlays(canvas: Canvas, w: Int, h: Int, logo: Bitmap?, o: OverlayOptions) {
        val minSide = minOf(w, h)
        val margin = minSide * o.logoMarginPct / 100f

        // ---- לוגו ----
        if (logo != null) {
            val lw = w * o.logoScalePct / 100f
            val lh = lw * logo.height / logo.width
            val left = if (o.logoCorner == 1 || o.logoCorner == 3) margin else w - margin - lw
            val top = if (o.logoCorner == 0 || o.logoCorner == 1) margin else h - margin - lh
            canvas.drawBitmap(logo, null, RectF(left, top, left + lw, top + lh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }

        // ---- כיתוב ----
        val text = o.caption.trim()
        if (text.isNotEmpty()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = minSide * o.captionSizePct / 100f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(textSize / 8f, 0f, textSize / 16f, Color.BLACK)
            }
            val boxW = (w * 0.9f).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, boxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.05f)
                .setMaxLines(6)
                .build()
            val pad = tp.textSize * 0.35f
            val boxH = layout.height + pad * 2
            val x = (w - boxW) / 2f
            val y = if (o.captionAtTop) h * 0.08f else h - h * 0.12f - boxH
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
            canvas.drawRoundRect(RectF(x - pad, y, x + boxW + pad, y + boxH), pad, pad, bgPaint)
            canvas.save()
            canvas.translate(x, y + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    fun composeImage(src: Bitmap, logo: Bitmap?, o: OverlayOptions): Bitmap {
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        drawOverlays(Canvas(out), out.width, out.height, logo, o)
        return out
    }

    /** שכבה שקופה בגודל פריים הווידאו, עם לוגו וכיתוב, לשימוש כ-Overlay ב-Media3. */
    fun buildOverlayFrame(w: Int, h: Int, logo: Bitmap?, o: OverlayOptions): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        drawOverlays(Canvas(bmp), w, h, logo, o)
        return bmp
    }
}
