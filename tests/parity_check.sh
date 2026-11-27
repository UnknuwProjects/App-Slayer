#!/usr/bin/env bash
# Parity checklist: asserts the behavioral fixtures from the user's
# 5.2.0 reference screenshots and the 6.0.1 bug reports are present
# in the source. Prints PASS/FAIL per check with a summary count.
cd "$(dirname "$0")/.."
SRC=src/main/java/com/appslayer/official
PASS=0
FAIL=0

check() {
    # $1 = description, $2 = file, $3 = fixed string that must exist
    if grep -qF "$3" "$2"; then
        echo "PASS $1"
        PASS=$((PASS + 1))
    else
        echo "FAIL $1 (missing in $2: $3)"
        FAIL=$((FAIL + 1))
    fi
}

check_count() {
    # $1 = description, $2 = file, $3 = fixed string, $4 = minimum occurrences
    local n
    n=$(grep -cF "$3" "$2" || true)
    if [ "$n" -ge "$4" ]; then
        echo "PASS $1 ($n occurrences)"
        PASS=$((PASS + 1))
    else
        echo "FAIL $1 (found $n, want >= $4 in $2: $3)"
        FAIL=$((FAIL + 1))
    fi
}

# Notification (MyService) — golden 5.2.0 format.
check "notif line 1 CPU label" "$SRC/MyService.java" '"CPU : "'
check "notif line 1 GPU label" "$SRC/MyService.java" '" · GPU : "'
check "notif battery line carries watts" "$SRC/MyService.java" 'PureCalc.formatWattsMagnitude'
check "notif battery line carries volts" "$SRC/MyService.java" 'PureCalc.formatVolts'
check "notif RAM line format string" "$SRC/MyService.java" 'R.string.notif_ram_format'
check "notif status line from daemon" "$SRC/MyService.java" 'currentStatusLine'

# Classic overlay chips — golden formats.
check "temp chip CPU . GPU combined" "$SRC/SystemStats.java" 'return cpu + " · " + gpu;'
check "battery chip level . temp" "$SRC/SystemStats.java" 'level + "% · "'
check "RAM chip free . total (used%)" "$SRC/SystemStats.java" '"%.2f GB · %.2f GB (%d%%)"'
check "combined chip builder exists" "$SRC/SystemStats.java" 'getCombinedChip()'
check "combined chip CPU piece" "$SRC/SystemStats.java" '"CPU: "'
check "combined chip GPU piece" "$SRC/SystemStats.java" '" GPU: "'
check "combined chip FPS piece" "$SRC/SystemStats.java" '" FPS: "'
check_count "FPS window uses combined chip" "$SRC/FloatingOverlayService.java" 'SystemStats.getCombinedChip()' 2

# Permissions flow.
check "POST_NOTIFICATIONS runtime request" "$SRC/MainActivity.java" 'requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}'
check "POST_NOTIFICATIONS in manifest" "src/main/AndroidManifest.xml" 'android.permission.POST_NOTIFICATIONS'
check "overlay permission check" "$SRC/FloatingMonitorActivity.java" 'Settings.canDrawOverlays'
check "overlay permission settings intent" "$SRC/FloatingMonitorActivity.java" 'MANAGE_OVERLAY_PERMISSION'

# Battery Information screen (6.3.7): a dedicated activity, not a
# dialog. Anti-spam is layered: singleTop in the manifest plus the
# static open flag the monitor button checks before starting it.
check "battery screen singleTop" "src/main/AndroidManifest.xml" 'android:launchMode="singleTop"'
check "battery screen open flag" "$SRC/BatteryInfoActivity.java" 'private static volatile boolean open'
check "battery button checks the open flag" "$SRC/FloatingMonitorActivity.java" 'BatteryInfoActivity.isOpen()'
check "battery button reorders to front when open" "$SRC/FloatingMonitorActivity.java" 'FLAG_ACTIVITY_REORDER_TO_FRONT'
check "battery screen power row wired" "$SRC/BatteryInfoActivity.java" 'PureCalc.powerWatts'
check "capacity chain widened to all power_supply dirs" "$SRC/BatteryInfoActivity.java" 'powerSupplyDirs()'
check "slope fallback for estimates" "$SRC/BatteryInfoActivity.java" 'PureCalc.minutesToFull'
check "dumpsys charge counter fallback" "$SRC/SystemStats.java" 'dumpsys battery'

# Services: foreground with distinct ids and the specialUse type.
check "MyService foreground id" "$SRC/MyService.java" 'NOTIF_ID = 1'
check "MyService specialUse type" "$SRC/MyService.java" 'FOREGROUND_SERVICE_TYPE_SPECIAL_USE'
check "overlay service foreground id 2" "$SRC/FloatingOverlayService.java" 'startForeground(2,'
check "overlay service specialUse type" "$SRC/FloatingOverlayService.java" 'FOREGROUND_SERVICE_TYPE_SPECIAL_USE'

# Mode writes are numeric (daemon reads 1/2/3, not names).
check "mode extreme writes 3" "$SRC/MainActivity.java" '"extreme".equals(nextMode) ? "3"'
check "mode aggressive writes 2" "$SRC/MainActivity.java" '"aggressive".equals(nextMode) ? "2" : "1"'

# Monitor style chooser wiring.
check "style pref key" "$SRC/PrefsKeys.java" 'MONITOR_STYLE'
check "service reads style pref" "$SRC/FloatingOverlayService.java" 'PrefsKeys.MONITOR_STYLE'
check "mini window key" "$SRC/FloatingOverlayService.java" 'MINI_KEY'
check "activity writes style pref" "$SRC/FloatingMonitorActivity.java" 'putString(PrefsKeys.MONITOR_STYLE, style)'

# Diagnosis block wiring.
check "overlay service running flag" "$SRC/FloatingOverlayService.java" 'public static boolean isRunning()'
check "overlay window count getter" "$SRC/FloatingOverlayService.java" 'getShownWindowCount()'
check "notification service running flag" "$SRC/MyService.java" 'public static boolean isRunning()'
check "test overlay button handler" "$SRC/FloatingMonitorActivity.java" 'private void testOverlay()'

# FPS recorder wiring: toggle -> recorder state -> overlay service
# samples -> session file -> sessions list reads the same directory.
check "recorder running flag" "$SRC/FpsRecorder.java" 'public static boolean isRecording()'
check "recorder start" "$SRC/FpsRecorder.java" 'public static synchronized boolean start'
check_absent "recorder no longer fed by overlay service" "$SRC/FloatingOverlayService.java" 'FpsRecorder.sampleNow'
check_absent "recorder no longer finalized by overlay service" "$SRC/FloatingOverlayService.java" 'FpsRecorder.stop(this)'
check "sessions dir name" "$SRC/FpsSession.java" 'DIR_NAME = "fps_sessions"'
check "recorder writes into sessions dir" "$SRC/FpsRecorder.java" 'FpsSession.dir(context)'
check "sessions list reads sessions" "$SRC/FpsSessionsActivity.java" 'FpsSession.list'
check "report stats from PureCalc" "$SRC/FpsReportActivity.java" 'PureCalc.summarizeFps'
check "record toggle starts recorder" "$SRC/FloatingMonitorActivity.java" 'FpsRecorder.start(getApplicationContext())'
check "record toggle in layout" "src/main/res/layout/activity_floating_monitor.xml" 'switchRecordFps'
check "record toggle in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'switchRecordFps'
check "sessions button in layout" "src/main/res/layout/activity_floating_monitor.xml" 'btnFpsSessions'
check "sessions activity in manifest" "src/main/AndroidManifest.xml" 'FpsSessionsActivity'
check "report activity in manifest" "src/main/AndroidManifest.xml" 'FpsReportActivity'
check "session graph view exists" "$SRC/SessionGraphView.java" 'class SessionGraphView'

