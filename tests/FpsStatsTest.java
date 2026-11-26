import com.appslayer.official.PureCalc;

import java.util.List;

// JVM tests for the FPS recorder statistics and the shell-output
// parsers, run before every build (see run_tests.sh). The series
// are synthetic with hand-computed expectations; the top dumps are
// shaped like real toybox and procps output.
public class FpsStatsTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void checkClose(String name, double actual, double expected, double tolerance) {
        check(name + " (got " + actual + ", want ~" + expected + ")",
                !Double.isNaN(actual) && Math.abs(actual - expected) <= tolerance);
    }

    private static double[] filled(int count, double value) {
        double[] out = new double[count];
        java.util.Arrays.fill(out, value);
        return out;
    }

    public static void main(String[] args) {
        // (a) FPS string parsing.
        checkClose("parse 'FPS: 60.1'", PureCalc.parseFpsValue("FPS: 60.1"), 60.1, 0.001);
        checkClose("parse 'FPS: 0.0' is a reading", PureCalc.parseFpsValue("FPS: 0.0"), 0.0, 0.001);
        check("parse 'FPS: ...' -> null", PureCalc.parseFpsValue("FPS: ...") == null);
        check("parse 'FPS: n/a' -> null", PureCalc.parseFpsValue("FPS: n/a") == null);
        check("parse null -> null", PureCalc.parseFpsValue(null) == null);
        check("parse garbage -> null", PureCalc.parseFpsValue("hello") == null);

        // (b) Basic stats on a known series.
        PureCalc.FpsSummary trio = PureCalc.summarizeFps(new double[]{30, 60, 90});
        check("trio count", trio.count == 3);
        checkClose("trio avg", trio.avg, 60.0, 0.001);
        checkClose("trio min", trio.min, 30.0, 0.001);
        checkClose("trio max", trio.max, 90.0, 0.001);

        // (c) Steady 60 fps: everything is 60, smoothness is 100.
        PureCalc.FpsSummary steady = PureCalc.summarizeFps(filled(120, 60.0));
        check("steady count", steady.count == 120);
        checkClose("steady avg", steady.avg, 60.0, 0.001);
        checkClose("steady low5", steady.low5, 60.0, 0.001);
        checkClose("steady low1", steady.low1, 60.0, 0.001);
        checkClose("steady smoothness 100", steady.smoothness, 100.0, 0.001);

        // (d) Lowest-percent bands: 95 samples at 60, 5 at 20.
        double[] band = filled(100, 60.0);
        for (int i = 0; i < 5; i++) {
            band[i] = 20.0;
        }
        PureCalc.FpsSummary bandSummary = PureCalc.summarizeFps(band);
        checkClose("band low5 is the 20 fps dip", bandSummary.low5, 20.0, 0.001);
        checkClose("band low1 is the 20 fps dip", bandSummary.low1, 20.0, 0.001);
        checkClose("band avg pulled down slightly", bandSummary.avg, 58.0, 0.001);
        // 20 samples: 19 at 60, 1 at 30 -> the lowest 5% is one sample.
        double[] small = filled(20, 60.0);
        small[0] = 30.0;
        checkClose("small low5 single dip", PureCalc.lowPercentAverage(small, 5.0), 30.0, 0.001);
        // Its frame time (33.3 ms) sits outside ±20% of the 16.67 ms
        // median, so smoothness is 19/20 = 95%.
        checkClose("small smoothness 95", PureCalc.smoothnessPercent(small), 95.0, 0.001);

        // (e) Jittery series: alternating 30/120 fps. Frame times
        // 33.3 and 8.3 ms, median ~20.8 ms, ±20% band [16.7, 25.0]
        // holds neither -> smoothness 0.
        double[] jitter = new double[100];
        for (int i = 0; i < jitter.length; i++) {
            jitter[i] = i % 2 == 0 ? 30.0 : 120.0;
        }
        double jitterSmoothness = PureCalc.smoothnessPercent(jitter);
        check("jitter smoothness computed", !Double.isNaN(jitterSmoothness));
        checkClose("jitter smoothness 0", jitterSmoothness, 0.0, 0.001);
        check("jitter smoother than nothing, rougher than steady",
                jitterSmoothness < PureCalc.smoothnessPercent(filled(100, 60.0)));

        // (f) Frame time conversion.
        checkClose("frame time at 60 fps", PureCalc.frameTimeMs(60.0), 16.667, 0.01);
        checkClose("frame time at 30 fps", PureCalc.frameTimeMs(30.0), 33.333, 0.01);
        check("frame time at 0 fps is NaN", Double.isNaN(PureCalc.frameTimeMs(0.0)));
        check("frame time at negative fps is NaN", Double.isNaN(PureCalc.frameTimeMs(-30.0)));

        // (g) Edge cases: empty, null, single sample, dead readings.
        PureCalc.FpsSummary empty = PureCalc.summarizeFps(new double[0]);
        check("empty count 0", empty.count == 0);
        check("empty avg NaN", Double.isNaN(empty.avg));
        check("empty low5 NaN", Double.isNaN(empty.low5));
        check("empty smoothness NaN", Double.isNaN(empty.smoothness));
        PureCalc.FpsSummary nullSummary = PureCalc.summarizeFps(null);
        check("null count 0", nullSummary.count == 0);
        PureCalc.FpsSummary single = PureCalc.summarizeFps(new double[]{45.0});
        check("single count 1", single.count == 1);
        checkClose("single avg", single.avg, 45.0, 0.001);
        checkClose("single low5", single.low5, 45.0, 0.001);
        check("single smoothness NaN (needs 2+)", Double.isNaN(single.smoothness));
        PureCalc.FpsSummary dead = PureCalc.summarizeFps(new double[]{0.0, -5.0, 60.0});
        check("zero/negative readings ignored", dead.count == 1);
        checkClose("dead avg is the one live reading", dead.avg, 60.0, 0.001);

        // (h) Averages with holes (temps / power columns).
        checkClose("average skips NaN", PureCalc.average(new double[]{40, Double.NaN, 50}), 45.0, 0.001);
        check("average of nothing is NaN", Double.isNaN(PureCalc.average(new double[]{Double.NaN})));
        checkClose("min skips NaN", PureCalc.minValue(new double[]{Double.NaN, 33, 47}), 33.0, 0.001);
        checkClose("max skips NaN", PureCalc.maxValue(new double[]{Double.NaN, 33, 47}), 47.0, 0.001);

        // (i) top parsing, toybox layout (Android's own top).
        String toybox = "Tasks: 312 total,   1 running, 311 sleeping\n"
                + "  PID USER         PR  NI VIRT  RES  SHR S %CPU  %MEM     TIME+ ARGS\n"
                + " 1234 u0_a123      10 -10  15G 200M 100M S 25.0   2.0   10:00.00 com.example.game\n"
                + "  567 system       20   0 5.0G  90M  50M S 10.5   0.9    5:00.00 system_server\n"
                + "  890 root         20   0 2.0G  10M   5M R  3.0   0.1    0:30.00 /system/bin/surfaceflinger\n";
        List<PureCalc.ProcEntry> toy = PureCalc.parseTopProcesses(toybox, 5);
        check("toybox parses 3 processes", toy.size() == 3);
        check("toybox busiest first", toy.get(0).name.equals("com.example.game"));
        checkClose("toybox busiest cpu", toy.get(0).cpu, 25.0, 0.001);
        check("toybox path collapsed to base name", toy.get(2).name.equals("surfaceflinger"));
        check("toybox limit honored", PureCalc.parseTopProcesses(toybox, 2).size() == 2);
        String formatted = PureCalc.formatTopProcesses(toy);
        check("format lines", "com.example.game 25%\nsystem_server 11%\nsurfaceflinger 3%".equals(formatted));

        // (j) top parsing, procps layout.
        String procps = "top - 15:00:00 up 1 day,  2:00,  0 users\n"
                + "    PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND\n"
                + "   2000 user      20   0 1000000  50000  20000 S  42.0   1.0   1:00.00 com.game.two\n"
                + "   2001 user      20   0  900000  40000  10000 S   1.5   0.5   0:10.00 com.game.idle\n";
        List<PureCalc.ProcEntry> proc = PureCalc.parseTopProcesses(procps, 5);
        check("procps parses 2 processes", proc.size() == 2);
        check("procps busiest first", proc.get(0).name.equals("com.game.two"));
        checkClose("procps busiest cpu", proc.get(0).cpu, 42.0, 0.001);
        check("empty top output -> empty list", PureCalc.parseTopProcesses("", 5).isEmpty());
        check("null top output -> empty list", PureCalc.parseTopProcesses(null, 5).isEmpty());
        check("garbage top output -> empty list", PureCalc.parseTopProcesses("no header here\n1 2 3\n", 5).isEmpty());
        check("format of empty list is null", PureCalc.formatTopProcesses(new java.util.ArrayList<PureCalc.ProcEntry>()) == null);

        // (k) Foreground package parsing.
        check("topResumedActivity package", "com.example.game".equals(PureCalc.parseForegroundPackage(
                "    topResumedActivity=ActivityRecord{abc123 u0 com.example.game/.MainActivity t42}")));
        check("mCurrentFocus package", "com.launcher.three".equals(PureCalc.parseForegroundPackage(
                "  mCurrentFocus=Window{def456 u0 com.launcher.three/com.launcher.three.Home}")));
        check("no marker -> null", PureCalc.parseForegroundPackage("some other dumpsys text") == null);
        check("null dump -> null", PureCalc.parseForegroundPackage(null) == null);

        // (l) Dominant package by foreground time.
        long[] starts = {0L, 10000L, 50000L};
        String[] pkgs = {"com.launcher", "com.example.game", "com.launcher"};
        check("dominant is the game (40s vs 10s+10s)",
                "com.example.game".equals(PureCalc.dominantPackage(starts, pkgs, 60000L)));
        check("dominant of nothing is null", PureCalc.dominantPackage(new long[0], new String[0], 0L) == null);
        check("dominant of null is null", PureCalc.dominantPackage(null, null, 0L) == null);

        System.out.println("FpsStatsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
