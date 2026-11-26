import com.appslayer.official.PureCalc;

// JVM tests for the classic-overlay stacking math in PureCalc.
// The pile-up case is the user's real one: a nine-line CPU window
// (~430 px tall) with the fixed 100 px default offsets put every
// following window inside it.
public class StackOffsetsTest {

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

    private static void checkArray(String name, int[] actual, int[] expected) {
        boolean equal = actual != null && actual.length == expected.length;
        if (equal) {
            for (int i = 0; i < expected.length; i++) {
                if (actual[i] != expected[i]) {
                    equal = false;
                    break;
                }
            }
        }
        check(name, equal);
    }

    public static void main(String[] args) {
        checkArray("null heights -> empty",
                PureCalc.stackOffsets(null, 8, 200), new int[0]);
        checkArray("no windows -> empty",
                PureCalc.stackOffsets(new int[0], 8, 200), new int[0]);
        checkArray("single window sits at startY",
                PureCalc.stackOffsets(new int[]{50}, 8, 200), new int[]{200});
        checkArray("three windows stack by height + gap",
                PureCalc.stackOffsets(new int[]{50, 30, 100}, 8, 200),
                new int[]{200, 258, 296});
        checkArray("zero-height window still advances by the gap",
                PureCalc.stackOffsets(new int[]{0, 40}, 8, 100),
                new int[]{100, 108});
        checkArray("zero gap packs tightly",
                PureCalc.stackOffsets(new int[]{10, 10}, 0, 0),
                new int[]{0, 10});
        checkArray("negative height treated as zero",
                PureCalc.stackOffsets(new int[]{-5, 20}, 4, 10),
                new int[]{10, 14});

        // The pile-up: the tall multi-line CPU window must push the
        // one-line chips fully below it, not 100 px under its top.
        int[] ys = PureCalc.stackOffsets(new int[]{432, 48, 48}, 8, 200);
        check("tall CPU window pushes the next window below it",
                ys[1] == 200 + 432 + 8);
        check("third window stacks below the second",
                ys[2] == ys[1] + 48 + 8);

        System.out.println("StackOffsetsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
