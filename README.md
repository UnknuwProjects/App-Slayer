**English** | [Bahasa Indonesia](README_ID.md)

# AppSlayer

**AppSlayer** is a lightweight Android app that completes the *[Background App Slayer](https://github.com/UNKNUW/Background-App-Slayer)* module. It monitors and manages background processes through root scripts, shows the current mode and execution results right in the status bar, and lets you edit the module's lists from inside the app — no extra tools needed.

## Features

- New main screen: modes are shown as cards, and the active mode is easy to spot.
- Three modes — Normal, Aggressive, and Extreme — each with a short description on its card.
- Status bar notification with CPU/GPU temperature, battery, and RAM, updated automatically.
- Floating monitor: draggable overlays for FPS, CPU, RAM, temperature, battery, battery current, and signal.
- Full battery info with a 24-hour battery history graph, average drain per hour, and estimated time left.
- Built-in list editors for gamelist, whitelist, extreme, and extrakill.
- The service can restart itself after a reboot.
- Indonesian and English, switchable from inside the app.
- Lightweight, built to stay friendly on low-end devices.

## Requirements

- Android 7.0 to Android 16.
- Root access.
- The Background App Slayer module installed.

## Installation

Install the module first, then install the AppSlayer APK and grant root when asked.

Signing note: builds up to 6.3.0 were signed with a debug key. From 6.3.1 on, releases are signed with the official UNKNUW Projects release key — if you are coming from 6.3.0 or older you must uninstall once, and after that updates install in place.

## Build

The APK is built automatically by GitHub Actions on every push to `main`, and can also be run manually from the Actions tab (the "Build APK" workflow). The debug and release APKs are uploaded as artifacts.

To build it yourself:

```
./gradlew assembleDebug assembleRelease
```

Requires JDK 17 and Android SDK 36.

## Changelog

Full release history: [CHANGELOG.md](CHANGELOG.md)

## Preview

<p align="center">
  <img src="screenshots/Screenshot_20260927-173129_App%20Slayer.png" width="220">
  <img src="screenshots/Screenshot_20260927-173302_App%20Slayer.png" width="220">
</p>

<p align="center">
  <img src="screenshots/Screenshot_20260927-173314_App%20Slayer.png" width="220">
  <img src="screenshots/Screenshot_20260927-173318_App%20Slayer.png" width="220">
</p>

## License

Copyright (C) 2025-2026 UNKNUW Projects

App Slayer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.
