package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import starmp.NetManager;
import starmp.StarMPPlugin;
import starmp.combat.CoopState;

/**
 * An always-present Intel entry listing everyone in the session: name, host/guest, ping, location and fleet size.
 * Never saved (removed before every save and when the link ends).
 */
public class PlayersIntel extends BaseIntelPlugin {
    @Override protected String getName() { return "Players (StarMP)"; }

    @Override public String getIcon() { return Global.getSettings().getSpriteName("intel", "fleet_log"); }

    @Override public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> t = new LinkedHashSet<>(super.getIntelTags(map));
        t.add("Fleet log");
        return t;
    }

    @Override public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        NetManager net = StarMPPlugin.net();
        int count = net != null && net.linked() ? 1 + others(net).size() : 1;
        info.addPara(count + " player" + (count == 1 ? "" : "s") + " in the session", Misc.getGrayColor(), 3f);
    }

    @Override public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.linked()) { info.addPara("Not connected.", 10f); return; }
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor();
        info.addPara("Everyone currently in this StarMP session.", 10f);
        row(info, net.playerName() + (net.isHost() ? "  (you, host)" : "  (you)"), net.myId(), net, h, g, true);
        for (int id : others(net)) row(info, CoopState.nameOf(net, id) + (id == 0 ? "  (host)" : "  (guest)"), id, net, h, g, false);
    }

    private void row(TooltipMakerAPI info, String label, int id, NetManager net, Color h, Color g, boolean self) {
        StringBuilder sb = new StringBuilder();
        CampWire.Fleet f = self ? null : CoopCampaign.fleets.get(id);
        String loc = self ? locOf(Global.getSector().getPlayerFleet()) : (f != null && f.loc != null && !f.loc.isEmpty() ? locLabel(f.loc) : "unknown");
        sb.append(loc);
        int ships = fleetSize(id, self);
        if (ships >= 0) sb.append("  -  ").append(ships).append(ships == 1 ? " ship" : " ships");
        if (!self) {
            NetManager.Peer p = net.peer(id);
            if (p != null && p.pingMs >= 0) sb.append("  -  ").append(p.pingMs).append(" ms");
        }
        info.addPara(label, h, 8f);
        info.addPara(sb.toString(), g, 2f);
    }

    private static int fleetSize(int id, boolean self) {
        try {
            if (self) return Global.getSector().getPlayerFleet().getFleetData().getNumMembers();
            CampaignFleetAPI f = CoopCampaign.playerFleets().get(id);
            return f != null ? f.getFleetData().getNumMembers() : -1;
        } catch (Throwable t) { return -1; }
    }

    private static String locOf(CampaignFleetAPI me) {
        return me != null && me.getContainingLocation() != null ? me.getContainingLocation().getName() : "unknown";
    }
    private static String locLabel(String locId) {
        com.fs.starfarer.api.campaign.LocationAPI l = CoopCampaign.location(locId);
        return l != null ? l.getName() : locId;
    }

    private static List<Integer> others(NetManager net) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>(net.peerIds());
        ids.addAll(CoopCampaign.fleets.keySet());
        ids.remove(net.myId());
        return new ArrayList<>(ids);
    }

    @Override public boolean shouldRemoveIntel() { return isEnded(); }
    @Override public boolean isHidden() { return false; } // vanilla hides intel during the tutorial
}
