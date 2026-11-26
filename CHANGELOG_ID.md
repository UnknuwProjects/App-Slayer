[English](CHANGELOG.md) | **Bahasa Indonesia**

# Catatan Rilis

## 6.3.2
- Baru: Tombol Tes semua fungsi di Diagnostik. Ngetes root, overlay, sensor suhu, FPS, proses, baterai, file modul, salinan log, dan notifikasi satu-satu.
- Root belum aktif sekarang ada peringatan di layar utama, plus tombol buat minta akses.
- Fix jendela overlay klasik yang numpuk satu sama lain.
- Bisa langsung ditimpa dari 6.3.1, tidak perlu uninstall.

## 6.3.1
- Fix overlay tidak muncul padahal switch sudah nyala.
- Blok diagnosis sekarang update langsung, ada info jumlah monitor aktif dan error terakhir.
- Daftar sensor suhu sekarang terbaca di lebih banyak HP.
- Ada log diagnostik baru di blok diagnosis, filenya juga tersimpan di /storage/emulated/0/AppSlayer/logs/.
- Riwayat baterai sekarang ada keterangan kenapa kosong kalau service notifikasi mati.
- Mulai versi ini APK ditandatangani kunci resmi. Uninstall dulu versi lama sekali ini saja, setelah itu update bisa langsung ditimpa.
- Lisensi proyek sekarang GNU GPL v3.

## 6.3.0
- Baru: Perekam FPS. Rekam FPS pas main, berhenti sendiri setelah 1 jam.
- Baru: Laporan sesi FPS — FPS maks/min/rata-rata, 5% low, 1% low, kelancaran, suhu, dan grafik.
- Baru: Monitor Proses, overlay 5 proses teratas pemakai CPU.

## 6.2.0
- Baru: Gaya monitor Mini, satu bar ringkas isi statistik yang aktif.
- Baru: Daftar sensor suhu di layar Monitor Melayang.
- Baru: Blok diagnosis, buat cek izin dan service kalau overlay/notif tidak muncul.
- Info Baterai sekarang menampilkan daya (watt), dan dialognya tidak menumpuk lagi kalau ditekan berkali-kali.
- Fix kapasitas baterai yang N/A di sebagian HP.
- Aplikasi sekarang minta izin notifikasi (Android 13+).

## 6.0.1
- Fix cek update yang menawarkan versi lama.
- Fix mode Aggressive dan Extreme yang diam-diam jalan sebagai Normal.
- Fix overlay tidak muncul.
- Fix overlay tidak balik setelah reboot.
- Info Baterai sekarang terisi kapasitas sistem, status, arus, dan perkiraan waktu penuh/sisa.
- Fix slider transparansi dan ukuran teks yang saling menimpa.

## 6.0.0
- Rombak total, source dirapikan dan sekarang dibangun dari repo ini.
- Tampilan utama baru, mode tampil sebagai kartu.
- Monitor lebih ringan dan hemat baterai.
- Baru: Grafik riwayat baterai 24 jam.
- Baru: Pilihan bahasa Inggris.
- Support sampai Android 16.
- Catatan: APK ditandatangani debug key, uninstall dulu versi lama sebelum pasang.

## Rilis sebelumnya
- Rilis perdana aplikasi.
- Notifikasi status dan manajemen daftar aplikasi.
