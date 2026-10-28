package de.feineflammen.rotg02viewer

import java.util.Locale

/** One selectable video mode (a UVC frame descriptor of a supported format). */
data class UvcFrameMode(
    val formatIndex: Int,
    val frameIndex: Int,
    val kind: Int,
    val width: Int,
    val height: Int,
    val defaultInterval: Int,
    val intervals: List<Int>,
    val maxFrameBytes: Int
) {
    val key: String get() = "$formatIndex:$frameIndex"
    val kindName: String get() = if (kind == KIND_MJPEG) "MJPEG" else "YUY2"
    val fps: Double get() = if (defaultInterval > 0) 10_000_000.0 / defaultInterval else 0.0
    val sizeLabel: String get() = "$width×$height"

    fun label(): String = String.format(Locale.ROOT, "%d×%d  %s  %.0f fps", width, height, kindName, fps)

    companion object {
        const val KIND_MJPEG = 0
        const val KIND_YUY2 = 1
    }
}

/** An isochronous alternate setting of the VideoStreaming interface. */
data class UvcAlt(val alt: Int, val endpoint: Int, val packetBytes: Int)

data class UvcStreamInfo(
    val interfaceNumber: Int,
    val bcdUvc: Int,
    val modes: List<UvcFrameMode>,
    val alts: List<UvcAlt>,
    val bulkEndpoint: Int,
    val bulkPacketBytes: Int,
    val unsupportedFormats: List<String>
) {
    val isBulk: Boolean get() = alts.isEmpty() && bulkEndpoint != 0
    val usable: Boolean get() = modes.isNotEmpty() && (alts.isNotEmpty() || bulkEndpoint != 0)

    val probeLength: Int
        get() = when {
            bcdUvc >= 0x0150 -> 48
            bcdUvc >= 0x0110 -> 34
            else -> 26
        }

    /** Layout must match runStream() in rotg02_native.cpp. */
    fun nativeConfig(mode: UvcFrameMode): IntArray {
        val rawBytes = mode.width * mode.height * 2
        val maxFrame = maxOf(mode.maxFrameBytes, rawBytes)
        val head = intArrayOf(
            interfaceNumber,
            mode.formatIndex,
            mode.frameIndex,
            mode.defaultInterval,
            probeLength,
            maxFrame,
            mode.kind,
            if (mode.kind == UvcFrameMode.KIND_YUY2) rawBytes else 0,
            if (isBulk) 1 else 0,
            bulkEndpoint,
            bulkPacketBytes
        )
        val tail = IntArray(alts.size * 3)
        alts.forEachIndexed { i, a ->
            tail[i * 3] = a.alt
            tail[i * 3 + 1] = a.endpoint
            tail[i * 3 + 2] = a.packetBytes
        }
        return head + tail
    }

    /** Sensible default: MJPEG up to 1080p, otherwise the smallest YUY2 mode ≥ 640×480. */
    fun defaultMode(): UvcFrameMode? {
        val mjpeg = modes.filter { it.kind == UvcFrameMode.KIND_MJPEG }
        mjpeg.filter { it.width * it.height <= 1920 * 1080 }
            .maxByOrNull { it.width * it.height }
            ?.let { return it }
        mjpeg.minByOrNull { it.width * it.height }?.let { return it }
        val yuy2 = modes.filter { it.kind == UvcFrameMode.KIND_YUY2 }
        return yuy2.filter { it.width * it.height >= 640 * 480 }.minByOrNull { it.width * it.height }
            ?: yuy2.maxByOrNull { it.width * it.height }
    }

    fun describe(): String = buildString {
        append(String.format(Locale.ROOT, "UVC %x.%02x, interface %d, %s\n",
            bcdUvc shr 8, bcdUvc and 0xff, interfaceNumber,
            if (isBulk) "bulk" else "isochronous"))
        if (isBulk) {
            append(String.format(Locale.ROOT, "Bulk endpoint 0x%02x, %d bytes\n", bulkEndpoint, bulkPacketBytes))
        } else {
            append("Alternates: ")
            append(alts.joinToString { "${it.alt}=${it.packetBytes}" })
            append('\n')
        }
        modes.forEach { append("  ").append(it.label()).append('\n') }
        unsupportedFormats.forEach { append("  (unsupported: ").append(it).append(")\n") }
    }
}

object UvcDescriptors {
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val CS_INTERFACE = 0x24
    private const val CLASS_VIDEO = 0x0E
    private const val SC_VIDEOCONTROL = 0x01
    private const val SC_VIDEOSTREAMING = 0x02

    private const val VC_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FORMAT_FRAME_BASED = 0x10
    private const val VS_FORMAT_H264 = 0x13

    private class Builder(val interfaceNumber: Int) {
        val modes = mutableListOf<UvcFrameMode>()
        val alts = mutableListOf<UvcAlt>()
        val unsupported = mutableListOf<String>()
        var bulkEndpoint = 0
        var bulkPacketBytes = 0
    }

