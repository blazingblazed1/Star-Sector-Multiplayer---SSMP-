package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.util.LinkedHashSet;
import java.util.Set;
import starmp.Session;

/** 0.9.0.0: a running vote (pause / unpause / fast-forward) with Yes and No buttons. Never saved. */
public class VoteIntel extends BaseIntelPlugin {
    final int voteId;
    private final String text;

    VoteIntel(int voteId, String text) { this.voteId = voteId; this.text = text; setImportant(true); }

    void close() { endImmediately(); }

    @Override protected String getName() { return "Vote: " + text; }

    @Override public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        long left = Math.max(0, (Session.openEndMs - System.currentTimeMillis()) / 1000);
        info.addPara(Session.myBallotCast ? "You voted - waiting for the others" : "Vote now (" + left + " s left) - also in F8", Misc.getGrayColor(), 3f);
    }

    @Override public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        info.addPara(text + ".", 10f);
        info.addPara("More than half of the players must vote yes within 20 seconds.", Misc.getGrayColor(), 10f);
        if (Session.openId != voteId) { info.addPara("This vote is over.", 10f); return; }
        if (Session.myBallotCast) { info.addPara("You voted.", Misc.getHighlightColor(), 10f); return; }
        info.addButton("Vote YES", "yes", Misc.getButtonTextColor(), Misc.getDarkPlayerColor(), width, 26f, 10f);
        info.addButton("Vote NO", "no", Misc.getButtonTextColor(), Misc.getDarkPlayerColor(), width, 26f, 6f);
    }

    @Override public void buttonPressConfirmed(Object id, IntelUIAPI ui) {
        if (Session.openId == voteId) Session.ballot("yes".equals(id));
        ui.updateUIForItem(this);
    }

    @Override public String getIcon() { return Global.getSettings().getSpriteName("intel", "fleet_log"); }

    @Override public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> t = new LinkedHashSet<>(super.getIntelTags(map));
        t.add("Fleet log");
        return t;
    }

    @Override public boolean shouldRemoveIntel() { return isEnded(); }
    @Override public boolean isHidden() { return false; }
}
