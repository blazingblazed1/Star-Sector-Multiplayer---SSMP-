package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.1.4.9: the host's save holds everyone's progress (player records), so the host saves by itself when a guest
 * leaves and every 15 minutes while guests are connected - whenever the map is free (no dialog, menu or battle).
 */
public final class AutoSave {
    private AutoSave() {}
    private static final long EVERY_NS = 15L * 60L * 1_000_000_000L;
    private static long lastSaveNs = System.nanoTime();
    private static String pending; // reason, waiting for a free moment

    public static void request(String why) { if (pending == null) pending = why; }

    static void hostTick(NetManager net, SectorAPI sector) {
        long now = System.nanoTime();
        if (pending == null && !net.peerIds().isEmpty() && now - lastSaveNs > EVERY_NS) pending = "every 15 minutes";
        if (pending == null || HotJoin.busy()) return;
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null) return;
        if (sector.getPlayerFleet() == null || sector.getPlayerFleet().getBattle() != null) return;
        String why = pending; pending = null; lastSaveNs = now;
        boolean ok = false;
        try { ok = ui.cmdSave(); } catch (Throwable t) { StarMPPlugin.flog("autosave failed: " + t); }
        StarMPPlugin.flog("autosave (" + why + "): " + (ok ? "saved" : "the game refused to save here"));
        if (ok) ui.addMessage("StarMP: autosaved (" + why + ") - everyone's progress is in the host's save");
    }
}
