# kiosk-app

Android WebView-Kiosk-App für die lokale **Termux-Shopkasse**.  
Dieses Repo enthält den Android-Client (APK), nicht das FastAPI-Backend.

## Zugehöriges Backend-Repo

- Shop-System (FastAPI/SQLite): [`chrishaef/termux-kasse`](https://github.com/chrishaef/termux-kasse)

## Zweck

- Öffnet die Shopkasse im Vollbild auf Android
- Kiosk-Hardening mit PIN-geschütztem Admin-Menü
- Aktionen wie Minimieren/Beenden nur mit zusätzlicher Bestätigung
- URL im Admin-Menü änderbar (inkl. Verbindungstest)
- Upload/Download-Unterstützung im WebView

## Voraussetzungen

- Android Studio (aktueller Stable-Stand)
- Android SDK (minSdk 24, targetSdk 36 laut Projektkonfiguration)
- Laufender Shop-Server (lokal via Termux oder im LAN)

## Projekt öffnen und APK bauen

1. Repo klonen und in Android Studio öffnen
2. Gradle Sync abwarten
3. Debug-APK bauen:
   - `Build` -> `Build APK(s)`
4. APK auf Tablet installieren (Sideload)

Optional Release-Build:
- `Build` -> `Generate Signed Bundle / APK`
- Signatur/Keystore konfigurieren

## Ersteinrichtung auf dem Tablet

1. App starten
2. 5 Sekunden unten rechts drücken (Admin-Trigger)
3. Admin-PIN setzen (beim ersten Mal)
4. `Server-Adresse ändern` öffnen
5. URL setzen, `Verbindung testen`, dann speichern

Typische URL:
- lokal auf demselben Gerät: `http://127.0.0.1:8000`
- im LAN: `http://<tablet-ip>:8000`

## Hinweise

- Die App ist für den Betrieb mit der Termux-Shopkasse ausgelegt.
- Für Auto-Start des Backends nach Reboot: im Backend-Repo die Anleitung zu `Termux:Boot` nutzen.
