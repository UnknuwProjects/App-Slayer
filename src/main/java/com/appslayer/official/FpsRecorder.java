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
// screen. The recorder runs its OWN sampling loop (one sample per
// second, started and stopped with the recording) — up to 6.3.6
// the samples were fed by the overlay service's scheduler, so a
// recording made with the overlay service down collected nothing
// at all. Samples land in a ".tmp" file under
// filesDir/fps_sessions/ and the file is renamed to its final
// ".csv" name when the recording stops, so the sessions list only
// ever sees finished sessions. While a recording runs, the
// foreground app is polled every few seconds; when it changes to
// a different app, the current session file is finalized exactly
// as if the recording had stopped and a new one is opened for
// the new app — one session per tested app, with the capture
// running untouched across the split. EVERY recording produces a session
// file — including one with zero samples, which the sessions list
// shows marked as no-data instead of silently vanishing (the old
// finalize deleted empty files, which is why recordings "never
// saved" whenever no FPS source answered).
public class FpsRecorder {

    // Hard stop for one session: one hour at the 1 s cadence. Keeps
    // a forgotten recording from growing a file forever.
    public static final int MAX_SAMPLES = 3600;

    public static final int SAMPLE_OK = 0;
    public static final int SAMPLE_LIMIT_REACHED = 1;
    public static final int SAMPLE_NOT_RECORDING = 2;

    // The foreground app is polled every few samples, not every
    // second. The usage-stats poll is cheap; the root dumpsys
    // fallback is not (two large dumps), so it is only consulted
    // every ROOT_CHECK_EVERY package checks while usage stats
    // have no answer.
    private static final int PACKAGE_CHECK_EVERY = 3;
    private static final int ROOT_CHECK_EVERY = 5;

    private static volatile boolean recording = false;
    private static volatile long startTimeMs = 0;
    private static volatile int sampleCount = 0;

    private static File sessionFile;
    private static BufferedWriter writer;
    // The app the current session file belongs to: the first
    // foreground app observed for it. A later, different app
    // splits the session (PureCalc.shouldSplitSession).
    private static String sessionPackage;
    private static int samplesSincePackageCheck;
    private static int packageCheckCycles;

