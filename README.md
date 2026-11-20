# PRS Auto

Otomasi tugas **Follow Instagram** di [pr.social](https://pr.social) untuk tablet
Android — tanpa root, tanpa PC. Aplikasi mengendalikan Chrome lewat
AccessibilityService, mengambil **screenshot asli layar penuh** lewat
MediaProjection, lalu mengunggahnya lewat file picker.

> **Status: v1.0.0 — BELUM DITES di tablet asli.** Alur dibuat dari rekaman
> layar user (20 Nov 2026). Nama tombol/teks dicocokkan dengan yang terlihat
> di rekaman; kalau pr.social atau Instagram mengubah tampilan, otomasi bisa
> gagal — aplikasi berhenti dengan aman dan mencatat di log.

## Cara kerja (per tugas)

1. Buka `pr.social/tasks`, aktifkan filter Instagram.
2. Tap kartu tugas pertama (`Ikuti di Instagram @<target>`).
3. Tap `Mulai · $0.0028` → tap `Buka profil dan ikuti` (ikon ↗).
4. Di profil IG: tap tombol `Ikuti` **utama** (bukan yang di "Disarankan
   untuk Anda") → tunggu sampai jadi `Diikuti`.
5. Screenshot asli full screen (termasuk address bar, tajam, **tanpa edit**)
   → disimpan ke `Pictures/PRS Auto/` (JPEG, resolusi layar, mis. 2880x1800).
6. Kembali ke pr.social → tap `Tambah tangkapan layar` → file picker
   Google Foto terbuka → tap item **pertama** di grid (screenshot terbaru
   selalu paling kiri atas) → tap `Selesai`.
7. Tunggu `Tangkapan layar terkirim` / `Sedang ditinjau` → tap
   `Tugas berikutnya` → tunggu hitung mundur `Mulai dalam N dtk` →
   jeda acak 5–15 dtk → tugas berikutnya.

Satu langkah gagal 3x berturut-turut → aplikasi berhenti dengan aman dan
mencatat penyebabnya di log. Max tugas per sesi default 20 (bisa diubah).

## Install

1. Download APK dari [Releases](../../releases) (`prs-auto-v1.0.0-debug.apk`).
2. Di tablet: buka file APK → izinkan **"Install aplikasi yang tidak dikenal"**
   (Unknown sources) untuk aplikasi yang membuka file itu (Chrome/File Manager).
3. Install. Ikon: **PRS Auto**.

## Persiapan — WAJIB (sekali saja)

Buka aplikasi PRS Auto, ikuti checklist di layar:

1. **Aksesibilitas** — tombol "Aktifkan Aksesibilitas" → cari **PRS Auto** →
   aktifkan. Tanpa ini aplikasi tidak bisa membaca layar/mengklik.
2. **Baterai: Tanpa batasan** — tombol "Baterai: Tanpa batasan" → pilih
   **Tanpa batasan**. **Khusus Xiaomi/HyperOS: sistemnya agresif mematikan
   background service.** Kalau ini tidak diatur, service + accessibility bisa
   dimatikan di tengah jalan.
3. **Autostart (Xiaomi/HyperOS)** — tombol "Buka Info Aplikasi" → izinkan
   **Autostart**.

## Menjalankan

1. Login dulu ke **pr.social** dan **Instagram** (akun pekerja, mis.
   `@sapawes.2`) di **Chrome**.
2. Buka PRS Auto → atur max tugas & jeda kalau perlu → tekan **MULAI**.
3. Dialog izin screenshot ("Start now") muncul sekali per sesi → setujui.
4. Chrome terbuka ke `pr.social/tasks`, otomasi jalan sendiri. Log terlihat
   di aplikasi dan notifikasi.
5. Tekan **BERHENTI** kapan saja untuk berhenti dengan aman.

> Catatan: layar tablet jangan dikunci dan jangan pindah aplikasi lain selama
> sesi berjalan — otomasi bekerja dengan mengendalikan layar yang terlihat.

## Batasan yang diketahui

- **Belum dites di perangkat asli** — baru verifikasi build (APK valid,
  service terdaftar). Kemungkinan perlu penyesuaian pencocokan tombol.
- Hanya **Chrome** (`com.android.chrome`). Browser lain tidak didukung.
- Item pertama di picker: aplikasi memilih item paling kiri atas di tab
  **Foto**. Kalau picker terbuka di tab lain atau ada item yang lebih baru
  dari screenshot (mis. foto baru masuk), bisa salah pilih.
- **Risiko action block Instagram** kalau terlalu cepat/banyak. Panduan aman
  umum: ~10 follow/jam, ~150/hari untuk akun lama; akun baru jauh lebih
  sedikit. Jeda default 5–15 dtk antar tugas sudah acak, tapi tetap pantau:
  kalau muncul "Try Again Later" di IG, berhenti 24–48 jam.
- pr.social/Instagram bisa mengubah teks/tampilan kapan saja — kalau alur
  berhenti di satu langkah, lihat log untuk tahu di langkah mana.
- Screenshot diambil lewat MediaProjection: ikon "screen cast" muncul di
  status bar selama sesi — itu normal.
- Screenshot disimpan di `Pictures/PRS Auto/` dan tetap ada di galeri
  setelah sesi (bisa dihapus manual).

## Build dari source

Butuh JDK 17 + Android SDK (platform 34, build-tools 34.0.0):

```sh
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Tanpa dependensi eksternal — semua API yang dipakai (Accessibility,
MediaProjection, MediaStore, Notification) tersedia di framework Android.

## Struktur

```
app/src/main/
  AndroidManifest.xml
  java/com/luna/prsauto/
    MainActivity.kt        # UI: checklist, pengaturan, MULAI/BERHENTI, log
    AutomationService.kt   # AccessibilityService + state machine tugas
    ScreenCapture.kt       # MediaProjection -> JPEG ke MediaStore
    LogBus.kt              # Log (broadcast + file prs-auto.log)
    Prefs.kt               # Pengaturan sesi
  res/
    xml/accessibility_service_config.xml
    layout/activity_main.xml
    values/strings.xml
    drawable/ic_launcher.xml
```
