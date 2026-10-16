package starmp.ui;

import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;

/**
 * 0.1.5.9: games keep running when their window isn't focused (multiboxing / alt-tab).
 *
 * Vanilla idles the whole game while the window is unfocused and the game is paused (title screen, a paused campaign,
 * a paused battle): no frames, so no mod code runs. With SSMP that froze background windows - a guest following the
 * host's pause never saw the host unpause, the lobby stopped answering, a host in a menu stopped the shared world.
 * The mod's settings.json turns that idle off ("idleWhileWindowNotVisible": false); this caps an unfocused window's
 * frame rate instead, so six copies on one PC don't each render flat out: 30 fps while linked (F8-free pref "bgFps"),
 * 10 fps otherwise.
 */
public final class Background {
    private Background() {}
    private static long lastNs;
    private static boolean wasUnfocused;

    /** Once per rendered frame, from one place per screen (title menu, campaign script, combat plugin). */
    public static void frame() {
        boolean active;
        try { active = org.lwjgl.opengl.Display.isActive(); } catch (Throwable t) { return; }
        if ("1".equals(System.getenv("STARMP_TEST_UNFOCUSED"))) active = false; // test hook: act as if never focused
        if (!active) {
            NetManager n = StarMPPlugin.net();
            boolean linked = n != null && n.linked();
            int fps = 10;
            if (linked) { try { fps = Math.max(5, Math.min(120, Integer.parseInt(Prefs.get("bgFps", "30").trim()))); } catch (Throwable t) { fps = 30; } }
            long wait = 1_000_000_000L / fps - (System.nanoTime() - lastNs);
            if (wait > 1_000_000L) { try { Thread.sleep(Math.min(200L, wait / 1_000_000L)); } catch (InterruptedException ignored) {} }
            if (!wasUnfocused) { wasUnfocused = true; StarMPPlugin.flog("background: window not focused - game keeps running, capped at " + fps + " fps"); }
        } else if (wasUnfocused) {
            wasUnfocused = false; StarMPPlugin.flog("background: window focused again");
        }
        lastNs = System.nanoTime();
    }
}
