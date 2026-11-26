package com.appslayer.official;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PrefsKeys.APP_PREFS, 0);
            // Default true, matching MainActivity and MyService: the
            // notification is on unless the user turned it off (the
            // old false here meant it never came back after a reboot
            // unless the toggle had been touched at least once).
            boolean notifOn = sharedPreferences.getBoolean(PrefsKeys.NOTIFICATIONS, true);
            // The monitor switches live in the default preferences
            // (that is where FloatingMonitorActivity writes them);
            // reading them from AppSlayerPrefs always returned false
            // and the overlay never came back after a reboot.
            SharedPreferences monitorPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean autoStart = monitorPrefs.getBoolean(PrefsKeys.AUTO_START, false);
            boolean anyMonitor = monitorPrefs.getBoolean(PrefsKeys.MONITOR_CPU, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_TEMP, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_BATTERY, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_RAM, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_CURRENT, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_SIGNAL, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_FPS, false)
                    || monitorPrefs.getBoolean(PrefsKeys.MONITOR_PROCESS, false);
            if (notifOn) {
                Intent serviceIntent = new Intent(context, MyService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
            // Battery history logging is independent of the
            // notification feature (6.3.7): start its service on
            // every boot so the 24-hour graph keeps collecting.
            BatteryLoggingService.start(context);
            if (autoStart && anyMonitor && Settings.canDrawOverlays(context)) {
                Intent overlayIntent = new Intent(context, FloatingOverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(overlayIntent);
                } else {
                    context.startService(overlayIntent);
                }
            }
        }
    }
}
