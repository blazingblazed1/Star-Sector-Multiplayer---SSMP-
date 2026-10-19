package starmp.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import starmp.NetManager;
import starmp.diag.Diag;
import starmp.diag.Perf;

/**
 * 0.1.5.3 debug log, campaign side: every 500 ms one block of lines describing what THIS screen shows - my fleet,
 * other players' fleets (where their packets say vs where we show them) and NPC fleets (host: what it streams;
 * guest: what it shows, with the host's latest position and the error). Fleets carry the host's key, so the same
 * fleet can be followed across every player's log.
 */
public final class CampDiag {
    private CampDiag() {}
    private static long lastNs, lastRelNs;

    static void tick(NetManager net, SectorAPI sector) {
        if (!Diag.enabled() || sector == null || sector.getPlayerFleet() == null) return;
        long now = System.nanoTime();
        if (now - lastNs < 500_000_000L) return;
        lastNs = now;
        long t0 = Perf.begin();
        try {
            boolean hosting = net != null && net.isHost() && net.linked();
            boolean client = net != null && !net.isHost() && net.linked();
            CampaignFleetAPI me = sector.getPlayerFleet();
            LocationAPI here = me.getContainingLocation();
            CampaignUIAPI ui = sector.getCampaignUI();
            String screen = ui.getCurrentInteractionDialog() != null ? "dialog:" + NpcSync.nm(String.valueOf(ui.getCurrentInteractionDialog().getInteractionTarget() == null ? "?" : ui.getCurrentInteractionDialog().getInteractionTarget().getName()))
                    : ui.getCurrentCoreTab() != null ? "tab:" + ui.getCurrentCoreTab() : ui.isShowingDialog() ? "popup" : ui.isShowingMenu() ? "menu" : "map";
            List<String> out = new ArrayList<>();
            out.add("SELF role=" + (hosting ? "host" : client ? "guest" : "solo") + " loc=" + (here == null ? "-" : here.getId()) + " x=" + Math.round(me.getLocation().x) + " y=" + Math.round(me.getLocation().y)
                    + " vx=" + Math.round(me.getVelocity().x) + " vy=" + Math.round(me.getVelocity().y) + " burn=" + String.format(java.util.Locale.ROOT, "%.1f", me.getCurrBurnLevel())
                    + " transp=" + (me.isTransponderOn() ? 1 : 0) + " battle=" + (me.getBattle() != null ? 1 : 0) + " paused=" + (sector.isPaused() ? 1 : 0)
                    + " screen=" + screen + " date=" + NpcSync.nm(sector.getClock().getDateString()) + " ships=" + me.getFleetData().getNumMembers());
            if (hosting || client) {
                Map<Integer, CampaignFleetAPI> shown = CoopCampaign.playerFleets();
                for (Map.Entry<Integer, CampWire.Fleet> e : CoopCampaign.fleets.entrySet()) {
                    CampWire.Fleet w = e.getValue();
                    CampaignFleetAPI f = shown.get(e.getKey());
                    String err = "-";
                    if (f != null && f.getContainingLocation() != null && f.getContainingLocation().getId().equals(w.loc)) {
                        float dx = f.getLocation().x - w.x, dy = f.getLocation().y - w.y; err = String.valueOf(Math.round(Math.sqrt(dx * dx + dy * dy)));
                    }
                    out.add("PLAYER peer=" + e.getKey() + " name=" + NpcSync.nm(starmp.combat.CoopState.nameOf(net, e.getKey())) + " loc=" + w.loc + " x=" + Math.round(w.x) + " y=" + Math.round(w.y)
                            + " vx=" + Math.round(w.vx) + " vy=" + Math.round(w.vy) + " age_ms=" + (now - w.recvNs) / 1_000_000
                            + " shown=" + (f == null ? "no" : Math.round(f.getLocation().x) + "," + Math.round(f.getLocation().y)) + " err=" + err
                            + " paused=" + (w.paused ? 1 : 0) + " in_battle=" + (CoopCampaign.playerInBattle(e.getKey()) ? 1 : 0)
                            + " hostile=" + (Relations.hostileTo(e.getKey()) ? 1 : 0) + " hidden=" + (Relations.hidden(e.getKey()) ? 1 : 0)
                            + " vis=" + (f == null ? "-" : String.valueOf(f.getVisibilityLevelToPlayerFleet())) + " transp=" + (w.transponder ? 1 : 0));
                }
                if (NpcSync.enabled()) { if (hosting) NpcSync.diagHost(out); else NpcSync.diagGuest(out, here); }
                if (System.nanoTime() - lastRelNs > 10_000_000_000L) { lastRelNs = System.nanoTime(); Relations.diag(out); Alliances.diag(out); AllianceStorage.diag(out); } // 0.9.2.1 / 0.9.2.2
            }
            int npcs = 0;
            if (here != null) for (CampaignFleetAPI f : here.getFleets()) if (!f.isPlayerFleet()) npcs++;
            Perf.counts = "campaign: fleets here " + npcs + (client ? ", shared NPCs shown " + count(out, "NPC-G") + ", leaks " + count(out, "NPC-LEAK") : "")
                    + (hosting ? ", NPCs streamed " + count(out, "NPC-H") : "") + ", players " + CoopCampaign.fleets.size();
            for (String l : out) Diag.line("CAMP", l);
        } catch (Throwable t) {
            Diag.line("ERR", "camp diag: " + t);
        } finally { Perf.end("Diag", t0); }
    }

    private static int count(List<String> l, String prefix) { int n = 0; for (String s : l) if (s.startsWith(prefix)) n++; return n; }
}
