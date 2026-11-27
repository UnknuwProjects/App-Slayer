package com.appslayer.official;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Pure calculations with no Android dependencies, kept separate so
// they can be exercised by the JVM tests in tests/ before a build.
// The Android classes call these same methods — the tests cover the
// production code, not a copy of it.
public final class PureCalc {

    private PureCalc() {
    }

    // Instantaneous battery power in watts from a signed current
    // (mA) and the battery voltage (mV). The sign follows the
    // current: negative while discharging, positive while charging.
    public static double powerWatts(int signedCurrentMa, int voltageMv) {
        return (signedCurrentMa / 1000.0d) * (voltageMv / 1000.0d);
    }

    // The notification prints the magnitude with one decimal
    // ("4.4 W"); the direction is already in the status word.
    public static String formatWattsMagnitude(double watts) {
        return String.format(Locale.US, "%.1f W", Math.abs(watts));
    }

    // Voltage for display, two decimals ("4.17 V").
    public static String formatVolts(int voltageMv) {
        return String.format(Locale.US, "%.2f V", voltageMv / 1000.0d);
    }

    // Thermal zones report millidegrees on most kernels and plain
    // degrees on a few. Convert only when the raw value looks like
    // millidegrees.
    public static int thermalToCelsius(int raw) {
        return Math.abs(raw) >= 1000 ? raw / 1000 : raw;
    }

    // Same reading kept at full precision for the combined overlay
    // chip, which prints one decimal ("33.5°C").
    public static double thermalToCelsiusDecimal(int raw) {
        return Math.abs(raw) >= 1000 ? raw / 1000.0d : raw;
    }

    // A sysfs capacity value may be microamp-hours or, on some ROMs,
    // plain milliamp-hours already. Values that look like µAh are
    // converted; the result must land in a plausible phone range or
    // it is rejected (null), never silently used as data.
    public static Integer capacityToMah(long raw) {
        if (raw <= 0) {
            return null;
        }
        long mah = raw > 100000 ? raw / 1000 : raw;
        return (mah >= 500 && mah <= 30000) ? (int) mah : null;
    }

    // Parses a raw sysfs string to a number. Garbage, empty and
    // missing readings come back null — invalid, never 0-as-data.
    public static Long parseRawLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // Raw sysfs capacity string -> plausible mAh, or null.
    public static Integer capacityFromRaw(String raw) {
        Long value = parseRawLong(raw);
        return value == null ? null : capacityToMah(value);
    }

    // Battery level slope in %/minute across a window of history
    // samples (parallel arrays, chronological). Null when the window
    // spans less than ~2 minutes or holds fewer than 2 samples.
    public static Double levelSlopePerMinute(long[] timesMs, int[] levels) {
        if (timesMs == null || levels == null || timesMs.length < 2
                || timesMs.length != levels.length) {
            return null;
        }
        long spanMs = timesMs[timesMs.length - 1] - timesMs[0];
        if (spanMs < 120000) {
            return null;
        }
        return (levels[levels.length - 1] - levels[0]) / (spanMs / 60000.0d);
    }

    // Minutes until full at the given slope, or null when the slope
    // is too flat to mean anything.
    public static Double minutesToFull(int levelPercent, double slopePerMinute) {
        if (slopePerMinute < 0.05d || levelPercent >= 100) {
            return null;
        }
        return (100 - levelPercent) / slopePerMinute;
    }

    // Minutes until empty at the given (negative) slope, or null
    // when the slope is too flat to mean anything.
    public static Double minutesToEmpty(int levelPercent, double slopePerMinute) {
        if (slopePerMinute > -0.05d || levelPercent <= 0) {
            return null;
        }
        return levelPercent / -slopePerMinute;
    }

