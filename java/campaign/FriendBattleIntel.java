package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Intel item for another player's running battle: who is fighting whom, how far away it is, and a Join button that
 * works within vanilla's join range. Replaces the old pop-up. Never saved (removed before every save).
 */
public class FriendBattleIntel extends BaseIntelPlugin {
    static final String JOIN = "starmp_join";
    final int battleId, owner;
    final String who, enemy;
    private boolean joinPressed;

    FriendBattleIntel(int battleId, int owner, String who, String enemy) {
        this.battleId = battleId; this.owner = owner; this.who = who; this.enemy = enemy == null ? "" : enemy;
        setImportant(true);
    }

    void close() { endImmediately(); }

    @Override protected String getName() { return who + " is in battle"; }

    @Override public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(getName(), c, 0f);
        float d = CoopCampaign.battleDistance(CoopCampaign.battleById(battleId));
        Color g = Misc.getGrayColor();
        if (!enemy.isEmpty()) info.addPara("vs " + enemy, g, 3f);
        info.addPara(d < 0 ? "In another star system" : d <= CoopCampaign.joinRange() ? "In range - you can join" : "Too far to join (" + Math.round(d) + " away)", g, 3f);
    }

    @Override public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color h = Misc.getHighlightColor();
        info.addPara(who + " is fighting" + (enemy.isEmpty() ? "." : " " + enemy + "."), 10f);
        CampWire.Battle b = CoopCampaign.battleById(battleId);
        float d = CoopCampaign.battleDistance(b);
        float range = CoopCampaign.joinRange();
        boolean live = CoopCampaign.live(b);
        if (!live) { info.addPara("The battle is over.", 10f); return; }
        if (d < 0) { info.addPara("The battle is in another star system. Get within " + Math.round(range) + " units of it to join.", 10f); return; }
        if (d > range) {
            info.addPara("Distance: %s units. Get within %s units to join (the same distance at which vanilla pulls nearby fleets into a fight).",
                    10f, h, String.valueOf(Math.round(d)), String.valueOf(Math.round(range)));
            return;
        }
        info.addPara("You are close enough to join. You bring your own combat ships and fly your flagship; ships lost there are lost from your fleet.", 10f);
        if (joinPressed && CoopCampaign.joinRequested == battleId) { info.addPara("Close this screen (Esc) to join.", Misc.getHighlightColor(), 10f); return; }
        info.addButton("Join the battle", JOIN, Misc.getButtonTextColor(), Misc.getDarkPlayerColor(), width, 26f, 10f);
    }

    @Override public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        if (JOIN.equals(buttonId)) {
            CoopCampaign.joinRequested = battleId;
            joinPressed = true;
            ui.updateUIForItem(this);
        }
    }

    @Override public String getIcon() { return Global.getSettings().getSpriteName("intel", "fleet_log"); }

    @Override public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> t = new LinkedHashSet<>(super.getIntelTags(map));
        t.add("Fleet log");
        return t;
    }

    @Override public SectorEntityToken getMapLocation(SectorMapAPI map) {
        java.util.Map<Integer, com.fs.starfarer.api.campaign.CampaignFleetAPI> pf = CoopCampaign.playerFleets();
        return pf.get(owner);
    }

    @Override public boolean shouldRemoveIntel() { return isEnded(); }

    /** Vanilla hides all intel while the tutorial runs; a friend's battle must always show. */
    @Override public boolean isHidden() { return false; }
}
