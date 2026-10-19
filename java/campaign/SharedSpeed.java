package starmp.campaign;

import com.fs.starfarer.api.Global;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import starmp.StarMPPlugin;

/**
 * 0.9.0.0: shared fast-forward. While the host (or a vote) has it on, every player's campaign runs fast-forwarded at
 * the host's speed, so nobody's clock runs ahead of the others. Vanilla sets CampaignState.fastForward from the
 * fast-forward key at the start of each input pass; we set it again later in the same pass (before the frame's
 * time step reads it). java.lang.invoke only (the mod sandbox blocks java.lang.reflect).
 */
final class SharedSpeed {
    private SharedSpeed() {}
    private static MethodHandle setter;
    private static boolean broken, logged;

    static void force() {
        if (broken) return;
        try {
            if (Global.getSector().isPaused()) return;
            // 0.9.0.1: guests only speed up while the host's world is actually moving (not while the host sits in a
            // dialog or menu) - otherwise guests ran days ahead and the host had to jump to catch up
            starmp.NetManager n = StarMPPlugin.net();
            if (n != null && !n.isHost() && System.nanoTime() - CoopCampaign.hostClockMovedNs > 1_000_000_000L) return;
            com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null || ui.getCurrentCoreTab() != null) return;
            Object st = com.fs.state.AppDriver.getInstance().getCurrentState();
            if (st == null || !st.getClass().getName().equals("com.fs.starfarer.campaign.CampaignState")) return;
            if (setter == null) setter = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup()).findSetter(st.getClass(), "fastForward", boolean.class);
            setter.invoke(st, true);
            if (!logged) { logged = true; StarMPPlugin.flog("shared fast-forward: running"); }
        } catch (Throwable t) {
            broken = true;
            StarMPPlugin.flog("shared fast-forward: not possible in this game build (" + t + ")");
        }
    }

    static void stopped() { logged = false; }
}
