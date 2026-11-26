package com.appslayer.official;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class FloatingOverlayService extends Service {

    private TextView batteryView;
    private boolean clickThrough;
    private TextView cpuView;
    private TextView currentView;
    private TextView fpsView;
    private SharedPreferences prefs;
    private TextView ramView;
    private TextView signalView;
    private TextView tempView;
    private WindowManager windowManager;
    private final Map<String, View> overlayViews = new HashMap<>();
    private boolean isPausedByFullscreen = false;
    private boolean isPausedByLandscape = false;
    private int lastOrientation = 0;

    // ------------------------------------------------------------------
    // Stats polling. The 5.2.0 build ran seven Handler loops on the
    // main thread, and a second set of loops could start from
    // removeOverlay(), so stats work piled up and the overlays stuttered.
    // Now there is exactly one scheduler on one background thread. It
    // collects each stat at its own interval and posts to the main
    // thread only when the text actually changed.
    // ------------------------------------------------------------------
    private static final long INTERVAL_CPU = 1500;
    private static final long INTERVAL_RAM = 3000;
    private static final long INTERVAL_TEMP = 2000;
    private static final long INTERVAL_BATTERY = 2000;
    private static final long INTERVAL_CURRENT = 2000;
    private static final long INTERVAL_SIGNAL = 2000;
    private static final long INTERVAL_FPS = 1000;
    private static final long INTERVAL_PROCESS = 2500;
    private static final long INTERVAL_RECORD = 1000;
    private static final long SCHEDULER_TICK = 500;

    private interface StatSupplier {
        String get();
    }

    private static class StatDef {
        final String key;
        final long intervalMs;
        final StatSupplier supplier;
        // Compact one-piece form of the same stat for the mini bar,
        // or null when this stat has no compact form. A null return
        // from the supplier omits the piece from the bar.
        final StatSupplier miniSupplier;
        long nextDueMs = 0;

        StatDef(String key, long intervalMs, StatSupplier supplier, StatSupplier miniSupplier) {
            this.key = key;
            this.intervalMs = intervalMs;
            this.supplier = supplier;
            this.miniSupplier = miniSupplier;
        }
    }

    // Live instance for the settings screen's diagnosis block: it
    // reads whether the service is up and how many windows it holds.
    private static volatile FloatingOverlayService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    public static int getShownWindowCount() {
        FloatingOverlayService service = instance;
        return service != null ? service.overlayViews.size() : 0;
    }

    // Explicit rebuild request, delivered as a start intent. The
    // settings screen used to rely on the REBUILD_FLOATING_MONITOR
    // broadcast alone; on the user's phone that broadcast never
    // produced a rebuild (service running, switches on, zero
    // windows, toggles feeling dead), so the toggle path now goes
    // through onStartCommand, which cannot be filtered away. The
    // broadcast stays as a secondary path.
    public static final String ACTION_REBUILD = "com.appslayer.official.ACTION_REBUILD_OVERLAY";

    // Diagnosis data recorded by updateActiveOverlays(): how many
    // monitors the service itself sees as enabled, and how the last
    // window-add attempt ended. This is what separates "the service
    // never heard about the switches" from "the window add failed".
    private static volatile int diagEnabledCount = 0;
    private static volatile String diagLastAddResult = "—";
    private String lastRunAddFailure = null;

    public static int getServiceEnabledCount() {
        return diagEnabledCount;
    }

    public static String getLastAddResult() {
        return diagLastAddResult;
    }

    // The mini bar lives in the same window map under its own key.
    private static final String MINI_KEY = "mini_monitor";
    // Fixed left-to-right order of the pieces in the mini bar.
    private static final String[] MINI_ORDER = {
            PrefsKeys.MONITOR_CPU, PrefsKeys.MONITOR_RAM, PrefsKeys.MONITOR_TEMP,
            PrefsKeys.MONITOR_BATTERY, PrefsKeys.MONITOR_CURRENT,
            PrefsKeys.MONITOR_SIGNAL, PrefsKeys.MONITOR_FPS
    };
    // Latest compact piece per stat, main thread only.
    private final Map<String, String> miniSegments = new HashMap<>();
    private String lastMiniText = null;

    private long nextProcessDueMs = 0;
    private long nextRecordDueMs = 0;
    private boolean fpsFirstSampleLogged = false;
    private int lastProcessRows = -2; // -2 = never sampled, -1 = empty parse

    private HandlerThread statsThread;
    private Handler statsHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, StatDef> statDefs = new HashMap<>();
    private final Map<String, String> lastTexts = new HashMap<>();
    private boolean schedulerRunning = false;

    private final Runnable schedulerTick = new Runnable() {
        @Override
        public void run() {
            if (!schedulerRunning) {
                return;
            }
            // The FPS recorder is fed before the pause check on
            // purpose: recording exists for fullscreen games, which
            // is exactly when the overlays are paused. One sample
            // per second at the FPS sampler's own cadence.
            if (FpsRecorder.isRecording()) {
                long nowRecording = System.currentTimeMillis();
                if (nowRecording >= nextRecordDueMs) {
                    nextRecordDueMs = nowRecording + INTERVAL_RECORD;
                    int result = FpsRecorder.sampleNow(FloatingOverlayService.this);
                    if (result == FpsRecorder.SAMPLE_LIMIT_REACHED) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(FloatingOverlayService.this,
                                        R.string.toast_recording_limit,
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
            if (!fpsFirstSampleLogged) {
                Double fps = SystemStats.getFpsValue();
                if (fps != null) {
                    fpsFirstSampleLogged = true;
                    DiagLog.log("FPS", "first FPS sample after service start: " + fps);
                }
            }
            updatePauseStateFromBackground();
            if (!isPausedByFullscreen && !isPausedByLandscape) {
                long now = System.currentTimeMillis();
                final boolean miniPresent = overlayViews.containsKey(MINI_KEY);
                for (final StatDef def : statDefs.values()) {
                    final boolean classicPresent = overlayViews.containsKey(def.key);
                    // In mini mode a stat is only polled while its
                    // switch is on; off stats leave no piece in the bar.
                    final boolean miniNeeded = miniPresent && prefs.getBoolean(def.key, false);
                    if ((!classicPresent && !miniNeeded) || now < def.nextDueMs) {
                        continue;
                    }
                    def.nextDueMs = now + def.intervalMs;
                    final String text = classicPresent ? def.supplier.get() : null;
                    final String segment = miniPresent && def.miniSupplier != null
                            ? def.miniSupplier.get() : null;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (text != null) {
                                applyStatText(def.key, text);
                            }
                            if (miniPresent) {
                                if (segment != null) {
                                    miniSegments.put(def.key, segment);
                                } else {
                                    miniSegments.remove(def.key);
                                }
                                refreshMiniText();
                            }
                        }
                    });
                }
                // The process list is not a StatDef: it has no mini
                // segment (a list does not fit the bar), it just
                // refreshes its own window on its own interval.
                if (overlayViews.containsKey(PrefsKeys.MONITOR_PROCESS)
                        && now >= nextProcessDueMs) {
                    nextProcessDueMs = now + INTERVAL_PROCESS;
                    final String processText = SystemStats.getTopProcesses(5);
                    int rows = processText == null ? -1
                            : processText.isEmpty() ? 0 : processText.split("\n").length;
                    if (rows != lastProcessRows) {
                        lastProcessRows = rows;
                        DiagLog.log("SYS", rows < 0
                                ? "process snapshot: empty (top parse gave nothing)"
                                : "process snapshot: " + rows + " rows");
                    }
                    if (processText != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyStatText(PrefsKeys.MONITOR_PROCESS, processText);
                            }
                        });
                    }
                }
            }
            statsHandler.postDelayed(this, SCHEDULER_TICK);
        }
    };

    private TelephonyManager telephonyManager;
    private PhoneStateListener signalListener;

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("UPDATE_FLOATING_MONITOR".equals(action)) {
                updateOverlayAppearance();
            } else if ("REBUILD_FLOATING_MONITOR".equals(action)) {
                DiagLog.log("OVERLAY", "rebuild requested via broadcast");
                updateActiveOverlays();
            }
        }
    };

    private final BroadcastReceiver clickThroughReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateAllOverlayTouchability();
        }
    };

    public interface DataUpdater {
        void update(TextView textView);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagLog.log(this, "OVERLAY", "overlay service created");
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
        this.windowManager = (WindowManager) getSystemService("window");
        registerCustomReceiver(this.settingsReceiver, new IntentFilter("UPDATE_FLOATING_MONITOR"));
        registerCustomReceiver(this.settingsReceiver, new IntentFilter("REBUILD_FLOATING_MONITOR"));
        registerCustomReceiver(this.clickThroughReceiver, new IntentFilter("UPDATE_CLICK_THROUGH"));
        // Go foreground FIRST. The old order built all the overlay
        // windows before calling startForeground(), so any hiccup in
        // window creation left a started foreground service that had
        // never gone foreground — the system then kills it and the
        // overlays silently never appear.
        startForegroundServiceNotification();
        initStatDefs();
        startStatsThread();
        // The FPS value comes from a process-wide sampler in
        // SystemStats; start it here too so the FPS overlay also works
        // when this service is started without the monitor screen
        // (e.g. by the boot receiver). The call is idempotent.
        SystemStats.startFpsMonitorThread();
        registerSignalListener();
        refreshOverlays();
        startScheduler();
    }

    private void registerCustomReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void initStatDefs() {
        statDefs.put(PrefsKeys.MONITOR_CPU, new StatDef(PrefsKeys.MONITOR_CPU, INTERVAL_CPU, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getCpuFrequency();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                int cpu = SystemStats.getCpuUsagePercent();
                if (cpu < 0) {
                    return null;
                }
                String piece = "CPU " + cpu + "%";
                int gpu = SystemStats.getGpuUsagePercent();
                if (gpu >= 0) {
                    piece += " · GPU " + gpu + "%";
                }
                return piece;
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_RAM, new StatDef(PrefsKeys.MONITOR_RAM, INTERVAL_RAM, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getRamUsage();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                int ram = SystemStats.getRamUsagePercent();
                return ram < 0 ? null : "RAM " + ram + "%";
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_TEMP, new StatDef(PrefsKeys.MONITOR_TEMP, INTERVAL_TEMP, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getCpuGpuTemp();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                String temp = SystemStats.getCpuGpuTemp();
                return "n/a".equals(temp) ? null : temp;
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_BATTERY, new StatDef(PrefsKeys.MONITOR_BATTERY, INTERVAL_BATTERY, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getBatteryInfo();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                String info = SystemStats.getBatteryInfo();
                if ("n/a".equals(info)) {
                    return null;
                }
                // The classic text is "58% · 33°C"; the bar only
                // carries the level.
                return "BAT " + info.split(" · ")[0];
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_CURRENT, new StatDef(PrefsKeys.MONITOR_CURRENT, INTERVAL_CURRENT, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getBatteryCurrent();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                Integer ma = SystemStats.readBatteryCurrentMa();
                if (ma == null) {
                    return null;
                }
                Intent intent = registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
                boolean plugged = intent != null && intent.getIntExtra("plugged", 0) != 0;
                return (plugged ? "+" : "-") + ma + " mA";
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_SIGNAL, new StatDef(PrefsKeys.MONITOR_SIGNAL, INTERVAL_SIGNAL, new StatSupplier() {
            @Override
            public String get() {
                return SystemStats.getSignalVisual();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                int dbm = SystemStats.getSignalDbm();
                return (dbm < -150 || dbm > -30) ? null : dbm + " dBm";
            }
        }));
        statDefs.put(PrefsKeys.MONITOR_FPS, new StatDef(PrefsKeys.MONITOR_FPS, INTERVAL_FPS, new StatSupplier() {
            @Override
            public String get() {
                // The classic FPS window is the combined chip
                // (CPU/GPU load, FPS, CPU temperature on one line).
                return SystemStats.getCombinedChip();
            }
        }, new StatSupplier() {
            @Override
            public String get() {
                String fps = SystemStats.getFps();
                return (fps == null || fps.contains("n/a") || fps.contains("...")) ? null : fps;
            }
        }));
    }

    // Rebuilds the mini bar text from the latest pieces of the stats
    // that are switched on. Main thread only, and the window is only
    // touched when the composed text actually changed.
    private void refreshMiniText() {
        View view = this.overlayViews.get(MINI_KEY);
        if (view == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : MINI_ORDER) {
            if (!this.prefs.getBoolean(key, false)) {
                continue;
            }
            String piece = this.miniSegments.get(key);
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(piece);
        }
        String text = sb.toString();
        if (text.equals(this.lastMiniText)) {
            return;
        }
        this.lastMiniText = text;
        TextView textView = (TextView) view.findViewById(R.id.textMonitor);
        if (textView == null) {
            return;
        }
        textView.setText(text.isEmpty() ? getString(R.string.overlay_loading) : text);
        textView.setTextColor(this.prefs.getBoolean(PrefsKeys.DARK_TEXT, false) ? Color.BLACK : Color.WHITE);
        if (this.prefs.getBoolean(PrefsKeys.TEXT_SHADOW, false)) {
            textView.setShadowLayer(4, 2, 2, Color.BLACK);
        } else {
            textView.setShadowLayer(0, 0, 0, 0);
        }
    }

    private void startStatsThread() {
        this.statsThread = new HandlerThread("AppSlayerStats");
        this.statsThread.start();
        this.statsHandler = new Handler(this.statsThread.getLooper());
    }

    // The one and only place the scheduler is started.
    private void startScheduler() {
        if (this.schedulerRunning || this.statsHandler == null) {
            return;
        }
        this.schedulerRunning = true;
        this.statsHandler.post(this.schedulerTick);
    }

    private void stopScheduler() {
        this.schedulerRunning = false;
        if (this.statsHandler != null) {
            this.statsHandler.removeCallbacks(this.schedulerTick);
        }
    }

    // Runs on the background thread. Pause/resume itself is posted to
    // the main thread because it touches views and notifications.
    private void updatePauseStateFromBackground() {
        boolean fullscreen = this.prefs.getBoolean(PrefsKeys.HIDE_IN_FULLSCREEN, false) && isInFullScreen();
        boolean landscape = this.prefs.getBoolean(PrefsKeys.DISABLE_IN_LANDSCAPE, false)
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (fullscreen && !this.isPausedByFullscreen) {
            this.isPausedByFullscreen = true;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    pauseAllOverlays();
                }
            });
        } else if (!fullscreen && this.isPausedByFullscreen) {
            this.isPausedByFullscreen = false;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    resumeAllOverlays();
                }
            });
        }
        if (landscape && !this.isPausedByLandscape) {
            this.isPausedByLandscape = true;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    pauseAllOverlays();
                }
            });
        } else if (!landscape && this.isPausedByLandscape) {
            this.isPausedByLandscape = false;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    resumeAllOverlays();
                }
            });
        }
    }

    // Main thread only, and only when the text changed.
    private void applyStatText(String key, String text) {
        if (text == null || text.equals(this.lastTexts.get(key))) {
            return;
        }
        this.lastTexts.put(key, text);
        View view = this.overlayViews.get(key);
        if (view == null) {
            return;
        }
        TextView textView = (TextView) view.findViewById(R.id.textMonitor);
        if (textView == null) {
            return;
        }
        textView.setText(text);
        textView.setTextColor(this.prefs.getBoolean(PrefsKeys.DARK_TEXT, false) ? Color.BLACK : Color.WHITE);
        if (this.prefs.getBoolean(PrefsKeys.TEXT_SHADOW, false)) {
            textView.setShadowLayer(4, 2, 2, Color.BLACK);
        } else {
            textView.setShadowLayer(0, 0, 0, 0);
        }
    }

    private void registerSignalListener() {
        try {
            this.telephonyManager = (TelephonyManager) getSystemService("phone");
            if (this.telephonyManager == null) {
                return;
            }
            this.signalListener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    if (signalStrength == null) {
                        return;
                    }
                    for (android.telephony.CellSignalStrength strength : signalStrength.getCellSignalStrengths()) {
                        int dbm = strength.getDbm();
                        if (dbm >= -150 && dbm <= -30) {
                            SystemStats.updateCachedSignalDbm(dbm);
                            return;
                        }
                    }
                }
            };
            this.telephonyManager.listen(this.signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception ignored) {
            // No permission or no telephony: the signal stat just shows n/a.
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean disableInLandscape = this.prefs.getBoolean(PrefsKeys.DISABLE_IN_LANDSCAPE, false);
        if (disableInLandscape && landscape && !this.isPausedByLandscape) {
            this.isPausedByLandscape = true;
            pauseAllOverlays();
        } else if ((!landscape || !disableInLandscape) && this.isPausedByLandscape) {
            this.isPausedByLandscape = false;
            resumeAllOverlays();
        }
        updateOverlayVisibility();
        sendBroadcast(new Intent("REBUILD_FLOATING_MONITOR"));
        sendBroadcast(new Intent("UPDATE_FLOATING_MONITOR"));
    }

    private void createOverlay(final String key, int layoutRes, int defaultX, int defaultY, DataUpdater dataUpdater) {
        this.clickThrough = this.prefs.getBoolean(PrefsKeys.CLICK_THROUGH, false);
        if (this.overlayViews.containsKey(key)) {
            removeOverlay(key);
        }
        View view;
        TextView textView;
        try {
            view = LayoutInflater.from(this).inflate(layoutRes, (ViewGroup) null);
            textView = (TextView) view.findViewById(R.id.textMonitor);
            if (textView == null) {
                throw new IllegalStateException("layout has no textMonitor view");
            }
            // The first text fill used to run outside any try/catch:
            // one throwing stat reader aborted the whole rebuild and
            // every window after it, leaving the service running with
            // zero windows and nothing recorded anywhere.
            dataUpdater.update(textView);
        } catch (Exception e) {
            recordAddFailure(key, e);
            return;
        }
        boolean darkText = this.prefs.getBoolean(PrefsKeys.DARK_TEXT, false);
        if (darkText) {
            textView.setBackgroundResource(R.drawable.white_background);
        } else {
            textView.setBackgroundResource(R.drawable.overlay_background);
        }
        textView.setTextColor(darkText ? Color.BLACK : Color.WHITE);
        int alpha = this.prefs.getInt(PrefsKeys.OVERLAY_ALPHA, 200);
        int textSize = this.prefs.getInt(PrefsKeys.OVERLAY_TEXT_SIZE, 14);
        textView.getBackground().setAlpha(alpha);
        textView.setAlpha(1.0f);
        textView.setTextSize(textSize);
        int windowType = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        int flags = 8;
        if (this.clickThrough) {
            flags = 8 | 16;
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, windowType, flags, -3);
        params.gravity = 51;
        boolean snapToBar = this.prefs.getBoolean(PrefsKeys.SNAP_TO_BAR, false);
        params.x = this.prefs.getInt(key + "_x", defaultX);
        params.y = snapToBar ? getStatusBarHeight() : this.prefs.getInt(key + "_y", defaultY);
        try {
            this.windowManager.addView(view, params);
        } catch (Exception e) {
            // Never fail silently: the user flipped a switch, so if the
            // window cannot be added they get told why — and the
            // outcome is recorded for the diagnosis block and the log.
            recordAddFailure(key, e);
            android.util.Log.e("AppSlayer", "Overlay addView failed for " + key, e);
            android.widget.Toast.makeText(this,
                    getString(R.string.toast_overlay_failed, e.getMessage()),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        this.overlayViews.put(key, view);
        view.setTranslationY(-100);
        view.setAlpha(0.0f);
        view.animate().translationY(0).alpha(1.0f).setDuration(400).start();
        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] initialTouchX = new float[1];
        final float[] initialTouchY = new float[1];
        final View overlayView = view;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (clickThrough) {
                    return false;
                }
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX[0] = params.x;
                        initialY[0] = params.y;
                        initialTouchX[0] = event.getRawX();
                        initialTouchY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit()
                                .putInt(key + "_x", params.x)
                                .putInt(key + "_y", params.y)
                                .apply();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX[0] + ((int) (event.getRawX() - initialTouchX[0]));
                        params.y = initialY[0] + ((int) (event.getRawY() - initialTouchY[0]));
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                    default:
                        return false;
                }
            }
        });
        view.setAlpha(this.prefs.getInt(PrefsKeys.OVERLAY_ALPHA, 200) / 255.0f);
        try {
            dataUpdater.update(textView);
        } catch (Exception e) {
            // The window exists at this point; a bad refresh is not
            // an add failure, it just goes to the log.
            DiagLog.log("OVERLAY", "initial text fill failed for " + key + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // Records a failed window creation for the diagnosis block and
    // the diagnostic log (class + message, e.g. the BadTokenException
    // a ROM throws when its own pop-up permission is missing even
    // though the standard overlay permission is granted).
    private void recordAddFailure(String key, Exception e) {
        String outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
        this.lastRunAddFailure = key + " -> " + outcome;
        DiagLog.log("OVERLAY", "window add failed for " + key + ": " + outcome);
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService("notification");
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel("overlay_channel", getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent intent = new Intent(this, FloatingOverlayService.class);
        intent.setAction("STOP_OVERLAY");
        Notification notification = new Notification.Builder(this, "overlay_channel")
                .setContentTitle(getString(R.string.floating_monitor_title))
                .setContentText(getString(R.string.overlay_running))
                .setSmallIcon(android.R.drawable.presence_video_online)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_close),
                        PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        // Notification id 2, not 1: MyService owns id 1, and its
        // stop path cancels id 1 — which used to strip this service's
        // foreground notification and get the overlay killed.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(2, notification);
        }
    }

    public void updateOverlayAppearance() {
        int alpha = this.prefs.getInt(PrefsKeys.OVERLAY_ALPHA, 200);
        int textSize = this.prefs.getInt(PrefsKeys.OVERLAY_TEXT_SIZE, 14);
        boolean darkText = this.prefs.getBoolean(PrefsKeys.DARK_TEXT, false);
        for (Map.Entry<String, View> entry : this.overlayViews.entrySet()) {
            View view = entry.getValue();
            TextView textView;
            if (view != null && (textView = (TextView) view.findViewById(R.id.textMonitor)) != null) {
                if (darkText) {
                    textView.setBackgroundResource(R.drawable.white_background);
                } else {
                    textView.setBackgroundResource(R.drawable.overlay_background);
                }
                textView.setTextSize(textSize);
                textView.getBackground().setAlpha(alpha);
                view.setAlpha(alpha / 255.0f);
                textView.setTextColor(darkText ? Color.BLACK : Color.WHITE);
                if (this.prefs.getBoolean(PrefsKeys.HIDE_IN_FULLSCREEN, false)) {
                    view.setVisibility(isInFullscreenApp() ? View.GONE : View.VISIBLE);
                }
            }
        }
    }

    private boolean isInFullscreenApp() {
        try {
            for (ActivityManager.RunningAppProcessInfo processInfo : ((ActivityManager) getSystemService("activity")).getRunningAppProcesses()) {
                if (processInfo.importance == 100
                        && processInfo.processName.startsWith(getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void refreshOverlays() {
        updateActiveOverlays();
        updateOverlayVisibility();
    }

    private void removeAllOverlays() {
        // Iterate over a copy: removeOverlay() removes from the map,
        // which throws ConcurrentModificationException on the live set
        // whenever two or more overlays are up (e.g. in onDestroy).
        for (String key : new java.util.ArrayList<>(this.overlayViews.keySet())) {
            removeOverlay(key);
        }
    }

    private void removeOverlay(final String key) {
        final View view = this.overlayViews.get(key);
        if (view == null) {
            return;
        }
        this.overlayViews.remove(key);
        this.lastTexts.remove(key);
        if (view.getWindowToken() != null) {
            view.animate().alpha(0.0f).setDuration(300).withEndAction(new Runnable() {
                @Override
                public void run() {
                    try {
                        windowManager.removeView(view);
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }

    // Closes out a rebuild run: publishes the diagnosis values (how
    // many monitors the service sees as enabled, how the last add
    // ended) and writes the run's summary line to the log.
    private void finishRebuild(String style, int enabledCount) {
        diagEnabledCount = enabledCount;
        diagLastAddResult = this.lastRunAddFailure != null
                ? this.lastRunAddFailure
                : "OK (" + this.overlayViews.size() + ")";
        DiagLog.log("OVERLAY", "rebuild done: style=" + style
                + " enabled(service)=" + enabledCount
                + " windows=" + this.overlayViews.size()
                + " lastAdd=" + diagLastAddResult);
    }

    private int countEnabled(String[] keys) {
        int count = 0;
        for (String key : keys) {
            if (this.prefs.getBoolean(key, false)) {
                count++;
            }
        }
        return count;
    }

    public void updateActiveOverlays() {
        this.lastRunAddFailure = null;
        boolean mini = PrefsKeys.MONITOR_STYLE_MINI.equals(
                this.prefs.getString(PrefsKeys.MONITOR_STYLE, PrefsKeys.MONITOR_STYLE_CLASSIC));
        if (mini) {
            // One combined bar instead of the per-stat windows.
            for (String key : MINI_ORDER) {
                if (this.overlayViews.containsKey(key)) {
                    removeOverlay(key);
                }
            }
            boolean anyEnabled = false;
            for (String key : MINI_ORDER) {
                if (this.prefs.getBoolean(key, false)) {
                    anyEnabled = true;
                    break;
                }
            }
            if (anyEnabled && !this.overlayViews.containsKey(MINI_KEY)) {
                createOverlay(MINI_KEY, R.layout.overlay_mini, 100, 200, new DataUpdater() {
                    @Override
                    public void update(TextView textView) {
                        textView.setText(lastMiniText != null && !lastMiniText.isEmpty()
                                ? lastMiniText : getString(R.string.overlay_loading));
                    }
                });
            } else if (!anyEnabled && this.overlayViews.containsKey(MINI_KEY)) {
                removeOverlay(MINI_KEY);
            }
            // The process list is a standalone window, not a bar
            // piece: it shows alongside the mini bar too.
            updateOverlay(PrefsKeys.MONITOR_PROCESS, R.layout.overlay_process, 100, 900, new DataUpdater() {
                @Override
                public void update(TextView textView) {
                    // Loading text only: the top snapshot runs on
                    // the background scheduler, not here on the UI
                    // thread during window creation.
                    textView.setText(R.string.overlay_loading);
                }
            });
            refreshMiniText();
            int enabledMini = countEnabled(MINI_ORDER)
                    + (this.prefs.getBoolean(PrefsKeys.MONITOR_PROCESS, false) ? 1 : 0);
            finishRebuild("mini", enabledMini);
            return;
        }
        if (this.overlayViews.containsKey(MINI_KEY)) {
            removeOverlay(MINI_KEY);
        }
        updateOverlay(PrefsKeys.MONITOR_CPU, R.layout.overlay_cpu, 100, 200, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getCpuFrequency());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_RAM, R.layout.overlay_ram, 100, 300, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getRamUsage());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_TEMP, R.layout.overlay_temp, 100, 400, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getCpuGpuTemp());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_BATTERY, R.layout.overlay_battery, 100, 500, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getBatteryInfo());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_CURRENT, R.layout.overlay_current, 100, 600, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getBatteryCurrent());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_SIGNAL, R.layout.overlay_signal, 100, 700, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getSignalVisual());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_FPS, R.layout.overlay_fps, 100, 800, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                textView.setText(SystemStats.getCombinedChip());
            }
        });
        updateOverlay(PrefsKeys.MONITOR_PROCESS, R.layout.overlay_process, 100, 900, new DataUpdater() {
            @Override
            public void update(TextView textView) {
                // Loading text only, same reason as in mini mode:
                // the scheduler fills in the real list.
                textView.setText(R.string.overlay_loading);
            }
        });
        int enabledClassic = countEnabled(MINI_ORDER)
                + (this.prefs.getBoolean(PrefsKeys.MONITOR_PROCESS, false) ? 1 : 0);
        finishRebuild("classic", enabledClassic);
    }

    private void updateOverlay(String key, int layoutRes, int defaultX, int defaultY, DataUpdater dataUpdater) {
        boolean enabled = this.prefs.getBoolean(key, false);
        boolean present = this.overlayViews.containsKey(key);
        if (enabled && !present) {
            createOverlay(key, layoutRes, defaultX, defaultY, dataUpdater);
        } else if (!enabled && present) {
            removeOverlay(key);
        }
    }

    private int getStatusBarHeight() {
        int height = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            height = getResources().getDimensionPixelSize(resourceId);
        }
        return height;
    }

    public boolean isInFullScreen() {
        // Since Android 5.1 an app can only ever see its OWN processes
        // in this list (some ROMs return more). The old check treated
        // ANY importance-100 (foreground) process as "a fullscreen app
        // is running", so with "hide in fullscreen" switched on, the
        // overlays stayed paused forever — some process is in the
        // foreground whenever the phone is in use. Only our own UI
        // being in the foreground counts now: the overlays step aside
        // while App Slayer itself is on screen and come back as soon
        // as the user leaves the app. Other apps' fullscreen state is
        // not visible to a normal app on modern Android.
        java.util.List<ActivityManager.RunningAppProcessInfo> processes =
                ((ActivityManager) getSystemService("activity")).getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
            if (processInfo.importance == 100
                    && processInfo.processName.startsWith(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    public void updateAllOverlayTouchability() {
        this.clickThrough = this.prefs.getBoolean(PrefsKeys.CLICK_THROUGH, false);
        for (Map.Entry<String, View> entry : this.overlayViews.entrySet()) {
            View view = entry.getValue();
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            if (this.clickThrough) {
                params.flags |= 16;
            } else {
                params.flags &= -17;
            }
            try {
                this.windowManager.updateViewLayout(view, params);
            } catch (Exception ignored) {
            }
        }
    }

    public void pauseAllOverlays() {
        // Pause only hides the windows (visibility), it never
        // removes them — the diagnosis window count stays truthful.
        DiagLog.log("OVERLAY", "overlays hidden by a pause gate (fullscreen/landscape), windows kept: "
                + this.overlayViews.size());
        for (View view : this.overlayViews.values()) {
            view.setVisibility(View.INVISIBLE);
        }
        showResumeNotification();
    }

    public void resumeAllOverlays() {
        DiagLog.log("OVERLAY", "overlays resumed, windows: " + this.overlayViews.size());
        for (View view : this.overlayViews.values()) {
            view.setVisibility(View.VISIBLE);
        }
        hideResumeNotification();
    }

    private void updateOverlayVisibility() {
        boolean hide = this.prefs.getBoolean(PrefsKeys.DISABLE_IN_LANDSCAPE, false)
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        for (View view : this.overlayViews.values()) {
            view.setVisibility(hide ? View.GONE : View.VISIBLE);
        }
    }

    private void showResumeNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService("notification");
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel("overlay_resume_channel", getString(R.string.resume_channel_name), NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent intent = new Intent(this, FloatingOverlayService.class);
        intent.setAction("RESUME_OVERLAY");
        NotificationManager manager = (NotificationManager) getSystemService("notification");
        if (manager != null) {
            manager.notify(99, new Notification.Builder(this, "overlay_resume_channel")
                    .setContentTitle(getString(R.string.overlay_paused_title))
                    .setContentText(getString(R.string.overlay_paused_text))
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .addAction(android.R.drawable.ic_media_play, getString(R.string.action_resume_overlay),
                            PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true)
                    .build());
        }
    }

    private void hideResumeNotification() {
        NotificationManager manager = (NotificationManager) getSystemService("notification");
        if (manager != null) {
            manager.cancel(99);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_REBUILD.equals(action)) {
                // The toggle path: rebuild right now on the main
                // thread (window creation paints the initial text
                // synchronously) and make every stat due immediately,
                // so the next scheduler tick — at most half a second
                // out — refreshes all texts instead of waiting out
                // each stat's interval.
                DiagLog.log("OVERLAY", "rebuild requested via service intent");
                updateActiveOverlays();
                for (StatDef def : statDefs.values()) {
                    def.nextDueMs = 0;
                }
                nextProcessDueMs = 0;
            } else if ("RESUME_OVERLAY".equals(action)) {
                resumeAllOverlays();
            } else if ("STOP_OVERLAY".equals(action)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DiagLog.log(this, "OVERLAY", "overlay service destroyed");
        DiagLog.flushMirrorAsync(this);
        if (instance == this) {
            instance = null;
        }
        // A recording in progress belongs to this service's
        // scheduler; if the service dies, finalize the session file
        // instead of leaving a half-written ".tmp" behind.
        if (FpsRecorder.isRecording()) {
            FpsRecorder.stop(this);
        }
        stopScheduler();
        if (this.telephonyManager != null && this.signalListener != null) {
            try {
                this.telephonyManager.listen(this.signalListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) {
            }
        }
        try {
            unregisterReceiver(this.settingsReceiver);
        } catch (Exception ignored) {
        }
        removeAllOverlays();
        try {
            unregisterReceiver(this.clickThroughReceiver);
        } catch (Exception ignored) {
        }
        if (this.statsThread != null) {
            this.statsThread.quitSafely();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
