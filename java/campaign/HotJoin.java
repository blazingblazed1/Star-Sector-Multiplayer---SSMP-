package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * Hot join: a player who connects while the host is already playing gets the host's campaign without anyone going
 * back to the title screen. Host (F8): save now, then the save goes to every connected player. Guest: once it has
 * arrived, F8 offers to open the load screen with it (or, on the title screen, the lobby shows Load Game).
 */
public final class HotJoin {
    private HotJoin() {}

    private static volatile boolean saveRequested, sendAfterSave;
    /** Host chose NEW CAMPAIGN in the lobby: send the new game as soon as it reaches the map. */
    public static volatile boolean sendOnNewGame;

    /** From the mod plugin's onGameLoad. */
    public static void gameLoaded(boolean newGame) {
        if (sendOnNewGame) {
            sendOnNewGame = false;
            if (newGame) { saveRequested = true; StarMPPlugin.flog("hot join: new campaign created - it is saved and sent once the map is free"); }
        }
    }
    private static String notifiedSave = "";

    /** A save is pending or being sent: the background simulation holds still. */
    static boolean busy() { return saveRequested || sendAfterSave; }

    /** Host, from the F8 dialog: save as soon as the dialog has closed, then send the save. */
    public static void requestSendSave() { saveRequested = true; }

    /** Campaign thread, every frame. */
    static void tick(NetManager net) {
        // Host already in-campaign: a guest that reconnects here must be resynced too (Lobby.tick only runs at title).
        if (net.isHost()) starmp.ui.Lobby.serviceHostReconnects(net);
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (saveRequested && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentInteractionDialog() == null) {
            saveRequested = false;
            sendAfterSave = true;
            boolean ok = false;
            try { ok = ui.cmdSave(); } catch (Throwable t) { StarMPPlugin.flog("hot join: save failed: " + t); }
            StarMPPlugin.flog("hot join: saving the campaign to send it (" + (ok ? "started" : "the game refused to save here") + ")");
            if (!ok) { sendAfterSave = false; ui.addMessage("StarMP: the game can't save right now (in a battle or dialog?) - try F8 again in a moment"); }
        }
        if (loadRequested && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentInteractionDialog() == null) {
            loadRequested = false;
            try { ui.cmdLoad(); StarMPPlugin.flog("hot join: opened the load screen"); } catch (Throwable t) { StarMPPlugin.flog("hot join: could not open the load screen: " + t); }
        }
        // guest: tell the player once when a host save has arrived
        String got = net.receivedSave;
        if (!net.isHost() && got != null && !got.isEmpty() && !got.equals(notifiedSave)) {
            notifiedSave = got;
            ui.addMessage("StarMP: the host's campaign has arrived (" + NetManager.saveLabel(got) + ") - press F8 and choose Load it");
        }
    }

    /** From the mod plugin's afterGameSave. */
    public static void afterSave() {
        if (!sendAfterSave) return;
        sendAfterSave = false;
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.isHost()) return;
        net.sendSave("");
        starmp.ui.Lobby.hostCampaignReady = true; // reconnecting guests can now be resynced with this save
        // a guest on the title screen sees Load Game. 0.1.4.11: also when the host never went through the lobby
        // (Continue + F8 host): record a Start, or reconnecting guests were sent the save but no game screen
        if (starmp.ui.Lobby.startedWhat == null) starmp.ui.Lobby.hostStart(net, "campaign");
        else net.broadcast(starmp.ui.Lobby.encodeStart(starmp.ui.Lobby.startedWhat));
        StarMPPlugin.flog("hot join: campaign saved, sending it to " + net.peerIds().size() + " player(s)");
        try { Global.getSector().getCampaignUI().addMessage("StarMP: campaign saved and sent to the other players"); } catch (Throwable ignored) {}
    }

    /** Guest, from F8: open the vanilla load screen (the host's save is the newest one). */
    public static void openLoadScreen() { loadRequested = true; }
    private static volatile boolean loadRequested;
}
