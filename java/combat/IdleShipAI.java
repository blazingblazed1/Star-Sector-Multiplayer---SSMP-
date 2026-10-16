package starmp.combat;

import com.fs.starfarer.api.combat.*;

/** Client side: ships are puppets driven by host snapshots, so local AI must not act. */
public class IdleShipAI implements ShipAIPlugin {
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    @Override public void setDoNotFireDelay(float amount) {}
    @Override public void forceCircumstanceEvaluation() {}
    @Override public void advance(float amount) {}
    @Override public boolean needsRefit() { return false; }
    @Override public ShipwideAIFlags getAIFlags() { return flags; }
    @Override public void cancelCurrentManeuver() {}
    @Override public ShipAIConfig getConfig() { return config; }
}
