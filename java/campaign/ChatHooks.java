package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.List;
import starmp.ui.Chat;

/** 0.9.0.0: the chat window on the campaign map (input before everything else; drawn above the UI). */
public final class ChatHooks implements CampaignInputListener, CampaignUIRenderingListener {
    public static void register() {
        Global.getSector().getListenerManager().removeListenerOfClass(ChatHooks.class);
        Global.getSector().getListenerManager().addListener(new ChatHooks(), true);
    }
    private static boolean free() {
        com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return ui != null && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null && ui.getCurrentInteractionDialog() == null;
    }
    @Override public int getListenerInputPriority() { return 100; } // before the scoreboard and the game
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) { if (free() && !Scoreboard.editingName()) Chat.input(events); }
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}
    @Override public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) { if (free()) Chat.draw(); }
    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {}
}
