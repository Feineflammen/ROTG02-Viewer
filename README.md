# ROTG02 Viewer 1.0.2

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

- `ROTG02-Viewer-v1.0.2-release.apk`
- `ROTG02-Viewer-v1.0.2-release.apk.sha256`

## Berechtigungen

Manifest:

- `android.permission.CAMERA` für Androids UVC-USB-Zugriffsregeln
- USB Host Feature

Nicht vorhanden:

- INTERNET
- RECORD_AUDIO
- allgemeine Storage-Berechtigungen


## 1.0.1 Packaging-Härtung

Der erste 1.0.0-Debug-Build wurde nach einer Malware-Warnung nicht weiter verteilt.
1.0.1 wird deshalb bewusst anders gebaut:

- Release-Variante statt Debug-Variante
- nicht debuggable
- R8-Codeoptimierung und Resource-Shrinking
- vollständiger Neubuild mit deaktiviertem Gradle-Build-Cache und `--rerun-tasks`
- APK-Signaturprüfung nach dem Build
- automatische Prüfung auf exakt die erwartete Package-ID und Version
- automatische Permission-Prüfung; unerwartete Netzwerk-, Mikrofon-, Standort-, SMS-, Kontakte-, Installations- oder allgemeine Storage-Berechtigungen brechen den Build ab
- native Library wird auf Netzwerk-/Shell-Indikatoren geprüft
- Build-Commit und Workflow-Run werden im Artifact als `build-info.txt` dokumentiert
- SHA-256 wird direkt im selben Workflow erzeugt

Der aktuelle CI-Release ist für direkte Tests mit dem Android-Debug-Schlüssel signiert,
aber nicht als Debug-Build kompiliert. Für eine spätere öffentliche Verteilung sollte
ein dauerhaft geschützter eigener Release-Signing-Key verwendet werden.

Eine bestandene statische Prüfung beweist nicht, dass ein Virenscanner niemals einen
Fehlalarm erzeugt. Bei einer erneuten Malware-Warnung die APK weiterhin nicht installieren.


## 1.0.2 Frame-Integrität

Einzelne sichtbar halb schwarze/weiße Frames werden nicht mehr nur anhand von
JPEG SOI/EOI zusammengesetzt. Der native Assembler wertet jetzt die UVC-eigenen
Framegrenzen aus:

- FID-Wechsel startet einen neuen UVC-Frame.
- Fehlt beim FID-Wechsel das vorherige EOF, wird der unvollständige Frame verworfen.
- EOF beendet genau den aktuellen UVC-Frame.
- Nur Frames, die innerhalb derselben UVC-Grenze mit JPEG SOI beginnen und mit
  JPEG EOI enden, werden an Android weitergegeben.
- Paket-, UVC-Header- und Oversize-Fehler markieren den aktuellen Frame als
  unbrauchbar.
- Verworfene Frames ersetzen das angezeigte Bild nicht. Der letzte gültige Frame
  bleibt stehen, bis der nächste vollständige Frame eintrifft.

Der Recorder erhält dieselben validierten Frames wie die Anzeige.
