package com.appslayer.official;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

// One recorded FPS session, stored as CSV under
// filesDir/fps_sessions/. Layout:
//   #start,<millis>                      session start
//   #app,<millis>,<package>              foreground app segment
//   <millis>,<fps>,<cpuTemp>,<gpuTemp>,<battLevel>,<currentMa>,<voltageMv>
//   #end,<millis>                        session stop (6.3.7+)
// Empty fields mean that reading was unavailable for that sample.
// While recording, the file carries a ".tmp" suffix and only gets
// its final ".csv" name when the recording stops, so the sessions
// list never shows a half-written session. A finished session with
// a "#start" but zero samples is a real session (a recording whose
// FPS sources never answered): it loads with an empty sample list
// and the UI marks it as no-data instead of hiding it.
public class FpsSession {

    public static final String DIR_NAME = "fps_sessions";
    public static final int MAX_KEPT_SESSIONS = 20;

    public final File file;
    public final long startTimeMs;
    public final long endTimeMs;
    public final String packageName;
    public final List<Sample> samples;

    public static class Sample {
        public final long time;
        public final double fps;
        public final double cpuTemp;
        public final double gpuTemp;
        public final int level;
        public final Integer currentMa;
        public final Integer voltageMv;

        Sample(long time, double fps, double cpuTemp, double gpuTemp,
               int level, Integer currentMa, Integer voltageMv) {
            this.time = time;
            this.fps = fps;
            this.cpuTemp = cpuTemp;
            this.gpuTemp = gpuTemp;
            this.level = level;
            this.currentMa = currentMa;
            this.voltageMv = voltageMv;
        }
    }

    private FpsSession(File file, long startTimeMs, long endTimeMs,
                       String packageName, List<Sample> samples) {
        this.file = file;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.packageName = packageName;
        this.samples = samples;
    }

    public static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    public long durationMs() {
        return Math.max(0, endTimeMs - startTimeMs);
    }

    // FPS values of every sample, in order (NaN-free: a sample only
    // exists because an FPS reading existed; 0.0 readings are kept
    // and PureCalc decides what counts as data).
    public double[] fpsArray() {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            out[i] = samples.get(i).fps;
        }
        return out;
    }

    public double[] cpuTempArray() {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            out[i] = samples.get(i).cpuTemp;
        }
        return out;
    }

    public double[] gpuTempArray() {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            out[i] = samples.get(i).gpuTemp;
        }
        return out;
    }

    // Per-sample power in watts, NaN where current or voltage was
    // not recorded for that sample.
    public double[] powerArray() {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            out[i] = s.currentMa != null && s.voltageMv != null
                    ? PureCalc.powerWatts(s.currentMa, s.voltageMv)
                    : Double.NaN;
        }
        return out;
    }

    // Frame times in ms, NaN where the FPS reading was not positive.
    public double[] frameTimeArray() {
        double[] out = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            out[i] = PureCalc.frameTimeMs(samples.get(i).fps);
        }
        return out;
    }

    public long[] timeArray() {
        long[] out = new long[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            out[i] = samples.get(i).time;
        }
        return out;
    }

    // All finished sessions, newest first. Unparseable files are
    // skipped rather than breaking the whole list.
    public static List<FpsSession> list(Context context) {
        List<FpsSession> sessions = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) {
            return sessions;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".csv")) {
                continue;
            }
            FpsSession session = load(file);
            if (session != null) {
                sessions.add(session);
            }
        }
        sessions.sort((a, b) -> Long.compare(b.startTimeMs, a.startTimeMs));
        return sessions;
    }

    public static FpsSession load(File file) {
        List<Sample> samples = new ArrayList<>();
        List<Long> segmentStarts = new ArrayList<>();
        List<String> segmentPackages = new ArrayList<>();
        long startMs = -1;
        long endMs = -1;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("#start,")) {
                    Long value = PureCalc.parseRawLong(line.substring("#start,".length()));
                    if (value != null) {
                        startMs = value;
                    }
                    continue;
                }
                if (line.startsWith("#end,")) {
                    Long value = PureCalc.parseRawLong(line.substring("#end,".length()));
                    if (value != null) {
                        endMs = value;
                    }
                    continue;
                }
                if (line.startsWith("#app,")) {
                    String[] parts = line.split(",", 3);
                    if (parts.length == 3) {
                        Long at = PureCalc.parseRawLong(parts[1]);
                        if (at != null && !parts[2].trim().isEmpty()) {
                            segmentStarts.add(at);
                            segmentPackages.add(parts[2].trim());
                        }
                    }
                    continue;
                }
                if (line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 2) {
                    continue;
                }
                Long time = PureCalc.parseRawLong(parts[0]);
                Double fps = parseDoubleOrNull(parts[1]);
                if (time == null || fps == null) {
                    continue;
                }
                samples.add(new Sample(time, fps,
                        doubleOrNaN(parts, 2), doubleOrNaN(parts, 3),
                        intOrMinusOne(parts, 4), intOrNull(parts, 5), intOrNull(parts, 6)));
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
        if (samples.isEmpty()) {
            // A no-data session: the recording ran but no FPS
            // source ever answered. It still loads — with its real
            // start/end — so the list can show it marked as no-data
            // instead of the recording vanishing without a trace.
            // Files with neither samples nor a start line are not
            // sessions at all and stay skipped.
            if (startMs < 0) {
                return null;
            }
            if (endMs < startMs) {
                endMs = startMs;
            }
            return new FpsSession(file, startMs, endMs, null, samples);
        }
        if (startMs < 0) {
            startMs = samples.get(0).time;
        }
        if (endMs < 0) {
            endMs = samples.get(samples.size() - 1).time;
        }
        long[] starts = new long[segmentStarts.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = segmentStarts.get(i);
        }
        String dominant = PureCalc.dominantPackage(starts,
                segmentPackages.toArray(new String[0]), endMs);
        return new FpsSession(file, startMs, endMs, dominant, samples);
    }

    // Keeps the newest MAX_KEPT_SESSIONS finished sessions and drops
    // the rest, plus any stale ".tmp" files from a recording that
    // never got to stop cleanly.
    public static void prune(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        List<File> finished = new ArrayList<>();
        for (File file : files) {
            if (file.getName().endsWith(".tmp")) {
                file.delete();
            } else if (file.getName().endsWith(".csv")) {
                finished.add(file);
            }
        }
        finished.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_KEPT_SESSIONS; i < finished.size(); i++) {
            finished.get(i).delete();
        }
    }

    private static Double parseDoubleOrNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static double doubleOrNaN(String[] parts, int index) {
        if (index >= parts.length) {
            return Double.NaN;
        }
        Double value = parseDoubleOrNull(parts[index]);
        return value == null ? Double.NaN : value;
    }

    private static Integer intOrNull(String[] parts, int index) {
        if (index >= parts.length) {
            return null;
        }
        Long value = PureCalc.parseRawLong(parts[index]);
        return value == null ? null : value.intValue();
    }

    private static int intOrMinusOne(String[] parts, int index) {
        Integer value = intOrNull(parts, index);
        return value == null ? -1 : value;
    }
}
