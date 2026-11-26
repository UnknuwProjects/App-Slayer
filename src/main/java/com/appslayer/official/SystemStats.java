package com.appslayer.official;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;

public class SystemStats {

    private static volatile String lastFpsString = "FPS: ...";
    private static boolean fpsMonitorRunning = false;

    // CPU min/max frequencies never change at runtime, so they are read
    // once and cached. Only scaling_cur_freq is read on every tick.
    private static int[] cachedMinFreq;
    private static int[] cachedMaxFreq;

    // Latest signal strength, fed by a PhoneStateListener in
    // FloatingOverlayService. Read as a fallback-free cache so the
    // overlay does not have to poll telephony on every tick.
    public static volatile int cachedSignalDbm = -200;
    public static volatile long cachedSignalTime = 0;

    public static void updateCachedSignalDbm(int dbm) {
        cachedSignalDbm = dbm;
        cachedSignalTime = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // One root shell for the whole process lifetime. The old code ran
    // "su -c ..." for every FPS and battery reading, which meant a new
    // root shell (plus a full dumpsys) about once a second. Now the
    // shell is opened once and commands are written to it; if it dies
    // it is restarted, and if it cannot be opened we back off instead
    // of hammering exec.
    // ------------------------------------------------------------------
    private static class PersistentShell {
        private static Process process;
        private static DataOutputStream stdin;
        private static BufferedReader stdout;
        private static long lastOpenFailure = 0;
        private static int markerCounter = 0;
        // True only once the shell has answered the open handshake.
        // A `su` process can be alive but useless — stuck at a root
        // prompt nobody approved, or granted to a session that never
        // replies — so "process exists" is not proof of root. An
        // unverified shell is treated as dead and reopened, and
        // reset() lets the app retry on demand: one early failure
        // must not latch for the whole process lifetime (on the
        // user's phone it did, and every shell-backed feature —
        // thermal, FPS, processes, capacities, the log mirror —
        // stayed dead until the app was restarted).
        private static boolean verified = false;
        private static final long OPEN_RETRY_BACKOFF_MS = 10000;
        private static final long HANDSHAKE_TIMEOUT_MS = 4000;

        static synchronized boolean ensureOpen() {
            if (process != null) {
                try {
                    process.exitValue();
                    closeLocked(); // it died, fall through and reopen
                } catch (IllegalThreadStateException e) {
                    if (verified) {
                        return true; // alive and proven to answer
                    }
                    closeLocked(); // alive but never answered: zombie
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastOpenFailure < OPEN_RETRY_BACKOFF_MS) {
                return false;
            }
            try {
                process = Runtime.getRuntime().exec("su");
                stdin = new DataOutputStream(process.getOutputStream());
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                verified = runHandshake();
                if (!verified) {
                    lastOpenFailure = System.currentTimeMillis();
                    closeLocked();
                    return false;
                }
                return true;
            } catch (Exception e) {
                lastOpenFailure = now;
                closeLocked();
                return false;
            }
        }

        // Writes an echo marker and waits (bounded) for it to come
        // back. The read runs on a helper thread because readLine()
        // has no timeout: a shell stuck at an unapproved root prompt
        // would otherwise block the caller forever.
        private static boolean runHandshake() {
            final String marker = "__APPSLAYER_READY_" + (++markerCounter) + "__";
            try {
                stdin.writeBytes("echo " + marker + "\n");
                stdin.flush();
            } catch (Exception e) {
                return false;
            }
            final boolean[] answered = {false};
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = stdout.readLine()) != null) {
                            if (line.trim().equals(marker)) {
                                answered[0] = true;
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            try {
                reader.join(HANDSHAKE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return false;
            }
            return answered[0];
        }

        static synchronized boolean isVerified() {
            if (process == null || !verified) {
                return false;
            }
            try {
                process.exitValue();
                return false; // it died since
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }

        // Drops the current shell (if any) and clears the failure
        // backoff, so the next exec opens a fresh one. Called after
        // a successful fresh root grant and by the retry kicks.
        static synchronized void reset() {
            closeLocked();
            lastOpenFailure = 0;
        }

        private static void closeLocked() {
            verified = false;
            try {
                if (stdin != null) stdin.close();
            } catch (Exception ignored) {
            }
            try {
                if (stdout != null) stdout.close();
            } catch (Exception ignored) {
            }
            if (process != null) {
                process.destroy();
            }
            process = null;
            stdin = null;
            stdout = null;
        }

        // Runs one command and returns everything it printed, or null
        // when the shell is unavailable.
        static synchronized String exec(String command) {
            if (!ensureOpen()) {
                return null;
            }
            String marker = "__APPSLAYER_DONE_" + (++markerCounter) + "__";
            try {
                stdin.writeBytes(command + "\n");
                stdin.writeBytes("echo " + marker + "\n");
                stdin.flush();
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (line.trim().equals(marker)) {
                        return out.toString();
                    }
                    out.append(line).append("\n");
                }
                closeLocked(); // shell ended mid-answer
                return null;
            } catch (Exception e) {
                closeLocked();
                return null;
            }
        }
    }

    public static String getCpuFrequency() {
        StringBuilder sb = new StringBuilder();
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            if (cachedMinFreq == null || cachedMinFreq.length != cores) {
                cachedMinFreq = new int[cores];
                cachedMaxFreq = new int[cores];
                for (int i = 0; i < cores; i++) {
                    String max = readFile("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                    String min = readFile("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_min_freq");
                    cachedMaxFreq[i] = max != null ? Integer.parseInt(max.trim()) : -1;
                    cachedMinFreq[i] = min != null ? Integer.parseInt(min.trim()) : -1;
                }
            }
            for (int i = 0; i < cores; i++) {
                String cur = readFile("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                if (cur != null && cachedMaxFreq[i] > 0 && cachedMinFreq[i] >= 0) {
                    int curFreq = Integer.parseInt(cur.trim());
                    int maxFreq = cachedMaxFreq[i];
                    int minFreq = cachedMinFreq[i];
                    sb.append(curFreq / 1000).append(" MHz · ")
                            .append(Math.max(0, Math.min(100, (int) (((curFreq - minFreq) * 100.0d) / (maxFreq - minFreq)))))
                            .append("%\n");
                }
            }
            String gpuInfo = getUniversalGpuInfo();
            if (!gpuInfo.isEmpty()) {
                sb.append(gpuInfo);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "n/a";
        }
    }

    // Current/max/min GPU frequency in Hz, or -1 where a value could
    // not be read. Shared by the classic GPU text and the mini bar.
    private static int[] readGpuFreqs() {
        int cur = -1;
        int max = -1;
        int min = 100000000;
        String[] curPaths = {"/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/devfreq/11800000.mali/cur_freq", "/sys/class/devfreq/gpu/cur_freq"};
        String[] maxPaths = {"/sys/class/kgsl/kgsl-3d0/max_gpuclk", "/sys/class/devfreq/11800000.mali/max_freq", "/sys/class/devfreq/gpu/max_freq", "/sys/class/kgsl/kgsl-3d0/max_clock_mhz"};
        String available = readFile("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies");
        for (String path : curPaths) {
            String value = readFile(path);
            if (value != null) {
                try {
                    cur = Integer.parseInt(value.trim());
                    break;
                } catch (Exception ignored) {
                }
            }
        }
        for (String path : maxPaths) {
            String value = readFile(path);
            if (value != null) {
                try {
                    if (path.endsWith("max_clock_mhz")) {
                        max = Integer.parseInt(value.trim()) * 1000000;
                    } else {
                        max = Integer.parseInt(value.trim());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (available != null && available.contains(" ")) {
            try {
                min = Integer.parseInt(available.trim().split("\\s+")[0]);
            } catch (Exception ignored) {
            }
        } else {
            min = cur;
        }
        return new int[]{cur, max, min};
    }

    public static String getUniversalGpuInfo() {
        int[] freqs = readGpuFreqs();
        int cur = freqs[0];
        int max = freqs[1];
        int min = freqs[2];
        if (cur > 0 && max > 0) {
            return (cur / 1000000) + " MHz · " + Math.max(0, Math.min(100, (int) (((cur - min) * 100.0d) / (max - min)))) + "% (GPU)";
        } else if (cur > 0) {
            return (cur / 1000000) + " MHz (GPU)";
        }
        return "";
    }

    public static String getCpuGpuTemp() {
        String cpu = readThermalByLabel("cpu");
        String gpu = readThermalByLabel("gpu");
        if (!cpu.isEmpty() && !gpu.isEmpty()) {
            return cpu + " · " + gpu;
        }
        if (!cpu.isEmpty()) {
            return cpu;
        }
        if (!gpu.isEmpty()) {
            return gpu;
        }
        return "n/a";
    }

    public static String getBatteryInfo() {
        Intent batteryIntent = AppContext.get().registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        if (batteryIntent == null) {
            return "n/a";
        }
        int level = batteryIntent.getIntExtra("level", -1);
        int temp = batteryIntent.getIntExtra("temperature", -1);
        return (level == -1 || temp == -1) ? "n/a" : level + "% · " + (temp / 10) + "°C";
    }

    public static String getRamUsage() {
        long total = 0;
        long available = 0;
        long swapTotal = 0;
        long swapFree = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
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
            }
            reader.close();
            long totalAll = total + swapTotal;
            long freeAll = available + swapFree;
            long usedAll = totalAll - freeAll;
            return String.format(Locale.US, "%.2f GB · %.2f GB (%d%%)",
                    ((float) freeAll) / 1024.0f / 1024.0f,
                    ((float) totalAll) / 1024.0f / 1024.0f,
                    (int) (((float) usedAll) * 100.0f / ((float) totalAll)));
        } catch (Exception e) {
            return "RAM: n/a";
        }
    }

    public static String getSignalVisual() {
        Context context = AppContext.get();
        int dbm = getSignalDbm();
        if (dbm < -150 || dbm > -30) {
            return context != null ? context.getString(R.string.signal_na) : "Sinyal: n/a";
        }
        int percent = Math.max(0, Math.min(100, (int) (((dbm + 150) * 100.0d) / 60)));
        return context != null
                ? context.getString(R.string.signal_format, dbm, percent)
                : "Sinyal: " + dbm + " dBm (" + percent + "%)";
    }

    // Battery current in mA. BatteryManager first, then the sysfs file
    // directly, and the shared root shell only as a last resort — the
    // old code started a root shell for this on every reading.
    public static Integer readBatteryCurrentMa() {
        Context context = AppContext.get();
        if (context != null) {
            try {
                BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (batteryManager != null) {
                    int microAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (microAmps != Integer.MIN_VALUE) {
                        return Math.abs(microAmps / 1000);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String path : new String[]{"/sys/class/power_supply/battery/current_now", "/sys/class/power_supply/bms/current_now"}) {
            String value = readFile(path);
            if (value != null && value.trim().length() > 0) {
                try {
                    return Math.abs(Integer.parseInt(value.trim()) / 1000);
                } catch (Exception ignored) {
                }
            }
        }
        String out = PersistentShell.exec("cat /sys/class/power_supply/battery/current_now");
        if (out != null && out.trim().length() > 0) {
            try {
                return Math.abs(Integer.parseInt(out.trim().split("\\s+")[0]) / 1000);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // Runs a command on the shared root shell and returns its
    // output, or null when the shell is unavailable. Callers are the
    // FPS recorder (foreground app) and the process monitor (top) —
    // both background work, never per-frame.
    public static String runShellCommand(String command) {
        return PersistentShell.exec(command);
    }

    // Whether the persistent root shell is up and has proven it
    // answers commands. Cheap: spawns nothing.
    public static boolean isShellVerified() {
        return PersistentShell.isVerified();
    }

    // Drops the persistent shell so the next command reopens it.
    // Used right after a fresh root grant.
    public static void resetShell() {
        PersistentShell.reset();
    }

    // Outcome of a fresh `su -c id` probe. Status is one of:
    // "uid0" (root granted), "denied" (su answered but refused, or
    // exited non-zero), "execdenied" (the su process could not even
    // be started — exec error=13, the app is not on the root
    // manager's allow list), "notfound" (no su binary), "timeout"
    // (su never answered — usually a grant prompt nobody approved),
    // "error" (anything else). Detail carries the first output
    // line, for the log and the UI.
    public static class RootProbe {
        public final String status;
        public final String detail;

        RootProbe(String status, String detail) {
            this.status = status;
            this.detail = detail;
        }

        public boolean granted() {
            return "uid0".equals(status);
        }
    }

    // Spawns a brand-new `su -c id` — never the cached persistent
    // shell — so it re-triggers the Magisk/KernelSU grant prompt
    // when the app has not been granted yet. Blocking: call it on a
    // background thread. Bounded at ~20 s so a prompt the user
    // ignores cannot hang the caller.
    public static RootProbe probeRootFresh() {
        Process probe;
        try {
            probe = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
        } catch (Exception e) {
            String msg = e.getMessage();
            boolean missing = msg != null
                    && (msg.contains("No such file") || msg.contains("error=2"));
            if (missing) {
                return new RootProbe("notfound", firstLine(msg));
            }
            // error=13 starting the process means su exists but the
            // kernel/root manager will not let this app execute it —
            // the app simply is not granted root. That is a different
            // story from su running and refusing, so it gets its own
            // status and its own wording in the UI.
            boolean execDenied = msg != null
                    && (msg.contains("error=13") || msg.contains("Permission denied"));
            return new RootProbe(execDenied ? "execdenied" : "error", firstLine(msg));
        }
        final Process process = probe;
        final StringBuffer outBuf = new StringBuffer();
        final StringBuffer errBuf = new StringBuffer();
        Thread outReader = pump(process.getInputStream(), outBuf);
        Thread errReader = pump(process.getErrorStream(), errBuf);
        final boolean[] exited = {false};
        final int[] exitCode = {-1};
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    exitCode[0] = process.waitFor();
                    exited[0] = true;
                } catch (InterruptedException ignored) {
                }
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        try {
            waiter.join(20000);
        } catch (InterruptedException ignored) {
        }
        if (!exited[0]) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            return new RootProbe("timeout", firstLine(outBuf.toString() + errBuf.toString()));
        }
        try {
            outReader.join(500);
        } catch (Exception ignored) {
        }
        try {
            errReader.join(500);
        } catch (Exception ignored) {
        }
        String combined = outBuf.toString() + "\n" + errBuf.toString();
        if (combined.contains("uid=0")) {
            return new RootProbe("uid0", firstLine(outBuf.toString()));
        }
        String detail = firstLine(errBuf.length() > 0 ? errBuf.toString() : combined);
        if (exitCode[0] > 0) {
            detail = detail.isEmpty() ? "exit " + exitCode[0] : detail + " (exit " + exitCode[0] + ")";
        }
        return new RootProbe("denied", detail);
    }

    private static Thread pump(final java.io.InputStream in, final StringBuffer sink) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (sink) {
                            sink.append(line).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
            }
        }
        return "";
    }

    private static volatile long lastKickMs = 0;
    private static volatile Boolean lastRootState = null;

    // Retry kick for the persistent shell, called when a service
    // starts and when the main screen comes to the foreground: if
    // the shell never came up, drop the backoff and try once more
    // instead of staying dead for the process lifetime. Throttled
    // to one attempt per 15 s; runs on its own thread.
    public static void kickRootShell() {
        long now = System.currentTimeMillis();
        if (now - lastKickMs < 15000) {
            return;
        }
        lastKickMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (isShellVerified()) {
                    noteRootState(true);
                    return;
                }
                PersistentShell.reset();
                String out = PersistentShell.exec("echo APPSLAYER_KICK");
                noteRootState(out != null && out.contains("APPSLAYER_KICK"));
            }
        }).start();
    }

    private static void noteRootState(boolean available) {
        if (lastRootState == null || lastRootState != available) {
            lastRootState = available;
            DiagLog.log("SYS", available
                    ? "root shell is available"
                    : "root shell still unavailable after retry");
        }
    }

    // Reads a file through the shared root shell. Used as the last
    // resort for sysfs nodes a plain read cannot open (SELinux).
    // Returns the trimmed content, or null when unavailable.
    public static String readFileWithRoot(String path) {
        String out = PersistentShell.exec("cat " + path);
        if (out == null) {
            return null;
        }
        String trimmed = out.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // Which source last answered the charge counter ("BatteryManager"
    // or "dumpsys battery"), or null when none has yet. Read by the
    // diagnosis snapshot; changes are written to the log.
    private static volatile String lastChargeCounterSource = null;

    public static String getLastChargeCounterSource() {
        return lastChargeCounterSource;
    }

    private static void noteChargeCounterSource(String source) {
        if (!source.equals(lastChargeCounterSource)) {
            lastChargeCounterSource = source;
            DiagLog.log("BATT", "charge counter source: " + source);
        }
    }

    // Remaining charge in mAh from BatteryManager's charge counter
    // (microamp-hours). When the ROM does not expose the property,
    // "dumpsys battery" through the root shell prints the same
    // counter on many devices. Null when neither source answers.
    public static Integer readChargeCounterMah() {
        Context context = AppContext.get();
        if (context != null) {
            try {
                BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (batteryManager != null) {
                    long microAh = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (microAh != Long.MIN_VALUE && microAh > 0) {
                        noteChargeCounterSource("BatteryManager");
                        return (int) (microAh / 1000);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String dump = PersistentShell.exec("dumpsys battery");
        if (dump != null) {
            for (String line : dump.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Charge counter:")) {
                    try {
                        long microAh = Long.parseLong(trimmed.substring("Charge counter:".length()).trim());
                        if (microAh > 0) {
                            noteChargeCounterSource("dumpsys battery");
                            return (int) (microAh / 1000);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        noteChargeCounterSource("none");
        return null;
    }

    public static String getBatteryCurrent() {
        try {
            Integer currentMa = readBatteryCurrentMa();
            Intent batteryIntent = AppContext.get().registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
            if (currentMa != null && batteryIntent != null) {
                int abs = currentMa;
                int level = batteryIntent.getIntExtra("level", -1);
                boolean discharging = false;
                String status;
                Context context = AppContext.get();
                if (batteryIntent.getIntExtra("plugged", 0) != 0) {
                    if (level == 100 && abs <= 85) {
                        status = context.getString(R.string.batt_full);
                    } else if (abs >= 2500) {
                        status = context.getString(R.string.batt_turbo);
                    } else if (abs >= 1500) {
                        status = context.getString(R.string.batt_fast);
                    } else if (abs >= 1000) {
                        status = context.getString(R.string.batt_charging);
                    } else {
                        status = context.getString(R.string.batt_slow);
                    }
                } else {
                    status = context.getString(R.string.batt_discharging);
                    discharging = true;
                }
                return (discharging ? "-" : "") + abs + " mA" + " · " + status;
            }
        } catch (Exception ignored) {
        }
        return "n/a";
    }

    public static int getSignalDbm() {
        // Fresh listener cache wins; no telephony work needed.
        if (System.currentTimeMillis() - cachedSignalTime < 15000 && cachedSignalDbm != -200) {
            return cachedSignalDbm;
        }
        Context context;
        TelephonyManager telephonyManager = null;
        try {
            context = AppContext.get();
            telephonyManager = (TelephonyManager) context.getSystemService("phone");
        } catch (Exception e) {
            Log.e("SystemStats", "Signal error: " + e.getMessage());
            context = null;
        }
        if (telephonyManager == null || context == null) {
            return -200;
        }
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
            return -200;
        }
        List<CellInfo> cellInfos = null;
        try {
            cellInfos = telephonyManager.getAllCellInfo();
        } catch (SecurityException e) {
            return -200;
        } catch (Exception e) {
            return -200;
        }
        if (cellInfos != null && !cellInfos.isEmpty()) {
            for (CellInfo cellInfo : cellInfos) {
                if (cellInfo.isRegistered()) {
                    if (cellInfo instanceof CellInfoLte) {
                        return ((CellInfoLte) cellInfo).getCellSignalStrength().getDbm();
                    }
                    if (cellInfo instanceof CellInfoWcdma) {
                        return ((CellInfoWcdma) cellInfo).getCellSignalStrength().getDbm();
                    }
                    if (cellInfo instanceof CellInfoGsm) {
                        return ((CellInfoGsm) cellInfo).getCellSignalStrength().getDbm();
                    }
                    if (Build.VERSION.SDK_INT >= 29 && cellInfo instanceof CellInfoNr) {
                        return ((CellInfoNr) cellInfo).getCellSignalStrength().getDbm();
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            SignalStrength signalStrength = null;
            try {
                signalStrength = telephonyManager.getSignalStrength();
            } catch (SecurityException e) {
                return -200;
            } catch (Exception e) {
                return -200;
            }
            if (signalStrength != null) {
                for (CellSignalStrength cellSignalStrength : signalStrength.getCellSignalStrengths()) {
                    int dbm = cellSignalStrength.getDbm();
                    if (dbm >= -150 && dbm <= -30) {
                        return dbm;
                    }
                }
            }
        }
        return -200;
    }

    private static String readFile(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readThermalByLabel(String label) {
        File thermalDir = new File("/sys/class/thermal/");
        File[] files;
        if (thermalDir.exists() && (files = thermalDir.listFiles()) != null) {
            for (File zone : files) {
                if (zone.getName().startsWith("thermal_zone")) {
                    File typeFile = new File(zone, "type");
                    File tempFile = new File(zone, "temp");
                    if (typeFile.exists() && tempFile.exists()) {
                        try {
                            String type = readFile(typeFile.getAbsolutePath());
                            String temp = readFile(tempFile.getAbsolutePath());
                            if (type != null && type.toLowerCase().contains(label) && temp != null) {
                                int value = Integer.parseInt(temp.trim());
                                if (value > 1000) {
                                    value /= 1000;
                                }
                                if (value > 0 && value < 150) {
                                    return value + "°C";
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        // Plain reads came back empty — on the user's ROM SELinux
        // blocks the app from the thermal nodes entirely, while his
        // 5.2.0 temperatures always arrived through root. Fall back
        // to the batched shell dump.
        for (PureCalc.ThermalReading reading : getShellThermalReadings()) {
            if (reading.name.toLowerCase().contains(label)
                    && reading.tempC > 0 && reading.tempC < 150) {
                return reading.tempC + "°C";
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Thermal enumeration through the root shell. One batched command
    // dumps every zone's index, type and temp; the result is parsed
    // by PureCalc and cached for a few seconds so the sensor list,
    // the overlay temp and the recorder all share one shell call
    // instead of one per reader.
    // ------------------------------------------------------------------
    private static final String THERMAL_DUMP_COMMAND =
            "for d in /sys/class/thermal/thermal_zone*; do "
                    + "i=${d##*thermal_zone}; "
                    + "t=$(cat $d/type 2>/dev/null); "
                    + "v=$(cat $d/temp 2>/dev/null); "
                    + "echo \"$i|$t|$v\"; done";
    private static final long THERMAL_CACHE_TTL_MS = 10000;
    private static volatile java.util.List<PureCalc.ThermalReading> shellThermalCache;
    private static volatile long shellThermalCacheTime = 0;
    private static volatile int lastShellThermalCount = -1;

    public static synchronized java.util.List<PureCalc.ThermalReading> getShellThermalReadings() {
        long now = System.currentTimeMillis();
        if (shellThermalCache != null && now - shellThermalCacheTime < THERMAL_CACHE_TTL_MS) {
            return shellThermalCache;
        }
        java.util.List<PureCalc.ThermalReading> parsed =
                PureCalc.parseThermalDump(PersistentShell.exec(THERMAL_DUMP_COMMAND));
        shellThermalCache = parsed;
        shellThermalCacheTime = now;
        if (parsed.size() != lastShellThermalCount) {
            lastShellThermalCount = parsed.size();
            DiagLog.log("THERM", "root shell thermal dump: " + parsed.size() + " zones parsed");
        }
        return parsed;
    }

    // Integer temperature for a zone whose type contains the label,
    // from whichever enumeration answers (plain files first, the
    // root shell dump when SELinux blocks the plain reads). Null
    // when no matching zone answers.
    public static Integer thermalTempByLabel(String label) {
        for (ThermalSensor sensor : getThermalSensors()) {
            if (sensor.name.toLowerCase().contains(label)) {
                return sensor.tempC;
            }
        }
        return null;
    }

    // One readable thermal sensor: its zone type and temperature.
    public static class ThermalSensor {
        public final String name;
        public final int tempC;

        ThermalSensor(String name, int tempC) {
            this.name = name;
            this.tempC = tempC;
        }
    }

    private static volatile String lastSensorOutcome = null;

    private static void logSensorOutcome(String outcome) {
        if (!outcome.equals(lastSensorOutcome)) {
            lastSensorOutcome = outcome;
            DiagLog.log("THERM", "sensor enumeration: " + outcome);
        }
    }

    // The plain-file thermal enumeration on its own, without the
    // shell fallback, so the function test can report each path's
    // sensor count separately.
    public static java.util.List<ThermalSensor> readPlainThermalSensors() {
        java.util.List<ThermalSensor> sensors = new java.util.ArrayList<>();
        File thermalDir = new File("/sys/class/thermal/");
        File[] files = thermalDir.exists() ? thermalDir.listFiles() : null;
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Integer.compare(thermalZoneIndex(a.getName()), thermalZoneIndex(b.getName())));
            for (File zone : files) {
                if (!zone.getName().startsWith("thermal_zone")) {
                    continue;
                }
                String type = readFile(new File(zone, "type").getAbsolutePath());
                String temp = readFile(new File(zone, "temp").getAbsolutePath());
                if (type == null || temp == null) {
                    continue;
                }
                try {
                    int raw = Integer.parseInt(temp.trim());
                    int celsius = PureCalc.thermalToCelsius(raw);
                    if (celsius < -30 || celsius > 150) {
                        continue;
                    }
                    String name = type.trim();
                    if (!name.isEmpty()) {
                        sensors.add(new ThermalSensor(name, celsius));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return sensors;
    }

    // Every thermal zone the kernel exposes, sorted by zone index.
    // Zones whose type or temp cannot be read or parsed are skipped,
    // so the list only holds sensors that actually answer. When the
    // plain enumeration finds nothing — SELinux on the user's ROM
    // denies the app every thermal node — the root-shell dump takes
    // over so the list (and everything built on it) still works.
    public static java.util.List<ThermalSensor> getThermalSensors() {
        java.util.List<ThermalSensor> sensors = readPlainThermalSensors();
        if (!sensors.isEmpty()) {
            logSensorOutcome("plain files answered, " + sensors.size() + " sensors");
            return sensors;
        }
        java.util.List<ThermalSensor> fromShell = new java.util.ArrayList<>();
        for (PureCalc.ThermalReading reading : getShellThermalReadings()) {
            fromShell.add(new ThermalSensor(reading.name, reading.tempC));
        }
        logSensorOutcome(fromShell.isEmpty()
                ? "no path answered (plain files blocked, shell dump empty)"
                : "root shell answered, " + fromShell.size() + " sensors");
        return fromShell;
    }

    private static int thermalZoneIndex(String dirName) {
        try {
            return Integer.parseInt(dirName.substring("thermal_zone".length()));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    // The system thermal status from the Android Thermal API
    // (PowerManager, API 29+) — the non-root thermal source. It is
    // NOT a temperature: it is the platform's own throttling state
    // (PowerManager.THERMAL_STATUS_*), and callers must present it
    // labelled as a status, never dressed up as °C. Returns -1 when
    // the API is unavailable (older Android, no PowerManager).
    // Per-sensor °C stays with the file/shell enumeration above.
    public static int getThermalStatus(Context context) {
        if (Build.VERSION.SDK_INT < 29 || context == null) {
            return -1;
        }
        try {
            android.os.PowerManager powerManager = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return -1;
            }
            return powerManager.getCurrentThermalStatus();
        } catch (Exception e) {
            return -1;
        }
    }

    // String resource naming a PowerManager thermal status value,
    // for the labelled status line. -1 (API unavailable) maps to
    // the "unknown" wording only if a caller still asks.
    public static int thermalStatusStringRes(int status) {
        switch (status) {
            case 1:
                return R.string.thermal_status_light;
            case 2:
                return R.string.thermal_status_moderate;
            case 3:
                return R.string.thermal_status_severe;
            case 4:
                return R.string.thermal_status_critical;
            case 5:
                return R.string.thermal_status_emergency;
            case 6:
                return R.string.thermal_status_shutdown;
            case 0:
                return R.string.thermal_status_normal;
            default:
                return R.string.thermal_status_unknown;
        }
    }

    // Which path produced the latest FPS reading ("SurfaceFlinger",
    // "gfxinfo" or "none"), so the function test can name the path
    // that answered instead of just reporting a number.
    private static volatile String lastFpsSource = "none";

    // Non-root FPS path: FpsCaptureService (MediaProjection) counts
    // distinct presented frames and publishes the latest per-second
    // value here. While a published value is fresh it wins over the
    // shell paths — it measures what is actually on the screen,
    // foreground game included, with no root at all. The shell
    // paths (SurfaceFlinger latency, gfxinfo) remain the fallback
    // whenever no capture is running, and the root enhancement when
    // the capture cannot run.
    private static volatile Double projectionFps = null;
    private static volatile long projectionFpsTimeMs = 0;
    private static final long PROJECTION_FRESH_MS = 2500;

    public static void setProjectionFps(Double value) {
        projectionFps = value;
        projectionFpsTimeMs = value != null ? System.currentTimeMillis() : 0;
    }

    private static Double freshProjectionFps() {
        Double value = projectionFps;
        if (value == null) {
            return null;
        }
        return System.currentTimeMillis() - projectionFpsTimeMs <= PROJECTION_FRESH_MS
                ? value : null;
    }

    public static String getLastFpsSource() {
        if (freshProjectionFps() != null) {
            return "MediaProjection";
        }
        return lastFpsSource;
    }

    public static String getSystemFpsFromSurfaceFlinger() {
        try {
            String output = PersistentShell.exec("dumpsys SurfaceFlinger --latency");
            if (output == null) {
                return getFallbackFps();
            }
            long[] timestamps = new long[128];
            int count = 0;
            String[] lines = output.split("\n");
            for (int li = 1; li < lines.length && count < timestamps.length; li++) {
                String[] parts = lines[li].trim().split("\\s+");
                if (parts.length >= 3 && !"0".equals(parts[1])) {
                    try {
                        timestamps[count++] = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (count < 2) {
                return getFallbackFps();
            }
            long totalDelta = 0;
            int deltaCount = 0;
            for (int i = 1; i < count; i++) {
                long delta = timestamps[i] - timestamps[i - 1];
                if (delta > 0) {
                    totalDelta += delta;
                    deltaCount++;
                }
            }
            if (deltaCount == 0) {
                return getFallbackFps();
            }
            lastFpsSource = "SurfaceFlinger";
            return String.format(Locale.US, "FPS: %.1f", 1000.0d / ((totalDelta / deltaCount) / 1000000.0d));
        } catch (Exception e) {
            Log.e("FPS_ERROR", "Gagal ambil FPS", e);
            return getFallbackFps();
        }
    }

    public static void startFpsMonitorThread() {
        if (!fpsMonitorRunning) {
            fpsMonitorRunning = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (fpsMonitorRunning) {
                        try {
                            lastFpsString = getSystemFpsFromSurfaceFlinger();
                            Thread.sleep(1000);
                        } catch (Exception e) {
                            lastFpsString = "FPS: n/a";
                        }
                    }
                }
            }).start();
        }
    }

    private static String getFallbackFps() {
        try {
            String output = PersistentShell.exec("dumpsys gfxinfo com.appslayer.official");
            if (output == null) {
                lastFpsSource = "none";
                return "FPS: n/a";
            }
            double total = 0;
            int count = 0;
            for (String rawLine : output.split("\n")) {
                String line = rawLine.trim();
                if (line.matches("^\\d+\\.\\d+\\s+\\d+\\.\\d+\\s+\\d+\\.\\d+$")) {
                    String[] parts = line.split("\\s+");
                    total += Double.parseDouble(parts[0]) + Double.parseDouble(parts[1]) + Double.parseDouble(parts[2]);
                    count++;
                }
            }
            if (count == 0) {
                lastFpsSource = "none";
                return "FPS: n/a";
            }
            lastFpsSource = "gfxinfo";
            return String.format(Locale.US, "FPS: %.1f", 1000.0d / (total / count));
        } catch (Exception e) {
            Log.e("FPS_FALLBACK", "Gagal fallback FPS", e);
            lastFpsSource = "none";
            return "FPS: n/a";
        }
    }

    public static String getFps() {
        Double projected = freshProjectionFps();
        if (projected != null) {
            return String.format(Locale.US, "FPS: %.1f", projected);
        }
        return lastFpsString;
    }

    // The latest FPS reading as a number, for the recorder. Null
    // while the sampler has no reading ("FPS: ..." / "FPS: n/a").
    public static Double getFpsValue() {
        Double projected = freshProjectionFps();
        if (projected != null) {
            return projected;
        }
        return PureCalc.parseFpsValue(lastFpsString);
    }

    // CPU temperature with one decimal ("33.5°C"), from the raw
    // millidegree zone value. Null when no CPU zone answers.
    public static String getCpuTempDecimal() {
        Double value = thermalValueByLabel("cpu");
        return value == null ? null : String.format(Locale.US, "%.1f°C", value);
    }

    // Numeric CPU / GPU temperatures for the FPS recorder. Null when
    // no matching zone answers.
    public static Double getCpuTempValue() {
        return thermalValueByLabel("cpu");
    }

    public static Double getGpuTempValue() {
        return thermalValueByLabel("gpu");
    }

    private static Double thermalValueByLabel(String label) {
        File thermalDir = new File("/sys/class/thermal/");
        File[] files = thermalDir.exists() ? thermalDir.listFiles() : null;
        if (files != null) {
            for (File zone : files) {
                if (!zone.getName().startsWith("thermal_zone")) {
                    continue;
                }
                String type = readFile(new File(zone, "type").getAbsolutePath());
                String temp = readFile(new File(zone, "temp").getAbsolutePath());
                if (type == null || temp == null || !type.toLowerCase().contains(label)) {
                    continue;
                }
                try {
                    int raw = Integer.parseInt(temp.trim());
                    double celsius = PureCalc.thermalToCelsiusDecimal(raw);
                    if (celsius > -30 && celsius < 150) {
                        return celsius;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // Same SELinux exposure as the sensor list: when the plain
        // scan finds no matching zone, try the root-shell dump.
        for (PureCalc.ThermalReading reading : getShellThermalReadings()) {
            if (reading.name.toLowerCase().contains(label)) {
                return reading.tempCDecimal;
            }
        }
        return null;
    }

    // The foreground app's package without root, from usage stats.
    // Answers only when the user has granted this app usage access
    // in the system settings — without the grant the query simply
    // returns no events (it never throws and never prompts), so
    // this is safe to try first and fall back to the shell. The
    // latest resumed activity in the last ten minutes that is not
    // this app itself wins.
    public static String getForegroundPackageNoRoot(Context context) {
        if (context == null) {
            return null;
        }
        try {
            android.app.usage.UsageStatsManager manager =
                    (android.app.usage.UsageStatsManager)
                            context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) {
                return null;
            }
            long end = System.currentTimeMillis();
            android.app.usage.UsageEvents events = manager.queryEvents(end - 10 * 60 * 1000, end);
            if (events == null) {
                return null;
            }
            String own = context.getPackageName();
            String foreground = null;
            android.app.usage.UsageEvents.Event event =
                    new android.app.usage.UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                // ACTIVITY_RESUMED (the pre-29 name MOVE_TO_FOREGROUND
                // has the same value, and the constant is inlined).
                if (event.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    String pkg = event.getPackageName();
                    if (pkg != null && !pkg.equals(own)) {
                        foreground = pkg;
                    }
                }
            }
            return foreground;
        } catch (Exception e) {
            return null;
        }
    }

    // The foreground app's package, read through the root shell.
    // `dumpsys activity activities` names the top resumed activity
    // on most ROMs; the focused window from `dumpsys window` is the
    // fallback. Best-effort by nature: null when neither answers.
    public static String getForegroundPackage() {
        String pkg = PureCalc.parseForegroundPackage(runShellCommand("dumpsys activity activities"));
        if (pkg != null) {
            return pkg;
        }
        return PureCalc.parseForegroundPackage(runShellCommand("dumpsys window windows"));
    }

    // Top processes by CPU as overlay text ("name 12%" lines), from
    // one `top` snapshot on the root shell. Toybox accepts the -b
    // batch flag; if a ROM's top rejects it, the plain one-shot form
    // is tried before giving up. Null when nothing parses, so the
    // caller keeps the previous text instead of blanking the window.
    public static String getTopProcesses(int limit) {
        String text = PureCalc.formatTopProcesses(
                PureCalc.parseTopProcesses(runShellCommand("top -b -n 1"), limit));
        if (text != null) {
            return text;
        }
        return PureCalc.formatTopProcesses(
                PureCalc.parseTopProcesses(runShellCommand("top -n 1"), limit));
    }

    // The combined single-line chip the FPS window shows:
    // "CPU: 12% GPU: 19% FPS: 0.0 33.5°C" — CPU load, GPU load,
    // current FPS and CPU temperature on one line.
    public static String getCombinedChip() {
        int cpu = getCpuUsagePercent();
        int gpu = getGpuUsagePercent();
        String fps = lastFpsString != null && lastFpsString.startsWith("FPS: ")
                ? lastFpsString.substring("FPS: ".length()) : "...";
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(cpu >= 0 ? cpu + "%" : "...");
        sb.append(" GPU: ").append(gpu >= 0 ? gpu + "%" : "...");
        sb.append(" FPS: ").append(fps);
        String temp = getCpuTempDecimal();
        if (temp != null) {
            sb.append(" ").append(temp);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Compact values for the mini overlay bar, plus the battery
    // voltage reader the Battery Info power row needs.
    // ------------------------------------------------------------------

    private static long prevCpuTotal = -1;
    private static long prevCpuIdle = -1;

    // Real CPU usage in percent, from the delta between two /proc/stat
    // reads. -1 until a second sample exists.
    public static synchronized int getCpuUsagePercent() {
        String line = readFile("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) {
            return -1;
        }
        try {
            String[] parts = line.trim().split("\\s+");
            long idle = Long.parseLong(parts[4]) + Long.parseLong(parts[5]);
            long total = 0;
            for (int i = 1; i < parts.length; i++) {
                total += Long.parseLong(parts[i]);
            }
            if (prevCpuTotal < 0) {
                prevCpuTotal = total;
                prevCpuIdle = idle;
                return -1;
            }
            long totalDelta = total - prevCpuTotal;
            long idleDelta = idle - prevCpuIdle;
            prevCpuTotal = total;
            prevCpuIdle = idle;
            if (totalDelta <= 0) {
                return -1;
            }
            return Math.max(0, Math.min(100, (int) ((100L * (totalDelta - idleDelta)) / totalDelta)));
        } catch (Exception e) {
            return -1;
        }
    }

    // GPU usage in percent on the same min..max scale the classic GPU
    // text uses. -1 when the frequency cannot be read.
    public static int getGpuUsagePercent() {
        int[] freqs = readGpuFreqs();
        int cur = freqs[0];
        int max = freqs[1];
        int min = freqs[2];
        if (cur > 0 && max > min) {
            return Math.max(0, Math.min(100, (int) (((cur - min) * 100.0d) / (max - min))));
        }
        return -1;
    }

    // Used RAM in percent, computed exactly like getRamUsage()
    // (memory + swap combined). -1 when /proc/meminfo cannot be read.
    public static int getRamUsagePercent() {
        long total = 0;
        long available = 0;
        long swapTotal = 0;
        long swapFree = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
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
            }
            reader.close();
        } catch (Exception e) {
            return -1;
        }
        long totalAll = total + swapTotal;
        if (totalAll <= 0) {
            return -1;
        }
        long usedAll = totalAll - (available + swapFree);
        return (int) ((usedAll * 100.0f) / totalAll);
    }

    // Battery voltage in millivolts: the sticky broadcast extra first,
    // then the sysfs node (microvolts), then the root shell for nodes
    // a plain read cannot open. Null when no source answers.
    public static Integer readBatteryVoltageMv() {
        Context context = AppContext.get();
        if (context != null) {
            try {
                Intent intent = context.registerReceiver(null, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
                if (intent != null) {
                    int mv = intent.getIntExtra("voltage", -1);
                    if (mv > 0) {
                        return mv;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String plain = readFile("/sys/class/power_supply/battery/voltage_now");
        Integer mv = voltageMicroToMilli(plain);
        if (mv != null) {
            return mv;
        }
        return voltageMicroToMilli(readFileWithRoot("/sys/class/power_supply/battery/voltage_now"));
    }

    private static Integer voltageMicroToMilli(String microVolts) {
        if (microVolts == null) {
            return null;
        }
        try {
            int uv = Integer.parseInt(microVolts.trim());
            return uv > 0 ? uv / 1000 : null;
        } catch (Exception e) {
            return null;
        }
    }
}
