package de.feineflammen.rotg02viewer

class FrameSink(
    private val callback: (ByteArray, Long) -> Unit
) {
    fun onJpegFrame(data: ByteArray, timestampNs: Long) {
        callback(data, timestampNs)
    }
}

object NativeUvcStream {
    init {
        System.loadLibrary("rotg02native")
    }

    external fun prepareStream()
    external fun runStream(fileDescriptor: Int, sink: FrameSink): String
    external fun stopStream()
}
