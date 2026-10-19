package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.nio.ByteBuffer;
import org.lwjgl.util.vector.Vector2f;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import starmp.NetManager;
import starmp.StarMPPlugin;
import starmp.combat.Ships;
import starmp.combat.Wire;

/**
 * Shared NPC fleets (phase 1). The host's NPC fleets are the real ones: the host sends the fleets near each guest
 * (position ~4 Hz, exact ship list on change). Guests hide their own moving NPC fleets in the location they are in
 * and show the host's instead, as real fleets they can talk to or attack. Ships a guest destroys are removed from
 * the host's real fleet. Stations stay local (same seed, same stations).
 * Not yet: host NPC fleets chasing / intercepting guests (they don't see guests as targets).
 */
public final class NpcSync {
    private NpcSync() {}

    public static final byte NPCPOS = 22, NPCCOMP = 23, NPCLOSS = 24;
    /** 0.1.4.6: guest -> host "I'm talking to this fleet" (engagement hold); host -> guest "this fleet intercepts you". */
    public static final byte NPCHOLD = 28, NPCENGAGE = 29;

    /** Shared NPC fleets are the default since 0.1.4.6 (F8 can still turn them off). */
    /** 0.9.3.2: a linked guest follows the HOST's setting (it used to read its own saved one, so a guest with it off ignored the stream). */
    public static boolean enabled() {
        try {
            NetManager n = starmp.StarMPPlugin.net();
            if (n != null && !n.isHost() && n.linked()) { starmp.Session.Settings s = starmp.Session.remoteSettings(); if (s != null) return s.npcSync; }
        } catch (Throwable ignored) {}
        return starmp.Prefs.getBool("npcSync", true);
    }
    private static final String PUPPET = "$starmp_npcpuppet", HIDDEN = "$starmp_hidden";

    // ---------------------------------------------------------------- host
    private static long lastPosNs, lastFullCompNs;
    /** fleet key -> hash of the ship list last sent, and the members in the order sent. */
    private static final Map<Integer, Integer> sentHash = new HashMap<>();
    private static final Map<Integer, List<FleetMemberAPI>> sentMembers = new HashMap<>();
    /** (key, comp hash) -> members in the order sent with that hash: a guest's loss report names the hash its
     * copy was built from, so a composition re-sent in between can't shift the indices onto the wrong ship. */
    private static final Map<Long, List<FleetMemberAPI>> sentByHash = new HashMap<>();
    private static long kh(int k, int h) { return ((long) k << 32) ^ (h & 0xFFFFFFFFL); }
    /** Engagement holds: fleet key -> {expiry ns}; pinned position per held fleet. */
    private static final Map<Integer, Long> holds = new HashMap<>();
    private static final Map<Integer, float[]> holdPos = new HashMap<>();
    static final ConcurrentLinkedQueue<int[]> holdQueue = new ConcurrentLinkedQueue<>(); // {key, peer}
    private static final Map<String, Long> engageCooldown = new HashMap<>();
    private static long lastRelNs;
    private static final Map<Integer, CampaignFleetAPI> byKey = new HashMap<>();
    /** Fleets sent to guests last cycle, per location: kept until they leave a wider ring, so fleets hovering at
     * the range boundary don't flip in and out of the broadcast (which made guest copies flash on and off). */
    private static final Map<String, Set<Integer>> sentNear = new HashMap<>();
    static final ConcurrentLinkedQueue<int[]> lossQueue = new ConcurrentLinkedQueue<>(); // {key, peer, index, hash}

    static int key(CampaignFleetAPI f) { return f.getId().hashCode(); }

    private static boolean isNpc(CampaignFleetAPI f) {
        if (f == null || f.isPlayerFleet() || f.isStationMode() || f.isDespawning() || f.isExpired()) return false;
        MemoryAPI m = f.getMemoryWithoutUpdate();
        if (m.contains("$starmp_puppet") || m.contains("$starmp_stage") || m.contains(PUPPET)) return false;
        return f.getFleetData().getNumMembers() > 0;
    }

