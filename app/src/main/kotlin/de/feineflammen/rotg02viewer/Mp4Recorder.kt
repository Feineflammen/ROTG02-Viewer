package de.feineflammen.rotg02viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Mp4Recorder(
    private val context: Context,
    private val width: Int = 640,
    private val height: Int = 480,
    private val nominalFps: Int = 30
) {
    private data class Target(
        val muxer: MediaMuxer,
        val uri: Uri?,
        val pfd: ParcelFileDescriptor?,
        val file: File?,
        val label: String
    )

    private var codec: MediaCodec? = null
    private var target: Target? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var firstTimestampNs = Long.MIN_VALUE
    private var lastPtsUs = -1L
    private var queuedFrames = 0L
    private var pixels = IntArray(width * height)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun start(): String {
        check(codec == null) { "Recorder is already running" }

        var createdCodec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    (width.toLong() * height * nominalFps / 10).coerceIn(2_500_000L, 20_000_000L).toInt()
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, nominalFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            createdCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            createdCodec.start()

            val createdTarget = createTarget()
            codec = createdCodec
            target = createdTarget
            muxerStarted = false
            trackIndex = -1
            firstTimestampNs = Long.MIN_VALUE
            lastPtsUs = -1L
            queuedFrames = 0L
            return createdTarget.label
        } catch (t: Throwable) {
            try {
                createdCodec?.stop()
            } catch (_: Throwable) {
            }
            try {
                createdCodec?.release()
            } catch (_: Throwable) {
            }
            cleanupTarget(delete = true)
            codec = null
            throw t
        }
    }

    @Synchronized
    fun writeFrame(bitmap: Bitmap, timestampNs: Long): Boolean {
        val c = codec ?: return false
        if (bitmap.width != width || bitmap.height != height) {
            throw IllegalArgumentException(
                "Unexpected frame size ${bitmap.width}x${bitmap.height}, expected ${width}x${height}"
            )
        }

        drainEncoder(endOfStream = false)

        val inputIndex = c.dequeueInputBuffer(8_000)
        if (inputIndex < 0) return false

        val inputCapacity = c.getInputBuffer(inputIndex)?.capacity()
            ?: (width * height * 3 / 2)

        val image = c.getInputImage(inputIndex)
        if (image == null) {
            c.queueInputBuffer(inputIndex, 0, 0, nextPtsUs(timestampNs), 0)
            throw IllegalStateException("H.264 encoder did not provide a YUV420 input image")
        }

        try {
            fillYuv420(image, bitmap)
        } finally {
            image.close()
        }

        val ptsUs = nextPtsUs(timestampNs)
        c.queueInputBuffer(inputIndex, 0, inputCapacity, ptsUs, 0)
        queuedFrames++
        drainEncoder(endOfStream = false)
        return true
    }

    @Synchronized
    fun stop(): String? {
        val c = codec ?: return null
        val savedLabel = target?.label

        var success = queuedFrames > 0
        try {
            if (queuedFrames > 0) {
                signalEndOfStream(c)
                drainEncoder(endOfStream = true)
            }
        } catch (_: Throwable) {
            success = false
        } finally {
            releaseCodecAndMuxer()
            finishTarget(success)
            codec = null
        }
        return if (success) savedLabel else null
    }

    @Synchronized
    fun abort() {
        if (codec == null && target == null) return
        releaseCodecAndMuxer()
        finishTarget(success = false)
        codec = null
    }

    private fun nextPtsUs(timestampNs: Long): Long {
        if (firstTimestampNs == Long.MIN_VALUE) {
            firstTimestampNs = timestampNs
        }
        var pts = ((timestampNs - firstTimestampNs).coerceAtLeast(0L)) / 1_000L
        if (pts <= lastPtsUs) pts = lastPtsUs + 1L
        lastPtsUs = pts
        return pts
    }

    private fun signalEndOfStream(c: MediaCodec) {
        var attempts = 0
        while (attempts < 50) {
            val index = c.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val pts = lastPtsUs.coerceAtLeast(0L) + 1_000_000L / nominalFps
                c.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainEncoder(endOfStream = false)
            attempts++
        }
        throw IllegalStateException("No encoder input buffer available for EOS")
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val m = target?.muxer ?: return
        var idle = 0

        while (true) {
            val outputIndex = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idle++
                    if (idle > 100) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException("Encoder output format changed twice")
                    }
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }

                outputIndex >= 0 -> {
                    val outputBuffer = c.getOutputBuffer(outputIndex)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (!muxerStarted || outputBuffer == null) {
                            c.releaseOutputBuffer(outputIndex, false)
                            throw IllegalStateException("Encoder produced data before muxer start")
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }

                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    c.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                    idle = 0
                }
            }
        }
    }

    private fun fillYuv420(image: Image, bitmap: Bitmap) {
        if (pixels.size != width * height) pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planes = image.planes
        if (planes.size < 3) throw IllegalStateException("YUV420 image has fewer than three planes")

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        for (y in 0 until height) {
            val yRow = yBase + y * yPlane.rowStride
            val pixelRow = y * width
            for (x in 0 until width) {
                val color = pixels[pixelRow + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val yy = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val index = yRow + x * yPlane.pixelStride
                if (index < yBuffer.limit()) yBuffer.put(index, yy.toByte())
            }
        }

        var cy = 0
        var y = 0
        while (y < height) {
            val uRow = uBase + cy * uPlane.rowStride
            val vRow = vBase + cy * vPlane.rowStride
            var cx = 0
            var x = 0
            while (x < width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (dy in 0..1) {
                    val py = y + dy
                    if (py >= height) continue
                    for (dx in 0..1) {
                        val px = x + dx
                        if (px >= width) continue
                        val color = pixels[py * width + px]
                        sumR += (color shr 16) and 0xff
                        sumG += (color shr 8) and 0xff
                        sumB += color and 0xff
                        count++
                    }
                }

                val r = sumR / count
                val g = sumG / count
                val b = sumB / count
                val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)

                val ui = uRow + cx * uPlane.pixelStride
                val vi = vRow + cx * vPlane.pixelStride
                if (ui < uBuffer.limit()) uBuffer.put(ui, uu.toByte())
                if (vi < vBuffer.limit()) vBuffer.put(vi, vv.toByte())

                cx++
                x += 2
            }
            cy++
            y += 2
        }
    }

    private fun createTarget(): Target {
        val name = "ROTG02-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            ".mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/ROTG02"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore could not create a video file")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: run {
                    resolver.delete(uri, null, null)
                    throw IllegalStateException("MediaStore file could not be opened")
                }
            return try {
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                Target(muxer, uri, pfd, null, "Movies/ROTG02/$name")
            } catch (t: Throwable) {
                pfd.close()
                resolver.delete(uri, null, null)
                throw t
            }
        }

        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, "ROTG02")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Recording directory could not be created")
        }
        val file = File(dir, name)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return Target(muxer, null, null, file, file.absolutePath)
    }

    private fun releaseCodecAndMuxer() {
        val c = codec
        codec = null
        if (c != null) {
            try {
                c.stop()
            } catch (_: Throwable) {
            }
            try {
                c.release()
            } catch (_: Throwable) {
            }
        }

        val m = target?.muxer
        if (m != null) {
            if (muxerStarted) {
                try {
                    m.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                m.release()
            } catch (_: Throwable) {
            }
        }
        muxerStarted = false
        trackIndex = -1
    }

    private fun finishTarget(success: Boolean) {
        val t = target ?: return
        target = null

        try {
            t.pfd?.close()
        } catch (_: Throwable) {
        }

        if (t.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.update(t.uri, values, null, null)
                } catch (_: Throwable) {
                }
            } else {
                try {
                    context.contentResolver.delete(t.uri, null, null)
                } catch (_: Throwable) {
                }
            }
        } else if (!success) {
            try {
                t.file?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun cleanupTarget(delete: Boolean) {
        val t = target ?: return
        target = null
        try {
            t.muxer.release()
        } catch (_: Throwable) {
        }
        try {
            t.pfd?.close()
        } catch (_: Throwable) {
        }
        if (delete) {
            try {
                if (t.uri != null) context.contentResolver.delete(t.uri, null, null)
                else t.file?.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
