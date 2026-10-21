# Changelog

## 1.1.0

Public-readiness and UI cleanup.

- added English default UI and German localization
- moved all visible UI text into Android string resources
- moved the live-status overlay below the system status bar
- moved the REC/STOP control above the navigation bar
- reduced and rounded the status and record controls
- translated repository-facing diagnostics and comments to English
- rewrote the README for public use
- kept the audited release-build pipeline from 1.0.1/1.0.2

## 1.0.2

Frame-integrity filtering.

- UVC FID changes define new frame boundaries
- UVC EOF closes the current frame
- incomplete frames are discarded
- packet, UVC-header, UVC error-flag, and oversize failures invalidate the current frame
- JPEG SOI and EOI must be present inside one UVC frame
- discarded frames leave the last valid image on screen

## 1.0.1

Build and packaging hardening.

- switched CI from a debug variant to an optimized non-debuggable release variant
- enabled R8 and resource shrinking
- disabled the Gradle build cache for audited builds
- added APK signature, package/version, permission, and native-import audits
- added SHA-256 generation in the same workflow

## 1.0.0

Initial complete viewer.

- automatic ROTG02 detection
- custom USBFS/JNI isochronous UVC transport
- 640×480 MJPEG live view
- reconnect lifecycle
- H.264 MP4 recording without audio
