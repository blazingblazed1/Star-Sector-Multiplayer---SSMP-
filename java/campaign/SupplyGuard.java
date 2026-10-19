package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.util.Misc;
import starmp.StarMPPlugin;

/**
 * 0.9.4.0: a fleet next to a planet or station uses no supplies. In a multiplayer session the world keeps running while a player has a
 * dialog or menu open (nobody can pause for everyone), so a docked fleet used to burn its supplies unattended. Whatever the game
 * consumes while the fleet sits near a planet/station is given back; buying, selling and transfers (dialogs/menus) are never touched.
 */
final class SupplyGuard {
    private SupplyGuard() {}
    private static final boolean DRAIN = System.getenv("STARMP_TEST_SUPPLYDRAIN") != null;
    private static boolean moved;
    private static float prev = -1f, given;
    private static long lastLogNs;

    static void tick() {
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI me = sector.getPlayerFleet();
            if (me == null || me.getContainingLocation() == null) { prev = -1f; return; }
            if (DRAIN && prev >= 0f && me.getCargo().getSupplies() > 1f) me.getCargo().removeSupplies(0.02f); // test hook: fake consumption
            if (DRAIN && !moved) { moved = true; for (SectorEntityToken e : me.getContainingLocation().getAllEntities()) if (e instanceof PlanetAPI && !((PlanetAPI) e).isStar()) { me.setLocation(e.getLocation().x + e.getRadius() + 250f, e.getLocation().y); StarMPPlugin.flog("supplies: TEST teleported next to " + e.getName()); break; } }
            float now = me.getCargo().getSupplies();
            boolean busy = false;
            try { busy = sector.getCampaignUI().isShowingDialog() || sector.getCampaignUI().isShowingMenu(); } catch (Throwable ignored) {}
            float d = prev - now;
            // with a dialog/menu open, trades move whole units; the game's own consumption is small and fractional
            boolean consumed = !busy || d < 0.5f || Math.abs(d - Math.round(d)) > 0.001f;
            if (prev >= 0f && d > 0.0001f && consumed && me.getBattle() == null && nearPlanet(me)) {
                float back = d;
                me.getCargo().addSupplies(back);
                given += back;
                now = prev;
                long t = System.nanoTime();
                if (t - lastLogNs > 60_000_000_000L) { lastLogNs = t; StarMPPlugin.flog("supplies: kept " + Math.round(given) + " supplies near a planet so far"); }
            }
            prev = now;
        } catch (Throwable ignored) { prev = -1f; }
    }

    private static boolean nearPlanet(CampaignFleetAPI me) {
        LocationAPI loc = me.getContainingLocation();
        for (SectorEntityToken e : loc.getAllEntities()) {
            boolean world = e instanceof PlanetAPI ? !((PlanetAPI) e).isStar() : (e.getMarket() != null && !e.getMarket().isHidden());
            if (!world) continue;
            if (Misc.getDistance(me, e) <= e.getRadius() + 700f) return true;
        }
        return false;
    }
}
