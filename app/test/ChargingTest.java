import com.evrange.app.RangeEngine;

public class ChargingTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        RangeEngine e = new RangeEngine();
        long t = 1_000_000;
        // drive 10 minutes at 30 km/h (3000 rpm) and 8 kW, then regen for 1 minute
        for (int i = 0; i <= 300; i++) {
            e.addSample(t + i * 2000L, 330, 24, 60, 3000, -1);
        }
        long t1 = t + 300 * 2000L;
        for (int i = 1; i <= 30; i++) {
            e.addSample(t1 + i * 2000L, 330, -20, 60, 3000, -1);
        }
        check("regen while moving is not charging", !e.charging());
        check("regen while moving is counted as regen", e.tripRegenKwh() > 0.05);

        // park and charge at about 3.2 kW for one hour: current -10 A at 320 V, rpm 0
        long t2 = t1 + 31 * 2000L;
        e.addSample(t1 + 31 * 2000L - 1000L, 330, 0, 60, 0, -1);  // the car comes to rest
        double before = e.allTimeKwhPer100();
        double regenBefore = e.tripRegenKwh();
        double netBefore = before * e.tripKm() / 100.0;
        for (int i = 1; i <= 1800; i++) {
            e.addSample(t2 + i * 2000L, 320, -10, 60 + i / 60, 0, -1);
        }
        check("standing still with current flowing in is charging", e.charging());
        check("charging does not change the all-time consumption", Math.abs(e.allTimeKwhPer100() - before) < 1e-9);
        check("charging is not counted as regen", Math.abs(e.tripRegenKwh() - regenBefore) < 1e-9);
        check("charging adds no distance", Math.abs(e.tripKm() * e.allTimeKwhPer100() / 100.0 - netBefore) < 1e-9);

        // unplug and set off: not charging any more
        long t3 = t2 + 1800 * 2000L;
        e.addSample(t3 + 2000, 330, 20, 90, 2000, -1);
        check("driving off clears charging", !e.charging());

        // standing still and using power (air conditioning, lights) still counts as consumption
        RangeEngine f = new RangeEngine();
        for (int i = 0; i <= 100; i++) {
            f.addSample(t + i * 2000L, 330, 6, 60, 0, -1);
        }
        check("standing still and discharging is not charging and is still counted", !f.charging() && f.liveKw() > 1.5);
        System.out.println(failures == 0 ? "\nALL CHARGING TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