# Process monitor wiring: switch -> pref -> rebuild path in both
# monitor styles -> window counted like the others (same map).
check "process pref key" "$SRC/PrefsKeys.java" 'MONITOR_PROCESS = "monitor_process"'
check_count "process window in both style branches" "$SRC/FloatingOverlayService.java" 'updateOverlay(PrefsKeys.MONITOR_PROCESS' 2
check "process window refresh on scheduler" "$SRC/FloatingOverlayService.java" 'SystemStats.getTopProcesses(5)'
check "process switch in layout" "src/main/res/layout/activity_floating_monitor.xml" 'switchProcess'
check "process switch in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'switchProcess'
check "process switch wired" "$SRC/FloatingMonitorActivity.java" 'R.id.switchProcess'
check "top snapshot command" "$SRC/SystemStats.java" 'top -b -n 1'
check "foreground package via dumpsys" "$SRC/SystemStats.java" 'dumpsys activity activities'

check_absent() {
    # $1 = description, $2 = file, $3 = fixed string that must NOT exist
    if grep -qF "$3" "$2"; then
        echo "FAIL $1 (unexpected in $2: $3)"
        FAIL=$((FAIL + 1))
    else
        echo "PASS $1"
        PASS=$((PASS + 1))
    fi
}

# 6.3.1: the overlay rebuild path. The toggle -> window path used to
# depend on one implicit broadcast; it now goes through an explicit
# service intent, and both sides read the same default prefs file.
check "explicit rebuild action declared" "$SRC/FloatingOverlayService.java" 'ACTION_REBUILD = "com.appslayer.official.ACTION_REBUILD_OVERLAY"'
check "service handles rebuild intent" "$SRC/FloatingOverlayService.java" 'rebuild requested via service intent'
check "activity sends rebuild intent" "$SRC/FloatingMonitorActivity.java" 'FloatingOverlayService.ACTION_REBUILD'
check "activity reads default prefs" "$SRC/FloatingMonitorActivity.java" 'getDefaultSharedPreferences'
check "service reads default prefs" "$SRC/FloatingOverlayService.java" 'getDefaultSharedPreferences'
check "service-view enabled count getter" "$SRC/FloatingOverlayService.java" 'getServiceEnabledCount()'
check "last add result getter" "$SRC/FloatingOverlayService.java" 'getLastAddResult()'
check "window add failures recorded" "$SRC/FloatingOverlayService.java" 'recordAddFailure'
check "diagnosis ticker runs" "$SRC/FloatingMonitorActivity.java" 'diagTick'
check "test overlay polls for the result" "$SRC/FloatingMonitorActivity.java" 'test overlay result'

# 6.3.1: thermal enumeration falls back to the root shell.
check "thermal dump command" "$SRC/SystemStats.java" 'THERMAL_DUMP_COMMAND'
check "shell thermal cache" "$SRC/SystemStats.java" 'getShellThermalReadings()'
check "thermal dump parsed by PureCalc" "$SRC/SystemStats.java" 'PureCalc.parseThermalDump'
check "thermal dump parser exists" "$SRC/PureCalc.java" 'public static java.util.List<ThermalReading> parseThermalDump'
check "sensor list uses shell fallback" "$SRC/SystemStats.java" 'root shell answered'
check "notification temps use shared enumeration" "$SRC/MyService.java" 'SystemStats.thermalTempByLabel'

# 6.3.1: diagnostic log — call sites, user actions, mirror, share.
check "DiagLog class exists" "$SRC/DiagLog.java" 'class DiagLog'
check "log mirror path constant" "$SRC/DiagLog.java" 'MIRROR_PATH = MIRROR_DIR + "/" + LOG_FILE_NAME'
check_count "mirror path used by flush and mirrorFile" "$SRC/DiagLog.java" 'MIRROR_PATH' 2
check "share targets the mirror file" "$SRC/FloatingMonitorActivity.java" 'DiagLog.mirrorFile()'
check "log mirror dir on shared storage" "$SRC/DiagLog.java" '/storage/emulated/0/AppSlayer/logs'
check_count "overlay service writes to the log" "$SRC/FloatingOverlayService.java" 'DiagLog.log' 5
check_count "notification service writes to the log" "$SRC/MyService.java" 'DiagLog.log' 4
check "mode tap logged" "$SRC/MainActivity.java" '"mode tap: "'
check "mode write result logged" "$SRC/MainActivity.java" '"mode write ok: mode.txt="'
check "notification toggle logged" "$SRC/MainActivity.java" '"notifications: "'
check "POST_NOTIFICATIONS result logged" "$SRC/MainActivity.java" '"POST_NOTIFICATIONS result: "'
check "manual.sh result logged" "$SRC/MainActivity.java" '"manual.sh finished ok"'
check "list save diff logged" "$SRC/MainActivity.java" 'describeListDiff'
check "monitor toggles logged" "$SRC/FloatingMonitorActivity.java" 'DiagLog.log("USER", key'
check "record toggle logged" "$SRC/FloatingMonitorActivity.java" '"record_fps: "'
check "run checks handler" "$SRC/FloatingMonitorActivity.java" 'private void runChecks()'
check "share log via FileProvider" "$SRC/FloatingMonitorActivity.java" 'FileProvider.getUriForFile'
check_absent "no file:// uris for sharing" "$SRC/FloatingMonitorActivity.java" 'Uri.fromFile'
check "mirror flush on monitor pause" "$SRC/FloatingMonitorActivity.java" 'DiagLog.flushMirrorAsync(this);'
check "diag enabled-service row in layout" "src/main/res/layout/activity_floating_monitor.xml" 'diagEnabledService'
check "diag enabled-service row in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'diagEnabledService'
check "diag last-add row in layout" "src/main/res/layout/activity_floating_monitor.xml" 'diagLastAdd'
check "diag last-add row in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'diagLastAdd'
check "run checks button in layout" "src/main/res/layout/activity_floating_monitor.xml" 'btnRunChecks'
check "run checks button in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'btnRunChecks'
check "battery history hint in layout" "src/main/res/layout/activity_floating_monitor.xml" 'batteryHistoryHint'
check "battery history hint in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'batteryHistoryHint'
check "provider serves the mirror dir" "src/main/res/xml/file_paths.xml" 'AppSlayer/logs/'
# (No new storage permission was added for the mirror — it is
# written through the root shell. The manifest's permission set is
# verified unchanged against the previous release by aapt at ship
# time; note MANAGE_EXTERNAL_STORAGE was already declared in 6.3.0.)

# 6.3.1: battery history dependency is stated, and the notification
# service self-heals from the main screen.
check "battery history hint string (ID)" "src/main/res/values/strings.xml" 'battery_history_hint'
check "notification self-heal on main screen" "$SRC/MainActivity.java" 'notification preference on but service not running'

