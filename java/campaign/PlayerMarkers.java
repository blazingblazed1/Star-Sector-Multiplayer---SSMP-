package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import java.util.*;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.0.0: every other player on the main map (Tab), in both views. The map draws custom entities whose type has
 * "showIconOnMap" (see data/config/custom_entities.json, type starmp_player_marker - invisible in the campaign view).
 * Each player gets one marker in hyperspace (their spot there, or their star system's spot) for the Sector view, and
 * one in the star system they are in for the System view. Moved every frame from the players' position updates;
 * removed before every save, so they never end up in one.
 */
final class PlayerMarkers {
    private PlayerMarkers() {}
    private static final String TYPE = "starmp_player_marker";
    private static final Map<Integer, CustomCampaignEntityAPI> hyper = new HashMap<>(), local = new HashMap<>();
    private static boolean broken;

    static void tick(SectorAPI sector, NetManager net, boolean linked) {
        if (broken) return;
        try {
            Set<Integer> want = new HashSet<>();
            if ("1".equals(System.getenv("STARMP_TEST_MARKER")) && sector.getPlayerFleet() != null) { // test hook: a marker for myself, 1500 units away
                com.fs.starfarer.api.campaign.CampaignFleetAPI me = sector.getPlayerFleet();
                place(local, 99, me.getContainingLocation(), me.getLocation().x + 1500, me.getLocation().y + 1500, "TEST MARKER");
                if (!me.getContainingLocation().isHyperspace() && me.getContainingLocation() instanceof StarSystemAPI)
                    place(hyper, 99, sector.getHyperspace(), ((StarSystemAPI) me.getContainingLocation()).getHyperspaceAnchor().getLocation().x + 3000, ((StarSystemAPI) me.getContainingLocation()).getHyperspaceAnchor().getLocation().y, "TEST MARKER");
                return;
            }
            if (linked) for (Map.Entry<Integer, CampWire.Fleet> e : CoopCampaign.fleets.entrySet())
                if (e.getKey() != net.myId() && !Relations.hidden(e.getKey()) && System.nanoTime() - e.getValue().recvNs < 15_000_000_000L && e.getValue().loc != null && !e.getValue().loc.isEmpty()) want.add(e.getKey());
            for (Integer p : new ArrayList<>(hyper.keySet())) if (!want.contains(p)) { remove(hyper.remove(p)); remove(local.remove(p)); }
            for (Integer p : want) {
                CampWire.Fleet f = CoopCampaign.fleets.get(p);
                LocationAPI loc = find(sector, f.loc);
                if (loc == null) continue;
                String name = starmp.combat.CoopState.nameOf(net, p);
                // hyperspace marker: where they are in hyperspace, or where their system is
                float hx = f.x, hy = f.y;
                if (!loc.isHyperspace()) {
                    if (loc instanceof StarSystemAPI && ((StarSystemAPI) loc).getHyperspaceAnchor() != null) {
                        hx = ((StarSystemAPI) loc).getHyperspaceAnchor().getLocation().x; hy = ((StarSystemAPI) loc).getHyperspaceAnchor().getLocation().y;
                    } else { hx = loc.getLocation().x; hy = loc.getLocation().y; }
                }
                place(hyper, p, sector.getHyperspace(), hx, hy, name);
                if (loc.isHyperspace()) remove(local.remove(p));
                else place(local, p, loc, f.x, f.y, name);
            }
        } catch (Throwable t) { broken = true; StarMPPlugin.flog("player map markers off: " + StarMPPlugin.trace(t)); }
    }

    private static void place(Map<Integer, CustomCampaignEntityAPI> m, int p, LocationAPI loc, float x, float y, String name) {
        CustomCampaignEntityAPI e = m.get(p);
        if (e != null && e.getContainingLocation() != loc) { remove(e); e = null; }
        if (e == null) {
            e = loc.addCustomEntity("starmp_marker_" + p + "_" + (loc.isHyperspace() ? "h" : "s"), name, TYPE, "neutral");
            e.setDiscoverable(false);
            e.getMemoryWithoutUpdate().set("$starmp_marker", true);
            m.put(p, e);
            StarMPPlugin.flog("player map marker: " + name + " in " + loc.getName() + " @" + Math.round(x) + "," + Math.round(y) + " icon=" + e.getCustomEntitySpec().getIconName() + " " + e.getCustomEntitySpec().getIconWidth() + " visible=" + e.getVisibilityLevelToPlayerFleet());
        }
        if (!name.equals(e.getName())) e.setName(name);
        e.setFixedLocation(x, y);
    }

    private static void remove(CustomCampaignEntityAPI e) { if (e != null && e.getContainingLocation() != null) e.getContainingLocation().removeEntity(e); }

    private static LocationAPI find(SectorAPI sector, String id) {
        if (sector.getHyperspace().getId().equals(id)) return sector.getHyperspace();
        StarSystemAPI s = sector.getStarSystem(id);
        if (s != null) return s;
        for (LocationAPI l : sector.getAllLocations()) if (id.equals(l.getId())) return l;
        return null;
    }

    /** Before a save, and when the link ends. */
    static void removeAll() {
        for (CustomCampaignEntityAPI e : hyper.values()) remove(e);
        for (CustomCampaignEntityAPI e : local.values()) remove(e);
        hyper.clear(); local.clear();
    }
}
