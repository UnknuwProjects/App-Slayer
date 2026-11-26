package com.appslayer.official;

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
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public class MyService extends Service {

    public static final String ACTION_STOP_NOTIFICATION = "com.appslayer.official.ACTION_STOP_NOTIFICATION";
    public static final String CHANNEL_ID = "AppSlayerChannel";
    public static final int NOTIF_ID = 1;

    // Live instance for the diagnosis block on the monitor screen.
    private static volatile MyService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    private String currentStatusLine;
    private Runnable ramRunnable;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver showToastReceiver;
    private Runnable thermalRunnable;
    private BroadcastReceiver toastReceiver;
    private String latestCpu = "n/a";
    private String latestGpu = "n/a";
    private String latestBattery = "n/a";
    private String latestRam = "n/a";
    private int cpuZone = -1;
    private int gpuZone = -1;
    private int batteryZone = -1;
    private boolean serviceStopped = false;
    private Handler thermalHandler = new Handler();
    private Handler ramHandler = new Handler();
    private String lastRamLine = "";
    private String lastCpuGpuLine = "";
    private String lastBatteryLine = "";
    private String lastToastLine = "";
    private long notifStartTimestamp = 0;
    private String customModeLine = "";
    private String customProcessLine = "";
    private BroadcastReceiver batteryHistoryReceiver;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagLog.log(this, "NOTIF", "notification service created");
        // No root shell is opened here anymore: nothing in this
        // service ever sent it a command (battery current goes through
        // SystemStats and its own shell), so starting one only made
        // Magisk show a root prompt for no reason.
        this.currentStatusLine = getSharedPreferences(PrefsKeys.APP_PREFS, 0)
                .getString(PrefsKeys.LAST_STATUS_LINE, getString(R.string.notif_status_default));
        createNotificationChannel();
        this.notifStartTimestamp = System.currentTimeMillis();
        registerToastReceiver();
        registerShowToastReceiver();
        registerBatteryHistoryReceiver();
        detectThermalZones();
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.serviceStopped = false;
        SharedPreferences sharedPreferences = getSharedPreferences(PrefsKeys.APP_PREFS, 0);
        boolean notifEnabled = sharedPreferences.getBoolean(PrefsKeys.NOTIFICATIONS, true);
        if (intent != null && ACTION_STOP_NOTIFICATION.equals(intent.getAction())) {
            DiagLog.log("NOTIF", "stop requested via ACTION_STOP_NOTIFICATION");
            this.serviceStopped = true;
            stopTimers();
            stopForeground(true);
            stopSelf();
            NotificationManager notificationManager = (NotificationManager) getSystemService("notification");
            if (notificationManager != null) {
                notificationManager.cancel(NOTIF_ID);
            }
            return START_NOT_STICKY;
        } else if (!notifEnabled) {
            DiagLog.log("NOTIF", "start refused: notification preference is off");
            stopTimers();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        } else {
            this.notifStartTimestamp = System.currentTimeMillis();
            stopTimers();
            this.currentStatusLine = getString(R.string.notif_status_default);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PrefsKeys.LAST_STATUS_LINE, this.currentStatusLine);
            editor.apply();
            this.latestCpu = getThermalByType("cpu");
            this.latestGpu = getThermalByType("gpu");
            this.latestBattery = getBatteryInfo();
            this.latestRam = getRamInfo();
            this.lastCpuGpuLine = "";
            this.lastBatteryLine = "";
            this.lastRamLine = "";
            this.lastToastLine = "";
            Notification foregroundNotification = buildNotification(
                    "CPU : " + this.latestCpu + " · GPU : " + this.latestGpu + "\n"
                            + "Battery : " + this.latestBattery + "\n" + this.latestRam,
                    this.currentStatusLine);
            // Same typed call the overlay service uses: at targetSdk
            // 34+ the specialUse type is passed explicitly instead of
            // relying on the manifest alone.
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, foregroundNotification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIF_ID, foregroundNotification);
                }
                DiagLog.log("NOTIF", "startForeground ok (id " + NOTIF_ID + ")");
            } catch (Exception e) {
                DiagLog.log("NOTIF", "startForeground FAILED: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
            this.lastCpuGpuLine = "";
            this.lastBatteryLine = "";
            this.lastRamLine = "";
            this.lastToastLine = "";
            updateNotification();
            startTimers();
            return START_STICKY;
        }
    }

    public void startTimers() {
        this.thermalHandler.removeCallbacksAndMessages(null);
        this.ramHandler.removeCallbacksAndMessages(null);
        this.thermalRunnable = new Runnable() {
            @Override
            public void run() {
                latestCpu = getThermalByType("cpu");
                latestGpu = getThermalByType("gpu");
                latestBattery = getBatteryInfo();
                lastCpuGpuLine = "";
                lastBatteryLine = "";
                updateNotification();
                thermalHandler.postDelayed(this, 1500);
            }
        };
        this.ramRunnable = new Runnable() {
            @Override
            public void run() {
                latestRam = getRamInfo();
                lastRamLine = "";
                updateNotification();
                ramHandler.postDelayed(this, 5000);
            }
        };
        this.thermalHandler.post(this.thermalRunnable);
        this.ramHandler.post(this.ramRunnable);
    }

    public void stopTimers() {
        this.thermalHandler.removeCallbacksAndMessages(null);
        this.ramHandler.removeCallbacksAndMessages(null);
    }

    private void registerScreenReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        intentFilter.addAction("android.intent.action.SCREEN_ON");
        this.screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    stopTimers();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    startTimers();
                }
            }
        };
        registerReceiver(this.screenReceiver, intentFilter);
        // The 5.2.0 code also ran filesDir/service.sh here, but no code
        // ever extracted a service.sh into filesDir, so that exec could
        // only fail silently. The module's own service.sh is started by
        // the root manager, not by this app.
    }

    public void updateNotification() {
        if (!this.serviceStopped && getSharedPreferences(PrefsKeys.APP_PREFS, 0).getBoolean(PrefsKeys.NOTIFICATIONS, true)) {
            String cpuGpuLine = "CPU : " + this.latestCpu + " · GPU : " + this.latestGpu;
            String batteryLine = "Battery : " + this.latestBattery;
            String ramLine = this.latestRam;
            String statusLine = this.currentStatusLine;
            boolean changed = false;
            if (!cpuGpuLine.equals(this.lastCpuGpuLine)) {
                this.lastCpuGpuLine = cpuGpuLine;
                changed = true;
            }
            if (!batteryLine.equals(this.lastBatteryLine)) {
                this.lastBatteryLine = batteryLine;
                changed = true;
            }
            if (!ramLine.equals(this.lastRamLine)) {
                this.lastRamLine = ramLine;
                changed = true;
            }
            if (!statusLine.equals(this.lastToastLine)) {
                this.lastToastLine = statusLine;
                changed = true;
            }
            if (changed) {
                Notification notification = buildNotification(cpuGpuLine + "\n" + batteryLine + "\n" + ramLine, statusLine);
                NotificationManager notificationManager = (NotificationManager) getSystemService("notification");
                if (notificationManager != null) {
                    notificationManager.notify(NOTIF_ID, notification);
                }
            }
        }
    }

    public String getRamInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            long total = 0;
            long available = 0;
            long swapTotal = 0;
            long swapFree = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = Long.parseLong(line.replaceAll("\\D+", ""));
                } else if (line.startsWith("MemAvailable:")) {
                    available = Long.parseLong(line.replaceAll("\\D+", ""));
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = Long.parseLong(line.replaceAll("\\D+", ""));
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = Long.parseLong(line.replaceAll("\\D+", ""));
                }
                if (total > 0 && available > 0 && swapTotal > 0 && swapFree > 0) {
                    break;
                }
            }
            reader.close();
            if (total > 0 && available > 0) {
                long totalAll = total + swapTotal;
                long freeAll = available + swapFree;
                double totalGb = (((float) totalAll) / 1024.0f) / 1024.0f;
                double freeGb = (((float) freeAll) / 1024.0f) / 1024.0f;
                return String.format(Locale.US, getString(R.string.notif_ram_format),
                        freeGb, totalGb, (int) ((((float) (totalAll - freeAll)) * 100.0f) / ((float) totalAll)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "RAM: n/a";
    }

    private void detectThermalZones() {
        for (int i = 0; i < 30; i++) {
            try {
                File file = new File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (file.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    String type = reader.readLine();
                    reader.close();
                    if (type != null) {
                        String lower = type.toLowerCase();
                        if (lower.contains("cpu") && this.cpuZone == -1) {
                            this.cpuZone = i;
                        } else if (lower.contains("gpu") && this.gpuZone == -1) {
                            this.gpuZone = i;
                        } else if ((lower.contains("batt") || lower.contains("bat")) && this.batteryZone == -1) {
                            this.batteryZone = i;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private Notification buildNotification(String text, String status) {
        int color;
        String upper = status.toUpperCase();
        if (upper.contains("EXTREME")) {
            color = Color.RED;
        } else if (upper.contains("AGGRESSIVE")) {
            color = Color.YELLOW;
        } else if (upper.contains("NORMAL")) {
            color = Color.GREEN;
        } else if (upper.contains("BERHASIL") || upper.contains("SUCCESS")) {
            color = Color.GREEN;
        } else if (upper.contains("GAGAL") || upper.contains("FAILED")) {
            color = Color.RED;
        } else {
            color = Color.BLUE;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        SpannableString spannable = new SpannableString(text + "\n" + status);
        int open = text.indexOf('(');
        int close = text.indexOf(')', open);
        if (open >= 0 && close > open) {
            try {
                String inside = text.substring(open + 1, close);
                int percentIndex = inside.indexOf('%');
                if (percentIndex > 0) {
                    int percent = Integer.parseInt(inside.substring(0, percentIndex).trim());
                    int spanColor;
                    if (percent <= 75) {
                        spanColor = Color.GREEN;
                    } else if (percent <= 90) {
                        spanColor = Color.parseColor("#FFA500");
                    } else {
                        spanColor = Color.RED;
                    }
                    spannable.setSpan(new ForegroundColorSpan(spanColor), open + 1, close, 33);
                }
            } catch (Exception ignored) {
            }
        }
        builder.setStyle(new Notification.BigTextStyle().bigText(spannable))
                .setContentText(spannable)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(color)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setWhen(this.notifStartTimestamp)
                .setShowWhen(true);
        return builder.build();
    }

    public String getThermalByType(String type) {
        int zone = -1;
        if (type.equals("cpu")) {
            zone = this.cpuZone;
        } else if (type.equals("gpu")) {
            zone = this.gpuZone;
        } else if (type.equals("battery")) {
            zone = this.batteryZone;
        }
        if (zone != -1) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone" + zone + "/temp"));
                String value = reader.readLine();
                reader.close();
                if (value != null) {
                    return (Integer.parseInt(value.trim()) / 1000) + "°C";
                }
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i < 20; i++) {
            try {
                File file = new File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (file.exists()) {
                    BufferedReader typeReader = new BufferedReader(new FileReader(file));
                    String typeValue = typeReader.readLine();
                    typeReader.close();
                    if (typeValue != null && typeValue.toLowerCase().contains(type)) {
                        BufferedReader tempReader = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone" + i + "/temp"));
                        String tempValue = tempReader.readLine();
                        tempReader.close();
                        if (tempValue != null) {
                            return (Integer.parseInt(tempValue.trim()) / 1000) + "°C";
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // Plain zone reads found nothing: on ROMs where SELinux
        // blocks the app from the thermal nodes, SystemStats' shared
        // enumeration (plain files, then the root-shell dump) can
        // still answer.
        Integer viaStats = SystemStats.thermalTempByLabel(type);
        if (viaStats != null) {
            return viaStats + "°C";
        }
        return "n/a";
    }

    private String getBatteryCurrent() {
        // BatteryManager / direct sysfs first (via SystemStats), the
        // already-open root shell only as a last resort.
        Integer currentMa = SystemStats.readBatteryCurrentMa();
        if (currentMa != null) {
            return String.valueOf(currentMa);
        }
        return "n/a";
    }

    public String getBatteryInfo() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        int status = batteryIntent.getIntExtra("status", -1);
        int level = batteryIntent.getIntExtra("level", -1);
        batteryIntent.getIntExtra("scale", 100);
        int temperature = batteryIntent.getIntExtra("temperature", 0);
        String current = getBatteryCurrent();
        String state = "Unknown";
        try {
            int currentValue = Integer.parseInt(current);
            boolean discharging = false;
            if (status == 2 || status == 5) {
                if (level == 100 && currentValue <= 85) {
                    state = getString(R.string.batt_full);
                } else if (currentValue >= 2500) {
                    state = getString(R.string.batt_turbo);
                } else if (currentValue >= 1500) {
                    state = getString(R.string.batt_fast);
                } else if (currentValue >= 1000) {
                    state = getString(R.string.batt_charging);
                } else {
                    state = getString(R.string.batt_slow);
                }
            } else {
                state = getString(R.string.batt_discharging);
                discharging = true;
            }
            if (state.equals(getString(R.string.batt_full))) {
                return (temperature / 10) + "°C · " + state;
            }
            String line = (temperature / 10) + "°C · " + state + " " + (discharging ? "-" : "") + currentValue + " mA";
            // Power and voltage ride along on the same line, as in
            // the reference format ("… -1048 mA · 4.4 W · 4.17 V").
            Integer voltageMv = SystemStats.readBatteryVoltageMv();
            if (voltageMv != null && voltageMv > 0) {
                int signedMa = discharging ? -currentValue : currentValue;
                line += " · " + PureCalc.formatWattsMagnitude(PureCalc.powerWatts(signedMa, voltageMv))
                        + " · " + PureCalc.formatVolts(voltageMv);
            }
            return line;
        } catch (Exception e) {
            return (temperature / 10) + "°C · " + state + " " + current + " mA";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // Receivers for the app's own broadcast actions. On Android 13+
    // these must be registered with an explicit export flag.
    private void registerCustomReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    // Battery history for the graph on the floating monitor screen.
    // ACTION_BATTERY_CHANGED is sticky and only fires when the level
    // or charging state changes, so this costs nothing between events.
    private void registerBatteryHistoryReceiver() {
        this.batteryHistoryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BatteryHistory.record(getApplicationContext(), intent);
            }
        };
        Intent sticky = registerReceiver(this.batteryHistoryReceiver,
                new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        if (sticky != null) {
            BatteryHistory.record(getApplicationContext(), sticky);
        }
        BatteryHistory.prune(getApplicationContext());
    }

    private void registerToastReceiver() {
        this.toastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.appslayer.official.ACTION_TOAST_MODE".equals(intent.getAction())) {
                    String msg = intent.getStringExtra("msg");
                    String status = intent.getStringExtra("status");
                    currentStatusLine = msg + "\n" + status;
                    updateNotification();
                }
            }
        };
        registerCustomReceiver(this.toastReceiver, new IntentFilter("com.appslayer.official.ACTION_TOAST_MODE"));
    }

    private void registerShowToastReceiver() {
        this.showToastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String toast;
                if ("com.appslayer.official.SHOW_TOAST".equals(intent.getAction())
                        && getSharedPreferences(PrefsKeys.APP_PREFS, 0).getBoolean(PrefsKeys.TOAST, false)
                        && (toast = intent.getStringExtra("toast")) != null) {
                    Toast.makeText(getApplicationContext(), toast, Toast.LENGTH_SHORT).show();
                }
            }
        };
        registerCustomReceiver(this.showToastReceiver, new IntentFilter("com.appslayer.official.SHOW_TOAST"));
    }

    @Override
    public void onDestroy() {
        DiagLog.log(this, "NOTIF", "notification service destroyed");
        DiagLog.flushMirrorAsync(this);
        if (instance == this) {
            instance = null;
        }
        stopTimers();
        this.serviceStopped = true;
        stopForeground(true);
        NotificationManager notificationManager = (NotificationManager) getSystemService("notification");
        if (notificationManager != null) {
            notificationManager.cancel(NOTIF_ID);
        }
        super.onDestroy();
        if (this.toastReceiver != null) {
            unregisterReceiver(this.toastReceiver);
        }
        if (this.showToastReceiver != null) {
            unregisterReceiver(this.showToastReceiver);
        }
        if (this.screenReceiver != null) {
            unregisterReceiver(this.screenReceiver);
        }
        if (this.batteryHistoryReceiver != null) {
            unregisterReceiver(this.batteryHistoryReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
