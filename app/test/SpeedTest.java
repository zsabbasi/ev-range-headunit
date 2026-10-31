import com.evrange.app.RangeEngine;

public class SpeedTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static RangeEngine at(int rpm) {
        RangeEngine e = new RangeEngine();
        e.addSample(1000, 330, 0, 70, rpm, -1);
        return e;
    }

    public static void main(String[] args) {
        check("3000 rpm is 31.0 km/h by the calibrated ratio", Math.abs(at(3000).speedKmh(-1) - 31.0) < 0.1);
        check("8877 rpm (the fastest rpm seen) is about 92 km/h", Math.abs(at(8877).speedKmh(-1) - 91.7) < 0.5);
        check("105 km/h needs about 10,160 rpm", Math.abs(at(10163).speedKmh(-1) - 105.0) < 0.5);
        check("a fresh GPS speed that agrees with the rpm is used", Math.abs(at(3000).speedKmh(28.4) - 28.4) < 1e-9 && at(3000).speedUsesGps(28.4));
        check("a GPS speed that contradicts the rpm is ignored (0 and 60 km/h against 31 km/h)",
                Math.abs(at(3000).speedKmh(0.0) - 31.0) < 0.1 && Math.abs(at(3000).speedKmh(60.0) - 31.0) < 0.1
                        && !at(3000).speedUsesGps(0.0) && !at(3000).speedUsesGps(60.0));
        check("agreement window is the larger of 20 % and 5 km/h",
                at(1000).speedUsesGps(10.3 + 4.9) && !at(1000).speedUsesGps(10.3 + 5.2)
                        && at(9000).speedUsesGps(93.0 * 1.19) && !at(9000).speedUsesGps(93.0 * 1.25));
        check("no GPS fix means rpm only", Math.abs(at(3000).speedKmh(-1) - 31.0) < 0.1 && !at(3000).speedUsesGps(-1));
        check("stopped motor shows 0 even if GPS drifts", at(0).speedKmh(1.7) == 0.0 && at(-1).speedKmh(1.7) == 0.0);
        check("reverse (negative rpm) shows a positive speed", Math.abs(at(-1500).speedKmh(-1) - 15.5) < 0.1);
        check("below the 20 rpm dead-band counts as stopped", at(19).speedKmh(-1) == 0.0 && at(25).speedKmh(-1) > 0.0);
        System.out.println(failures == 0 ? "\nALL SPEED TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
