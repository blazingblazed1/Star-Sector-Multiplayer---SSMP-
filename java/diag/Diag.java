package starmp.diag;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import java.nio.ByteBuffer;
import java.util.TimeZone;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;

/**
 * 0.1.5.3 debug log. Collects this game's debug lines (campaign, combat, performance), stamps them with the player
 * name and UTC time, and hands them to the network helper every 250 ms. The helper keeps the last 6 min, maintains the
 * desktop crash report and writes F9 bug reports (see smnet/src/diag.rs). Nothing here sends logs over the network:
 * the only network use is a tiny clock-sync ping to the host (TSYNC) so logs from different PCs line up.
 *
 * Line format:  Player : HH:MM:SS:mmmZ | <host-clock ms> | TAG | data
 */
public final class Diag {
    private Diag() {}

    /** Clock sync with the host (guest -> host request, host -> guest answer). */
    public static final byte TSYNC = 33;

    /**
     * 0.1.5.8: master switch for the whole debug logger (crash report, F9 bug report, F10 overlay, performance and
     * hardware lines, the launcher requirement for Host/Join). false = the code is in the build but never runs: no
     * helper logging, no files, no hardware probe, no F9/F10, and Host/Join work without the SSMP launcher.
     * (Env STARMP_DIAG=1 turns it back on for the developer's test rig.)
     */
    public static final boolean BUILD_LOGGER = true;
    public static boolean active() { return BUILD_LOGGER || "1".equals(System.getenv("STARMP_DIAG")); }

    public static boolean enabled() { return active() && Prefs.getBool("debugLog", true); }

    /** 0.1.5.5: started through the SSMP launcher (Multiplayer), so the crash logger ran before Starsector did.
     *  Host / Join need this. */
    /** 0.9.2.3: the launcher is optional - Host / Join always work (a plain Starsector start uses IROH). */
    public static boolean launcherOk() { return true; }
    public static boolean viaLauncher() { return System.getenv("STARMP_LAUNCHER") != null; }
    public static final String NEED_LAUNCHER = "To host or join, start Starsector with the \"Starsector (SSMP)\" desktop shortcut and pick IROH or LAN.";
    /** Where reports go: "mods" (StarMP/crash logs + StarMP/player logs, default), "desktop" or "off". */
    public static String reportPlace() { return Prefs.get("crashReport", "mods"); }

    private static volatile NetManager net;
    private static final StringBuilder buf = new StringBuilder();
    private static volatile long hostOffsetMs; // add to this PC's clock to get the host's
    private static volatile int hostRttMs = -1;
    private static volatile String lastHw = "", lastSys = "", glInfo = "", sentName = "", sentPlace = "";
    private static volatile String pendingNote;
    private static boolean flusherStarted;
    private static long lastClipNs;
    private static boolean f9Down, f10Down;
    public static volatile boolean overlay;

    public static String hwText() { return lastHw; }
    public static String sysText() { return lastSys; }
    public static long hostOffsetMs() { return hostOffsetMs; }
    public static int hostRttMs() { return hostRttMs; }

    // ------------------------------------------------------------------ helper link
    /** NetManager calls this whenever the helper process (re)starts. */
    public static synchronized void helperStarted(NetManager n) {
        net = n;
        if (!enabled()) return; // logger off: the helper only does networking
        sentName = ""; sentPlace = "";
        sendInit();
        sendHeader();
        if (!flusherStarted) {
            flusherStarted = true;
            Thread t = new Thread(Diag::flushLoop, "StarMP-diag");
            t.setDaemon(true); t.start();
        }
    }

    private static void sendInit() {
        NetManager n = net;
        if (n == null) return;
        String place = enabled() ? reportPlace() : "off";
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        boolean dst = tz.inDaylightTime(new java.util.Date(now));
        String abbr = tz.getDisplayName(dst, TimeZone.SHORT);
        // smnet adds "crash logs" / "player logs" / <player name>. Multibox copies point "logRoot" at the main
        // install's StarMP folder so every client's reports land in one place (0.1.5.7).
        String logs = Prefs.get("logRoot", "").trim();
        if (logs.isEmpty()) logs = StarMPPlugin.modFolder();
        n.diagCmd("cmd", "diag_init", "player", n.playerName(), "report", place, "modlogs", logs, "tz", abbr,
                "offset", tz.getOffset(now) / 60000, "hw", enabled());
        sentName = n.playerName(); sentPlace = place;
    }

    /** Settings changed (F8): new player name or report place. */
    public static void settingsChanged() { sendInit(); sendHeader(); }

    private static void sendHeader() {
        NetManager n = net;
        if (n == null) return;
        n.diagCmd("cmd", "diag_hdr", "text", header());
    }