    /** Host: every frame from CoopCampaign. */
    static void hostTick(NetManager net, Map<Integer, CampWire.Fleet> guests) {
        // ships guests destroyed in their battles
        int[] loss;
        while ((loss = lossQueue.poll()) != null) applyLoss(loss[0], loss[1], loss[2], loss[3]);
        long now = System.nanoTime();
        int[] hq;
        while ((hq = holdQueue.poll()) != null) hold(hq[0], 2_000_000_000L);
        pinHeld();
        if (now - lastPosNs < 250_000_000L) return;
        lastPosNs = now;
        boolean full = now - lastFullCompNs > 15_000_000_000L;
        if (full) lastFullCompNs = now;
        // locations with a guest in them, and the guests' positions
        Map<String, List<float[]>> where = new HashMap<>();
        // 0.9.4.0: only the location the host itself is in is shared (that is the only place the host's world really runs its fleets);
        // a guest anywhere else plays that place with its own fleets (see guestTick)
        String hostLoc = null;
        try { CampaignFleetAPI hp = Global.getSector().getPlayerFleet(); if (hp != null && hp.getContainingLocation() != null) hostLoc = hp.getContainingLocation().getId(); } catch (Throwable ignored) {}
        if (tutorialActive()) hostLoc = null; // 0.9.4.0: the tutorial runs on every player's own game, nothing is streamed
        for (CampWire.Fleet g : guests.values()) {
            if (g.loc == null || g.loc.isEmpty() || now - g.recvNs > 5_000_000_000L) continue;
            if (hostLoc == null || !hostLoc.equals(g.loc)) continue;
            where.computeIfAbsent(g.loc, k -> new ArrayList<>()).add(new float[]{g.x, g.y});
        }
        if (System.getenv("STARMP_TEST_NPC_SWARM") != null && !where.isEmpty()) testSwarm(where);
        for (Map.Entry<String, List<float[]>> e : where.entrySet()) {
            LocationAPI loc = CoopCampaign.location(e.getKey());
            if (loc == null) continue;
            float r = loc.isHyperspace() ? 8000f : 14000f;
            Set<Integer> prev = sentNear.getOrDefault(e.getKey(), Collections.<Integer>emptySet());
            Set<Integer> nowKeys = new HashSet<>();
            // every NPC fleet in range of a guest, nearest first; fleets already shown keep their place first, so a
            // busy system never swaps fleets in and out of the update (0.1.5.8: the old first-60-in-list cut made
            // fleets past the cap drop out and come back - blinking on the guest)
            List<Object[]> cand = new ArrayList<>();
            for (CampaignFleetAPI f : loc.getFleets()) {
                if (!isNpc(f)) continue;
                boolean shown = prev.contains(key(f));
                // hysteresis: a fleet already being shown stays until it leaves 1.3x the range
                float rr = shown ? r * 1.3f : r;
                float best = Float.MAX_VALUE;
                for (float[] p : e.getValue()) { float dx = f.getLocation().x - p[0], dy = f.getLocation().y - p[1]; best = Math.min(best, dx * dx + dy * dy); }
                if (best < rr * rr) cand.add(new Object[]{f, shown ? 0 : 1, best});
            }
            Collections.sort(cand, new java.util.Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    int c = Integer.compare((Integer) a[1], (Integer) b[1]);
                    return c != 0 ? c : Float.compare((Float) a[2], (Float) b[2]);
                }
            });
            List<CampaignFleetAPI> near = new ArrayList<>();
            for (Object[] c : cand) {
                if (near.size() >= MAX_SENT) { capped++; break; }
                CampaignFleetAPI f = (CampaignFleetAPI) c[0];
                near.add(f); nowKeys.add(key(f));
            }
            sentNear.put(e.getKey(), nowKeys);
            ByteBuffer b = ByteBuffer.allocate(1 + 2 + e.getKey().length() * 3 + 2 + near.size() * 22);
            b.put(NPCPOS); putStr(b, e.getKey()); b.putShort((short) near.size());
            for (CampaignFleetAPI f : near) {
                int k = key(f); byKey.put(k, f);
                b.putInt(k); b.putFloat(f.getLocation().x); b.putFloat(f.getLocation().y); b.putFloat(f.getVelocity().x); b.putFloat(f.getVelocity().y);
                b.put((byte) (f.getBattle() != null ? 1 : 0)); b.put((byte) (f.isTransponderOn() ? 1 : 2)); // 0 = unknown (older host)
                int h = compHash(f);
                Integer old = sentHash.get(k);
                if (full || old == null || old != h) { sendComp(net, f, k, h); }
            }
            net.broadcast(done(b));
            if (now - lastHostDiagNs > 10_000_000_000L) {
                lastHostDiagNs = now;
                int total = 0, other = 0; StringBuilder names = new StringBuilder();
                for (CampaignFleetAPI f : loc.getFleets()) { if (isNpc(f)) total++; else if (!f.isPlayerFleet()) { other++; if (names.length() < 160) names.append(f.getName()).append(f.getMemoryWithoutUpdate().contains("$starmp_puppet") ? "(player puppet)" : "").append("; "); } }
                StarMPPlugin.flog("npc host: " + near.size() + " fleet(s) near guests in " + loc.getName() + " (NPC fleets there " + total + ", non-NPC " + other + (names.length() > 0 ? ": " + names : "") + "; comps sent " + sentHash.size() + (capped > 0 ? "; over the " + MAX_SENT + " cap " + capped + "x" : "") + ")");
            }
        }
    }
    private static long swarmNs; private static boolean swarmed;
    /** Test hook (host): STARMP_TEST_NPC_SWARM=N puts N small wandering fleets around the first guest, 20 s after it
     * shows up (busy-system test for the shared NPC update). */
    private static void testSwarm(Map<String, List<float[]>> where) {
        if (swarmed) return;
        long now = System.nanoTime();
        if (swarmNs == 0) { swarmNs = now; return; }
        if (now - swarmNs < 20_000_000_000L) return;
        swarmed = true;
        Map.Entry<String, List<float[]>> e = where.entrySet().iterator().next();
        LocationAPI loc = CoopCampaign.location(e.getKey());
        if (loc == null) return;
        int n = Integer.parseInt(System.getenv("STARMP_TEST_NPC_SWARM"));
        float[] g = e.getValue().get(0);
        Random r = new Random(7);
        String[] variants = {"hound_Standard", "kite_Standard"};
        for (int i = 0; i < n; i++) {
            CampaignFleetAPI f = Global.getFactory().createEmptyFleet("independent", "Swarm " + i, true);
            f.getFleetData().addFleetMember(Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variants[i % 2]));
            f.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
            f.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            loc.addEntity(f);
            double a = r.nextDouble() * Math.PI * 2; float d = 800f + r.nextFloat() * 13000f;
            f.setLocation(g[0] + (float) Math.cos(a) * d, g[1] + (float) Math.sin(a) * d);
            f.addAssignment(FleetAssignment.PATROL_SYSTEM, null, 1e6f);
        }
        StarMPPlugin.flog("TEST: swarm of " + n + " fleets put around the guest in " + loc.getName());
    }

    private static long lastHostDiagNs, lastGuestDiagNs;
    /** Most NPC fleets sent per location (each costs 22 bytes per update, 4 updates a second). */
    private static final int MAX_SENT = 120;
    private static int capped;

    private static int compHash(CampaignFleetAPI f) {
        int h = f.getFaction().getId().hashCode() * 31 + f.getName().hashCode();
        for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) h = h * 31 + m.getId().hashCode();
        return h;
    }

    private static void sendComp(NetManager net, CampaignFleetAPI f, int k, int h) {
        List<FleetMemberAPI> ms = new ArrayList<>();
        for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) if (!m.isFighterWing()) ms.add(m);
        byte[] defs = Wire.encodeDefs(Wire.SHIPDEFS, -1, Ships.fleetDefs(ms));
        String fac = f.getFaction().getId(), name = f.getName() == null ? "" : f.getName();
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 2 + fac.length() * 3 + 2 + name.length() * 3 + defs.length);
        b.put(NPCCOMP); b.putInt(k); b.putInt(h); putStr(b, fac); putStr(b, name); b.put(defs);
        net.broadcast(done(b));
        sentHash.put(k, h); sentMembers.put(k, ms);
        if (sentByHash.size() > 3000) sentByHash.clear();
        sentByHash.put(kh(k, h), ms);
    }

    private static void applyLoss(int k, int peer, int index, int hash) {
        CampaignFleetAPI f = byKey.get(k);
        List<FleetMemberAPI> ms = sentByHash.get(kh(k, hash));
        if (ms == null) ms = sentMembers.get(k);
        if (f == null || !f.isAlive() || ms == null || index < 0 || index >= ms.size()) { StarMPPlugin.flog("npc: loss for unknown fleet/ship " + k + "/" + index + " from peer " + peer); return; }
        FleetMemberAPI m = ms.get(index);
        if (f.getFleetData().getMembersListCopy().contains(m)) {
            f.getFleetData().removeFleetMember(m);
            StarMPPlugin.flog("npc: " + f.getName() + " lost " + m.getHullSpec().getHullId() + " to player " + peer);
        }
        boolean anyLeft = false;
        for (FleetMemberAPI x : f.getFleetData().getMembersListCopy()) if (!x.isFighterWing()) { anyLeft = true; break; }
        if (!anyLeft) {
            StarMPPlugin.flog("npc: " + f.getName() + " destroyed by player " + peer);
            try { f.despawn(CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE, null); } catch (Throwable t) { f.getContainingLocation().removeEntity(f); }
            byKey.remove(k); holds.remove(k); holdPos.remove(k);
        } else {
            try { f.getFleetData().setSyncNeeded(); f.forceSync(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- host: engagement hold
    private static void hold(int k, long forNs) {
        CampaignFleetAPI f = byKey.get(k);
        if (f == null || !f.isAlive() || f.getBattle() != null) return; // never touch a fleet that is in a battle
        long until = System.nanoTime() + forNs;
        Long old = holds.get(k);
        if (old == null) { holdPos.put(k, new float[]{f.getLocation().x, f.getLocation().y}); StarMPPlugin.flog("npc: holding " + f.getName() + " (someone is talking to it)"); }
        if (old == null || old < until) holds.put(k, until);
    }

    /** Host: keep held fleets still (also called right after a never-pause background step). */
    static void pinHeld() {
        if (holds.isEmpty()) return;
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Integer, Long>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> e = it.next();
            CampaignFleetAPI f = byKey.get(e.getKey());
            float[] p = holdPos.get(e.getKey());
            if (f == null || !f.isAlive() || now > e.getValue() || f.getBattle() != null) {
                if (f != null) { try { f.setDoNotAdvanceAI(null); } catch (Throwable ignored) {} StarMPPlugin.flog("npc: released " + f.getName()); }
                holdPos.remove(e.getKey()); it.remove(); continue;
            }
            f.setDoNotAdvanceAI(true);
            if (p != null && f.getBattle() == null) { f.setLocation(p[0], p[1]); f.setVelocity(0f, 0f); }
        }
    }

    // ---------------------------------------------------------------- host: guests as targets
    /**
     * Host: the other players' fleets on this map are stand-ins the host's NPC AI can see - so pirates chase guests,
     * patrols flee from strong ones, etc. (the host's NPCs are the only real ones). They get the player faction's
     * relations. When a hostile NPC reaches a guest's stand-in, the host does NOT fight it out locally: it tells that
     * guest (NPCENGAGE), which opens the encounter with its copy of that fleet; the stand-in is briefly ignored so no
     * AI-vs-stand-in battle can start here.
     */
    static void hostStandIns(NetManager net, Map<Integer, CampaignFleetAPI> guestFleets) {
        long now = System.nanoTime();
        SectorAPI sector = Global.getSector();
        if (now - lastRelNs > 5_000_000_000L) {
            lastRelNs = now;
            // each stand-in faction carries ITS player's standings (from their record; a player without one yet has
            // a new captain's standings)
            Set<String> done = new HashSet<>();
            for (CampaignFleetAPI p : guestFleets.values()) {
                if (p == null || p.getFaction() == null || !done.add(p.getFaction().getId())) continue;
                FactionAPI g = p.getFaction();
                String owner = CoopCampaign.slotOwner(g.getId());
                Map<String, Float> rel = owner == null ? null : PlayerRecords.relationsFor(owner);
                for (FactionAPI f : sector.getAllFactions()) {
                    String id = f.getId();
                    if (id.equals("player") || id.startsWith("starmp_")) continue;
                    Float v = rel == null ? null : rel.get(id);
                    g.setRelationship(id, v != null ? v : NewPlayerStart.defaultRelation(id));
                }
            }
        }
        for (Map.Entry<Integer, CampaignFleetAPI> e : guestFleets.entrySet()) {
            int peer = e.getKey(); if (peer == 0) continue;
            CampaignFleetAPI p = e.getValue();
            if (p == null || !p.isAlive() || p.getContainingLocation() == null) continue;
            MemoryAPI pm = p.getMemoryWithoutUpdate();
            if (p.getBattle() != null) { // should never happen; don't let an AI fight a stand-in
                try { p.getBattle().leave(p, false); } catch (Throwable ignored) {}
                StarMPPlugin.flog("npc: pulled player " + peer + "'s stand-in out of a host-side battle");
            }
            boolean cooling = pm.getBoolean("$starmp_engaged");
            if (!cooling) { pm.unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS); pm.unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE); }
            if (cooling) continue;
            for (CampaignFleetAPI f : p.getContainingLocation().getFleets()) {
                if (!isNpc(f) || f.getBattle() != null || !f.isHostileTo(p)) continue;
                float dx = f.getLocation().x - p.getLocation().x, dy = f.getLocation().y - p.getLocation().y;
                float reach = f.getRadius() + p.getRadius() + 60f;
                if (dx * dx + dy * dy > reach * reach) continue;
                if (f.getAI() != null && f.getAI().isFleeing()) continue;
                String ck = peer + ":" + key(f);
                Long until = engageCooldown.get(ck);
                if (until != null && now < until) continue;
                engageCooldown.put(ck, now + 30_000_000_000L);
                byKey.put(key(f), f);
                if (sentHash.get(key(f)) == null) sendComp(net, f, key(f), compHash(f)); // make sure they can show it
                hold(key(f), 6_000_000_000L);
                pm.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true, 2f); // ~20 s
                pm.set("$starmp_engaged", true, 2f);
                ByteBuffer b = ByteBuffer.allocate(5); b.put(NPCENGAGE); b.putInt(key(f));
                starmp.combat.CoopState.sendTo(net, peer, b.array());
                StarMPPlugin.flog("npc: " + f.getName() + " intercepts player " + peer + " - encounter handed to them");
                break;
            }
        }
    }

    // ---------------------------------------------------------------- guest
    private static final class Puppet {
        CampaignFleetAPI fleet; int key, hash; long seenNs, lastMoveNs;
        final List<FleetMemberAPI> created = new ArrayList<>();
        final Set<Integer> reported = new HashSet<>();
        float x, y, vx, vy, fade = 1f;
        boolean inBattle; // the host's real fleet is in a battle
    }

    static final class Comp { int hash; String faction, name; List<Wire.ShipDef> defs; }
    static final ConcurrentHashMap<Integer, Comp> comps = new ConcurrentHashMap<>();
    /** Latest fleet positions per location, {loc, int[] keys, float[][] pos, recvNs}. One slot per location (0.1.5.8):
     * with guests in two systems the host's updates alternate between them, and a single slot made each guest drop and
     * re-create every shown fleet whenever the other system's update arrived (blinking). */
    static final java.util.concurrent.ConcurrentHashMap<String, Object[]> latestPos = new java.util.concurrent.ConcurrentHashMap<>();
    static Object[] posFor(LocationAPI here) { return here == null ? null : latestPos.get(here.getId()); }
    private static final Map<Integer, Puppet> puppets = new HashMap<>();
    /** Fleets we destroyed: key -> {hash it had, when}. Their copy is not shown again until the host's composition
     * changes (or 60 s pass), so a destroyed fleet doesn't pop back before the host has processed the loss. */
    private static final Map<Integer, long[]> destroyed = new HashMap<>();
    static final ConcurrentLinkedQueue<long[]> engageQueue = new ConcurrentLinkedQueue<>(); // {key, recvNs}
    private static long lastHoldSendNs;
    private static long lastHideNs; private static int recompLogs;
    private static boolean testEngaged;
    private static int hiddenCount;
    private static final boolean SLOW_HIDE = System.getenv("STARMP_TEST_SLOWHIDE") != null;
    private static int leaked, leakedTotal, dimTotal; // 0.9.3.6: hidden local copies that were still visible at the start of a frame

    public static void dispatch(int peer, byte t, byte[] d) { onPacket(peer, t, d); }

    /** Guest: is this shown fleet (a copy of the host's) in a battle on the host? */
    static boolean puppetInBattle(CampaignFleetAPI f) {
        for (Puppet p : puppets.values()) if (p.fleet == f) return p.inBattle;
        return false;
    }

    /** A fleet this game spawned for its own player's mission/bounty: individual, never hidden (0.1.4.9). */
    static boolean ownMissionFleet(CampaignFleetAPI f) {
        return f.getMemoryWithoutUpdate().contains(MemFlags.ENTITY_MISSION_IMPORTANT);
    }

    static void onPacket(int peer, byte t, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        if (t == NPCPOS) {
            String loc = getStr(b); int n = b.getShort() & 0xFFFF;
            int[] keys = new int[n]; float[][] pos = new float[n][6];
            for (int i = 0; i < n; i++) { keys[i] = b.getInt(); pos[i][0] = b.getFloat(); pos[i][1] = b.getFloat(); pos[i][2] = b.getFloat(); pos[i][3] = b.getFloat(); pos[i][4] = b.get(); pos[i][5] = b.get(); }
            latestPos.put(loc, new Object[]{loc, keys, pos, System.nanoTime()});
        } else if (t == NPCCOMP) {
            Comp c = new Comp(); int k = b.getInt(); c.hash = b.getInt(); c.faction = getStr(b); c.name = getStr(b);
            byte[] rest = new byte[b.remaining()]; b.get(rest);
            c.defs = Wire.decodeDefs(rest);
            comps.put(k, c);
        } else if (t == NPCLOSS) {
            int k = b.getInt(); int h = b.getInt(); int n = b.getShort() & 0xFFFF;
            for (int i = 0; i < n; i++) lossQueue.add(new int[]{k, peer, b.getShort(), h});
        } else if (t == NPCHOLD) {
            holdQueue.add(new int[]{b.getInt(), peer});
        } else if (t == NPCENGAGE) {
            engageQueue.add(new long[]{b.getInt(), System.nanoTime()});
        }
    }

    /** Guest: every frame from CoopCampaign. */
    static void guestTick(NetManager net, SectorAPI sector) {
        CampaignFleetAPI me = sector.getPlayerFleet();
        if (me == null || me.getContainingLocation() == null) return;
        LocationAPI here = me.getContainingLocation();
        // 0.9.4.0: the host's fleets are only shared where the host itself is. Anywhere else this game runs its own fleets as normal
        // (before, the guest hid them and the host had none there: empty systems).
        if (!hostHere(here)) {
            if (!puppets.isEmpty()) removeAll("the host is not in " + here.getName());
            if (hiddenCount > 0) unhideIn(here);
            return;
        }
        Object[] lp = posFor(here);
        boolean fresh = lp != null && here.getId().equals(lp[0]) && System.nanoTime() - (Long) lp[3] < 5_000_000_000L;
        long now = System.nanoTime();
        if (now - lastGuestDiagNs > 10_000_000_000L) {
            lastGuestDiagNs = now;
            StarMPPlugin.flog("npc guest: data " + (lp == null ? "none" : "for " + lp[0] + " (" + ((int[]) lp[1]).length + " fleets, age " + (now - (Long) lp[3]) / 1_000_000 + " ms)") + " here=" + here.getId() + " comps=" + comps.size() + " shown=" + puppets.size());
            try { int c = 0; StringBuilder sb = new StringBuilder("npc guest: motion"); for (Puppet q : puppets.values()) { if (c++ >= 3) break; CampaignFleetAPI f = q.fleet; Vector2f mv = f.getVelocityFromMovementModule(); sb.append(" | v=").append(r(f.getVelocity().x)).append(',').append(r(f.getVelocity().y)).append(" mod=").append(r(mv.x)).append(',').append(r(mv.y)).append(" burn=").append(f.getCurrBurnLevel()).append(" spd=").append(r(f.getTravelSpeed())); } StarMPPlugin.flog(sb.toString()); } catch (Throwable t) { StarMPPlugin.flog("npc guest: motion probe failed " + t); }
        }
        if (!fresh) {
            // right after our own battle the host hasn't had our position for a while: keep what we show for a bit
            if (sector.getCampaignUI().getCurrentInteractionDialog() != null) return; // still in our battle's dialogs
            if (lp == null || !here.getId().equals(lp[0]) || now - (Long) lp[3] > 30_000_000_000L) { reportAll(net); if (!puppets.isEmpty()) removeAll("no data for this location"); }
            return;
        }
        // hide this game's own moving NPC fleets here: the host's are the real ones
        // every frame for fleets that just appeared here (a new local fleet was visible for up to 300 ms before:
        // a blink), the full re-force of the hidden ones every 300 ms
        boolean fullHide = SLOW_HIDE ? now - lastHideNs > 300_000_000L : true; // 0.9.3.6: every frame (was every 300 ms: a hidden local copy faded back in between and flashed)
        if (fullHide) lastHideNs = now;
        purgeLocal(here);
        CampaignUIAPI ui = sector.getCampaignUI();
        SectorEntityToken talking = ui.getCurrentInteractionDialog() != null ? ui.getCurrentInteractionDialog().getInteractionTarget() : null;
        int[] keys = (int[]) lp[1]; float[][] pos = (float[][]) lp[2];
        float age = Math.min(2f, (now - (Long) lp[3]) / 1e9f); // 0.9.2.9: predict up to 2 s between host updates (was 1)
        // 0.9.2.9: the glide toward the host's spot is time-based (same feel at any frame rate; was 25% per frame)
        float frameDt = guestFrameNs == 0 ? 0f : Math.min(0.25f, (now - guestFrameNs) / 1e9f); guestFrameNs = now;
        float glide = 1f - (float) Math.exp(-frameDt * 6f);
        for (Puppet q : puppets.values()) { try { if (q.fleet.getSensorFaderBrightness() < 0.9f || q.fleet.getSensorContactFaderBrightness() < 0.9f) dimTotal++; } catch (Throwable ignored) {} } // 0.9.3.6 diag
        Set<Integer> live = new HashSet<>();
        for (int i = 0; i < keys.length; i++) {
            int k = keys[i]; live.add(k);
            Comp c = comps.get(k);
            if (c == null) continue;
            long[] dz = destroyed.get(k);
            if (dz != null) { if (dz[0] == c.hash && now - dz[1] < 60_000_000_000L) continue; destroyed.remove(k); }
            Puppet p = puppets.get(k);
            boolean busy = p != null && (p.fleet == talking || p.fleet.getBattle() != null);
            if (p != null && p.hash != c.hash && !busy) {
                if (recompLogs++ < 20) StarMPPlugin.flog("npc: host fleet '" + c.name + "' changed composition - rebuilt (" + p.created.size() + " -> " + c.defs.size() + " ships)");
                reportLosses(net, p); despawn(p); puppets.remove(k); p = null;
            }
            float px = pos[i][0] + pos[i][2] * age, py = pos[i][1] + pos[i][3] * age;
            if (p == null) { p = spawn(k, c, here, px, py); if (p == null) continue; puppets.put(k, p); }
            p.seenNs = now; p.lastMoveNs = now; p.vx = pos[i][2]; p.vy = pos[i][3]; p.inBattle = pos[i][4] != 0;
            if (p.fade < 1f) { p.fade = 1f; p.fleet.forceSensorContactFaderBrightness(1f); }
            if (pos[i][5] != 0) { boolean on = pos[i][5] == 1; if (p.fleet.isTransponderOn() != on) p.fleet.setTransponderOn(on); }
            if (busy) continue; // don't move a fleet we are talking to / fighting
            CampaignFleetAPI f = p.fleet;
            float dx = px - f.getLocation().x, dy = py - f.getLocation().y;
            // snap only on a real teleport (jump point, new spawn); otherwise glide toward the predicted spot, so a
            // fleet that got ahead / behind slides back over about half a second instead of jumping
            if (dx * dx + dy * dy > 1500f * 1500f) { f.setLocation(px, py); snapsNpc++; }
            else f.setLocation(f.getLocation().x + dx * glide, f.getLocation().y + dy * glide);
            f.setVelocity(pos[i][2], pos[i][3]);
            f.setMoveDestinationOverride(px + pos[i][2] * 0.5f, py + pos[i][3] * 0.5f);
            if (sector.isPaused()) { try { f.advance(0.001f); } catch (Throwable ignored) {} }
        }
        // engagement hold: tell the host which of its fleets we're talking to, so it stays put there
        if (talking != null && now - lastHoldSendNs > 500_000_000L) {
            for (Puppet p : puppets.values()) if (p.fleet == talking) {
                lastHoldSendNs = now;
                ByteBuffer hb = ByteBuffer.allocate(5); hb.put(NPCHOLD); hb.putInt(p.key);
                net.sendToHost(hb.array());
                break;
            }
        }
        // interceptions decided by the host: open the encounter with our copy of that fleet
        long[] eg;
        while ((eg = engageQueue.peek()) != null) {
            if (now - eg[1] > 5_000_000_000L) { engageQueue.poll(); continue; }
            if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog() || ui.getCurrentCoreTab() != null) break; // try again next frame
            engageQueue.poll();
            Puppet p = puppets.get((int) eg[0]);
            if (p == null || !p.fleet.isAlive()) { StarMPPlugin.flog("npc: interception by an unknown fleet " + eg[0] + " ignored"); continue; }
            float dx = p.fleet.getLocation().x - me.getLocation().x, dy = p.fleet.getLocation().y - me.getLocation().y;
            if (dx * dx + dy * dy > 900f * 900f) { StarMPPlugin.flog("npc: interception by " + p.fleet.getName() + " ignored (too far here)"); continue; }
            p.fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true, 1f);
            StarMPPlugin.flog("npc: intercepted by " + p.fleet.getName() + " (host's fleet) - opening the encounter");
            ui.showInteractionDialog(p.fleet);
            break;
        }
        // test hook: talk to the first shared NPC fleet (STARMP_TEST_ENGAGE_NPC=1)
        if (!testEngaged && "1".equals(System.getenv("STARMP_TEST_ENGAGE_NPC")) && !puppets.isEmpty() && ui.getCurrentInteractionDialog() == null && !ui.isShowingDialog()) {
            testEngaged = true;
            Puppet p = puppets.values().iterator().next();
            for (Puppet q : puppets.values()) if (q.fleet.getName().contains("Test")) p = q;
            StarMPPlugin.flog("npc test: opening interaction with " + p.fleet.getName());
            ui.showInteractionDialog(p.fleet);
        }
        // our battles: ships we destroyed come off the host's real fleet
        for (Iterator<Map.Entry<Integer, Puppet>> it = puppets.entrySet().iterator(); it.hasNext(); ) {
            Puppet p = it.next().getValue();
            boolean busy = p.fleet == talking || p.fleet.getBattle() != null;
            if (!busy) reportLosses(net, p);
            boolean gone = !p.fleet.isAlive() || p.fleet.getContainingLocation() != here;
            if (!live.contains(p.key) && !busy && !gone) {
                // missing from this update: don't blink it out - keep dead-reckoning on its last velocity, then
                // fade the sensor contact over the last second before removing it, instead of a hard pop.
                float dt = Math.min(0.5f, (now - p.lastMoveNs) / 1e9f); p.lastMoveNs = now;
                CampaignFleetAPI f = p.fleet;
                f.setLocation(f.getLocation().x + p.vx * dt, f.getLocation().y + p.vy * dt);
                long stale = now - p.seenNs;
                if (stale > 5_000_000_000L) { p.fade = Math.max(0f, 1f - (stale - 5_000_000_000L) / 1_000_000_000f); f.forceSensorContactFaderBrightness(p.fade); }
            }
            if (!busy && gone && !p.fleet.isAlive()) destroyed.put(p.key, new long[]{p.hash, now});
            if (!busy && (gone || (!live.contains(p.key) && now - p.seenNs > 6_000_000_000L))) { despawn(p); it.remove(); }
        }
    }

    private static long guestFrameNs;
    static int snapsNpc; // debug log: hard snaps (should stay near 0)

    private static void reportLosses(NetManager net, Puppet p) {
        List<FleetMemberAPI> now = p.fleet.isAlive() ? p.fleet.getFleetData().getMembersListCopy() : Collections.<FleetMemberAPI>emptyList();
        List<Integer> lost = new ArrayList<>();
        for (int i = 0; i < p.created.size(); i++) if (!p.reported.contains(i) && !now.contains(p.created.get(i))) lost.add(i);
        if (lost.isEmpty()) return;
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 2 + lost.size() * 2);
        b.put(NPCLOSS); b.putInt(p.key); b.putInt(p.hash); b.putShort((short) lost.size());
        for (int i : lost) { b.putShort((short) i); p.reported.add(i); }
        net.sendToHost(done(b));
        StarMPPlugin.flog("npc: told the host " + p.fleet.getName() + " lost " + lost.size() + " ship(s)");
    }

    /** Guest in a campaign battle: keep holding every host fleet in it (the campaign script doesn't run in combat). */
    public static void combatHoldTick(NetManager net) {
        long now = System.nanoTime();
        if (net == null || net.isHost() || puppets.isEmpty() || now - lastHoldSendNs < 500_000_000L) return;
        CampaignFleetAPI me = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        BattleAPI battle = me == null ? null : me.getBattle();
        if (battle == null) return;
        lastHoldSendNs = now;
        List<CampaignFleetAPI> in = battle.getBothSides();
        for (Puppet p : puppets.values()) if (in.contains(p.fleet)) {
            ByteBuffer hb = ByteBuffer.allocate(5); hb.put(NPCHOLD); hb.putInt(p.key);
            net.sendToHost(hb.array());
        }
    }

    private static Puppet spawn(int k, Comp c, LocationAPI loc, float x, float y) {
        if (Global.getSector().getFaction(c.faction) == null) return null;
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(c.faction, c.name, true);
        Puppet p = new Puppet(); p.key = k; p.hash = c.hash; p.fleet = f;
        for (int i = 0; i < c.defs.size(); i++) {
            try { FleetMemberAPI m = Ships.member(c.defs.get(i), "npc" + Math.abs(k) + "_" + i); f.getFleetData().addFleetMember(m); p.created.add(m); }
            catch (Throwable t) { p.created.add(null); }
        }
        if (f.getFleetData().getNumMembers() == 0) return null;
        StarMPPlugin.flog("npc: showing host fleet '" + c.name + "' (" + c.faction + ", " + f.getFleetData().getNumMembers() + " ships)");
        f.forceSync();
        f.setNoAutoDespawn(true);
        MemoryAPI mem = f.getMemoryWithoutUpdate();
        mem.set(PUPPET, true);
        mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true); // phase 1: they don't chase anybody here
        mem.set(MemFlags.MEMORY_KEY_NO_JUMP, true);
        loc.addEntity(f);
        f.setLocation(x, y);
        f.clearAssignments();
        f.addAssignment(FleetAssignment.HOLD, loc.createToken(x, y), 1e6f);
        return p;
    }

    private static void reportAll(NetManager net) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        for (Puppet p : puppets.values()) if (ui.getCurrentInteractionDialog() == null) reportLosses(net, p);
    }

    private static void despawn(Puppet p) {
        try { if (p.fleet.getContainingLocation() != null) p.fleet.getContainingLocation().removeEntity(p.fleet); } catch (Throwable ignored) {}
    }

    static void removeAll(String why) {
        if (puppets.isEmpty()) return;
        for (Puppet p : puppets.values()) despawn(p);
        StarMPPlugin.flog("npc: removed " + puppets.size() + " shared NPC fleet(s) (" + why + ")");
        puppets.clear();
    }

    /** Guest: is the host's player fleet in this location right now (so its fleets here are the real ones)? */
    static boolean hostHere(LocationAPI here) {
        if (tutorialActive()) return false;
        try {
            CampWire.Fleet h = CoopCampaign.fleets.get(0);
            return h != null && here != null && here.getId().equals(h.loc) && System.nanoTime() - h.recvNs < 8_000_000_000L;
        } catch (Throwable t) { return false; }
    }

    /** 0.9.4.0: the vanilla tutorial (rogue miner, scripted pirates) is still running in this game: it is local to each player, so the host's
     * copies of its fleets are neither sent nor shown. */
    static boolean tutorialActive() {
        try { if (com.fs.starfarer.api.impl.campaign.tutorial.TutorialMissionIntel.isTutorialInProgress()) return true; } catch (Throwable ignored) {}
        try {
            for (com.fs.starfarer.api.EveryFrameScript sc : Global.getSector().getScripts())
                if (sc instanceof com.fs.starfarer.api.impl.campaign.tutorial.CampaignTutorialScript && !sc.isDone()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static int purged;
    /** 0.9.4.0: where the host's fleets are shown, this game's own NPC fleets must not exist at all - a hidden fleet still flies, pings the
     * sensors and can ambush the player unseen. They are removed (and removed again whenever the game spawns a new one). */
    private static void purgeLocal(LocationAPI here) {
        int n = 0; StringBuilder names = new StringBuilder();
        for (CampaignFleetAPI f : new ArrayList<>(here.getFleets())) {
            if (!isNpc(f) || ownMissionFleet(f)) continue;
            try { if (f.getBattle() != null) continue; } catch (Throwable ignored) {}
            if (names.length() < 200) names.append(f.getName()).append(" [").append(f.getFaction().getId()).append("]; ");
            try { f.despawn(CampaignEventListener.FleetDespawnReason.OTHER, null); }
            catch (Throwable t) { try { here.removeEntity(f); } catch (Throwable ignored) {} }
            n++;
        }
        if (n > 0) { purged += n; StarMPPlugin.flog("npc: removed " + n + " local NPC fleet(s) in " + here.getName() + " (the host's are shown instead; total " + purged + "): " + names); }
    }

    /** The host left this location: clear the hide flags on any fleet that is still marked (old saves / older builds). */
    private static void unhideIn(LocationAPI here) {
        for (CampaignFleetAPI f : here.getFleets()) {
            MemoryAPI m = f.getMemoryWithoutUpdate();
            if (!m.contains(HIDDEN)) continue;
            m.unset(HIDDEN); m.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS); m.unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            f.setAlwaysUseSensorFaderBrightness(null);
        }
    }

    private static void hideLocal(LocationAPI here, boolean full) {
        int n = 0; StringBuilder names = new StringBuilder(); leaked = 0;
        for (CampaignFleetAPI f : here.getFleets()) {
            if (!isNpc(f)) continue;
            MemoryAPI m = f.getMemoryWithoutUpdate();
            if (ownMissionFleet(f)) { // missions and bounties are individual: this player's own targets stay in their game
                if (m.contains(HIDDEN)) { m.unset(HIDDEN); m.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS); m.unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS); f.setAlwaysUseSensorFaderBrightness(null); StarMPPlugin.flog("npc: " + f.getName() + " is one of my mission targets - shown"); }
                continue;
            }
            boolean fresh = !m.contains(HIDDEN);
            if (!fresh && !full) continue;
            if (!fresh) { try { if (f.getSensorFaderBrightness() > 0.02f || f.getSensorContactFaderBrightness() > 0.02f) leaked++; } catch (Throwable ignored) {} }
            if (fresh) {
                if (names.length() < 200) names.append(f.getName()).append(" [").append(f.getFaction().getId()).append("]; ");
                m.set(HIDDEN, true);
                m.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
                m.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
                n++;
            }
            f.setAlwaysUseSensorFaderBrightness(true);
            f.forceSensorFaderBrightness(0f);
            f.forceSensorContactFaderBrightness(0f);
            f.setNoEngaging(5f);
        }
        if (leaked > 0) leakedTotal += leaked;
        if (n > 0) { hiddenCount += n; StarMPPlugin.flog("npc: hid " + n + " local NPC fleet(s) in " + here.getName() + " (host's are shown instead): " + names); }
    }

    /** Before a save / when the link ends: our NPC copies go, this game's own NPC fleets come back. */
    static void restoreAll(String why) {
        sentNear.clear(); latestPos.clear();
        for (Integer k : new ArrayList<>(holds.keySet())) { CampaignFleetAPI f = byKey.get(k); if (f != null) try { f.setDoNotAdvanceAI(null); } catch (Throwable ignored) {} }
        holds.clear(); holdPos.clear();
        removeAll(why);
        if (hiddenCount == 0) return;
        int n = 0;
        for (LocationAPI loc : allLocations()) for (CampaignFleetAPI f : loc.getFleets()) {
            MemoryAPI m = f.getMemoryWithoutUpdate();
            if (!m.contains(HIDDEN)) continue;
            m.unset(HIDDEN); m.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS); m.unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            f.setAlwaysUseSensorFaderBrightness(null);
            n++;
        }
        hiddenCount = 0;
        StarMPPlugin.flog("npc: restored " + n + " local NPC fleet(s) (" + why + ")");
    }

    private static List<LocationAPI> allLocations() {
        List<LocationAPI> l = new ArrayList<LocationAPI>(Global.getSector().getStarSystems());
        l.add(Global.getSector().getHyperspace());
        return l;
    }

    // ---------------------------------------------------------------- bytes
    // ---------------------------------------------------------------- 0.1.5.3 debug log
    static String nm(String s) { return s == null || s.isEmpty() ? "-" : s.replace(' ', '_').replace('|', '/'); }
    private static String r(float v) { return String.valueOf(Math.round(v)); }

    /** Host: every NPC fleet currently streamed to guests, with the key guests know it by. */
    static void diagHost(List<String> out) {
        for (Map.Entry<String, Set<Integer>> e : sentNear.entrySet()) {
            for (Integer k : e.getValue()) {
                CampaignFleetAPI f = byKey.get(k);
                if (f == null) continue;
                out.add("NPC-H k=" + k + " name=" + nm(f.getName()) + " fac=" + f.getFaction().getId() + " loc=" + e.getKey()
                        + " x=" + r(f.getLocation().x) + " y=" + r(f.getLocation().y) + " vx=" + r(f.getVelocity().x) + " vy=" + r(f.getVelocity().y)
                        + " ships=" + f.getFleetData().getNumMembers() + " battle=" + (f.getBattle() != null ? 1 : 0) + " held=" + (holds.containsKey(k) ? 1 : 0)
                        + " alive=" + (f.isAlive() ? 1 : 0) + " ai=" + nm(f.getCurrentAssignment() == null ? "none" : String.valueOf(f.getCurrentAssignment().getAssignment())));
            }
        }
    }

    /** Guest: every host fleet we show (with where the host says it is now) and any local fleet that escaped hiding. */
    static void diagGuest(List<String> out, LocationAPI here) {
        Object[] lp = posFor(here);
        long now = System.nanoTime();
        Map<Integer, float[]> hostNow = new HashMap<>();
        long ageMs = -1;
        if (lp != null && here != null && here.getId().equals(lp[0])) {
            int[] keys = (int[]) lp[1]; float[][] pos = (float[][]) lp[2];
            float age = Math.min(1f, (now - (Long) lp[3]) / 1e9f); ageMs = (now - (Long) lp[3]) / 1_000_000;
            for (int i = 0; i < keys.length; i++) hostNow.put(keys[i], new float[]{pos[i][0] + pos[i][2] * age, pos[i][1] + pos[i][3] * age, pos[i][4]});
        }
        out.add("NPC-SUM snaps=" + snapsNpc + " data=" + (lp == null ? "none" : lp[0] + " age_ms=" + ((now - (Long) lp[3]) / 1_000_000) + " fleets=" + ((int[]) lp[1]).length)
                + " here=" + (here == null ? "-" : here.getId()) + " comps=" + comps.size() + " shown=" + puppets.size() + " destroyed_mem=" + destroyed.size() + " hidden_total=" + hiddenCount + " leaked_frames=" + leakedTotal + " puppet_dim_frames=" + dimTotal);
        for (Puppet p : puppets.values()) {
            CampaignFleetAPI f = p.fleet;
            float[] h = hostNow.get(p.key);
            String err = "-";
            if (h != null) { float dx = f.getLocation().x - h[0], dy = f.getLocation().y - h[1]; err = r((float) Math.sqrt(dx * dx + dy * dy)); }
            String state = !f.isAlive() ? "dead" : p.fade < 1f ? "fading" : h == null ? "missing-from-update" : "shown";
            out.add("NPC-G k=" + p.key + " name=" + nm(f.getName()) + " state=" + state + " x=" + r(f.getLocation().x) + " y=" + r(f.getLocation().y)
                    + " vx=" + r(p.vx) + " vy=" + r(p.vy) + " host_x=" + (h == null ? "-" : r(h[0])) + " host_y=" + (h == null ? "-" : r(h[1])) + " err=" + err
                    + " stale_ms=" + (now - p.seenNs) / 1_000_000 + " fade=" + String.format(Locale.ROOT, "%.2f", p.fade) + " host_battle=" + (p.inBattle ? 1 : 0)
                    + " local_battle=" + (f.getBattle() != null ? 1 : 0) + " ships=" + f.getFleetData().getNumMembers() + " hash=" + p.hash);
        }
        if (here != null) for (CampaignFleetAPI f : here.getFleets()) {
            if (!isNpc(f)) continue;
            boolean hidden = f.getMemoryWithoutUpdate().contains(HIDDEN), mission = ownMissionFleet(f);
            if (hidden && !mission) continue;
            out.add((mission ? "NPC-MISSION" : "NPC-LEAK") + " name=" + nm(f.getName()) + " fac=" + f.getFaction().getId() + " x=" + r(f.getLocation().x) + " y=" + r(f.getLocation().y)
                    + " ships=" + f.getFleetData().getNumMembers() + " id=" + f.getId().hashCode());
        }
        if (ageMs > 3000) out.add("NPC-STALE update_age_ms=" + ageMs);
    }

    private static void putStr(ByteBuffer b, String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); b.putShort((short) d.length); b.put(d); }
    private static String getStr(ByteBuffer b) { byte[] d = new byte[b.getShort() & 0xFFFF]; b.get(d); return new String(d, StandardCharsets.UTF_8); }
    private static byte[] done(ByteBuffer b) { byte[] o = new byte[b.position()]; b.flip(); b.get(o); return o; }
}
