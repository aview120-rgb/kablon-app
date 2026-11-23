package com.kablon.automation.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** עיבוד וידאו: הוספת לוגו + כיתוב לכל הפריימים בעזרת Media3 Transformer (חומרה, ללא ffmpeg). */
@UnstableApi
object VideoProcessor {

    data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

    fun info(context: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            return VideoInfo(w, h, d)
        } finally {
            r.release()
        }
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    suspend fun addOverlays(
        context: Context,
        input: Uri,
        output: File,
        logo: Bitmap?,
        options: OverlayOptions,
        onProgress: (String) -> Unit = {},
    ): Result<File> {
        val vi = withContext(Dispatchers.IO) { info(context, input) }
        val overlayBmp = withContext(Dispatchers.Default) {
            MediaComposer.buildOverlayFrame(vi.width, vi.height, logo, options)
        }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val overlay = BitmapOverlay.createStaticBitmapOverlay(overlayBmp)
                val effects = Effects(
                    ImmutableList.of<AudioProcessor>(),
                    ImmutableList.of<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))),
                )
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(input)).setEffects(effects).build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(output))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            output.delete()
                            if (cont.isActive) cont.resume(Result.failure(exportException))
                        }
                    })
                    .build()
                output.parentFile?.mkdirs()
                onProgress("מעבד וידאו ${vi.width}×${vi.height}…")
                transformer.start(edited, output.absolutePath)
                val main = Handler(Looper.getMainLooper())
                cont.invokeOnCancellation { main.post { transformer.cancel(); output.delete() } }
            }
        }
    }
}
