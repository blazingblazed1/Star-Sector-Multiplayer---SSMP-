package starmp.combat;

import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

/** AI that does nothing except apply the latest input received from a remote player (host side). */
public class RemoteShipAI implements ShipAIPlugin {
    private final ShipAPI ship;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private volatile Wire.Input input = new Wire.Input();
    private boolean prevVent, prevSystem, prevShield;
    private int shieldLogs, sysLogs;

    public RemoteShipAI(ShipAPI ship) { this.ship = ship; }

    public void setInput(Wire.Input in) { if (in != null) this.input = in; }
    public boolean isFiring() { return (input.bits & Wire.B_FIRE) != 0; }

    @Override public void advance(float amount) {
        if (ship == null || !ship.isAlive()) return;
        Wire.Input in = input;
        int b = in.bits;
        Vector2f aim = new Vector2f(in.aimX, in.aimY);
        ship.getMouseTarget().set(aim);
        if ((b & Wire.B_ACCEL) != 0) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        if ((b & Wire.B_BACK) != 0) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        if ((b & Wire.B_DECEL) != 0) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        if ((b & Wire.B_STRAFE_MOD) != 0) {
            // vanilla strafe mode: turn keys strafe and the ship turns to face the mouse
            if ((b & (Wire.B_LEFT | Wire.B_STRAFE_L)) != 0) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            if ((b & (Wire.B_RIGHT | Wire.B_STRAFE_R)) != 0) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            float want = (float) Math.toDegrees(Math.atan2(aim.y - ship.getLocation().y, aim.x - ship.getLocation().x));
            float d = want - ship.getFacing();
            while (d > 180f) d -= 360f; while (d < -180f) d += 360f;
            if (d > 2f) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            else if (d < -2f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        } else {
            if ((b & Wire.B_LEFT) != 0) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            if ((b & Wire.B_RIGHT) != 0) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            if ((b & Wire.B_STRAFE_L) != 0) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            if ((b & Wire.B_STRAFE_R) != 0) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        }
        if ((b & Wire.B_FIRE) != 0) ship.giveCommand(ShipCommand.FIRE, aim, in.group);
        boolean vent = (b & Wire.B_VENT) != 0, sys = (b & Wire.B_SYSTEM) != 0, sh = (b & Wire.B_SHIELD) != 0;
        if (vent && !prevVent) ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
        if (sys && !prevSystem) {
            // like shields, the command is ignored for ships under a custom AI: use the system directly
            ShipSystemAPI s = ship.getSystem();
            boolean ok = s != null && !s.isActive() && !s.isCoolingDown() && !ship.getFluxTracker().isOverloadedOrVenting();
            if (ok) ship.useSystem(); else ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
            if (sysLogs++ < 8) starmp.StarMPPlugin.flog("owner: remote system use on " + ship.getHullSpec().getHullId() + " ok=" + ok
                    + (s == null ? " (no system)" : " active=" + s.isActive() + " cooldown=" + s.isCoolingDown() + " ammo=" + s.getAmmo() + " outOfAmmo=" + s.isOutOfAmmo() + " state=" + s.getState()));
        }
        if (sh && !prevShield) {
            ShieldAPI shield = ship.getShield();
            if (shield != null && shield.getType() != ShieldAPI.ShieldType.NONE && shield.getType() != ShieldAPI.ShieldType.PHASE) {
                // the toggle command is ignored for ships under a custom AI; switch the shield directly
                if (shield.isOn()) shield.toggleOff(); else if (!ship.getFluxTracker().isOverloadedOrVenting()) shield.toggleOn();
            } else ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
            if (shieldLogs++ < 5) starmp.StarMPPlugin.flog("owner: remote shield toggle on " + ship.getHullSpec().getHullId() + " -> " + (shield == null ? "phase/none" : shield.isOn()));
        }
        // a raised shield follows the player's mouse
        if (ship.getShield() != null && ship.getShield().isOn() && ship.getShield().getType() == ShieldAPI.ShieldType.OMNI)
            ship.getShield().forceFacing((float) Math.toDegrees(Math.atan2(aim.y - ship.getLocation().y, aim.x - ship.getLocation().x)));
        prevVent = vent; prevSystem = sys; prevShield = sh;
    }

    @Override public void setDoNotFireDelay(float amount) {}
    @Override public void forceCircumstanceEvaluation() {}
    @Override public boolean needsRefit() { return false; }
    @Override public ShipwideAIFlags getAIFlags() { return flags; }
    @Override public void cancelCurrentManeuver() {}
    @Override public ShipAIConfig getConfig() { return config; }
}
