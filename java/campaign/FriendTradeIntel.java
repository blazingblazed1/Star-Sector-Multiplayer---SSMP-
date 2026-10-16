package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Another player asked to trade: Accept / Decline. Accept opens the trade screen once the Intel screen closes. */
public class FriendTradeIntel extends BaseIntelPlugin {
    static final String ACCEPT = "starmp_trade_accept", DECLINE = "starmp_trade_decline";
    private static final List<FriendTradeIntel> open = new ArrayList<>();
    final int from; final String who;
    private boolean accepted;

    FriendTradeIntel(int from, String who) { this.from = from; this.who = who; setImportant(true); }

    static void post(int from, String who) {
        closeFor(from);
        FriendTradeIntel i = new FriendTradeIntel(from, who);
        open.add(i);
        Global.getSector().getIntelManager().addIntel(i, false);
        Global.getSector().getCampaignUI().addMessage(who + " wants to trade. Click their fleet, or open Intel, to answer.", Misc.getHighlightColor());
        TradeScreen.play("ui_sensor_burst_on");
    }

    /** Remove the request from this player (answered or withdrawn). */
    static void closeFor(int from) {
        for (FriendTradeIntel i : new ArrayList<>(open)) if (i.from == from && !i.accepted) { open.remove(i); i.endImmediately(); }
    }

    /** Called before saving: these are never saved. */
    static void closeAll() { for (FriendTradeIntel i : new ArrayList<>(open)) i.endImmediately(); open.clear(); }

    @Override protected String getName() { return who + " wants to trade"; }

    @Override public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        info.addPara(accepted ? "Close this screen to open the trade" : "Accept or decline", Misc.getGrayColor(), 3f);
    }

    @Override public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        if (accepted) { info.addPara("Close this screen (Esc) to open the trade.", Misc.getHighlightColor(), 10f); return; }
        info.addPara(who + " is asking to trade cargo, ships and credits with you. Nothing changes hands until you both press the check mark.", 10f);
        info.addButton("Accept", ACCEPT, Misc.getButtonTextColor(), Misc.getDarkPlayerColor(), width, 26f, 10f);
        info.addButton("Decline", DECLINE, Misc.getButtonTextColor(), Misc.getDarkPlayerColor(), width, 26f, 6f);
    }

    @Override public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        if (ACCEPT.equals(buttonId)) {
            if (!TradeSession.pending.containsKey(from)) { endImmediately(); ui.updateUIForItem(this); return; }
            accepted = true;
            TradeSession.accept(from, who);
            TradeScreen.openFromAccept();
            ui.updateUIForItem(this);
            open.remove(this);
            endAfterDelay(0f);
        } else if (DECLINE.equals(buttonId)) {
            TradeSession.decline(from);
            ui.updateUIForItem(this);
        }
    }

    @Override public String getIcon() { return Global.getSettings().getSpriteName("intel", "fleet_log"); }

    @Override public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> t = new LinkedHashSet<>(super.getIntelTags(map));
        t.add("Fleet log");
        return t;
    }

    @Override public SectorEntityToken getMapLocation(SectorMapAPI map) { return CoopCampaign.playerFleets().get(from); }
    @Override public boolean shouldRemoveIntel() { return isEnded(); }
    @Override public boolean isHidden() { return false; }
}
