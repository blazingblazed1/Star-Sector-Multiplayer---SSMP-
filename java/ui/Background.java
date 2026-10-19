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
        if ("1".equals(System.getenv("STARMP_TEST_UNFOCUSED")) || starmp.Bot.ACTIVE) active = false; // test hook / bot test mode: act as if never focused
        if (!active) {
            NetManager n = StarMPPlugin.net();
            boolean linked = n != null && n.linked();
            int fps = 10;
            if (starmp.Bot.ACTIVE) fps = starmp.Bot.FPS;
            else if (linked) { try { fps = Math.max(5, Math.min(120, Integer.parseInt(Prefs.get("bgFps", "30").trim()))); } catch (Throwable t) { fps = 30; } }
            long wait = 1_000_000_000L / fps - (System.nanoTime() - lastNs);
            if (wait > 1_000_000L) { try { Thread.sleep(Math.min(200L, wait / 1_000_000L)); } catch (InterruptedException ignored) {} }
            if (!wasUnfocused) { wasUnfocused = true; StarMPPlugin.flog("background: window not focused - game keeps running, capped at " + fps + " fps"); }
        } else {
            if (wasUnfocused) { wasUnfocused = false; StarMPPlugin.flog("background: window focused again"); }
            capFocused();
        }
        lastNs = System.nanoTime();
    }

    /**
     * 0.9.2.7: Starsector moves the world a clamped step per frame (measured: ~1/30 s per frame below 30 fps; above
     * 60 fps - vsync on a 120/144/240 Hz screen ignores the game's 60 fps cap - each frame still counts ~1/60 s, so a
     * 116 fps host ran its whole world about 2x: Mike's guest saw planets / fleets / its own orbit snap forward every
     * sync). While in a session every game is held to at most 60 fps (pref "fpsCap"), so everyone runs at 1x.
     */
    private static long capNextNs;
    private static boolean capLogged;
    private static void capFocused() {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) { capNextNs = 0; return; }
        int fps;
        try { fps = Math.max(30, Math.min(60, Integer.parseInt(Prefs.get("fpsCap", "60").trim()))); } catch (Throwable t) { fps = 60; }
        long step = 1_000_000_000L / fps, now = System.nanoTime();
        if (capNextNs == 0 || now - capNextNs > step) { capNextNs = now + step; return; } // first frame, or already slower than the cap
        long left;
        while ((left = capNextNs - System.nanoTime()) > 0) {
            if (left > 2_000_000L) { try { Thread.sleep(1); } catch (InterruptedException ignored) {} }
            else Thread.yield();
        }
        capNextNs += step;
        if (!capLogged) { capLogged = true; StarMPPlugin.flog("background: in a session - frame rate held at " + fps + " fps max (the game's world speed follows the frame rate above 60)"); }
    }
}
