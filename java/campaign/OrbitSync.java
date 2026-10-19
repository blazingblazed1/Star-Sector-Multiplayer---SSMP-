package starmp.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.1.4.7 (idea from the community fork's AuthSector, host -> guest only): a guest's planets and stations drift
 * from the host's every time the guest's calendar catches up (skipped time doesn't move orbits). The host sends the
 * orbit angles of everything in each location a guest is in; the guest snaps any that differ.
 *
 * 0.1.4.11: one slot PER LOCATION (with guests in two systems the second packet overwrote the first, so one of
 * them was never corrected); a guest entering a system gets that system at once (not up to 4 s later); every 2 s
 * instead of 4; the host also sends positions (appended, older builds ignore them) so the guest can log how far
 * off things still are after the snap - and how many orbiting things aren't on circular orbits (not correctable).
 */
public final class OrbitSync {
    private OrbitSync() {}
    public static final byte ORBITS = 30;
    /** 0.9.2.8: every second (was 2 s) - with the rate the guest predicts in between, so this is only a correction. */
    private static final long PERIOD_NS = 1_000_000_000L;
    private static long lastSendNs;
    private static Set<String> lastLocs = new HashSet<>();
    /** loc id -> Snap (the host's latest orbits there) */
    private static final ConcurrentHashMap<String, Snap> latest = new ConcurrentHashMap<>();
    private static long lastLogNs;
    private static String lastHere;
    private static boolean scrambled;

    /** One host update for one location. */
    static final class Snap {
        String[] ids; float[] angles, rates, xy; int other; long hostClock; // rates: degrees per game day; hostClock: 0 = old host
    }

    static void hostTick(NetManager net, Map<Integer, CampWire.Fleet> guests) {
        long now = System.nanoTime();
        Set<String> locs = new HashSet<>();
        for (CampWire.Fleet g : guests.values()) if (g.loc != null && !g.loc.isEmpty() && now - g.recvNs < 5_000_000_000L) locs.add(g.loc);
        boolean newLoc = !lastLocs.containsAll(locs); // a guest just arrived somewhere: send that system now
        lastLocs = locs;
        if (!newLoc && now - lastSendNs < PERIOD_NS) return;
        lastSendNs = now;
        long clock = com.fs.starfarer.api.Global.getSector().getClock().getTimestamp();
        for (String id : locs) {
            LocationAPI loc = CoopCampaign.location(id);
            if (loc == null || loc.isHyperspace()) continue;
            List<SectorEntityToken> list = new ArrayList<>();
            int other = 0;
            for (SectorEntityToken e : loc.getAllEntities()) {
                try {
                    if (e.getOrbit() == null || e.getOrbitFocus() == null || e.getId() == null) continue;
                    if (e.getCircularOrbitRadius() > 0) list.add(e); else other++;
                } catch (Throwable ignored) {}
                if (list.size() >= 400) break;
            }
            byte[] idb = id.getBytes(StandardCharsets.UTF_8);
            int size = 1 + 2 + idb.length + 2 + 2 + 2 + 8 + 2;
            for (SectorEntityToken e : list) size += 2 + e.getId().getBytes(StandardCharsets.UTF_8).length + 4 + 8 + 4;
            ByteBuffer b = ByteBuffer.allocate(size);
            b.put(ORBITS); ps(b, id); b.putShort((short) list.size());
            for (SectorEntityToken e : list) { ps(b, e.getId()); b.putFloat(e.getCircularOrbitAngle()); }
            // appended in 0.1.4.11: positions (same order) + count of non-circular orbits
            b.putShort((short) list.size());
            for (SectorEntityToken e : list) { b.putFloat(e.getLocation().x); b.putFloat(e.getLocation().y); }
            b.putShort((short) Math.min(other, 32000));
            // appended in 0.9.2.8: the host's clock + each orbit's speed (degrees per game day), so guests place
            // planets where the host's are at every moment instead of snapping them every few seconds
            b.putLong(clock); b.putShort((short) list.size());
            for (SectorEntityToken e : list) b.putFloat(rate(e, clock));
            byte[] o = new byte[b.position()]; b.flip(); b.get(o);
            net.broadcast(o);
        }
    }

    /** Host: an orbit's speed in degrees per game day, read from the game itself: advance it a tenth of a day,
     *  see how far it turned, put it back (the period's sign doesn't say which way it turns). Cached per entity. */
    private static float rate(SectorEntityToken e, long clockUnused) {
        Float r = rateCache.get(e.getId());
        if (r != null) return r;
        float rr = 0f;
        try {
            float a0 = e.getCircularOrbitAngle();
            e.getOrbit().advance(com.fs.starfarer.api.Global.getSector().getClock().getSecondsPerDay() * 0.1f);
            float a1 = e.getCircularOrbitAngle();
            e.setCircularOrbitAngle(a0);
            rr = ((((a1 - a0) % 360f) + 540f) % 360f - 180f) * 10f;
        } catch (Throwable ignored) {}
        rateCache.put(e.getId(), rr);
        return rr;
    }
    private static final Map<String, Float> rateCache = new HashMap<>();

    public static void onPacket(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        Snap s = new Snap();
        String loc = gs(b); int n = b.getShort() & 0xFFFF;
        s.ids = new String[n]; s.angles = new float[n];
        for (int i = 0; i < n; i++) { s.ids[i] = gs(b); s.angles[i] = b.getFloat(); }
        if (b.remaining() >= 2) {
            int m = b.getShort() & 0xFFFF;
            if (m == n && b.remaining() >= m * 8) { s.xy = new float[m * 2]; for (int i = 0; i < m * 2; i++) s.xy[i] = b.getFloat(); }
            if (b.remaining() >= 2) s.other = b.getShort();
        }
        if (b.remaining() >= 10) {
            s.hostClock = b.getLong(); int m = b.getShort() & 0xFFFF;
            if (m == n && b.remaining() >= m * 4) { s.rates = new float[m]; for (int i = 0; i < m; i++) s.rates[i] = b.getFloat(); }
        }
        latest.put(loc, s);
    }

    /** Guest, every frame: keep each orbit where the host's is now (its last angle + speed x time since), easing
     *  small differences out over about half a second and snapping only big ones (a new system, a long freeze). */
    static void guestTick(SectorAPI sector) {
        LocationAPI here = sector.getPlayerFleet().getContainingLocation();
        if (here == null) return;
        if (!here.getId().equals(lastHere)) { lastHere = here.getId(); latest.remove(here.getId()); } // only fresh data for a new system
        testScramble(here);
        for (String k : new ArrayList<>(latest.keySet())) if (!k.equals(here.getId())) latest.remove(k); // other systems' data is stale by the time we get there
        Snap s = latest.get(here.getId());
        if (s == null) return;
        boolean predict = s.rates != null && s.hostClock != 0L;
        float days = predict ? (sector.getClock().getTimestamp() - s.hostClock) / 86_400_000f : 0f;
        float amount = Math.min(0.1f, frameSeconds());
        float ease = Math.min(1f, amount * 2.5f);
        int moved = 0, snapped = 0; float worst = 0, worstGap = 0; String worstGapId = null;
        for (int i = 0; i < s.ids.length; i++) {
            SectorEntityToken e = here.getEntityById(s.ids[i]);
            if (e == null) continue;
            try {
                float target = predict ? s.angles[i] + s.rates[i] * days : s.angles[i];
                float d = ((target - e.getCircularOrbitAngle()) % 360f + 540f) % 360f - 180f; // signed, -180..180
                float ad = Math.abs(d);
                if (ad > worst) worst = ad;
                if (!predict) { if (ad > 0.5f) { e.setCircularOrbitAngle(target); moved++; } continue; } // old host: snap like before
                if (ad > 15f) { e.setCircularOrbitAngle(target); snapped++; }                    // far off (new system, long freeze)
                else if (ad > 0.01f) { e.setCircularOrbitAngle(e.getCircularOrbitAngle() + d * ease); moved++; }
            } catch (Throwable ignored) {}
        }
        if (!predict) latest.remove(here.getId()); // old host: each packet applied once
        long now = System.nanoTime();
        if ((snapped > 0 || worst > 2f) && now - lastLogNs > 5_000_000_000L || now - lastLogNs > 60_000_000_000L) {
            lastLogNs = now;
            StarMPPlugin.flog("orbits: " + here.getName() + " - following the host's " + s.ids.length + " orbit(s)" + (predict ? "" : " (old host: snapping)")
                    + ", largest difference " + String.format("%.1f", worst) + " deg" + (snapped > 0 ? ", snapped " + snapped : "")
                    + (s.other > 0 ? "; " + s.other + " non-circular orbit(s) not synced" : ""));
        }
    }

    private static long lastFrameNs;
    private static float frameSeconds() { long n = System.nanoTime(); float f = lastFrameNs == 0 ? 0f : (n - lastFrameNs) / 1e9f; lastFrameNs = n; return f; }

    /** 0.9.2.8: the calendar jumped forward by that many days without the world moving (a host catching up after a
     *  fight, a guest catching up to the host): move every orbit by the same time, so planets stay with the date. */
    static void skipTime(SectorAPI sector, float days) {
        if (days <= 0f || days > 60f) return;
        float secs = days * sector.getClock().getSecondsPerDay();
        int n = 0;
        for (LocationAPI loc : sector.getAllLocations()) {
            for (SectorEntityToken e : loc.getAllEntities()) {
                try {
                    if (e.getOrbit() == null || e instanceof com.fs.starfarer.api.campaign.CampaignFleetAPI) continue;
                    e.getOrbit().advance(secs); n++;
                } catch (Throwable ignored) {}
            }
        }
        StarMPPlugin.flog("orbits: calendar jumped " + String.format("%.2f", days) + " day(s) - moved " + n + " orbit(s) with it");
    }

    /** Test hook STARMP_TEST_ORBIT_SCRAMBLE=deg: once, 20 s after linking, turn every orbit here by that much. */
    private static long scrambleAtNs;
    private static void testScramble(LocationAPI here) {
        if (scrambled || System.getenv("STARMP_TEST_ORBIT_SCRAMBLE") == null) return;
        if (scrambleAtNs == 0) { scrambleAtNs = System.nanoTime() + 20_000_000_000L; return; }
        if (System.nanoTime() < scrambleAtNs) return;
        scrambled = true;
        float deg = Float.parseFloat(System.getenv("STARMP_TEST_ORBIT_SCRAMBLE"));
        int n = 0;
        for (SectorEntityToken e : here.getAllEntities()) {
            try { if (e.getOrbit() != null && e.getCircularOrbitRadius() > 0) { e.setCircularOrbitAngle(e.getCircularOrbitAngle() + deg); n++; } } catch (Throwable ignored) {}
        }
        StarMPPlugin.flog("TEST: scrambled " + n + " orbit(s) in " + here.getName() + " by " + deg + " deg");
    }

    private static void ps(ByteBuffer b, String s) { byte[] x = s.getBytes(StandardCharsets.UTF_8); b.putShort((short) x.length); b.put(x); }
    private static String gs(ByteBuffer b) { byte[] x = new byte[b.getShort() & 0xFFFF]; b.get(x); return new String(x, StandardCharsets.UTF_8); }
}
