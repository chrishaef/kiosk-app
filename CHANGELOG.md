# Changelog

Alle wichtigen Änderungen an diesem Projekt werden in dieser Datei dokumentiert.

## [1.2.7] - 2026-08-02

### Hinzugefügt
- **Akku-Optimierung verwalten:** Neuer Button im Admin-Menü, um die App von der Android-Akku-Optimierung auszuschließen. Dies verbessert die Stabilität im Dauerbetrieb auf Tablets (besonders Lenovo).
- **Berechtigungen:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` im Manifest hinzugefügt.

### Geändert
- **Keep-Alive Intervall:** Das Prüf-Intervall zur Erkennung eingefrorener WebViews wurde von 2 Minuten auf 1 Minute verkürzt, um Hänger schneller abzufangen.
- **Code-Cleanup:** Umfangreiches Refactoring der Download-Logik. Der ungenutzte Android-System `DownloadManager` wurde entfernt. Die App nutzt nun ausschließlich den zuverlässigeren manuellen Download-Pfad via WebView-Cookies.
- **UI-Stabilität:** Kleinere Korrekturen an Dialog-Layouts und Texten.

### Optimiert
- SAF (Storage Access Framework) Schreib-Performance durch vergrößerte Puffer beim Speichern von Downloads optimiert.
- Versionsnummer auf 1.2.7 (Build 8) angehoben.

---

## [1.2.6] - 2026-06-15
- Fehlerbehebungen bei Downloads und allgemeine Optimierungen.

## [1.2.0] bis [1.2.5]
- Diverse UI-Fixes für 10 Zoll Tablets, Layout-Perfektion und Sicherheits-Updates.

## [1.1.0]
- Speicher-Optimierung.

## [1.0.0]
- Erster Release - Kiosk Browser.
