import com.lgb.canmodule.CanJni;
import com.yyw.ts70xhw.FtSet;
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
        String vol = "";
        final List<String> ac = new ArrayList<String>();

        public void log(String l) {
            logs.add(l);
        }

        public void latest(String t) {
        }

        public void status(String s) {
            status.add(s);
        }

        public void speedVol(String s) {
            vol = s;
        }

        public void ac(String s) {
            ac.add(s);
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

    static void write(File f, String s) throws Exception {
        java.io.FileOutputStream o = new java.io.FileOutputStream(f, false);
        o.write(s.getBytes("UTF-8"));
        o.close();
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


        // 9. speed-linked volume: the one setting the feed may write
        File req = new File(dir, "speedvol_request.txt");
        long T = t + 10000000L;
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.gets = 0; FtSet.sets = 0; FtSet.throwLinkage = false; FtSet.ignoreSet = false;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(T);
        check("no request file: nothing is written, state is reported as boost ON",
                FtSet.sets == 0 && r.vol.contains("setting70=0") && r.vol.contains("boost=ON") && r.vol.contains("request=-1"));
        write(req, "1");
        c.step(T + 5000);
        check("the check runs at most every 10 s (no write after 5 s)", FtSet.sets == 0);
        c.step(T + 12000);
        check("request 1: setting 70 is set to 1 once and read back", FtSet.sets == 1 && FtSet.value == 1
                && r.vol.contains("boost=OFF") && r.vol.contains("set to 1, read back 1"));
        c.step(T + 24000);
        c.step(T + 36000);
        check("while the state already matches the request, nothing more is written", FtSet.sets == 1);
        write(req, "0");
        c.step(T + 48000);
        check("request 0 restores it", FtSet.sets == 2 && FtSet.value == 0 && r.vol.contains("boost=ON"));
        write(req, "2");
        c.step(T + 60000);
        write(req, "yes");
        c.step(T + 72000);
        write(req, "");
        c.step(T + 84000);
        check("anything but exactly 0 or 1 is ignored", FtSet.sets == 2);
        check("the feed itself is unaffected (not tripped)", !c.isTripped());

        // 10. at most 6 changes an hour
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.sets = 0;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(T);
        boolean sawLimit = false;
        for (int i = 0; i < 10; i++) {
            write(req, i % 2 == 0 ? "1" : "0");
            c.step(T + 12000L * (i + 1));
            sawLimit |= r.vol.contains("limit");
        }
        check("more than 6 changes within an hour are refused (sets=" + FtSet.sets + ")", FtSet.sets == 6 && sawLimit);
        write(req, FtSet.value == 0 ? "1" : "0");
        c.step(T + 3700000L);
        check("the limit lifts after an hour", FtSet.sets == 7);

        // 11. a write that does not stick blocks further writes
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.sets = 0; FtSet.ignoreSet = true;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        write(req, "1");
        c.step(T);
        c.step(T + 12000);
        c.step(T + 24000);
        check("read-back mismatch: one attempt only, then blocked", FtSet.sets == 1 && r.vol.contains("blocked"));
        FtSet.ignoreSet = false;

        // 12. the setting class failing disables only this feature, never the feed
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.sets = 0; FtSet.throwLinkage = true;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        write(req, "1");
        long next = c.step(T);
        check("settings class failing: the feed carries on and is not tripped", next == EvFeedCore.POLL_MS && !c.isTripped());
        check("settings class failing: reported as unavailable", r.vol.contains("boost=UNKNOWN") && r.vol.contains("unavailable"));
        FtSet.throwLinkage = false;
        c.step(T + 12000);
        check("after a failure the feature stays off for the rest of the process", FtSet.sets == 0);

        // 13. kill switch and wrong CAN type: no setting access at all
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.gets = 0; FtSet.sets = 0;
        write(new File(dir, "evfeed.disable"), "x");
        write(req, "1");
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(T);
        check("kill switch present: the setting is not touched", FtSet.gets == 0 && FtSet.sets == 0);
        clean(dir);
        reset();
        CanJni.type = 140;
        write(req, "1");
        c = new EvFeedCore(cl, dir, new Rec());
        c.step(T);
        check("wrong CAN type: the setting is not touched", FtSet.gets == 0 && FtSet.sets == 0);
        // a request file in another location is honoured too (the app's own folder)
        clean(dir);
        reset();
        FtSet.value = 0; FtSet.sets = 0;
        File other = new File(dir, "app_request.txt");
        write(other, "1");
        r = new Rec();
        c = new EvFeedCore(cl, dir, r, new File[] {new File(dir, "missing.txt"), other});
        c.step(T);
        check("the first request file that exists is used", FtSet.sets == 1 && FtSet.value == 1);

        // 14. air-conditioning log: read-only, on change plus a heartbeat
        clean(dir);
        reset();
        CanJni.acInner = 0; CanJni.acFan = 3; CanJni.acReads = 0; CanJni.acThrow = false; CanJni.acLeft = "22";
        FtSet.value = 0; FtSet.sets = 0; FtSet.throwLinkage = false;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        c.step(T);
        check("the first read is logged", r.ac.size() == 1 && r.ac.get(0).contains(",22") );
        c.step(T + 2000);
        c.step(T + 4000);
        check("an unchanged state is not logged again within a minute", r.ac.size() == 1 && CanJni.acReads == 3);
        CanJni.acInner = 1;
        c.step(T + 6000);
        check("a change (recirculation switched on) is logged at once", r.ac.size() == 2);
        String[] cols = r.ac.get(1).trim().split(",", -1);
        String[] hdr = EvFeedCore.AC_HEADER.trim().split(",", -1);
        int idx = java.util.Arrays.asList(hdr).indexOf("inner_loop");
        check("the log line has one value per header column", cols.length == hdr.length);
        check("the inner_loop column carries the flag", idx > 0 && cols[idx].equals("1"));
        CanJni.acLeft = "21,5\nx";
        c.step(T + 8000);
        check("commas and newlines in text values cannot break the line", r.ac.size() == 3 && r.ac.get(2).trim().split(",", -1).length == hdr.length
                && r.ac.get(2).indexOf('\n') == r.ac.get(2).length() - 1);
        c.step(T + 8000 + EvFeedCore.AC_HEARTBEAT_MS);
        check("a heartbeat line is written after a minute without change", r.ac.size() == 4);
        check("AC logging never writes to the setting or to the car", FtSet.sets == 0);

        // 15. AC reading failing disables only the AC log
        clean(dir);
        reset();
        CanJni.acThrow = true; CanJni.acReads = 0;
        r = new Rec();
        c = new EvFeedCore(cl, dir, r);
        long nxt = c.step(T);
        check("AC read failing: the feed carries on, not tripped", nxt == EvFeedCore.POLL_MS && !c.isTripped());
        check("AC read failing: reported once, then not retried", r.status.toString().contains("AC logging unavailable"));
        int readsAfterFirst = CanJni.acReads;
        c.step(T + 2000);
        c.step(T + 4000);
        check("after an AC failure it is not called again", CanJni.acReads == readsAfterFirst);
        CanJni.acThrow = false;

        // 16. no AC reads when the feed is paused or the CAN type is wrong
        clean(dir);
        reset();
        CanJni.acReads = 0;
        write(new File(dir, "evfeed.disable"), "x");
        c = new EvFeedCore(cl, dir, new Rec());
        c.step(T);
        check("kill switch present: the AC state is not read", CanJni.acReads == 0);
        clean(dir);
        reset();
        CanJni.type = 140;
        c = new EvFeedCore(cl, dir, new Rec());
        c.step(T);
        check("wrong CAN type: the AC state is not read", CanJni.acReads == 0);
        clean(dir);
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
