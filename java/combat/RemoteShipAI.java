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
    private int shieldLogs, sysLogs, groupLogs, fireLogs, ventLogs, autoLogs;
    private long lastFireLogNs;

    public RemoteShipAI(ShipAPI ship) { this.ship = ship; }

    public void setInput(Wire.Input in) { if (in != null) this.input = in; }
    public boolean isFiring() { return (input.bits & Wire.B_FIRE) != 0; }

    private static float norm(float a) { while (a > 180f) a -= 360f; while (a < -180f) a += 360f; return a; }

    /** 0.9.3.9: a weapon fired directly (no FIRE command) is not aimed by the game: turn the turret toward the joiner's mouse ourselves, within its arc and turn rate. */
    private void aimTurret(WeaponAPI w, Vector2f aim, float dt) {
        try {
            if (w == null || w.getSlot() == null || !w.getSlot().isTurret() || w.getType() == WeaponAPI.WeaponType.DECORATIVE) return;
            float want = (float) Math.toDegrees(Math.atan2(aim.y - w.getLocation().y, aim.x - w.getLocation().x));
            float mid = w.getSlot().computeMidArcAngle(ship);
            float half = w.getArc() * 0.5f;
            float off = norm(want - mid);
            if (off > half) off = half; else if (off < -half) off = -half;
            float target = mid + off;
            float curr = w.getCurrAngle();
            float delta = norm(target - curr);
            float step = Math.max(w.getTurnRate(), 30f) * Math.max(dt, 0.001f);
            w.setCurrAngle(norm(Math.abs(delta) <= step ? target : curr + Math.signum(delta) * step));
        } catch (Throwable ignored) {}
    }

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
        // 0.9.0.2: the joiner's weapon group (keys 1-7). FIRE always fires the ship's *selected* group (its group
        // argument is ignored), so select the player's group first; if the ship ignores that too (custom AI, like
        // shields), fire that group's weapons directly.
        java.util.List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        int want = in.group;
        boolean selectedOk = true;
        if (want >= 0 && want < groups.size()) {
            int cur = groups.indexOf(ship.getSelectedGroupAPI());
            if (cur != want) {
                ship.giveCommand(ShipCommand.SELECT_GROUP, null, want);
                selectedOk = false;
                if (groupLogs++ < 6) starmp.StarMPPlugin.flog("owner: remote group select on " + ship.getHullSpec().getHullId() + " " + (cur + 1) + " -> " + (want + 1));
            }
        }
        if ((b & Wire.B_FIRE) != 0) {
            // 0.9.3.7: a group set to autofire ignores the FIRE command (and a remotely flown ship has no autofire AI), so only the
            // manual groups fired for joiners. Fire the selected group's weapons directly when it is on autofire.
            boolean auto = false;
            try { auto = want >= 0 && want < groups.size() && groups.get(want).isAutofiring(); } catch (Throwable ignored) {}
            if (auto || !(selectedOk || want < 0 || want >= groups.size())) {
                if (want >= 0 && want < groups.size()) for (WeaponAPI w : groups.get(want).getWeaponsCopy()) { aimTurret(w, aim, amount); w.setForceFireOneFrame(true); } // selection takes a frame (or never): fire it directly (0.9.3.9: turrets follow the joiner's mouse)
                if (auto && autoLogs++ < 4) starmp.StarMPPlugin.flog("owner: remote fire on an autofire group " + (want + 1) + " of " + ship.getHullSpec().getHullId() + " - firing its weapons directly");
            } else ship.giveCommand(ShipCommand.FIRE, aim, want);
            // 0.9.1.9 (debug log): which weapons are really firing, and their groups - checks keys 1-7 for joiners
            if (fireLogs < 6 && System.nanoTime() - lastFireLogNs > 1_500_000_000L) {
                StringBuilder sb = new StringBuilder();
                for (WeaponAPI w : ship.getAllWeapons()) if (w.isFiring()) {
                    WeaponGroupAPI g = ship.getWeaponGroupFor(w);
                    sb.append(' ').append(w.getId()).append("(group ").append(g == null ? "-" : String.valueOf(groups.indexOf(g) + 1)).append(')');
                }
                if (sb.length() > 0) {
                    lastFireLogNs = System.nanoTime(); fireLogs++;
                    starmp.StarMPPlugin.flog("owner: remote fire on " + ship.getHullSpec().getHullId() + " wants group " + (want + 1) + ", selected " + (groups.indexOf(ship.getSelectedGroupAPI()) + 1) + ", firing:" + sb);
                }
            }
        }
        boolean vent = (b & Wire.B_VENT) != 0, sys = (b & Wire.B_SYSTEM) != 0, sh = (b & Wire.B_SHIELD) != 0;
        if (vent && !prevVent) { // 0.9.3.7: the command is ignored for ships under a custom AI (like shields): vent directly
            com.fs.starfarer.api.combat.FluxTrackerAPI ft = ship.getFluxTracker();
            if (!ft.isVenting() && !ft.isOverloaded() && ft.getCurrFlux() > 0f) ft.ventFlux(); else ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
            if (ventLogs++ < 5) starmp.StarMPPlugin.flog("owner: remote vent on " + ship.getHullSpec().getHullId() + " -> venting=" + ft.isVenting());
        }
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
