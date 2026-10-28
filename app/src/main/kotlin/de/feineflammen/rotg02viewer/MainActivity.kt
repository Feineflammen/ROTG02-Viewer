package de.feineflammen.rotg02viewer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val PREFS = "uvc_modes"
    }

    private data class Frame(val jpeg: ByteArray, val timestampNs: Long)

    private lateinit var usbManager: UsbManager
    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var recordButton: Button
    private lateinit var photoButton: Button
    private lateinit var modeButton: Button
    private lateinit var controlBar: LinearLayout

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

    @Volatile
    private var streamInfo: UvcStreamInfo? = null

    @Volatile
    private var streamMode: UvcFrameMode? = null

    @Volatile
    private var lastJpeg: ByteArray? = null

    @Volatile
    private var lastNativeLog = ""

    @Volatile
    private var deviceText = ""

    private var yuvPixels = IntArray(0)

    @Volatile
    private var unsupportedDeviceId = -1

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
                val rec = if (recording) getString(R.string.rec_suffix) else ""
                val size = streamMode?.sizeLabel ?: ""
                setStatus(getString(R.string.status_live, size, fps, rec))
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
                        stopRecording("USB detached")
                        stopStreaming()
                        setStatus(getString(R.string.status_disconnected))
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
                setStatus(getString(R.string.status_usb_permission_unmatched))
                return
            }

            synchronized(pendingPermissionIds) {
                pendingPermissionIds.remove(device.deviceId)
            }

            if (usbManager.hasPermission(device)) {
                startStream(device)
            } else {
                setStatus(getString(R.string.status_usb_permission_denied))
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

        setStatus(getString(R.string.status_ready, BuildConfig.VERSION_NAME))
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
        stopRecording("App backgrounded")
        stopStreaming()
        super.onPause()
    }

    override fun onDestroy() {
        foreground = false
        usbWatchRunning = false
        mainHandler.removeCallbacks(usbWatch)
        mainHandler.removeCallbacks(statsWatch)

        stopStreaming()
        stopRecording("App destroyed")

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
            contentDescription = getString(R.string.live_view_description)
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
            textSize = 14f
            setBackgroundResource(R.drawable.bg_status)
        }
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        }
        root.addView(statusView, statusParams)
        statusView.setOnClickListener { showInfoDialog() }

        fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_record_button)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(74, 74, 74))
            setOnClickListener { onClick() }
        }

        modeButton = barButton(getString(R.string.mode)) { showModeDialog() }.apply { isEnabled = false }
        photoButton = barButton(getString(R.string.photo)) { takePhoto() }.apply { isEnabled = false }
        recordButton = barButton(getString(R.string.rec)) { toggleRecording() }.apply { isEnabled = false }

        val bar = LinearLayout(this).apply {
            controlBar = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(modeButton, photoButton, recordButton).forEach { button ->
            bar.addView(button, LinearLayout.LayoutParams(dp(104), dp(48)).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            })
        }
        val recordParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        root.addView(bar, recordParams)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }

            (statusView.layoutParams as FrameLayout.LayoutParams).also {
                it.topMargin = topInset + dp(8)
                statusView.layoutParams = it
            }
            (controlBar.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottomInset + dp(16)
                controlBar.layoutParams = it
            }
            insets
        }

        setContentView(root)
        root.requestApplyInsets()
    }

    private fun ensureCameraPermissionThenScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanAndConnect("onResume")
            return
        }

        setStatus(getString(R.string.status_camera_permission_required))
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
            setStatus(getString(R.string.status_camera_permission_missing))
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

        val devices = usbManager.deviceList.values
        val device = devices.firstOrNull { isRotg02(it) }
            ?: devices.firstOrNull { hasVideoStreaming(it) }
        if (device == null) {
            if (!streamRunning) {
                setStatus(getString(R.string.status_not_connected))
                recordButton.isEnabled = false
            }
            return
        }

        Log.d(TAG, "scanAndConnect($reason): id=${device.deviceId}, permission=${usbManager.hasPermission(device)}")

        if (device.deviceId == unsupportedDeviceId) return

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

        setStatus(getString(R.string.status_usb_permission_required))

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
            setStatus(getString(R.string.status_usb_permission_failed))
            Log.e(TAG, "requestPermission", t)
        }
    }

    private fun startStream(device: UsbDevice) {
        if (!foreground || !usbManager.hasPermission(device)) return

        synchronized(streamStateLock) {
            if (streamRunning) {
                if (activeDeviceId == device.deviceId) return
                NativeUvcStream.stopStream()
                mainHandler.postDelayed({ scanAndConnect("stream switch") }, 500)
                return
            }
            streamRunning = true
            activeDeviceId = device.deviceId
        }

        setStatus(getString(R.string.status_connecting))
        recordButton.isEnabled = false
        frameQueue.clear()
        lastProcessedFrameNs = 0L

        streamExecutor.execute {
            var connection: UsbDeviceConnection? = null
            var streamingInterface: UsbInterface? = null
            var claimed = false
            var failed = false
            val deviceId = device.deviceId

            try {
                if (!foreground || activeDeviceId != deviceId) return@execute

                connection = usbManager.openDevice(device)
                if (connection == null) {
                    throw IllegalStateException("openDevice() returned null")
                }

                val raw = connection.rawDescriptors
                    ?: throw IllegalStateException("USB descriptors could not be read")
                val infos = UvcDescriptors.parse(raw)
                deviceText = describeDevice(device) + infos.joinToString("\n") { it.describe() }

                val info = infos.firstOrNull { it.usable }
                if (info == null) {
                    mainHandler.post { if (foreground) setStatus(getString(R.string.status_unsupported)) }
                    lastNativeLog = "No VideoStreaming interface with MJPEG or YUY2 and a usable endpoint."
                    unsupportedDeviceId = deviceId
                    return@execute
                }
                val mode = selectedMode(device, info)
                    ?: throw IllegalStateException("No supported video mode")
                streamInfo = info
                streamMode = mode
                lastJpeg = null
                mainHandler.post { modeButton.text = mode.sizeLabel; modeButton.isEnabled = true }

                streamingInterface = findStreamingInterface(device, info.interfaceNumber)
                    ?: throw IllegalStateException("VideoStreaming interface ${info.interfaceNumber}/alt0 not found")

                claimed = connection.claimInterface(streamingInterface, true)
                if (!claimed) {
                    throw IllegalStateException("claimInterface(if=${info.interfaceNumber}, force=true) failed")
                }

                NativeUvcStream.prepareStream()
                if (!foreground || activeDeviceId != deviceId) {
                    NativeUvcStream.stopStream()
                    return@execute
                }

                val summary = NativeUvcStream.runStream(
                    connection.fileDescriptor,
                    frameSink,
                    info.nativeConfig(mode)
                )
                lastNativeLog = summary
                Log.i(TAG, summary)
                if (!summary.contains("Stream ended")) {
                    failed = true
                    mainHandler.post { if (foreground) setStatus(getString(R.string.status_stream_error)) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Stream error", t)
                lastNativeLog = "Stream error: ${t.message ?: t.javaClass.simpleName}"
                failed = true
                mainHandler.post {
                    if (foreground) setStatus(getString(R.string.status_stream_error))
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
                    photoButton.isEnabled = false
                    if (recording) stopRecording("Stream ended")
                    if (foreground && unsupportedDeviceId != deviceId) {
                        mainHandler.postDelayed({ scanAndConnect("stream end") }, if (failed) 3000L else 750L)
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

    private fun findStreamingInterface(device: UsbDevice, interfaceNumber: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (
                intf.id == interfaceNumber &&
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

            val mode = streamMode ?: continue
            val bitmap = try {
                if (mode.kind == UvcFrameMode.KIND_YUY2) {
                    yuy2ToBitmap(frame.jpeg, mode.width, mode.height)
                } else {
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                }
            } catch (_: Throwable) {
                null
            } ?: continue
            if (mode.kind == UvcFrameMode.KIND_MJPEG) lastJpeg = frame.jpeg

            val activeRecorder = recorder
            if (activeRecorder != null) {
                try {
                    activeRecorder.writeFrame(bitmap, frame.timestampNs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Recording error", t)
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
                            getString(
                                R.string.toast_recording_error,
                                t.message ?: t.javaClass.simpleName
                            ),
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
        photoButton.isEnabled = streamRunning

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
            Toast.makeText(this, getString(R.string.toast_no_live_view), Toast.LENGTH_SHORT).show()
            return
        }

        val bmp = currentBitmap ?: return
        val fps = (streamMode?.fps ?: 30.0).toInt().coerceIn(5, 30)
        val newRecorder = Mp4Recorder(this, bmp.width, bmp.height, fps)
        try {
            val target = newRecorder.start()
            recorder = newRecorder
            recording = true
            updateRecordButton(true)
            Toast.makeText(this, getString(R.string.toast_recording_started, target), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            try {
                newRecorder.abort()
            } catch (_: Throwable) {
            }
            Toast.makeText(
                this,
                getString(
                    R.string.toast_recording_start_failed,
                    t.message ?: t.javaClass.simpleName
                ),
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
                Log.e(TAG, "Recorder stop failed", t)
                null
            }

            mainHandler.post {
                if (saved != null) {
                    Toast.makeText(this, getString(R.string.toast_saved, saved), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateRecordButton(active: Boolean) {
        recordButton.text = getString(if (active) R.string.stop else R.string.rec)
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

    private fun hasVideoStreaming(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val intf = device.getInterface(it)
            intf.interfaceClass == USB_CLASS_VIDEO && intf.interfaceSubclass == UVC_SUBCLASS_VIDEOSTREAMING
        }

    private fun prefKey(device: UsbDevice) = "%04x:%04x".format(device.vendorId, device.productId)

    private fun selectedMode(device: UsbDevice, info: UvcStreamInfo): UvcFrameMode? {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(prefKey(device), null)
        return info.modes.firstOrNull { it.key == saved } ?: info.defaultMode()
    }

    private fun describeDevice(device: UsbDevice): String = buildString {
        append("%04x:%04x".format(device.vendorId, device.productId))
        device.manufacturerName?.let { append("  ").append(it) }
        device.productName?.let { append("  ").append(it) }
        append('\n')
    }

    private fun showModeDialog() {
        val info = streamInfo ?: return
        val current = streamMode
        val modes = info.modes
        val labels = modes.map { it.label() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_title)
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                val chosen = modes[which]
                if (chosen == current) return@setSingleChoiceItems
                val device = usbManager.deviceList.values.firstOrNull { it.deviceId == activeDeviceId }
                if (device != null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(prefKey(device), chosen.key).apply()
                }
                stopRecording("Mode change")
                stopStreaming()
                setStatus(getString(R.string.status_connecting))
            }
            .show()
    }

    private fun showInfoDialog() {
        val text = buildString {
            append(deviceText.ifBlank { getString(R.string.info_no_device) })
            if (lastNativeLog.isNotBlank()) {
                append("\n").append(lastNativeLog)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.info_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun yuy2ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        val count = width * height
        if (data.size < count * 2) return null
        if (yuvPixels.size != count) yuvPixels = IntArray(count)
        val out = yuvPixels
        var src = 0
        var dst = 0
        while (dst < count) {
            val y0 = (data[src].toInt() and 0xff) - 16
            val u = (data[src + 1].toInt() and 0xff) - 128
            val y1 = (data[src + 2].toInt() and 0xff) - 16
            val v = (data[src + 3].toInt() and 0xff) - 128
            val rv = 409 * v
            val gv = -100 * u - 208 * v
            val bu = 516 * u
            out[dst] = yuvPixel(298 * y0, rv, gv, bu)
            out[dst + 1] = yuvPixel(298 * y1, rv, gv, bu)
            src += 4
            dst += 2
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun yuvPixel(y: Int, rv: Int, gv: Int, bu: Int): Int {
        val r = ((y + rv + 128) shr 8).coerceIn(0, 255)
        val g = ((y + gv + 128) shr 8).coerceIn(0, 255)
        val b = ((y + bu + 128) shr 8).coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun takePhoto() {
        val jpeg = lastJpeg
        val bitmap = currentBitmap
        if (jpeg == null && bitmap == null) {
            Toast.makeText(this, getString(R.string.toast_no_live_view), Toast.LENGTH_SHORT).show()
            return
        }
        // MJPEG: save the camera's own JPEG unchanged. YUY2: encode the current frame.
        val bytes = jpeg ?: run {
            val stream = java.io.ByteArrayOutputStream()
            bitmap!!.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            stream.toByteArray()
        }
        recordFinalizeExecutor.execute {
            val saved = try {
                savePhoto(bytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Photo save failed", t)
                null
            }
            mainHandler.post {
                val msg = if (saved != null) getString(R.string.toast_saved, saved)
                else getString(R.string.toast_photo_failed)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun savePhoto(bytes: ByteArray): String {
        val name = "IMG-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ROTG02")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore could not create an image")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Image could not be opened")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return "Pictures/ROTG02/$name"
        }
        val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val dir = File(base, "ROTG02").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
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
