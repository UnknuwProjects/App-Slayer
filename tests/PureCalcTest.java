import com.appslayer.official.PureCalc;

// Hand-rolled JVM tests for the pure logic in PureCalc, run before
// every build (see run_tests.sh). The cases use the user's real
// device data where possible: -1048 mA at 4.17 V from his 5.2.0
// notification screenshot, +903 mA from his 6.0.1 battery dialog.
public class PureCalcTest {

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
                Math.abs(actual - expected) <= tolerance);
    }

    public static void main(String[] args) {
        // (a) Power computation.
        double discharge = PureCalc.powerWatts(-1048, 4170);
        checkClose("power discharging -1048mA x 4170mV", discharge, -4.37, 0.01);
        check("power discharging is negative", discharge < 0);
        check("notification magnitude format is 4.4 W",
                "4.4 W".equals(PureCalc.formatWattsMagnitude(discharge)));
        double charging = PureCalc.powerWatts(903, 4200);
        checkClose("power charging +903mA x 4200mV", charging, 3.79, 0.01);
        check("power charging is positive", charging > 0);
        check("volts format is 4.17 V", "4.17 V".equals(PureCalc.formatVolts(4170)));

        // (b) Sysfs parsing and unit sanity.
        check("millidegrees 33500 -> 33 C integer", PureCalc.thermalToCelsius(33500) == 33);
        checkClose("millidegrees 33500 -> 33.5 C decimal",
                PureCalc.thermalToCelsiusDecimal(33500), 33.5, 0.001);
        check("millidegrees 61000 -> 61 C", PureCalc.thermalToCelsius(61000) == 61);
        check("plain degrees 42 left alone", PureCalc.thermalToCelsius(42) == 42);
        check("uAh 9320000 -> 9320 mAh",
                Integer.valueOf(9320).equals(PureCalc.capacityFromRaw("9320000")));
        check("already-mAh 5000 left alone",
                Integer.valueOf(5000).equals(PureCalc.capacityFromRaw("5000")));
        check("zero capacity invalid", PureCalc.capacityFromRaw("0") == null);
        check("negative capacity invalid", PureCalc.capacityFromRaw("-1200") == null);
        check("tiny capacity 200 invalid", PureCalc.capacityFromRaw("200") == null);
        check("huge capacity 99999999 invalid", PureCalc.capacityFromRaw("99999999") == null);
        check("garbage capacity invalid", PureCalc.capacityFromRaw("abc") == null);
        check("empty capacity invalid", PureCalc.capacityFromRaw("") == null);
        check("missing capacity invalid", PureCalc.capacityFromRaw(null) == null);
        check("whitespace capacity parses",
                Integer.valueOf(4500).equals(PureCalc.capacityFromRaw(" 4500000\n")));

        // (c) Level-slope time estimates.
        long[] times = new long[6];
        int[] chargingLevels = new int[6];
        int[] dischargingLevels = new int[6];
        for (int i = 0; i < 6; i++) {
            times[i] = i * 120000L; // one sample every 2 minutes, 10 minutes total
            chargingLevels[i] = 50 + i;
            dischargingLevels[i] = 80 - i;
        }
        Double chargeSlope = PureCalc.levelSlopePerMinute(times, chargingLevels);
        check("charging slope computed", chargeSlope != null);
        checkClose("charging slope +0.5 %/min", chargeSlope, 0.5, 0.001);
        Double toFull = PureCalc.minutesToFull(55, chargeSlope);
        check("time-to-full computed", toFull != null);
        checkClose("time-to-full 90 min from 55%", toFull, 90.0, 0.01);
        Double drainSlope = PureCalc.levelSlopePerMinute(times, dischargingLevels);
        checkClose("discharging slope -0.5 %/min", drainSlope, -0.5, 0.001);
        Double toEmpty = PureCalc.minutesToEmpty(75, drainSlope);
        check("time-left computed", toEmpty != null);
        checkClose("time-left 150 min from 75%", toEmpty, 150.0, 0.01);
        long[] shortTimes = {0L, 30000L};
        int[] shortLevels = {50, 51};
        check("too-short window -> no slope",
                PureCalc.levelSlopePerMinute(shortTimes, shortLevels) == null);
        check("flat slope -> no time-to-full", PureCalc.minutesToFull(50, 0.0) == null);
        check("flat slope -> no time-left", PureCalc.minutesToEmpty(50, 0.0) == null);
        check("full battery -> no time-to-full", PureCalc.minutesToFull(100, 0.5) == null);

        // (d) Version compare (regression from the 6.0.1 update fix).
        check("5.2.0 < 6.0.1", PureCalc.compareVersions("5.2.0", "6.0.1") < 0);
        check("6.0.1 < 6.2.0", PureCalc.compareVersions("6.0.1", "6.2.0") < 0);
        check("6.2.0 == 6.2.0 (no prompt)", PureCalc.compareVersions("6.2.0", "6.2.0") == 0);
        check("v-prefix tolerated", PureCalc.compareVersions("v6.2.0", "6.2.0") == 0);
        check("6.2.0 > 6.0.1", PureCalc.compareVersions("6.2.0", "6.0.1") > 0);

        System.out.println("PureCalcTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
