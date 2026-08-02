# kiosk-app

Die Android WebView-Kiosk-App für die lokale **Termux-Shopkasse**. Die App ist optimiert für den offline Betrieb der **termux-shopkasse**. Sie legt sich in Vollbildansicht in den Vordergrund und heftet sich im Sperrbildschirm von Android an.
Bei Bedarf kann in den Appeinsteistellungen aber auch eine andere Domain/Adresse hinterlegt werden. So kann die App z.B. auch im Heimnetz zur Anzeige von **HomeAssistant** genutzt werden.

Zum Aufraufen des Admin-Bereichs und der App Einstellungen muss 5s lang ein Klick oder Tipp auf die untere rechte Bildschirmecke durchgeführt werden. Bei erstinstallation muss ein Admin Pin vergeben werden, welcher den Bereich und andere Funktionen (Schließen der App) absichert.

Dieses Repo enthält den Android-Client (APK), nicht das FastAPI-Backend.

## Zugehöriges Backend-Repo

- Shop-System (FastAPI/SQLite): [`chrishaef/termux-kasse`](https://github.com/chrishaef/termux-kasse)

## Zweck

- Öffnet die Shopkasse im Vollbild auf Android
- Kiosk-Hardening mit PIN-geschütztem Admin-Menü
- Aktionen wie Minimieren/Beenden nur mit zusätzlicher Bestätigung
- URL im Admin-Menü änderbar (inkl. Verbindungstest)
- Upload/Download-Unterstützung im WebView

## Installation

- .apk aus dem aktuellesten Release herunterladen und installieren
- vorerst keine Bereitstellung der App über den App-Store!

## Voraussetzungen für eigene Builds

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
- Die App hält die WebView periodisch wach (JS-/Server-Check jede Minute) und weckt sie bei `onResume` erneut auf. Das soll Einfrieren auf OEM-Tablets (z. B. Lenovo) abfangen, ohne Fake-Touches und ohne die Idle-Logik der Shopkasse zu stören.
- Im Admin-Menü kann die Akku-Optimierung für die App deaktiviert werden, um sicherzustellen, dass Android die App im Hintergrund (bzw. bei Inaktivität) nicht drosselt oder beendet.
