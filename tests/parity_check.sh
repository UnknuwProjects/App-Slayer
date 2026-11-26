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

# Battery dialog: single instance + live refresh pairing.
check "dialog single-instance guard" "$SRC/FloatingMonitorActivity.java" 'batteryDialog.isShowing()'
check "dialog busy guard" "$SRC/FloatingMonitorActivity.java" 'batteryInfoBusy'
check "dialog refresh starts" "$SRC/FloatingMonitorActivity.java" 'startBatteryDialogRefresh()'
check "dialog dismiss stops refresh" "$SRC/FloatingMonitorActivity.java" 'batteryDialogRefreshing = false'
check "dialog power row wired" "$SRC/FloatingMonitorActivity.java" 'PureCalc.powerWatts'
check "capacity chain widened to all power_supply dirs" "$SRC/FloatingMonitorActivity.java" 'powerSupplyDirs()'
check "slope fallback for estimates" "$SRC/FloatingMonitorActivity.java" 'PureCalc.minutesToFull'
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
check "recorder fed by overlay service" "$SRC/FloatingOverlayService.java" 'FpsRecorder.sampleNow'
check "recorder finalized on service destroy" "$SRC/FloatingOverlayService.java" 'FpsRecorder.stop(this)'
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

# 6.3.3: Battery Information is a structured, themed layout now —
# header + cards, values updated in place by id on every refresh.
# The one-big-message-string dialog is gone.
check "battery dialog layout exists" "src/main/res/layout/dialog_battery_info.xml" 'battLevelText'
check "battery dialog layout exists (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" 'battLevelText'
check "battery dialog inflated" "$SRC/FloatingMonitorActivity.java" 'R.layout.dialog_battery_info'
check "battery dialog dark theme" "$SRC/FloatingMonitorActivity.java" 'R.style.BatteryDialogTheme'
check "battery dialog theme (Holo)" "src/main/res/values/styles.xml" 'BatteryDialogTheme'
check "battery dialog theme (Material)" "src/main/res/values-v21/styles.xml" 'BatteryDialogTheme'
check_count "battery snapshot applied at create and on refresh" "$SRC/FloatingMonitorActivity.java" 'applyBatteryInfo(info)' 2
check "battery views bound from the layout" "$SRC/FloatingMonitorActivity.java" 'bindBatteryDialogViews(content)'
check_absent "battery dialog no longer uses setMessage" "$SRC/FloatingMonitorActivity.java" 'setMessage('
check_absent "old battery text-dump string removed (ID)" "src/main/res/values/strings.xml" 'battery_info_message'
check_absent "old battery text-dump string removed (EN)" "src/main/res/values-en/strings.xml" 'battery_info_message'
check "battery voltage row wired" "$SRC/FloatingMonitorActivity.java" 'info.voltageText'
check "battery health bar hides when not numeric" "$SRC/FloatingMonitorActivity.java" 'battSysHealthBar.setVisibility(View.GONE)'
for batt_id in battLevelText battLevelBar battStatusText battHealthText \
        battDesignValue battFullValue battSysHealthValue battSysHealthBar \
        battLiveStatusValue battCurrentValue battPowerValue battVoltageValue \
        battEstFullValue battEstLeftValue battRealCapValue battRealHealthValue \
        battLastCalcValue battScroll battCloseButton; do
    check "battery id in layout: $batt_id" "src/main/res/layout/dialog_battery_info.xml" "@+id/$batt_id"
    check "battery id in v21 layout: $batt_id" "src/main/res/layout-v21/dialog_battery_info.xml" "@+id/$batt_id"
    check "battery id referenced in code: $batt_id" "$SRC/FloatingMonitorActivity.java" "R.id.$batt_id"
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

# 6.3.6: the battery dialog is a plain Dialog with a hand-sized
# window. As a stock alert dialog the custom view measured itself
# at full content height (taller than the screen), the panel ran
# off the top of the display, the bars took the theme accent, and
# the close button sat in the stock grey button strip. Now: the
# window gets 24 dp side margins and an 88%-of-screen height cap,
# the content scrolls inside the panel, the close button is a
# layout view, and both bars carry the app green.
check "battery dialog built as a plain Dialog" "$SRC/FloatingMonitorActivity.java" 'new Dialog('
check "battery dialog window sized in code" "$SRC/FloatingMonitorActivity.java" 'sizeBatteryDialogWindow(dialog, content)'
check "battery dialog height capped at 88 percent" "$SRC/FloatingMonitorActivity.java" 'metrics.heightPixels * 0.88f'
check "battery dialog side margins in code" "$SRC/FloatingMonitorActivity.java" 'metrics.widthPixels - (int) (48 * metrics.density)'
check "battery dialog window background transparent" "$SRC/FloatingMonitorActivity.java" 'window.setBackgroundDrawableResource(android.R.color.transparent)'
check "battery dialog centered" "$SRC/FloatingMonitorActivity.java" 'params.gravity = Gravity.CENTER'
check_absent "battery dialog no longer an alert dialog" "$SRC/FloatingMonitorActivity.java" 'AlertDialog'
check_absent "battery dialog no stock positive button" "$SRC/FloatingMonitorActivity.java" 'setPositiveButton'
check "battery panel drawable is the dark panel" "src/main/res/drawable/bg_battery_dialog.xml" '#111111'
check "battery panel drawable is rounded" "src/main/res/drawable/bg_battery_dialog.xml" 'android:radius="16dp"'
check "battery layout uses the panel drawable" "src/main/res/layout/dialog_battery_info.xml" '@drawable/bg_battery_dialog'
check "battery layout uses the panel drawable (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" '@drawable/bg_battery_dialog'
check "battery scroll view wraps the content" "src/main/res/layout/dialog_battery_info.xml" '@+id/battScroll'
check "battery scroll view wraps the content (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" '@+id/battScroll'
check "battery close button in layout" "src/main/res/layout/dialog_battery_info.xml" '@+id/battCloseButton'
check "battery close button in layout (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" '@+id/battCloseButton'
check_count "battery bars tinted green" "src/main/res/layout/dialog_battery_info.xml" 'android:progressTint="#4caf50"' 2
check_count "battery bars tinted green (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" 'android:progressTint="#4caf50"' 2
check_count "battery bar tracks tinted" "src/main/res/layout/dialog_battery_info.xml" 'android:progressBackgroundTint="#333333"' 2
check_count "battery bar tracks tinted (v21)" "src/main/res/layout-v21/dialog_battery_info.xml" 'android:progressBackgroundTint="#333333"' 2

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