    // Numeric part-by-part version compare ("6.0.1" vs "5.2.0").
    // Returns a negative value when a < b, 0 when equal, positive
    // when a > b. Non-numeric parts count as 0.
    public static int compareVersions(String a, String b) {
        String[] partsA = a.replaceFirst("^[vV]", "").split("\\.");
        String[] partsB = b.replaceFirst("^[vV]", "").split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int va = i < partsA.length ? parsePart(partsA[i]) : 0;
            int vb = i < partsB.length ? parsePart(partsB[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // FPS recorder: value parsing and session statistics.
    // ------------------------------------------------------------------

    // The FPS sampler publishes a display string ("FPS: 60.1", or
    // "FPS: ..." / "FPS: n/a" while it has no reading). The recorder
    // needs the number back. Null when there is no usable reading.
    public static Double parseFpsValue(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("FPS:")) {
            value = value.substring("FPS:".length()).trim();
        }
        try {
            double parsed = Double.parseDouble(value);
            return (Double.isNaN(parsed) || parsed < 0) ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    // Frame time in milliseconds for a frame rate. NaN when the rate
    // is not positive — a stalled or unreadable source has no frame
    // time, and inventing one would poison the smoothness math.
    public static double frameTimeMs(double fps) {
        return fps > 0 ? 1000.0d / fps : Double.NaN;
    }

    // Mean of the values that are present (NaN entries are skipped).
    // NaN when nothing is present.
    public static double average(double[] values) {
        if (values == null) {
            return Double.NaN;
        }
        double sum = 0;
        int count = 0;
        for (double value : values) {
            if (!Double.isNaN(value)) {
                sum += value;
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    public static double minValue(double[] values) {
        if (values == null) {
            return Double.NaN;
        }
        double best = Double.NaN;
        for (double value : values) {
            if (!Double.isNaN(value) && (Double.isNaN(best) || value < best)) {
                best = value;
            }
        }
        return best;
    }

    public static double maxValue(double[] values) {
        if (values == null) {
            return Double.NaN;
        }
        double best = Double.NaN;
        for (double value : values) {
            if (!Double.isNaN(value) && (Double.isNaN(best) || value > best)) {
                best = value;
            }
        }
        return best;
    }

    // Average of the lowest `percent` percent of the valid (positive)
    // FPS samples — the "5% low" / "1% low" figures. Always averages
    // at least one sample. NaN when there are no valid samples.
    public static double lowPercentAverage(double[] fps, double percent) {
        double[] valid = validFps(fps);
        if (valid.length == 0) {
            return Double.NaN;
        }
        java.util.Arrays.sort(valid);
        int count = Math.max(1, (int) Math.ceil(valid.length * percent / 100.0d));
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += valid[i];
        }
        return sum / count;
    }

    // Smoothness: the percentage of samples whose frame time lands
    // within ±20% of the session's median frame time. A locked frame
    // rate scores 100; wild swings score low. NaN with fewer than
    // two valid samples — one frame says nothing about steadiness.
    public static double smoothnessPercent(double[] fps) {
        double[] valid = validFps(fps);
        if (valid.length < 2) {
            return Double.NaN;
        }
        double[] frameTimes = new double[valid.length];
        for (int i = 0; i < valid.length; i++) {
            frameTimes[i] = 1000.0d / valid[i];
        }
        double median = median(frameTimes);
        double low = median * 0.8d;
        double high = median * 1.2d;
        int within = 0;
        for (double frameTime : frameTimes) {
            if (frameTime >= low && frameTime <= high) {
                within++;
            }
        }
        return within * 100.0d / frameTimes.length;
    }

    // The FPS figures a session report shows. Every double is NaN
    // when the session has no valid samples, so the UI prints a dash
    // instead of a made-up number.
    public static class FpsSummary {
        public final int count;
        public final double avg;
        public final double min;
        public final double max;
        public final double low5;
        public final double low1;
        public final double smoothness;

        FpsSummary(int count, double avg, double min, double max,
                   double low5, double low1, double smoothness) {
            this.count = count;
            this.avg = avg;
            this.min = min;
            this.max = max;
            this.low5 = low5;
            this.low1 = low1;
            this.smoothness = smoothness;
        }
    }

    public static FpsSummary summarizeFps(double[] fps) {
        double[] valid = validFps(fps);
        if (valid.length == 0) {
            return new FpsSummary(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double value : valid) {
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new FpsSummary(valid.length, sum / valid.length, min, max,
                lowPercentAverage(fps, 5.0d), lowPercentAverage(fps, 1.0d),
                smoothnessPercent(fps));
    }

    // FPS samples that count as data: present and positive. A 0.0
    // reading means the source saw no presented frames (static
    // screen), which is not a frame rate.
    private static double[] validFps(double[] fps) {
        if (fps == null) {
            return new double[0];
        }
        int count = 0;
        for (double value : fps) {
            if (!Double.isNaN(value) && value > 0) {
                count++;
            }
        }
        double[] valid = new double[count];
        int index = 0;
        for (double value : fps) {
            if (!Double.isNaN(value) && value > 0) {
                valid[index++] = value;
            }
        }
        return valid;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0d;
    }

    // ------------------------------------------------------------------
    // Process monitor and foreground-app detection: parsing of shell
    // output. Both parsers are defensive — ROMs ship different top
    // builds (toybox, busybox, procps) and dumpsys layouts.
    // ------------------------------------------------------------------

    public static class ProcEntry {
        public final String name;
        public final double cpu;

        ProcEntry(String name, double cpu) {
            this.name = name;
            this.cpu = cpu;
        }
    }

    // Parses one `top -b -n 1` snapshot into the busiest processes,
    // highest CPU first, at most `limit` entries. The header row
    // decides which column is %CPU and which is the command, so the
    // toybox and procps layouts both parse. Command paths collapse
    // to their base name. Empty when nothing parses.
    public static List<ProcEntry> parseTopProcesses(String output, int limit) {
        List<ProcEntry> entries = new ArrayList<>();
        if (output == null || limit <= 0) {
            return entries;
        }
        int cpuIndex = -1;
        int commandIndex = -1;
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (cpuIndex < 0) {
                // Header row: find it by its %CPU column. Some toybox
                // builds fuse the state column into it ("S[%CPU]").
                String normalized = line.replace("S[%CPU]", "S %CPU").replace("[%CPU]", "%CPU");
                if (!normalized.contains("%CPU")) {
                    continue;
                }
                String[] header = normalized.split("\\s+");
                for (int i = 0; i < header.length; i++) {
                    if ("%CPU".equals(header[i]) || "CPU%".equals(header[i])) {
                        cpuIndex = i;
                    }
                }
                if (cpuIndex < 0) {
                    continue;
                }
                commandIndex = header.length - 1;
                continue;
            }
            String[] parts = line.split("\\s+", commandIndex + 1);
            if (parts.length <= Math.max(cpuIndex, commandIndex)) {
                continue;
            }
            double cpu;
            try {
                cpu = Double.parseDouble(parts[cpuIndex].replace(',', '.'));
            } catch (Exception e) {
                continue;
            }
            String name = parts[commandIndex].trim();
            int space = name.indexOf(' ');
            if (space > 0) {
                name = name.substring(0, space);
            }
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash < name.length() - 1) {
                name = name.substring(slash + 1);
            }
            if (!name.isEmpty()) {
                entries.add(new ProcEntry(name, cpu));
            }
        }
        entries.sort((a, b) -> Double.compare(b.cpu, a.cpu));
        return entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
    }

    // Overlay text for the process window: one "name 12%" line per
    // process, names clipped so a long package cannot stretch the
    // window. Null when there is nothing to show.
    public static String formatTopProcesses(List<ProcEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ProcEntry entry : entries) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String name = entry.name.length() > 24 ? entry.name.substring(0, 24) : entry.name;
            sb.append(name).append(" ").append(Math.round(entry.cpu)).append("%");
        }
        return sb.toString();
    }

    // The foreground app's package from a dumpsys dump. Tries, in
    // order: the top resumed activity (dumpsys activity activities),
    // a resumed-activity line, then the focused window (dumpsys
    // window windows). Null when no marker matches.
    public static String parseForegroundPackage(String dump) {
        if (dump == null) {
            return null;
        }
        String[] markers = {"topResumedActivity", "ResumedActivity", "mResumedActivity",
                "mCurrentFocus", "mFocusedApp"};
        for (String marker : markers) {
            int at = dump.indexOf(marker);
            while (at >= 0) {
                String pkg = packageAfter(dump, at + marker.length());
                if (pkg != null) {
                    return pkg;
                }
                at = dump.indexOf(marker, at + marker.length());
            }
        }
        return null;
    }

    // Finds the first "package.name/" component after a position —
    // activity and window records both carry the package as the part
    // before the '/' of the component name.
    private static String packageAfter(String text, int from) {
        int end = Math.min(text.length(), from + 300);
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return null;
            }
            if (c == '/') {
                int start = i - 1;
                while (start >= from && isPackageChar(text.charAt(start))) {
                    start--;
                }
                start++;
                if (i - start >= 3 && text.substring(start, i).contains(".")) {
                    return text.substring(start, i);
                }
            }
        }
        return null;
    }

    private static boolean isPackageChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_';
    }

    // One thermal zone as parsed from the root-shell dump below.
    public static class ThermalReading {
        public final int index;
        public final String name;
        public final int tempC;
        public final double tempCDecimal;

        ThermalReading(int index, String name, int tempC, double tempCDecimal) {
            this.index = index;
            this.name = name;
            this.tempC = tempC;
            this.tempCDecimal = tempCDecimal;
        }
    }

    // Parses the batched root-shell thermal dump. The shell emits one
    // line per zone as "index|type|temp" (temp is the raw sysfs
    // value, usually millidegrees). Lines that are malformed, have an
    // empty type or implausible temperature are skipped; the result
    // is sorted by zone index. Null or empty input gives an empty
    // list, never null.
    public static java.util.List<ThermalReading> parseThermalDump(String dump) {
        java.util.List<ThermalReading> readings = new java.util.ArrayList<>();
        if (dump == null) {
            return readings;
        }
        for (String rawLine : dump.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 3) {
                continue;
            }
            try {
                int index = Integer.parseInt(parts[0].trim());
                String name = parts[1].trim();
                int raw = Integer.parseInt(parts[2].trim());
                int celsius = thermalToCelsius(raw);
                if (name.isEmpty() || celsius < -30 || celsius > 150) {
                    continue;
                }
                readings.add(new ThermalReading(index, name, celsius, thermalToCelsiusDecimal(raw)));
            } catch (Exception ignored) {
            }
        }
        readings.sort((a, b) -> Integer.compare(a.index, b.index));
        return readings;
    }

