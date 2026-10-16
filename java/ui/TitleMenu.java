package starmp.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;

/**
 * StarMP main menu on Starsector's title screen, styled like the Brotato / Wanderburg co-op mods.
 * Main: Single Player / Multiplayer / Options / Quit Game. The vanilla button column is removed (see Vanilla) and
 * its entries live under Single Player (play) and Options (settings, codex, credits).
 * Drawn by a combat plugin because the title screen runs a background battle.
 */
public class TitleMenu extends BaseEveryFrameCombatPlugin {
    enum Page { MAIN, SINGLE, TUTORIALS, OPTIONS, MULTI, JOIN, STARTED }

    private static Page page = Page.MAIN;
    private static String focus = null;
    private static String joinCode = "", nameEdit = null, notice = null;
    private static final LinkedList<String> log = new LinkedList<>();
    private static boolean listening;

    private final Gui gui = new Gui();
    private boolean active, replaced;
    private int replaceTries;

    public static void addLog(String s) { synchronized (log) { log.add(s); while (log.size() > 4) log.removeFirst(); } }

    @Override public void init(CombatEngineAPI engine) {
        GameState gs = null;
        try { gs = Global.getCurrentState(); } catch (Throwable ignored) {}
        active = gs == GameState.TITLE;
        if (active && !listening && StarMPPlugin.net() != null) {
            listening = true;
            StarMPPlugin.net().addListener(new NetManager.Listener() {
                public void onState(NetManager.State s) {}
                public void onLog(String line) { addLog(line); }
                public void onPeers(Map<Integer, NetManager.Peer> peers) {}
                public void onCode(String code) {}
            });
        }
        if (active && page == Page.STARTED && !linked()) page = Page.MAIN;
    }

    private static NetManager net() { return StarMPPlugin.net(); }
    private static boolean linked() { NetManager n = net(); return n != null && n.linked(); }

    /** Our menu steps aside while a vanilla screen (load game, settings, missions...) is up. */
    private boolean vanillaBusy() { return Vanilla.dialogOpen() || Vanilla.missionsShowing(); }

    // ------------------------------------------------------------------ input
    private int clickLogs;

    @Override public void advance(float amount, List<InputEventAPI> events) {
        if (!active || net() == null) return;
        Background.frame(); // 0.1.5.9: keep running unfocused, capped
        try { // 0.1.5.3 debug log / performance
            starmp.diag.Perf.frame("title");
            for (InputEventAPI e : events) if (e.isKeyDownEvent() && !e.isRepeat()) starmp.diag.Diag.keyEvent(e.getEventValue(), "title");
            starmp.diag.Diag.pollKeys("title");
            String note = starmp.diag.Diag.takeNote();
            if (note != null) addLog(note);
        } catch (Throwable ignored) {}
        if (replaced && !Vanilla.stillReplaced()) { replaced = false; replaceTries = 0; StarMPPlugin.flog("vanilla menu: title screen was rebuilt - replacing again"); }
        if (!replaced && replaceTries < 120) { replaceTries++; replaced = Vanilla.replaceMainMenu(); }
        if (replaced) Vanilla.keepHidden();
        Lobby.tick(net());
        if (Lobby.startedWhat != null && !net().isHost() && page != Page.STARTED && linked() && Lobby.startId != Lobby.dismissedStartId) page = Page.STARTED;
        boolean busy = vanillaBusy();
        float scale = Global.getSettings().getScreenScaleMult();
        if (clickLogs < 12) for (InputEventAPI e : events) if (e.isLMBDownEvent()) {
            clickLogs++;
            StarMPPlugin.flog("menu click: raw=" + e.getX() + "," + e.getY() + " scale=" + scale + " ui=" + Math.round(e.getX() / scale) + "," + Math.round(e.getY() / scale)
                    + " screen=" + Math.round(Gui.sw()) + "x" + Math.round(Gui.sh()) + " consumed=" + e.isConsumed() + " busy=" + busy
                    + " (dialog=" + Vanilla.dialogOpen() + " missions=" + Vanilla.missionsShowing() + ") page=" + page);
        }
        if (busy) return;
        for (InputEventAPI e : events) {
            if (e.isConsumed()) continue;
            if (e.isMouseEvent()) { gui.mouseX = e.getX(); gui.mouseY = e.getY(); } // already in UI units (UI scaling applied)
            if (e.isLMBDownEvent()) {
                focus = null;
                if (gui.click(gui.mouseX, gui.mouseY)) e.consume();
                continue;
            }
            if (e.isKeyDownEvent()) {
                if (focus != null) { typeInto(e); e.consume(); } // (Escape belongs to Starsector here: it opens its quit prompt)
            }
        }
    }