# 6.3.1: signed release builds (keystore from CI secrets only).
check "workflow decodes the keystore" ".github/workflows/build.yml" 'Decode release keystore'
check "workflow reads keystore secret" ".github/workflows/build.yml" 'KEYSTORE_BASE64'
check "workflow passes keystore path" ".github/workflows/build.yml" 'KEYSTORE_PATH'
check "gradle signing config" "build.gradle" 'signingConfigs'
check "gradle signing from env" "build.gradle" "System.getenv('KEYSTORE_PATH')"

# 6.3.1: changelog lives in its own files; license is GPL v3.
check_absent "no version entries left in README" "README.md" '### 6.'
check_absent "no version entries left in README_ID" "README_ID.md" '### 6.'
check "README links the changelog" "README.md" '[CHANGELOG.md](CHANGELOG.md)'
check "README_ID links the changelog" "README_ID.md" '[CHANGELOG_ID.md](CHANGELOG_ID.md)'
check "CHANGELOG has the 6.3.1 entry" "CHANGELOG.md" '## 6.3.1'
check "CHANGELOG_ID has the 6.3.1 entry" "CHANGELOG_ID.md" '## 6.3.1'
check "LICENSE is the GPL text" "LICENSE" 'GNU GENERAL PUBLIC LICENSE'

# 6.3.2: function self-test ("Tes Fungsi") — the sandbox the user
# asked for: every capability exercised for real, per-function
# verdicts, results also in the diagnostic log.
check "function tester exists" "$SRC/FunctionTester.java" 'class FunctionTester'
check "function test activity exists" "$SRC/FunctionTestActivity.java" 'class FunctionTestActivity'
check "test activity in manifest" "src/main/AndroidManifest.xml" 'FunctionTestActivity'
check "test button in layout" "src/main/res/layout/activity_floating_monitor.xml" 'btnTestFunctions'
check "test button in v21 layout" "src/main/res/layout-v21/activity_floating_monitor.xml" 'btnTestFunctions'
check "test button wired" "$SRC/FloatingMonitorActivity.java" 'R.id.btnTestFunctions'
check "tests logged as TEST" "$SRC/FunctionTester.java" 'DiagLog.log("TEST"'
check "module write-back test uses AppPaths" "$SRC/FunctionTester.java" 'AppPaths.MODE_FILE'
check "mirror test uses sync flush" "$SRC/FunctionTester.java" 'DiagLog.flushMirrorSync'
check "sync mirror flush exists" "$SRC/DiagLog.java" 'flushMirrorSync'

# 6.3.2: root access is visible and retryable — a fresh su -c id
# probe, a verified-handshake persistent shell that cannot latch
# dead, retry kicks, and the main-screen warning row.
check "fresh root probe exists" "$SRC/SystemStats.java" 'probeRootFresh'
check "fresh probe runs su -c id" "$SRC/SystemStats.java" '"su", "-c", "id"'
check "shell verified getter" "$SRC/SystemStats.java" 'isShellVerified()'
check "shell reset exists" "$SRC/SystemStats.java" 'resetShell()'
check "shell retry kick exists" "$SRC/SystemStats.java" 'kickRootShell()'
check "shell handshake verify" "$SRC/SystemStats.java" 'runHandshake'
check "overlay service kicks the shell" "$SRC/FloatingOverlayService.java" 'SystemStats.kickRootShell()'
check "main screen kicks the shell" "$SRC/MainActivity.java" 'SystemStats.kickRootShell()'
check "root row in main layout" "src/main/res/layout/main.xml" 'rowRootWarning'
check "root row in v21 main layout" "src/main/res/layout-v21/main.xml" 'rowRootWarning'
check "request root button in main layout" "src/main/res/layout/main.xml" 'btnRequestRoot'
check "request root handler" "$SRC/MainActivity.java" 'requestRootAccess()'
check "main su open split out" "$SRC/MainActivity.java" 'tryOpenSu()'
check "plain thermal enumeration split out" "$SRC/SystemStats.java" 'readPlainThermalSensors()'
check "fps source tracked" "$SRC/SystemStats.java" 'getLastFpsSource()'

# 6.3.2: classic overlay stacking — measured heights via PureCalc,
# re-stacked on rebuild and on line-count changes, dragged windows
# left alone.
check "stack offsets in PureCalc" "$SRC/PureCalc.java" 'public static int[] stackOffsets'
check "service restacks classic windows" "$SRC/FloatingOverlayService.java" 'restackClassicWindows()'
check_count "restack called on rebuild and on text change" "$SRC/FloatingOverlayService.java" 'restackClassicWindows();' 2
check "drag marks a window custom" "$SRC/FloatingOverlayService.java" '"_custompos"'

# 6.3.2: changelog entries.
check "CHANGELOG has the 6.3.2 entry" "CHANGELOG.md" '## 6.3.2'
check "CHANGELOG_ID has the 6.3.2 entry" "CHANGELOG_ID.md" '## 6.3.2'

# 6.3.3: Battery Information became a structured, themed layout —
# header + cards, values updated in place by id on every refresh.
# 6.3.7: that layout is a full screen (BatteryInfoActivity) with
# the same ids and bindings; the dialog form is gone entirely.
check "battery screen layout exists" "src/main/res/layout/activity_battery_info.xml" 'battLevelText'
check "battery screen layout exists (v21)" "src/main/res/layout-v21/activity_battery_info.xml" 'battLevelText'
check "battery screen content view set" "$SRC/BatteryInfoActivity.java" 'R.layout.activity_battery_info'
check "battery snapshot applied on refresh" "$SRC/BatteryInfoActivity.java" 'applyBatteryInfo(info)'
check "battery views bound from the layout" "$SRC/BatteryInfoActivity.java" 'findViewById(R.id.battLevelText)'
check_absent "battery dialog wiring gone from monitor screen" "$SRC/FloatingMonitorActivity.java" 'showBatteryStatus'
check_absent "old battery text-dump string removed (ID)" "src/main/res/values/strings.xml" 'battery_info_message'
check_absent "old battery text-dump string removed (EN)" "src/main/res/values-en/strings.xml" 'battery_info_message'
check "battery voltage row wired" "$SRC/BatteryInfoActivity.java" 'info.voltageText'
check "battery health bar hides when not numeric" "$SRC/BatteryInfoActivity.java" 'battSysHealthBar.setVisibility(View.GONE)'
for batt_id in battLevelText battLevelBar battStatusText battHealthText \
        battDesignValue battFullValue battSysHealthValue battSysHealthBar \
        battLiveStatusValue battCurrentValue battPowerValue battVoltageValue \
        battEstFullValue battEstLeftValue battRealCapValue \
        battHealthBig battHealthBasis battHealthBar battLastCalcValue \
        battSessionState battSessionBody battSessionLevelsValue \
        battSessionDurationValue battSessionAvgCurrentValue \
        battSessionAvgPowerValue battSessionMahValue battSessionsList \
        battSessionEmpty; do
    check "battery id in layout: $batt_id" "src/main/res/layout/activity_battery_info.xml" "@+id/$batt_id"
    check "battery id in v21 layout: $batt_id" "src/main/res/layout-v21/activity_battery_info.xml" "@+id/$batt_id"
    check "battery id referenced in code: $batt_id" "$SRC/BatteryInfoActivity.java" "R.id.$batt_id"
done

