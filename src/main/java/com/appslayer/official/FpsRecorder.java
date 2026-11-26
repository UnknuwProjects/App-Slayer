package com.appslayer.official;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

// FPS session recorder. The state is static (the same pattern as
// the services' running flags) so a recording started on the
// Floating Monitor screen keeps going after the user leaves the
// screen — the overlay service's background scheduler feeds it one
// sample per second through sampleNow(). Samples land in a ".tmp"
// file under filesDir/fps_sessions/ and the file is renamed to its
// final ".csv" name when the recording stops, so the sessions list
// only ever sees finished sessions.
public class FpsRecorder {

    // Hard stop for one session: one hour at the 1 s cadence. Keeps
    // a forgotten recording from growing a file forever.
    public static final int MAX_SAMPLES = 3600;

    public static final int SAMPLE_OK = 0;
    public static final int SAMPLE_LIMIT_REACHED = 1;
    public static final int SAMPLE_NOT_RECORDING = 2;

    // The foreground app is polled through dumpsys every few
    // samples, not every second — the dump is large and the app in
    // front rarely changes mid-session.
    private static final int PACKAGE_CHECK_EVERY = 5;

    private static volatile boolean recording = false;
    private static volatile long startTimeMs = 0;
    private static volatile int sampleCount = 0;

    private static File sessionFile;
    private static BufferedWriter writer;
    private static String lastPackage;
    private static int samplesSincePackageCheck;

    public static boolean isRecording() {
        return recording;
    }

    public static long getStartTimeMs() {
        return startTimeMs;
    }

    public static int getSampleCount() {
        return sampleCount;
    }

    public static synchronized boolean start(Context context) {
        if (recording) {
            return true;
        }
        // Clear out leftovers from a recording that never stopped
        // cleanly and trim the old sessions before starting a new
        // one. Safe here: prune never runs while a recording is live.
        FpsSession.prune(context);
        File dir = FpsSession.dir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            DiagLog.log(context, "FPS", "recording start failed: cannot create " + dir);
            return false;
        }
        startTimeMs = System.currentTimeMillis();
        sessionFile = new File(dir, "session_" + startTimeMs + ".tmp");
        try {
            writer = new BufferedWriter(new FileWriter(sessionFile, false));
            writer.write("#start," + startTimeMs);
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            DiagLog.log(context, "FPS", "recording start failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            closeWriter();
            sessionFile = null;
            return false;
        }
        sampleCount = 0;
        lastPackage = null;
        samplesSincePackageCheck = PACKAGE_CHECK_EVERY; // check on the first sample
        recording = true;
        DiagLog.log(context, "FPS", "recording started");
        // The FPS sampler is process-wide; make sure it is running
        // even if nothing else started it yet.
        SystemStats.startFpsMonitorThread();
        return true;
    }

    // Takes one sample from the current readings and appends it.
    // Runs on the overlay service's background thread — it touches
    // the root shell (foreground app poll) and the file, so it must
    // never be called from the UI thread. Returns SAMPLE_LIMIT_REACHED
    // when this sample hit the cap and the recording stopped itself.
    public static synchronized int sampleNow(Context context) {
        if (!recording || writer == null) {
            return SAMPLE_NOT_RECORDING;
        }
        Double fps = SystemStats.getFpsValue();
        if (fps == null) {
            return SAMPLE_OK; // sampler has no reading yet; skip, keep recording
        }
        long now = System.currentTimeMillis();
        Double cpuTemp = SystemStats.getCpuTempValue();
        Double gpuTemp = SystemStats.getGpuTempValue();
        int level = -1;
        Integer currentMa = null;
        Integer voltageMv = null;
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter("android.intent.action.BATTERY_CHANGED"));
            if (intent != null) {
                int rawLevel = intent.getIntExtra("level", -1);
                int scale = intent.getIntExtra("scale", 100);
                if (rawLevel >= 0 && scale > 0) {
                    level = rawLevel * 100 / scale;
                }
                boolean plugged = intent.getIntExtra("plugged", 0) != 0;
                Integer magnitude = SystemStats.readBatteryCurrentMa();
                if (magnitude != null) {
                    currentMa = plugged ? magnitude : -magnitude;
                }
                int mv = intent.getIntExtra("voltage", -1);
                if (mv > 0) {
                    voltageMv = mv;
                }
            }
        } catch (Exception ignored) {
        }
        if (voltageMv == null) {
            voltageMv = SystemStats.readBatteryVoltageMv();
        }
        try {
            writer.write(now + ","
                    + String.format(Locale.US, "%.1f", fps) + ","
                    + (cpuTemp == null ? "" : String.format(Locale.US, "%.1f", cpuTemp)) + ","
                    + (gpuTemp == null ? "" : String.format(Locale.US, "%.1f", gpuTemp)) + ","
                    + (level >= 0 ? String.valueOf(level) : "") + ","
                    + (currentMa == null ? "" : String.valueOf(currentMa)) + ","
                    + (voltageMv == null ? "" : String.valueOf(voltageMv)));
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            stopLocked();
            return SAMPLE_NOT_RECORDING;
        }
        sampleCount++;
        samplesSincePackageCheck++;
        if (samplesSincePackageCheck >= PACKAGE_CHECK_EVERY) {
            samplesSincePackageCheck = 0;
            String pkg = SystemStats.getForegroundPackage();
            if (pkg != null && !pkg.equals(lastPackage)) {
                lastPackage = pkg;
                try {
                    writer.write("#app," + now + "," + pkg);
                    writer.newLine();
                    writer.flush();
                } catch (Exception ignored) {
                }
            }
        }
        if (sampleCount >= MAX_SAMPLES) {
            stopLocked();
            return SAMPLE_LIMIT_REACHED;
        }
        return SAMPLE_OK;
    }

    public static synchronized void stop(Context context) {
        if (!recording) {
            return;
        }
        int samples = sampleCount;
        stopLocked();
        DiagLog.log(context, "FPS", "recording stopped, " + samples + " samples");
        FpsSession.prune(context);
    }

    // Finalizes the session file: a session with samples is renamed
    // to ".csv" (visible to the sessions list); an empty one is
    // dropped instead of cluttering the list with a no-data entry.
    private static void stopLocked() {
        recording = false;
        closeWriter();
        if (sessionFile != null) {
            if (sampleCount > 0) {
                File target = new File(sessionFile.getParent(),
                        sessionFile.getName().replace(".tmp", ".csv"));
                if (!sessionFile.renameTo(target)) {
                    sessionFile.delete();
                }
            } else {
                sessionFile.delete();
            }
            sessionFile = null;
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
            writer = null;
        }
    }
}
