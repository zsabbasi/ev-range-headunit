import com.lgb.canmodule.CanJni;
import com.ts.evhook.EvFeedCore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FeedTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static final class Rec implements EvFeedCore.Sink {
        final List<String> logs = new ArrayList<String>();
        final List<String> status = new ArrayList<String>();

        public void log(String l) {
            logs.add(l);
        }

        public void latest(String t) {
        }

        public void status(String s) {
            status.add(s);
        }
    }

    static void clean(File d) {
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                f.delete();
            }
        }
    }

    static void reset() {
        CanJni.type = 356;
        CanJni.amp = 500;
        CanJni.rpmRaw = 15000;
        CanJni.soc = 74;
        CanJni.delayMs = 0;
        CanJni.throwRuntime = false;
        CanJni.throwLinkage = false;
        CanJni.queries = 0;
        CanJni.reads = 0;
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        ClassLoader cl = FeedTest.class.getClassLoader();

        // 1. normal polling, active driving: every step logs (after the first, which only queries)
        clean(dir);
        reset();
        Rec r = new Rec();
        EvFeedCore c = new EvFeedCore(cl, dir, r);
        CanJni.amp = 520; // +20 A
        CanJni.rpmRaw = 16500; // 1500 rpm
        long t = 1000000;
        long d0 = c.step(t);
        check("first step returns the 2 s delay", d0 == 2000);
        check("first step only queries (no data yet)", r.logs.size() == 0 && CanJni.queries == 2);
        for (int i = 1; i <= 5; i++) {
            c.step(t + i * 2000);
        }
        check("active driving logs every step (5 lines)", r.logs.size() == 5);
        check("decoded values: 328 V, +20 A, SOC 74, 1500 rpm", r.logs.get(0).contains(",328,20,74,33,32,33,34,1500,"));

        // 2. idle throttling: same values, standing still
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        for (int i = 0; i <= 30; i++) { // 60 s at 2 s steps
            c.step(t + i * 2000L);
        }
        check("idle for 60 s logs only 2 or 3 lines (not 30): " + r.logs.size(), r.logs.size() >= 2 && r.logs.size() <= 3);
        CanJni.soc = 73;
        int before = r.logs.size();
        c.step(t + 70000);
        c.step(t + 72000);
        check("a SOC change is logged immediately", r.logs.size() == before + 1);

        // 3. kill switch
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(t);
        int q = CanJni.queries;
        new File(dir, "evfeed.disable").createNewFile();
        long dd = c.step(t + 2000);
        check("kill switch: step returns 10 s and makes no JNI calls", dd == 10000 && CanJni.queries == q);
        new File(dir, "evfeed.disable").delete();
        dd = c.step(t + 12000);
        check("kill switch removed: polling resumes", dd == 2000 && CanJni.queries > q);

        // 4. crash-loop breaker
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.recordStart(t);
        c.recordStart(t + 60000);
        check("2 starts in 10 min: not tripped", !new File(dir, "evfeed.tripped").exists());
        c.recordStart(t + 120000);
        check("3 starts in 10 min: tripped file written", new File(dir, "evfeed.tripped").exists());
        EvFeedCore c2 = new EvFeedCore(cl, dir, new Rec());
        int q2 = CanJni.queries;
        check("after trip: step returns -1 and makes no JNI calls", c2.step(t + 130000) == -1 && CanJni.queries == q2);
        clean(dir);
        reset();
        c = new EvFeedCore(cl, dir, new Rec());
        c.recordStart(t);
        c.recordStart(t + 700000); // more than 10 minutes later
        c.recordStart(t + 1400000);
        check("starts spread over more than 10 min each: not tripped", !new File(dir, "evfeed.tripped").exists());

        // 5. slow-poll breaker
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        CanJni.delayMs = 200;
        long last = 0;
        int steps = 0;
        for (int i = 0; i < 10 && last != -1; i++) {
            last = c.step(t + i * 2000L);
            steps++;
        }
        check("slow polls trip the breaker after 5-6 steps (steps=" + steps + ")", last == -1 && steps <= 7 && c.isTripped());
        check("slow trip wrote evfeed.tripped", new File(dir, "evfeed.tripped").exists());

        // 6. error breaker (generic failures)
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(t); // prime
        CanJni.throwRuntime = true;
        last = 0;
        steps = 0;
        for (int i = 1; i < 10 && last != -1; i++) {
            last = c.step(t + i * 2000L);
            steps++;
        }
        check("5 consecutive errors trip the breaker (steps=" + steps + ")", last == -1 && steps == 5);

        // 7. linkage error trips immediately
        clean(dir);
        reset();
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(t);
        CanJni.throwLinkage = true;
        last = c.step(t + 2000);
        check("LinkageError trips immediately", last == -1 && c.isTripped());

        // 8. wrong CAN type: idle, no queries
        clean(dir);
        reset();
        CanJni.type = 140;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        last = c.step(t);
        check("wrong CAN type: idle 30 s, no queries", last == 30000 && CanJni.queries == 0);

        clean(dir);
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