    // Y positions for a vertical stack of overlay windows: the
    // first window sits at startY, each next one below the previous
    // window's measured height plus the gap. Parallel to the input:
    // result[i] is the y for the window whose height is heights[i].
    // The classic windows used to get fixed default offsets (200,
    // 300, 400, …) while the CPU window alone can be nine lines
    // tall, so on the user's phone they all piled up into one
    // unreadable stack.
    public static int[] stackOffsets(int[] heights, int gapPx, int startY) {
        if (heights == null) {
            return new int[0];
        }
        int[] ys = new int[heights.length];
        int cursor = startY;
        for (int i = 0; i < heights.length; i++) {
            ys[i] = cursor;
            cursor += Math.max(0, heights[i]) + Math.max(0, gapPx);
        }
        return ys;
    }

    // The package that held the foreground for the longest total
    // time across a session's app segments (parallel arrays: segment
    // start times and their packages; the last segment runs until
    // endMs). Null when there are no segments. First package wins a
    // tie.
    public static String dominantPackage(long[] segmentStartsMs, String[] packages, long endMs) {
        if (segmentStartsMs == null || packages == null
                || segmentStartsMs.length == 0 || segmentStartsMs.length != packages.length) {
            return null;
        }
        String best = null;
        long bestTotal = -1;
        for (int i = 0; i < packages.length; i++) {
            long total = 0;
            for (int j = 0; j < segmentStartsMs.length; j++) {
                if (packages[j].equals(packages[i])) {
                    long segEnd = j + 1 < segmentStartsMs.length ? segmentStartsMs[j + 1] : endMs;
                    total += Math.max(0, segEnd - segmentStartsMs[j]);
                }
            }
            if (total > bestTotal) {
                bestTotal = total;
                best = packages[i];
            }
        }
        return best;
    }

