import com.evrange.app.LogCap;
import java.io.File;
import java.io.FileOutputStream;

public class LogCapTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static void write(File f, int bytes) throws Exception {
        FileOutputStream o = new FileOutputStream(f, false);
        o.write(new byte[bytes]);
        o.close();
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        for (File f : dir.listFiles()) {
            f.delete();
        }
        File log = new File(dir, "gps_rpm_log.csv");
        File old = new File(dir, "gps_rpm_log.1.csv");
        check("the old copy is named name.1.ext", LogCap.oldCopy(log).getName().equals("gps_rpm_log.1.csv")
                && LogCap.oldCopy(new File(dir, "noext")).getName().equals("noext.1"));
        write(log, 900);
        check("a log under the limit is left alone", !LogCap.rotateIfBig(log, 1000) && log.exists() && !old.exists());
        write(log, 1001);
        check("a log over the limit becomes the old copy", LogCap.rotateIfBig(log, 1000) && !log.exists() && old.length() == 1001);
        write(log, 1500);
        check("rotating again replaces the previous old copy (only one is kept)",
                LogCap.rotateIfBig(log, 1000) && !log.exists() && old.length() == 1500 && dir.listFiles().length == 1);
        check("a missing file is fine", !LogCap.rotateIfBig(new File(dir, "missing.csv"), 10));
        System.out.println(failures == 0 ? "\nALL LOG CAP TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}