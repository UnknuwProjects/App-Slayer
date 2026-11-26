package com.appslayer.official;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Charge sessions, recorded from the battery logging service's
// ACTION_BATTERY_CHANGED stream (6.3.8): a session runs from
// plug-in to unplug or full. The detection and the math live in
// PureCalc.ChargeSessionTracker; this class is the storage and
// plumbing around it, mirroring the FPS sessions' approach:
//
//   filesDir/battery_sessions/current.csv     the open session's
//                                             raw events, appended
//                                             as they arrive
//   filesDir/battery_sessions/session_<start>.csv
//                                             one finished session:
//                                             #start/#end/#from/#to
//                                             #avgma/#avgw/#mah
//
// Events are enriched (current, voltage, charge counter) and fed
// to the tracker on a single background thread: the readers can
// fall back to the root shell and must never block the broadcast
// receiver. If the process dies mid-session, the open session's
// events are replayed through a fresh tracker on the next feed,
// so the session resumes instead of vanishing. Sessions only
// exist from 6.3.8 forward — nothing here invents older ones.
public class ChargeSessions {

    public static final String DIR_NAME = "battery_sessions";
    private static final String CURRENT_NAME = "current.csv";
    public static final int MAX_KEPT_SESSIONS = 30;

    public static class Record {
        public final long startMs;
        public final long endMs;
        public final int startLevel;
        public final int endLevel;
        public final double avgCurrentMa;
        public final double avgPowerW;
        public final int mahAdded;
        public final boolean inProgress;

