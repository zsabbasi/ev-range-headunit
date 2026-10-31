import com.evprobe.app.ProbeModel;

public class ProbeModelTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static int[] sample(int speed, int gear, int mode) {
        int[] v = new int[ProbeModel.SIZE];
        v[2] = speed;
        v[24] = gear;
        v[78] = mode;
        for (int i = ProbeModel.VIN_FIRST; i <= ProbeModel.VIN_LAST; i++) {
            v[i] = 65 + (i - ProbeModel.VIN_FIRST);   // a fake VIN: letters
        }
        return v;
    }

    public static void main(String[] args) {
        check("index names match the unit's layout (2 Speed, 24 Stalls, 78 DriveMode, 77 Soc, 22 FootBrake)",
                ProbeModel.NAMES[2].equals("Speed") && ProbeModel.NAMES[24].startsWith("Stalls")
                        && ProbeModel.NAMES[78].equals("DriveMode") && ProbeModel.NAMES[77].equals("Soc")
                        && ProbeModel.NAMES[22].equals("FootBrake"));
        boolean allNamed = true;
        for (String n : ProbeModel.NAMES) {
            allNamed &= n != null;
        }
        check("every one of the 82 indices has a name", allNamed);

        ProbeModel m = new ProbeModel();
        m.add(sample(0, 0, 0));
        m.add(sample(0, 3, 0));
        m.add(sample(0, 5, 1));
        check("a value that changes is reported as changed, one that does not is not",
                m.changed(24) && m.changed(78) && !m.changed(2));
        check("first, last, min and max are tracked", m.first(24) == 0 && m.last()[24] == 5 && m.min(24) == 0 && m.max(24) == 5);
        check("three samples counted", m.samples() == 3);

        String row = ProbeModel.csvRow(1234L, sample(1, 2, 3));
        String header = ProbeModel.csvHeader();
        check("the VIN is not in the CSV row (82 values minus 17 VIN columns, plus the timestamp)",
                row.split(",").length == ProbeModel.SIZE - 17 + 1 && header.split(",").length == row.split(",").length);
        check("no VIN letter codes (65..81) appear in the CSV row", !row.matches(".*,(6[5-9]|7[0-9]|8[01]),.*"));
        ProbeModel vinModel = new ProbeModel();
        vinModel.add(sample(0, 0, 0));
        vinModel.add(sampleDifferentVin());
        check("VIN indices are hidden from the change summary even if they differ",
                !vinModel.changedSummary().contains("Vin") && vinModel.changedSummary().contains("nothing has changed"));
        System.out.println(failures == 0 ? "\nALL PROBE TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static int[] sampleDifferentVin() {
        int[] v = sample(0, 0, 0);
        for (int i = ProbeModel.VIN_FIRST; i <= ProbeModel.VIN_LAST; i++) {
            v[i] += 3;
        }
        return v;
    }
}
