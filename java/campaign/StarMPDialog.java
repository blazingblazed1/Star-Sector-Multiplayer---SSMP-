package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.Prefs;
import com.fs.starfarer.api.util.Misc;
import starmp.StarMPPlugin;

/** F8 on the campaign map: StarMP status and connection controls without going back to the title screen. */
public class StarMPDialog implements InteractionDialogPlugin {
    public static final int HOTKEY = Keyboard.KEY_F8;
    private InteractionDialogAPI dialog;

    /** Opens the dialog on F8. */
    public static class Hotkey implements CampaignInputListener {
        @Override public int getListenerInputPriority() { return 0; }
        @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyDownEvent() || e.getEventValue() != HOTKEY) continue;
                CampaignUIAPI ui = Global.getSector().getCampaignUI();
                if (ui.isShowingDialog() || ui.isShowingMenu()) continue;
                e.consume();
                ui.showInteractionDialog(new StarMPDialog(), Global.getSector().getPlayerFleet());
            }
        }
        @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
        @Override public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    }

    public static void register() {
        Global.getSector().getListenerManager().removeListenerOfClass(Hotkey.class);
        Global.getSector().getListenerManager().addListener(new Hotkey(), true);
    }

    private static NetManager net() { return StarMPPlugin.net(); }

    @Override public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        show(null);
    }

    private void show(String note) {
        NetManager n = net();
        shownState = stateKey();
        TextPanelAPI t = dialog.getTextPanel();
        t.clear();
        t.addPara("StarMP " + StarMPPlugin.VERSION + " - Iroh peer-to-peer", Color.ORANGE);
        if (note != null) t.addPara(note, Color.YELLOW);
        String st;
        switch (n.state()) {
            case STARTING: st = "starting host..."; break;
            case HOSTING: st = n.peerIds().isEmpty() ? "hosting - waiting for a player" : "hosting - " + n.peerIds().size() + " player(s) connected"; break;
            case JOINING: st = "joining..."; break;
            case CONNECTED: st = "connected to host"; break;
            default: st = "not connected";
        }
        t.addPara("Status: " + st);
        if (n.isHost() && !n.code().isEmpty()) t.addPara("Join code: " + shortCode(n.code()));
        for (Integer id : n.peerIds()) {
            NetManager.Peer p = n.peer(id);
            if (p != null) t.addPara("Player: " + p.name + (id == 0 ? " (host)" : "") + (p.pingMs >= 0 ? "  -  " + p.pingMs + " ms" : ""));
        }
        if (!n.saveStatus.isEmpty()) t.addPara(n.saveStatus);
        t.addPara("Everyone must have the same campaign loaded. Host: \"Send this campaign\" saves and copies it to everyone connected; they then load it from here.", Color.GRAY);
        OptionPanelAPI o = dialog.getOptionPanel();
        o.clearOptions();
        if (n.state() == NetManager.State.IDLE && !starmp.diag.Diag.launcherOk()) {
            t.addPara(starmp.diag.Diag.NEED_LAUNCHER, Misc.getHighlightColor());
        } else if (n.state() == NetManager.State.IDLE) {
            o.addOption("Host (" + (Prefs.getBool("lan", false) ? "LAN only" : "internet") + ")", "host");
            o.addOption("Join using the code in the clipboard", "join");
        } else {
            if (n.isHost() && !n.code().isEmpty()) o.addOption("Copy join code to clipboard", "copy");
            if (n.isHost() && !n.peerIds().isEmpty()) o.addOption("Send this campaign to the other players (saves first)", "sendsave");
            if (!n.isHost() && n.receivedSave != null && !n.receivedSave.isEmpty()) o.addOption("Load the host's campaign (" + NetManager.saveLabel(n.receivedSave) + ")", "loadsave");
            o.addOption("Disconnect", "disc");
        }
        float ly = FriendHud.rangeLy();
        o.addOption("Friend markers in hyperspace: " + (ly <= 0 ? "off" : ly + " light-years") + " (change)", "frange");
        if (!n.linked() || n.isHost()) o.addOption("Never pause - host keeps the world running in menus/dialogs: " + (BackgroundSim.enabled() ? "ON" : "OFF") + " (change)", "neverpause");
        o.addOption("Scoreboard key: " + Scoreboard.keyName() + " (change)", "scorekey");
        o.addOption("Hyperspace fleet speed: x" + HyperSpeed.localSetting() + (n.linked() && !n.isHost() ? " (the host's x" + HyperSpeed.effective(n) + " is used)" : "") + " (change)", "hyperspeed");
        o.addOption("Shared NPC fleets (host-authoritative): " + (NpcSync.enabled() ? "ON" : "OFF") + " (change)", "npcsync");
        if (starmp.diag.Diag.active()) o.addOption("Debug log (F9 bug report, F10 overlay, crash report): " + (starmp.diag.Diag.enabled() ? "ON" : "OFF") + " (change)", "debuglog");
        if (starmp.diag.Diag.enabled()) o.addOption("Crash / bug reports go to: " + starmp.ui.TitleMenu.reportPlaceLabel() + " (change)", "debugwhere");
        o.addOption("Refresh", "refresh");
        o.addOption("Leave", "leave");
        dialog.setPromptText("");
    }

    private static String shortCode(String c) { return c.length() > 40 ? c.substring(0, 40) + "..." : c; }

    @Override public void optionSelected(String text, Object data) {
        NetManager n = net();
        String note = null;
        if ("host".equals(data)) { n.host(Prefs.getBool("lan", false)); note = "Hosting started. Wait a moment, then Refresh to see the code."; }
        else if ("join".equals(data)) {
            String c = "";
            try { Object x = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); c = x == null ? "" : x.toString().trim(); } catch (Throwable ignored) {}
            if (c.startsWith("SM1:")) { n.join(c); note = "Joining... press Refresh in a moment."; }
            else note = "The clipboard doesn't hold a StarMP join code (it starts with SM1:).";
        }
        else if ("copy".equals(data)) {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(n.code()), null); note = "Join code copied."; }
            catch (Throwable t) { note = "Copy failed: " + t.getMessage(); }
        }
        else if ("sendsave".equals(data)) { HotJoin.requestSendSave(); dialog.dismiss(); return; }
        else if ("loadsave".equals(data)) { dialog.dismiss(); HotJoin.openLoadScreen(); return; }
        else if ("disc".equals(data)) { n.disconnect(); note = "Disconnected."; }
        else if ("leave".equals(data)) { dialog.dismiss(); return; }
        else if ("neverpause".equals(data)) Prefs.put("neverPause", String.valueOf(!BackgroundSim.enabled()));
        else if ("hyperspeed".equals(data)) HyperSpeed.cycle();
        else if ("scorekey".equals(data)) Scoreboard.cycleKey();
        else if ("npcsync".equals(data)) Prefs.put("npcSync", String.valueOf(!NpcSync.enabled()));
        else if ("debuglog".equals(data)) { Prefs.put("debugLog", String.valueOf(!starmp.diag.Diag.enabled())); starmp.diag.Diag.settingsChanged(); }
        else if ("debugwhere".equals(data)) {
            String[] o2 = {"mods", "desktop", "off"}; String cur = starmp.diag.Diag.reportPlace(); int i = 0;
            while (i < o2.length && !o2[i].equals(cur)) i++;
            Prefs.put("crashReport", o2[(i + 1) % o2.length]); starmp.diag.Diag.settingsChanged();
        }
        else if ("tradedraft".equals(data)) { dialog.dismiss(); TradeScreen.showDraft(); return; }
        else if ("frange".equals(data)) {
            float[] steps = {1f, 2.5f, 5f, 10f, 0f}; float cur = FriendHud.rangeLy(); int i = 0;
            while (i < steps.length && steps[i] != cur) i++;
            Prefs.put("friendRangeLy", String.valueOf(steps[(i + 1) % steps.length]));
        }
        show(note);
    }

    @Override public void optionMousedOver(String optionText, Object optionData) {}
    private String shownState = "";
    private float sinceShow;

    /** Re-draws when the connection state, player list or save status changes (no need to press Refresh). */
    @Override public void advance(float amount) {
        sinceShow += amount;
        if (sinceShow < 0.5f) return;
        sinceShow = 0;
        if (!stateKey().equals(shownState)) show(null);
    }

    private static String stateKey() {
        NetManager n = net();
        return n.state() + "|" + n.code() + "|" + n.peerIds() + "|" + n.saveStatus + "|" + n.receivedSave;
    }
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
