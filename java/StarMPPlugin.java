package starmp;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Mod entry point: starts the network helper manager and opens the StarMP window.
 *
 * Starsector's mod class loader blocks most of java.io (File, writers, data streams), reflection and
 * java.util.prefs. Everything here uses only the game API, java.lang.Process and Swing.
 * Logging goes to starsector.log (lines tagged StarMP) and to saves/common/starmp_log.txt.
 */
public class StarMPPlugin extends BaseModPlugin {
    public static final String MOD_ID = "starmp";
    public static final String VERSION = "0.1.6.4";
    private static NetManager net;
    private static final StringBuilder logBuf = new StringBuilder();
    private static volatile boolean logDirty;
    private static volatile boolean commonLog;

    public static NetManager net() { return net; }
    /** Test hook. */
    static void setNetForTest(NetManager n) { net = n; }

    /** Logs to stdout, starsector.log and (when running in the game) saves/common/starmp_log.txt. */
    public static void flog(String s) {
        System.out.println("[StarMP] " + s);
        try { Global.getLogger(StarMPPlugin.class).info(s); } catch (Throwable ignored) {}
        if (!commonLog) return;
        synchronized (logBuf) {
            logBuf.append(new SimpleDateFormat("HH:mm:ss ").format(new Date())).append(s).append('\n');
            if (logBuf.length() > 400_000) logBuf.delete(0, logBuf.length() - 300_000);
            logDirty = true;
        }
    }

    /** Stack trace as text without java.io.StringWriter (blocked by the sandbox). */
    public static String trace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c != t) sb.append("Caused by: ");
            sb.append(c).append('\n');
            StackTraceElement[] st = c.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 25); i++) sb.append("    at ").append(st[i]).append('\n');
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    private static void startLogFlusher() {
        commonLog = true;
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (!logDirty) continue;
                String text;
                synchronized (logBuf) { text = logBuf.toString(); logDirty = false; }
                try { Global.getSettings().writeTextFileToCommon("starmp_log.txt", text); } catch (Throwable ignored) {}
            }
        }, "StarMP-log");
        t.setDaemon(true);
        t.start();
    }

    /** The mod folder, from the game's own mod list (works for any install path). */
    static String modDir() {
        try {
            String p = Global.getSettings().getModManager().getModSpec(MOD_ID).getPath();
            if (p != null && !p.isEmpty()) return p;
        } catch (Throwable ignored) {}
        return System.getProperty("com.fs.starfarer.settings.paths.mods", "mods") + "/StarMP";
    }

    /** 0.1.5.3: the mod folder; the helper writes reports to its "crash logs" and "player logs" subfolders. */
    public static String modFolder() { return modDir(); }

    static String helperPath(String dir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String sep = win ? "\\" : "/";
        String d = dir.endsWith("/") || dir.endsWith("\\") ? dir.substring(0, dir.length() - 1) : dir;
        return d + sep + "bin" + sep + (win ? "smnet.exe" : "smnet-linux");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        try { starmp.campaign.CoopCampaign.install(); } catch (Throwable t) { flog("campaign install failed: " + trace(t)); }
        try { starmp.campaign.PlayerRecords.gameLoaded(); } catch (Throwable t) { flog("player records: " + t); }
        try { starmp.campaign.HotJoin.gameLoaded(newGame); } catch (Throwable t) { flog("hot join: " + t); }
    }

    @Override
    public void afterGameSave() {
        try { starmp.campaign.HotJoin.afterSave(); } catch (Throwable t) { flog("afterGameSave: " + t); }
    }

    @Override
    public void beforeGameSave() {
        try { starmp.campaign.PlayerRecords.beforeSave(); } catch (Throwable t) { flog("player records before save: " + t); }
        try { starmp.campaign.CoopCampaign.beforeSave(); } catch (Throwable t) { flog("beforeGameSave: " + t); }
    }

    @Override
    public void onApplicationLoad() {
        startLogFlusher();
        try {
            String dir = modDir();
            flog("StarMP " + VERSION + " onApplicationLoad; mod dir " + dir);
            flog("os=" + System.getProperty("os.name") + " java=" + System.getProperty("java.version")
                    + " headless=" + java.awt.GraphicsEnvironment.isHeadless());
            try { flog("idle when unfocused (vanilla idleWhileWindowNotVisible): " + Global.getSettings().getBoolean("idleWhileWindowNotVisible") + " (SSMP sets false)"); } catch (Throwable t) { flog("idle setting: " + t); }
            String exe = helperPath(dir);
            flog("helper " + exe);
            net = new NetManager(exe);
            starmp.combat.CoopState.install(net);
            net.setName(Prefs.get("name", System.getProperty("user.name", "Player")));
            net.addListener(new NetManager.Listener() {
                public void onState(NetManager.State s) {}
                public void onLog(String line) {}
                public void onPeers(java.util.Map<Integer, NetManager.Peer> peers) {}
                public void onCode(String code) {}
            });
            try { starmp.diag.Diag.start(net); } catch (Throwable t) { flog("debug log start failed: " + trace(t)); }
            flog("menu ready (in-game title menu)");
        } catch (Throwable t) {
            flog("FAILED to start: " + trace(t));
        }
    }
}
