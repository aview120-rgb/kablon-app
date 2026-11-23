package com.kablon.automation.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** קבצי פלט, שמירה לגלריה (כדי ש-TikTok יראה אותם) ושיתוף לאפליקציות אחרות. */
object MediaFiles {
    const val ALBUM = "Kablon"

    fun outputDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "output").apply { mkdirs() }

    fun newOutputFile(context: Context, ext: String, suffix: String = ""): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outputDir(context), "kablon_${ts}$suffix.$ext")
    }

    fun saveBitmap(bmp: Bitmap, file: File, quality: Int = 95): File {
        FileOutputStream(file).use { out ->
            val fmt = if (file.extension.equals("png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            bmp.compress(fmt, quality, out)
        }
        return file
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: "").length()
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun mimeOf(context: Context, uri: Uri): String {
        context.contentResolver.getType(uri)?.let { return it }
        val p = uri.toString().lowercase()
        return when {
            p.endsWith(".mp4") || p.endsWith(".mov") || p.endsWith(".webm") -> "video/mp4"
            p.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
    }

    /** מעתיק קובץ פלט לגלריה (Pictures/Kablon או Movies/Kablon). מחזיר את הנתיב הציבורי. */
    fun copyToGallery(context: Context, file: File, mime: String): String {
        val isVideo = mime.startsWith("video")
        val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$ALBUM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(collection, values) ?: throw IllegalStateException("שמירה לגלריה נכשלה")
            cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            @Suppress("DEPRECATION")
            return File(Environment.getExternalStorageDirectory(), "$baseDir/$ALBUM/${file.name}").absolutePath
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), ALBUM).apply { mkdirs() }
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return dest.absolutePath
        }
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Intent שיתוף ישיר לאפליקציה מסוימת (TikTok / ChatGPT / Gemini...). */
    fun shareIntent(context: Context, file: File, mime: String, pkg: String?, text: String? = null): Intent {
        val uri = shareUri(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri("media", uri)
        }
    }
}
