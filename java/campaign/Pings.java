package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ViewportAPI;
import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.lwjgl.opengl.GL11;
import starmp.NetManager;
import starmp.Prefs;
import starmp.Session;
import starmp.StarMPPlugin;
import starmp.ui.Gui;

/**
 * 0.9.0.0: map pings. Middle-click (or the F8-chosen key) marks a spot; every player looking at the same place sees an
 * expanding ring in the pinger's colour with their name for 6 s, with a short sound. Three layers, each switchable in
 * F8 per player: combat (the battle you are in), system (inside a star system), hyperspace. The host can turn pings
 * off for everyone. Pings off screen show as an arrow at the screen edge.
 *
 * PING (36): [36][layer][battle owner][x float][y float][location id] - sent to everyone (the host passes it on).
 */
public final class Pings {
    private Pings() {}
    public static final byte PING = 36;
    public static final int COMBAT = 0, SYSTEM = 1, HYPER = 2;
    private static final String[] LAYER = {"combat", "star systems", "hyperspace"};
    private static final float LIFE = 6f;

    private static final class Ping { int from, layer, battle; String loc, name; float x, y; long t0; Color color; }
    private static final List<Ping> active = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static long lastSentNs;

    public static String layerName(int l) { return LAYER[l]; }
    public static boolean layerOn(int l) { return Prefs.getBool("ping" + l, true); }
    public static void toggle(int l) { Prefs.put("ping" + l, String.valueOf(!layerOn(l))); }
    public static boolean allowed() { return Session.current().pings; }

    /** The ping key: middle mouse (2002) by default. */
    public static int key() { try { return Integer.parseInt(Prefs.get("pingKey", "2002")); } catch (Throwable t) { return 2002; } }