    private static String header() {
        NetManager n = net;
        StringBuilder h = new StringBuilder();
        TimeZone tz = TimeZone.getDefault();
        h.append("Version:       StarMP ").append(StarMPPlugin.VERSION).append('\n');
        String role = n == null ? "offline" : n.isHost() ? (n.linked() ? "HOST (" + n.peerIds().size() + " guest(s))" : "not linked")
                : n.linked() ? "GUEST (my id " + n.myId() + ")" : "not linked";
        h.append("Role:          ").append(role).append('\n');
        h.append("Time zone:     ").append(tz.getID()).append('\n');
        h.append("Started via:   ").append(viaLauncher() ? "SSMP launcher " + System.getenv("STARMP_LAUNCHER") + " (Multiplayer)" : "Starsector directly (IROH by default)").append('\n');
        if (n != null && !n.isHost() && n.linked())
            h.append("Host clock:    this PC is ").append(-hostOffsetMs >= 0 ? "+" : "").append(-hostOffsetMs).append(" ms vs the host (measured, round trip ").append(hostRttMs).append(" ms)\n");
        h.append("Column 2:      host clock in ms since 1970 (this PC's clock corrected by the measured offset)\n");
        try { if (Global.getSector() != null && Global.getSector().getClock() != null) h.append("Game date:     ").append(Global.getSector().getClock().getDateString()).append('\n'); } catch (Throwable ignored) {}
        Runtime rt = Runtime.getRuntime();
        h.append("Java:          ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append("), max heap ")
                .append(rt.maxMemory() / 1_048_576).append(" MB, ").append(rt.availableProcessors()).append(" threads available\n");
        if (!glInfo.isEmpty()) h.append("Graphics:      ").append(glInfo).append('\n');
        try {
            org.lwjgl.opengl.DisplayMode dm = org.lwjgl.opengl.Display.getDisplayMode();
            h.append("Display:       ").append(dm.getWidth()).append('x').append(dm.getHeight()).append(" @ ").append(dm.getFrequency()).append(" Hz, ")
                    .append(org.lwjgl.opengl.Display.isFullscreen() ? "fullscreen" : "windowed").append(", UI scale ").append(Global.getSettings().getScreenScaleMult()).append('\n');
        } catch (Throwable ignored) {}
        h.append("OS:            ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
        h.append("Mods:          ").append(starmp.ui.Lobby.modList()).append('\n');
        return h.toString();
    }

    /** From the first rendered frame (GL calls need the render thread). */
    public static void captureGl() {
        if (!glInfo.isEmpty()) return;
        try {
            glInfo = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) + " | " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR)
                    + " | OpenGL " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            line("INFO", "graphics " + glInfo);
        } catch (Throwable t) { glInfo = "n/a"; }
    }

    /** Events from the helper: hardware text, live system line, F9 result. */
    public static void onHelperEvent(String ev, String text) {
        if ("hw".equals(ev)) lastHw = text;
        else if ("sys".equals(ev)) lastSys = text;
        else if ("diag_clip".equals(ev)) {
            if (text.startsWith("error")) note("StarMP: bug report failed (" + text + ")");
            else {
                String name = text; int i = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                if (i >= 0) name = name.substring(i + 1);
                note("StarMP: bug report saved to StarMP/player logs - " + name);
                StarMPPlugin.flog("F9 bug report saved: " + name);
            }
        }
    }

    // ------------------------------------------------------------------ lines
    /** One debug line (any thread). */
    public static void line(String tag, String data) {
        if (!enabled()) return;
        NetManager n = net;
        if (n == null) return;
        long now = System.currentTimeMillis();
        StringBuilder l = new StringBuilder(data.length() + 64);
        l.append(n.playerName()).append(" : ");
        appendUtc(l, now);
        l.append("Z | ").append(now + hostOffsetMs).append(" | ").append(tag).append(" | ").append(data.replace('\n', ' ')).append('\n');
        synchronized (buf) {
            if (buf.length() < 4_000_000) buf.append(l);
        }
    }

    private static void appendUtc(StringBuilder sb, long ms) {
        long sod = Math.floorMod(ms / 1000, 86_400L);
        int h = (int) (sod / 3600), m = (int) (sod / 60 % 60), s = (int) (sod % 60), milli = (int) Math.floorMod(ms, 1000L);
        if (h < 10) sb.append('0'); sb.append(h).append(':');
        if (m < 10) sb.append('0'); sb.append(m).append(':');
        if (s < 10) sb.append('0'); sb.append(s).append(':');
        if (milli < 100) sb.append('0'); if (milli < 10) sb.append('0'); sb.append(milli);
    }

    private static void flushLoop() {
        long lastHdr = 0, lastSync = 0; int syncs = 0;
        while (true) {
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            NetManager n = net;
            if (n == null) continue;
            try {
                String text = null;
                synchronized (buf) { if (buf.length() > 0) { text = buf.toString(); buf.setLength(0); } }
                if (text != null) n.diagCmd("cmd", "diag", "d", text);
                long now = System.currentTimeMillis();
                String place = enabled() ? reportPlace() : "off";
                if (!n.playerName().equals(sentName) || !place.equals(sentPlace)) sendInit();
                if (now - lastHdr > 30_000) { lastHdr = now; sendHeader(); }
                // clock sync with the host: quick at first, then every 10 s
                if (!n.isHost() && n.linked() && now - lastSync > (syncs < 5 ? 2_000 : 10_000)) {
                    lastSync = now; syncs++;
                    ByteBuffer b = ByteBuffer.allocate(1 + 1 + 8 + 8);
                    b.put(TSYNC); b.put((byte) 0); b.putLong(now); b.putLong(0);
                    n.sendToHost(b.array());
                }
                if (!n.linked()) { syncs = 0; hostOffsetMs = 0; }
            } catch (Throwable t) { /* never let the logger die */ }
        }
    }

    // ------------------------------------------------------------------ clock sync
    private static final long[][] samples = new long[8][2]; // {rtt, offset}
    private static int sampleN;

    /** From CoopState (network reader thread). */
    public static void onTsync(NetManager n, int peer, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        byte kind = b.get(); long t0 = b.getLong(); long hostT = b.getLong();
        if (kind == 0 && n.isHost()) { // answer with our clock
            ByteBuffer r = ByteBuffer.allocate(18); r.put(TSYNC); r.put((byte) 1); r.putLong(t0); r.putLong(System.currentTimeMillis());
            n.sendToPeer(peer, r.array());
        } else if (kind == 1 && !n.isHost()) {
            long now = System.currentTimeMillis(), rtt = now - t0;
            if (rtt < 0 || rtt > 10_000) return;
            long off = hostT + rtt / 2 - now;
            synchronized (samples) {
                samples[sampleN % samples.length][0] = rtt; samples[sampleN % samples.length][1] = off; sampleN++;
                // the sample with the shortest round trip is the most accurate
                long bestRtt = Long.MAX_VALUE, bestOff = 0;
                for (int i = 0; i < Math.min(sampleN, samples.length); i++) if (samples[i][0] < bestRtt) { bestRtt = samples[i][0]; bestOff = samples[i][1]; }
                hostOffsetMs = bestOff; hostRttMs = (int) bestRtt;
            }
        }
    }

    // ------------------------------------------------------------------ keys, clean exit, notes
    private static long lastF9Ns, lastF10Ns;

    /** A key press from the screen's own input events (campaign listener, combat plugin, title menu). */
    public static void keyEvent(int key, String where) {
        if (!enabled()) return;
        long now = System.nanoTime();
        if (key == Keyboard.KEY_F9 && now - lastF9Ns > 300_000_000L) { lastF9Ns = now; f9Down = true; clip(where); }
        else if (key == Keyboard.KEY_F10 && now - lastF10Ns > 300_000_000L) { lastF10Ns = now; f10Down = true; toggleOverlay(); }
    }

    private static void toggleOverlay() { overlay = !overlay; line("NOTE", "F10 overlay " + (overlay ? "on" : "off")); }

    /** Every frame: backup for screens whose input events we don't see (some dialogs). Same debounce as above. */
    public static void pollKeys(String where) {
        if (!enabled()) return;
        try {
            if (!Keyboard.isCreated()) return;
            boolean f9 = Keyboard.isKeyDown(Keyboard.KEY_F9), f10 = Keyboard.isKeyDown(Keyboard.KEY_F10);
            long now = System.nanoTime();
            if (f9 && !f9Down && now - lastF9Ns > 300_000_000L) { lastF9Ns = now; clip(where); }
            if (f10 && !f10Down && now - lastF10Ns > 300_000_000L) { lastF10Ns = now; toggleOverlay(); }
            f9Down = f9; f10Down = f10;
        } catch (Throwable ignored) {}
    }

    private static void clip(String where) {
        NetManager n = net;
        if (n == null) { note("StarMP: bug report not available (network helper not running)"); return; }
        if (!enabled()) { note("StarMP: debug logging is off (F8 to turn it on)"); return; }
        long now = System.nanoTime();
        if (now - lastClipNs < 5_000_000_000L) return;
        lastClipNs = now;
        line("NOTE", "F9 bug report requested (" + where + ")");
        String text = null;
        synchronized (buf) { if (buf.length() > 0) { text = buf.toString(); buf.setLength(0); } }
        if (text != null) n.diagCmd("cmd", "diag", "d", text);
        n.diagCmd("cmd", "diag_clip");
        note("StarMP: saving bug report (last 6 min)...");
    }

    /** A message for the player, shown by whichever screen is up (campaign, combat or title). */
    public static void note(String s) { pendingNote = s; }
    public static String takeNote() { String s = pendingNote; pendingNote = null; return s; }

    /** Normal exit = the game is closing from the title screen (crashes and force-closes happen elsewhere). */
    public static boolean cleanExit() {
        try { return Global.getCurrentState() == GameState.TITLE; } catch (Throwable t) { return false; }
    }

    /** Called once at game start (after the net manager exists). */
    public static void start(NetManager n) {
        if (!active()) { StarMPPlugin.flog("debug log: not in this build"); return; }
        if (!enabled()) { StarMPPlugin.flog("debug log: off (F8)"); return; }
        n.startHelper(); // the crash report needs the helper for the whole session
        line("INFO", "game started, StarMP " + StarMPPlugin.VERSION + (viaLauncher() ? " (via SSMP launcher " + System.getenv("STARMP_LAUNCHER") + ")" : " (started directly - IROH)"));
    }
}
