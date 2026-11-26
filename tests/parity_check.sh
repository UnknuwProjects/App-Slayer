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
        battLastCalcValue; do
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

# String parity between the two locales.
ID_COUNT=$(grep -c '<string ' src/main/res/values/strings.xml)
EN_COUNT=$(grep -c '<string ' src/main/res/values-en/strings.xml)
if [ "$ID_COUNT" = "$EN_COUNT" ]; then
    echo "PASS string parity ID=EN ($ID_COUNT)"
    PASS=$((PASS + 1))
else
    echo "FAIL string parity ID=$ID_COUNT EN=$EN_COUNT"
    FAIL=$((FAIL + 1))
fi

echo "Parity checklist: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
