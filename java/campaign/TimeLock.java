package starmp.campaign;

import com.fs.starfarer.api.Global;
import starmp.StarMPPlugin;

/** 0.1.4.7 (idea from the community fork's OnlineTimeLock): a guest can't fast-forward the campaign - the host
 * owns time; a guest running ahead would only drift from the host's world. Restored when the link ends. */
public final class TimeLock {
    private TimeLock() {}
    private static Float saved;

    static void tick(boolean guestLinked) {
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