    // Whether a recording that is currently attributed to
    // currentPackage must end and a new session begin, given a
    // freshly observed foreground package. Split only on a real
    // change: the observation must be a different app, and neither
    // a gap in the observations (null/empty — detection may simply
    // have no answer right now) nor this app itself (the user
    // popping back to stop the recording) ever splits a session.
    // A session with no app yet (currentPackage null) adopts the
    // first observation instead of splitting.
    public static boolean shouldSplitSession(String currentPackage,
                                             String observedPackage,
                                             String ownPackage) {
        if (observedPackage == null || observedPackage.isEmpty()) {
            return false;
        }
        if (currentPackage == null || currentPackage.isEmpty()) {
            return false;
        }
        if (observedPackage.equals(currentPackage)) {
            return false;
        }
        return ownPackage == null || !observedPackage.equals(ownPackage);
    }

    // ------------------------------------------------------------------
    // Chart axes. The session graphs used to pad the raw data range
    // by 8% on both ends, which pushed the FPS axis below zero (a
    // "-3.1" label under a session whose lowest reading was 0). The
    // helpers below give the charts a zero-based axis with a round
    // ceiling, and a zoomed variant for temperature, whose values
    // live in a narrow band far from zero.
    // ------------------------------------------------------------------

    private static final double[] NICE_STEPS = {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};

