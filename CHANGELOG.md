**English** | [Bahasa Indonesia](CHANGELOG_ID.md)

# Changelog

## 6.3.10
- Floating Monitor switches redesigned: compact pill switches in the app's colors, bright green when on.
- App detection for FPS sessions: grant usage access once (new row in the FPS Recorder card) and sessions carry the tested app's name — and a session now splits automatically when you switch apps mid-recording.
- Installs over 6.3.9, no need to uninstall.

## 6.3.9
- Floating Monitor screen tidied up into the same card design as the rest of the app: diagnosis values are colored by state, the Battery Status entry is a card row, and the switches are proper tinted switches now.
- Installs over 6.3.8, no need to uninstall.

## 6.3.8
- FPS session report redesigned: stat cards with big numbers and modern charts — smooth lines, temperature overlay with a legend, and axes that never go below zero.
- Battery screen leads with battery health now: estimated real capacity against the design capacity, with the health percent up front.
- New charging sessions card: the current or last charge session with duration, average current and power, plus your recent sessions.
- Fixed the smoothness note showing a double percent sign.
- Installs over 6.3.7, no need to uninstall.

## 6.3.7
- Fixed text that was barely readable on the Floating Monitor screen.
- Battery Status is a full screen now, not a dialog — tap it as many times as you like, it will not stack.
- Battery history now records on its own while the app is used, no need to turn on the notification feature.
- FPS recordings are always saved as a session now, even short ones.
- FPS recording works without root: it asks for screen capture permission once and counts the game's real frames. Root stays as a fallback.
- Temperature section shows the system thermal status when no sensor can be read.
- Installs over 6.3.6, no need to uninstall.

## 6.3.6
- Fixed the Battery Information dialog running off the screen — it is a proper panel now, the content scrolls inside, and the close button is part of the panel.
- Battery level and health bars are green now, matching the app.
- Fixed the Arabic label sitting far from its radio in the language picker.
- Installs over 6.3.5, no need to uninstall.

## 6.3.5
- Main screen header is just the title now — the gear and menu buttons are gone. Floating Monitor and Language live in the Features list.
- Installs over 6.3.4, no need to uninstall.

## 6.3.4
- Main screen: the light top bar is gone — the title now sits at the top in the app's dark theme, gear and menu buttons included.
- Language is picked from a bottom sheet list now, with four new languages: Chinese, Hindi, Spanish, and Arabic.
- Fresh screenshots in the README, matching this build.
- Installs over 6.3.3, no need to uninstall.

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