# 6.3.3: root probe outcomes are split — "su could not even be
# executed" (error=13) is its own case with its own wording, in the
# self-test and in the main screen's request-root toast.
check "probe reports execdenied" "$SRC/SystemStats.java" '"execdenied"'
check "probe detects error=13" "$SRC/SystemStats.java" 'msg.contains("error=13")'
check "self-test maps execdenied wording" "$SRC/FunctionTester.java" 'R.string.test_root_exec_denied'
check "request-root toast maps execdenied" "$SRC/MainActivity.java" 'R.string.toast_root_exec_denied'
check "request-root toast maps notfound" "$SRC/MainActivity.java" 'R.string.toast_root_notfound'
check "request-root toast maps timeout" "$SRC/MainActivity.java" 'R.string.toast_root_timeout'
check "exec-denied string (ID)" "src/main/res/values/strings.xml" 'test_root_exec_denied'
check "exec-denied string (EN)" "src/main/res/values-en/strings.xml" 'test_root_exec_denied'
check "exec-denied toast (ID)" "src/main/res/values/strings.xml" 'toast_root_exec_denied'
check "exec-denied toast (EN)" "src/main/res/values-en/strings.xml" 'toast_root_exec_denied'

# 6.3.3: changelog entries.
check "CHANGELOG has the 6.3.3 entry" "CHANGELOG.md" '## 6.3.3'
check "CHANGELOG_ID has the 6.3.3 entry" "CHANGELOG_ID.md" '## 6.3.3'

# 6.3.4: the main screen loses the stock action bar. MainActivity
# gets its own NoActionBar theme (sub-screens keep the app theme),
# the status bar takes the page color, and the title lives in an
# in-layout header row.
check "MainActivity uses MainTheme" "src/main/AndroidManifest.xml" '@style/MainTheme'
check "MainTheme is Holo NoActionBar (base)" "src/main/res/values/styles.xml" 'Theme.Holo.NoActionBar'
check "MainTheme is Material NoActionBar (v21)" "src/main/res/values-v21/styles.xml" 'Theme.Material.NoActionBar'
check "status bar takes the page color" "src/main/res/values-v21/styles.xml" 'android:statusBarColor'
check "header row in main layout" "src/main/res/layout/main.xml" 'headerRow'
check "header row in v21 main layout" "src/main/res/layout-v21/main.xml" 'headerRow'
check "centered title in main layout" "src/main/res/layout/main.xml" 'textHeaderTitle'
check "centered title in v21 main layout" "src/main/res/layout-v21/main.xml" 'textHeaderTitle'
check "title text is app_name" "src/main/res/layout/main.xml" '@string/app_name'

# 6.3.5: the header is title-only. The gear and overflow buttons
# are gone, and so is everything that existed only for them: the
# header wiring, the PopupMenu, the options-menu callbacks, the
# menu resource and its icons, and the overflow content string.
# Both actions they carried live in the Features list instead —
# the Floating Monitor row and the language row — asserted below.
check_absent "gear button gone from main layout" "src/main/res/layout/main.xml" 'btnHeaderMonitor'
check_absent "gear button gone from v21 main layout" "src/main/res/layout-v21/main.xml" 'btnHeaderMonitor'
check_absent "overflow button gone from main layout" "src/main/res/layout/main.xml" 'btnHeaderOverflow'
check_absent "overflow button gone from v21 main layout" "src/main/res/layout-v21/main.xml" 'btnHeaderOverflow'
check_absent "header wiring removed" "$SRC/MainActivity.java" 'wireHeaderButtons'
check_absent "popup menu removed" "$SRC/MainActivity.java" 'PopupMenu'
check_absent "options menu creation removed" "$SRC/MainActivity.java" 'onCreateOptionsMenu'
check_absent "options item handler removed" "$SRC/MainActivity.java" 'onOptionsItemSelected'
check_absent "main menu reference removed" "$SRC/MainActivity.java" 'R.menu.main_menu'
check_absent "overflow string removed (ID)" "src/main/res/values/strings.xml" 'header_more_options'
check_absent "overflow string removed (EN)" "src/main/res/values-en/strings.xml" 'header_more_options'
if [ ! -f src/main/res/menu/main_menu.xml ]; then
    echo "PASS menu resource file deleted"
    PASS=$((PASS + 1))
else
    echo "FAIL menu resource file deleted (src/main/res/menu/main_menu.xml still exists)"
    FAIL=$((FAIL + 1))
fi
check "monitor row in main layout" "src/main/res/layout/main.xml" 'rowFloatingMonitor'
check "monitor row in v21 main layout" "src/main/res/layout-v21/main.xml" 'rowFloatingMonitor'
check "language row in main layout" "src/main/res/layout/main.xml" 'rowLanguage'
check "language row in v21 main layout" "src/main/res/layout-v21/main.xml" 'rowLanguage'
check "monitor row wired" "$SRC/MainActivity.java" 'R.id.rowFloatingMonitor'
check "language row wired" "$SRC/MainActivity.java" 'R.id.rowLanguage'

# 6.3.4: language picking is a bottom sheet now — a dark rounded
# panel with a radio list (system default + six languages), applied
# through the same LocaleHelper save + recreate path; the old
# two-language toggle is gone. Entry point: the main screen
# language row in the Features list.
check "language sheet layout" "src/main/res/layout/dialog_language_picker.xml" 'radioLanguages'
check "language sheet layout (v21)" "src/main/res/layout-v21/dialog_language_picker.xml" 'radioLanguages'
check "sheet panel drawable used" "src/main/res/layout/dialog_language_picker.xml" '@drawable/bg_language_sheet'
check "sheet title from strings" "src/main/res/layout/dialog_language_picker.xml" '@string/language_picker_title'
check "sheet cancel button" "src/main/res/layout/dialog_language_picker.xml" 'btnLanguageCancel'
check "sheet drawable is rounded panel" "src/main/res/drawable/bg_language_sheet.xml" 'topLeftRadius="16dp"'
check "picker theme (base)" "src/main/res/values/styles.xml" 'LanguagePickerTheme'
check "picker theme (v21)" "src/main/res/values-v21/styles.xml" 'LanguagePickerTheme'
check "picker shown by MainActivity" "$SRC/MainActivity.java" 'showLanguagePicker()'
check "picker inflates the sheet layout" "$SRC/MainActivity.java" 'R.layout.dialog_language_picker'
check "picker anchored to the bottom" "$SRC/MainActivity.java" 'Gravity.BOTTOM'
check "picker saves through LocaleHelper" "$SRC/MainActivity.java" 'LocaleHelper.saveLanguage(MainActivity.this, next)'
check "language codes include zh hi es ar" "$SRC/MainActivity.java" '"", "id", "en", "zh", "hi", "es", "ar"'
check_absent "old language toggle removed" "$SRC/MainActivity.java" 'toggleLanguage'
check "picker title string (ID)" "src/main/res/values/strings.xml" 'language_picker_title'
check "picker title string (ZH)" "src/main/res/values-zh/strings.xml" 'language_picker_title'
check "picker title string (HI)" "src/main/res/values-hi/strings.xml" 'language_picker_title'
check "picker title string (ES)" "src/main/res/values-es/strings.xml" 'language_picker_title'
check "picker title string (AR)" "src/main/res/values-ar/strings.xml" 'language_picker_title'

# 6.3.4: README screenshots are the current build's screens.
check "README shows the main screenshot" "README.md" 'screenshots/main.png'
check "README shows the language picker shot" "README.md" 'screenshots/language-picker.png'
check "README_ID shows the main screenshot" "README_ID.md" 'screenshots/main.png'
check "README_ID shows the language picker shot" "README_ID.md" 'screenshots/language-picker.png'
check_absent "old screenshots gone from README" "README.md" 'Screenshot_20260927'
check_absent "old screenshots gone from README_ID" "README_ID.md" 'Screenshot_20260927'

