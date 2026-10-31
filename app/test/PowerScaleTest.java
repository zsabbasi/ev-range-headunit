import com.evrange.app.PowerScale;

public class PowerScaleTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    public static void main(String[] args) {
        check("bar ends are +42 kW / -13 kW (measured peaks 35.7 / 11.1 plus 15 %, rounded up)",
                near(PowerScale.MAX_KW, 42) && near(PowerScale.MIN_KW, -13));
        check("both measured peaks fit on the bar with headroom",
                PowerScale.MAX_KW > PowerScale.PEAK_DISCHARGE_KW * 1.10 && -PowerScale.MIN_KW > PowerScale.PEAK_REGEN_KW * 1.10);
        check("zero sits at 13/55 of the bar", near(PowerScale.zeroFraction(), 13.0 / 55.0));
        check("values beyond the ends are clamped", near(PowerScale.fraction(100), 1.0) && near(PowerScale.fraction(-100), 0.0));
        check("the discharge peak is inside the bar (below 0.9)",
                PowerScale.fraction(PowerScale.PEAK_DISCHARGE_KW) > 0.8 && PowerScale.fraction(PowerScale.PEAK_DISCHARGE_KW) < 0.9);
        check("fraction is monotonic", PowerScale.fraction(-5) < PowerScale.fraction(0) && PowerScale.fraction(0) < PowerScale.fraction(20));
        check("regen is below zero, normal driving is normal",
                PowerScale.level(-3) == PowerScale.REGEN && PowerScale.level(8) == PowerScale.NORMAL && PowerScale.level(0) == PowerScale.NORMAL);
        check("red from 85 % of the discharge peak (30.3 kW)",
                PowerScale.level(30.0) == PowerScale.NORMAL && PowerScale.level(30.4) == PowerScale.HIGH
                        && PowerScale.level(35.7) == PowerScale.HIGH);
        System.out.println(failures == 0 ? "\nALL POWER SCALE TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
