package com.appslayer.official;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingMonitorActivity extends Activity {

    private boolean isCharging = false;
    private long lastChargeTime = 0;
    private SharedPreferences prefs;

    // Battery Info dialog: single instance, refreshed live while
    // open (see showBatteryStatus).
    private AlertDialog batteryDialog;
    private volatile boolean batteryDialogRefreshing = false;
    private volatile boolean batteryInfoBusy = false;

    // The Battery Information dialog's views (layout
    // dialog_battery_info), bound when the dialog is created and
    // updated in place by the refresh loop.
    private TextView battLevelText;
    private ProgressBar battLevelBar;
    private TextView battStatusText;
    private TextView battHealthText;
    private TextView battDesignValue;
    private TextView battFullValue;
    private TextView battSysHealthValue;
    private ProgressBar battSysHealthBar;
    private TextView battLiveStatusValue;
    private TextView battCurrentValue;
    private TextView battPowerValue;
    private TextView battVoltageValue;
    private TextView battEstFullValue;
    private TextView battEstLeftValue;
    private TextView battRealCapValue;
    private TextView battRealHealthValue;
    private TextView battLastCalcValue;

    // Temperature sensor list refresher, alive only while this
    // screen is in the foreground.
    private volatile boolean sensorRefreshing = false;
    private Thread sensorThread;

    // Diagnosis block refresher: the rows used to update only in
    // onResume, so after toggling a switch the block kept showing
    // the old state and the whole screen felt delayed. While this
    // screen is visible it now re-reads the (cheap, static) values
    // once a second.
    private final Handler diagHandler = new Handler(Looper.getMainLooper());
    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            refreshDiagnostics();
            diagHandler.postDelayed(this, 1000);
        }
    };

    // Last overlay-permission state this screen has logged, so the
    // outcome of a trip to the system settings page lands in the
    // diagnostic log exactly once per change.
    private Boolean lastLoggedOverlayPermission = null;

    // Last battery-capacity outcome written to the log (the dialog
    // rebuilds its text every 2 s; the log only hears about changes).
    private volatile String lastCapacityOutcome = null;
    private String lastCapacitySource = null;

    public interface SeekBarCallback {
        void onProgressChanged(int progress);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_floating_monitor);
        setTitle(getString(R.string.floating_monitor_title));
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
            Toast.makeText(this, R.string.toast_location_needed, Toast.LENGTH_LONG).show();
        }
        // Battery history logging lives in MyService (it records the
        // battery broadcast to the CSV the graph reads). The old code
        // also fired intents at BatteryLoggingService here, but that
        // class is an empty placeholder that is not a Service and is
        // not in the manifest, so those intents never did anything.
        if (Settings.canDrawOverlays(this)) {
            startOverlayService();
            SystemStats.startFpsMonitorThread();
        } else {
            Toast.makeText(this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show();
            startActivity(overlayPermissionIntent());
        }
        startChargingMonitor();
        setupSwitches();
        setupSeekBars();
        setupStyleChooser();
        setupDiagnostics();
        setupRecorder();
        Button btnStatus = (Button) findViewById(R.id.btnBatteryStatus);
        if (btnStatus != null) {
            btnStatus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBatteryStatus();
                }
            });
        }
        TextView summaryView = (TextView) findViewById(R.id.batteryHistorySummary);
        if (summaryView != null) {
            summaryView.setText(buildHistorySummary());
        }
        Button btnReset = (Button) findViewById(R.id.buttonReset);
        if (btnReset != null) {
            btnReset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().clear().apply();
                    setupSwitches();
                    setupSeekBars();
                    setupStyleChooser();
                    Toast.makeText(FloatingMonitorActivity.this, R.string.toast_settings_reset, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private String buildHistorySummary() {
        java.util.List<BatteryHistory.Sample> samples =
                BatteryHistory.load(this, 7L * 24 * 60 * 60 * 1000);
        BatteryHistory.Summary summary = BatteryHistory.summarize(samples);
        if (summary == null) {
            return getString(R.string.collecting_battery_data);
        }
        long hours = summary.sinceFullChargeMs / 3600000;
        long minutes = (summary.sinceFullChargeMs % 3600000) / 60000;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.since_last_full_charge)).append(": ")
                .append(getString(R.string.duration_hm, hours, minutes));
        sb.append("\n").append(getString(R.string.avg_drain)).append(": ")
                .append(getString(R.string.drain_per_hour, summary.drainPerHour));
        if (summary.estimatedHoursLeft >= 0) {
            sb.append("\n").append(getString(R.string.estimated_time_left)).append(": ")
                    .append(getString(R.string.hours_left, summary.estimatedHoursLeft));
        }
        return sb.toString();
    }

    private Intent overlayPermissionIntent() {
        Intent intent = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION");
        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
        return intent;
    }

    // FloatingOverlayService is a foreground service, so it must be
    // started with startForegroundService() — the old code used plain
    // startService() here (the boot receiver already did it right),
    // which modern Android refuses for foreground services in several
    // states, silently leaving the switches on with no overlay.
    private void startOverlayService() {
        try {
            Intent intent = new Intent(this, FloatingOverlayService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_overlay_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startOverlayServiceIfAllowed() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService();
        }
    }

    // Asks the overlay service to rebuild its windows NOW, through an
    // explicit start intent handled in its onStartCommand. This is
    // the primary path: an explicit intent to a running service
    // always lands, unlike the implicit REBUILD broadcast that the
    // toggles relied on before (on the user's phone the broadcast
    // path never produced a rebuild, so the switches looked dead).
    // The broadcast is still sent as a secondary path.
    private void requestOverlayRebuild() {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        try {
            Intent intent = new Intent(this, FloatingOverlayService.class);
            intent.setAction(FloatingOverlayService.ACTION_REBUILD);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_overlay_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
        sendBroadcast(new Intent("REBUILD_FLOATING_MONITOR"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the overlay-permission settings (or from
        // anywhere else): if permission is in place now, make sure the
        // service is actually running. Before, granting the permission
        // and returning changed nothing — the service was never
        // started again and the overlays stayed missing.
        boolean overlayGranted = Settings.canDrawOverlays(this);
        if (lastLoggedOverlayPermission == null
                || lastLoggedOverlayPermission != overlayGranted) {
            lastLoggedOverlayPermission = overlayGranted;
            DiagLog.log("SYS", "overlay permission state: "
                    + (overlayGranted ? "granted" : "NOT granted"));
        }
        if (overlayGranted) {
            startOverlayService();
        }
        refreshDiagnostics();
        diagHandler.removeCallbacks(diagTick);
        diagHandler.post(diagTick);
        startSensorRefresh();
        startRecordStatusUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        diagHandler.removeCallbacks(diagTick);
        stopSensorRefresh();
        stopRecordStatusUpdates();
        dismissBatteryDialog();
        DiagLog.flushMirrorAsync(this);
    }

    @Override
    protected void onDestroy() {
        dismissBatteryDialog();
        super.onDestroy();
    }

    private void setupMonitorSwitch(int id, final String key, boolean defaultValue) {
        Switch switchView = (Switch) findViewById(id);
        if (switchView != null) {
            switchView.setChecked(this.prefs.getBoolean(key, defaultValue));
            switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!isChecked || Settings.canDrawOverlays(FloatingMonitorActivity.this)) {
                    if (isChecked && key.equals(PrefsKeys.MONITOR_SIGNAL)
                            && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
                        startActivity(new Intent(FloatingMonitorActivity.this, PermissionActivity.class));
                    }
                    boolean oldValue = prefs.getBoolean(key, defaultValue);
                    prefs.edit().putBoolean(key, isChecked).apply();
                    DiagLog.log("USER", key + ": " + oldValue + " -> " + isChecked);
                    sendBroadcast(new Intent("UPDATE_FLOATING_MONITOR"));
                    requestOverlayRebuild();
                    return;
                }
                buttonView.setChecked(false);
                Toast.makeText(FloatingMonitorActivity.this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show();
                startActivity(overlayPermissionIntent());
            });
        }
    }

    private void setupSwitch(int id, final String key, boolean defaultValue) {
        Switch switchView = (Switch) findViewById(id);
        if (switchView != null) {
            switchView.setChecked(this.prefs.getBoolean(key, defaultValue));
            switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                boolean oldValue = prefs.getBoolean(key, defaultValue);
                prefs.edit().putBoolean(key, isChecked).apply();
                DiagLog.log("USER", key + ": " + oldValue + " -> " + isChecked);
                sendBroadcast(new Intent("UPDATE_FLOATING_MONITOR"));
                sendBroadcast(new Intent("UPDATE_CLICK_THROUGH"));
            });
        }
    }

    private void setupSwitches() {
        setupMonitorSwitch(R.id.switchCpu, PrefsKeys.MONITOR_CPU, false);
        setupMonitorSwitch(R.id.switchRam, PrefsKeys.MONITOR_RAM, false);
        setupMonitorSwitch(R.id.switchTemp, PrefsKeys.MONITOR_TEMP, false);
        setupMonitorSwitch(R.id.switchBattery, PrefsKeys.MONITOR_BATTERY, false);
        setupMonitorSwitch(R.id.switchBatteryCurrent, PrefsKeys.MONITOR_CURRENT, false);
        setupMonitorSwitch(R.id.switchSignal, PrefsKeys.MONITOR_SIGNAL, false);
        setupMonitorSwitch(R.id.switchFps, PrefsKeys.MONITOR_FPS, false);
        setupMonitorSwitch(R.id.switchProcess, PrefsKeys.MONITOR_PROCESS, false);
        setupSwitch(R.id.switchBoot, PrefsKeys.AUTO_START, false);
        setupSwitch(R.id.switchSnapToBar, PrefsKeys.SNAP_TO_BAR, false);
        setupSwitch(R.id.switchClickThrough, PrefsKeys.CLICK_THROUGH, false);
        setupSwitch(R.id.switchFullscreen, PrefsKeys.HIDE_IN_FULLSCREEN, false);
        setupSwitch(R.id.switchDarkText, PrefsKeys.DARK_TEXT, false);
        setupSwitch(R.id.switchTextShadow, PrefsKeys.TEXT_SHADOW, false);
        setupSwitch(R.id.switchLandscape, PrefsKeys.DISABLE_IN_LANDSCAPE, false);
    }

    // Classic (one window per stat) vs Mini (a single combined bar).
    // The choice is a default-prefs value the overlay service reads
    // in updateActiveOverlays(); changing it rebuilds the windows
    // through the same REBUILD broadcast the switches use.
    private void setupStyleChooser() {
        RadioButton classic = (RadioButton) findViewById(R.id.radioStyleClassic);
        RadioButton mini = (RadioButton) findViewById(R.id.radioStyleMini);
        RadioGroup group = (RadioGroup) findViewById(R.id.radioMonitorStyle);
        if (classic == null || mini == null || group == null) {
            return;
        }
        boolean isMini = PrefsKeys.MONITOR_STYLE_MINI.equals(
                prefs.getString(PrefsKeys.MONITOR_STYLE, PrefsKeys.MONITOR_STYLE_CLASSIC));
        classic.setChecked(!isMini);
        mini.setChecked(isMini);
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int checkedId) {
                String style = checkedId == R.id.radioStyleMini
                        ? PrefsKeys.MONITOR_STYLE_MINI : PrefsKeys.MONITOR_STYLE_CLASSIC;
                String oldStyle = prefs.getString(PrefsKeys.MONITOR_STYLE, PrefsKeys.MONITOR_STYLE_CLASSIC);
                if (style.equals(oldStyle)) {
                    return;
                }
                prefs.edit().putString(PrefsKeys.MONITOR_STYLE, style).apply();
                DiagLog.log("USER", "monitor_style: " + oldStyle + " -> " + style);
                requestOverlayRebuild();
            }
        });
    }

    // ------------------------------------------------------------------
    // FPS recorder. The toggle drives FpsRecorder's static state and
    // the overlay service feeds it samples, so a recording keeps
    // going after this screen closes. The status line refreshes once
    // a second while the screen is visible; it also re-syncs the
    // switch when a recording stops by itself (session cap reached,
    // or the overlay service went away and finalized the session).
    // ------------------------------------------------------------------
    private boolean syncingRecordUi = false;
    private final Handler recordHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordStatusTick = new Runnable() {
        @Override
        public void run() {
            updateRecordStatus();
            recordHandler.postDelayed(this, 1000);
        }
    };

    private void setupRecorder() {
        Switch recordSwitch = (Switch) findViewById(R.id.switchRecordFps);
        if (recordSwitch != null) {
            recordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (syncingRecordUi) {
                    return;
                }
                DiagLog.log("USER", "record_fps: " + (!isChecked) + " -> " + isChecked);
                if (isChecked) {
                    // Samples come from the overlay service, which
                    // needs the overlay permission; without it a
                    // recording would collect nothing.
                    if (!Settings.canDrawOverlays(FloatingMonitorActivity.this)) {
                        setRecordSwitch(false);
                        Toast.makeText(FloatingMonitorActivity.this,
                                R.string.toast_overlay_permission, Toast.LENGTH_LONG).show();
                        startActivity(overlayPermissionIntent());
                        return;
                    }
                    if (FpsRecorder.start(getApplicationContext())) {
                        startOverlayService();
                        Toast.makeText(FloatingMonitorActivity.this,
                                R.string.toast_recording_started, Toast.LENGTH_SHORT).show();
                    } else {
                        setRecordSwitch(false);
                    }
                } else {
                    int count = FpsRecorder.getSampleCount();
                    FpsRecorder.stop(getApplicationContext());
                    Toast.makeText(FloatingMonitorActivity.this,
                            getString(R.string.toast_recording_stopped, count),
                            Toast.LENGTH_SHORT).show();
                }
                updateRecordStatus();
            });
        }
        Button sessionsButton = (Button) findViewById(R.id.btnFpsSessions);
        if (sessionsButton != null) {
            sessionsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(FloatingMonitorActivity.this, FpsSessionsActivity.class));
                }
            });
        }
        updateRecordStatus();
    }

    private void setRecordSwitch(boolean checked) {
        Switch recordSwitch = (Switch) findViewById(R.id.switchRecordFps);
        if (recordSwitch != null && recordSwitch.isChecked() != checked) {
            syncingRecordUi = true;
            recordSwitch.setChecked(checked);
            syncingRecordUi = false;
        }
    }

    private void startRecordStatusUpdates() {
        recordHandler.removeCallbacks(recordStatusTick);
        recordHandler.post(recordStatusTick);
    }

    private void stopRecordStatusUpdates() {
        recordHandler.removeCallbacks(recordStatusTick);
    }

    private void updateRecordStatus() {
        setRecordSwitch(FpsRecorder.isRecording());
        TextView status = (TextView) findViewById(R.id.recordFpsStatus);
        if (status == null) {
            return;
        }
        if (FpsRecorder.isRecording()) {
            long elapsedSeconds = Math.max(0,
                    (System.currentTimeMillis() - FpsRecorder.getStartTimeMs()) / 1000);
            String elapsed = String.format(Locale.US, "%02d:%02d",
                    elapsedSeconds / 60, elapsedSeconds % 60);
            status.setText(getString(R.string.fps_recording_status,
                    elapsed, FpsRecorder.getSampleCount()));
        } else {
            status.setText(R.string.fps_record_idle);
        }
    }

    // ------------------------------------------------------------------
    // Self-diagnosis block: when the overlay or the notification does
    // not appear, this says which link is down instead of leaving the
    // user flipping switches blind.
    // ------------------------------------------------------------------
    private void setupDiagnostics() {
        TextView permRow = (TextView) findViewById(R.id.diagPermission);
        if (permRow != null) {
            permRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!Settings.canDrawOverlays(FloatingMonitorActivity.this)) {
                        startActivity(overlayPermissionIntent());
                    }
                }
            });
        }
        TextView notifRow = (TextView) findViewById(R.id.diagNotifPermission);
        if (notifRow != null) {
            notifRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!notificationsAllowed()) {
                        openNotificationSettings();
                    }
                }
            });
        }
        Button testButton = (Button) findViewById(R.id.btnTestOverlay);
        if (testButton != null) {
            testButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testOverlay();
                }
            });
        }
        Button testFunctionsButton = (Button) findViewById(R.id.btnTestFunctions);
        if (testFunctionsButton != null) {
            testFunctionsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(FloatingMonitorActivity.this,
                            FunctionTestActivity.class));
                }
            });
        }
        Button runChecksButton = (Button) findViewById(R.id.btnRunChecks);
        if (runChecksButton != null) {
            runChecksButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runChecks();
                }
            });
        }
        Button shareButton = (Button) findViewById(R.id.btnShareLog);
        if (shareButton != null) {
            shareButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareLog();
                }
            });
        }
        Button clearButton = (Button) findViewById(R.id.btnClearLog);
        if (clearButton != null) {
            clearButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearLog();
                }
            });
        }
        refreshDiagnostics();
    }

    private boolean notificationsAllowed() {
        NotificationManager manager = (NotificationManager) getSystemService("notification");
        boolean enabled = manager != null && manager.areNotificationsEnabled();
        boolean granted = android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0;
        return enabled && granted;
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void refreshDiagnostics() {
        TextView perm = (TextView) findViewById(R.id.diagPermission);
        if (perm != null) {
            boolean granted = Settings.canDrawOverlays(this);
            perm.setText(getString(R.string.diag_permission,
                    getString(granted ? R.string.state_granted : R.string.state_not_granted)));
        }
        TextView service = (TextView) findViewById(R.id.diagService);
        if (service != null) {
            boolean running = FloatingOverlayService.isRunning();
            service.setText(getString(R.string.diag_service,
                    getString(running ? R.string.state_running : R.string.state_not_running)));
        }
        TextView windows = (TextView) findViewById(R.id.diagWindows);
        if (windows != null) {
            windows.setText(getString(R.string.diag_windows, FloatingOverlayService.getShownWindowCount()));
        }
        TextView enabledService = (TextView) findViewById(R.id.diagEnabledService);
        if (enabledService != null) {
            enabledService.setText(getString(R.string.diag_enabled_service,
                    FloatingOverlayService.getServiceEnabledCount()));
        }
        TextView lastAdd = (TextView) findViewById(R.id.diagLastAdd);
        if (lastAdd != null) {
            lastAdd.setText(getString(R.string.diag_last_add,
                    FloatingOverlayService.getLastAddResult()));
        }
        TextView notifPerm = (TextView) findViewById(R.id.diagNotifPermission);
        if (notifPerm != null) {
            notifPerm.setText(getString(R.string.diag_notif_permission,
                    getString(notificationsAllowed() ? R.string.state_granted : R.string.state_not_granted)));
        }
        TextView notifService = (TextView) findViewById(R.id.diagNotifService);
        if (notifService != null) {
            boolean running = MyService.isRunning();
            notifService.setText(getString(R.string.diag_notif_service,
                    getString(running ? R.string.state_running : R.string.state_not_running)));
        }
        // Battery history is collected by MyService (it holds the
        // battery-changed receiver). When that service is off the
        // graph stays empty, so say why right under it instead of
        // leaving a bare "collecting data…" forever.
        TextView historyHint = (TextView) findViewById(R.id.batteryHistoryHint);
        if (historyHint != null) {
            historyHint.setVisibility(MyService.isRunning() ? View.GONE : View.VISIBLE);
        }
    }

    private void testOverlay() {
        DiagLog.log("USER", "test overlay pressed");
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show();
            startActivity(overlayPermissionIntent());
            refreshDiagnostics();
            return;
        }
        requestOverlayRebuild();
        // Report from the real window count as soon as it is known:
        // poll briefly instead of sleeping a flat delay and reading
        // whatever state happens to be there.
        final Handler handler = new Handler(Looper.getMainLooper());
        final long deadline = System.currentTimeMillis() + 1500;
        handler.post(new Runnable() {
            @Override
            public void run() {
                refreshDiagnostics();
                int count = FloatingOverlayService.getShownWindowCount();
                if (FloatingOverlayService.isRunning() && count > 0) {
                    DiagLog.log("SYS", "test overlay result: " + count + " windows shown");
                    Toast.makeText(FloatingMonitorActivity.this,
                            getString(R.string.diag_test_ok, count), Toast.LENGTH_SHORT).show();
                } else if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 100);
                } else {
                    DiagLog.log("SYS", "test overlay result: still 0 windows (service running: "
                            + FloatingOverlayService.isRunning()
                            + ", enabled per service: " + FloatingOverlayService.getServiceEnabledCount()
                            + ", last add: " + FloatingOverlayService.getLastAddResult() + ")");
                    Toast.makeText(FloatingMonitorActivity.this,
                            R.string.diag_test_fail, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // "Run checks": appends one full status snapshot to the
    // diagnostic log — every diagnosis value, both views of the
    // monitor switches, the battery sources, shell availability —
    // so the user can share a single file that shows what works and
    // what does not. Runs on a worker thread: the capacity chain and
    // the shell probes can take a moment.
    private void runChecks() {
        DiagLog.log("USER", "run checks pressed");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("===== RUN CHECKS =====\n");
                String version = "unknown";
                try {
                    android.content.pm.PackageInfo info =
                            getPackageManager().getPackageInfo(getPackageName(), 0);
                    version = info.versionName + " (" + info.versionCode + ")";
                } catch (Exception ignored) {
                }
                sb.append("App: App Slayer ").append(version).append("\n");
                sb.append("Android: SDK ").append(android.os.Build.VERSION.SDK_INT)
                        .append(", ").append(android.os.Build.MANUFACTURER)
                        .append(" ").append(android.os.Build.MODEL).append("\n");
                String rootProbe = SystemStats.runShellCommand("echo ROOT_OK");
                sb.append("Root shell: ")
                        .append(rootProbe != null && rootProbe.contains("ROOT_OK")
                                ? "available" : "UNAVAILABLE").append("\n");
                sb.append("Overlay permission: ")
                        .append(Settings.canDrawOverlays(FloatingMonitorActivity.this)
                                ? "granted" : "NOT granted").append("\n");
                sb.append("Overlay service: ")
                        .append(FloatingOverlayService.isRunning() ? "running" : "NOT running")
                        .append(", windows: ").append(FloatingOverlayService.getShownWindowCount())
                        .append("\n");
                sb.append("Monitor style: ").append(prefs.getString(
                        PrefsKeys.MONITOR_STYLE, PrefsKeys.MONITOR_STYLE_CLASSIC)).append("\n");
                String[] monitorKeys = {PrefsKeys.MONITOR_CPU, PrefsKeys.MONITOR_RAM,
                        PrefsKeys.MONITOR_TEMP, PrefsKeys.MONITOR_BATTERY, PrefsKeys.MONITOR_CURRENT,
                        PrefsKeys.MONITOR_SIGNAL, PrefsKeys.MONITOR_FPS, PrefsKeys.MONITOR_PROCESS};
                StringBuilder activityView = new StringBuilder();
                for (String key : monitorKeys) {
                    if (activityView.length() > 0) {
                        activityView.append(", ");
                    }
                    activityView.append(key).append("=").append(prefs.getBoolean(key, false));
                }
                sb.append("Monitors (activity view): ").append(activityView).append("\n");
                sb.append("Monitors enabled (service view): ")
                        .append(FloatingOverlayService.getServiceEnabledCount())
                        .append(", last window add: ")
                        .append(FloatingOverlayService.getLastAddResult()).append("\n");
                sb.append("Notification permission: ")
                        .append(notificationsAllowed() ? "granted" : "NOT granted").append("\n");
                sb.append("Notification service: ")
                        .append(MyService.isRunning() ? "running" : "NOT running")
                        .append(" (collects the battery history)\n");
                java.util.List<BatteryHistory.Sample> samples =
                        BatteryHistory.load(FloatingMonitorActivity.this, 7L * 24 * 60 * 60 * 1000);
                sb.append("Battery history samples (7d): ").append(samples.size()).append("\n");
                java.util.List<SystemStats.ThermalSensor> sensors = SystemStats.getThermalSensors();
                sb.append("Thermal sensors readable: ").append(sensors.size());
                if (!sensors.isEmpty()) {
                    sb.append(" (");
                    for (int i = 0; i < sensors.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(sensors.get(i).name).append(" ").append(sensors.get(i).tempC).append("C");
                    }
                    sb.append(")");
                }
                sb.append("\n");
                int designMah = readCapacityMah("charge_full_design", "energy_full_design",
                        "POWER_SUPPLY_CHARGE_FULL_DESIGN=");
                String designSource = lastCapacitySource;
                int fullMah = readCapacityMah("charge_full", "energy_full",
                        "POWER_SUPPLY_CHARGE_FULL=");
                String fullSource = lastCapacitySource;
                Integer counter = SystemStats.readChargeCounterMah();
                sb.append("Capacity design: ").append(designMah > 0 ? designMah + " mAh" : "N/A")
                        .append(" via ").append(designSource).append("\n");
                sb.append("Capacity full: ").append(fullMah > 0 ? fullMah + " mAh" : "N/A")
                        .append(" via ").append(fullSource).append("\n");
                sb.append("Charge counter: ").append(counter != null ? counter + " mAh" : "N/A")
                        .append(" via ").append(SystemStats.getLastChargeCounterSource())
                        .append("\n");
                Double fps = SystemStats.getFpsValue();
                sb.append("FPS sampler: ").append(fps != null ? String.valueOf(fps) : "no reading yet")
                        .append("\n");
                String top = SystemStats.getTopProcesses(5);
                sb.append("Process snapshot: ").append(top == null ? "empty" : "ok").append("\n");
                sb.append("===== END RUN CHECKS =====");
                DiagLog.logBlock(FloatingMonitorActivity.this, "CHECK", sb.toString());
                DiagLog.flushMirrorAsync(FloatingMonitorActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshDiagnostics();
                        Toast.makeText(FloatingMonitorActivity.this,
                                R.string.diag_log_saved, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // Shares the diagnostic log: the mirrored copy on shared storage
    // when it exists (flushed first so it is current), the private
    // primary file otherwise. Always through FileProvider — a
    // file:// Uri would crash on modern Android.
    private void shareLog() {
        DiagLog.log("USER", "share log pressed");
        new Thread(new Runnable() {
            @Override
            public void run() {
                DiagLog.flushMirrorAsync(FloatingMonitorActivity.this);
                // Give the flush a brief moment; the share falls back
                // to the primary file if the mirror is not there.
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        java.io.File mirror = DiagLog.mirrorFile();
                        java.io.File primary = DiagLog.file(FloatingMonitorActivity.this);
                        java.io.File target = mirror.exists() ? mirror
                                : primary != null && primary.exists() ? primary : null;
                        if (target == null) {
                            Toast.makeText(FloatingMonitorActivity.this,
                                    R.string.diag_log_saved, Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                    FloatingMonitorActivity.this,
                                    getPackageName() + ".fileprovider", target);
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_STREAM, uri);
                            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(share,
                                    getString(R.string.diag_log_share_chooser)));
                        } catch (Exception e) {
                            DiagLog.log("SYS", "share log failed: "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                            Toast.makeText(FloatingMonitorActivity.this,
                                    getString(R.string.toast_overlay_failed, e.getMessage()),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void clearLog() {
        DiagLog.clear(this);
        DiagLog.log("USER", "log cleared");
        Toast.makeText(this, R.string.diag_log_cleared, Toast.LENGTH_SHORT).show();
    }

    // Temperature sensor list: re-read about every 2 s while this
    // screen is visible. Started in onResume, stopped in onPause, so
    // the thread never outlives the screen.
    private void startSensorRefresh() {
        if (sensorRefreshing) {
            return;
        }
        sensorRefreshing = true;
        sensorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sensorRefreshing) {
                    final String text = buildSensorText();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView list = (TextView) findViewById(R.id.tempSensorList);
                            if (list != null && sensorRefreshing) {
                                list.setText(text);
                            }
                        }
                    });
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        sensorThread.start();
    }

    private void stopSensorRefresh() {
        sensorRefreshing = false;
        if (sensorThread != null) {
            sensorThread.interrupt();
            sensorThread = null;
        }
    }

    private String buildSensorText() {
        java.util.List<SystemStats.ThermalSensor> sensors = SystemStats.getThermalSensors();
        if (sensors.isEmpty()) {
            return getString(R.string.temp_sensors_empty);
        }
        StringBuilder sb = new StringBuilder();
        for (SystemStats.ThermalSensor sensor : sensors) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(sensor.name).append(": ").append(sensor.tempC).append("°C");
        }
        return sb.toString();
    }

    private void setupSeekBar(int id, String key, int defaultValue, int max, final SeekBarCallback callback) {
        SeekBar seekBar = (SeekBar) findViewById(id);
        if (seekBar != null) {
            seekBar.setMax(max);
            seekBar.setProgress(this.prefs.getInt(key, defaultValue));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        callback.onProgressChanged(progress);
                    }
                }

                // Persistence happens in onProgressChanged through the
                // callback, which writes this seek bar's own key. (The
                // old code wrote OVERLAY_ALPHA here and OVERLAY_TEXT_SIZE
                // on stop for BOTH seek bars, so dragging one slider
                // corrupted the other setting.) The log hears only the
                // released value, never the drag stream.
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    DiagLog.log("USER", key + " -> " + seekBar.getProgress() + " (released)");
                }
            });
        }
    }

    private void setupSeekBars() {
        setupSeekBar(R.id.seekTransparency, PrefsKeys.OVERLAY_ALPHA, 200, 255, new SeekBarCallback() {
            @Override
            public void onProgressChanged(int progress) {
                prefs.edit().putInt(PrefsKeys.OVERLAY_ALPHA, progress).apply();
                sendBroadcast(new Intent("UPDATE_FLOATING_MONITOR"));
            }
        });
        setupSeekBar(R.id.seekTextSize, PrefsKeys.OVERLAY_TEXT_SIZE, 14, 30, new SeekBarCallback() {
            @Override
            public void onProgressChanged(int progress) {
                prefs.edit().putInt("overlay_text_size", progress).apply();
                sendBroadcast(new Intent("UPDATE_FLOATING_MONITOR"));
            }
        });
    }

    private String readBatteryValueFromUevent(String key) {
        return readUeventValue("/sys/class/power_supply/battery/", key);
    }

    private String readUeventValue(String dir, String key) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(dir + "uevent"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.split("=");
                    if (parts.length >= 2) {
                        return parts[1];
                    }
                    return null;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // Every power_supply directory on the device, the battery folder
    // first. Some ROMs put the fuel gauge under a different node
    // ("bms", the charger chip, ...), and the 6.0.1 chain only ever
    // looked inside battery/ — on those devices every source missed
    // and the dialog showed N/A.
    private java.util.List<String> powerSupplyDirs() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        dirs.add("/sys/class/power_supply/battery/");
        java.io.File[] subs = new java.io.File("/sys/class/power_supply/").listFiles();
        if (subs != null) {
            java.util.Arrays.sort(subs, new java.util.Comparator<java.io.File>() {
                @Override
                public int compare(java.io.File a, java.io.File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (java.io.File sub : subs) {
                if (sub.isDirectory() && !"battery".equals(sub.getName())) {
                    dirs.add(sub.getAbsolutePath() + "/");
                }
            }
        }
        return dirs;
    }

    // One capacity value (mAh) with every source we have, in order,
    // each tried in every power_supply directory before moving on:
    // 1. the plain charge file (what 5.2.0 read),
    // 2. the same value inside the directory's uevent dump,
    // 3. the energy counters converted through the voltage,
    // 4. the root shell, for nodes a plain read cannot open.
    // Values pass through PureCalc.capacityToMah, which converts
    // µAh-scale readings and rejects implausible ones instead of
    // treating them as data. -1 = no source answered.
    private int readCapacityMah(String chargeFile, String energyFile, String ueventKey) {
        java.util.List<String> dirs = powerSupplyDirs();
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readPlainFile(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "plain file " + dir + chargeFile;
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readUeventValue(dir, ueventKey));
            if (mah != null) {
                lastCapacitySource = "uevent " + dir;
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer microWh = parseIntOrNull(readPlainFile(dir + energyFile));
            Integer microV = parseIntOrNull(readPlainFile(dir + "voltage_now"));
            if (microWh != null && microWh > 0 && microV != null && microV > 0) {
                int mah = (int) (microWh * 1000L / microV);
                if (mah >= 500 && mah <= 30000) {
                    lastCapacitySource = "energy counters " + dir;
                    return mah;
                }
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(SystemStats.readFileWithRoot(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "root shell " + dir + chargeFile;
                return mah;
            }
        }
        lastCapacitySource = "none";
        return -1;
    }

    private String readPlainFile(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return reader.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String formatDuration(double hours) {
        long h = (long) hours;
        long m = Math.round((hours - h) * 60);
        if (m == 60) {
            h++;
            m = 0;
        }
        return getString(R.string.duration_hm, h, m);
    }

    // Battery level slope (%/min) from the history log, used for the
    // time estimates when the mAh-based path has no data. Samples
    // from the last ~10 minutes; PureCalc rejects windows shorter
    // than ~2 minutes.
    private Double levelSlopePerMinute() {
        java.util.List<BatteryHistory.Sample> samples =
                BatteryHistory.load(FloatingMonitorActivity.this, 10L * 60 * 1000);
        if (samples.size() < 2) {
            return null;
        }
        long[] times = new long[samples.size()];
        int[] levels = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            times[i] = samples.get(i).time;
            levels[i] = samples.get(i).level;
        }
        return PureCalc.levelSlopePerMinute(times, levels);
    }

    // One Battery Information snapshot: every value the dialog
    // shows, already formatted. sysHealthPercent is -1 when the
    // system health is not numeric (its bar hides then).
    private static class BatteryInfo {
        int level;
        String healthText;
        String designText;
        String fullText;
        String sysHealthText;
        int sysHealthPercent = -1;
        String statusText;
        String currentText;
        String powerText;
        String voltageText;
        String estFull;
        String estEmpty;
        String realCapText;
        String realHealthText;
        String lastCalcText;
    }

    // Gathers a fresh Battery Information snapshot from the same
    // sources, in the same order, as the old text version. Runs on
    // a background thread (capacity chain, current sampling and a
    // possible root-shell read can take a moment).
    private BatteryInfo gatherBatteryInfo(int currentSamples, long sampleGapMs) {
        Intent batteryIntent = registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        int rawLevel = batteryIntent != null ? batteryIntent.getIntExtra("level", 0) : 0;
        int scale = batteryIntent != null ? batteryIntent.getIntExtra("scale", 100) : 100;
        final int level = scale > 0 ? (rawLevel * 100) / scale : rawLevel;
        int healthCode = batteryIntent != null ? batteryIntent.getIntExtra("health", 0) : 0;
        final int statusCode = batteryIntent != null ? batteryIntent.getIntExtra("status", 1) : 1;
        String healthText;
        switch (healthCode) {
            case 2:
                healthText = "Good";
                break;
            case 3:
                healthText = "Overheat";
                break;
            case 4:
                healthText = "Dead";
                break;
            case 5:
                healthText = "Over Voltage";
                break;
            case 6:
                healthText = "Failure";
                break;
            default:
                healthText = "Unknown";
                break;
        }

        final int designMah = readCapacityMah("charge_full_design", "energy_full_design",
                "POWER_SUPPLY_CHARGE_FULL_DESIGN=");
        final String designSource = lastCapacitySource;
        final int fullMah = readCapacityMah("charge_full", "energy_full",
                "POWER_SUPPLY_CHARGE_FULL=");
        final String fullSource = lastCapacitySource;
        // The dialog re-gathers this snapshot every couple of
        // seconds; the log only hears when the outcome changes.
        String capacityOutcome = "design=" + designMah + " via " + designSource
                + ", full=" + fullMah + " via " + fullSource;
        if (!capacityOutcome.equals(lastCapacityOutcome)) {
            lastCapacityOutcome = capacityOutcome;
            DiagLog.log("BATT", "capacity chain: " + capacityOutcome);
        }
        String sysHealthStr = "N/A";
        int sysHealthPercent = -1;
        if (designMah > 0 && fullMah > 0) {
            sysHealthPercent = Math.round((fullMah * 100.0f) / designMah);
            sysHealthStr = sysHealthPercent + "%";
        }
        Integer chargeCounter = SystemStats.readChargeCounterMah();
        final int remainingMah = chargeCounter != null ? chargeCounter
                : (fullMah > 0 ? (fullMah * level) / 100 : -1);
        // Smooth the instantaneous current over a few samples so the
        // estimates do not jump around.
        int sum = 0;
        int count = 0;
        for (int i = 0; i < currentSamples; i++) {
            Integer sample = SystemStats.readBatteryCurrentMa();
            if (sample != null && sample > 0) {
                sum += sample;
                count++;
            }
            if (i < currentSamples - 1) {
                try {
                    Thread.sleep(sampleGapMs);
                } catch (InterruptedException ignored) {
                }
            }
        }
        final int currentMa = count > 0 ? sum / count : -1;

        String statusText;
        switch (statusCode) {
            case 2:
                statusText = getString(R.string.batt_charging);
                break;
            case 3:
                statusText = getString(R.string.batt_discharging);
                break;
            case 4:
                statusText = getString(R.string.batt_not_charging);
                break;
            case 5:
                statusText = getString(R.string.batt_full);
                break;
            default:
                statusText = getString(R.string.batt_status_unknown);
                break;
        }
        String currentText = "N/A";
        if (currentMa >= 0) {
            String sign = statusCode == 2 ? "+" : statusCode == 3 ? "-" : "";
            currentText = sign + currentMa + " mA";
        }
        // Instantaneous power from the same current and the battery
        // voltage, signed like the current. N/A when either input is
        // missing — never a fake 0. The voltage itself is shown in
        // the Live card whenever it can be read.
        Integer voltageMv = SystemStats.readBatteryVoltageMv();
        String voltageText = voltageMv != null && voltageMv > 0
                ? PureCalc.formatVolts(voltageMv) : "N/A";
        String powerText = "N/A";
        if (currentMa >= 0 && voltageMv != null && voltageMv > 0) {
            int signedMa = statusCode == 3 ? -currentMa : currentMa;
            powerText = (signedMa < 0 ? "-" : "+")
                    + String.format(java.util.Locale.US, "%.2f W",
                            Math.abs(PureCalc.powerWatts(signedMa, voltageMv)));
        }
        String estFull = "—";
        String estEmpty = "—";
        if (statusCode == 5) {
            estFull = getString(R.string.batt_full);
        } else if (statusCode == 2) {
            if (currentMa >= 20 && fullMah > 0 && remainingMah >= 0 && fullMah > remainingMah) {
                estFull = formatDuration((fullMah - remainingMah) / (double) currentMa);
            } else {
                // No capacity data on this ROM: estimate from how
                // fast the level itself is climbing.
                Double slope = levelSlopePerMinute();
                Double minutes = slope != null ? PureCalc.minutesToFull(level, slope) : null;
                estFull = minutes != null ? formatDuration(minutes / 60.0d)
                        : getString(R.string.batt_calculating);
            }
        } else if (statusCode == 3) {
            if (currentMa >= 20 && remainingMah > 0) {
                estEmpty = formatDuration(remainingMah / (double) currentMa);
            } else {
                Double slope = levelSlopePerMinute();
                Double minutes = slope != null ? PureCalc.minutesToEmpty(level, slope) : null;
                if (minutes != null) {
                    estEmpty = formatDuration(minutes / 60.0d);
                } else {
                    // Final fallback: the drain rate learned from the
                    // full history log.
                    BatteryHistory.Summary summary = BatteryHistory.summarize(
                            BatteryHistory.load(FloatingMonitorActivity.this, 7L * 24 * 60 * 60 * 1000));
                    if (summary != null && summary.estimatedHoursLeft >= 0) {
                        estEmpty = getString(R.string.hours_left, summary.estimatedHoursLeft);
                    } else {
                        estEmpty = getString(R.string.batt_calculating);
                    }
                }
            }
        }

        int realCap = prefs.getInt("real_capacity_mah", 0);
        int realHealth = prefs.getInt("real_health_percent", 0);
        long lastTime = prefs.getLong("real_last_calc", 0);
        String lastCalc = lastTime > 0
                ? new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(new Date(lastTime))
                : getString(R.string.not_available_yet);
        BatteryInfo info = new BatteryInfo();
        info.level = level;
        info.healthText = healthText;
        info.designText = designMah > 0 ? designMah + " mAh" : "N/A";
        info.fullText = fullMah > 0 ? fullMah + " mAh" : "N/A";
        info.sysHealthText = sysHealthStr;
        info.sysHealthPercent = sysHealthPercent;
        info.statusText = statusText;
        info.currentText = currentText;
        info.powerText = powerText;
        info.voltageText = voltageText;
        info.estFull = estFull;
        info.estEmpty = estEmpty;
        info.realCapText = realCap > 0 ? realCap + " mAh" : getString(R.string.waiting_data);
        info.realHealthText = realCap > 0 ? realHealth + "%" : getString(R.string.waiting_data);
        info.lastCalcText = lastCalc;
        return info;
    }

    // Single instance: tapping the entry again while the dialog is
    // open (or still loading) does nothing — the dialogs used to
    // pile up on repeated taps. While open, the values refresh in
    // place about every 2 s; the loop stops the moment the dialog
    // is dismissed.
    private void showBatteryStatus() {
        if (batteryInfoBusy || (batteryDialog != null && batteryDialog.isShowing())) {
            return;
        }
        DiagLog.log("USER", "battery info dialog opened");
        batteryInfoBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BatteryInfo info = gatherBatteryInfo(5, 400);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        batteryInfoBusy = false;
                        if (isFinishing() || batteryDialog != null) {
                            return;
                        }
                        View content = LayoutInflater.from(FloatingMonitorActivity.this)
                                .inflate(R.layout.dialog_battery_info, null);
                        bindBatteryDialogViews(content);
                        applyBatteryInfo(info);
                        AlertDialog dialog = new AlertDialog.Builder(
                                FloatingMonitorActivity.this, R.style.BatteryDialogTheme)
                                .setView(content)
                                .setPositiveButton(getString(R.string.dialog_close), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        d.dismiss();
                                    }
                                })
                                .create();
                        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                batteryDialogRefreshing = false;
                                if (batteryDialog == d) {
                                    batteryDialog = null;
                                }
                            }
                        });
                        batteryDialog = dialog;
                        dialog.show();
                        startBatteryDialogRefresh();
                    }
                });
            }
        }).start();
    }

    private void bindBatteryDialogViews(View root) {
        battLevelText = (TextView) root.findViewById(R.id.battLevelText);
        battLevelBar = (ProgressBar) root.findViewById(R.id.battLevelBar);
        battStatusText = (TextView) root.findViewById(R.id.battStatusText);
        battHealthText = (TextView) root.findViewById(R.id.battHealthText);
        battDesignValue = (TextView) root.findViewById(R.id.battDesignValue);
        battFullValue = (TextView) root.findViewById(R.id.battFullValue);
        battSysHealthValue = (TextView) root.findViewById(R.id.battSysHealthValue);
        battSysHealthBar = (ProgressBar) root.findViewById(R.id.battSysHealthBar);
        battLiveStatusValue = (TextView) root.findViewById(R.id.battLiveStatusValue);
        battCurrentValue = (TextView) root.findViewById(R.id.battCurrentValue);
        battPowerValue = (TextView) root.findViewById(R.id.battPowerValue);
        battVoltageValue = (TextView) root.findViewById(R.id.battVoltageValue);
        battEstFullValue = (TextView) root.findViewById(R.id.battEstFullValue);
        battEstLeftValue = (TextView) root.findViewById(R.id.battEstLeftValue);
        battRealCapValue = (TextView) root.findViewById(R.id.battRealCapValue);
        battRealHealthValue = (TextView) root.findViewById(R.id.battRealHealthValue);
        battLastCalcValue = (TextView) root.findViewById(R.id.battLastCalcValue);
    }

    // Pushes one snapshot into the dialog's views. UI thread only.
    private void applyBatteryInfo(BatteryInfo info) {
        if (battLevelText == null) {
            return;
        }
        battLevelText.setText(info.level + "%");
        battLevelBar.setProgress(Math.max(0, Math.min(100, info.level)));
        battStatusText.setText(info.statusText);
        battHealthText.setText(info.healthText);
        battDesignValue.setText(info.designText);
        battFullValue.setText(info.fullText);
        battSysHealthValue.setText(info.sysHealthText);
        if (info.sysHealthPercent >= 0) {
            battSysHealthBar.setVisibility(View.VISIBLE);
            battSysHealthBar.setProgress(Math.min(100, info.sysHealthPercent));
        } else {
            battSysHealthBar.setVisibility(View.GONE);
        }
        battLiveStatusValue.setText(info.statusText);
        battCurrentValue.setText(info.currentText);
        battPowerValue.setText(info.powerText);
        battVoltageValue.setText(info.voltageText);
        battEstFullValue.setText(info.estFull);
        battEstLeftValue.setText(info.estEmpty);
        battRealCapValue.setText(info.realCapText);
        battRealHealthValue.setText(info.realHealthText);
        battLastCalcValue.setText(info.lastCalcText);
    }

    private void startBatteryDialogRefresh() {
        if (batteryDialogRefreshing) {
            return;
        }
        batteryDialogRefreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (batteryDialogRefreshing) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!batteryDialogRefreshing) {
                        return;
                    }
                    final BatteryInfo info = gatherBatteryInfo(3, 300);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog dialog = batteryDialog;
                            if (dialog != null && dialog.isShowing()) {
                                applyBatteryInfo(info);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void dismissBatteryDialog() {
        batteryDialogRefreshing = false;
        if (batteryDialog != null && batteryDialog.isShowing()) {
            batteryDialog.dismiss();
        }
        batteryDialog = null;
    }

    private static boolean chargingMonitorRunning = false;

    private void startChargingMonitor() {
        // One sampler per process: this screen can be opened again and
        // again, and every extra thread would add the charge current a
        // second time and corrupt the capacity estimate.
        synchronized (FloatingMonitorActivity.class) {
            if (chargingMonitorRunning) {
                return;
            }
            chargingMonitorRunning = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Intent batteryIntent = registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
                        int status = batteryIntent != null ? batteryIntent.getIntExtra("status", 1) : 1;
                        int level = batteryIntent != null ? batteryIntent.getIntExtra("level", 0) : 0;
                        boolean chargingNow = status == 2 || status == 5;
                        if (chargingNow && !isCharging) {
                            isCharging = true;
                            lastChargeTime = System.currentTimeMillis();
                        } else if (!chargingNow && isCharging) {
                            isCharging = false;
                            int totalMah = prefs.getInt("total_mah", 0);
                            int steps = prefs.getInt("charge_steps", 0);
                            if (totalMah > 0 && steps > 0 && level >= 95) {
                                int avgMah = totalMah / steps;
                                int designMah = 0;
                                String designStr = readBatteryValueFromUevent("POWER_SUPPLY_CHARGE_FULL_DESIGN=");
                                if (designStr != null) {
                                    designMah = Integer.parseInt(designStr) / 1000;
                                }
                                if (designMah > 0) {
                                    int realCap = (avgMah * 100) / Math.max(1, level);
                                    int realHealth = (realCap * 100) / designMah;
                                    if (realCap > 500 && realCap < designMah * 1.2d) {
                                        prefs.edit()
                                                .putInt("real_capacity_mah", realCap)
                                                .putInt("real_health_percent", realHealth)
                                                .putLong("real_last_calc", System.currentTimeMillis())
                                                .putInt("total_mah", 0)
                                                .putInt("charge_steps", 0)
                                                .apply();
                                    }
                                }
                            }
                        }
                        if (isCharging) {
                            Integer currentMa = SystemStats.readBatteryCurrentMa();
                            int mA = currentMa != null ? currentMa : 0;
                            int total = prefs.getInt("total_mah", 0) + mA;
                            int steps = prefs.getInt("charge_steps", 0) + 1;
                            prefs.edit().putInt("total_mah", total).putInt("charge_steps", steps).apply();
                        }
                        Thread.sleep(60000);
                    } catch (Exception e) {
                        try {
                            Thread.sleep(60000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }).start();
    }
}
