package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import java.util.Map;

/**
 * Clicking the other player's fleet opens this instead of the vanilla encounter (which would let you
 * "fight" the display-only stand-in fleet).
 */
public class PlayerFleetDialog implements InteractionDialogPlugin {
    private InteractionDialogAPI dialog;
    private final CampaignFleetAPI other; // their fleet on our map, or null (opened from the scoreboard, far away)
    private final int peerId;
    private final String otherName;

    public PlayerFleetDialog(CampaignFleetAPI other) {
        this.other = other;
        Object p = other.getMemoryWithoutUpdate().get("$starmp_peer");
        this.peerId = p instanceof Integer ? (Integer) p : -1;
        this.otherName = other.getName();
    }

    /** 0.1.4.10: from the scoreboard - works wherever that player is. */
    public PlayerFleetDialog(int peer, String name) {
        this.other = CoopCampaign.playerFleets().get(peer);
        this.peerId = peer;
        this.otherName = other != null ? other.getName() : name + "'s fleet";
    }

    static void open(int peer, String name) {
        Global.getSector().getCampaignUI().showInteractionDialog(new PlayerFleetDialog(peer, name), Global.getSector().getPlayerFleet());
    }

    /** Transient campaign plugin that routes interactions with StarMP puppet fleets to this dialog. */
    public static class Picker extends BaseCampaignPlugin {
        @Override public String getId() { return "starmp_puppet_dialog"; }
        @Override public boolean isTransient() { return true; }
        @Override public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken target) {
            if (target instanceof CampaignFleetAPI && target.getMemoryWithoutUpdate().getBoolean("$starmp_puppet"))
                return new PluginPick<InteractionDialogPlugin>(new PlayerFleetDialog((CampaignFleetAPI) target), PickPriority.HIGHEST);
            return null;
        }
    }

    public static void register() {
        SectorAPI s = Global.getSector();
        s.unregisterPlugin("starmp_puppet_dialog");
        s.registerPlugin(new Picker());
    }

    @Override public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        if (other != null) dialog.getVisualPanel().showFleetInfo("Your fleet", Global.getSector().getPlayerFleet(), otherName, other);
        dialog.getTextPanel().addPara("You are in contact with " + otherName + ", another captain playing in this sector.");
        if (peerId >= 0 && TradeTax.remote(peerId))
            dialog.getTextPanel().addPara("They are in " + TradeTax.where(peerId) + ", not in your star system: anything you send costs an out-of-system tax on top "
                    + "(credits 100%, items and ships 50% of their value).", java.awt.Color.ORANGE);
        dialog.getTextPanel().addPara("When either of you fights, the other gets an offer to join with their fleet.");
        options();
        dialog.setPromptText("");
    }

    private void options() {
        dialog.getOptionPanel().clearOptions();
        float have = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        Object pr = peerId >= 0 ? (Object) peerId : null;
        boolean remote = pr instanceof Integer && TradeTax.remote((Integer) pr);
        for (int amount : new int[]{10000, 50000}) {
            int cost = Math.round(amount + (remote ? TradeTax.onCredits(amount) : 0f));
            dialog.getOptionPanel().addOption("Send " + amount + " credits" + (remote ? " (costs " + cost + " - out-of-system tax 100%)" : ""), amount);
            if (have < cost) dialog.getOptionPanel().setEnabled(amount, false);
        }
        Object peer = peerId >= 0 ? (Object) peerId : null;
        if (peer instanceof Integer && TradeSession.pending.containsKey(peer)) {
            dialog.getOptionPanel().addOption("Accept " + TradeSession.pending.get(peer) + "'s trade request", "trade_accept");
            dialog.getOptionPanel().addOption("Decline the trade request", "trade_decline");
        } else {
            dialog.getOptionPanel().addOption("Propose a trade", "trade");
            if (TradeSession.active || TradeSession.waitingAccept) dialog.getOptionPanel().setEnabled("trade", false);
        }
        // 0.1.5.9: join their battle from here too (same rules as the Intel item's Join button)
        CampWire.Battle b = peerId >= 0 ? CoopCampaign.battleOf(peerId) : null;
        if (CoopCampaign.live(b)) {
            float d = CoopCampaign.battleDistance(b);
            float range = CoopCampaign.joinRange();
            boolean inRange = d >= 0 && d <= range;
            dialog.getOptionPanel().addOption("JOIN BATTLE" + (b.enemyName == null || b.enemyName.isEmpty() ? "" : " vs " + b.enemyName)
                    + (inRange ? "" : d < 0 ? " (in another location)" : " (too far: " + Math.round(d) + " / " + Math.round(range) + ")"), "join");
            if (!inRange) dialog.getOptionPanel().setEnabled("join", false);
        }
        dialog.getOptionPanel().addOption("Leave", "leave");
    }

    @Override public void optionSelected(String optionText, Object optionData) {
        if ("leave".equals(optionData)) { dialog.dismiss(); return; }
        if ("join".equals(optionData)) {
            CampWire.Battle b = CoopCampaign.battleOf(peerId);
            if (!CoopCampaign.live(b)) { dialog.getTextPanel().addPara("The battle is over."); options(); return; }
            // the join dialog opens as soon as this one is closed (two interaction dialogs can't be open at once)
            CoopCampaign.joinRequested = b.battleId;
            starmp.StarMPPlugin.flog("campaign: join requested from " + otherName + "'s contact dialog (battle " + b.battleId + ")");
            dialog.dismiss();
            return;
        }
        if ("trade".equals(optionData) || "trade_accept".equals(optionData) || "trade_decline".equals(optionData)) {
            Object peer = peerId >= 0 ? (Object) peerId : null;
            starmp.NetManager n = starmp.StarMPPlugin.net();
            if (!(peer instanceof Integer) || n == null || !n.linked()) { dialog.getTextPanel().addPara("Not connected."); return; }
            int p = (Integer) peer;
            String name = starmp.combat.CoopState.nameOf(n, p);
            if ("trade".equals(optionData)) {
                TradeSession.request(p, name);
                dialog.getTextPanel().addPara("You ask " + name + " to trade. The trade screen opens when they accept.");
                options();
            } else if ("trade_accept".equals(optionData)) {
                TradeSession.accept(p, name);
                TradeScreen.openFromAccept();
                dialog.dismiss();
            } else {
                TradeSession.decline(p);
                dialog.getTextPanel().addPara("You decline the trade.");
                options();
            }
            return;
        }
        if (optionData instanceof Integer) {
            int amount = (Integer) optionData;
            com.fs.starfarer.api.campaign.CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
            Object peer = peerId >= 0 ? (Object) peerId : null;
            starmp.NetManager n = starmp.StarMPPlugin.net();
            if (!(peer instanceof Integer) || !n.linked()) { dialog.getTextPanel().addPara("Not connected."); return; }
            float tax = TradeTax.remote((Integer) peer) ? TradeTax.onCredits(amount) : 0f;
            if (cargo.getCredits().get() < amount + tax) return;
            cargo.getCredits().subtract(amount + tax);
            if (tax > 0) dialog.getTextPanel().addPara("Out-of-system tax: " + Math.round(tax) + " credits.");
            starmp.combat.CoopState.sendTo(n, (Integer) peer, CampWire.encodeGift(amount, n.playerName()));
            starmp.StarMPPlugin.flog("campaign: sent " + amount + " credits to peer " + peer);
            dialog.getTextPanel().addPara("Sent " + amount + " credits to " + otherName + ".");
            options();
        }
    }

    @Override public void optionMousedOver(String optionText, Object optionData) {}
    @Override public void advance(float amount) {}
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
