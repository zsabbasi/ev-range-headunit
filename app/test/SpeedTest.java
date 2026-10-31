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

    static RangeEngine at0(int source) {
        RangeEngine e = at(0);
        e.speedSource = source;
        return e;
    }

    static RangeEngine driveTwoSeconds(int source) {
        RangeEngine e = new RangeEngine();
        e.speedSource = source;
        e.addSample(1000, 330, 10, 70, 3000, 10 / 3.6);
        e.addSample(3000, 330, 10, 70, 3000, 10 / 3.6);
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
        // user-selectable source
        RangeEngine r = at(3000);
        r.speedSource = RangeEngine.SRC_RPM;
        check("rpm source ignores GPS even when it agrees", Math.abs(r.speedKmh(28.4) - 31.0) < 0.1 && !r.speedUsesGps(28.4));
        RangeEngine g = at(3000);
        g.speedSource = RangeEngine.SRC_GPS;
        check("GPS source uses GPS even when it differs from the rpm, falls back to rpm without a fix",
                Math.abs(g.speedKmh(24.0) - 24.0) < 1e-9 && g.speedUsesGps(24.0) && Math.abs(g.speedKmh(-1) - 31.0) < 0.1);
        check("GPS source still shows 0 when the motor is stopped", at0(RangeEngine.SRC_GPS).speedKmh(1.7) == 0.0);
        // distance follows the same choice: 2 s at 3000 rpm is 31 km/h x 2 s = 0.0172 km by rpm, 10 km/h by GPS = 0.0056 km
        RangeEngine dr = driveTwoSeconds(RangeEngine.SRC_RPM);
        RangeEngine dg = driveTwoSeconds(RangeEngine.SRC_GPS);
        check("distance by rpm source ignores GPS, by GPS source uses it", dr.tripKm() > 0.016 && dr.tripKm() < 0.0185
                && dg.tripKm() > 0.005 && dg.tripKm() < 0.006);
        check("this car's pack is 19.14 kWh by default", Math.abs(new RangeEngine().capacityKwh - 19.14) < 1e-9);
        System.out.println(failures == 0 ? "\nALL SPEED TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
