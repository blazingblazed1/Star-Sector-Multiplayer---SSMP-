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

    private String page = "main";

    private void show(String note) {
        NetManager n = net();
        shownState = stateKey();
        TextPanelAPI t = dialog.getTextPanel();
        OptionPanelAPI o = dialog.getOptionPanel();
        t.clear();
        o.clearOptions();
        t.addPara("StarMP " + StarMPPlugin.VERSION + (Prefs.getBool("lan", false) ? " - LAN" : " - Iroh peer-to-peer"), Color.ORANGE);
        if (note != null) t.addPara(note, Color.YELLOW);
        if ("host".equals(page)) { showHostSettings(n, t, o); return; }
        if ("players".equals(page)) { showPlayers(n, t, o); return; }
        if ("mine".equals(page)) { showMine(n, t, o); return; }
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
            if (p != null) t.addPara("Player: " + p.name + " (" + starmp.Session.roleOf(id) + ")" + (p.pingMs >= 0 ? "  -  " + p.pingMs + " ms" : ""));
        }
        if (!n.saveStatus.isEmpty()) t.addPara(n.saveStatus);
        if (n.linked()) t.addPara("You are " + (n.isHost() ? "the host" : starmp.Session.roleOf(n.myId()).equals("admin") ? "an admin" : "a player")
                + ". Pause: " + starmp.Session.permLabel(starmp.Session.current().pausePerm) + ". Fast-forward everyone: " + starmp.Session.permLabel(starmp.Session.current().speedPerm) + ".", Color.GRAY);
        t.addPara("Everyone must have the same campaign loaded. Host: \"Send this campaign\" saves and copies it to everyone connected; they then load it from here.", Color.GRAY);
        if (starmp.Session.openId != 0) {
            t.addPara("VOTE: " + starmp.Session.openText, Misc.getHighlightColor());
            if (!starmp.Session.myBallotCast) { o.addOption("Vote YES - " + starmp.Session.openText, "voteyes"); o.addOption("Vote NO", "voteno"); }
        }
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
        if (n.linked()) {
            boolean ff = starmp.Session.sharedFastForward();
            boolean can = starmp.Session.myCan(starmp.Session.Perm.SPEED);
            o.addOption("Fast-forward for everyone: " + (ff ? "ON" : "OFF") + (can ? " (change)" : starmp.Session.voteAllowed(ff ? starmp.Session.V_FF_OFF : starmp.Session.V_FF_ON) ? " (start a vote)" : " (host/admins only)"), "ff");
            o.addOption("Players - kick" + (n.isHost() ? ", admins" : ""), "pg_players");
        }
        o.addOption(n.isHost() || !n.linked() ? "Host settings & permissions" : "Host settings & permissions (view)", "pg_host");
        o.addOption("My settings - pings, markers, keys", "pg_mine");
        o.addOption("Refresh", "refresh");
        o.addOption("Leave", "leave");
        o.setShortcut("leave", Keyboard.KEY_ESCAPE, false, false, false, true);
        dialog.setPromptText("");
    }

    private void showHostSettings(NetManager n, TextPanelAPI t, OptionPanelAPI o) {
        boolean edit = !n.linked() || n.isHost();
        t.addPara(edit ? "Host settings - used whenever you host. Players get them automatically." : "The host's settings:", Misc.getHighlightColor());
        t.addPara("Without a permission, a player can start a vote instead (if votes are on): more than half of the players must agree within 20 seconds. Admins are players you promote in Players.", Color.GRAY);
        for (String k : starmp.Session.HOST_ROWS) {
            if (edit) o.addOption(starmp.Session.rowLabel(k) + " (change)", "hs_" + k);
            else t.addPara(starmp.Session.rowLabel(k));
        }
        if (!edit && !starmp.Session.current().admins.isEmpty()) t.addPara("Admins: " + String.join(", ", starmp.Session.current().admins));
        o.addOption("Back", "pg_main");
        o.setShortcut("pg_main", Keyboard.KEY_ESCAPE, false, false, false, true);
        dialog.setPromptText("");
    }

    private void showPlayers(NetManager n, TextPanelAPI t, OptionPanelAPI o) {
        boolean canKick = starmp.Session.myCan(starmp.Session.Perm.KICK);
        t.addPara("Players in this game:", Misc.getHighlightColor());
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>(n.peerIds());
        ids.addAll(CoopCampaign.fleets.keySet());
        ids.remove(n.myId());
        t.addPara("- " + n.playerName() + " (you, " + starmp.Session.roleOf(n.myId()) + ")");
        for (int id : ids) {
            String name = starmp.combat.CoopState.nameOf(n, id);
            t.addPara("- " + name + " (" + starmp.Session.roleOf(id) + ")");
            if (id == 0) continue;
            if (canKick) o.addOption("Kick " + name, "kick_" + id);
            if (n.isHost()) o.addOption(starmp.Session.isAdmin(name) ? "Remove admin: " + name : "Make admin: " + name, "adm_" + id);
        }
        if (!canKick) t.addPara("Kicking is " + starmp.Session.permLabel(starmp.Session.current().kickPerm) + ".", Color.GRAY);
        o.addOption("Back", "pg_main");
        o.setShortcut("pg_main", Keyboard.KEY_ESCAPE, false, false, false, true);
        dialog.setPromptText("");
    }

    private void showMine(NetManager n, TextPanelAPI t, OptionPanelAPI o) {
        t.addPara("Your own settings (this PC).", Misc.getHighlightColor());
        t.addPara("Pings: middle-click on the map or in a battle marks the spot for everyone looking at the same place." + (Pings.allowed() ? "" : " The host has pings turned off."), Color.GRAY);
        for (int l = 0; l < 3; l++) o.addOption("Pings in " + Pings.layerName(l) + ": " + (Pings.layerOn(l) ? "ON" : "OFF") + " (change)", "ping_" + l);
        o.addOption("Chat window (Enter to type): " + (starmp.ui.Chat.enabled() ? "ON" : "OFF") + " (change)", "chat");
        float ly = FriendHud.rangeLy();
        o.addOption("Friend markers in hyperspace: " + (ly <= 0 ? "off" : ly + " light-years") + " (change)", "frange");
        o.addOption("Scoreboard key: " + Scoreboard.keyName() + " (change)", "scorekey");
        if (starmp.diag.Diag.active()) o.addOption("Debug log (F9 bug report, F10 overlay, crash report): " + (starmp.diag.Diag.enabled() ? "ON" : "OFF") + " (change)", "debuglog");
        if (starmp.diag.Diag.enabled()) o.addOption("Crash / bug reports go to: " + starmp.ui.TitleMenu.reportPlaceLabel() + " (change)", "debugwhere");
        o.addOption("Back", "pg_main");
        o.setShortcut("pg_main", Keyboard.KEY_ESCAPE, false, false, false, true);
        dialog.setPromptText("");
    }

    private static String shortCode(String c) { return c.length() > 40 ? c.substring(0, 40) + "..." : c; }

    @Override public void optionSelected(String text, Object data) {
        NetManager n = net();
        String note = null;
        String d = String.valueOf(data);
        if (d.startsWith("pg_")) page = d.substring(3);
        else if (d.startsWith("hs_")) { if (!n.linked() || n.isHost()) starmp.Session.cycleRow(d.substring(3)); }
        else if (d.startsWith("ping_")) Pings.toggle(Integer.parseInt(d.substring(5)));
        else if (d.startsWith("kick_")) { int id = Integer.parseInt(d.substring(5)); note = "Kicked " + starmp.combat.CoopState.nameOf(n, id) + "."; starmp.Session.kick(id); }
        else if (d.startsWith("adm_")) { String nm = starmp.combat.CoopState.nameOf(n, Integer.parseInt(d.substring(4))); starmp.Session.setAdmin(nm, !starmp.Session.isAdmin(nm)); }
        else if ("voteyes".equals(d) || "voteno".equals(d)) { starmp.Session.ballot("voteyes".equals(d)); note = "Vote sent."; }
        else if ("ff".equals(d)) {
            String m = starmp.Session.request(starmp.Session.sharedFastForward() ? starmp.Session.V_FF_OFF : starmp.Session.V_FF_ON);
            note = m != null ? m.replace("StarMP: ", "") : "Asked the host.";
        }
        else if ("host".equals(data)) { n.host(Prefs.getBool("lan", false)); note = "Hosting started. Wait a moment, then Refresh to see the code."; }
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
        else if ("scorekey".equals(data)) Scoreboard.cycleKey();
        else if ("chat".equals(data)) starmp.ui.Chat.toggle();
        else if ("debuglog".equals(data)) { Prefs.put("debugLog", String.valueOf(!starmp.diag.Diag.enabled())); starmp.diag.Diag.settingsChanged(); }
        else if ("debugwhere".equals(data)) {
            String[] o2 = {"mods", "desktop", "off"}; String cur = starmp.diag.Diag.reportPlace(); int i = 0;
            while (i < o2.length && !o2[i].equals(cur)) i++;
            Prefs.put("crashReport", o2[(i + 1) % o2.length]); starmp.diag.Diag.settingsChanged();
        }
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
        return n.state() + "|" + n.code() + "|" + n.peerIds() + "|" + n.saveStatus + "|" + n.receivedSave + "|" + starmp.Session.voteSeq + "|" + starmp.Session.myBallotCast + "|" + starmp.Session.sharedFastForward();
    }
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