# 6.3.4: changelog entries.
check "CHANGELOG has the 6.3.4 entry" "CHANGELOG.md" '## 6.3.4'
check "CHANGELOG_ID has the 6.3.4 entry" "CHANGELOG_ID.md" '## 6.3.4'

# 6.3.5: changelog entries.
check "CHANGELOG has the 6.3.5 entry" "CHANGELOG.md" '## 6.3.5'
check "CHANGELOG_ID has the 6.3.5 entry" "CHANGELOG_ID.md" '## 6.3.5'

# 6.3.6 (superseded by 6.3.7): the battery dialog got a hand-sized
# window, green bars and an in-panel close button. In 6.3.7 the
# dialog itself is gone — Battery Information is a full screen —
# so everything dialog-only must stay gone: the layout files, the
# panel drawable, the theme, and the sizing code.
if [ ! -f src/main/res/layout/dialog_battery_info.xml ] && [ ! -f src/main/res/layout-v21/dialog_battery_info.xml ]; then
    echo "PASS battery dialog layouts deleted"
    PASS=$((PASS + 1))
else
    echo "FAIL battery dialog layouts deleted (dialog_battery_info.xml still exists)"
    FAIL=$((FAIL + 1))
fi
if [ ! -f src/main/res/drawable/bg_battery_dialog.xml ]; then
    echo "PASS battery dialog panel drawable deleted"
    PASS=$((PASS + 1))
else
    echo "FAIL battery dialog panel drawable deleted (bg_battery_dialog.xml still exists)"
    FAIL=$((FAIL + 1))
fi
check_absent "battery dialog theme gone (Holo)" "src/main/res/values/styles.xml" 'BatteryDialogTheme'
check_absent "battery dialog theme gone (Material)" "src/main/res/values-v21/styles.xml" 'BatteryDialogTheme'
check_absent "no dialog construction in monitor screen" "$SRC/FloatingMonitorActivity.java" 'new Dialog('
check_absent "no dialog sizing in monitor screen" "$SRC/FloatingMonitorActivity.java" 'sizeBatteryDialogWindow'
check_absent "no battery dialog field in monitor screen" "$SRC/FloatingMonitorActivity.java" 'batteryDialog'
check_count "battery bars tinted green" "src/main/res/layout/activity_battery_info.xml" 'android:progressTint="#4caf50"' 3
check_count "battery bars tinted green (v21)" "src/main/res/layout-v21/activity_battery_info.xml" 'android:progressTint="#4caf50"' 3
check_count "battery bar tracks tinted" "src/main/res/layout/activity_battery_info.xml" 'android:progressBackgroundTint="#333333"' 3
check_count "battery bar tracks tinted (v21)" "src/main/res/layout-v21/activity_battery_info.xml" 'android:progressBackgroundTint="#333333"' 3

# 6.3.6: the language picker's radio rows are wrap_content. With a
# match_parent text area, the right-to-left Arabic label aligned
# to the far side of its row, away from the radio; sized to the
# label it stays next to the radio in LTR and RTL alike.
RADIO_OUT=$(python3 - <<'PYEOF'
import re, sys
ok = True
for path in ("src/main/res/layout/dialog_language_picker.xml",
             "src/main/res/layout-v21/dialog_language_picker.xml"):
    text = open(path, encoding="utf-8").read()
    buttons = re.findall(r"<RadioButton\b[^>]*", text)
    if len(buttons) != 7:
        print(f"FAIL picker radio count in {path}: {len(buttons)}")
        ok = False
        continue
    for b in buttons:
        if 'android:layout_width="wrap_content"' not in b:
            print(f"FAIL picker radio not wrap_content in {path}: {b[:80]}")
            ok = False
if ok:
    print("PASS picker radios wrap_content (7 x 2 layouts)")
    sys.exit(0)
sys.exit(1)
PYEOF
)
echo "$RADIO_OUT"
if echo "$RADIO_OUT" | grep -q '^PASS'; then
    PASS=$((PASS + 1))
else
    FAIL=$((FAIL + 1))
fi

# 6.3.6: changelog entries.
check "CHANGELOG has the 6.3.6 entry" "CHANGELOG.md" '## 6.3.6'
check "CHANGELOG_ID has the 6.3.6 entry" "CHANGELOG_ID.md" '## 6.3.6'

# 6.3.7: text contrast. Whole blocks of the Floating Monitor
# screen were invisible on the user's phone: the battery history
# title and summary, the temperature sensor title and list, and
# the FPS recorder hint carried NO textColor at all, so they
# inherited near-black text from the light app theme onto the
# black screen. Guard: every TextView in every layout must carry
# an explicit android:textColor, and the five fixed views must
# carry the palette colors.
CONTRAST_OUT=$(python3 - <<'PYEOF'
import glob, re, sys
bad = []
for path in sorted(glob.glob("src/main/res/layout/*.xml") + glob.glob("src/main/res/layout-v21/*.xml")):
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r"<TextView\b[^>]*", text, re.S):
        if "android:textColor" not in m.group(0):
            idm = re.search(r'android:id="@\+id/(\w+)"', m.group(0))
            bad.append(f"{path}: TextView id={idm.group(1) if idm else '?'}")
if bad:
    print("FAIL TextViews without explicit textColor")
    for b in bad:
        print("  " + b)
    sys.exit(1)
print("PASS every TextView in every layout has an explicit textColor")
PYEOF
)
echo "$CONTRAST_OUT"
if echo "$CONTRAST_OUT" | grep -q '^PASS'; then
    PASS=$((PASS + 1))
else
    FAIL=$((FAIL + 1))
fi
check_count "history title uses the accent blue" "src/main/res/layout/activity_floating_monitor.xml" '@string/battery_history_title" android:textSize="16sp" android:textStyle="bold" android:textColor="#448aff"' 1
check_count "history title uses the accent blue (v21)" "src/main/res/layout-v21/activity_floating_monitor.xml" '@string/battery_history_title" android:textSize="16sp" android:textStyle="bold" android:textColor="#448aff"' 1
check_count "sensor title uses the accent blue" "src/main/res/layout/activity_floating_monitor.xml" '@string/temp_sensors_title" android:textSize="16sp" android:textStyle="bold" android:textColor="#448aff"' 1
check_count "sensor title uses the accent blue (v21)" "src/main/res/layout-v21/activity_floating_monitor.xml" '@string/temp_sensors_title" android:textSize="16sp" android:textStyle="bold" android:textColor="#448aff"' 1
check_count "secondary color on the info texts" "src/main/res/layout/activity_floating_monitor.xml" 'android:textColor="#9aa0a6"' 4
check_count "secondary color on the info texts (v21)" "src/main/res/layout-v21/activity_floating_monitor.xml" 'android:textColor="#9aa0a6"' 4

