# Rybky

Lov samolepek a tetování s rybkou po městě. Někdo nalepí samolepku (nebo zaregistruje své tetování),
ostatní ji hledají podle kruhu na mapě a „uloví“ ji namířením kamery – aplikace pozná obrázek
a server ověří polohu.

- `android/` – aplikace (Kotlin, Jetpack Compose, CameraX, MediaPipe Image Embedder, osmdroid + Mapy.com)
- `backend/` – server (jen standardní knihovna Pythonu + SQLite)

## Stažení

Hotové APK je v [Releases](../../releases/latest).

## Backend

```sh
python3 backend/server.py          # port 8000
```

Nebo v Dockeru: `backend/deploy/compose.yml` (vedle něj zkopírovat `server.py`).

## Aplikace

Do `android/local.properties`:

```properties
sdk.dir=/cesta/k/Android/Sdk
mapy.apikey=...                     # klíč z https://developer.mapy.com
api.url=https://rybky.example.com   # bez něj http://127.0.0.1:8000 přes `adb reverse tcp:8000 tcp:8000`
```

`android/dev.sh` sestaví aplikaci a nahraje ji do všech připojených telefonů.
