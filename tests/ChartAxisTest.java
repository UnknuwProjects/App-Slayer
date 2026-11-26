import com.appslayer.official.PureCalc;

// JVM tests for the chart axis helpers. The old graph padded the
// raw data range by 8% on both ends, which pushed the FPS axis
// below zero (the user's report showed a "-3.1" label under a
// session whose lowest reading was 0). These cases pin the new
// behaviour: zero-based axes with round ceilings for FPS and frame
// time, and a zoomed, never-negative axis for temperature.
public class ChartAxisTest {

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

    private static void checkRange(String name, double[] range, double min, double max) {
        check(name + " (got " + (range == null ? "null"
                        : "[" + range[0] + ", " + range[1] + "]")
                        + ", want [" + min + ", " + max + "])",
                range != null && range[0] == min && range[1] == max);
    }

    public static void main(String[] args) {
        // Nice-number ladder.
        check("niceCeil(39) = 40", PureCalc.niceCeil(39) == 40);
        check("niceCeil(42.1) = 50", PureCalc.niceCeil(42.1) == 50);
        check("niceCeil(60) = 60", PureCalc.niceCeil(60) == 60);
        check("niceCeil(0) = 1", PureCalc.niceCeil(0) == 1);
        check("niceCeil(NaN) = 1", PureCalc.niceCeil(Double.NaN) == 1);
        check("niceFloor(37.1) = 30", PureCalc.niceFloor(37.1) == 30);
        check("niceFloor(-5) = 0", PureCalc.niceFloor(-5) == 0);

        // Steady 60 fps: the data max lands exactly on a nice
        // number, so the ceiling steps up one rung for headroom.
        checkRange("steady 60 fps axis", PureCalc.axisRangeZero(60), 0, 80);
        // Dips to 20 with a 60 peak scale the same way.
        checkRange("60 peak with dips axis", PureCalc.axisRangeZero(60), 0, 80);
        // The user's tiny 5-sample session: readings 31..39 (and
        // 0.0 for static frames). The old code labelled the bottom
        // -3.1; the axis must now be exactly [0, 40].
        checkRange("tiny session axis", PureCalc.axisRangeZero(39), 0, 40);
        // An all-equal-values session still gets a sane axis.
        checkRange("all-equal 45 axis", PureCalc.axisRangeZero(45), 0, 50);
        // Frame times: a 33.3 ms ceiling rounds to 40, never below 0.
        checkRange("frame time axis", PureCalc.axisRangeZero(33.3), 0, 40);
        // A flat 0 session (screen never moved) still draws.
        checkRange("zero axis", PureCalc.axisRangeZero(0), 0, 1);
        // High-refresh peaks.
        checkRange("150 fps axis", PureCalc.axisRangeZero(150), 0, 200);
        check("NaN data max -> null range", PureCalc.axisRangeZero(Double.NaN) == null);

        // Temperature zoom: a 38..44 band gets a [30, 50] window,
        // a flat 41 gets the same neighbourhood, and the floor can
        // never go negative.
        checkRange("temp band axis", PureCalc.axisRangeZoom(38, 44), 30, 50);
        checkRange("temp flat axis", PureCalc.axisRangeZoom(41, 41), 30, 50);
        double[] cold = PureCalc.axisRangeZoom(2, 5);
        check("temp floor never negative", cold != null && cold[0] >= 0 && cold[0] < 2 && cold[1] > 5);
        check("zoom NaN -> null", PureCalc.axisRangeZoom(Double.NaN, 40) == null);

        System.out.println("ChartAxisTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