    /** Mark a spot (world coordinates of the view the player is looking at). */
    public static void send(int layer, String loc, float x, float y, int battleOwner) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked() || !allowed() || !layerOn(layer)) return;
        long now = System.nanoTime();
        if (now - lastSentNs < 700_000_000L) return; // no spam
        lastSentNs = now;
        byte[] l = (loc == null ? "" : loc).getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(13 + l.length).put(PING).put((byte) layer).put((byte) battleOwner).putFloat(x).putFloat(y).putShort((short) l.length).put(l);
        n.sendAll(b.array());
        add(n.myId(), n.playerName(), layer, battleOwner, loc, x, y);
    }

    public static void onPacket(NetManager net, int peer, byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d); b.get();
            int layer = b.get(), battle = b.get() & 0xFF; float x = b.getFloat(), y = b.getFloat();
            byte[] l = new byte[b.getShort() & 0xFFFF]; b.get(l);
            if (layer < 0 || layer > 2 || !layerOn(layer) || !allowed()) return;
            if (Relations.hostileTo(peer)) return; // 0.9.2.1: no pings from hostile players
            String sender = starmp.combat.CoopState.nameOf(net, peer);
            if (Alliances.of(sender) != null && !Alliances.same(sender, net.playerName())) return; // 0.9.2.2: alliance-only pings
            add(peer, starmp.combat.CoopState.nameOf(net, peer), layer, battle, new String(l, StandardCharsets.UTF_8), x, y);
        } catch (Throwable t) { StarMPPlugin.flog("pings: bad packet from " + peer + ": " + t); }
    }

    private static void add(int from, String name, int layer, int battle, String loc, float x, float y) {
        Ping p = new Ping();
        p.from = from; p.name = name == null ? "Player " + from : name; p.layer = layer; p.battle = battle; p.loc = loc; p.x = x; p.y = y;
        p.t0 = System.nanoTime(); p.color = Scoreboard.colorFor(p.name);
        active.removeIf(o -> o.from == from && o.layer == layer); // one live ping per player per layer
        active.add(p);
        if (visibleHere(p)) { try { Global.getSoundPlayer().playUISound("ui_button_rally", 1.2f, 0.8f); } catch (Throwable ignored) {} }
        StarMPPlugin.flog("ping: " + p.name + " " + LAYER[layer] + " " + loc + " @" + Math.round(x) + "," + Math.round(y));
    }

    private static boolean visibleHere(Ping p) {
        try {
            if (p.layer == COMBAT) return Global.getCombatEngine() != null && starmp.combat.CoopState.battleOwner == p.battle && Global.getCurrentState() == com.fs.starfarer.api.GameState.COMBAT;
            com.fs.starfarer.api.campaign.CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            return me != null && me.getContainingLocation() != null && me.getContainingLocation().getId().equals(p.loc);
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ drawing
    private static final Gui gui = new Gui();

    /** Campaign map (UI coordinates, below the UI). */
    public static void drawCampaign(ViewportAPI vp, String locId) {
        if (active.isEmpty() || vp == null) return;
        for (Ping p : active) if (p.layer != COMBAT && p.loc.equals(locId)) draw(vp, p);
        expire();
    }

    /** Combat (UI coordinates). */
    public static void drawCombat(ViewportAPI vp, int battleOwner) {
        if (active.isEmpty() || vp == null) return;
        for (Ping p : active) if (p.layer == COMBAT && p.battle == battleOwner) draw(vp, p);
        expire();
    }

    private static void expire() { long now = System.nanoTime(); active.removeIf(p -> (now - p.t0) / 1e9 > LIFE); }

    private static void draw(ViewportAPI vp, Ping p) {
        float age = (float) ((System.nanoTime() - p.t0) / 1e9);
        if (age > LIFE) return;
        float alpha = age > LIFE - 1f ? LIFE - age : 1f;
        float sx = vp.convertWorldXtoScreenX(p.x), sy = vp.convertWorldYtoScreenY(p.y);
        float W = Gui.sw(), H = Gui.sh(), m = 40f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        boolean on = sx > m && sx < W - m && sy > m && sy < H - m;
        Color c = p.color;
        gui.begin();
        if (on) {
            for (int k = 0; k < 3; k++) { // three rings growing outwards
                float ph = ((age * 1.2f) + k / 3f) % 1f;
                ring(sx, sy, 8f + ph * 46f, c, alpha * (1f - ph));
            }
            diamond(sx, sy, 7f, c, alpha);
            gui.textCentered(p.name, Gui.TINY, sx + 1, sy + 30, new Color(0, 0, 0, (int) (200 * alpha)));
            gui.textCentered(p.name, Gui.TINY, sx, sy + 31, withAlpha(c, alpha));
        } else { // off screen: arrow at the edge, pointing at it
            float cx = W / 2f, cy = H / 2f, dx = sx - cx, dy = sy - cy;
            float k = Math.min((W / 2f - m) / Math.max(1e-3f, Math.abs(dx)), (H / 2f - m) / Math.max(1e-3f, Math.abs(dy)));
            float ex = cx + dx * k, ey = cy + dy * k, len = (float) Math.sqrt(dx * dx + dy * dy), ux = dx / len, uy = dy / len;
            color(c, alpha);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2f(ex + ux * 14, ey + uy * 14);
            GL11.glVertex2f(ex - uy * 9, ey + ux * 9);
            GL11.glVertex2f(ex + uy * 9, ey - ux * 9);
            GL11.glEnd();
            gui.textCentered(p.name, Gui.TINY, ex - ux * 22, ey - uy * 22 + 6, withAlpha(c, alpha));
        }
        gui.end();
    }

    private static void ring(float x, float y, float r, Color c, float a) {
        color(c, a);
        GL11.glLineWidth(2.5f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 36; i++) { double t = 2 * Math.PI * i / 36; GL11.glVertex2f(x + (float) Math.cos(t) * r, y + (float) Math.sin(t) * r); }
        GL11.glEnd();
    }

    private static void diamond(float x, float y, float r, Color c, float a) {
        color(c, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y + r); GL11.glVertex2f(x + r, y); GL11.glVertex2f(x, y - r); GL11.glVertex2f(x - r, y);
        GL11.glEnd();
    }

    private static Color withAlpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, (int) (255 * a)))); }
    private static void color(Color c, float a) { GL11.glColor4ub((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) Math.max(0, Math.min(255, (int) (255 * a)))); }
}
