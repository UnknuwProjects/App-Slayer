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
import android.os.Build;
import android.os.IBinder;

// Battery history logger. Up to 6.3.6 the 24-hour graph only
// filled while MyService (the notification-stats service) ran,
// because that service held the battery-changed receiver — users
// who never enabled the notification feature got an empty graph
// forever. This service exists only to hold that receiver, so the
// history now accumulates whenever the app is in use, independent
// of the notification toggle. It is started from the main screen,
// the Floating Monitor screen and the boot receiver.
//
// BatteryHistory.record is broadcast-driven (the system fires
// ACTION_BATTERY_CHANGED when the level or charging state moves)
// and dedupes consecutive identical states, so this service costs
// no polling loop — it only has to stay alive. That is why it is a
// foreground service: a plain background service is killed too
// eagerly by modern Android/MIUI to collect anything. Its
// notification is deliberately minimal: its own silent,
// low-importance channel, no sound, no stats content — it is not
// the notification feature's stats notification.
public class BatteryLoggingService extends Service {

    public static final String CHANNEL_ID = "AppSlayerBatteryLogChannel";
    public static final int NOTIF_ID = 4;

    private static volatile BatteryLoggingService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    // Starts the logger if it is not already running. Safe to call
    // from any screen's resume/create and from the boot receiver.
    public static void start(Context context) {
        if (isRunning()) {
            return;
        }
        try {
            Intent intent = new Intent(context, BatteryLoggingService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            DiagLog.log(context, "BATT", "battery logging service start FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BroadcastReceiver batteryReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            DiagLog.log("BATT", "battery logging service foreground (id " + NOTIF_ID + ")");
        } catch (Exception e) {
            DiagLog.log("BATT", "battery logging startForeground FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopSelf();
            return;
        }
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BatteryHistory.record(getApplicationContext(), intent);
            }
        };
        registerReceiver(batteryReceiver,
                new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        // Record the current state right away, like MyService does,
        // so opening the app after a long gap still leaves a point.
        Intent sticky = registerReceiver(null,
                new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        BatteryHistory.record(getApplicationContext(), sticky);
        BatteryHistory.prune(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
            batteryReceiver = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.battery_logging_channel),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService("notification");
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MIN);
        }
        return builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.battery_logging_notif))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }
}
