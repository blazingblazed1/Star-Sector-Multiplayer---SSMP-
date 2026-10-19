package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;
import starmp.ui.Gui;

/**
 * 0.1.4.10: the scoreboard. Press the scoreboard key (default ` / ~, change it in F8 - Console Commands also uses
 * backtick) on the campaign map to open/close it. One row per player: colour, portrait, name, where they are, fleet
 * size, ping. Click another player's row: contact them from anywhere (send credits, trade - with the out-of-system
 * tax when they're in another star system). Click your own row: next portrait.
 */
public final class Scoreboard implements CampaignUIRenderingListener, CampaignInputListener {
    private static boolean open;
    private final Gui gui = new Gui();
    // 0.1.5.3: F9 is the bug-report key now (and F10 the debug overlay), so they're no longer scoreboard choices
    public static final int[] KEYS = {Keyboard.KEY_GRAVE, Keyboard.KEY_F7, Keyboard.KEY_INSERT, Keyboard.KEY_HOME};
    private static final Color[] COLORS = {new Color(90, 230, 120), new Color(230, 180, 60), new Color(220, 90, 220), new Color(80, 200, 230),
            new Color(240, 120, 80), new Color(170, 140, 255), new Color(140, 230, 60), new Color(255, 110, 150), new Color(200, 200, 200)};

    public static void register() {
        Global.getSector().getListenerManager().removeListenerOfClass(Scoreboard.class);
        Global.getSector().getListenerManager().addListener(new Scoreboard(), true);
    }

