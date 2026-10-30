import com.evrange.app.RangeEngine;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Map;

public class EngineTest {
    public static void main(String[] a) throws Exception {
        RangeEngine e = new RangeEngine();
        e.wltpKm = 150;
        long from = Long.parseLong(a[1]);
        BufferedReader r = new BufferedReader(new FileReader(a[0]));
        String line = r.readLine();
        int n = 0;
        while ((line = r.readLine()) != null) {
            String[] c = line.split(",");
            long ms = Long.parseLong(c[0]);
            if (ms < from) {
                continue;
            }
            e.addSample(ms, Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                    Integer.parseInt(c[9]), -1);
            n++;
        }
        r.close();
        System.out.println("samples: " + n);
        for (int w : new int[] {1, 2, 10, 25, 50}) {
            System.out.printf("window %2d km -> %.1f kWh/100 km (covered %.2f km)%n", w, e.consumption(w), e.coveredKm(w));
        }
        e.windowKm = 25;
        System.out.printf("best: %.1f kWh/100 km from '%s', range %.0f km, remaining %.1f kWh, WLTP-based %.0f km%n",
                e.bestKwhPer100(), e.bestSource(), e.rangeKm(), e.remainingKwh(), e.wltpRangeKm());
        System.out.printf("trip: %.2f km, %.1f kWh/100, regen %.0f Wh, revs %.0f%n", e.tripKm(), e.tripKwhPer100(),
                e.tripRegenKwh() * 1000, e.tripRevs());
        for (Map.Entry<Long, RangeEngine.Day> d : e.days().entrySet()) {
            System.out.printf("day %d: %.2f km, %.0f Wh net, %.1f kWh/100%n", d.getKey(), d.getValue().km,
                    d.getValue().kwhNet * 1000, d.getValue().kwhPer100());
        }

        // save / load round trip
        String saved = e.save();
        RangeEngine e2 = new RangeEngine();
        e2.load(saved);
        boolean same = e2.save().equals(saved);
        System.out.println("save/load round trip identical: " + same + "  (" + saved.length() + " chars)");
        System.out.printf("after reload: window25 %.1f, trip %.2f km, all-time %.1f%n", e2.consumption(25),
                e2.tripKm(), e2.allTimeKwhPer100());

        // calibration example: if the real trip was 2.0 km
        double kmPerRev = 2.0 / e.tripRevs();
        System.out.printf("calibration if the real distance had been 2.0 km: %.6f km/rev (default 0.000211)%n", kmPerRev);
    }
}
