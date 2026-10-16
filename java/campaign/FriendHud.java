package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import java.awt.Color;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;
import starmp.ui.Gui;

/**
 * Campaign map: "(Player)" + name over other players' fleets, and a direction marker per friend: a white "P" in a
 * blue circle with a white outline, sitting on an invisible ring (100 map units across) around our fleet, with an
 * arrow pointing at the friend. Shown when the friend is in the same star system, or in hyperspace within the
 * range set in Options (default 2.5 light-years). Player fleets only.
 */
public final class FriendHud implements CampaignUIRenderingListener {
    public static final float UNITS_PER_LY = 2000f;
    /** Ring around our fleet, in map units (diameter 100). */
    private static final float RING_RADIUS = 50f;
    private static final float ICON_R = 11f; // marker size on screen, fixed
    private static final Color BLUE = new Color(40, 110, 225, 235), WHITE = new Color(255, 255, 255, 245),
            LABEL = new Color(110, 170, 255), SHADOW = new Color(0, 0, 0, 200);
    private final Gui gui = new Gui();
    private boolean errLogged;

    /** Hyperspace marker range in light-years (0 = markers off in hyperspace). */
    public static float rangeLy() {
        try { return Float.parseFloat(Prefs.get("friendRangeLy", "2.5")); } catch (Throwable t) { return 2.5f; }
    }

    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {
        long t0 = starmp.diag.Perf.begin();
        try { draw(vp); } catch (Throwable t) { if (!errLogged) { errLogged = true; StarMPPlugin.flog("friend hud: " + StarMPPlugin.trace(t)); } }
        starmp.diag.Perf.end("HUD", t0);
    }
    @Override public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {}
    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {
        starmp.diag.Diag.captureGl();
        starmp.diag.Overlay.draw(); // F10
    }