    /** Parses the raw configuration descriptors from UsbDeviceConnection.getRawDescriptors(). */
    fun parse(raw: ByteArray): List<UvcStreamInfo> {
        val builders = linkedMapOf<Int, Builder>()
        var bcdUvc = 0x0100

        var ifNum = -1
        var ifAlt = 0
        var ifClass = -1
        var ifSub = -1
        var formatKind = -1   // kind of the most recent format descriptor, -1 = unsupported
        var formatIndex = 0

        var i = 0
        while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(raw, i + 1)

            when (type) {
                DT_INTERFACE -> if (len >= 9) {
                    ifNum = u8(raw, i + 2)
                    ifAlt = u8(raw, i + 3)
                    ifClass = u8(raw, i + 5)
                    ifSub = u8(raw, i + 6)
                    if (ifClass == CLASS_VIDEO && ifSub == SC_VIDEOSTREAMING) {
                        builders.getOrPut(ifNum) { Builder(ifNum) }
                    }
                }

                CS_INTERFACE -> if (ifClass == CLASS_VIDEO && len >= 3) {
                    val subtype = u8(raw, i + 2)
                    if (ifSub == SC_VIDEOCONTROL && subtype == VC_HEADER && len >= 5) {
                        bcdUvc = u16(raw, i + 3)
                    } else if (ifSub == SC_VIDEOSTREAMING) {
                        val b = builders[ifNum]
                        when (subtype) {
                            VS_FORMAT_MJPEG -> {
                                formatIndex = u8(raw, i + 3)
                                formatKind = UvcFrameMode.KIND_MJPEG
                            }
                            VS_FORMAT_UNCOMPRESSED -> if (len >= 21) {
                                formatIndex = u8(raw, i + 3)
                                val fourcc = String(raw, i + 5, 4, Charsets.US_ASCII)
                                formatKind = if (fourcc == "YUY2") UvcFrameMode.KIND_YUY2 else -1
                                if (formatKind < 0) b?.unsupported?.add("uncompressed $fourcc")
                            }
                            VS_FORMAT_FRAME_BASED -> {
                                formatKind = -1
                                val fourcc = if (len >= 9) String(raw, i + 5, 4, Charsets.US_ASCII) else "?"
                                b?.unsupported?.add("frame-based $fourcc")
                            }
                            VS_FORMAT_H264 -> {
                                formatKind = -1
                                b?.unsupported?.add("H.264")
                            }
                            VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED -> {
                                val matches = (subtype == VS_FRAME_MJPEG && formatKind == UvcFrameMode.KIND_MJPEG) ||
                                    (subtype == VS_FRAME_UNCOMPRESSED && formatKind == UvcFrameMode.KIND_YUY2)
                                if (b != null && matches && len >= 26) {
                                    b.modes += parseFrame(raw, i, len, formatIndex, formatKind)
                                }
                            }
                        }
                    }
                }

                DT_ENDPOINT -> if (len >= 7 && ifClass == CLASS_VIDEO && ifSub == SC_VIDEOSTREAMING) {
                    val b = builders[ifNum]
                    val address = u8(raw, i + 2)
                    val attributes = u8(raw, i + 3) and 0x03
                    val wMax = u16(raw, i + 4)
                    val bytes = (wMax and 0x7ff) * (1 + ((wMax shr 11) and 0x03))
                    if (b != null && (address and 0x80) != 0) {
                        if (attributes == 1 && bytes > 0) {
                            b.alts += UvcAlt(ifAlt, address, bytes)
                        } else if (attributes == 2 && ifAlt == 0) {
                            b.bulkEndpoint = address
                            b.bulkPacketBytes = wMax and 0x7ff
                        }
                    }
                }
            }
            i += len
        }

        return builders.values.map { b ->
            UvcStreamInfo(
                interfaceNumber = b.interfaceNumber,
                bcdUvc = bcdUvc,
                modes = b.modes.sortedWith(compareBy({ it.kind }, { -it.width * it.height })),
                alts = b.alts.sortedBy { it.packetBytes },
                bulkEndpoint = b.bulkEndpoint,
                bulkPacketBytes = b.bulkPacketBytes,
                unsupportedFormats = b.unsupported
            )
        }
    }

    private fun parseFrame(raw: ByteArray, i: Int, len: Int, formatIndex: Int, kind: Int): UvcFrameMode {
        val frameIndex = u8(raw, i + 3)
        val width = u16(raw, i + 5)
        val height = u16(raw, i + 7)
        val maxBuffer = u32(raw, i + 17)
        var defaultInterval = u32(raw, i + 21)
        val intervalType = u8(raw, i + 25)
        val intervals = mutableListOf<Int>()
        if (intervalType == 0) {
            if (len >= 38) {
                intervals += u32(raw, i + 26)
                intervals += u32(raw, i + 30)
            }
        } else {
            for (k in 0 until intervalType) {
                val off = i + 26 + 4 * k
                if (off + 4 > i + len) break
                intervals += u32(raw, off)
            }
        }
        if (defaultInterval <= 0) defaultInterval = intervals.firstOrNull { it > 0 } ?: 333_333
        return UvcFrameMode(formatIndex, frameIndex, kind, width, height, defaultInterval, intervals, maxBuffer)
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int) =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
}
