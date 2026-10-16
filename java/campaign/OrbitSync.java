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
    private static final long PERIOD_NS = 2_000_000_000L;
    private static long lastSendNs;
    private static Set<String> lastLocs = new HashSet<>();
    /** loc id -> {String[] ids, float[] angles, float[] xy (or null), Integer otherOrbits} */
    private static final ConcurrentHashMap<String, Object[]> latest = new ConcurrentHashMap<>();
    private static long lastLogNs;
    private static String lastHere;
    private static boolean scrambled;

    static void hostTick(NetManager net, Map<Integer, CampWire.Fleet> guests) {
        long now = System.nanoTime();
        Set<String> locs = new HashSet<>();
        for (CampWire.Fleet g : guests.values()) if (g.loc != null && !g.loc.isEmpty() && now - g.recvNs < 5_000_000_000L) locs.add(g.loc);
        boolean newLoc = !lastLocs.containsAll(locs); // a guest just arrived somewhere: send that system now
        lastLocs = locs;
        if (!newLoc && now - lastSendNs < PERIOD_NS) return;
        lastSendNs = now;
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
            int size = 1 + 2 + idb.length + 2 + 2 + 2;
            for (SectorEntityToken e : list) size += 2 + e.getId().getBytes(StandardCharsets.UTF_8).length + 4 + 8;
            ByteBuffer b = ByteBuffer.allocate(size);
            b.put(ORBITS); ps(b, id); b.putShort((short) list.size());
            for (SectorEntityToken e : list) { ps(b, e.getId()); b.putFloat(e.getCircularOrbitAngle()); }
            // appended in 0.1.4.11: positions (same order) + count of non-circular orbits
            b.putShort((short) list.size());
            for (SectorEntityToken e : list) { b.putFloat(e.getLocation().x); b.putFloat(e.getLocation().y); }
            b.putShort((short) Math.min(other, 32000));
            byte[] o = new byte[b.position()]; b.flip(); b.get(o);
            net.broadcast(o);
        }
    }

    public static void onPacket(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        String loc = gs(b); int n = b.getShort() & 0xFFFF;
        String[] ids = new String[n]; float[] a = new float[n];
        for (int i = 0; i < n; i++) { ids[i] = gs(b); a[i] = b.getFloat(); }
        float[] xy = null; int other = 0;
        if (b.remaining() >= 2) {
            int m = b.getShort() & 0xFFFF;
            if (m == n && b.remaining() >= m * 8) { xy = new float[m * 2]; for (int i = 0; i < m * 2; i++) xy[i] = b.getFloat(); }
            if (b.remaining() >= 2) other = b.getShort();
        }
        latest.put(loc, new Object[]{ids, a, xy, other});
    }

    static void guestTick(SectorAPI sector) {
        LocationAPI here = sector.getPlayerFleet().getContainingLocation();
        if (here == null) return;
        if (!here.getId().equals(lastHere)) { lastHere = here.getId(); latest.remove(here.getId()); } // only fresh data for a new system
        testScramble(here);
        Object[] l = latest.remove(here.getId());
        latest.clear(); // other systems' packets are stale by the time we get there
        if (l == null) return;
        String[] ids = (String[]) l[0]; float[] a = (float[]) l[1]; float[] xy = (float[]) l[2]; int other = (Integer) l[3];
        int moved = 0; float worst = 0, worstGap = 0; String worstGapId = null;
        for (int i = 0; i < ids.length; i++) {
            SectorEntityToken e = here.getEntityById(ids[i]);
            if (e == null) continue;
            try {
                if (xy != null) { // how far off it is here before the correction
                    float dx = e.getLocation().x - xy[2 * i], dy = e.getLocation().y - xy[2 * i + 1];
                    float g = (float) Math.sqrt(dx * dx + dy * dy);
                    if (g > worstGap) { worstGap = g; worstGapId = ids[i]; }
                }
                float d = Math.abs(((e.getCircularOrbitAngle() - a[i]) % 360f + 540f) % 360f - 180f);
                if (d > 0.5f) { e.setCircularOrbitAngle(a[i]); moved++; worst = Math.max(worst, d); }
            } catch (Throwable ignored) {}
        }
        long now = System.nanoTime();
        if (moved > 0 && now - lastLogNs > (worst > 5f ? 5_000_000_000L : 30_000_000_000L)) {
            lastLogNs = now;
            StarMPPlugin.flog("orbits: " + here.getName() + " - matched " + moved + " of " + ids.length + " orbit(s) to the host's (largest drift "
                    + Math.round(worst) + " deg" + (xy != null ? ", largest gap " + Math.round(worstGap) + " units: " + worstGapId : "") + ")"
                    + (other > 0 ? "; " + other + " non-circular orbit(s) not synced" : ""));
        }
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
