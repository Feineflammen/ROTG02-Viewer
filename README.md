# ROTG02 Viewer 1.0.0

Schlanker Android-FPV-Viewer für den Eachine ROTG02 (VID:PID 18EC:5850).

## Funktionen

- automatische ROTG02-Erkennung im laufenden Betrieb
- explizite Android-USB-Permission
- eigener nativer UVC/ISO-Transport über Linux usbfs/JNI
- 640×480 MJPEG
- Livebild in Hoch- und Querformat
- deterministisches Stoppen und Neuverbinden bei USB-Trennung sowie App-Hintergrund/Vordergrund
- REC/STOP
- MP4/H.264 ohne Ton
- Aufnahme unter `Movies/ROTG02` auf Android 10+
- keine Werbung
- keine INTERNET-Berechtigung
- keine Mikrofon-Berechtigung
- keine Storage-Berechtigung auf modernen Android-Versionen

## Technische Basis

Der USB-Transport wurde auf einem Samsung SM-A166B mit Android 16 / API 36 und
dem ROTG02 real vermessen und in `Feineflammen/diag` bestätigt:

- UVC 1.0
- VideoStreaming Interface 1
- MJPEG formatIndex 1 / frameIndex 1
- 640×480
- Endpoint 0x83 IN, ISOCHRONOUS
- Alternate Setting 11
- 3000 Byte pro High-Speed-Mikroframe
- 8 parallele URBs mit je 8 Mikroframe-Paketen
- 10-Sekunden-Test: 80.000 ISO-Pakete, 0 Paketfehler, 0 UVC-Fehler

Der ROTG02 meldet bei PROBE/GET_CUR 30 fps, liefert praktisch jedoch ungefähr
60 vollständige MJPEG-Frames/s. Der Viewer nimmt für Anzeige und Aufnahme
maximal ungefähr 30 Frames/s an, damit Decodierung und H.264-Encoding nicht
unnötig doppelt belastet werden.

## Aufnahme

MJPEG wird für das Livebild mit Androids BitmapFactory decodiert. Für REC wird
das Bild in YUV420 gewandelt und über Android MediaCodec als H.264/AVC encodiert.
MediaMuxer schreibt einen MP4-Container ohne Audiospur.

## Build

GitHub Actions baut mit:

- Android API 36
- AGP 9.4.0
- Gradle 9.6
- JDK 17
- NDK r29 (29.0.14206865)
- CMake 3.22.1
- ABI arm64-v8a

Artifact:

- `ROTG02-Viewer-v1.0.0-debug.apk`
- `ROTG02-Viewer-v1.0.0-debug.apk.sha256`

## Berechtigungen

Manifest:

- `android.permission.CAMERA` für Androids UVC-USB-Zugriffsregeln
- USB Host Feature

Nicht vorhanden:

- INTERNET
- RECORD_AUDIO
- allgemeine Storage-Berechtigungen
