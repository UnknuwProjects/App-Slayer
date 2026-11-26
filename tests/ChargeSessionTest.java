import com.appslayer.official.PureCalc;

// JVM tests for the charge-session state machine: the same
// PureCalc tracker the battery logging service feeds with real
// ACTION_BATTERY_CHANGED events. Sequences are synthetic with
// hand-computed expectations.
public class ChargeSessionTest {

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

    private static PureCalc.ChargeSample ev(long time, int level, boolean plugged,
                                            Integer ma, Integer mv, Integer counter) {
        return new PureCalc.ChargeSample(time, level, plugged, ma, mv, counter);
    }

    public static void main(String[] args) {
        // (a) The headline case: plug in at 40%, charge to 96%,
        // unplug. One session, sane duration and averages; the mAh
        // added comes from the charge counter's own delta.
        PureCalc.ChargeSessionTracker tracker = new PureCalc.ChargeSessionTracker();
        check("discharge event opens nothing",
                tracker.onEvent(ev(0, 40, false, null, null, null)) == null);
        check("plug-in opens, no finish yet",
                tracker.onEvent(ev(60000, 40, true, 1500, 4000, 2000)) == null);
        check("session open after plug-in", tracker.isOpen());
        tracker.onEvent(ev(660000, 55, true, 1500, 4100, 2250));
        tracker.onEvent(ev(1260000, 70, true, 1500, 4200, 2500));
        tracker.onEvent(ev(1860000, 85, true, 1400, 4200, 2750));
        tracker.onEvent(ev(2460000, 96, true, 1200, 4300, 2960));
        PureCalc.ChargeSessionSummary done =
                tracker.onEvent(ev(2520000, 96, false, null, null, null));
        check("unplug finishes the session", done != null);
        check("session closed after unplug", !tracker.isOpen());
        if (done != null) {
            check("start level 40", done.startLevel == 40);
            check("end level 96", done.endLevel == 96);
            check("start time is the plug-in", done.startMs == 60000);
            check("duration 41 min", done.durationMs() == 2460000);
            // Time-weighted: 1500 mA for 30 min, 1400 for 10 min,
            // 1200 for the final minute.
            checkClose("avg current", done.avgCurrentMa, 1468.3, 1.0);
            checkClose("avg power", done.avgPowerW, 6.06, 0.02);
            check("mAh added from charge counter", done.mahAdded == 960);
            check("finished session is not in progress", !done.inProgress);
        }

        // (b) No charge counter: the mAh estimate integrates the
        // average current over the session length instead.
        PureCalc.ChargeSessionTracker noCounter = new PureCalc.ChargeSessionTracker();
        noCounter.onEvent(ev(0, 20, true, 2000, 4000, null));
        noCounter.onEvent(ev(1800000, 50, true, 2000, 4000, null));
        PureCalc.ChargeSessionSummary integrated =
                noCounter.onEvent(ev(1860000, 50, false, null, null, null));
        check("no-counter session finishes", integrated != null);
        if (integrated != null) {
            checkClose("no-counter avg current", integrated.avgCurrentMa, 2000, 0.001);
            check("no-counter mAh integrated", integrated.mahAdded == 1033);
        }

        // (c) A pure discharge interval never produces a session.
        PureCalc.ChargeSessionTracker discharging = new PureCalc.ChargeSessionTracker();
        check("discharge 80 -> nothing", discharging.onEvent(ev(0, 80, false, null, null, null)) == null);
        check("discharge 75 -> nothing", discharging.onEvent(ev(600000, 75, false, null, null, null)) == null);
        check("discharge 70 -> nothing", discharging.onEvent(ev(1200000, 70, false, null, null, null)) == null);
        check("discharge never opens", !discharging.isOpen());
        check("discharge has no current summary", discharging.currentSummary(1800000) == null);

        // (d) Reaching full closes the session, and the plugged-at-
        // full events that follow do not reopen one every few
        // minutes. After a real unplug, a new plug-in starts fresh.
        PureCalc.ChargeSessionTracker full = new PureCalc.ChargeSessionTracker();
        full.onEvent(ev(0, 98, true, 800, 4400, 4800));
        PureCalc.ChargeSessionSummary atFull = full.onEvent(ev(300000, 100, true, 300, 4400, 5000));
        check("reaching full finishes the session", atFull != null);
        if (atFull != null) {
            check("full session ends at 100", atFull.endLevel == 100);
        }
        check("still plugged at full opens nothing",
                full.onEvent(ev(600000, 100, true, 100, 4400, 5000)) == null);
        check("not open while latched at full", !full.isOpen());
        check("unplug while latched finishes nothing",
                full.onEvent(ev(700000, 100, false, null, null, null)) == null);
        check("plug-in after unplug opens again",
                full.onEvent(ev(800000, 60, true, 1500, 4000, 3000)) == null && full.isOpen());

        // (e) The in-progress view: duration so far, levels so far,
        // averages over the samples collected up to now.
        PureCalc.ChargeSessionTracker live = new PureCalc.ChargeSessionTracker();
        live.onEvent(ev(0, 40, true, 1000, 4000, null));
        live.onEvent(ev(600000, 46, true, 1000, 4000, null));
        PureCalc.ChargeSessionSummary current = live.currentSummary(900000);
        check("in-progress summary exists", current != null);
        if (current != null) {
            check("in-progress flagged", current.inProgress);
            check("in-progress start level", current.startLevel == 40);
            check("in-progress end level so far", current.endLevel == 46);
            check("in-progress duration so far", current.durationMs() == 900000);
            checkClose("in-progress avg current", current.avgCurrentMa, 1000, 0.001);
        }

        // (f) A session whose current never answered: honest NaNs
        // and an unknown mAh, not invented numbers.
        PureCalc.ChargeSessionTracker silent = new PureCalc.ChargeSessionTracker();
        silent.onEvent(ev(0, 30, true, null, null, null));
        silent.onEvent(ev(600000, 40, true, null, null, null));
        PureCalc.ChargeSessionSummary noData = silent.onEvent(ev(660000, 40, false, null, null, null));
        check("silent session finishes", noData != null);
        if (noData != null) {
            check("silent avg current NaN", Double.isNaN(noData.avgCurrentMa));
            check("silent avg power NaN", Double.isNaN(noData.avgPowerW));
            check("silent mAh unknown", noData.mahAdded == -1);
            check("silent levels still tracked", noData.startLevel == 30 && noData.endLevel == 40);
        }

        System.out.println("ChargeSessionTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