        Record(long startMs, long endMs, int startLevel, int endLevel,
               double avgCurrentMa, double avgPowerW, int mahAdded, boolean inProgress) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.startLevel = startLevel;
            this.endLevel = endLevel;
            this.avgCurrentMa = avgCurrentMa;
            this.avgPowerW = avgPowerW;
            this.mahAdded = mahAdded;
            this.inProgress = inProgress;
        }

        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }

        static Record fromSummary(PureCalc.ChargeSessionSummary s) {
            return new Record(s.startMs, s.endMs, s.startLevel, s.endLevel,
                    s.avgCurrentMa, s.avgPowerW, s.mahAdded, s.inProgress);
        }
    }

    private static final PureCalc.ChargeSessionTracker tracker =
            new PureCalc.ChargeSessionTracker();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean restored = false;

    public static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    private static File currentFile(Context context) {
        return new File(dir(context), CURRENT_NAME);
    }

    // Entry point for the battery stream. Extracts the cheap facts
    // on the caller's thread and hands the rest to the worker.
    public static void onBatteryEvent(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        int rawLevel = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", 100);
        if (rawLevel < 0 || scale <= 0) {
            return;
        }
        final int level = Math.round(rawLevel * 100f / scale);
        final boolean plugged = intent.getIntExtra("plugged", 0) != 0;
        final Context app = context.getApplicationContext();
        final long now = System.currentTimeMillis();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                process(app, now, level, plugged);
            }
        });
    }

    private static synchronized void process(Context context, long now, int level, boolean plugged) {
        restoreIfNeeded(context);
        Integer currentMa = SystemStats.readBatteryCurrentMa();
        Integer voltageMv = SystemStats.readBatteryVoltageMv();
        Integer counterMah = SystemStats.readChargeCounterMah();
        PureCalc.ChargeSample sample = new PureCalc.ChargeSample(
                now, level, plugged, currentMa, voltageMv, counterMah);
        boolean wasOpen = tracker.isOpen();
        PureCalc.ChargeSessionSummary done = tracker.onEvent(sample);
        if (wasOpen || tracker.isOpen() || done != null) {
            appendEvent(context, sample);
        }
        if (done != null) {
            writeSummary(context, done);
            currentFile(context).delete();
            prune(context);
            DiagLog.log(context, "BATT", "charge session finished: "
                    + done.startLevel + "% -> " + done.endLevel + "%, "
                    + (done.durationMs() / 60000) + " min, avg "
                    + (Double.isNaN(done.avgCurrentMa) ? "n/a"
                            : Math.round(done.avgCurrentMa) + " mA")
                    + ", +" + (done.mahAdded >= 0 ? done.mahAdded + " mAh" : "n/a"));
        }
    }

    // The session in progress right now, or null. Replays the open
    // session's saved events first when this process has not fed
    // the tracker yet (the service may have restarted).
    public static synchronized Record current(Context context) {
        restoreIfNeeded(context);
        PureCalc.ChargeSessionSummary summary =
                tracker.currentSummary(System.currentTimeMillis());
        return summary == null ? null : Record.fromSummary(summary);
    }

    // Finished sessions, newest first. Unparseable files are
    // skipped rather than breaking the whole list.
    public static List<Record> list(Context context) {
        List<Record> records = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) {
            return records;
        }
        for (File file : files) {
            if (!file.getName().startsWith("session_") || !file.getName().endsWith(".csv")) {
                continue;
            }
            Record record = load(file);
            if (record != null) {
                records.add(record);
            }
        }
        records.sort((a, b) -> Long.compare(b.startMs, a.startMs));
        return records;
    }

    private static Record load(File file) {
        long startMs = -1;
        long endMs = -1;
        int from = -1;
        int to = -1;
        double avgMa = Double.NaN;
        double avgW = Double.NaN;
        int mah = -1;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#start,")) {
                    Long v = PureCalc.parseRawLong(line.substring("#start,".length()));
                    if (v != null) {
                        startMs = v;
                    }
                } else if (line.startsWith("#end,")) {
                    Long v = PureCalc.parseRawLong(line.substring("#end,".length()));
                    if (v != null) {
                        endMs = v;
                    }
                } else if (line.startsWith("#from,")) {
                    Long v = PureCalc.parseRawLong(line.substring("#from,".length()));
                    if (v != null) {
                        from = v.intValue();
                    }
                } else if (line.startsWith("#to,")) {
                    Long v = PureCalc.parseRawLong(line.substring("#to,".length()));
                    if (v != null) {
                        to = v.intValue();
                    }
                } else if (line.startsWith("#avgma,")) {
                    String v = line.substring("#avgma,".length()).trim();
                    if (!v.isEmpty()) {
                        avgMa = Double.parseDouble(v);
                    }
                } else if (line.startsWith("#avgw,")) {
                    String v = line.substring("#avgw,".length()).trim();
                    if (!v.isEmpty()) {
                        avgW = Double.parseDouble(v);
                    }
                } else if (line.startsWith("#mah,")) {
                    Long v = PureCalc.parseRawLong(line.substring("#mah,".length()));
                    if (v != null) {
                        mah = v.intValue();
                    }
                }
            }
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
        if (startMs < 0 || from < 0 || to < 0) {
            return null;
        }
        if (endMs < startMs) {
            endMs = startMs;
        }
        return new Record(startMs, endMs, from, to, avgMa, avgW, mah, false);
    }

    private static void writeSummary(Context context, PureCalc.ChargeSessionSummary s) {
        File dir = dir(context);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "session_" + s.startMs + ".csv");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file, false));
            writer.write("#start," + s.startMs);
            writer.newLine();
            writer.write("#end," + s.endMs);
            writer.newLine();
            writer.write("#from," + s.startLevel);
            writer.newLine();
            writer.write("#to," + s.endLevel);
            writer.newLine();
            writer.write("#avgma," + (Double.isNaN(s.avgCurrentMa)
                    ? "" : String.format(java.util.Locale.US, "%.1f", s.avgCurrentMa)));
            writer.newLine();
            writer.write("#avgw," + (Double.isNaN(s.avgPowerW)
                    ? "" : String.format(java.util.Locale.US, "%.2f", s.avgPowerW)));
            writer.newLine();
            writer.write("#mah," + s.mahAdded);
            writer.newLine();
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void appendEvent(Context context, PureCalc.ChargeSample s) {
        File dir = dir(context);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(currentFile(context), true));
            writer.write(s.time + "," + s.level + "," + (s.plugged ? 1 : 0) + ","
                    + (s.currentMa == null ? "" : s.currentMa) + ","
                    + (s.voltageMv == null ? "" : s.voltageMv) + ","
                    + (s.chargeCounterMah == null ? "" : s.chargeCounterMah));
            writer.newLine();
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // Replays the open session's saved events through the tracker
    // once per process, so a service restart mid-charge resumes
    // the session. A session that had actually finished just
    // before the process died (its closing event was saved but the
    // summary never written) is completed here instead of lost.
    private static void restoreIfNeeded(Context context) {
        if (restored) {
            return;
        }
        restored = true;
        File file = currentFile(context);
        if (!file.exists()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(",", -1);
                if (parts.length < 3) {
                    continue;
                }
                Long time = PureCalc.parseRawLong(parts[0]);
                Long level = PureCalc.parseRawLong(parts[1]);
                if (time == null || level == null) {
                    continue;
                }
                PureCalc.ChargeSessionSummary done = tracker.onEvent(new PureCalc.ChargeSample(
                        time, level.intValue(), "1".equals(parts[2].trim()),
                        intOrNull(parts, 3), intOrNull(parts, 4), intOrNull(parts, 5)));
                if (done != null) {
                    writeSummary(context, done);
                    file.delete();
                    prune(context);
                    return;
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
    }

    // Keeps the newest MAX_KEPT_SESSIONS finished sessions.
    public static void prune(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        List<File> finished = new ArrayList<>();
        for (File file : files) {
            if (file.getName().startsWith("session_") && file.getName().endsWith(".csv")) {
                finished.add(file);
            }
        }
        finished.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_KEPT_SESSIONS; i < finished.size(); i++) {
            finished.get(i).delete();
        }
    }

    private static Integer intOrNull(String[] parts, int index) {
        if (index >= parts.length) {
            return null;
        }
        Long value = PureCalc.parseRawLong(parts[index]);
        return value == null ? null : value.intValue();
    }
}
