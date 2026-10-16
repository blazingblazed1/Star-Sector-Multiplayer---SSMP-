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
            if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) { open = false; e.consume(); continue; }
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
        if (!open || !linked() || screenBusy()) return;
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

    private void draw() {
        NetManager net = StarMPPlugin.net();
        List<Row> rows = rows(net);
        float rowH = 58, w = 760, h = 70 + rows.size() * rowH + 30;
        float x = Gui.sw() / 2f - w / 2f, y = Gui.sh() / 2f - h / 2f;
        gui.begin();
        Gui.bpanel(x, y, w, h);
        gui.textCentered("PLAYERS  (" + rows.size() + "/10)", Gui.MED, x + w / 2f, y + h - 16, Gui.B_TEXT);
        float ty = y + h - 56;
        for (Row r : rows) {
            Color col = colorFor(r.name);
            final Row rr = r;
            gui.vbutton("row" + r.peer, "", x + 14, ty - rowH + 6, w - 28, rowH - 6, true, () -> clicked(rr), false);
            Gui.rect(x + 14, ty - rowH + 6, 6, rowH - 6, col);
            portrait(r.portrait, x + 28, ty - rowH + 8, rowH - 10);
            gui.text(r.name + (r.me ? "  (you)" : "") + (r.host ? "  (host)" : ""), Gui.SMALL, x + 90, ty - 8, col);
            gui.text(r.where.isEmpty() ? "-" : r.where, Gui.TINY, x + 90, ty - 30, Gui.B_DIM);
            gui.text(r.fleet, Gui.TINY, x + w - 260, ty - 18, Gui.B_TEXT);
            gui.text(r.ping, Gui.TINY, x + w - 120, ty - 18, Gui.B_DIM);
            ty -= rowH;
        }
        gui.textCentered("Click a player to contact them (credits, trade).  Click yourself: next portrait.  " + keyName() + " / Esc: close.",
                Gui.TINY, x + w / 2f, y + 22, Gui.B_DIM);
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
        if (r.me) { nextPortrait(); return; }
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
