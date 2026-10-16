package starmp.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import java.util.ArrayList;
import java.util.List;

/** Ship definition <-> real fleet member (exact loadout), shared by host and guest. */
public final class Ships {
    private Ships() {}

    public static Wire.ShipDef def(int id, int owner, FleetMemberAPI m) {
        Wire.ShipDef d = new Wire.ShipDef();
        d.id = id; d.owner = owner;
        d.hullId = m.getHullSpec().getHullId();
        d.name = m.getShipName() == null ? "" : m.getShipName();
        d.cr = m.getRepairTracker().getCR();
        d.hullFrac = m.getStatus().getHullFraction();
        ShipVariantAPI v = m.getVariant();
        if (v != null) fill(d, v);
        return d;
    }

    public static void fill(Wire.ShipDef d, ShipVariantAPI v) {
        d.vents = v.getNumFluxVents(); d.caps = v.getNumFluxCapacitors();
        for (String slot : v.getFittedWeaponSlots()) {
            String w = v.getWeaponId(slot);
            if (w != null) { d.slots.add(slot); d.weapons.add(w); }
        }
        d.mods.addAll(v.getNonBuiltInHullmods());
        d.wings.addAll(v.getFittedWings());
    }

    public static FleetMemberAPI member(Wire.ShipDef d, String variantTag) {
        ShipHullSpecAPI hull = Global.getSettings().getHullSpec(d.hullId);
        ShipVariantAPI v = Global.getSettings().createEmptyVariant(d.hullId + "_" + variantTag, hull);
        for (int i = 0; i < d.slots.size(); i++) { try { v.addWeapon(d.slots.get(i), d.weapons.get(i)); } catch (Throwable ignored) {} }
        for (String m : d.mods) {
            try {
                com.fs.starfarer.api.loading.HullModSpecAPI spec = Global.getSettings().getHullModSpec(m);
                if (spec != null && spec.hasTag("dmod")) v.addPermaMod(m, false); else v.addMod(m); // d-mods stay permanent
            } catch (Throwable ignored) {}
        }
        for (int i = 0; i < d.wings.size(); i++) { try { v.setWingId(i, d.wings.get(i)); } catch (Throwable ignored) {} }
        v.setNumFluxVents(d.vents); v.setNumFluxCapacitors(d.caps);
        v.autoGenerateWeaponGroups();
        FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
        if (!d.name.isEmpty()) m.setShipName(d.name);
        m.getRepairTracker().setCR(Math.max(0.2f, d.cr));
        m.setOwner(d.owner);
        return m;
    }

    /**
     * Gives a spawned ship its real combat readiness. Ships rebuilt from a ShipDef come from a fleet member outside any
     * campaign fleet, whose max CR reads 0, so the engine applies the 0%-CR penalties when it spawns them: weapon and
     * engine malfunctions, ship system and shields disabled, slower, more damage taken. Setting the CR number alone
     * does not remove those, so the CR effects are re-applied here with the real value.
     */
    public static void applyCR(com.fs.starfarer.api.combat.ShipAPI s, float cr) {
        cr = Math.max(0.01f, cr);
        s.setCRAtDeployment(cr);
        s.setCurrentCR(cr);
        // the engine cached "controls locked" while the CR read 0; clear it so it re-checks with the real CR
        s.setControlsLocked(false);
        try { Global.getSettings().getCRPlugin().applyCRToStats(cr, s.getMutableStats(), s.getHullSize()); }
        catch (Throwable t) { starmp.StarMPPlugin.flog("ships: could not apply CR to " + s.getHullSpec().getHullId() + ": " + t); }
    }

    /** Short CR-effect summary for the log (malfunction chances, system/shield state). */
    public static String crState(com.fs.starfarer.api.combat.ShipAPI s) {
        com.fs.starfarer.api.combat.MutableShipStatsAPI st = s.getMutableStats();
        return String.format("cr=%.2f wMalf=%.3f eMalf=%.3f crit=%.3f sysOff=%b defOff=%b speed=%.0f",
                s.getCurrentCR(), st.getWeaponMalfunctionChance().getModifiedValue(), st.getEngineMalfunctionChance().getModifiedValue(),
                st.getCriticalMalfunctionChance().getModifiedValue(), s.isShipSystemDisabled(), s.isDefenseDisabled(), st.getMaxSpeed().getModifiedValue());
    }

    public static List<Wire.ShipDef> fleetDefs(List<FleetMemberAPI> members) {
        List<Wire.ShipDef> out = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) out.add(def(i, 0, members.get(i)));
        return out;
    }
}