# 6.3.7: battery history no longer depends on the notification
# feature. BatteryLoggingService is a real foreground service
# holding the battery-changed receiver; it is started from the
# main screen, the monitor screen and the boot receiver, and the
# graph hint follows its state.
check "battery logging service is a real Service" "$SRC/BatteryLoggingService.java" 'extends Service'
check "battery logging service in manifest" "src/main/AndroidManifest.xml" 'BatteryLoggingService'
check "battery logging service foreground id" "$SRC/BatteryLoggingService.java" 'NOTIF_ID = 4'
check "battery logging holds the battery receiver" "$SRC/BatteryLoggingService.java" 'BATTERY_CHANGED'
check "battery logging started from main screen" "$SRC/MainActivity.java" 'BatteryLoggingService.start(this)'
check "battery logging started from monitor screen" "$SRC/FloatingMonitorActivity.java" 'BatteryLoggingService.start(this)'
check "battery logging started at boot" "$SRC/BootReceiver.java" 'BatteryLoggingService.start(context)'
check "history hint follows the logging service" "$SRC/FloatingMonitorActivity.java" 'BatteryLoggingService.isRunning() ? View.GONE : View.VISIBLE'
check "battery logging string (ID)" "src/main/res/values/strings.xml" 'battery_logging_notif'
check "battery logging string (AR)" "src/main/res/values-ar/strings.xml" 'battery_logging_notif'

# 6.3.7: FPS without root. FpsCaptureService measures distinct
# presented frames through MediaProjection (system consent, asked
# by the recorder toggle and by the function test) and publishes
# to SystemStats, whose getters prefer a fresh capture value and
# name the source. The recorder runs its own sampling loop and
# every recording finalizes to a session file, zero samples
# included.
check "fps capture service exists" "$SRC/FpsCaptureService.java" 'class FpsCaptureService extends Service'
check "capture service counts distinct frames" "$SRC/FpsCaptureService.java" 'distinctFrames++'
check "capture service publishes its fps" "$SRC/FpsCaptureService.java" 'SystemStats.setProjectionFps'
check "capture service in manifest as mediaProjection" "src/main/AndroidManifest.xml" 'android:foregroundServiceType="mediaProjection"'
check "media projection FGS permission declared" "src/main/AndroidManifest.xml" 'FOREGROUND_SERVICE_MEDIA_PROJECTION'
check "projection fps slot in SystemStats" "$SRC/SystemStats.java" 'public static void setProjectionFps'
check "fps source can name MediaProjection" "$SRC/SystemStats.java" '"MediaProjection"'
check "recorder toggle asks capture consent" "$SRC/FloatingMonitorActivity.java" 'createScreenCaptureIntent'
check "function test asks capture consent" "$SRC/FunctionTestActivity.java" 'createScreenCaptureIntent'
check "recorder runs its own sampling loop" "$SRC/FpsRecorder.java" 'startSamplingLoop()'
check "recorder stamps the end marker" "$SRC/FpsRecorder.java" '"#end,"'
check "session parser reads the end marker" "$SRC/FpsSession.java" '"#end,"'
check "no-data sessions stay listed" "$SRC/FpsSession.java" 'return new FpsSession(file, startMs, endMs, null, samples)'
check "sessions list marks no-data" "$SRC/FpsSessionsActivity.java" 'fps_session_no_data'
check "capture denied string (ID)" "src/main/res/values/strings.xml" 'fps_capture_denied'
check "capture denied string (EN)" "src/main/res/values-en/strings.xml" 'fps_capture_denied'

# 6.3.7: thermal without root. The Android Thermal API status is
# the non-root thermal source: shown on the monitor screen
# (labelled as a status) when no per-sensor reading exists, and
# counted by the function test.
check "thermal status API in SystemStats" "$SRC/SystemStats.java" 'getCurrentThermalStatus'
check "thermal status accessor" "$SRC/SystemStats.java" 'public static int getThermalStatus'
check "thermal status shown on monitor screen" "$SRC/FloatingMonitorActivity.java" 'R.string.temp_thermal_status'
check "thermal API counts in function test" "$SRC/FunctionTester.java" 'R.string.test_thermal_api_detail'
check "thermal status strings (ID)" "src/main/res/values/strings.xml" 'thermal_status_normal'
check "thermal status strings (ZH)" "src/main/res/values-zh/strings.xml" 'thermal_status_normal'
check "thermal status strings (AR)" "src/main/res/values-ar/strings.xml" 'thermal_status_normal'

# 6.3.7: changelog entries.
check "CHANGELOG has the 6.3.7 entry" "CHANGELOG.md" '## 6.3.7'
check "CHANGELOG_ID has the 6.3.7 entry" "CHANGELOG_ID.md" '## 6.3.7'

# 6.3.8: the FPS report is a card layout — header card, a stat
# grid fed by the same PureCalc summary, and chart cards — instead
# of the old plain text dump.
for rep_id in reportApp reportMeta reportEmpty statsCard statAvgValue \
        statMaxValue statMinValue statLow1Value statLow5Value \
        statSmoothValue statCpuTempValue statGpuTempValue statPowerValue \
        graphFps graphTemp graphFrameTime reportNote; do
    check "report id in layout: $rep_id" "src/main/res/layout/activity_fps_report.xml" "@+id/$rep_id"
done
check_absent "report text dump gone from activity" "$SRC/FpsReportActivity.java" 'fps_report_message'
for loc in values values-en values-zh values-hi values-es values-ar; do
    check_absent "report text dump string removed ($loc)" "src/main/res/$loc/strings.xml" 'fps_report_message'
    check_absent "smoothness double percent fixed ($loc)" "src/main/res/$loc/strings.xml" '±20%%'
done
check "smoothness note not formatted" "src/main/res/values/strings.xml" 'name="fps_smoothness_note" formatted="false"'
check "report stat strings (ID)" "src/main/res/values/strings.xml" 'stat_fps_avg'
check "report stat strings (AR)" "src/main/res/values-ar/strings.xml" 'stat_fps_avg'
check "temp empty text is temperature-specific" "$SRC/FpsReportActivity.java" 'R.string.fps_no_temp_samples'

# 6.3.8: modern charts — smoothed lines, gradient fill, dashed
# gridlines, and axis ranges from PureCalc that can never dip
# below zero (the old view printed a -3.1 label).
check "graph dashed gridlines" "$SRC/SessionGraphView.java" 'DashPathEffect'
check "graph gradient fill" "$SRC/SessionGraphView.java" 'LinearGradient'
check "graph zero-based axis" "$SRC/SessionGraphView.java" 'PureCalc.axisRangeZero'
check "graph zoomed axis" "$SRC/SessionGraphView.java" 'PureCalc.axisRangeZoom'
check "graph overlay series" "$SRC/SessionGraphView.java" 'setOverlay'
check "graph legend" "$SRC/SessionGraphView.java" 'setLegend'
check "axis helper in PureCalc" "$SRC/PureCalc.java" 'public static double[] axisRangeZero'
check "axis zoom helper in PureCalc" "$SRC/PureCalc.java" 'public static double[] axisRangeZoom'
check "report feeds temp overlay" "$SRC/FpsReportActivity.java" 'fpsGraph.setOverlay'

# 6.3.8: foreground app label without root — UsageStats events
# first, dumpsys fallback, and an honest dash when neither knows.
check "usage-stats foreground lookup" "$SRC/SystemStats.java" 'getForegroundPackageNoRoot'
check "recorder tries no-root foreground first" "$SRC/FpsRecorder.java" 'getForegroundPackageNoRoot'

# 6.3.8: sessions list rows use the card layout.
check "session row layout exists" "src/main/res/layout/item_fps_session.xml" 'sessionRowTitle'
check "sessions list inflates card rows" "$SRC/FpsSessionsActivity.java" 'R.layout.item_fps_session'

