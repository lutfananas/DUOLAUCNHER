# Duo Launcher — mod App Library ala iPhone

Mod dari `DuoLauncher-0.15.0-beta01-release.apk`. Halaman Library di home screen
(slide setelah halaman home) diganti dengan tampilan mirip **App Library iPhone**.

**APK siap pasang:** [`dist/DuoLauncher-0.15.0-beta01-applibrary.apk`](dist/DuoLauncher-0.15.0-beta01-applibrary.apk)

> APK ini ditandatangani ulang dengan kunci lain, jadi **hapus dulu Duo Launcher asli**
> sebelum memasang (kalau tidak, Android menolak karena tanda tangan berbeda).
> Tata letak home lama ikut terhapus saat uninstall.

## Fitur
- Kolom pencarian **"App Library"** di atas. Ketuk → daftar semua aplikasi A–Z
  dengan header huruf; ketik untuk memfilter; tombol **Cancel** / Back untuk kembali.
- Grid 2 kolom berisi folder kategori dengan kotak sudut membulat transparan:
  - 3 ikon besar → langsung membuka aplikasi.
  - Gugus 4 ikon kecil di pojok kanan bawah → membuka folder penuh
    (latar diburamkan, grid 4 kolom, ketuk area kosong / Back untuk menutup).
- Folder **Suggestions** (4 ikon, dari aplikasi yang paling sering/terakhir dibuka
  dari App Library) dan **Recently Added** (aplikasi yang baru dipasang).
- Kategori otomatis: Social, Entertainment, Creativity, Productivity & Finance,
  Utilities, Games, Travel, Shopping & Food, Health & Fitness,
  Information & Reading, Other — dari kategori Android + kata kunci nama paket.
- Tekan lama ikon → menu **Open / App info / Uninstall**.

## Cara kerja
- `mod/src/.../AppLibraryView.java` — tampilan App Library (View Android biasa).
- `mod/src/.../AppLibrary.java` — jembatan ke Compose (`AndroidView`), menerima daftar
  aplikasi dari state launcher.
- `mod/patch.py` — mengganti satu pemanggilan di `smali/G1/s5.smali` (halaman
  Library di pager) agar memanggil `AppLibrary.content(...)`, plus izin
  `REQUEST_DELETE_PACKAGES` untuk Uninstall.
- `mod/build.sh` — decode (apktool) → compile + D8 → patch → build → zipalign → sign.
  Kode mod dimasukkan sebagai `classes2.dex`.
