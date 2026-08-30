package com.anas.applocker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.IOException

/**
 * Lets the user pin a home-screen shortcut with a custom name + custom icon picked from
 * gallery/storage, backed by ShortcutManagerCompat so it works from API 24 all the way to
 * 34+ (the compat library itself is a no-op fallback below the OS's real pinning support,
 * so calling this on very old OEM launchers just silently does nothing instead of crashing).
 *
 * Every bitmap is safely downsampled to a fixed 128x128px BEFORE a full Bitmap object is
 * ever created, which is what prevents an OutOfMemoryError on 1GB-RAM devices when the
 * user picks a large (e.g. 12MP camera) photo as their icon.
 */
object CustomShortcutHelper {

    private const val TARGET_SIZE_PX = 128

    /**
     * Reads [imageUri], downsamples it to a 128x128 circular bitmap, and requests a pinned
     * shortcut labeled [label] that re-opens the app (MainActivity) when tapped.
     *
     * Returns true if the pin request was made (the OS still shows its own confirmation
     * dialog to the user - this only means the request itself didn't fail).
     */
    fun requestCustomShortcut(context: Context, imageUri: Uri, label: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val safeLabel = label.trim().ifBlank { "Writify" }.take(30)
        val circularBitmap = decodeSampledCircularBitmap(context, imageUri) ?: return false

        val shortcutIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val shortcutId = "custom_shortcut_${System.currentTimeMillis()}"
        val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(safeLabel)
            .setLongLabel(safeLabel)
            .setIcon(IconCompat.createWithBitmap(circularBitmap))
            .setIntent(shortcutIntent)
            .build()

        return try {
            ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Two-pass decode: first with inJustDecodeBounds=true (reads only the header, allocates
     * no pixel data) to compute inSampleSize, then a real decode already shrunk close to
     * 128x128 - never the full-resolution bitmap is ever created in memory.
     */
    private fun decodeSampledCircularBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            boundsOptions.inSampleSize = calculateInSampleSize(boundsOptions, TARGET_SIZE_PX, TARGET_SIZE_PX)
            boundsOptions.inJustDecodeBounds = false
            boundsOptions.inPreferredConfig = Bitmap.Config.ARGB_8888

            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val squared = cropToSquare(sampledBitmap)
            val scaled = if (squared.width != TARGET_SIZE_PX) {
                Bitmap.createScaledBitmap(squared, TARGET_SIZE_PX, TARGET_SIZE_PX, true)
            } else squared

            toCircularBitmap(scaled)
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun cropToSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    private fun toCircularBitmap(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, source.width, source.height)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, rect, rect, paint)
        return output
    }
}
