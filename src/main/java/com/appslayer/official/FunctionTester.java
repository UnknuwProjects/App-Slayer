package com.appslayer.official;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// "Tes Fungsi" — the sandbox self-test the user asked for: run
// every capability the app depends on, for real, one at a time,
// and say per function whether it works on this phone and what it
// actually did. No more guessing from screenshots which link in a
// chain is dead.
//
// Every probe is side-effect-safe: the overlay probe adds a window
// it removes again ~1.5 s later, and the only file write is
// mode.txt getting its own current value written back. No user
// setting is ever changed. Each result is also written to the
// diagnostic log as a [TEST] line.
public final class FunctionTester {

    public static class Result {
        public final String name;
        public final boolean ok;
        public final String detail;

        Result(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    public interface Callback {
        void onResult(Result result);

        void onDone();
    }

    private FunctionTester() {
    }

    public static void runAll(final Context context, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                emit(callback, testRoot(app));
                emit(callback, testOverlay(app));
                emit(callback, testThermal(app));
                emit(callback, testFps(app));
                emit(callback, testProcess(app));
                emit(callback, testBattery(app));
                emit(callback, testModule(app));
                emit(callback, testMirror(app));
                emit(callback, testNotification(app));
                if (callback != null) {
                    callback.onDone();
                }
            }
        }).start();
    }

    private static void emit(Callback callback, Result result) {
        DiagLog.log("TEST", result.name + ": " + (result.ok ? "OK" : "FAILED")
                + (result.detail.isEmpty() ? "" : " — " + result.detail));
        if (callback != null) {
            callback.onResult(result);
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // (a) Root shell: a FRESH `su -c id`, never the cached shell,
    // so a stuck or never-granted persistent shell cannot mask the
    // real state. Each probe outcome gets its own wording — most
    // importantly "execdenied" (su could not even be started,
    // error=13) is NOT lumped in with "denied" (su ran and
    // refused): the old text claimed su had answered when it had
    // never run at all.
    private static Result testRoot(Context context) {
        String name = context.getString(R.string.test_name_root);
        try {
            SystemStats.RootProbe probe = SystemStats.probeRootFresh();
            if (probe.granted()) {
                return new Result(name, true, probe.detail);
            }
            int reasonRes;
            boolean appendDetail = true;
            if ("execdenied".equals(probe.status)) {
                reasonRes = R.string.test_root_exec_denied;
                // The string already says everything; the raw exec
                // error behind it is just noise ("Cannot run
                // program...") — it stays in the diagnostic log.
                appendDetail = false;
            } else if ("notfound".equals(probe.status)) {
                reasonRes = R.string.test_root_notfound;
            } else if ("timeout".equals(probe.status)) {
                reasonRes = R.string.test_root_timeout;
            } else {
                reasonRes = R.string.test_root_denied;
            }
            String detail = context.getString(reasonRes);
            if (appendDetail && !probe.detail.isEmpty()) {
                detail += " (" + probe.detail + ")";
            }
            return new Result(name, false, detail);
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (b) Overlay: the live window count, plus proof a window can
    // really be added — a temporary test window goes up and comes
    // back down ~1.5 s later.
    private static Result testOverlay(final Context context) {
        final String name = context.getString(R.string.test_name_overlay);
        try {
            final int count = FloatingOverlayService.getShownWindowCount();
            if (!Settings.canDrawOverlays(context)) {
                return new Result(name, false, context.getString(R.string.test_overlay_no_perm));
            }
            final boolean[] added = {false};
            final String[] error = {null};
            final CountDownLatch latch = new CountDownLatch(1);
            final Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                @Override
                public void run() {
                    final WindowManager wm =
                            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                    final TextView view = new TextView(context);
                    try {
                        view.setText("App Slayer");
                        view.setTextColor(Color.WHITE);
                        view.setBackgroundColor(Color.rgb(46, 125, 50));
                        view.setPadding(24, 12, 24, 12);
                        int type = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, -3);
                        params.gravity = Gravity.CENTER;
                        wm.addView(view, params);
                        added[0] = true;
                    } catch (Exception e) {
                        error[0] = describe(e);
                        latch.countDown();
                        return;
                    }
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                wm.removeView(view);
                            } catch (Exception ignored) {
                            }
                            latch.countDown();
                        }
                    }, 1500);
                }
            });
            latch.await(6, TimeUnit.SECONDS);
            if (error[0] != null) {
                return new Result(name, false, error[0]);
            }
            return new Result(name, added[0],
                    context.getString(R.string.test_overlay_detail, count));
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (c) Thermal: the two enumeration paths counted separately,
    // so "SELinux blocks the files but the shell works" (or the
    // reverse) is visible instead of one merged number.
    private static Result testThermal(Context context) {
        String name = context.getString(R.string.test_name_thermal);
        try {
            int plain = SystemStats.readPlainThermalSensors().size();
            int shell = SystemStats.getShellThermalReadings().size();
            return new Result(name, plain + shell > 0,
                    context.getString(R.string.test_thermal_detail, plain, shell));
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (d) FPS sampler: watch for a numeric reading for up to ~3 s,
    // then try one direct sample. The detail names the path that
    // answered (SurfaceFlinger / gfxinfo / none).
    private static Result testFps(Context context) {
        String name = context.getString(R.string.test_name_fps);
        try {
            Double value = null;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                value = SystemStats.getFpsValue();
                if (value != null) {
                    break;
                }
                Thread.sleep(250);
            }
            if (value == null) {
                // The sampler thread may not be running (overlay
                // service never started): take one direct sample.
                value = PureCalc.parseFpsValue(SystemStats.getSystemFpsFromSurfaceFlinger());
            }
            if (value == null) {
                return new Result(name, false, context.getString(R.string.test_fps_none));
            }
            return new Result(name, true, context.getString(R.string.test_fps_detail,
                    String.format(Locale.US, "%.1f", value), SystemStats.getLastFpsSource()));
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (e) Process monitor: one real `top` snapshot, row count.
    private static Result testProcess(Context context) {
        String name = context.getString(R.string.test_name_process);
        try {
            String out = SystemStats.runShellCommand("top -b -n 1");
            int rows = PureCalc.parseTopProcesses(out, 5).size();
            if (rows == 0) {
                String out2 = SystemStats.runShellCommand("top -n 1");
                rows = PureCalc.parseTopProcesses(out2, 5).size();
                if (out == null) {
                    out = out2;
                }
            }
            if (rows > 0) {
                return new Result(name, true,
                        context.getString(R.string.test_process_detail, rows));
            }
            String detail = context.getString(R.string.test_process_empty);
            if (out == null) {
                detail += " (" + context.getString(R.string.test_shell_unavailable) + ")";
            }
            return new Result(name, false, detail);
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (f) Battery: current, charge counter (+ its source), and the
    // design/full capacities (+ the source that answered each),
    // through the same chain the Battery Info dialog uses.
    private static Result testBattery(Context context) {
        String name = context.getString(R.string.test_name_battery);
        try {
            Integer current = SystemStats.readBatteryCurrentMa();
            Integer counter = SystemStats.readChargeCounterMah();
            String counterSource = SystemStats.getLastChargeCounterSource();
            int design = readCapacityMah("charge_full_design", "energy_full_design",
                    "POWER_SUPPLY_CHARGE_FULL_DESIGN=");
            String designSource = lastCapacitySource;
            int full = readCapacityMah("charge_full", "energy_full",
                    "POWER_SUPPLY_CHARGE_FULL=");
            String fullSource = lastCapacitySource;
            String detail = context.getString(R.string.test_battery_detail,
                    current != null ? current + " mA" : "N/A",
                    counter != null ? counter + " mAh (" + counterSource + ")" : "N/A",
                    design > 0 ? design + " mAh (" + designSource + ")" : "N/A",
                    full > 0 ? full + " mAh (" + fullSource + ")" : "N/A");
            return new Result(name, current != null || counter != null, detail);
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // The capacity chain, mirroring FloatingMonitorActivity's
    // dialog chain: every power_supply directory, plain file →
    // uevent → energy counters → root shell, values sanity-checked
    // by PureCalc. lastCapacitySource names the winning pass.
    private static String lastCapacitySource = "none";

    private static java.util.List<String> powerSupplyDirs() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        dirs.add("/sys/class/power_supply/battery/");
        File[] subs = new File("/sys/class/power_supply/").listFiles();
        if (subs != null) {
            java.util.Arrays.sort(subs, (a, b) -> a.getName().compareTo(b.getName()));
            for (File sub : subs) {
                if (sub.isDirectory() && !"battery".equals(sub.getName())) {
                    dirs.add(sub.getAbsolutePath() + "/");
                }
            }
        }
        return dirs;
    }

    private static int readCapacityMah(String chargeFile, String energyFile, String ueventKey) {
        java.util.List<String> dirs = powerSupplyDirs();
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readPlainFile(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "plain file";
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(readUeventValue(dir, ueventKey));
            if (mah != null) {
                lastCapacitySource = "uevent";
                return mah;
            }
        }
        for (String dir : dirs) {
            Integer microWh = parseIntOrNull(readPlainFile(dir + energyFile));
            Integer microV = parseIntOrNull(readPlainFile(dir + "voltage_now"));
            if (microWh != null && microWh > 0 && microV != null && microV > 0) {
                int mah = (int) (microWh * 1000L / microV);
                if (mah >= 500 && mah <= 30000) {
                    lastCapacitySource = "energy counters";
                    return mah;
                }
            }
        }
        for (String dir : dirs) {
            Integer mah = PureCalc.capacityFromRaw(SystemStats.readFileWithRoot(dir + chargeFile));
            if (mah != null) {
                lastCapacitySource = "root shell";
                return mah;
            }
        }
        lastCapacitySource = "none";
        return -1;
    }

    private static String readPlainFile(String path) {
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

    private static String readUeventValue(String dir, String key) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(dir + "uevent"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.split("=");
                    return parts.length >= 2 ? parts[1] : null;
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

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // (g) Module contract: mode.txt and the three list files must
    // be readable, and mode.txt must survive a write of its own
    // current value (written back identically, then read back and
    // compared — no state change). The write-back only runs when
    // the current value is a real mode number (1/2/3); anything
    // else is reported, not rewritten.
    private static Result testModule(Context context) {
        String name = context.getString(R.string.test_name_module);
        try {
            String modeRaw = SystemStats.readFileWithRoot(AppPaths.MODE_FILE);
            String modeValue = modeRaw != null ? modeRaw.trim() : null;
            String[] listNames = {AppPaths.LIST_WHITELIST, AppPaths.LIST_EXTREME,
                    AppPaths.LIST_GAMELIST};
            int readable = 0;
            for (String listName : listNames) {
                if (SystemStats.readFileWithRoot(AppPaths.listFile(listName)) != null) {
                    readable++;
                }
            }
            boolean writeOk = true;
            String writeState;
            if (modeValue != null && modeValue.matches("[123]")) {
                SystemStats.runShellCommand("echo '" + modeValue + "' > " + AppPaths.MODE_FILE);
                String back = SystemStats.readFileWithRoot(AppPaths.MODE_FILE);
                writeOk = back != null && modeValue.equals(back.trim());
                writeState = context.getString(
                        writeOk ? R.string.test_write_ok : R.string.test_write_fail);
            } else {
                writeState = context.getString(R.string.test_write_skipped);
            }
            boolean ok = modeValue != null && readable == listNames.length && writeOk;
            String detail = context.getString(R.string.test_module_detail,
                    modeValue != null ? modeValue : "N/A", readable, writeState);
            if (modeRaw == null) {
                detail += " (" + context.getString(R.string.test_shell_unavailable) + ")";
            }
            return new Result(name, ok, detail);
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (h) Log mirror: one real flush attempt to shared storage.
    private static Result testMirror(Context context) {
        String name = context.getString(R.string.test_name_mirror);
        try {
            boolean ok = DiagLog.flushMirrorSync(context);
            return new Result(name, ok, ok
                    ? context.getString(R.string.test_mirror_detail, DiagLog.MIRROR_PATH)
                    : context.getString(R.string.test_mirror_fail));
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }

    // (i) Notification: permission state and service state.
    private static Result testNotification(Context context) {
        String name = context.getString(R.string.test_name_notif);
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean enabled = manager != null && manager.areNotificationsEnabled();
            boolean granted = Build.VERSION.SDK_INT < 33
                    || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
            boolean permission = enabled && granted;
            boolean running = MyService.isRunning();
            String detail = context.getString(R.string.test_notif_detail,
                    context.getString(permission
                            ? R.string.state_granted : R.string.state_not_granted),
                    context.getString(running
                            ? R.string.state_running : R.string.state_not_running));
            return new Result(name, permission, detail);
        } catch (Exception e) {
            return new Result(name, false, describe(e));
        }
    }
}
