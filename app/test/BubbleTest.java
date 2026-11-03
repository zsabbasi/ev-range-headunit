import com.evrange.app.BubbleLogic;
import com.evrange.app.SpeedVol;

public class BubbleTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    static String feed(long at, String boost) {
        return "epoch_ms=" + at + "\nsetting70=" + (boost.equals("ON") ? 0 : 1) + "\nboost=" + boost + "\nrequest=-1\nnote=\n";
    }

    public static void main(String[] args) {
        long now = 1_000_000_000L;
        // ---- speed-linked volume state ----
        check("fresh ON is read", SpeedVol.parse(feed(now - 5000, "ON"), now).boost == SpeedVol.BOOST_ON);
        check("fresh OFF is read", SpeedVol.parse(feed(now - 5000, "OFF"), now).boost == SpeedVol.BOOST_OFF);
        check("older than 90 s is unknown (feed not running)", SpeedVol.parse(feed(now - 120000, "ON"), now).boost == SpeedVol.UNKNOWN);
        check("missing, empty or garbage text is unknown",
                SpeedVol.parse(null, now).boost == SpeedVol.UNKNOWN && SpeedVol.parse("", now).boost == SpeedVol.UNKNOWN
                        && SpeedVol.parse("boost=ON\n", now).boost == SpeedVol.UNKNOWN
                        && SpeedVol.parse("epoch_ms=abc\nboost=ON\n", now).boost == SpeedVol.UNKNOWN);
        check("a feed that reports UNKNOWN stays unknown", SpeedVol.parse(feed(now, "UNKNOWN"), now).boost == SpeedVol.UNKNOWN);
        check("the note is carried through", SpeedVol.parse("epoch_ms=" + now + "\nboost=OFF\nnote=set to 1, read back 1\n", now).note.equals("set to 1, read back 1"));
        check("request text is exactly 1 or 0", SpeedVol.requestText(true).equals("1") && SpeedVol.requestText(false).equals("0"));
        check("labels", SpeedVol.label(SpeedVol.parse(feed(now, "ON"), now)).startsWith("ON")
                && SpeedVol.label(SpeedVol.parse(feed(now, "OFF"), now)).startsWith("OFF")
                && SpeedVol.label(SpeedVol.parse(null, now)).startsWith("unknown"));

        // ---- floating bubble ----
        BubbleLogic b = new BubbleLogic();
        check("driving mode: shown while driving forward", b.visible(now, BubbleLogic.DRIVING, 3000, false, false, false));
        check("driving mode: hidden when standing still (so never over the camera after selecting R)",
                !b.visible(now + 1000, BubbleLogic.DRIVING, 0, false, false, false));
        check("driving mode: hidden while reversing", !b.visible(now + 2000, BubbleLogic.DRIVING, -150, false, false, false));
        check("hidden for 8 s after the last reversing, even when moving forward",
                !b.visible(now + 5000, BubbleLogic.DRIVING, 3000, false, false, false)
                        && !b.visible(now + 2000 + 7999, BubbleLogic.DRIVING, 3000, false, false, false));
        check("shown again 8 s after reversing stopped", b.visible(now + 2000 + 8000, BubbleLogic.DRIVING, 3000, false, false, false));
        BubbleLogic a = new BubbleLogic();
        check("always mode: shown while standing still", a.visible(now, BubbleLogic.ALWAYS, 0, false, false, false));
        check("always mode: hidden while reversing and for 8 s after",
                !a.visible(now + 1000, BubbleLogic.ALWAYS, -200, false, false, false)
                        && !a.visible(now + 5000, BubbleLogic.ALWAYS, 0, false, false, false)
                        && a.visible(now + 1000 + 8000, BubbleLogic.ALWAYS, 0, false, false, false));
        BubbleLogic o = new BubbleLogic();
        check("off mode: never shown", !o.visible(now, BubbleLogic.OFF, 3000, false, false, false));
        check("hidden while the app itself is on screen", !o.visible(now, BubbleLogic.DRIVING, 3000, true, false, false));
        check("hidden in demo mode", !o.visible(now, BubbleLogic.DRIVING, 3000, false, true, false));
        check("hidden after the user snoozed it", !o.visible(now, BubbleLogic.DRIVING, 3000, false, false, true));
        check("slow creeping below the moving threshold counts as standing still",
                !new BubbleLogic().visible(now, BubbleLogic.DRIVING, 29, false, false, false)
                        && new BubbleLogic().visible(now, BubbleLogic.DRIVING, 30, false, false, false));
        System.out.println(failures == 0 ? "\nALL BUBBLE TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
