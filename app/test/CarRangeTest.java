import com.evrange.app.CarRange;
import com.evrange.app.RangeEngine;

public class CarRangeTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static int[] array(int endur, int soc) {
        int[] a = new int[82];
        a[CarRange.IDX_ENDURANCE] = endur;
        a[CarRange.IDX_SOC] = soc;
        return a;
    }

    public static void main(String[] args) {
        check("the two observed readings: 136 km at 56 % is D, 124 km at 56 % is S",
                CarRange.mode(array(136, 56)) == CarRange.MODE_D && CarRange.mode(array(124, 56)) == CarRange.MODE_S);
        check("range and battery are read from the right indices", CarRange.rangeKm(array(136, 56)) == 136 && CarRange.socPct(array(136, 56)) == 56);
        boolean ok = true;
        for (int soc = 12; soc <= 100; soc++) {
            int d = (int) Math.round(soc * CarRange.KM_PER_PCT_D);
            int s = (int) Math.round(soc * CarRange.KM_PER_PCT_S);
            ok &= CarRange.modeFrom(d, soc) == CarRange.MODE_D && CarRange.modeFrom(s, soc) == CarRange.MODE_S;
        }
        check("D and S are told apart at every battery level from 12 to 100 % (km rounded to whole numbers)", ok);
        check("below 10 % battery the mode is unknown", CarRange.modeFrom(20, 9) == CarRange.MODE_UNKNOWN);
        check("an implausible ratio is unknown (range 30 km at 56 %, range 200 km at 56 %)",
                CarRange.modeFrom(30, 56) == CarRange.MODE_UNKNOWN && CarRange.modeFrom(200, 56) == CarRange.MODE_UNKNOWN);
        check("empty, short or zero arrays give no value",
                CarRange.rangeKm(null) == -1 && CarRange.rangeKm(new int[5]) == -1 && CarRange.rangeKm(array(0, 56)) == -1
                        && CarRange.rangeKm(array(136, 0)) == -1 && CarRange.mode(null) == CarRange.MODE_UNKNOWN);
        check("labels", CarRange.label(CarRange.MODE_D).equals("D") && CarRange.label(CarRange.MODE_S).equals("S") && CarRange.label(-1).equals("?"));

        // per-mode consumption in the engine
        RangeEngine e = new RangeEngine();
        long t = 1_000_000;
        // 10 minutes in D at 4 kW and 30 km/h (3000 rpm), then 10 minutes in S at 8 kW and 30 km/h
        for (int i = 0; i <= 300; i++) {
            e.addSample(t + i * 2000L, 330, 12, 60, 3000, -1, CarRange.MODE_D);   // about 4.0 kW
        }
        long t2 = t + 300 * 2000L;
        for (int i = 1; i <= 300; i++) {
            e.addSample(t2 + i * 2000L, 330, 24, 55, 3000, -1, CarRange.MODE_S);  // about 7.9 kW
        }
        double d = e.modeKwhPer100(CarRange.MODE_D);
        double s = e.modeKwhPer100(CarRange.MODE_S);
        check("per-mode consumption separates D and S (D about 13 kWh/100, S about 26): " + String.format("%.1f / %.1f", d, s),
                d > 12 && d < 14.5 && s > 24 && s < 28 && s > 1.8 * d);
        check("distance is counted per mode", e.modeKm(CarRange.MODE_D) > 4.5 && e.modeKm(CarRange.MODE_S) > 4.5);
        double dKmBefore = e.modeKm(CarRange.MODE_D);
        double sKmBefore = e.modeKm(CarRange.MODE_S);
        e.addSample(t2 + 300 * 2000L + 2000L, 330, 12, 55, 3000, -1);  // mode unknown: counted overall, in neither mode
        check("a sample with no known mode does not change the per-mode totals",
                e.modeKm(CarRange.MODE_D) == dKmBefore && e.modeKm(CarRange.MODE_S) == sKmBefore);
        RangeEngine r = new RangeEngine();
        r.load(e.save());
        check("per-mode totals survive save and load",
                Math.abs(r.modeKwhPer100(CarRange.MODE_D) - d) < 1e-6 && Math.abs(r.modeKwhPer100(CarRange.MODE_S) - s) < 1e-6);
        RangeEngine old = new RangeEngine();
        old.load("s,0,50,0,0,0,0,0,0,0,0\n");
        check("a saved state from before this feature loads without per-mode data", Double.isNaN(old.modeKwhPer100(CarRange.MODE_D)));
        System.out.println(failures == 0 ? "\nALL CAR RANGE TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