    private void draw(ViewportAPI vp) {
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.linked() || vp == null) return;
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getCampaignUI() == null || sector.getCampaignUI().isShowingDialog()) return;
        CampaignFleetAPI me = sector.getPlayerFleet();
        if (me == null || me.getContainingLocation() == null) return;
        LocationAPI here = me.getContainingLocation();
        Map<Integer, CampaignFleetAPI> friends = CoopCampaign.playerFleets();
        battleLabels(vp, net, here, friends);
        if (friends.isEmpty()) return;
        float rangeUnits = rangeLy() * UNITS_PER_LY;
        boolean begun = false;
        java.util.List<float[]> marks = new java.util.ArrayList<>(); // {angle, ux, uy}
        for (Map.Entry<Integer, CampaignFleetAPI> e : friends.entrySet()) {
            CampaignFleetAPI f = e.getValue();
            if (f.getContainingLocation() != here) continue;
            if (!begun) { gui.begin(); begun = true; }
            String name = starmp.combat.CoopState.nameOf(net, e.getKey());
            // label over their fleet
            if (vp.isNearViewport(f.getLocation(), f.getRadius() + 300f)) {
                float x = vp.convertWorldXtoScreenX(f.getLocation().x);
                float y = vp.convertWorldYtoScreenY(f.getLocation().y) + vp.convertWorldHeightToScreenHeight(f.getRadius()) + 42f;
                shadowText("(Player)", x, y);
                shadowText(name, x, y - 14);
            }
            // direction marker
            if (CoopCampaign.atWar(e.getKey())) continue;
            float dx = f.getLocation().x - me.getLocation().x, dy = f.getLocation().y - me.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (here.isHyperspace() && dist > rangeUnits) continue;
            if (dist < RING_RADIUS * 2f) continue; // right next to us
            float ux = dx / dist, uy = dy / dist;
            marks.add(new float[]{(float) Math.atan2(uy, ux), ux, uy});
        }
        if (!marks.isEmpty()) {
            // friends in about the same direction: slide their markers apart along the ring so none overlap; each
            // arrow still points exactly at its friend
            float cx = vp.convertWorldXtoScreenX(me.getLocation().x), cy = vp.convertWorldYtoScreenY(me.getLocation().y);
            float ring = vp.convertWorldWidthToScreenWidth(RING_RADIUS);
            float minSep = (2f * ICON_R + 14f) / Math.max(1f, ring);
            int n = marks.size();
            if (n * minSep > 2 * Math.PI) minSep = (float) (2 * Math.PI / n);
            marks.sort((a, b) -> Float.compare(a[0], b[0]));
            float[] ang = new float[n];
            for (int i = 0; i < n; i++) ang[i] = marks.get(i)[0];
            for (int it = 0; it < 12 && n > 1; it++) {
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    float gap = ang[j] - ang[i]; if (j == 0) gap += (float) (2 * Math.PI);
                    if (gap < minSep) { float push = (minSep - gap) / 2f; ang[i] -= push; ang[j] += push; }
                }
            }
            for (int i = 0; i < n; i++) {
                float[] m = marks.get(i);
                marker(cx + (float) Math.cos(ang[i]) * ring, cy + (float) Math.sin(ang[i]) * ring, m[1], m[2]);
            }
        }
        if (begun) gui.end();
        if (System.nanoTime() - lastLogNs > 15_000_000_000L) {
            lastLogNs = System.nanoTime();
            StringBuilder sb = new StringBuilder("friend hud (I am " + net.playerName() + "):");
            for (Map.Entry<Integer, CampaignFleetAPI> e : friends.entrySet()) {
                CampaignFleetAPI f = e.getValue();
                float dx = f.getLocation().x - me.getLocation().x, dy = f.getLocation().y - me.getLocation().y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                boolean same = f.getContainingLocation() == here;
                boolean marker = same && !CoopCampaign.atWar(e.getKey()) && !(here.isHyperspace() && dist > rangeUnits) && dist >= RING_RADIUS * 2f;
                sb.append(" | peer ").append(e.getKey()).append(" label='").append(starmp.combat.CoopState.nameOf(net, e.getKey())).append("' fleet='").append(f.getName())
                  .append("' dist=").append(Math.round(dist)).append(same ? "" : " other-location").append(" marker=").append(marker)
                  .append(marker ? " dir=" + Math.round(Math.toDegrees(Math.atan2(dy, dx))) : "");
            }
            StarMPPlugin.flog(sb.toString());
        }
    }
    private long lastLogNs;

    private static final java.awt.Color BATTLE = new java.awt.Color(255, 140, 60);

    /** "IN BATTLE" over every fleet on the map that is fighting: local battles, the host's fleets (guests see them
     * through the stream) and other players who are in a battle of their own. */
    private void battleLabels(ViewportAPI vp, NetManager net, LocationAPI here, Map<Integer, CampaignFleetAPI> friends) {
        java.util.Map<CampaignFleetAPI, Integer> owner = new java.util.IdentityHashMap<>();
        for (Map.Entry<Integer, CampaignFleetAPI> e : friends.entrySet()) owner.put(e.getValue(), e.getKey());
        boolean begun = false;
        for (CampaignFleetAPI f : here.getFleets()) {
            if (!vp.isNearViewport(f.getLocation(), f.getRadius() + 300f)) continue;
            Integer peer = owner.get(f);
            boolean fighting = f.getBattle() != null || NpcSync.puppetInBattle(f) || (peer != null && CoopCampaign.playerInBattle(peer));
            if (!fighting) continue;
            if (!begun) { gui.begin(); begun = true; }
            float x = vp.convertWorldXtoScreenX(f.getLocation().x);
            float y = vp.convertWorldYtoScreenY(f.getLocation().y) + vp.convertWorldHeightToScreenHeight(f.getRadius()) + (peer != null ? 58f : 30f);
            gui.textCentered("IN BATTLE", Gui.TINY, x + 1, y - 1, SHADOW);
            gui.textCentered("IN BATTLE", Gui.TINY, x, y, BATTLE);
        }
        if (begun) gui.end();
    }

    private void shadowText(String s, float cx, float yTop) {
        gui.textCentered(s, Gui.TINY, cx + 1, yTop - 1, SHADOW);
        gui.textCentered(s, Gui.TINY, cx, yTop, LABEL);
    }

    /** Blue disc, white outline, white P, and a white arrow just outside the disc pointing along (ux, uy). */
    private void marker(float x, float y, float ux, float uy) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int seg = 28;
        color(BLUE);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(x, y);
        for (int i = 0; i <= seg; i++) { double a = 2 * Math.PI * i / seg; GL11.glVertex2f(x + (float) Math.cos(a) * ICON_R, y + (float) Math.sin(a) * ICON_R); }
        GL11.glEnd();
        color(WHITE);
        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < seg; i++) { double a = 2 * Math.PI * i / seg; GL11.glVertex2f(x + (float) Math.cos(a) * ICON_R, y + (float) Math.sin(a) * ICON_R); }
        GL11.glEnd();
        // arrow: tip 9 px beyond the outline, base 3 px beyond it
        float px = -uy, py = ux, b = ICON_R + 3f, t = ICON_R + 11f, hw = 5.5f;
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(x + ux * t, y + uy * t);
        GL11.glVertex2f(x + ux * b + px * hw, y + uy * b + py * hw);
        GL11.glVertex2f(x + ux * b - px * hw, y + uy * b - py * hw);
        GL11.glEnd();
        float h = gui.textHeight("P", Gui.TINY);
        gui.textCentered("P", Gui.TINY, x + 0.5f, y + h / 2f, WHITE);
    }

    private static void color(Color c) { GL11.glColor4ub((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) c.getAlpha()); }
}
