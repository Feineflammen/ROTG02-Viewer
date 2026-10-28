package de.feineflammen.rotg02viewer

class FrameSink(
    private val callback: (ByteArray, Long) -> Unit
) {
    fun onFrame(data: ByteArray, timestampNs: Long) {
        callback(data, timestampNs)
    }
}

object NativeUvcStream {
    init {
        System.loadLibrary("rotg02native")
    }

    external fun prepareStream()

    /**
     * Runs the stream until [stopStream] or a device error. Blocks the calling thread.
     * See [UvcStreamInfo.nativeConfig] for the layout of [config].
     */
    external fun runStream(fileDescriptor: Int, sink: FrameSink, config: IntArray): String

    external fun stopStream()
}
