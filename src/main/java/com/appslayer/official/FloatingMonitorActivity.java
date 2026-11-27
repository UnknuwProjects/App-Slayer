package com.appslayer.official;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

public class FloatingMonitorActivity extends Activity {

    private boolean isCharging = false;
    private long lastChargeTime = 0;
    private SharedPreferences prefs;

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
        // Battery history logging lives in BatteryLoggingService
        // (it records the battery broadcast to the CSV the graph
        // reads, independent of the notification feature). Make
        // sure it is running so the graph fills while this screen
        // is used.
        BatteryLoggingService.start(this);
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
                    openBatteryInfo();
                }
            });
        }
        populateBatteryHistory();
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

    // Fills the history card's label -> value rows (6.3.9). Same
    // summary data as the old three-line text block; while there
    // is nothing to summarize yet, the rows hide and the single
    // "collecting data" line shows in their place.
    private void populateBatteryHistory() {
        java.util.List<BatteryHistory.Sample> samples =
                BatteryHistory.load(this, 7L * 24 * 60 * 60 * 1000);
        BatteryHistory.Summary summary = BatteryHistory.summarize(samples);
        TextView collecting = (TextView) findViewById(R.id.batteryHistorySummary);
        View rows = findViewById(R.id.batteryHistoryRows);
        if (summary == null) {
            if (collecting != null) {
                collecting.setVisibility(View.VISIBLE);
                collecting.setText(R.string.collecting_battery_data);
            }
            if (rows != null) {
                rows.setVisibility(View.GONE);
            }
            return;
        }
        if (collecting != null) {
            collecting.setVisibility(View.GONE);
        }
        if (rows != null) {
            rows.setVisibility(View.VISIBLE);
        }
        long hours = summary.sinceFullChargeMs / 3600000;
        long minutes = (summary.sinceFullChargeMs % 3600000) / 60000;
        TextView since = (TextView) findViewById(R.id.batteryHistorySinceValue);
        if (since != null) {
            since.setText(getString(R.string.duration_hm, hours, minutes));
        }
        TextView drain = (TextView) findViewById(R.id.batteryHistoryDrainValue);
        if (drain != null) {
            drain.setText(getString(R.string.drain_per_hour, summary.drainPerHour));
        }
        View leftRow = findViewById(R.id.batteryHistoryLeftRow);
        if (leftRow != null) {
            if (summary.estimatedHoursLeft >= 0) {
                leftRow.setVisibility(View.VISIBLE);
                TextView left = (TextView) findViewById(R.id.batteryHistoryLeftValue);
                if (left != null) {
                    left.setText(getString(R.string.hours_left, summary.estimatedHoursLeft));
                }
            } else {
                leftRow.setVisibility(View.GONE);
            }
        }
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
        DiagLog.flushMirrorAsync(this);
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
    // Battery Information lives on its own screen since 6.3.7
    // (BatteryInfoActivity). The open flag + singleTop keep
    // repeated taps from stacking copies: while the screen is
    // already open, the tap just brings it forward.
    private void openBatteryInfo() {
        DiagLog.log("USER", "battery info screen opened");
        Intent intent = new Intent(this, BatteryInfoActivity.class);
        if (BatteryInfoActivity.isOpen()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        startActivity(intent);
    }

    // ------------------------------------------------------------------
    // FPS recorder. The toggle drives FpsRecorder's static state;
    // the recorder runs its own sampling loop (6.3.7), so a
    // recording keeps going after this screen closes and does not
    // depend on the overlay service or any monitor window. The
    // status line refreshes once a second while the screen is
    // visible; it also re-syncs the switch when a recording stops
    // by itself (session cap reached).
    //
    // Measurement paths, non-root first (the user's standing
    // rule): the MediaProjection capture (FpsCaptureService) works
    // on any device after the system's consent prompt; the root
    // shell's SurfaceFlinger path is the enhancement when a root
    // grant exists. Starting a recording therefore goes: capture
    // already running or root shell verified → start at once;
    // otherwise ask for the capture consent first.
    // ------------------------------------------------------------------
    private static final int REQUEST_FPS_CAPTURE = 7301;
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
                    startRecording();
                } else {
                    int count = FpsRecorder.getSampleCount();
                    FpsRecorder.stop(getApplicationContext());
                    // The capture only exists to feed this
                    // recording; it stops with it.
                    if (FpsCaptureService.isRunning()) {
                        FpsCaptureService.stop(getApplicationContext());
                    }
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

    // Starts a recording through whichever measurement path is
    // available, asking for the screen-capture consent when
    // neither the capture nor a verified root shell is up yet.
    private void startRecording() {
        if (FpsCaptureService.isRunning() || SystemStats.isShellVerified()) {
            beginRecording();
            return;
        }
        try {
            android.media.projection.MediaProjectionManager manager =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService("media_projection");
            if (manager == null) {
                beginRecording(); // no capture service: the shell path is all there is
                return;
            }
            DiagLog.log("USER", "record_fps: asking for screen-capture consent");
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_FPS_CAPTURE);
        } catch (Exception e) {
            DiagLog.log("SYS", "capture consent request FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            beginRecording();
        }
    }

    private void beginRecording() {
        if (FpsRecorder.start(getApplicationContext())) {
            Toast.makeText(FloatingMonitorActivity.this,
                    R.string.toast_recording_started, Toast.LENGTH_SHORT).show();
        } else {
            setRecordSwitch(false);
        }
        updateRecordStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FPS_CAPTURE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            DiagLog.log("USER", "record_fps: screen-capture consent granted");
            FpsCaptureService.start(getApplicationContext(), resultCode, data);
            beginRecording();
        } else {
            // No consent and no root: a recording would collect
            // nothing, so the toggle goes back off and says why
            // instead of silently producing an empty session.
            DiagLog.log("USER", "record_fps: screen-capture consent declined");
            setRecordSwitch(false);
            Toast.makeText(FloatingMonitorActivity.this,
                    R.string.fps_capture_denied, Toast.LENGTH_LONG).show();
            updateRecordStatus();
        }
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

    // Diagnosis value colors (6.3.9): the rows are label -> value
    // now, and the value carries the state the code already knows —
    // green when a permission is granted or a service runs, amber
    // when it is not, red when the last window add failed. Same
    // palette as the function test verdicts.
    private static final int DIAG_COLOR_OK = Color.rgb(76, 175, 80);
    private static final int DIAG_COLOR_WARN = Color.rgb(255, 179, 0);
    private static final int DIAG_COLOR_BAD = Color.rgb(239, 83, 80);
    private static final int DIAG_COLOR_PLAIN = Color.rgb(255, 255, 255);

    private void refreshDiagnostics() {
        TextView perm = (TextView) findViewById(R.id.diagPermission);
        if (perm != null) {
            boolean granted = Settings.canDrawOverlays(this);
            perm.setText(granted ? R.string.state_granted : R.string.state_not_granted);
            perm.setTextColor(granted ? DIAG_COLOR_OK : DIAG_COLOR_WARN);
        }
        TextView service = (TextView) findViewById(R.id.diagService);
        if (service != null) {
            boolean running = FloatingOverlayService.isRunning();
            service.setText(running ? R.string.state_running : R.string.state_not_running);
            service.setTextColor(running ? DIAG_COLOR_OK : DIAG_COLOR_WARN);
        }
        TextView windows = (TextView) findViewById(R.id.diagWindows);
        if (windows != null) {
            windows.setText(String.valueOf(FloatingOverlayService.getShownWindowCount()));
        }
        TextView enabledService = (TextView) findViewById(R.id.diagEnabledService);
        if (enabledService != null) {
            enabledService.setText(String.valueOf(FloatingOverlayService.getServiceEnabledCount()));
        }
        TextView lastAdd = (TextView) findViewById(R.id.diagLastAdd);
        if (lastAdd != null) {
            String result = FloatingOverlayService.getLastAddResult();
            lastAdd.setText(result);
            lastAdd.setTextColor(result.startsWith("OK") ? DIAG_COLOR_OK
                    : "—".equals(result) ? DIAG_COLOR_PLAIN : DIAG_COLOR_BAD);
        }
        TextView notifPerm = (TextView) findViewById(R.id.diagNotifPermission);
        if (notifPerm != null) {
            boolean granted = notificationsAllowed();
            notifPerm.setText(granted ? R.string.state_granted : R.string.state_not_granted);
            notifPerm.setTextColor(granted ? DIAG_COLOR_OK : DIAG_COLOR_WARN);
        }
        TextView notifService = (TextView) findViewById(R.id.diagNotifService);
        if (notifService != null) {
            boolean running = MyService.isRunning();
            notifService.setText(running ? R.string.state_running : R.string.state_not_running);
            notifService.setTextColor(running ? DIAG_COLOR_OK : DIAG_COLOR_WARN);
        }
        // Battery history is collected by BatteryLoggingService
        // (it holds the battery-changed receiver, independent of
        // the notification feature). When that service is down the
        // graph stays empty, so say why right under it instead of
        // leaving a bare "collecting data…" forever.
        TextView historyHint = (TextView) findViewById(R.id.batteryHistoryHint);
        if (historyHint != null) {
            historyHint.setVisibility(BatteryLoggingService.isRunning() ? View.GONE : View.VISIBLE);
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
                        .append("\n");
                sb.append("Battery logging service: ")
                        .append(BatteryLoggingService.isRunning() ? "running" : "NOT running")
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
                int designMah = BatteryInfoActivity.readCapacityMah(
                        "charge_full_design", "energy_full_design",
                        "POWER_SUPPLY_CHARGE_FULL_DESIGN=");
                String designSource = BatteryInfoActivity.lastCapacitySource;
                int fullMah = BatteryInfoActivity.readCapacityMah(
                        "charge_full", "energy_full",
                        "POWER_SUPPLY_CHARGE_FULL=");
                String fullSource = BatteryInfoActivity.lastCapacitySource;
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
                    final String thermalStatus = buildThermalStatusText();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView list = (TextView) findViewById(R.id.tempSensorList);
                            if (list != null && sensorRefreshing) {
                                list.setText(text);
                            }
                            TextView status = (TextView) findViewById(R.id.tempThermalStatus);
                            if (status != null && sensorRefreshing) {
                                if (thermalStatus != null) {
                                    status.setVisibility(View.VISIBLE);
                                    status.setText(thermalStatus);
                                } else {
                                    status.setVisibility(View.GONE);
                                }
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

    // The thermal status line has its own row in the Sensor Suhu
    // card (6.3.9). No per-sensor °C without root on most ROMs, but
    // the Android Thermal API still answers without root: show the
    // platform's thermal STATUS, labelled as a status — never
    // dressed up as a temperature. Null when the API has no answer.
    private String buildThermalStatusText() {
        int thermalStatus = SystemStats.getThermalStatus(this);
        if (thermalStatus < 0) {
            return null;
        }
        return getString(R.string.temp_thermal_status,
                getString(SystemStats.thermalStatusStringRes(thermalStatus)));
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
