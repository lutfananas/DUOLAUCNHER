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
- Folder **Recently Added** (aplikasi yang baru dipasang). Folder Suggestions sudah dihapus.
- **Mode Edit** (tombol *Edit* di samping kolom pencarian, atau tekan lama area kosong /
  folder): folder bergoyang seperti di iPhone, lalu bisa:
  - **＋ Folder**: membuat folder baru (langsung terbuka untuk diisi lewat *Add Apps*).
  - Tombol **−** di pojok folder: menghapus folder (aplikasinya kembali ke folder
    otomatis / Other, tidak di-uninstall). Recently Added juga bisa dihapus.
  - Ketuk nama folder (✎): ganti nama.
  - Buka folder → ketuk aplikasi untuk memindahkannya ke folder lain, atau **Add Apps**
    untuk memilih banyak aplikasi sekaligus.
  - **Reset**: kembali ke susunan otomatis.
  - **Wallpaper**: pilih foto dari galeri sebagai latar launcher, atau kembali ke
    "Duo dunes". Latar langsung berganti, blur kaca ikut diperbarui.
- Tekan lama aplikasi di mana saja → **Move to Folder…** juga tersedia.
- **Icon pack** (tombol *Icons* di mode Edit, atau tekan lama area kosong → *Icon Pack*):
  pilih icon pack yang terpasang (format Nova / ADW / Apex). Ikon berganti di **seluruh
  launcher**: home, dock, folder, dan App Library. Tombol *Unsupported icons* mengatur apakah
  ikon yang tidak ada di pack dibuat senada (latar/masker dari pack) atau tetap asli.
- **Ganti ikon satu aplikasi**: tekan lama aplikasi di App Library → *Change Icon…* → pilih
  ikon dari icon pack mana pun (ada pencarian), pakai ikon asli, atau reset.
- Kategori otomatis: Social, Entertainment, Creativity, Productivity & Finance,
  Utilities, Games, Travel, Shopping & Food, Health & Fitness,
  Information & Reading, Other — dari kategori Android + kata kunci nama paket.
- Tekan lama ikon → menu **Open / Move to Folder… / App info / Uninstall**.
- **Frosted glass**: halaman App Library menampilkan foto latar launcher yang diburamkan
  (sedikit lebih jenuh warnanya, seperti iOS). Latar buram ini ikut bergeser masuk saat
  halaman digeser. Folder, kolom pencarian, dan daftar hasil pencarian memakai panel kaca
  (isi transparan, kilau di bagian atas, garis tepi tipis). Kalau latar launcher bukan foto
  (latar bawaan), yang muncul hanya panel kaca dengan sedikit gelap, tanpa blur.

## Cara kerja
- `mod/src/.../AppLibraryView.java` — tampilan App Library (View Android biasa).
- `mod/src/.../LibraryModel.java` — susunan folder buatan pengguna (disimpan sebagai JSON).
- `mod/src/.../Categories.java` — kategori otomatis.
- `mod/src/.../WallpaperActivity.java` — pemilih foto; menyimpan ke file & preferensi yang
  sama dengan menu "Wallpaper & appearance" bawaan dan memperbarui latar secara langsung.
- `mod/src/.../AppIcons.java`, `IconPack.java`, `IconPicker.java` — dukungan icon pack.
  `patch.py` menyisipkan `AppIcons.themed(...)` ke constructor entri aplikasi (`G1/a`) dan
  `AppIcons.init(...)` ke `DuoApplication.onCreate`, plus `<queries>` agar icon pack terlihat.
- `mod/src/.../Glass.java` — blur latar (box blur pada salinan kecil
  `files/launcher-background.jpg`) dan panel kaca.
- `mod/src/.../AppLibrary.java` — jembatan ke Compose (`AndroidView`), menerima daftar
  aplikasi dari state launcher.
- `mod/patch.py` — mengganti satu pemanggilan di `smali/G1/s5.smali` (halaman
  Library di pager) agar memanggil `AppLibrary.content(...)`, plus izin
  `REQUEST_DELETE_PACKAGES` untuk Uninstall.
- `mod/build.sh` — decode (apktool) → compile + D8 → patch → build → zipalign → sign.
  Kode mod dimasukkan sebagai `classes2.dex`.
