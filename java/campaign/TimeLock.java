package starmp.campaign;

import com.fs.starfarer.api.Global;
import starmp.StarMPPlugin;

/** 0.1.4.7 (idea from the community fork's OnlineTimeLock): a guest can't fast-forward the campaign - the host
 * owns time; a guest running ahead would only drift from the host's world. Restored when the link ends. */
public final class TimeLock {
    private TimeLock() {}
    private static Float saved;

    static void tick(boolean guestLinked) {
        // 0.9.0.0: shared fast-forward on -> the host's speed; otherwise locked at x1
        if (guestLinked && saved != null) {
            float want = starmp.Session.sharedFastForward() ? Math.max(1f, starmp.Session.current().ffMult) : 1f;
            want *= CoopCampaign.guestPace; // 0.9.3.0: catching up to / waiting for the host's time
            try { if (Global.getSettings().getFloat("campaignSpeedupMult") != want) { Global.getSettings().setFloat("campaignSpeedupMult", want); if (CoopCampaign.guestPace == 1f || want > 1.5f) StarMPPlugin.flog("time lock: speed x" + want + (want > 1.5f ? " (shared fast-forward)" : "")); } } catch (Throwable ignored) {}
        }
        if (guestLinked && saved == null) {
            float cur = 3f;
            try { cur = Global.getSettings().getFloat("campaignSpeedupMult"); } catch (Throwable ignored) {}
            saved = cur;
            Global.getSettings().setFloat("campaignSpeedupMult", 1f);
            StarMPPlugin.flog("time lock: fast-forward off while you are a guest (was x" + cur + ")");
        } else if (!guestLinked && saved != null) {
            Global.getSettings().setFloat("campaignSpeedupMult", saved);
            StarMPPlugin.flog("time lock: fast-forward restored (x" + saved + ")");
            saved = null;
        }
    }
}