    private void back() {
        switch (page) {
            case TUTORIALS: page = Page.SINGLE; break;
            case JOIN: page = Page.MULTI; break;
            default: page = Page.MAIN;
        }
        focus = null; notice = null;
    }

    private void typeInto(InputEventAPI e) {
        int k = e.getEventValue();
        String v = "join".equals(focus) ? joinCode : (nameEdit == null ? net().playerName() : nameEdit);
        if (k == Keyboard.KEY_ESCAPE || k == Keyboard.KEY_RETURN) { if ("name".equals(focus)) saveName(); focus = null; return; }
        if (k == Keyboard.KEY_BACK) { if (!v.isEmpty()) v = e.isCtrlDown() ? "" : v.substring(0, v.length() - 1); }
        else if (k == Keyboard.KEY_V && e.isCtrlDown()) v = v + clipboard();
        else { char c = e.getEventChar(); if (c >= 32 && c < 127 && v.length() < 400) v = v + c; }
        if ("join".equals(focus)) joinCode = v; else nameEdit = v.length() > 24 ? v.substring(0, 24) : v;
    }

    private static void saveName() {
        if (nameEdit != null && !nameEdit.trim().isEmpty()) { Prefs.put("name", nameEdit.trim()); net().setName(nameEdit.trim()); }
        nameEdit = null;
    }