# 6.3.8: battery health card leads the Battery screen; the old
# Capacity / Real Capacity cards are merged into it.
check "battery health card title (ID)" "src/main/res/values/strings.xml" 'batt_card_health'
check_absent "old capacity card title removed (ID)" "src/main/res/values/strings.xml" 'batt_card_capacity'
check_absent "old real card title removed (ID)" "src/main/res/values/strings.xml" 'batt_card_real"'
check "battery health percent computed" "$SRC/BatteryInfoActivity.java" 'info.healthPercent'
check "battery health bar applied" "$SRC/BatteryInfoActivity.java" 'battHealthBar.setProgress'

# 6.3.8: charge sessions — recorded from the battery logging
# service's stream only (MyService records history too and must
# not double-feed), stored like the FPS sessions, shown on the
# Battery screen.
check "charge sessions class exists" "$SRC/ChargeSessions.java" 'class ChargeSessions'
check "charge sessions dir name" "$SRC/ChargeSessions.java" 'DIR_NAME = "battery_sessions"'
check "charge session tracker in PureCalc" "$SRC/PureCalc.java" 'class ChargeSessionTracker'
check_count "logging service feeds charge sessions" "$SRC/BatteryLoggingService.java" 'ChargeSessions.onBatteryEvent' 2
check_absent "notification service does not feed charge sessions" "$SRC/MyService.java" 'ChargeSessions'
check "battery screen reads live session" "$SRC/BatteryInfoActivity.java" 'ChargeSessions.current'
check "battery screen lists sessions" "$SRC/BatteryInfoActivity.java" 'ChargeSessions.list'
check "session row string (ID)" "src/main/res/values/strings.xml" 'batt_session_row'
check "session row string (AR)" "src/main/res/values-ar/strings.xml" 'batt_session_row'

# 6.3.8: changelog entries.
check "CHANGELOG has the 6.3.8 entry" "CHANGELOG.md" '## 6.3.8'
check "CHANGELOG_ID has the 6.3.8 entry" "CHANGELOG_ID.md" '## 6.3.8'

# 6.3.9: the Floating Monitor screen joins the card design
# language. Every section is a card, the diagnosis rows are
# label -> value with the value colored by the state the code
# already knows, the buttons use the app button drawable instead
# of the stock Holo blocks, the maroon Battery Status slab is a
# card row, and the switches are tinted with no on/off text box.
# Presentation only — every pre-existing view id must survive.
for mon_id in monitorContainer diagPermission diagService diagWindows \
        diagEnabledService diagLastAdd diagNotifPermission diagNotifService \
        btnTestOverlay btnTestFunctions btnRunChecks btnShareLog btnClearLog \
        diagLogPathHint btnBatteryStatus batteryGraph batteryHistorySummary \
        batteryHistoryHint tempSensorList seekTransparency seekTextSize \
        switchSnapToBar switchClickThrough switchFullscreen switchLandscape \
        switchDarkText switchTextShadow switchBoot radioMonitorStyle \
        radioStyleClassic radioStyleMini switchCpu switchTemp switchBattery \
        switchRam switchBatteryCurrent switchSignal switchFps switchProcess \
        switchRecordFps recordFpsStatus btnFpsSessions buttonReset; do
    check "monitor id kept: $mon_id" "src/main/res/layout/activity_floating_monitor.xml" "@+id/$mon_id"
    check "monitor id kept (v21): $mon_id" "src/main/res/layout-v21/activity_floating_monitor.xml" "@+id/$mon_id"
done
for lay in src/main/res/layout/activity_floating_monitor.xml src/main/res/layout-v21/activity_floating_monitor.xml; do
    check_count "monitor screen cards ($lay)" "$lay" '@drawable/card_background' 8
    check_count "monitor app-style buttons ($lay)" "$lay" '@drawable/btn_card' 6
    check_absent "maroon battery slab gone ($lay)" "$lay" '#880e4f'
    check "battery status is a card row ($lay)" "$lay" 'android:drawableStart="@drawable/ic_battery"'
    check "battery status chevron ($lay)" "$lay" '@drawable/ic_chevron'
    check "diag label row ($lay)" "$lay" '@string/diag_label_permission'
    check "history stats rows ($lay)" "$lay" 'batteryHistorySinceValue'
    check "thermal status row ($lay)" "$lay" 'tempThermalStatus'
    check "switches hide the text box ($lay)" "$lay" 'android:showText="false"'
    check_count "switches use the pill track ($lay)" "$lay" 'android:track="@drawable/switch_track"' 16
    check_count "switches use the clear thumb ($lay)" "$lay" 'android:thumb="@drawable/switch_thumb_clear"' 16
    check_count "switches pin the minimum width ($lay)" "$lay" 'android:switchMinWidth="52dp"' 16
    check_count "switches zero the extra padding ($lay)" "$lay" 'android:switchPadding="0dp" android:padding="0dp"' 16
    check_count "switches are pill sized ($lay)" "$lay" 'android:layout_width="52dp" android:layout_height="32dp"' 16
    check_absent "switch thumb tint gone ($lay)" "$lay" 'switch_thumb_tint'
    check_absent "switch track tint gone ($lay)" "$lay" 'switch_track_tint'
    check "reset is a quiet text button ($lay)" "$lay" 'android:textColor="#ef5350"'
done
check "diag values colored in refresh" "$SRC/FloatingMonitorActivity.java" 'DIAG_COLOR_OK'
check "diag value-only text" "$SRC/FloatingMonitorActivity.java" 'perm.setText(granted ? R.string.state_granted : R.string.state_not_granted)'
check_absent "old diag line format unused" "$SRC/FloatingMonitorActivity.java" 'R.string.diag_permission'
check "history rows populated" "$SRC/FloatingMonitorActivity.java" 'populateBatteryHistory()'
check_absent "history text block gone" "$SRC/FloatingMonitorActivity.java" 'buildHistorySummary'
check "thermal status builder" "$SRC/FloatingMonitorActivity.java" 'buildThermalStatusText()'
check "diag label string (ID)" "src/main/res/values/strings.xml" 'diag_label_permission'
check "diag label string (AR)" "src/main/res/values-ar/strings.xml" 'diag_label_permission'
for loc in values values-en values-zh values-hi values-es values-ar; do
    check_absent "old diag format string removed ($loc)" "src/main/res/$loc/strings.xml" 'name="diag_windows"'
done

# 6.3.9: changelog entries.
check "CHANGELOG has the 6.3.9 entry" "CHANGELOG.md" '## 6.3.9'
check "CHANGELOG_ID has the 6.3.9 entry" "CHANGELOG_ID.md" '## 6.3.9'

