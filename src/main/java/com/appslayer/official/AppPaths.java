package com.appslayer.official;

// Paths of the Magisk module this app controls. Everything that
// touches the module goes through here instead of repeating the
// literals all over the code.
public final class AppPaths {

    public static final String MODULE_DIR = "/data/adb/modules/appslayer";
    public static final String MODE_FILE = MODULE_DIR + "/config_mode/mode.txt";
    public static final String MANUAL_SCRIPT = MODULE_DIR + "/manual.sh";
    public static final String LIST_DIR = MODULE_DIR + "/list";

    public static final String LIST_WHITELIST = "whitelist.txt";
    public static final String LIST_EXTREME = "Extreme.txt";
    public static final String LIST_GAMELIST = "gamelist.txt";
    public static final String LIST_KILL = "listkill.txt";

    private AppPaths() {
    }

    public static String listFile(String name) {
        return LIST_DIR + "/" + name;
    }
}