    private static String clipboard() {
        try { Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); return o == null ? "" : o.toString().trim(); }
        catch (Throwable t) { return ""; }
    }

    private static void copy(String s) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null); notice = "Join code copied - send it to your friend."; }
        catch (Throwable t) { notice = "Copy failed: " + t.getMessage(); }
    }

    private void vanilla(String action) {
        if (!Vanilla.action(action)) notice = "Couldn't open that screen (see starmp log).";
    }

    // ------------------------------------------------------------------ drawing
    /** Actions that start loading right away (Continue) must not run inside the title battle's advance: the
     *  loading tears the battle down mid-frame and the engine crashes ("this.<x> is null"). Run them from render. */
    private volatile String pendingAction;

    @Override public void renderInUICoords(ViewportAPI viewport) {
        String pa = pendingAction;
        if (pa != null) { pendingAction = null; StarMPPlugin.flog("menu: running " + pa + " from render"); vanilla(pa); return; }
        if (!active || net() == null) return;
        starmp.diag.Diag.captureGl();
        starmp.diag.Overlay.draw(); // F10
        gui.begin();
        try {
            if (!vanillaBusy()) draw();
        } catch (Throwable t) {
            StarMPPlugin.flog("menu render error: " + StarMPPlugin.trace(t));
            page = Page.MAIN;
        }
        gui.end();
    }

    private float cx() { return replaced ? Gui.sw() / 2f : Gui.sw() * 0.35f; }

    private void draw() {
        float H = Gui.sh();
        if (linked() && (page == Page.MULTI || page == Page.JOIN)) { drawLobby(H); return; }
        switch (page) {
            case MAIN: column(H, new String[]{"Single Player", "Multiplayer", "Options", "Quit"}, new Runnable[]{
                    () -> page = Page.SINGLE,
                    () -> { page = Page.MULTI; net().refreshSaves(); },
                    () -> { page = Page.OPTIONS; nameEdit = null; },
                    () -> { if (!Vanilla.action("QUIT")) { net().shutdown(); System.exit(0); } }}, false); break;
            case SINGLE: column(H, new String[]{"Continue", "New Game", "Load Game", "Missions", "Tutorials"}, new Runnable[]{
                    () -> pendingAction = "CONTINUE", () -> vanilla("NEW_GAME"), () -> vanilla("LOAD_GAME"),
                    () -> vanilla("MISSIONS"), () -> page = Page.TUTORIALS}, true); break;
            case TUTORIALS: column(H, new String[]{"Combat Tutorial 1", "Combat Tutorial 2", "Command Tutorial"}, new Runnable[]{
                    () -> vanilla("COMBAT_TUTORIAL_1"), () -> vanilla("COMBAT_TUTORIAL_2"), () -> vanilla("COMMAND_TUTORIAL")}, true); break;
            case OPTIONS: drawOptions(H); break;
            case MULTI: drawMulti(H); break;
            case JOIN: drawJoin(H); break;
            case STARTED: drawStarted(H); break;
        }
        gui.text("StarMP " + StarMPPlugin.VERSION, Gui.TINY, 14, 24, Gui.B_DIM);
        if (Vanilla.problem != null) gui.text("Could not hide the game's menu (please send a screenshot): " + Vanilla.problem, Gui.TINY, 14, 44, Gui.BAD);
        if (notice != null && page != Page.MULTI && page != Page.JOIN) gui.textCentered(notice, Gui.TINY, cx(), 120, Gui.B_TEXT);
    }

    /** The vanilla main-menu column: same panel, button size and spacing, same place (right of centre). */
    private void column(float H, String[] labels, Runnable[] actions, boolean withBack) {
        float W = Gui.sw(), cxs = W / 2f, cys = H / 2f;
        float pw = 246, bw = 220, bh = 30, pitch = 34;
        int rows = labels.length + (withBack ? 1 : 0);
        float ph = 32 + rows * pitch + (withBack ? 11 : 0) + 40;
        float px = cxs + 264, top = cys + 198, py = top - ph;
        if (px + pw > W - 8) px = W - 8 - pw; // very narrow screens
        Gui.bpanel(px, py, pw, ph);
        float y = top - 16 - bh;
        for (int i = 0; i < labels.length; i++) {
            gui.vbutton("c" + i, labels[i], px + 16, y, bw, bh, true, actions[i], true);
            y -= pitch;
        }
        if (withBack) gui.vbutton("back", "Back", px + 16, y - 11, bw, bh, true, this::back, true);
    }

    private void heading(String text, float y) { gui.textCentered(text, Gui.BIG, cx(), y, Gui.B_TEXT); }

    private void drawOptions(float H) {
        NetManager n = net();
        float pw = 560, ph = 460, px = cx() - pw / 2f, py = H * 0.66f - ph;
        Gui.bpanel(px, py, pw, ph);
        float x = px + 30, w = pw - 60, top = py + ph - 24;
        heading("OPTIONS", top);
        float y = top - 100;
        gui.bbutton("set", "GAME SETTINGS", x, y, (w - 20) / 3f, 40, true, () -> vanilla("SETTINGS"));
        gui.bbutton("codex", "CODEX", x + (w - 20) / 3f + 10, y, (w - 20) / 3f, 40, true, () -> vanilla("CODEX"));
        gui.bbutton("cred", "CREDITS", x + 2 * ((w - 20) / 3f + 10), y, (w - 20) / 3f, 40, true, () -> vanilla("CREDITS"));
        y -= 40;
        gui.text("MULTIPLAYER", Gui.MED, x, y, Gui.B_TEXT);
        y -= 34;
        gui.text("Your name", Gui.TINY, x, y, Gui.B_DIM);
        y -= 52;
        String nm = nameEdit == null ? n.playerName() : nameEdit;
        gui.bfield("name", nm, "your name", x, y, w - 130, 32, "name".equals(focus), null, () -> { focus = "name"; if (nameEdit == null) nameEdit = n.playerName(); });
        gui.bbutton("savename", "SAVE", x + w - 120, y, 120, 32, true, TitleMenu::saveName);
        y -= 50;
        boolean lan = Prefs.getBool("lan", false);
        gui.bbutton("lan", (lan ? "[x]" : "[  ]") + "  LAN / direct only (no internet relays)", x, y, w, 30, true, () -> Prefs.put("lan", String.valueOf(!Prefs.getBool("lan", false))));
        y -= 40;
        float ly = starmp.campaign.FriendHud.rangeLy();
        gui.bbutton("frange", "Friend markers in hyperspace: " + (ly <= 0 ? "off" : (ly == (int) ly ? String.valueOf((int) ly) : String.valueOf(ly)) + " light-years"), x, y, w, 30, true, () -> {
            float[] steps = {1f, 2.5f, 5f, 10f, 0f};
            float cur = starmp.campaign.FriendHud.rangeLy(); int i = 0;
            while (i < steps.length && steps[i] != cur) i++;
            Prefs.put("friendRangeLy", String.valueOf(steps[(i + 1) % steps.length]));
        });
        if (starmp.diag.Diag.active()) { // 0.1.5.8: not shown in Logger Disabled builds
        y -= 40; // 0.1.5.3 debug log
        gui.bbutton("dbg", "Debug log (F9 bug report, F10 overlay, crash report): " + (starmp.diag.Diag.enabled() ? "ON" : "OFF"), x, y, w, 30, true, () -> {
            Prefs.put("debugLog", String.valueOf(!starmp.diag.Diag.enabled())); starmp.diag.Diag.settingsChanged(); });
        y -= 40;
        gui.bbutton("dbgwhere", "Crash / bug reports go to: " + reportPlaceLabel(), x, y, w, 30, starmp.diag.Diag.enabled(), () -> {
            String[] o = {"mods", "desktop", "off"}; String cur = starmp.diag.Diag.reportPlace(); int i = 0;
            while (i < o.length && !o[i].equals(cur)) i++;
            Prefs.put("crashReport", o[(i + 1) % o.length]); starmp.diag.Diag.settingsChanged(); });
        }
        gui.bbutton("back", "BACK", px, py - 48, 140, 34, true, this::back);
    }

    public static String reportPlaceLabel() {
        String p = starmp.diag.Diag.reportPlace();
        return "desktop".equals(p) ? "Desktop" : "off".equals(p) ? "no crash report (F9 still saves to StarMP/player logs)" : "StarMP/crash logs + player logs";
    }

    private void drawMulti(float H) {
        NetManager n = net();
        float top0 = H * 0.72f; // below the Starsector logo
        heading("MULTIPLAYER", top0);
        // name strip (Brotato: "Your name" + salmon box)
        String nm = nameEdit == null ? n.playerName() : nameEdit;
        gui.text("Your name", Gui.TINY, cx() - 200, top0 - 42, Gui.B_DIM);
        gui.bfield("name", nm, "your name", cx() - 120, top0 - 62, 260, 26, "name".equals(focus), null, () -> { focus = "name"; if (nameEdit == null) nameEdit = n.playerName(); });

        float pw = 560, ph = 330, px = cx() - pw / 2f, py = top0 - 90 - ph;
        Gui.bpanel(px, py, pw, ph);
        float x = px + 26, w = pw - 52, top = py + ph - 22;
        heading("IROH", top);
        String d1 = "Direct peer-to-peer connection with low latency. No port forwarding,";
        String d2 = "no VPN - share a join code with your friend. Also works on a LAN.";
        gui.textCentered(d1, Gui.TINY, cx(), top - 44, Gui.B_DIM);
        boolean viaLauncher = starmp.diag.Diag.launcherOk();
        if (viaLauncher) gui.textCentered(d2, Gui.TINY, cx(), top - 60, Gui.B_DIM);
        else gui.textCentered(starmp.diag.Diag.NEED_LAUNCHER, Gui.TINY, cx(), top - 60, Gui.GOLD);
        float by = top - 122, bw = (w - 16) / 2f;
        boolean idle = n.state() == NetManager.State.IDLE;
        gui.bbutton("host", "HOST GAME", x, by, bw, 44, idle && viaLauncher, () -> { saveNameIfEditing(); n.host(Prefs.getBool("lan", false)); });
        gui.bbutton("join", "JOIN GAME", x + bw + 16, by, bw, 44, idle && viaLauncher, () -> { saveNameIfEditing(); page = Page.JOIN; String c = clipboard(); joinCode = c.startsWith("SM1:") ? c : ""; notice = null; });
        boolean lan = Prefs.getBool("lan", false);
        gui.bbutton("lan", (lan ? "[x]" : "[  ]") + "  LAN / direct only (no internet relays)", x, by - 36, w, 26, true,
                () -> Prefs.put("lan", String.valueOf(!Prefs.getBool("lan", false))));
        gui.text("Public games", Gui.TINY, x, by - 52, Gui.B_TEXT);
        float boxH = by - 66 - (py + 18);
        Gui.rect(x, py + 18, w, boxH, Gui.B_BOX);
        gui.text("Iroh games are private: join with the host's code.", Gui.TINY, x + 10, py + 18 + boxH - 10, Gui.B_DIM);
        String state = n.state() == NetManager.State.STARTING ? "Starting host..." : n.state() == NetManager.State.JOINING ? "Joining..." : null;
        if (state != null) gui.text(state, Gui.TINY, x + 10, py + 18 + boxH - 30, Gui.B_TEXT);
        gui.bbutton("back", "BACK", px, py - 48, 140, 34, true, this::back);
    }

    private void saveNameIfEditing() { if (nameEdit != null) saveName(); focus = null; }

    private void drawJoin(float H) {
        NetManager n = net();
        float pw = 560, ph = 250, px = cx() - pw / 2f, py = H * 0.62f - ph;
        Gui.bpanel(px, py, pw, ph);
        float x = px + 26, w = pw - 52, top = py + ph - 22;
        heading("JOIN IROH GAME", top);
        gui.text("Copy the host's join code, then press PASTE.", Gui.TINY, x, top - 46, Gui.B_DIM);
        float fy = top - 96;
        gui.bfield("join", joinCode, "no code pasted yet", x, fy, w - 110, 32, "join".equals(focus), null, () -> focus = "join");
        gui.bbutton("paste", "PASTE", x + w - 100, fy, 100, 32, true, () -> { joinCode = clipboard(); notice = null; });
        boolean ok = joinCode.startsWith("SM1:");
        gui.bbutton("dojoin", n.state() == NetManager.State.JOINING ? "CONNECTING..." : "JOIN", x, fy - 56, w, 42,
                ok && n.state() == NetManager.State.IDLE, () -> n.join(joinCode));
        if (!joinCode.isEmpty() && !ok) gui.text("That isn't a StarMP join code (it starts with SM1:).", Gui.TINY, x, fy - 66, Gui.GOOD);
        synchronized (log) { if (!log.isEmpty() && log.getLast().startsWith("ERROR")) gui.text(log.getLast(), Gui.TINY, x, py + 64, Gui.GOOD); }
        gui.bbutton("back", "BACK", x, py + 16, 120, 30, true, this::back);
    }

    private void drawLobby(float H) {
        NetManager n = net();
        float pw = 680, ph = 470, px = cx() - pw / 2f, py = H * 0.68f - ph;
        Gui.bpanel(px, py, pw, ph);
        float x = px + 26, w = pw - 52, top = py + ph - 22;
        heading(n.isHost() ? "YOUR LOBBY  (IROH)" : "LOBBY  (IROH)", top);
        float y = top - 48;
        if (n.isHost()) {
            gui.text("Join code - send this to your friend:", Gui.TINY, x, y, Gui.B_DIM);
            y -= 50;
            gui.bfield("code", n.code().isEmpty() ? "" : n.code(), "starting...", x, y, w - 110, 26, false, null, null);
            gui.bbutton("copy", "COPY", x + w - 100, y, 100, 26, !n.code().isEmpty(), () -> copy(n.code()));
            y -= 22;
        }
        // everyone: direct links plus (for guests) the other guests the host tells us about
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>(n.peerIds());
        ids.addAll(Lobby.members.keySet());
        ids.remove(n.myId());
        int count = 1 + ids.size();
        gui.text("Players " + count + "/10", Gui.TINY, x, y - 6, Gui.B_TEXT);
        y -= 28;
        y = playerRow(x, y, w, n.playerName() + (n.isHost() ? "  (you, host)" : "  (you)"), Lobby.myReady, -1, StarMPPlugin.VERSION, false);
        for (Integer id : ids) {
            NetManager.Peer p = n.peer(id);
            Lobby.Member m = Lobby.members.get(id);
            if (p == null && m == null) continue;
            String name = (p != null ? p.name : m.name) + (id == 0 ? "  (host)" : "");
            String ver = m != null ? m.version : "";
            boolean mismatch = m != null && !m.version.isEmpty() && !m.version.equals(StarMPPlugin.VERSION);
            boolean modsDiffer = m != null && !m.mods.isEmpty() && !m.mods.equals(Lobby.modList());
            if (modsDiffer) { name = name + "  - DIFFERENT MODS"; mismatch = true; }
            y = playerRow(x, y, w, name, m != null && m.ready, p != null ? p.pingMs : -1, ver, mismatch);
        }
        if (count < 2) { gui.text("-  open slot", Gui.TINY, x + 6, y, Gui.B_DIM); y -= 20; }

        String saveLine = n.isHost()
                ? (n.newestSave.isEmpty() ? "No campaign save found - play once and save first." : "Campaign save to share: " + NetManager.saveLabel(n.newestSave) + " (your newest)")
                : (n.saveStatus.isEmpty() ? "The host picks Saved Campaign, New Campaign or Battle." : n.saveStatus);
        if (n.sharedSaves > 0) saveLine = NetManager.SHARED_SAVES_WARNING;
        gui.text(saveLine, Gui.TINY, x, py + 172, Gui.B_DIM);
        if (n.isHost()) {
            gui.text("SAVED CAMPAIGN: share your newest save.  NEW CAMPAIGN: make a new game, sent at the map.", Gui.TINY, x, py + 154, Gui.B_DIM);
            gui.text("START BATTLE: everyone starts any mission; your battle appears on their screens.", Gui.TINY, x, py + 138, Gui.B_DIM);
        } else gui.text("Press READY. The host starts the game.", Gui.TINY, x, py + 100, Gui.B_DIM);
        if (notice != null) gui.text(notice, Gui.TINY, x, py + 66, Gui.B_TEXT);

        float ay = py + 16, bh = 34;
        boolean allReady = Lobby.myReady && !Lobby.members.isEmpty();
        for (Lobby.Member m : Lobby.members.values()) allReady &= m.ready;
        gui.bbutton("close", n.isHost() ? "CLOSE LOBBY" : "LEAVE", x, ay, 130, bh, true, () -> { n.disconnect(); Lobby.myReady = false; notice = null; });
        gui.bbutton("ready", Lobby.myReady ? "NOT READY" : "READY", x + 140, ay, 120, bh, true, () -> { Lobby.myReady = !Lobby.myReady; Lobby.sendNow(n); });
        if (n.isHost()) {
            gui.bbutton("startn", "NEW CAMPAIGN", x + w - 320, ay + bh + 8, 170, bh, allReady, () -> start("newcampaign"));
            gui.bbutton("startc", "SAVED CAMPAIGN", x + w - 320, ay, 170, bh, allReady, () -> start("campaign"));
            gui.bbutton("startb", "START BATTLE", x + w - 140, ay, 140, bh, allReady, () -> start("battle"));
        } else if (Lobby.startedWhat != null) { // the host's game is running: one click back to it (0.1.4.6)
            String lbl = "battle".equals(Lobby.startedWhat) ? "BACK TO BATTLE" : "BACK TO GAME";
            gui.bbutton("rejoin", lbl, x + w - 200, ay, 200, bh, true, () -> { Lobby.dismissedStartId = -1; page = Page.STARTED; });
        }
        gui.bbutton("back", "BACK", px, py - 48, 140, 34, true, () -> { page = Page.MAIN; notice = null; });
    }

    private float playerRow(float x, float y, float w, String name, boolean ready, int ping, String version, boolean mismatch) {
        gui.text("•  " + name, Gui.TINY, x + 6, y, Gui.B_TEXT);
        if (version != null && !version.isEmpty()) gui.text("v" + version, Gui.TINY, x + w - 300, y, mismatch ? Gui.BAD : Gui.B_DIM);
        gui.text(ready ? "READY" : "not ready", Gui.TINY, x + w - 190, y, ready ? Gui.GOOD : Gui.B_DIM);
        if (ping >= 0) gui.text(ping + " ms", Gui.TINY, x + w - 70, y, Gui.B_DIM);
        return y - 20;
    }

    private void start(String what) {
        NetManager n = net();
        if ("campaign".equals(what)) { n.sendSave(""); Lobby.hostCampaignReady = true; }
        if ("newcampaign".equals(what)) { starmp.campaign.HotJoin.sendOnNewGame = true; vanilla("NEW_GAME"); }
        Lobby.hostStart(n, what);
        StarMPPlugin.flog("lobby: host pressed Start " + what);
        page = Page.STARTED;
    }

    /** After START: what to do next, with the vanilla screen one click away. */
    private void drawStarted(float H) {
        NetManager n = net();
        boolean fresh = "newcampaign".equals(Lobby.startedWhat);
        boolean campaign = fresh || "campaign".equals(Lobby.startedWhat);
        float pw = 620, ph = 220, px = cx() - pw / 2f, py = H * 0.62f - ph;
        Gui.bpanel(px, py, pw, ph);
        float x = px + 26, w = pw - 52, top = py + ph - 22;
        heading(campaign ? "SHARED CAMPAIGN" : "CO-OP BATTLE", top);
        String l1, l2;
        if (campaign) {
            String save = n.isHost() ? n.newestSave : n.receivedSave;
            if (fresh && n.isHost()) l1 = "Create your new game. It is sent to everyone as soon as you reach the map.";
            else if (fresh && save.isEmpty()) l1 = "The host is creating a new campaign - it arrives when they reach the map.";
            else l1 = save.isEmpty() ? "Waiting for the host's save..." : "Load the save: " + NetManager.saveLabel(save);
            l2 = n.sharedSaves > 0 ? NetManager.SHARED_SAVES_WARNING : n.saveStatus;
        } else {
            l1 = "Start any mission. The host's battle appears on your screen.";
            l2 = n.isHost() ? "Deploy and unpause - pause is shared." : "The host unpauses.";
        }
        gui.textCentered(l1, Gui.TINY, cx(), top - 50, Gui.B_TEXT);
        if (l2 != null && !l2.isEmpty()) gui.textCentered(l2, Gui.TINY, cx(), top - 70, Gui.B_DIM);
        if (fresh && n.isHost()) gui.bbutton("go", "NEW GAME", x, py + 20, w - 160, 44, true, () -> vanilla("NEW_GAME"));
        else gui.bbutton("go", campaign ? "LOAD GAME" : "MISSIONS", x, py + 20, w - 160, 44,
                !campaign || n.isHost() || !n.receivedSave.isEmpty(), () -> vanilla(campaign ? "LOAD_GAME" : "MISSIONS"));
        gui.bbutton("lobby", "LOBBY", x + w - 150, py + 20, 150, 44, true, () -> { Lobby.backToLobby(n); page = Page.MULTI; });
    }
}
