package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.List;

/** 0.1.5.3: F9 (bug report) and F10 (debug overlay) on the campaign map, from the game's own key events. */
public final class DiagInput implements CampaignInputListener {
    public static void register() {
        Global.getSector().getListenerManager().removeListenerOfClass(DiagInput.class);
        Global.getSector().getListenerManager().addListener(new DiagInput(), true);
    }
    @Override public int getListenerInputPriority() { return 5; }
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI e : events) if (!e.isConsumed() && e.isKeyDownEvent() && !e.isRepeat()) starmp.diag.Diag.keyEvent(e.getEventValue(), "campaign");
    }
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) {}
}
