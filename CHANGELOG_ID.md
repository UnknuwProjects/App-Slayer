[English](CHANGELOG.md) | **Bahasa Indonesia**

# Catatan Rilis

## 6.3.8
- Tampilan laporan sesi FPS diperbarui: kartu statistik dengan angka besar dan grafik modern — garis halus, garis suhu dengan legenda, dan sumbu yang tidak pernah di bawah nol.
- Layar baterai sekarang dibuka dengan kesehatan baterai: perkiraan kapasitas nyata dibandingkan kapasitas desain, dengan persen kesehatannya di depan.
- Kartu sesi pengisian baru: sesi pengisian yang sedang berjalan atau terakhir, lengkap dengan durasi, arus dan daya rata-rata, plus sesi-sesi terakhirmu.
- Fix catatan kelancaran yang menampilkan tanda persen ganda.
- Bisa langsung ditimpa dari 6.3.7, tidak perlu uninstall.

## 6.3.7
- Fix teks yang hampir tidak terbaca di layar Monitor Melayang.
- Status Baterai sekarang layar penuh, bukan dialog — ditekan berkali-kali pun tidak akan menumpuk.
- Riwayat baterai sekarang terekam sendiri selama aplikasi dipakai, tidak perlu menyalakan fitur notifikasi.
- Rekaman FPS sekarang selalu tersimpan sebagai sesi, termasuk yang singkat.
- Rekam FPS bisa tanpa root: minta izin tangkap layar sekali, lalu menghitung frame asli game-nya. Root tetap jadi cadangan.
- Bagian suhu menampilkan status termal sistem kalau tidak ada sensor yang bisa dibaca.
- Bisa langsung ditimpa dari 6.3.6, tidak perlu uninstall.

## 6.3.6
- Fix dialog Info Baterai yang lari keluar layar — sekarang panelnya pas, isinya bisa di-scroll, tombol tutup menyatu di panel.
- Bar level dan kesehatan baterai sekarang hijau, ikut tema aplikasi.
- Fix label Arab yang jauh dari tombol radionya di pemilih bahasa.
- Bisa langsung ditimpa dari 6.3.5, tidak perlu uninstall.

## 6.3.5
- Header layar utama sekarang cuma judul — tombol gir dan menu dihapus. Monitor Melayang dan Bahasa ada di daftar Fungsi.
- Bisa langsung ditimpa dari 6.3.4, tidak perlu uninstall.

## 6.3.4
- Layar utama: bar atas yang terang sudah hilang — judul sekarang di atas mengikuti tema gelap aplikasi, tombol gir dan menu tetap ada.
- Bahasa sekarang dipilih dari daftar bottom sheet, plus empat bahasa baru: Mandarin, Hindi, Spanyol, dan Arab.
- Screenshot di README diperbarui, sesuai build ini.
- Bisa langsung ditimpa dari 6.3.3, tidak perlu uninstall.

## 6.3.3
- Info Baterai tampilannya baru: kartu-kartu, header level besar, plus baris tegangan.
- Tes root sekarang benar bilangnya kalau su tidak bisa jalan sama sekali — cek daftar Superuser di Magisk/KernelSU.
- Bisa langsung ditimpa dari 6.3.2, tidak perlu uninstall.

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