    // Smallest "nice" number >= value, on a 1/1.5/2/2.5/3/4/5/6/8/10
    // ladder per decade. Non-positive input gives 1.
    public static double niceCeil(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double normalized = value / magnitude;
        for (double step : NICE_STEPS) {
            if (normalized <= step) {
                return step * magnitude;
            }
        }
        return 10 * magnitude;
    }

    // Largest "nice" number <= value, same ladder. Values below 1
    // give 0 — a temperature floor never goes negative.
    public static double niceFloor(double value) {
        if (Double.isNaN(value) || value < 1) {
            return 0;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double normalized = value / magnitude;
        double best = 1;
        for (double step : NICE_STEPS) {
            if (step <= normalized) {
                best = step;
            }
        }
        return best * magnitude;
    }

    // Zero-based axis for FPS and frame time: [0, nice ceiling].
    // The ceiling always leaves headroom — data that lands exactly
    // on a nice number (a locked 60 fps) steps up one rung, so the
    // line never hugs the top edge. Null when the data max is not a
    // real number.
    public static double[] axisRangeZero(double dataMax) {
        if (Double.isNaN(dataMax)) {
            return null;
        }
        double ceil = niceCeil(dataMax);
        if (ceil <= dataMax) {
            ceil = niceCeil(dataMax * 1.05d + 0.5d);
        }
        return new double[]{0, ceil};
    }

    // Zoomed axis for temperature: a nice floor/ceiling pair around
    // the data with a little headroom on both sides, floor clamped
    // at 0. A flat series still gets a visible band. Null when
    // either end is not a real number.
    public static double[] axisRangeZoom(double dataMin, double dataMax) {
        if (Double.isNaN(dataMin) || Double.isNaN(dataMax)) {
            return null;
        }
        double span = dataMax - dataMin;
        double pad = span > 0.001d ? span * 0.15d : Math.max(1.0d, Math.abs(dataMax) * 0.05d);
        double floor = niceFloor(dataMin - pad);
        double ceil = niceCeil(dataMax + pad);
        if (ceil <= floor) {
            ceil = floor + Math.max(1.0d, floor * 0.1d);
        }
        return new double[]{floor, ceil};
    }

    // ------------------------------------------------------------------
    // Charge sessions. The battery logging stream reports plug state
    // and level as they change; a session runs from plug-in to
    // unplug (or to full, whichever comes first). The tracker is a
    // pure state machine over those events so the JVM tests can run
    // the exact production logic over synthetic sequences.
    // ------------------------------------------------------------------

    public static class ChargeSample {
        public final long time;
        public final int level;
        public final boolean plugged;
        public final Integer currentMa;
        public final Integer voltageMv;
        public final Integer chargeCounterMah;

        public ChargeSample(long time, int level, boolean plugged,
                            Integer currentMa, Integer voltageMv, Integer chargeCounterMah) {
            this.time = time;
            this.level = level;
            this.plugged = plugged;
            this.currentMa = currentMa;
            this.voltageMv = voltageMv;
            this.chargeCounterMah = chargeCounterMah;
        }
    }

    public static class ChargeSessionSummary {
        public final long startMs;
        public final long endMs;
        public final int startLevel;
        public final int endLevel;
        public final double avgCurrentMa;
        public final double avgPowerW;
        public final int mahAdded;
        public final boolean inProgress;

        ChargeSessionSummary(long startMs, long endMs, int startLevel, int endLevel,
                             double avgCurrentMa, double avgPowerW, int mahAdded,
                             boolean inProgress) {
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
    }

    public static class ChargeSessionTracker {
        private final List<ChargeSample> samples = new ArrayList<>();
        private boolean open = false;
        // Set when a session closed at 100%: the charger stays in,
        // and the stream keeps reporting "plugged at full" — those
        // events must not open a fresh session every few minutes.
        // Cleared by the first unplugged event.
        private boolean fullLatch = false;

        public boolean isOpen() {
            return open;
        }

        // Feeds one event. Returns the finished session when this
        // event closed one, otherwise null.
        public ChargeSessionSummary onEvent(ChargeSample event) {
            if (event == null) {
                return null;
            }
            if (!event.plugged) {
                fullLatch = false;
                if (open) {
                    ChargeSessionSummary done = summarize(event.time, event.level, false);
                    samples.clear();
                    open = false;
                    return done;
                }
                return null;
            }
            if (!open) {
                if (fullLatch) {
                    return null;
                }
                open = true;
                samples.clear();
                samples.add(event);
                if (event.level >= 100) {
                    return closeAtFull(event);
                }
                return null;
            }
            samples.add(event);
            if (event.level >= 100) {
                return closeAtFull(event);
            }
            return null;
        }

        private ChargeSessionSummary closeAtFull(ChargeSample event) {
            ChargeSessionSummary done = summarize(event.time, event.level, false);
            samples.clear();
            open = false;
            fullLatch = true;
            return done;
        }

        // The in-progress session as of nowMs, or null when no
        // session is open.
        public ChargeSessionSummary currentSummary(long nowMs) {
            if (!open || samples.isEmpty()) {
                return null;
            }
            ChargeSample last = samples.get(samples.size() - 1);
            return summarize(nowMs, last.level, true);
        }

        private ChargeSessionSummary summarize(long endMs, int endLevel, boolean inProgress) {
            ChargeSample first = samples.get(0);
            ChargeSample last = samples.get(samples.size() - 1);
            // Time-weighted averages: each sample's reading stands
            // until the next sample (or the session end). Spot
            // readings from a broadcast stream are all we have, and
            // weighting by the interval they covered is the honest
            // average of them.
            double currentWeighted = 0;
            double currentWeight = 0;
            double powerWeighted = 0;
            double powerWeight = 0;
            for (int i = 0; i < samples.size(); i++) {
                ChargeSample s = samples.get(i);
                long until = i + 1 < samples.size() ? samples.get(i + 1).time : endMs;
                double weight = Math.max(0, until - s.time);
                if (weight <= 0) {
                    continue;
                }
                if (s.currentMa != null) {
                    currentWeighted += s.currentMa * weight;
                    currentWeight += weight;
                    if (s.voltageMv != null && s.voltageMv > 0) {
                        powerWeighted += powerWatts(s.currentMa, s.voltageMv) * weight;
                        powerWeight += weight;
                    }
                }
            }
            double avgCurrent = currentWeight > 0 ? currentWeighted / currentWeight : Double.NaN;
            double avgPower = powerWeight > 0 ? powerWeighted / powerWeight : Double.NaN;
            // mAh that actually entered the battery: the charge
            // counter's own delta when it answered at both ends of
            // the session; otherwise the average current integrated
            // over the session's length.
            int mahAdded = -1;
            Integer firstCounter = null;
            Integer lastCounter = null;
            for (ChargeSample s : samples) {
                if (s.chargeCounterMah != null && s.chargeCounterMah > 0) {
                    if (firstCounter == null) {
                        firstCounter = s.chargeCounterMah;
                    }
                    lastCounter = s.chargeCounterMah;
                }
            }
            if (firstCounter != null && lastCounter != null && lastCounter >= firstCounter) {
                mahAdded = lastCounter - firstCounter;
            } else if (!Double.isNaN(avgCurrent) && endMs > first.time) {
                mahAdded = (int) Math.round(avgCurrent * (endMs - first.time) / 3600000.0d);
            }
            return new ChargeSessionSummary(first.time, endMs, first.level, endLevel,
                    avgCurrent, avgPower, mahAdded, inProgress);
        }
    }
}
