package app.apexview

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Picks which frames a sped-up feed shows, evenly, so it hands the screen no more frames a
 * second than at normal speed. Tiles draw their pictures through the app (so they can overlap),
 * and the app redraws only so many times a second: three feeds at 1.5× outran it, and the
 * player then threw frames away wherever it fell behind, which looked choppy. Skipping the
 * same share of frames every time keeps the motion steady.
 */
internal class FrameThinner {
    private var lastPtsUs = C.TIME_UNSET
    private var nextDueUs = C.TIME_UNSET
    /** The stream's own time between frames, learnt from the frames themselves. */
    private var intervalUs = 0L

    fun reset() {
        lastPtsUs = C.TIME_UNSET
        nextDueUs = C.TIME_UNSET
        intervalUs = 0L
    }

    /** Whether to show the frame at [ptsUs] while playing at [speed]. */
    fun keep(ptsUs: Long, speed: Float): Boolean {
        val last = lastPtsUs
        lastPtsUs = ptsUs
        if (last != C.TIME_UNSET && ptsUs < last) {
            // Went back (a seek, or a new stream): start over.
            nextDueUs = C.TIME_UNSET
        } else if (last != C.TIME_UNSET) {
            val gap = ptsUs - last
            if (gap in 1..MAX_INTERVAL_US && (intervalUs == 0L || gap < intervalUs)) intervalUs = gap
        }
        val due = nextDueUs
        // The sync's small nudges around normal speed don't need any skipping.
        if (speed <= THIN_ABOVE || intervalUs == 0L || due == C.TIME_UNSET) {
            nextDueUs = ptsUs + (intervalUs * speed).toLong()
            return true
        }
        if (ptsUs < due - intervalUs / 2) return false
        val step = (intervalUs * speed).toLong()
        // After a gap (frames the player skipped itself), count on from this one.
        nextDueUs = if (due + step <= ptsUs) ptsUs + step else due + step
        return true
    }

    private companion object {
        const val THIN_ABOVE = 1.15f
        const val MAX_INTERVAL_US = 100_000L
    }
}

/** The usual video renderer, showing only the frames [FrameThinner] keeps. */
@OptIn(UnstableApi::class)
private class ThinningVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningMs: Long,
    fallback: Boolean,
    handler: Handler?,
    listener: VideoRendererEventListener?,
) : MediaCodecVideoRenderer(
    context, DefaultMediaCodecAdapterFactory(context), selector, joiningMs, fallback, handler, listener,
    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
) {
    private val thinner = FrameThinner()

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        thinner.reset()
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        if (thinner.keep(presentationTimeUs, playbackSpeed)) super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
        else skipOutputBuffer(codec, index, presentationTimeUs)
    }

    @Deprecated("Deprecated in Java")
    override fun renderOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        if (thinner.keep(presentationTimeUs, playbackSpeed)) @Suppress("DEPRECATION") super.renderOutputBuffer(codec, index, presentationTimeUs)
        else skipOutputBuffer(codec, index, presentationTimeUs)
    }
}

/** The usual renderers, with the video one thinning frames when sped up. */
@OptIn(UnstableApi::class)
internal class ThinningRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(ThinningVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener))
    }
}
