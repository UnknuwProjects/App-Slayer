import com.appslayer.official.PureCalc;

import java.util.List;

// JVM tests for PureCalc.parseThermalDump: the parser for the
// batched root-shell thermal enumeration added in 6.3.1. Fixtures
// mimic a real `for d in /sys/class/thermal/thermal_zone*` dump,
// including the broken lines a half-readable zone produces.
public class ThermalParseTest {

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

    public static void main(String[] args) {
        String dump = "2|gpu-thermal|52000\n"
                + "0|cpu-thermal|45231\n"
                + "1|battery|33500\n"
                + "5|quiet-thermal|41\n";
        List<PureCalc.ThermalReading> readings = PureCalc.parseThermalDump(dump);
        check("parses all valid zones", readings.size() == 4);
        check("sorted by zone index", readings.get(0).index == 0
                && readings.get(1).index == 1
                && readings.get(2).index == 2
                && readings.get(3).index == 5);
        check("millidegrees converted", readings.get(0).tempC == 45
                && readings.get(2).tempC == 52);
        check("plain degrees kept as-is", readings.get(3).tempC == 41);
        check("decimal precision kept", Math.abs(readings.get(0).tempCDecimal - 45.231) < 0.001);
        check("names kept", "battery".equals(readings.get(1).name)
                && "cpu-thermal".equals(readings.get(0).name));

        String messy = "0|cpu-thermal|45000\n"
                + "garbage line without pipes\n"
                + "1||38000\n"
                + "2|gpu-thermal|\n"
                + "3|gpu-thermal|notanumber\n"
                + "4|weird|999999\n"
                + "|cpu|45000\n"
                + "6|neg|-25000\n"
                + "\n"
                + "7|ok-zone|30000|extra-field\n";
        List<PureCalc.ThermalReading> messyReadings = PureCalc.parseThermalDump(messy);
        check("messy dump keeps only valid lines", messyReadings.size() == 2);
        check("messy dump keeps the good cpu zone",
                messyReadings.get(0).index == 0 && messyReadings.get(0).tempC == 45);
        check("negative millidegrees survive (cold sensor)",
                messyReadings.get(1).index == 6 && messyReadings.get(1).tempC == -25);

        check("null dump gives empty list", PureCalc.parseThermalDump(null).isEmpty());
        check("empty dump gives empty list", PureCalc.parseThermalDump("").isEmpty());
        check("implausible heat rejected",
                PureCalc.parseThermalDump("0|hot|200000\n").isEmpty());

        System.out.println("ThermalParseTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
