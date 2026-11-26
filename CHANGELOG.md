**English** | [Bahasa Indonesia](CHANGELOG_ID.md)

# Changelog

## 6.3.3
- Battery Information has a new look: cards, a big level header, and a voltage row.
- Root test now says it right when su cannot run at all — check the Superuser list in Magisk/KernelSU.
- Installs over 6.3.2, no need to uninstall.

## 6.3.2
- New: Test all functions button in Diagnostics. Tests root, overlay, temperature sensors, FPS, processes, battery, module files, log copy, and notifications one by one.
- Root is not active now shows a warning on the main screen, with a button to request access.
- Fixed classic overlay windows piling up on top of each other.
- Installs over 6.3.1, no need to uninstall.

## 6.3.1
- Fixed the overlay not showing even with the switches on.
- Diagnosis block now updates live, with the active monitor count and the last error.
- Temperature sensor list now works on more phones.
- New diagnostic log in the diagnosis block, also saved to /storage/emulated/0/AppSlayer/logs/.
- Battery history now explains why it is empty when the notification service is off.
- This version starts using the official signing key. Uninstall the old version one last time, after that updates install in place.
- Project license is now GNU GPL v3.

## 6.3.0
- New: FPS Recorder. Record FPS while gaming, stops by itself after 1 hour.
- New: FPS session report — max/min/average FPS, 5% low, 1% low, smoothness, temperatures, and graphs.
- New: Process Monitor, an overlay with the top 5 processes by CPU usage.

## 6.2.0
- New: Mini monitor style, one small bar with the active stats.
- New: Temperature sensor list on the Floating Monitor screen.
- New: Diagnosis block, to check permissions and services when the overlay or notification is missing.
- Battery Info now shows power (watts), and its dialog no longer stacks when tapped repeatedly.
- Fixed battery capacity showing N/A on some phones.
- The app now asks for the notification permission (Android 13+).

## 6.0.1
- Fixed the update checker offering an old version.
- Fixed Aggressive and Extreme modes silently running as Normal.
- Fixed the overlay not showing.
- Fixed the overlay not coming back after reboot.
- Battery Info now shows system capacity, status, current, and time estimates.
- Fixed the transparency and text size sliders overwriting each other.

## 6.0.0
- Total overhaul, source cleaned up and now built from this repo.
- New main screen, modes shown as cards.
- Monitor is lighter and easier on the battery.
- New: 24-hour battery history graph.
- New: English language option.
- Support up to Android 16.
- Note: the APK is debug-signed, uninstall the old version before installing.

## Earlier releases
- First release of the app.
- Status notifications and in-app list management.
