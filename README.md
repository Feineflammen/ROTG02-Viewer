# ROTG02 Viewer

A focused Android FPV viewer for the **Eachine ROTG02** USB receiver (`VID:PID 18EC:5850`).

The app uses a small native UVC/USB isochronous transport written specifically for the ROTG02 instead of a general-purpose UVC framework.

## Features

- automatic ROTG02 detection and reconnect
- 640×480 MJPEG live video
- portrait and landscape layouts
- English and German UI
- frame-integrity filtering using UVC FID/EOF boundaries
- REC / STOP
- H.264 MP4 recording without audio
- recordings stored under `Movies/ROTG02` on Android 10+
- no ads
- no internet permission
- no microphone permission
- no broad storage permission on modern Android versions

## Tested hardware

The USB transport and live view have been validated on:

- Eachine ROTG02
- Samsung SM-A166B
- Android 16 / API 36

The ROTG02 descriptors used by the transport are:

- UVC 1.0
- VideoStreaming interface 1
- MJPEG format index 1
- frame index 1: 640×480
- isochronous IN endpoint `0x83`
- alternate setting 11
- 3000 bytes per high-speed microframe
- eight parallel USBFS URBs with eight microframes each

A 10-second transport diagnostic completed 80,000 isochronous packets with zero packet errors, zero invalid UVC headers, and zero UVC error flags.

## Frame handling

The receiver negotiates a nominal 30 fps stream but has been observed to deliver roughly 60 complete MJPEG frames per second.

The viewer intentionally processes about 30 frames per second for display and recording.

MJPEG frames are accepted only when they remain inside one consistent UVC frame boundary:

- a FID change starts a new UVC frame
- EOF closes the current UVC frame
- incomplete frames are discarded
- packet, UVC-header, and oversize errors invalidate the current frame
- JPEG SOI and EOI must both be present inside the same UVC frame

When a frame is discarded, the last valid image remains on screen until the next valid frame arrives.

## USB architecture

Android/Kotlin handles:

- device detection
- camera permission
- USB permission
- `UsbDeviceConnection.openDevice()`
- `claimInterface(..., true)`
- lifecycle and reconnect logic
- MJPEG decoding
- UI
- recording

Native C++/JNI handles:

- UVC PROBE / COMMIT
- alternate-setting selection
- Linux USBFS isochronous URBs
- UVC payload parsing
- MJPEG frame assembly

The video path does **not** depend on AUSBC, UVCAndroid, libuvc, or libusb.

## Recording

Live MJPEG frames are decoded with Android's `BitmapFactory`.

For recording:

1. Bitmap → YUV420
2. YUV420 → H.264/AVC using `MediaCodec`
3. H.264 → MP4 using `MediaMuxer`

No audio track is created.

## Permissions

Requested:

- `android.permission.CAMERA` — required by Android for USB video-class access on current target SDKs
- USB host feature

Not requested:

- `INTERNET`
- `RECORD_AUDIO`
- location
- contacts
- SMS
- package installation
- broad external-storage access

## Build

GitHub Actions builds an optimized release variant from scratch using:

- Android API 36
- Android Gradle Plugin 9.4.0
- Gradle 9.6
- JDK 17
- NDK r29
- CMake 3.22.1
- ABI: arm64-v8a

The CI workflow disables the Gradle build cache, reruns all tasks, verifies the APK signature, checks package/version metadata, audits permissions, and inspects native dependencies/imports for unexpected network or process-control functionality.

Current version: **1.1.0**

CI artifact:

- `ROTG02-Viewer-v1.1.0-release-audited`

### Signing note

CI test APKs are currently signed with Android's test/debug signing configuration while being built as non-debuggable release variants. This is suitable for direct testing, but **not** a long-term public release-signing strategy.

Before publishing official binary releases, configure a stable private release key through GitHub Secrets or another protected signing system.

## Build locally

The project can be built with a compatible Android SDK/NDK setup:

```bash
gradle :app:assembleRelease
```

The GitHub Actions workflow is the reference build path used for audited test artifacts.

## Scope

This project intentionally targets the Eachine ROTG02 rather than attempting to be a generic Android UVC camera application.

Device constants and UVC negotiation values are therefore deliberately specific to this receiver.

## Version history

See [CHANGELOG.md](CHANGELOG.md).