# 6.3.10: the switches get real custom drawables. On the owner's
# phone the 6.3.9 tints still drew the stock Holo bar (long
# rectangle, square thumb, dull dark green). Now every switch is
# a 52x32dp pill: green track in the app's chart/battery green
# when on, muted blue-grey when off, round thumb with an inset.
# The tint color files are gone.
#
# 6.3.11: the widget's own thumb still broke the pill on the
# owner's phone — the widget sizes and positions the thumb
# drawable by its internal math (the inset thumb's 28dp box fed
# a switch-width computation wider than the 52dp layout box, so
# the off knob was clipped away and the on knob spilled past the
# edge; MIUI's Switch clips differently again). The knob now
# lives inside the track drawable as a layer-list layer, the
# widget thumb is a transparent 1dp drawable, and the layouts
# pin switchMinWidth/switchPadding/padding so the track bounds
# are exactly the 52x32dp box.
check "switch track on uses the app green" "src/main/res/drawable/switch_track.xml" '#4caf50'
check "switch track off is blue-grey" "src/main/res/drawable/switch_track.xml" '#39424e'
check_count "switch track states are layer lists" "src/main/res/drawable/switch_track.xml" '<layer-list' 2
check "switch pill corners are fully round" "src/main/res/drawable/switch_track.xml" '<corners android:radius="16dp"/>'
check_absent "switch pill has no fixed size" "src/main/res/drawable/switch_track.xml" '<size'
check "switch knob on is near-white" "src/main/res/drawable/switch_track.xml" '#f5f7fa'
check "switch knob off is light grey" "src/main/res/drawable/switch_track.xml" '#9aa5b1'
check "switch knob rests right when on" "src/main/res/drawable/switch_track.xml" 'android:width="24dp" android:height="24dp" android:gravity="right|center_vertical" android:right="4dp"'
check "switch knob rests left when off" "src/main/res/drawable/switch_track.xml" 'android:width="24dp" android:height="24dp" android:gravity="left|center_vertical" android:left="4dp"'
check "switch clear thumb is transparent" "src/main/res/drawable/switch_thumb_clear.xml" '@android:color/transparent'
check "switch clear thumb is 1dp" "src/main/res/drawable/switch_thumb_clear.xml" '<size android:width="1dp" android:height="1dp"/>'
if [ ! -f src/main/res/drawable/switch_thumb.xml ]; then
    echo "PASS switch thumb drawable removed"
    PASS=$((PASS + 1))
else
    echo "FAIL switch thumb drawable removed"
    FAIL=$((FAIL + 1))
fi
if [ ! -f src/main/res/color/switch_thumb_tint.xml ] && [ ! -f src/main/res/color/switch_track_tint.xml ]; then
    echo "PASS switch tint color files removed"
    PASS=$((PASS + 1))
else
    echo "FAIL switch tint color files removed"
    FAIL=$((FAIL + 1))
fi

# 6.3.10: app detection for FPS sessions. Usage access is the
# one system grant that makes the foreground app readable
# without root: the manifest declares PACKAGE_USAGE_STATS (it
# only lists the app in the usage access settings; nothing is
# granted until the user turns it on there), the recorder card
# shows the state and opens that settings page, recordings split
# per app, and the unknown-app sentence no longer appears — the
# list and the report both show a dash, for old files too.
check "usage stats permission declared" "src/main/AndroidManifest.xml" 'android.permission.PACKAGE_USAGE_STATS'
check "usage access probe exists" "$SRC/SystemStats.java" 'hasUsageAccess'
check "usage access probe reads AppOps" "$SRC/SystemStats.java" 'OPSTR_GET_USAGE_STATS'
check "split decision is pure" "$SRC/PureCalc.java" 'shouldSplitSession'
check "recorder splits on app change" "$SRC/FpsRecorder.java" 'shouldSplitSession'
check "recorder split finalizes the file" "$SRC/FpsRecorder.java" 'splitSession'
check "app label dash for unknown" "$SRC/FpsSessionsActivity.java" 'return "—";'
check "legacy unknown label handled" "$SRC/FpsSessionsActivity.java" 'isLegacyUnknownLabel'
check_absent "unknown-app string no longer returned" "$SRC/FpsSessionsActivity.java" 'return context.getString(R.string.fps_unknown_app);'
check "usage status wired on resume" "$SRC/FloatingMonitorActivity.java" 'refreshUsageAccessStatus()'
check "usage settings intent" "$SRC/FloatingMonitorActivity.java" 'Settings.ACTION_USAGE_ACCESS_SETTINGS'
for lay in src/main/res/layout/activity_floating_monitor.xml src/main/res/layout-v21/activity_floating_monitor.xml; do
    check "usage access status in layout ($lay)" "$lay" 'usageAccessStatus'
    check "usage access button in layout ($lay)" "$lay" 'btnUsageAccess'
done
check "usage access label string (ID)" "src/main/res/values/strings.xml" 'usage_access_label'
check "usage access label string (AR)" "src/main/res/values-ar/strings.xml" 'usage_access_label'

# 6.3.10: changelog entries.
check "CHANGELOG has the 6.3.10 entry" "CHANGELOG.md" '## 6.3.10'
check "CHANGELOG_ID has the 6.3.10 entry" "CHANGELOG_ID.md" '## 6.3.10'

# 6.3.11: changelog entries.
check "CHANGELOG has the 6.3.11 entry" "CHANGELOG.md" '## 6.3.11'
check "CHANGELOG_ID has the 6.3.11 entry" "CHANGELOG_ID.md" '## 6.3.11'

# String parity across ALL locales: same key count everywhere.
BASE_COUNT=$(grep -c '<string ' src/main/res/values/strings.xml)
PARITY_OK=1
for loc in values-en values-zh values-hi values-es values-ar; do
    C=$(grep -c '<string ' "src/main/res/$loc/strings.xml")
    if [ "$C" != "$BASE_COUNT" ]; then
        echo "FAIL string count $loc=$C base=$BASE_COUNT"
        PARITY_OK=0
    fi
done
if [ "$PARITY_OK" = "1" ]; then
    echo "PASS string parity all locales ($BASE_COUNT)"
    PASS=$((PASS + 1))
else
    FAIL=$((FAIL + 1))
fi

# Per-key format-specifier parity across all locales: every key in
# every locale must carry the same multiset of format specifiers
# (index + conversion) as the Indonesian base, so a translation can
# never drop or reorder a %1$s / %2$d the code fills in.
SPEC_OUT=$(python3 - <<'PYEOF'
import re, sys
from collections import Counter

LOCALES = ["values", "values-en", "values-zh", "values-hi", "values-es", "values-ar"]
# Only two token kinds are real in this codebase (verified against
# the base file): indexed specifiers (%1$s, %2$.2f, ...) and the %%
# literal. A bare "%" in prose ("95% saat") is not a specifier and
# must not count, or literal-percent notes false-positive.
SPEC = re.compile(r"%%|%(\d+)\$[-+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")
ENTRY = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)

def tokens(value):
    out = Counter()
    for m in SPEC.finditer(value):
        if m.group(0) == "%%":
            out[("%", "%")] += 1
        else:
            out[(m.group(1), m.group(2))] += 1
    return out

def load(loc):
    with open(f"src/main/res/{loc}/strings.xml", encoding="utf-8") as f:
        text = f.read()
    return {k: tokens(v) for k, v in ENTRY.findall(text)}

base = load("values")
problems = []
for loc in LOCALES[1:]:
    other = load(loc)
    if set(other) != set(base):
        problems.append(f"{loc}: key set differs (missing={sorted(set(base)-set(other))[:3]} extra={sorted(set(other)-set(base))[:3]})")
        continue
    for key in base:
        if other[key] != base[key]:
            problems.append(f"{loc}:{key}: base={sorted(base[key])} got={sorted(other[key])}")
if problems:
    print("FAIL format-specifier parity")
    for p in problems[:10]:
        print("  " + p)
    sys.exit(1)
print(f"PASS format-specifier parity ({len(base)} keys x {len(LOCALES)} locales)")
PYEOF
)
echo "$SPEC_OUT"
if echo "$SPEC_OUT" | grep -q '^PASS'; then
    PASS=$((PASS + 1))
else
    FAIL=$((FAIL + 1))
fi

echo "Parity checklist: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