    // The recorder's own sampling loop and the context it samples
    // with (the application context captured at start()).
    private static volatile boolean loopRunning = false;
    private static Thread loopThread;
    private static Context appContext;

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
        if (!openSessionFile(context)) {
            return false;
        }
        sampleCount = 0;
        sessionPackage = null;
        packageCheckCycles = 0;
        samplesSincePackageCheck = PACKAGE_CHECK_EVERY; // check on the first sample
        recording = true;
        appContext = context.getApplicationContext();
        DiagLog.log(context, "FPS", "recording started");
        // The FPS sampler is process-wide; make sure it is running
        // even if nothing else started it yet.
        SystemStats.startFpsMonitorThread();
        startSamplingLoop();
        return true;
    }

    // One sample per second on the recorder's own thread, for as
    // long as the recording runs — independent of the overlay
    // windows and of which screen is open. When the session cap
    // stops the recording from inside sampleNow(), the loop sees
    // the flag drop and ends; the capture service (if it was
    // started for this recording) is stopped with it.
    private static void startSamplingLoop() {
        if (loopRunning) {
            return;
        }
        loopRunning = true;
        loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (loopRunning && recording) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!loopRunning || !recording || appContext == null) {
                        return;
                    }
                    int result = sampleNow(appContext);
                    if (result == SAMPLE_LIMIT_REACHED) {
                        DiagLog.log(appContext, "FPS",
                                "recording stopped by the session cap");
                        FpsCaptureService.stop(appContext);
                        final Context toastContext = appContext;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(new Runnable() {
                                    @Override
                                    public void run() {
                                        android.widget.Toast.makeText(toastContext,
                                                R.string.toast_recording_limit,
                                                android.widget.Toast.LENGTH_LONG).show();
                                    }
                                });
                        return;
                    }
                }
            }
        });
        loopThread.start();
    }

    private static void stopSamplingLoop() {
        loopRunning = false;
        if (loopThread != null) {
            loopThread.interrupt();
            loopThread = null;
        }
    }

    // Takes one sample from the current readings and appends it.
    // Runs on the recorder's own sampling thread — it touches the
    // root shell (foreground app poll) and the file, so it must
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
            packageCheckCycles++;
            // Non-root first (usage stats, when the user granted
            // access); the root shell answers at a slower cadence
            // while usage stats have nothing.
            String pkg = SystemStats.getForegroundPackageNoRoot(context);
            if (pkg == null && packageCheckCycles % ROOT_CHECK_EVERY == 1) {
                pkg = SystemStats.getForegroundPackage();
            }
            // This app itself never counts: the user popping back
            // here to stop the recording must not split the session
            // or claim it.
            if (pkg != null && !pkg.equals(context.getPackageName())) {
                if (sessionPackage == null) {
                    sessionPackage = pkg;
                    writeAppMarker(now, pkg);
                } else if (PureCalc.shouldSplitSession(
                        sessionPackage, pkg, context.getPackageName())) {
                    splitSession(context, pkg, now);
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
        stopSamplingLoop();
        stopLocked();
        DiagLog.log(context, "FPS", "recording stopped, " + samples + " samples"
                + " (FPS source: " + SystemStats.getLastFpsSource() + ")");
        FpsSession.prune(context);
    }

    // Finalizes the session file: stamps the "#end" line and renames
    // ".tmp" to ".csv" (visible to the sessions list) no matter how
    // many samples the recording collected. A zero-sample session
    // stays in the list marked as no-data — the honest record that
    // a recording happened and no FPS source answered during it —
    // instead of being deleted without a trace.
    private static void stopLocked() {
        recording = false;
        stopSamplingLoop();
        finalizeSessionFile();
    }

    // Ends the current session because the foreground app changed:
    // the file is finalized exactly as a normal stop would do it,
    // then a fresh session opens for the new app. The sampling
    // loop and the screen capture keep running — a split needs no
    // consent prompt and no user action. If the new file cannot be
    // opened the recording stops instead of sampling into nowhere.
    private static void splitSession(Context context, String newPackage, long now) {
        finalizeSessionFile();
        DiagLog.log(context, "FPS", "session split: foreground app is now " + newPackage);
        startTimeMs = now;
        sampleCount = 0;
        sessionPackage = newPackage;
        samplesSincePackageCheck = 0;
        if (!openSessionFile(context)) {
            recording = false;
            stopSamplingLoop();
            return;
        }
        writeAppMarker(now, newPackage);
    }

    // Opens the ".tmp" file for the session starting at
    // startTimeMs and stamps its "#start" line.
    private static boolean openSessionFile(Context context) {
        File dir = FpsSession.dir(context);
        sessionFile = new File(dir, "session_" + startTimeMs + ".tmp");
        try {
            writer = new BufferedWriter(new FileWriter(sessionFile, false));
            writer.write("#start," + startTimeMs);
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception e) {
            DiagLog.log(context, "FPS", "recording start failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            closeWriter();
            sessionFile = null;
            return false;
        }
    }

    // Stamps the "#end" line, closes the writer and renames the
    // session file to its final ".csv" name.
    private static void finalizeSessionFile() {
        if (writer != null) {
            try {
                writer.write("#end," + System.currentTimeMillis());
                writer.newLine();
                writer.flush();
            } catch (Exception ignored) {
            }
        }
        closeWriter();
        if (sessionFile != null) {
            File target = new File(sessionFile.getParent(),
                    sessionFile.getName().replace(".tmp", ".csv"));
            if (!sessionFile.renameTo(target)) {
                sessionFile.delete();
            }
            sessionFile = null;
        }
    }

    private static void writeAppMarker(long now, String pkg) {
        try {
            writer.write("#app," + now + "," + pkg);
            writer.newLine();
            writer.flush();
        } catch (Exception ignored) {
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
