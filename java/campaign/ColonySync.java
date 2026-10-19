package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.PlayerColonizationListener;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONObject;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.4.2: colonies founded in the vanilla colonize screen are shared. The founder's game sends the planet and the name it was given;
 * the host and the other players repeat vanilla's own founding steps on their copy of that planet (same steps as the game's
 * PlanetSurveyPanel: size 3 colony, population industry, local resources and storage, spaceport queued), without charging anything.
 * Only the founding is shared - later buildings and upgrades are not.
 *
 * COL (50) any -> host, host -> all: {"p": planetId, "n": name}
 */
public final class ColonySync {
    private ColonySync() {}
    public static final byte COL = 50;
    private static final ConcurrentLinkedQueue<JSONObject> inbox = new ConcurrentLinkedQueue<>();
    private static boolean applying;

    static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new PlayerColonizationListener() {
                @Override public void reportPlayerColonizedPlanet(PlanetAPI planet) {
                    if (applying) return;
                    try { announce(planet); } catch (Throwable t) { StarMPPlugin.flog("colony sync: announce failed: " + StarMPPlugin.trace(t)); }
                }
                @Override public void reportPlayerAbandonedColony(MarketAPI market) {}
            }, true);
        } catch (Throwable t) { StarMPPlugin.flog("colony sync: listener failed: " + t); }
    }

    private static void announce(PlanetAPI planet) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked() || planet == null) return;
        JSONObject o = new JSONObject();
        try { o.put("p", planet.getId()); o.put("n", planet.getName()); } catch (Throwable ignored) {}
        n.sendAll(pack(o));
        StarMPPlugin.flog("colony sync: sent my new colony " + planet.getName() + " (" + planet.getId() + ")");
    }

    private static byte[] pack(JSONObject o) {
        byte[] j = o.toString().getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[j.length + 1]; d[0] = COL; System.arraycopy(j, 0, d, 1, j.length);
        return d;
    }

    public static void onPacket(NetManager n, int peer, byte[] d) {
        try {
            JSONObject o = new JSONObject(new String(d, 1, d.length - 1, StandardCharsets.UTF_8));
            inbox.add(o);
            if (n.isHost()) { // pass it on to the other players; whoever founded it already has it and ignores it
                byte[] copy = d.clone();
                for (int id : n.peerIds()) if (id != peer) n.sendToPeer(id, copy);
            }
        } catch (Throwable t) { StarMPPlugin.flog("colony sync: bad packet from " + peer + ": " + t); }
    }

    private static boolean testDone;
    private static long testStartNs;
    /** Test hook (env STARMP_TEST_COLONY): once linked for 40 s, this game founds a colony on the first free planet it finds, as if from the colonize screen. */
    private static void testHook() {
        if (testDone || System.getenv("STARMP_TEST_COLONY") == null) return;
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) { testStartNs = 0; return; }
        long now = System.nanoTime();
        if (testStartNs == 0) testStartNs = now;
        if (now - testStartNs < 40_000_000_000L) return;
        testDone = true;
        for (SectorEntityToken e : Global.getSector().getPlayerFleet().getContainingLocation().getAllEntities()) {
            if (!(e instanceof PlanetAPI) || ((PlanetAPI) e).isStar()) continue;
            MarketAPI m = e.getMarket();
            if (m == null || !m.isPlanetConditionMarketOnly() || m.isPlayerOwned()) continue;
            StarMPPlugin.flog("colony sync: TEST founding on " + e.getName() + " (" + e.getId() + ")");
            apply(e.getId(), "Testia");
            announce((PlanetAPI) e);
            return;
        }
        StarMPPlugin.flog("colony sync: TEST found no free planet here");
    }

    /** Campaign thread. */
    static void tick() {
        try { testHook(); } catch (Throwable t) { StarMPPlugin.flog("colony sync: test hook: " + StarMPPlugin.trace(t)); }
        JSONObject o;
        while ((o = inbox.poll()) != null) {
            try { apply(o.optString("p"), o.optString("n")); }
            catch (Throwable t) { StarMPPlugin.flog("colony sync: could not found a colony: " + StarMPPlugin.trace(t)); }
        }
    }

    private static void apply(String planetId, String name) {
        SectorAPI sector = Global.getSector();
        SectorEntityToken e = sector.getEntityById(planetId);
        if (!(e instanceof PlanetAPI)) { StarMPPlugin.flog("colony sync: planet " + planetId + " not found"); return; }
        PlanetAPI planet = (PlanetAPI) e;
        MarketAPI m = planet.getMarket();
        if (m == null) { StarMPPlugin.flog("colony sync: " + planetId + " has no market to found a colony on"); return; }
        if (m.isPlayerOwned() && !m.isPlanetConditionMarketOnly()) return; // already a colony here (the founder's own game)
        applying = true;
        try {
            if (name != null && !name.trim().isEmpty()) { m.setName(name); planet.setName(name); }
            planet.setFaction("player");
            m.setDaysInExistence(0f);
            m.setPlanetConditionMarketOnly(false);
            m.setFactionId("player");
            m.addCondition("population_3");
            m.addIndustry("population");
            m.setFreePort(false);
            if (m.hasCondition("decivilized")) { m.removeCondition("decivilized"); m.addCondition("decivilized_subpop"); }
            m.setSize(3);
            m.addSubmarket("local_resources");
            m.addSubmarket("storage");
            try { ((StoragePlugin) m.getSubmarket("storage").getPlugin()).setPlayerPaidToUnlock(true); } catch (Throwable ignored) {}
            m.getTariff().modifyFlat("default_tariff", m.getFaction().getTariffFraction());
            planet.setMarket(m);
            m.setPrimaryEntity(planet);
            m.setPlayerOwned(true);
            sector.getEconomy().addMarket(m, true);
            sector.getEconomy().tripleStep();
            m.advance(0f);
            m.getConstructionQueue().addToEnd("spaceport", 0);
            StarMPPlugin.flog("colony sync: founded " + planet.getName() + " (" + planetId + ") from another player");
            try {
                Global.getSector().getCampaignUI().addMessage("StarMP: a colony was founded on " + planet.getName());
            } catch (Throwable ignored) {}
        } finally { applying = false; }
    }
}
