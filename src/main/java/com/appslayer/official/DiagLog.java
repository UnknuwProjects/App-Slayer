package com.appslayer.official;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

// Diagnostic log: one timestamped line per event, so problems can be
// read off the timeline instead of guessed from screenshots. The
// primary file lives in the app's private files dir (always
// writable); a mirror is kept on shared storage through the root
// shell so the user can grab it with any file manager:
//
//   /storage/emulated/0/AppSlayer/logs/diagnostic_log.txt
//
// Tags: USER = something the user tapped or changed, SYS = app
// events, plus per-area tags (OVERLAY, NOTIF, THERM, BATT, FPS).
// Logging must never crash the app: every entry point swallows its
// own failures.
public final class DiagLog {

    public static final String LOG_FILE_NAME = "diagnostic_log.txt";
    public static final String MIRROR_DIR = "/storage/emulated/0/AppSlayer/logs";
    public static final String MIRROR_PATH = MIRROR_DIR + "/" + LOG_FILE_NAME;

    private static final long MAX_BYTES = 256 * 1024;
    private static final int FLUSH_EVERY_LINES = 25;

    private static final Object LOCK = new Object();
    private static volatile File logFile;
    private static int linesSinceFlush = 0;
    private static final AtomicBoolean flushInFlight = new AtomicBoolean(false);
    private static volatile boolean flushPending = false;

    private DiagLog() {
    }

    public static File file(Context context) {
        File cached = logFile;
        if (cached != null) {
            return cached;
        }
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            if (logFile == null) {
                logFile = new File(context.getApplicationContext().getFilesDir(), LOG_FILE_NAME);
            }
            return logFile;
        }
    }

    public static File mirrorFile() {
        return new File(MIRROR_PATH);
    }

    public static void log(String tag, String message) {
        log(AppContext.get(), tag, message);
    }

    public static void log(Context context, String tag, String message) {
        try {
            append(context, tag, message, true);
        } catch (Exception ignored) {
            // A logging failure is never worth surfacing.
        }
    }

    // Appends a multi-line block, one timestamped log line per text
    // line (used by the "run checks" snapshot).
    public static void logBlock(Context context, String tag, String block) {
        if (block == null) {
            return;
        }
        for (String line : block.split("\n")) {
            log(context, tag, line);
        }
    }

    private static void append(Context context, String tag, String message, boolean countForFlush) {
        File target = file(context);
        if (target == null || message == null) {
            return;
        }
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = stamp + " [" + tag + "] " + message.replace('\n', ' ') + "\n";
        boolean shouldFlush = false;
        synchronized (LOCK) {
            try {
                trimIfNeeded(target);
                FileOutputStream out = new FileOutputStream(target, true);
                out.write(line.getBytes("UTF-8"));
                out.close();
            } catch (Exception ignored) {
                return;
            }
            if (countForFlush) {
                linesSinceFlush++;
                if (linesSinceFlush >= FLUSH_EVERY_LINES) {
                    linesSinceFlush = 0;
                    shouldFlush = true;
                }
            }
        }
        if (shouldFlush) {
            flushMirrorAsync(context);
        }
    }

    // Ring behavior: once the file passes the cap, drop the oldest
    // half (aligned to a line boundary) so it can never grow wild.
    private static void trimIfNeeded(File target) {
        try {
            if (!target.exists() || target.length() <= MAX_BYTES) {
                return;
            }
            StringBuilder all = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(target));
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append("\n");
            }
            reader.close();
            String text = all.toString();
            int cut = text.indexOf('\n', text.length() / 2);
            String kept = cut >= 0 ? text.substring(cut + 1) : text;
            FileOutputStream out = new FileOutputStream(target, false);
            out.write(kept.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context context) {
        File target = file(context);
        if (target == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                new FileOutputStream(target, false).close();
            } catch (Exception ignored) {
            }
            linesSinceFlush = 0;
        }
    }

    // Copies the primary log to shared storage through the root
    // shell. Runs on a worker thread; if a flush is already in
    // flight, one more is queued behind it. Silent on failure apart
    // from a note in the primary log itself.
    public static void flushMirrorAsync(final Context context) {
        if (context == null) {
            return;
        }
        if (!flushInFlight.compareAndSet(false, true)) {
            flushPending = true;
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    flushMirrorNow(appContext);
                } finally {
                    flushInFlight.set(false);
                    if (flushPending) {
                        flushPending = false;
                        flushMirrorAsync(appContext);
                    }
                }
            }
        }).start();
    }

    private static void flushMirrorNow(Context context) {
        File primary = file(context);
        if (primary == null || !primary.exists()) {
            return;
        }
        String out = SystemStats.runShellCommand(
                "mkdir -p " + MIRROR_DIR + " && cp " + primary.getAbsolutePath()
                        + " " + MIRROR_PATH + " && echo MIRROR_OK");
        if (out == null || !out.contains("MIRROR_OK")) {
            // Not counted toward the next batched flush, and written
            // through the raw append so a dead shell cannot loop.
            append(context, "SYS",
                    "log mirror to shared storage failed (root shell unavailable)", false);
        }
    }
}
