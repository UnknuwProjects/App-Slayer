package com.appslayer.official;

// SharedPreferences names and keys used across the app.
public final class PrefsKeys {

    // The app's own preference file (notification/mode/toast/language).
    public static final String APP_PREFS = "AppSlayerPrefs";
    public static final String NOTIFICATIONS = "notifikasi";
    public static final String MODE = "mode";
    public static final String TOAST = "toast";
    public static final String LANGUAGE = "language";
    public static final String LAST_STATUS_LINE = "lastStatusLine";

    // Default SharedPreferences: floating monitor switches and overlay
    // appearance (also read by BootReceiver).
    public static final String AUTO_START = "auto_start";
    public static final String MONITOR_CPU = "monitor_cpu";
    public static final String MONITOR_RAM = "monitor_ram";
    public static final String MONITOR_TEMP = "monitor_temp";
    public static final String MONITOR_BATTERY = "monitor_battery";
    public static final String MONITOR_CURRENT = "monitor_current";
    public static final String MONITOR_SIGNAL = "monitor_signal";
    public static final String MONITOR_FPS = "monitor_fps";
    public static final String MONITOR_PROCESS = "monitor_process";
    public static final String OVERLAY_ALPHA = "overlay_alpha";
    public static final String OVERLAY_TEXT_SIZE = "overlay_text_size";
    public static final String CLICK_THROUGH = "click_through";
    public static final String DARK_TEXT = "dark_text";
    public static final String TEXT_SHADOW = "text_shadow";
    public static final String SNAP_TO_BAR = "snap_to_bar";
    public static final String HIDE_IN_FULLSCREEN = "hide_in_fullscreen";
    public static final String DISABLE_IN_LANDSCAPE = "disable_in_landscape";
    // Overlay presentation: one window per stat ("classic") or a
    // single combined bar ("mini").
    public static final String MONITOR_STYLE = "monitor_style";
    public static final String MONITOR_STYLE_CLASSIC = "classic";
    public static final String MONITOR_STYLE_MINI = "mini";

    private PrefsKeys() {
    }
}
