# ROTG02 Viewer

An Android viewer for USB Video Class (UVC) cameras, built around the **Eachine ROTG02** USB receiver (`VID:PID 18EC:5850`).

Since 2.0.0 the app also works with other UVC cameras that stream MJPEG or YUY2, for example cheap USB industrial cameras. It uses its own small native UVC/USBFS transport instead of a general-purpose UVC framework.

## Download

Every build on `main` is published as a pre-release with the APK attached. Release assets download without a GitHub login:

- [Releases](../../releases)

The APK is signed with the debug/test key (see [Signing note](#signing-note)). Play Protect asks to scan it on first install because the app is not from the Play Store.

## Features

- automatic camera detection and reconnect; the ROTG02 is preferred when several cameras are connected
- formats, resolutions and frame rates read from the camera's USB descriptors
- MJPEG and uncompressed YUY2
- isochronous and bulk transfer cameras
- **MODE**: choose the resolution, remembered per camera
- **PHOTO**: saves a JPEG to `Pictures/ROTG02`; for MJPEG cameras the camera's own JPEG is stored unchanged
- **REC / STOP**: H.264 MP4 recording without audio, stored under `Movies/ROTG02`
- portrait layout with buttons at the bottom, landscape layout with buttons at the right edge
- rotation follows the sensor inside the app, even with auto-rotate off
- tap the status line for camera details and the last stream log
- English and German UI
- no ads, no internet permission, no microphone permission, no broad storage permission on modern Android versions

## Tested hardware

- Eachine ROTG02 (640×480 MJPEG, isochronous)
- generic CS-mount USB industrial camera (MJPEG up to 1920×1080, isochronous)
- Samsung SM-A166B, Android 16 / API 36

Reference descriptors of the ROTG02:

- UVC 1.0, VideoStreaming interface 1
- MJPEG format index 1, frame index 1: 640×480 (frame index 2: 352×288)
- isochronous IN endpoint `0x83`, alternate setting 11 with 3000 bytes per high-speed microframe

On the Samsung host controller the ROTG02 only accepts isochronous URBs of eight packets; larger URBs are refused with `ENOMEM`. The transport therefore tries 32, 16 and 8 packets per URB and keeps the first size the controller accepts.

## How a stream is set up

1. Kotlin reads the raw configuration descriptors (`UsbDeviceConnection.getRawDescriptors()`) and parses the VideoControl header, the VideoStreaming format and frame descriptors, and the endpoints of every alternate setting.
2. The selected mode (format index, frame index, default frame interval) is passed to native code.
3. Native code runs UVC PROBE/COMMIT (26, 34 or 48 bytes depending on the UVC version, with a fallback to 26), then picks the smallest alternate setting whose packet size covers the negotiated payload size.
4. Isochronous or bulk URBs are queued through Linux USBFS and the UVC payload headers are parsed.

## Frame handling

- a FID change starts a new frame; frames are closed on EOF or on the FID change (many cameras never set EOF)
- packet, UVC-header and oversize errors invalidate the current frame
- MJPEG frames must contain JPEG SOI and EOI; trailing zero padding after EOI is accepted
- YUY2 frames must have exactly width × height × 2 bytes
- discarded frames leave the last valid image on screen

The viewer processes about 30 frames per second for display and recording. The ROTG02 negotiates 30 fps but has been observed to deliver about 60.

## USB architecture

Android/Kotlin handles device detection, camera and USB permissions, `openDevice()`, `claimInterface(..., true)`, descriptor parsing, lifecycle and reconnect, MJPEG/YUY2 decoding, UI, photos and recording.

Native C++/JNI handles UVC PROBE/COMMIT, alternate-setting selection, USBFS isochronous and bulk URBs, UVC payload parsing and frame assembly.

The video path does **not** depend on AUSBC, UVCAndroid, libuvc or libusb.

## Recording

1. live frame → Bitmap (`BitmapFactory` for MJPEG, own converter for YUY2)
2. Bitmap → YUV420
3. YUV420 → H.264/AVC with `MediaCodec`, bitrate scaled to the resolution
4. H.264 → MP4 with `MediaMuxer`

No audio track is created.

## Permissions

Requested:

- `android.permission.CAMERA`, required by Android for USB video-class access on current target SDKs
- USB host feature

Not requested: `INTERNET`, `RECORD_AUDIO`, location, contacts, SMS, package installation, broad external-storage access.

## Build

GitHub Actions builds an optimized release variant from scratch using:

- Android API 36
- Android Gradle Plugin 9.4.0
- Gradle 9.6
- JDK 17
- NDK r29
- CMake 3.22.1
- ABI: arm64-v8a

The workflow disables the Gradle build cache, reruns all tasks, verifies the APK signature, checks package/version metadata, audits permissions, inspects native dependencies/imports for unexpected network or process-control functionality, uploads an audited artifact and publishes the APK as a pre-release.

Current version: **2.0.0**

### Signing note

CI test APKs are signed with Android's test/debug signing configuration while being built as non-debuggable release variants. This is suitable for direct testing, but **not** a long-term public release-signing strategy.

Before publishing official binary releases, configure a stable private release key through GitHub Secrets or another protected signing system.

## Build locally

```bash
gradle :app:assembleRelease
```

The GitHub Actions workflow is the reference build path.

## Limitations

- H.264 and other frame-based camera formats are not supported
- only the first VideoStreaming interface with a supported format is used

## Version history

See [CHANGELOG.md](CHANGELOG.md). Version 1.1.0, the ROTG02-only viewer, is tagged as `v1.1.0`.