    public static int key() {
        try { int k = Integer.parseInt(Prefs.get("scoreKey", String.valueOf(Keyboard.KEY_GRAVE))); return k == Keyboard.KEY_F9 || k == Keyboard.KEY_F10 ? Keyboard.KEY_GRAVE : k; }
        catch (Throwable t) { return Keyboard.KEY_GRAVE; }
    }
    public static String keyName() { int k = key(); return k == Keyboard.KEY_GRAVE ? "` / ~" : Keyboard.getKeyName(k); }
    public static void cycleKey() {
        int k = key(), next = KEYS[0];
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == k) { next = KEYS[(i + 1) % KEYS.length]; break; }
        Prefs.put("scoreKey", String.valueOf(next));
    }

    /** Stable colour per player name (the same on every machine). */
    public static Color colorFor(String name) { return COLORS[Math.floorMod(PlayerRecords.key(name).hashCode(), COLORS.length)]; }

    private static boolean linked() { NetManager n = StarMPPlugin.net(); return n != null && n.linked(); }
    private static boolean screenBusy() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return ui == null || ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null || ui.getCurrentInteractionDialog() != null;
    }

    // ------------------------------------------------------------------ input
    @Override public int getListenerInputPriority() { return 10; }
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (!linked()) { open = false; return; }
        for (InputEventAPI e : events) {
            if (e.isConsumed()) continue;
            if (e.isKeyDownEvent() && !e.isRepeat() && e.getEventValue() == key()) {
                if (screenBusy()) continue;
                open = !open; e.consume(); continue;
            }
            if (!open) continue;
            if (editing != null && e.isKeyboardEvent()) { // 0.9.0.0: typing a new name
                if (e.isKeyDownEvent()) {
                    int k = e.getEventValue();
                    if (k == Keyboard.KEY_RETURN || k == Keyboard.KEY_NUMPADENTER) { String why = starmp.Session.rename(editing.toString()); hint = why == null ? "Name change sent." : "Name not changed: " + why; editing = null; }
                    else if (k == Keyboard.KEY_ESCAPE) editing = null;
                    else if (k == Keyboard.KEY_BACK) { if (editing.length() > 0) editing.setLength(editing.length() - 1); }
                    else { char c = e.getEventChar(); if (c >= 32 && c != 127 && editing.length() < 24) editing.append(c); }
                }
                e.consume(); continue;
            }
            if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) { if (picking) picking = false; else open = false; e.consume(); continue; }
            if (picking && e.isMouseScrollEvent()) { pickPage = Math.max(0, pickPage + (e.getEventValue() > 0 ? -1 : 1)); e.consume(); continue; }
            if (e.isMouseEvent()) { gui.mouseX = e.getX(); gui.mouseY = e.getY(); }
            if (e.isLMBDownEvent() && gui.click(gui.mouseX, gui.mouseY)) e.consume();
        }
    }
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) {}

    // ------------------------------------------------------------------ drawing
    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}
    @Override public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {}
    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {
        if (!open || !linked() || screenBusy()) { editing = null; picking = false; hint = null; return; }
        try { draw(); } catch (Throwable t) { open = false; StarMPPlugin.flog("scoreboard: " + StarMPPlugin.trace(t)); }
    }

    private static final class Row { int peer; String name, portrait = "", where = "", fleet = "", ping = ""; boolean me, host; }

    private List<Row> rows(NetManager net) {
        List<Row> out = new ArrayList<>();
        SectorAPI sector = Global.getSector();
        Row me = new Row(); me.me = true; me.peer = net.myId(); me.name = net.playerName(); me.host = net.isHost();
        me.portrait = sector.getPlayerPerson().getPortraitSprite();
        me.where = sector.getPlayerFleet().getContainingLocation() == null ? "" : sector.getPlayerFleet().getContainingLocation().getName();
        me.fleet = sector.getPlayerFleet().getFleetData().getNumMembers() + " ships";
        out.add(me);
        Set<Integer> ids = new TreeSet<>(CoopCampaign.fleets.keySet());
        ids.addAll(net.peerIds());
        ids.remove(net.myId());
        for (int id : ids) {
            CampWire.Fleet f = CoopCampaign.fleets.get(id);
            if (f != null && System.nanoTime() - f.recvNs > 30_000_000_000L && !net.peerIds().contains(id)) continue; // long gone
            Row r = new Row(); r.peer = id; r.host = id == 0;
            r.name = starmp.combat.CoopState.nameOf(net, id);
            CampWire.Hello h = CoopCampaign.hellos.get(id);
            if (h != null && h.portrait != null) r.portrait = h.portrait;
            if (f != null) { LocationAPI l = CoopCampaign.location(f.loc); r.where = l == null ? f.loc : l.getName(); }
            CampWire.Comp c = CoopCampaign.comps.get(id);
            if (c != null) r.fleet = c.hulls.size() + " ships";
            NetManager.Peer p = net.peer(id);
            if (p != null && p.pingMs >= 0) r.ping = p.pingMs + " ms";
            if (CoopCampaign.playerInBattle(id)) r.where = r.where + "  (IN BATTLE)";
            out.add(r);
        }
        return out;
    }

    // ---- 0.9.0.0: own portrait picker + name change
    private static StringBuilder editing;
    public static boolean editingName() { return open && editing != null; }
    private static boolean picking;
    private static int pickPage;
    private static String hint;
    private static List<String> allPortraits;

    /** Every portrait in the game: all factions' portraits, both genders, no duplicates. */
    private static List<String> portraits() {
        if (allPortraits != null) return allPortraits;
        Set<String> s = new LinkedHashSet<>();
        try {
            for (com.fs.starfarer.api.campaign.FactionAPI f : Global.getSector().getAllFactions())
                for (FullName.Gender g : new FullName.Gender[]{FullName.Gender.MALE, FullName.Gender.FEMALE})
                    try { s.addAll(f.getPortraits(g).getItems()); } catch (Throwable ignored) {}
        } catch (Throwable t) { StarMPPlugin.flog("scoreboard: portrait list: " + t); }
        allPortraits = new ArrayList<>(s);
        StarMPPlugin.flog("scoreboard: " + allPortraits.size() + " portraits to choose from");
        return allPortraits;
    }

    private void drawPicker() {
        List<String> all = portraits();
        int cols = 10, rowsN = 5, per = cols * rowsN;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        pickPage = Math.min(pickPage, pages - 1);
        float cell = 72, gap = 8, w = cols * (cell + gap) + 40, h = rowsN * (cell + gap) + 110;
        float x = Gui.sw() / 2f - w / 2f, y = Gui.sh() / 2f - h / 2f;
        gui.begin();
        Gui.bpanel(x, y, w, h);
        gui.textCentered("CHOOSE YOUR PORTRAIT  (" + (pickPage + 1) + "/" + pages + ")", Gui.MED, x + w / 2f, y + h - 16, Gui.B_TEXT);
        String mine = Global.getSector().getPlayerPerson().getPortraitSprite();
        for (int i = 0; i < per; i++) {
            int idx = pickPage * per + i;
            if (idx >= all.size()) break;
            final String path = all.get(idx);
            float cx = x + 20 + (i % cols) * (cell + gap), cy = y + h - 56 - (i / cols + 1) * (cell + gap);
            boolean hot = gui.over(cx, cy, cell, cell);
            Gui.rect(cx - 2, cy - 2, cell + 4, cell + 4, path.equals(mine) ? new Color(90, 230, 120, 220) : hot ? new Color(110, 170, 255, 200) : new Color(20, 40, 60, 200));
            portrait(path, cx, cy, cell);
            gui.hit("p" + idx, cx, cy, cell, cell, () -> {
                Global.getSector().getPlayerPerson().setPortraitSprite(path);
                CoopCampaign.helloNow = true; picking = false; hint = "Portrait changed.";
            });
        }
        gui.bbutton("prev", "< PREV", x + 20, y + 14, 120, 30, pickPage > 0, () -> pickPage--);
        gui.bbutton("next", "NEXT >", x + 150, y + 14, 120, 30, pickPage < pages - 1, () -> pickPage++);
        gui.bbutton("cancel", "BACK", x + w - 140, y + 14, 120, 30, true, () -> picking = false);
        gui.end();
    }

    private void draw() {
        if (picking) { drawPicker(); return; }
        NetManager net = StarMPPlugin.net();
        List<Row> rows = rows(net);
        float rowH = 58, w = 760, h = 70 + rows.size() * rowH + 30;
        float x = Gui.sw() / 2f - w / 2f, y = Gui.sh() / 2f - h / 2f;
        gui.begin();
        Gui.bpanel(x, y, w, h);
        gui.textCentered("PLAYERS  (" + rows.size() + "/" + starmp.Session.current().maxPlayers + ")", Gui.MED, x + w / 2f, y + h - 16, Gui.B_TEXT);
        float ty = y + h - 56;
        for (Row r : rows) {
            Color col = colorFor(r.name);
            final Row rr = r;
            gui.vbutton("row" + r.peer, "", x + 14, ty - rowH + 6, w - 28, rowH - 6, true, () -> clicked(rr), false);
            Gui.rect(x + 14, ty - rowH + 6, 6, rowH - 6, col);
            portrait(r.portrait, x + 28, ty - rowH + 8, rowH - 10);
            if (r.me && editing != null) {
                String caret = (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
                Gui.rect(x + 86, ty - 28, 300, 24, new Color(2, 8, 14, 230));
                Gui.frame(x + 86, ty - 28, 300, 24, Gui.B_TEXT);
                gui.text(editing + caret, Gui.SMALL, x + 90, ty - 8, Color.WHITE);
            } else gui.text(r.name + (r.me ? "  (you)" : "") + (r.host ? "  (host)" : ""), Gui.SMALL, x + 90, ty - 8, col);
            if (r.me) { // portrait: picker; name: rename (5 min cooldown)
                gui.hit("pic", x + 28, ty - rowH + 8, rowH - 10, rowH - 10, () -> { picking = true; pickPage = 0; });
                gui.hit("name", x + 86, ty - 30, 320, 28, () -> {
                    long wait = starmp.Session.renameWaitMs();
                    if (wait > 0) hint = "You can change your name again in " + (wait / 60000) + ":" + String.format("%02d", (wait / 1000) % 60) + ".";
                    else { editing = new StringBuilder(net.playerName()); hint = "Type your new name, Enter to save, Esc to cancel."; }
                });
            }
            gui.text(r.where.isEmpty() ? "-" : r.where, Gui.TINY, x + 90, ty - 30, Gui.B_DIM);
            gui.text(r.fleet, Gui.TINY, x + w - 260, ty - 18, Gui.B_TEXT);
            gui.text(r.ping, Gui.TINY, x + w - 120, ty - 18, Gui.B_DIM);
            ty -= rowH;
        }
        String rn = starmp.Session.renameNotice;
        if (rn != null) { hint = rn; starmp.Session.renameNotice = null; }
        gui.textCentered(hint != null ? hint : "Click a player: contact.  Your portrait: change.  Your name: rename (every 5 min).  " + keyName() + " / Esc: close",
                Gui.TINY, x + w / 2f, y + 22, hint != null ? Gui.B_TEXT : Gui.B_DIM);
        gui.end();
    }

    private static void portrait(String path, float x, float y, float size) {
        if (path == null || path.isEmpty()) return;
        try {
            SpriteAPI s = Global.getSettings().getSprite(path);
            if (s == null || s.getWidth() <= 0) return;
            s.setSize(size, size);
            s.render(x, y);
        } catch (Throwable ignored) {}
    }

    private void clicked(Row r) {
        if (r.me) return; // portrait and name have their own click areas
        open = false;
        PlayerFleetDialog.open(r.peer, r.name);
    }

    /** Own row: next portrait of the player faction for the character's gender (sent to others with HELLO). */
    private static void nextPortrait() {
        PersonAPI p = Global.getSector().getPlayerPerson();
        FullName.Gender g = p.getGender() == FullName.Gender.FEMALE ? FullName.Gender.FEMALE : FullName.Gender.MALE;
        List<String> all = new ArrayList<>(Global.getSector().getPlayerFaction().getPortraits(g).getItems());
        if (all.isEmpty()) return;
        int i = all.indexOf(p.getPortraitSprite());
        p.setPortraitSprite(all.get((i + 1) % all.size()));
        CoopCampaign.helloNow = true;
    }
}
