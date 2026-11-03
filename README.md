# Web ADB HMDM (Android)
Aplikasi Android (HP "installer") yang memasang APK + set Device Owner Headwind MDM ke HP lain lewat kabel USB OTG.

## Build
Android Studio: File > Open folder ini > tunggu sync > Build > Build APK(s).
Tanpa Android Studio: upload folder ini ke repo GitHub; workflow "Build APK" menghasilkan artifact APK.

## Catatan
- UI dimuat dari app/src/main/assets/index.html (Tailwind/FontAwesome/Fonts via CDN, butuh internet saat dibuka).
- Protokol ADB-over-USB ada di AdbConnection.kt (WebUSB tidak tersedia di WebView Android).
