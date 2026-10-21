package de.feineflammen.rotg02viewer

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {
    companion object {
        private const val TAG = "ROTG02Viewer"
        private const val ACTION_USB_PERMISSION = "de.feineflammen.rotg02viewer.USB_PERMISSION"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val ROTG02_VID = 0x18EC
        private const val ROTG02_PID = 0x5850
        private const val USB_CLASS_VIDEO = 0x0E
        private const val UVC_SUBCLASS_VIDEOSTREAMING = 0x02
    }

    private data class Frame(val jpeg: ByteArray, val timestampNs: Long)

    private lateinit var usbManager: UsbManager
    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var recordButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val recordFinalizeExecutor = Executors.newSingleThreadExecutor()
    private val frameQueue = ArrayBlockingQueue<Frame>(2)
    private val frameProcessorAlive = AtomicBoolean(true)
    private val receivedFrames = AtomicLong(0)
    private val shownFrames = AtomicLong(0)
    private val streamStateLock = Any()
    private val pendingPermissionIds = mutableSetOf<Int>()

    @Volatile
    private var foreground = false

    @Volatile
    private var streamRunning = false

    @Volatile
    private var activeDeviceId = -1

    @Volatile
    private var recorder: Mp4Recorder? = null

    @Volatile
    private var recording = false

    private var currentBitmap: Bitmap? = null
    private var lastProcessedFrameNs = 0L
    private var cameraPermissionRequested = false
    private var receiversRegistered = false
    private var usbWatchRunning = false
    private var lastUsbFingerprint = ""
    private var lastStatsShown = 0L

    private val frameSink = FrameSink { data, timestampNs ->
        receivedFrames.incrementAndGet()
        val frame = Frame(data, timestampNs)
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()
            frameQueue.offer(frame)
        }
    }

    private val usbWatch = object : Runnable {
        override fun run() {
            if (!usbWatchRunning) return
            val fingerprint = currentUsbFingerprint()
            if (fingerprint != lastUsbFingerprint) {
                lastUsbFingerprint = fingerprint
                scanAndConnect("Device-List")
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val statsWatch = object : Runnable {
        override fun run() {
            if (streamRunning) {
                val shown = shownFrames.get()
                val fps = shown - lastStatsShown
                lastStatsShown = shown
                val rec = if (recording) "  •  REC" else ""
                setStatus("LIVE  640×480  •  ${fps} fps${rec}")
            } else {
                lastStatsShown = shownFrames.get()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val device = intent.usbDeviceExtra()
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    mainHandler.postDelayed({ scanAndConnect("USB-ATTACH") }, 150)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device == null || device.deviceId == activeDeviceId) {
                        stopRecording("USB getrennt")
                        stopStreaming()
                        setStatus("ROTG02 getrennt")
                    }
                    mainHandler.postDelayed({ scanAndConnect("USB-DETACH") }, 250)
                }
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != ACTION_USB_PERMISSION) return

            var device = intent.usbDeviceExtra()
            if (device == null) {
                val ids = synchronized(pendingPermissionIds) { pendingPermissionIds.toSet() }
                device = usbManager.deviceList.values.firstOrNull { it.deviceId in ids }
            }

            if (device == null) {
                setStatus("USB-Freigabe konnte nicht zugeordnet werden")
                return
            }

            synchronized(pendingPermissionIds) {
                pendingPermissionIds.remove(device.deviceId)
            }

            if (usbManager.hasPermission(device)) {
                startStream(device)
            } else {
                setStatus("USB-Zugriff nicht freigegeben")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        registerUsbReceivers()

        frameExecutor.execute { frameLoop() }
        mainHandler.post(statsWatch)

        setStatus("ROTG02 Viewer ${BuildConfig.VERSION_NAME}  •  bereit")
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!usbWatchRunning) {
            usbWatchRunning = true
            lastUsbFingerprint = currentUsbFingerprint()
            mainHandler.post(usbWatch)
        }
        ensureCameraPermissionThenScan()
    }

    override fun onPause() {
        foreground = false
        usbWatchRunning = false
        mainHandler.removeCallbacks(usbWatch)
        stopRecording("App im Hintergrund")
        stopStreaming()
        super.onPause()
    }

    override fun onDestroy() {
        foreground = false
        usbWatchRunning = false
        mainHandler.removeCallbacks(usbWatch)
        mainHandler.removeCallbacks(statsWatch)

        stopStreaming()
        stopRecording("App beendet")

        frameProcessorAlive.set(false)
        frameExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        recordFinalizeExecutor.shutdown()

        if (receiversRegistered) {
            try {
                unregisterReceiver(usbEventReceiver)
            } catch (_: Throwable) {
            }
            try {
                unregisterReceiver(permissionReceiver)
            } catch (_: Throwable) {
            }
            receiversRegistered = false
        }

        currentBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentBitmap = null

        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        imageView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ROTG02 Livebild"
        }
        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        recordButton = Button(this).apply {
            text = "REC"
            isEnabled = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(70, 70, 70))
            setOnClickListener { toggleRecording() }
        }
        val recordParams = FrameLayout.LayoutParams(dp(120), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        recordParams.bottomMargin = dp(18)
        root.addView(recordButton, recordParams)

        setContentView(root)
    }

    private fun ensureCameraPermissionThenScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanAndConnect("onResume")
            return
        }

        setStatus("Kamera-Berechtigung wird benötigt")
        if (!cameraPermissionRequested) {
            cameraPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        cameraPermissionRequested = false

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted) {
            scanAndConnect("CAMERA-Permission")
        } else {
            setStatus("Kamera-Berechtigung fehlt")
        }
    }

    private fun registerUsbReceivers() {
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbEventReceiver, usbFilter)

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, permissionFilter)
        }
        receiversRegistered = true
    }

    private fun scanAndConnect(reason: String) {
        if (!foreground) return

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraPermissionThenScan()
            return
        }

        val device = usbManager.deviceList.values.firstOrNull { isRotg02(it) }
        if (device == null) {
            if (!streamRunning) {
                setStatus("ROTG02 nicht verbunden")
                recordButton.isEnabled = false
            }
            return
        }

        Log.d(TAG, "scanAndConnect($reason): id=${device.deviceId}, permission=${usbManager.hasPermission(device)}")

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        startStream(device)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val shouldRequest = synchronized(pendingPermissionIds) {
            if (pendingPermissionIds.contains(device.deviceId)) {
                false
            } else {
                pendingPermissionIds.add(device.deviceId)
                true
            }
        }
        if (!shouldRequest) return

        setStatus("USB-Zugriff freigeben")

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            usbManager.requestPermission(device, pendingIntent)
        } catch (t: Throwable) {
            synchronized(pendingPermissionIds) {
                pendingPermissionIds.remove(device.deviceId)
            }
            setStatus("USB-Freigabe fehlgeschlagen")
            Log.e(TAG, "requestPermission", t)
        }
    }

    private fun startStream(device: UsbDevice) {
        if (!foreground || !usbManager.hasPermission(device)) return

        synchronized(streamStateLock) {
            if (streamRunning) {
                if (activeDeviceId == device.deviceId) return
                NativeUvcStream.stopStream()
                mainHandler.postDelayed({ scanAndConnect("Streamwechsel") }, 500)
                return
            }
            streamRunning = true
            activeDeviceId = device.deviceId
        }

        setStatus("ROTG02 verbindet …")
        recordButton.isEnabled = false
        frameQueue.clear()
        lastProcessedFrameNs = 0L

        streamExecutor.execute {
            var connection: UsbDeviceConnection? = null
            var streamingInterface: UsbInterface? = null
            var claimed = false
            val deviceId = device.deviceId

            try {
                if (!foreground || activeDeviceId != deviceId) return@execute

                connection = usbManager.openDevice(device)
                if (connection == null) {
                    throw IllegalStateException("openDevice() lieferte null")
                }

                streamingInterface = findStreamingInterface(device)
                    ?: throw IllegalStateException("VideoStreaming Interface 1/alt0 fehlt")

                claimed = connection.claimInterface(streamingInterface, true)
                if (!claimed) {
                    throw IllegalStateException("claimInterface(if=1, force=true) fehlgeschlagen")
                }

                if (!foreground || activeDeviceId != deviceId) return@execute

                val summary = NativeUvcStream.runStream(connection.fileDescriptor, frameSink)
                Log.i(TAG, summary)
            } catch (t: Throwable) {
                Log.e(TAG, "Streamfehler", t)
                mainHandler.post {
                    if (foreground) setStatus("Streamfehler: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                if (claimed && connection != null && streamingInterface != null) {
                    try {
                        connection.releaseInterface(streamingInterface)
                    } catch (_: Throwable) {
                    }
                }
                try {
                    connection?.close()
                } catch (_: Throwable) {
                }

                synchronized(streamStateLock) {
                    if (activeDeviceId == deviceId) activeDeviceId = -1
                    streamRunning = false
                }

                mainHandler.post {
                    recordButton.isEnabled = false
                    if (recording) stopRecording("Stream beendet")
                    if (foreground) {
                        mainHandler.postDelayed({ scanAndConnect("Stream-Ende") }, 750)
                    }
                }
            }
        }
    }

    private fun stopStreaming() {
        synchronized(streamStateLock) {
            activeDeviceId = -1
        }
        NativeUvcStream.stopStream()
    }

    private fun findStreamingInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (
                intf.id == 1 &&
                intf.alternateSetting == 0 &&
                intf.interfaceClass == USB_CLASS_VIDEO &&
                intf.interfaceSubclass == UVC_SUBCLASS_VIDEOSTREAMING
            ) {
                return intf
            }
        }
        return null
    }

    private fun frameLoop() {
        while (frameProcessorAlive.get()) {
            val frame = try {
                frameQueue.take()
            } catch (_: InterruptedException) {
                break
            }

            if (
                lastProcessedFrameNs != 0L &&
                frame.timestampNs - lastProcessedFrameNs < 25_000_000L
            ) {
                continue
            }
            lastProcessedFrameNs = frame.timestampNs

            val bitmap = try {
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
            } catch (_: Throwable) {
                null
            } ?: continue

            val activeRecorder = recorder
            if (activeRecorder != null) {
                try {
                    activeRecorder.writeFrame(bitmap, frame.timestampNs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Aufnahmefehler", t)
                    if (recorder === activeRecorder) {
                        recorder = null
                        recording = false
                    }
                    try {
                        activeRecorder.abort()
                    } catch (_: Throwable) {
                    }
                    mainHandler.post {
                        updateRecordButton(false)
                        Toast.makeText(
                            this,
                            "Aufnahmefehler: ${t.message ?: t.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            mainHandler.post {
                presentBitmap(bitmap)
            }
        }
    }

    private fun presentBitmap(bitmap: Bitmap) {
        val old = currentBitmap
        currentBitmap = bitmap
        imageView.setImageBitmap(bitmap)
        shownFrames.incrementAndGet()
        recordButton.isEnabled = streamRunning

        if (old != null && old !== bitmap) {
            mainHandler.postDelayed({
                if (currentBitmap !== old && !old.isRecycled) {
                    old.recycle()
                }
            }, 500)
        }
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording("STOP")
            return
        }

        if (!streamRunning || currentBitmap == null) {
            Toast.makeText(this, "Noch kein Livebild", Toast.LENGTH_SHORT).show()
            return
        }

        val newRecorder = Mp4Recorder(this)
        try {
            val target = newRecorder.start()
            recorder = newRecorder
            recording = true
            updateRecordButton(true)
            Toast.makeText(this, "REC → $target", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            try {
                newRecorder.abort()
            } catch (_: Throwable) {
            }
            Toast.makeText(
                this,
                "REC konnte nicht starten: ${t.message ?: t.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRecording(reason: String) {
        val activeRecorder = recorder ?: return
        recorder = null
        recording = false
        updateRecordButton(false)

        Log.d(TAG, "stopRecording: $reason")
        recordFinalizeExecutor.execute {
            val saved = try {
                activeRecorder.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Recorder stop", t)
                null
            }

            mainHandler.post {
                if (saved != null) {
                    Toast.makeText(this, "Gespeichert: $saved", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Aufnahme konnte nicht gespeichert werden", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateRecordButton(active: Boolean) {
        recordButton.text = if (active) "STOP" else "REC"
        recordButton.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.rgb(190, 30, 30) else Color.rgb(70, 70, 70)
        )
    }

    private fun setStatus(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusView.text = text
        } else {
            mainHandler.post { statusView.text = text }
        }
    }

    private fun isRotg02(device: UsbDevice): Boolean =
        device.vendorId == ROTG02_VID && device.productId == ROTG02_PID

    private fun currentUsbFingerprint(): String =
        usbManager.deviceList.values
            .map { "${it.deviceId}:${it.vendorId}:${it.productId}:${it.deviceName}" }
            .sorted()
            .joinToString("|")

    private fun Intent.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
