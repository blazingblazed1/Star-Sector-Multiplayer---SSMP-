package starmp.combat;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * Combat co-op, host authoritative.
 * Host: numbers every ship, sends each ship's exact loadout (SHIPDEFS), drives one allied ship per client from
 * their input, broadcasts snapshots (20 Hz) and new projectiles.
 * Client: whatever battle it is in, it clears its own ships and rebuilds the host's battle from SHIPDEFS, makes
 * those ships unable to take local damage (the host decides HP), copies snapshots onto them, shows the host's
 * shots and removes its own, and flies its assigned ship.
 */
public class CoopCombatPlugin extends BaseEveryFrameCombatPlugin {
    private static final float SNAPSHOT_INTERVAL = 0.05f, INPUT_INTERVAL = 0.033f;
    private static final String MIRROR = "starmp_mirror";

    private CombatEngineAPI engine;
    private int tick, inputSeq, group;
    private boolean announced, titleScreen;
    /** true = this side mirrors someone else's battle; false = this side owns the battle (authority). */
    private boolean mirrorRole;
    private Bindings keys;

    // host
    private final Map<ShipAPI, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, ShipAPI> byId = new HashMap<>();
    private int nextId = 1;
    private final Map<Integer, ShipAPI> assigned = new HashMap<>();
    private final Map<Integer, RemoteShipAI> ais = new HashMap<>();
    private final Map<Integer, Integer> pauseSeen = new HashMap<>();
    private Set<DamagingProjectileAPI> seenProj = Collections.newSetFromMap(new IdentityHashMap<DamagingProjectileAPI, Boolean>());
    private long lastSnapNs, lastDefsNs, lastBattleNs;
    private boolean battleEndSent;
    private final int battleId = new Random().nextInt(Integer.MAX_VALUE);
    private int defsSentCount = -1, projSent;

    // client
    private final Map<Integer, ShipAPI> mirrors = new HashMap<>();
    /** Client: each mirror ship -> owner ship id (-1 = spare wing member). getCustomData() can't be written to. */
    private final Map<ShipAPI, Integer> mirrorId = new IdentityHashMap<>();
    private final Set<ShipAPI> mirrorSet = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Set<CombatEntityAPI> mirroredProj = Collections.newSetFromMap(new IdentityHashMap<CombatEntityAPI, Boolean>());
    private boolean tookOver, endingCombat;
    private int fightersShown, beamsShown, missilesSteered, missileCounter;
    private boolean hadMissiles, clientOverLogged, doNotEndSet, undoneOver;
    /** 0.9.2.5 PvP. Owner: the player whose fleet joins as the ENEMY (side 1), -1 = none. Mirror: flip = I fight on the
     *  owner's side 1, shown locally as side 0 (so the HUD, tactical map and own-ship code work unchanged). */
    private int pvpPeer = -1;
    private boolean flip, pvpSpawned, pvpGaveUp;
    private long pvpStartNs;
    private int side(int ownerSide) { return flip && (ownerSide == 0 || ownerSide == 1) ? 1 - ownerSide : ownerSide; }
    private String lastDialog = "none", lastEngineFlags = "";
    private final Set<Integer> diagUnmatched = new HashSet<>();
    private final Map<MissileAPI, Integer> missileIds = new IdentityHashMap<>();
    /** Client: owner missile id -> our copy. */
    private final Map<Integer, MissileAPI> missileCopies = new HashMap<>();
    private int closeDeployTries;
    private ShipAPI lastExternal, originalPlayer;
    private float lastAimX, lastAimY;
    private long lastInputSentNs, lastDiagNs;
    private int pauseCount, sentInputs, appliedSnaps, lastSnapTick = -1, lastBits, removedLocal, projShown;

    @Override public void init(CombatEngineAPI engine) {
        this.engine = engine;
        CoopState.reset();
        GameState gs = null;
        try { gs = Global.getCurrentState(); } catch (Throwable ignored) {}
        titleScreen = gs == GameState.TITLE;
        if (titleScreen) return; // the title screen runs a background battle; leave it alone
        try { starmp.campaign.BackgroundSim.combatStarted(); } catch (Throwable ignored) {}
        keys = Bindings.load();
        NetManager n0 = StarMPPlugin.net();
        if (!CoopState.joiningBattle) CoopState.battleOwner = 0; // missions / Start Battle: the network host's battle
        if (CoopState.joiningBattle) {
            mirrorRole = true; CoopState.joiningBattle = false;
            flip = CoopState.pvpFlip; CoopState.pvpFlip = false;
            if (flip) StarMPPlugin.flog("combat: PvP - I fight on the owner's side 1 (shown as my side)");
            // the stand-in battle has nobody on either side at first: without this the engine declares it over at
            // once and leaves it ~20 s later
            engine.setDoNotEndCombat(true); doNotEndSet = true;
        }
        else mirrorRole = n0 != null && !n0.isHost() && !engine.isInCampaign(); // missions/Start Battle: the network host owns it
        if (!mirrorRole && CoopState.pvpEnemy >= 0) { // 0.9.2.5: my PvP battle - the other player's fleet joins as the enemy
            pvpPeer = CoopState.pvpEnemy; CoopState.pvpEnemy = -1; pvpStartNs = System.nanoTime();
            engine.setDoNotEndCombat(true); doNotEndSet = true; // the enemy side is empty until their fleet arrives
            // an empty enemy side counts as fleeing / beaten at once ("The enemy fleet is defeated") and both AIs stand
            // down - keep that side "in the fight" until the other player's ships are in
            try { for (boolean al : new boolean[]{false, true}) engine.getFleetManager(1).getTaskManager(al).setPreventFullRetreat(true); } catch (Throwable t) { StarMPPlugin.flog("pvp: prevent retreat failed: " + t); }
            StarMPPlugin.flog("combat: PvP battle vs player " + pvpPeer);
        }
        StarMPPlugin.flog("combat started; state=" + gs + " sim=" + engine.isSimulation() + " mission=" + engine.isMission()
                + " keys: " + keys.describe());
    }

    @Override public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || titleScreen) return;
        NetManager net = StarMPPlugin.net();
        if (net == null) return;
        starmp.ui.Background.frame(); // 0.1.5.9: keep running unfocused, capped
        try { starmp.Session.tick(net); } catch (Throwable t) { StarMPPlugin.flog("combat session: " + t); }
        try { starmp.campaign.CoopCampaign.combatKeepAlive(net); } catch (Throwable t) { StarMPPlugin.flog("combat keepalive: " + t); } // 0.9.3.3
        if (starmp.ui.Chat.typing() && engine.getPlayerShip() != null) // typing in the chat: keys don't fly the ship
            for (com.fs.starfarer.api.combat.ShipCommand c : com.fs.starfarer.api.combat.ShipCommand.values()) engine.getPlayerShip().blockCommandForOneFrame(c);
        // 0.1.5.3 debug log / performance
        try {
            starmp.diag.Perf.frame("combat");
            if (events != null) for (InputEventAPI e : events) if (!e.isConsumed() && e.isKeyDownEvent() && !e.isRepeat()) starmp.diag.Diag.keyEvent(e.getEventValue(), "combat");
            starmp.diag.Diag.pollKeys("combat");
            String note = starmp.diag.Diag.takeNote();
            if (note != null && engine.getCombatUI() != null) engine.getCombatUI().addMessage(1, java.awt.Color.CYAN, note);
        } catch (Throwable ignored) {}
        long perfT0 = starmp.diag.Perf.begin();
        try { advanceInner(net, amount, events); } finally { starmp.diag.Perf.end("Combat", perfT0); }
        try { combatDiag(net); } catch (Throwable t) { if (!diagErrLogged) { diagErrLogged = true; StarMPPlugin.flog("combat diag: " + StarMPPlugin.trace(t)); } }
    }

    private void advanceInner(NetManager net, float amount, List<InputEventAPI> events) {
        if (!net.isHost() && engine.isInCampaign()) { try { starmp.campaign.NpcSync.combatHoldTick(net); } catch (Throwable ignored) {} }
        // 0.1.4.11: the campaign scripts don't run while the host fights, so a guest (re)joining then got no game
        // screen and no save until the battle ended - serve them from here too
        if (net.isHost() && engine.isInCampaign()) { try { starmp.ui.Lobby.serviceHostReconnects(net); } catch (Throwable ignored) {} }
        try { starmp.campaign.BackgroundSim.combatTick(engine); } catch (Throwable t) { StarMPPlugin.flog("never-pause (combat) error: " + StarMPPlugin.trace(t)); }
        if (System.getenv("STARMP_TEST_CR_SPAWN") != null) { try { testCrSpawn(); } catch (Throwable t) { StarMPPlugin.flog("TEST cr spawn: " + StarMPPlugin.trace(t)); } }
        try {
            boolean linked = net.linked() && !net.others().isEmpty();
            boolean owner = linked && !mirrorRole;
            boolean client = linked && mirrorRole;
            if (owner) hostTick(net);
            else if (!assigned.isEmpty()) releaseAll("link stopped");
            if (client) clientTick(net, events);
            else if (tookOver) restoreClient();
        } catch (Throwable t) {
            StarMPPlugin.flog("combat tick error: " + StarMPPlugin.trace(t));
        }
    }

    private long crTestNs; private int crTestStep; private ShipAPI crOld, crNew;
    /** Test hook (STARMP_TEST_CR_SPAWN=1): spawn two copies of my first ship the way another player's ships are
     * brought in (fleet member outside any campaign fleet): one with the pre-0.1.5.8 handling (CR number only), one
     * with Ships.applyCR, and log their CR effects at spawn and 10 s later. */
    private void testCrSpawn() {
        long now = System.nanoTime();
        if (crTestNs == 0) { crTestNs = now; return; }
        if (crTestStep == 0 && now - crTestNs > 5_000_000_000L) {
            crTestStep = 1;
            FleetMemberAPI src = null;
            for (FleetMemberAPI m : engine.getFleetManager(0).getDeployedCopy()) { if (!m.isFighterWing()) { src = m; break; } }
            if (src == null) { crTestStep = 0; crTestNs = now; return; } // not deployed yet: try again in 5 s
            Wire.ShipDef d = Ships.def(9000, 0, src);
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            ShipAPI me = engine.getPlayerShip();
            float x = me == null ? 0 : me.getLocation().x, y = me == null ? 0 : me.getLocation().y - 400f;
            FleetMemberAPI m1 = Ships.member(d, "crtest_old"); m1.setAlly(true); fm.addToReserves(m1);
            crOld = fm.spawnFleetMember(m1, new Vector2f(x - 300, y), 90f, 0f);
            String spawnOld = Ships.crState(crOld);
            crOld.setCRAtDeployment(Math.max(0.01f, d.cr)); crOld.setCurrentCR(Math.max(0.01f, d.cr)); crOld.setControlsLocked(false); // pre-0.1.5.8
            FleetMemberAPI m2 = Ships.member(d, "crtest_new"); m2.setAlly(true); fm.addToReserves(m2);
            crNew = fm.spawnFleetMember(m2, new Vector2f(x + 300, y), 90f, 0f);
            String spawnNew = Ships.crState(crNew);
            Ships.applyCR(crNew, d.cr);
            StarMPPlugin.flog("TEST cr spawn: " + d.hullId + " real CR " + String.format("%.2f", d.cr) + " (source ship {" + Ships.crState(engine.getFleetManager(0).getShipFor(src)) + "})");
            StarMPPlugin.flog("TEST cr spawn: OLD handling: at spawn {" + spawnOld + "} after {" + Ships.crState(crOld) + "}");
            StarMPPlugin.flog("TEST cr spawn: NEW handling: at spawn {" + spawnNew + "} after {" + Ships.crState(crNew) + "}");
        } else if (crTestStep == 1 && now - crTestNs > 15_000_000_000L) {
            crTestStep = 2;
            StarMPPlugin.flog("TEST cr spawn: 10 s later OLD {" + Ships.crState(crOld) + "} NEW {" + Ships.crState(crNew) + "}");
        }
    }

    private static boolean tracked(ShipAPI s) { return !s.isFighter() && !s.isDrone() && !s.isShuttlePod(); }

    // ======================================================================= host
    private void hostTick(NetManager net) {
        if (!announced) { announced = true; StarMPPlugin.flog("combat: this side OWNS the battle (" + (net.isHost() ? "network host" : "network client") + ")"); }
        // everyone taking part: direct links, plus guests who reach this battle through the host
        List<Integer> peers = new ArrayList<>(net.others());
        long nowP = System.nanoTime();
        for (Map.Entry<Integer, Long> e : CoopState.inputRecvNs.entrySet()) if (nowP - e.getValue() < 6_000_000_000L && !peers.contains(e.getKey()) && e.getKey() != net.myId()) peers.add(e.getKey());
        for (Integer p : CoopState.joinRecvNs.keySet()) if (!peers.contains(p) && p != net.myId()) peers.add(p);
        spawnGuestFleets(peers);
        loadGate(net, peers);
        pvpGate(net);
        refreshGuestCR(nowP);
        // ships brought in by another player get an automatic retreat order from the engine (their members aren't in a
        // campaign fleet on this side); cancel it while the battle runs
        if (!engine.isCombatOver()) for (ShipAPI g : guestShips.keySet()) {
            if (g.isAlive() && !g.isHulk() && g.isRetreating() && !guestRetreats.contains(g)) {
                g.setRetreating(false, false); retreatCancels++;
                CombatFleetManagerAPI fm = engine.getFleetManager(g.getOwner() == 1 ? 1 : 0);
                DeployedFleetMemberAPI dm = fm.getDeployedFleetMember(g);
                if (retreatCancels <= 6 || retreatCancels % 100 == 0) {
                    String why = "?";
                    try {
                        com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo ai = fm.getTaskManager(true).getAssignmentFor(g);
                        why = "assignment=" + (ai == null ? "none" : ai.getType()) + " directRetreat=" + (dm != null && dm.isDirectRetreat())
                                + " cr=" + String.format("%.2f", g.getCurrentCR()) + " hp=" + Math.round(100 * g.getHullLevel()) + "% peakLeft=" + Math.round(g.getPeakTimeRemaining())
                                + " deployed=" + (dm != null) + " member=" + (g.getFleetMember() != null) + " inReserves=" + (g.getFleetMember() != null && fm.getReservesCopy().contains(g.getFleetMember()));
                    } catch (Throwable t) { why = "info failed: " + t; }
                    StarMPPlugin.flog("owner: guest ship " + g.getHullSpec().getHullId() + " was ordered to retreat (" + why + ") - cancelled (#" + retreatCancels + ")");
                }
                if (dm != null) {
                    try {
                        CombatTaskManagerAPI tm = fm.getTaskManager(g.getOwner() != 1);
                        CombatFleetManagerAPI.AssignmentInfo cur = tm.getAssignmentFor(g);
                        if (cur != null && cur.getType() == CombatAssignmentType.RETREAT) tm.removeAssignment(cur);
                        dm.setDirectRetreat(false);
                        tm.orderSearchAndDestroy(dm, false);
                    } catch (Throwable ignored) {}
                }
            }
        }
        // number every ship once
        for (ShipAPI s : engine.getShips()) {
            if (s.isDrone() || s.isShuttlePod() || ids.containsKey(s)) continue; // ships and fighters (drones belong to ship systems)
            int id = nextId++;
            ids.put(s, id); byId.put(id, s);
        }
        // loot split bookkeeping (campaign battles): who dealt and took how much damage
        if (engine.isInCampaign()) {
            if (!lootStarted) { lootStarted = true; starmp.campaign.LootSplit.battleStarted(net.myId()); }
            for (ShipAPI s : engine.getShips()) if (damageWatched.add(s)) { try { s.addListener(damageListener); } catch (Throwable ignored) {} }
        }
        // drop assignments for peers that left
        for (Iterator<Integer> it = assigned.keySet().iterator(); it.hasNext();) {
            Integer p = it.next();
            Long last = CoopState.inputRecvNs.get(p);
            boolean stale = last == null || System.nanoTime() - last > 6_000_000_000L;
            if (!peers.contains(p) || stale) {
                ShipAPI s = assigned.get(p); it.remove(); ais.remove(p); CoopState.inputs.remove(p);
                if (s.isAlive()) s.resetDefaultAI();
                StarMPPlugin.flog("released ship of peer " + p + " " + s.getName() + " back to AI");
            }
        }
        // a client switched to another ship of its own fleet
        for (Map.Entry<Integer, Wire.Input> e : CoopState.inputs.entrySet()) {
            int want = e.getValue().wantShip;
            if (want <= 0) continue;
            ShipAPI w = byId.get(want); int[] g = w == null ? null : guestShips.get(w);
            if (g == null || g[0] != e.getKey() || !w.isAlive() || w.isHulk() || guestFlagship.get(e.getKey()) == w) continue;
            guestFlagship.put(e.getKey(), w);
            StarMPPlugin.flog("owner: peer " + e.getKey() + " switched to " + w.getHullSpec().getHullId() + " (" + w.getName() + ")");
        }
        applyOrders();
        // assign ships
        for (int p : peers) {
            ShipAPI own = guestFlagship.get(p);
            if (own != null && own.isAlive() && assigned.get(p) != own) {
                ShipAPI old = assigned.remove(p);
                if (old != null && old.isAlive()) old.resetDefaultAI();
                ais.remove(p);
            }
            if (assigned.containsKey(p)) continue;
            Long last = CoopState.inputRecvNs.get(p);
            if (last == null || System.nanoTime() - last > 3_000_000_000L) continue; // only players who are actually in this battle
            ShipAPI pick = own != null && own.isAlive() ? own : pickShip(p);
            if (pick != null && guestShips.containsKey(pick)) guestFlagship.put(p, pick);
            if (pick == null) continue;
            RemoteShipAI ai = new RemoteShipAI(pick);
            pick.setShipAI(ai);
            assigned.put(p, pick); ais.put(p, ai);
            StarMPPlugin.flog("assigned " + pick.getHullSpec().getHullId() + " (" + pick.getName() + ", id " + ids.get(pick) + ") to peer " + p);
            engine.addFloatingText(pick.getLocation(), "Player " + p, 24f, java.awt.Color.CYAN, pick, 1f, 3f);
        }
        for (Map.Entry<Integer, RemoteShipAI> e : ais.entrySet()) e.getValue().setInput(CoopState.inputs.get(e.getKey()));
        applyRemoteTargets();
        // pause requests from clients (a changed counter = they pressed pause once)
        for (Map.Entry<Integer, Wire.Input> e : CoopState.inputs.entrySet()) {
            Integer seen = pauseSeen.get(e.getKey());
            int pc = e.getValue().pauseCount;
            if (seen == null) { pauseSeen.put(e.getKey(), pc); continue; }
            if (seen != pc) {
                pauseSeen.put(e.getKey(), pc);
                if (!starmp.Session.can(e.getKey(), starmp.Session.Perm.PAUSE)) { StarMPPlugin.flog("peer " + e.getKey() + " pause ignored (no permission)"); continue; } // 0.9.0.0
                engine.setPaused(!engine.isPaused());
                StarMPPlugin.flog("peer " + e.getKey() + " toggled pause -> paused=" + engine.isPaused());
            }
        }
        long nowNs = System.nanoTime();
        // campaign battles: tell clients so they can join (~1 Hz), and once more when it ends
        if (engine.isInCampaign()) {
            boolean over = engine.isCombatOver();
            if (over && !battleEndSent) { battleEndSent = true; sendResults(net); sendBattle(net, false); StarMPPlugin.flog("combat: campaign battle over, told clients"); }
            else if (!over && nowNs - lastBattleNs > 1_000_000_000L) { lastBattleNs = nowNs; sendBattle(net, true); }
        }
        // ship definitions: whenever new ships appeared, and every 3 s for late joiners
        if (ids.size() != defsSentCount || nowNs - lastDefsNs > 3_000_000_000L) {
            defsSentCount = ids.size(); lastDefsNs = nowNs;
            List<Wire.ShipDef> defList = buildDefs(net);
            net.sendAll(Wire.encodeDefs(defList));
            Map<Integer, String> names = new TreeMap<>();
            for (Wire.ShipDef d : defList) if (d.player >= 0 && !names.containsKey(d.player)) names.put(d.player, CoopState.nameOf(net, d.player));
            if (!names.isEmpty()) net.sendAll(Wire.encodeNames(names));
        }
        if (diagDue()) hostDiag();
        // snapshots + projectiles on the wall clock (keep flowing while paused)
        if (nowNs - lastSnapNs < (long) (SNAPSHOT_INTERVAL * 1e9)) return;
        lastSnapNs = nowNs;
        Wire.Snapshot snap = new Wire.Snapshot();
        snap.tick = ++tick;
        snap.paused = engine.isPaused();
        for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
            ShipAPI s = e.getKey();
            if (!engine.isEntityInPlay(s) && s.isAlive()) continue; // retreated / not deployed
            if (s.isFighter() && (!s.isAlive() || !engine.isEntityInPlay(s))) {
                Integer n = deadReports.merge(s, 1, Integer::sum);
                if (n > 20) { prune.add(s); continue; } // reported dead for ~1 s: forget it
            }
            Wire.ShipState st = new Wire.ShipState();
            st.id = e.getValue();
            st.x = s.getLocation().x; st.y = s.getLocation().y;
            st.vx = s.getVelocity().x; st.vy = s.getVelocity().y;
            st.facing = s.getFacing(); st.angVel = s.getAngularVelocity();
            st.hp = s.getHitpoints(); st.flux = s.getFluxTracker().getCurrFlux();
            st.alive = s.isAlive() && !s.isHulk();
            st.shieldOn = s.getShield() != null && s.getShield().isOn();
            st.venting = s.getFluxTracker().isVenting();
            st.phased = s.isPhased();
            if (st.alive) {
                List<WeaponAPI> ws = s.getAllWeapons();
                for (int w = 0; w < ws.size() && w < 255; w++) {
                    WeaponAPI wp = ws.get(w);
                    if (wp.isBeam() && (wp.isFiring() || wp.getChargeLevel() > 0f)) st.beams.add(new float[] { w, wp.getCurrAngle() });
                }
                if (assigned.containsValue(s)) { // a player flies it: their HUD needs cooldowns and ammo
                    int nw = Math.min(255, ws.size());
                    st.weaponCd = new float[nw]; st.weaponAmmo = new int[nw];
                    for (int w = 0; w < nw; w++) {
                        WeaponAPI wp = ws.get(w);
                        float cd = wp.getCooldown();
                        st.weaponCd[w] = cd > 0 ? wp.getCooldownRemaining() / cd : 0f;
                        st.weaponAmmo[w] = wp.usesAmmo() ? Math.min(32767, wp.getAmmo()) : -1;
                    }
                }
            }
            st.cr = s.getCurrentCR();
            if (s.getShield() != null) st.shieldFacing = s.getShield().getFacing();
            snap.ships.add(st);
        }
        for (Map.Entry<Integer, ShipAPI> a : assigned.entrySet()) {
            Integer id = ids.get(a.getValue());
            if (id != null) snap.assignments.put(a.getKey(), id);
        }
        net.sendAll(Wire.encodeSnapshot(snap));
        for (ShipAPI dead : prune) { Integer id = ids.remove(dead); if (id != null) byId.remove(id); deadReports.remove(dead); }
        if (!prune.isEmpty()) defsSentCount = -1; // resend defs without them
        prune.clear();
        // projectiles that appeared since the last snapshot
        List<Wire.ProjSpawn> spawns = new ArrayList<>();
        Set<DamagingProjectileAPI> now = Collections.newSetFromMap(new IdentityHashMap<DamagingProjectileAPI, Boolean>());
        for (DamagingProjectileAPI p : engine.getProjectiles()) {
            now.add(p);
            if (seenProj.contains(p) || p.getWeapon() == null || p.getSource() == null) continue;
            Integer sid = ids.get(p.getSource());
            if (sid == null) continue;
            Wire.ProjSpawn ps = new Wire.ProjSpawn();
            ps.sourceId = sid; ps.weaponId = p.getWeapon().getId();
            ps.x = p.getLocation().x; ps.y = p.getLocation().y; ps.angle = p.getFacing();
            ps.vx = p.getSource().getVelocity().x; ps.vy = p.getSource().getVelocity().y;
            if (p instanceof MissileAPI) { ps.missileId = ++missileCounter; missileIds.put((MissileAPI) p, ps.missileId); }
            spawns.add(ps);
            if (spawns.size() >= 300) break;
        }
        seenProj = now;
        if (!spawns.isEmpty()) { net.sendAll(Wire.encodeProj(spawns)); projSent += spawns.size(); }
        // positions of all live missiles we numbered (the joiner's copies follow them instead of steering themselves)
        List<Wire.MissileState> ms = new ArrayList<>();
        for (Iterator<Map.Entry<MissileAPI, Integer>> it = missileIds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MissileAPI, Integer> e = it.next();
            MissileAPI m = e.getKey();
            if (!engine.isEntityInPlay(m) || m.isExpired() || m.didDamage()) { it.remove(); continue; }
            Wire.MissileState st = new Wire.MissileState();
            st.id = e.getValue(); st.x = m.getLocation().x; st.y = m.getLocation().y; st.facing = m.getFacing();
            st.vx = m.getVelocity().x; st.vy = m.getVelocity().y;
            ms.add(st);
        }
        if (!ms.isEmpty() || hadMissiles) net.sendAll(Wire.encodeMissiles(ms, missileCounter));
        hadMissiles = !ms.isEmpty();
    }

    // ---- guests' own fleets
    private final Map<ShipAPI, int[]> guestShips = new IdentityHashMap<>(); // ship -> {peer, member index}
    private final Map<Integer, ShipAPI> guestFlagship = new HashMap<>();
    private final Set<Integer> guestSpawned = new HashSet<>();
    private int retreatCancels;
    /** How far inside the map edge joining ships appear (vanilla reinforcements arrive just inside the edge). */
    private static final float EDGE_IN = 700f;
    /** Guest ships their player ordered to retreat (the automatic retreat cancel leaves these alone). */
    private final Set<ShipAPI> guestRetreats = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final Map<Integer, String> ordersKey = new HashMap<>();
    private final Map<Integer, List<CombatFleetManagerAPI.AssignmentInfo>> ordersMade = new HashMap<>();
    private static final int FULL_RETREAT = 250;

    /** Owner: apply each client's tactical-map orders to that client's own ships (their CP was spent on their side). */
    private void applyOrders() {
        for (Map.Entry<Integer, List<Wire.Order>> e : CoopState.orders.entrySet()) {
            int peer = e.getKey();
            List<Wire.Order> list = e.getValue();
            StringBuilder k = new StringBuilder(); for (Wire.Order o : list) k.append(o.key()).append(';');
            if (k.toString().equals(ordersKey.get(peer))) continue;
            ordersKey.put(peer, k.toString());
            CombatFleetManagerAPI fm = engine.getFleetManager(peer == pvpPeer ? 1 : 0);
            CombatTaskManagerAPI tm = fm.getTaskManager(peer != pvpPeer);
            List<CombatFleetManagerAPI.AssignmentInfo> old = ordersMade.remove(peer);
            if (old != null) for (CombatFleetManagerAPI.AssignmentInfo a : old) { try { tm.removeAssignment(a); } catch (Throwable ignored) {} }
            for (Iterator<ShipAPI> it = guestRetreats.iterator(); it.hasNext(); ) { int[] g = guestShips.get(it.next()); if (g != null && g[0] == peer) it.remove(); }
            List<CombatFleetManagerAPI.AssignmentInfo> made = new ArrayList<>();
            int applied = 0;
            for (Wire.Order o : list) {
                try {
                    List<DeployedFleetMemberAPI> members = new ArrayList<>();
                    for (int id : o.ships) {
                        ShipAPI s = byId.get(id); int[] g = s == null ? null : guestShips.get(s);
                        if (g == null || g[0] != peer || !s.isAlive()) continue; // only that player's own ships
                        DeployedFleetMemberAPI dm = fm.getDeployedFleetMember(s);
                        if (dm != null) members.add(dm);
                    }
                    if (members.isEmpty()) continue;
                    if (o.type == FULL_RETREAT || o.type == CombatAssignmentType.RETREAT.ordinal()) {
                        for (DeployedFleetMemberAPI dm : members) { guestRetreats.add(dm.getShip()); tm.orderRetreat(dm, false, o.kind == 3); applied++; }
                        continue;
                    }
                    AssignmentTargetAPI target = null;
                    if (o.kind == 1) {
                        ShipAPI t = byId.get(o.targetShip);
                        if (t != null) target = engine.getFleetManager(t.getOwner()).getDeployedFleetMember(t);
                    } else if (o.kind == 2) {
                        float best = 400f * 400f;
                        for (BattleObjectiveAPI ob : engine.getObjectives()) {
                            float dx = ob.getLocation().x - o.x, dy = ob.getLocation().y - o.y;
                            if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; target = ob; }
                        }
                    }
                    if (target == null) target = tm.createWaypoint2(new Vector2f(o.x, o.y), true);
                    CombatAssignmentType type = CombatAssignmentType.values()[o.type];
                    CombatFleetManagerAPI.AssignmentInfo info = tm.createAssignment(type, target, false);
                    if (info == null) continue;
                    made.add(info);
                    for (DeployedFleetMemberAPI dm : members) { tm.giveAssignment(dm, info, false); applied++; }
                } catch (Throwable t) { StarMPPlugin.flog("owner: order from peer " + peer + " failed: " + t); }
            }
            ordersMade.put(peer, made);
            StarMPPlugin.flog("owner: applied " + list.size() + " order(s) from peer " + peer + " to " + applied + " ship(s): " + k);
        }
    }

    private long guestCrNs; private int guestCrFixes;
    /**
     * Every 2 s, re-apply the CR effects of other players' ships from their current CR. The engine works their CR
     * effects out from a fleet member with max CR 0; if it ever re-applies them (CR loss over time, peak time running
     * out) they would fall back to the 0%-CR penalties. Idempotent: the same effect id is overwritten.
     */
    private void refreshGuestCR(long now) {
        if (now - guestCrNs < 2_000_000_000L || guestShips.isEmpty()) return;
        guestCrNs = now;
        for (ShipAPI g : guestShips.keySet()) {
            if (!g.isAlive() || g.isHulk()) continue;
            String before = Ships.crState(g);
            try { Global.getSettings().getCRPlugin().applyCRToStats(g.getCurrentCR(), g.getMutableStats(), g.getHullSize()); } catch (Throwable ignored) {}
            String after = Ships.crState(g);
            if (!before.equals(after) && guestCrFixes++ < 10)
                StarMPPlugin.flog("owner: guest ship " + g.getHullSpec().getHullId() + " CR effects had drifted {" + before + "} -> {" + after + "}");
        }
    }

    private int targetLogs;
    /**
     * 0.1.6.3: another player's target lock. The joiner locks a target on its own screen, but the ship it flies is
     * simulated here, and until now that ship never had a target: autofire groups and guided missiles picked their
     * own targets ("target locking is wacky"). Now the flown ship gets the same target the player locked.
     */
    private void applyRemoteTargets() {
        for (Map.Entry<Integer, ShipAPI> e : assigned.entrySet()) {
            ShipAPI s = e.getValue();
            Wire.Input in = CoopState.inputs.get(e.getKey());
            if (s == null || !s.isAlive() || in == null) continue;
            ShipAPI t = in.targetShip > 0 ? byId.get(in.targetShip) : null;
            if (t != null && (!t.isAlive() || t.isHulk())) t = null;
            if (s.getShipTarget() == t) continue;
            s.setShipTarget(t);
            if (targetLogs++ < 20) StarMPPlugin.flog("owner: peer " + e.getKey() + "'s " + s.getHullSpec().getHullId() + " target -> " + (t == null ? "none" : t.getHullSpec().getHullId() + " (id " + in.targetShip + ")"));
        }
    }

    @SuppressWarnings("unchecked")
    private void spawnGuestFleets(List<Integer> peers) {
        for (Map.Entry<Integer, Object[]> e : CoopState.joinFleets.entrySet()) {
            int peer = e.getKey();
            if (guestSpawned.contains(peer) || !peers.contains(peer)) continue;
            // 0.9.2.5 PvP: wait until I have deployed, then bring them in at the edge opposite my ships
            if (peer == pvpPeer && myDeployed() == null) continue;
            guestSpawned.add(peer);
            int flag = (Integer) e.getValue()[0];
            List<Wire.ShipDef> defs = (List<Wire.ShipDef>) e.getValue()[1];
            float mapH = engine.getMapHeight();
            ShipAPI anchor = engine.getPlayerShip();
            final int sideG = peer == pvpPeer ? 1 : 0; // 0.9.2.5: the PvP enemy deploys on side 1, from the top edge
            // behind the owner's side: at that side's map edge (side 0 deploys from the bottom), lined up around
            // the owner's flagship x
            float bx = (anchor != null ? anchor.getLocation().x : 0) + 150f * (defs.size() - 1);
            ShipAPI dep = myDeployed();
            float ownEdge = (dep != null ? dep : anchor) != null && (dep != null ? dep : anchor).getLocation().y > 0 ? 1f : -1f; // which edge my side came in from
            float by = sideG == 1 ? -ownEdge * (mapH / 2f - EDGE_IN) : -mapH / 2f + EDGE_IN;
            if ("1".equals(System.getenv("STARMP_TEST_JOIN_NEAR")) && anchor != null) by = anchor.getLocation().y - 350f; // tests: right next to the owner

            int n = 0;
            for (Wire.ShipDef d : defs) {
                try {
                    FleetMemberAPI m = Ships.member(d, "guest" + peer + "_" + d.id);
                    if (sideG == 0) m.setAlly(true); // another player's ship: blue, and the owner can't command it
                    m.setOwner(sideG);                 // 0.9.2.5: the member's side decides the ship's side, not the fleet manager
                    float sx = Math.max(-engine.getMapWidth() / 2f + 800f, Math.min(engine.getMapWidth() / 2f - 800f, bx - 300 * n));
                    CombatFleetManagerAPI fm = engine.getFleetManager(sideG);
                    fm.addToReserves(m); // register like an own ship, so the engine doesn't treat it as a stray to send home
                    ShipAPI s = fm.spawnFleetMember(m, new Vector2f(sx, by), sideG == 1 ? (ownEdge > 0 ? 90f : 270f) : 90f, 0f);
                    if (s == null) continue;
                    if (sideG == 0 && !s.isAlly()) s.setAlly(true);
                    if (s.getOwner() != sideG) { StarMPPlugin.flog("owner: guest ship " + d.hullId + " spawned on side " + s.getOwner() + " - moved to side " + sideG); s.setOwner(sideG); }
                    DeployedFleetMemberAPI dm = fm.getDeployedFleetMember(s);
                    if (dm != null) { try { fm.getTaskManager(sideG == 0).orderSearchAndDestroy(dm, false); } catch (Throwable ignored) {} }
                    if (n == 0) StarMPPlugin.flog("owner: joined ship deployment record=" + (dm != null) + " inReserves=" + fm.getReservesCopy().contains(m)
                            + " ally=" + s.isAlly() + " dmAlly=" + (dm != null && dm.isAlly()) + " at " + Math.round(sx) + "," + Math.round(by));
                    s.setHitpoints(s.getMaxHitpoints() * Math.max(0.05f, d.hullFrac));
                    // the player's real CR, and its real CR effects (spawned at 0% CR: malfunctions, no system/shields)
                    String before = Ships.crState(s);
                    Ships.applyCR(s, d.cr);
                    StarMPPlugin.flog("owner: guest ship " + d.hullId + " spawned {" + before + "} -> {" + Ships.crState(s) + "} peakTimeLeft=" + Math.round(s.getPeakTimeRemaining()) + "s");
                    guestShips.put(s, new int[]{peer, d.id});
                    if (sideG == 0) starmp.campaign.LootSplit.addPlayer(peer);
                    if (d.id == flag) guestFlagship.put(peer, s);
                    n++;
                } catch (Throwable t) { StarMPPlugin.flog("host: could not bring guest ship " + d.hullId + ": " + t); }
            }
            if (sideG == 1) {
                pvpSpawned = true;
                CoopState.joinRecvNs.put(peer, System.nanoTime()); // their load time counts from now
                // the enemy side's commander gives the other player's AI ships no orders: send them in (the player's
                // own tactical-map orders still apply on top)
                try { engine.getFleetManager(1).getTaskManager(false).setFullAssault(true); } catch (Throwable t) { StarMPPlugin.flog("pvp: full assault failed: " + t); }
                StarMPPlugin.flog("owner: PvP enemy " + peer + " is in with " + n + " ships at y " + Math.round(by) + " (my side's edge " + (ownEdge > 0 ? "top" : "bottom") + ")");
            }
            StarMPPlugin.flog("host: brought " + n + " ships of peer " + peer + " into the battle" + (guestFlagship.containsKey(peer) ? " (flagship " + guestFlagship.get(peer).getName() + ")" : ""));
            NetManager nm = StarMPPlugin.net();
            engine.getCombatUI().addMessage(1, sideG == 1 ? java.awt.Color.RED : java.awt.Color.CYAN, (nm == null ? "Player " + peer : CoopState.nameOf(nm, peer)) + (sideG == 1 ? " is fighting you with " : " joined the battle with ") + n + " ships");
        }
    }

    /** A ship of mine that is actually deployed on the map (not just picked on the deployment screen). */
    private ShipAPI myDeployed() {
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            for (DeployedFleetMemberAPI d : fm.getDeployedCopyDFM()) {
                ShipAPI s = d.getShip();
                if (s != null && !s.isFighter() && !s.isAlly() && engine.isEntityInPlay(s) && !guestShips.containsKey(s)
                        && Math.abs(s.getLocation().y) > 300f) return s; // placed at its edge (right after deploying it can still sit at 0,0)
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---- 0.9.2.5 PvP: hold the battle until the other player's fleet is in; then let it end normally
    private long pvpMsgNs, pvpDiagNs;
    private void pvpGate(NetManager net) {
        if (pvpPeer < 0 || pvpGaveUp) return;
        long now = System.nanoTime();
        String who = CoopState.nameOf(net, pvpPeer);
        if (pvpSpawned && now - pvpDiagNs > 10_000_000_000L) {
            pvpDiagNs = now;
            try {
                CombatFleetManagerAPI f0 = engine.getFleetManager(0), f1 = engine.getFleetManager(1);
                StarMPPlugin.flog("owner: PvP diag over=" + engine.isCombatOver() + " enemyFullRetreat=" + engine.isEnemyInFullRetreat()
                        + " retreat0=" + f0.getTaskManager(false).isInFullRetreat() + " retreat1=" + f1.getTaskManager(false).isInFullRetreat()
                        + " deployed=" + f0.getDeployedCopy().size() + "/" + f1.getDeployedCopy().size() + " notOverFor=" + engine.getCombatNotOverFor()
                        + " admiral1=" + (f1.getAdmiralAI() == null ? "none" : f1.getAdmiralAI().getClass().getSimpleName()));
            } catch (Throwable t) { StarMPPlugin.flog("owner: PvP diag failed: " + t); }
        }
        if (pvpSpawned) {
            if (doNotEndSet && loaded.contains(pvpPeer)) {
                engine.setDoNotEndCombat(false); doNotEndSet = false;
                if (engine.isPaused() && !gateHolding) engine.setPaused(false);
                StarMPPlugin.flog("owner: PvP - " + who + " is in; the battle ends normally from now on");
            }
            return;
        }
        if (myDeployed() == null) { pvpStartNs = now; return; } // still on the deployment screen
        if (now - pvpStartNs > 60_000_000_000L) {
            pvpGaveUp = true;
            engine.setDoNotEndCombat(false); doNotEndSet = false;
            if (engine.isPaused()) engine.setPaused(false);
            StarMPPlugin.flog("owner: PvP - " + who + "'s fleet never arrived (60 s)");
            try { engine.getCombatUI().addMessage(1, java.awt.Color.ORANGE, who + "'s fleet never arrived - they got away."); } catch (Throwable ignored) {}
            return;
        }
        if (!engine.isPaused()) engine.setPaused(true);
        if (now - pvpMsgNs > 3_000_000_000L) {
            pvpMsgNs = now;
            try { engine.getCombatUI().addMessage(1, java.awt.Color.ORANGE, "Waiting for " + who + "'s fleet..."); } catch (Throwable ignored) {}
        }
    }

    // ---- load gate: hold the battle (paused for everyone) until each joining player has loaded in
    private static final long LOAD_TIMEOUT_NS = 45_000_000_000L;
    private final Set<Integer> loaded = new HashSet<>();
    private boolean gateHolding;
    private long lastGateMsgNs;

    private void loadGate(NetManager net, List<Integer> peers) {
        String waitingFor = null;
        long now = System.nanoTime();
        for (Map.Entry<Integer, Long> e : CoopState.joinRecvNs.entrySet()) {
            int p = e.getKey();
            if (loaded.contains(p) || !peers.contains(p)) continue;
            if (p == pvpPeer && !pvpSpawned) continue; // 0.9.2.5: the PvP enemy comes in after I deploy (pvpGate holds until then)
            Long in = CoopState.inputRecvNs.get(p);
            if (in != null && in > e.getValue()) { // their battle is up and sending input
                loaded.add(p);
                StarMPPlugin.flog("owner: peer " + p + " has loaded into the battle");
                continue;
            }
            if (now - e.getValue() > LOAD_TIMEOUT_NS) {
                loaded.add(p);
                StarMPPlugin.flog("owner: peer " + p + " did not load within 45 s; continuing without waiting");
                continue;
            }
            waitingFor = CoopState.nameOf(net, p);
        }
        if (waitingFor != null) {
            if (!gateHolding) { gateHolding = true; StarMPPlugin.flog("owner: holding the battle until " + waitingFor + " has loaded"); }
            if (!engine.isPaused()) engine.setPaused(true);
            if (now - lastGateMsgNs > 3_000_000_000L) {
                lastGateMsgNs = now;
                try { engine.getCombatUI().addMessage(1, java.awt.Color.CYAN, "Waiting for " + waitingFor + " to load into the battle..."); } catch (Throwable ignored) {}
            }
        } else if (gateHolding) {
            gateHolding = false;
            engine.setPaused(false);
            StarMPPlugin.flog("owner: everyone has loaded - battle continues");
            try { engine.getCombatUI().addMessage(1, java.awt.Color.CYAN, "Everyone is in - battle continues"); } catch (Throwable ignored) {}
        }
    }

    private void sendResults(NetManager net) {
        Map<Integer, List<Wire.Outcome>> byPeer = new HashMap<>();
        for (Map.Entry<ShipAPI, int[]> e : guestShips.entrySet()) {
            ShipAPI s = e.getKey();
            Wire.Outcome o = new Wire.Outcome();
            o.index = e.getValue()[1];
            o.destroyed = s.isHulk() || s.getHitpoints() <= 0f; // retreated ships are not alive in the engine but are not lost
            o.hullFrac = s.getMaxHitpoints() > 0 ? s.getHitpoints() / s.getMaxHitpoints() : 1f;
            o.cr = s.getCurrentCR();
            StarMPPlugin.flog("owner: result " + s.getHullSpec().getHullId() + " '" + s.getName() + "' alive=" + s.isAlive() + " hulk=" + s.isHulk()
                    + " hp=" + Math.round(s.getHitpoints()) + "/" + Math.round(s.getMaxHitpoints()) + " inPlay=" + engine.isEntityInPlay(s)
                    + " retreating=" + s.isRetreating() + " cr=" + String.format("%.2f", s.getCurrentCR()));
            byPeer.computeIfAbsent(e.getValue()[0], k -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<Integer, List<Wire.Outcome>> e : byPeer.entrySet()) {
            CoopState.sendTo(net, e.getKey(), Wire.encodeResult(e.getValue()));
            int lost = 0; for (Wire.Outcome o : e.getValue()) if (o.destroyed) lost++;
            StarMPPlugin.flog("host: sent battle result to peer " + e.getKey() + " (" + e.getValue().size() + " ships, " + lost + " lost)");
        }
        CoopState.joinFleets.clear();
        CoopState.joinRecvNs.clear();
        if (!guestShips.isEmpty()) starmp.campaign.LootSplit.battleEnded();
    }

    // ---- damage per player, for the loot split
    private boolean lootStarted;
    private final Set<ShipAPI> damageWatched = Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private final com.fs.starfarer.api.combat.listeners.DamageListener damageListener = (source, target, result) -> {
        try {
            if (!(target instanceof ShipAPI) || result == null) return;
            double amount = result.getDamageToHull() + result.getTotalDamageToArmor() + result.getDamageToShields();
            if (amount <= 0) return;
            NetManager n = StarMPPlugin.net();
            if (n == null) return;
            ShipAPI t = (ShipAPI) target;
            Integer victim = playerOf(t, n);
            if (pvpPeer >= 0) return; // 0.9.2.5: no loot split in PvP
            if (victim != null) { starmp.campaign.LootSplit.taken(victim, amount); return; }
            if (t.getOwner() != 1) return;
            ShipAPI src = source instanceof ShipAPI ? (ShipAPI) source
                    : source instanceof DamagingProjectileAPI ? ((DamagingProjectileAPI) source).getSource()
                    : source instanceof BeamAPI ? ((BeamAPI) source).getSource() : null;
            Integer dealer = src == null ? null : playerOf(src, n);
            if (dealer != null) starmp.campaign.LootSplit.dealt(dealer, amount);
        } catch (Throwable ignored) {}
    };

    /** Which player a ship (or its carrier / drone source) belongs to: a guest's peer id, our own id, or null. */
    private Integer playerOf(ShipAPI s, NetManager n) {
        for (int i = 0; i < 3 && s != null; i++) {
            if (s.isFighter() && s.getWing() != null && s.getWing().getSourceShip() != null && s.getWing().getSourceShip() != s) { s = s.getWing().getSourceShip(); continue; }
            if (s.isDrone() && s.getDroneSource() != null && s.getDroneSource() != s) { s = s.getDroneSource(); continue; }
            break;
        }
        if (s == null) return null;
        int[] g = guestShips.get(s);
        if (g != null) return g[0];
        for (Map.Entry<Integer, ShipAPI> a : assigned.entrySet()) if (a.getValue() == s) return a.getKey();
        if (s.getOwner() == 0 && !s.isAlly()) return n.myId();
        return null;
    }

    private void sendBattle(NetManager net, boolean active) {
        starmp.campaign.CampWire.Battle b = new starmp.campaign.CampWire.Battle();
        b.active = active; b.battleId = battleId; b.hostName = net.playerName();
        try {
            BattleCreationContext ctx = engine.getContext();
            if (ctx != null && ctx.getOtherFleet() != null) b.enemyName = ctx.getOtherFleet().getFullName();
        } catch (Throwable ignored) {}
        try {
            com.fs.starfarer.api.campaign.CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            if (pf != null && pf.getContainingLocation() != null) { b.loc = pf.getContainingLocation().getId(); b.x = pf.getLocation().x; b.y = pf.getLocation().y; }
        } catch (Throwable ignored) {}
        net.sendAll(starmp.campaign.CampWire.encode(b));
    }

    private final Map<ShipAPI, Integer> deadReports = new IdentityHashMap<>();
    private final List<ShipAPI> prune = new ArrayList<>();
    private final Map<Object, Integer> wingKeys = new IdentityHashMap<>();

    private List<Wire.ShipDef> buildDefs(NetManager net) {
        List<Wire.ShipDef> out = new ArrayList<>();
        for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
            ShipAPI s = e.getKey();
            if (s.isFighter()) { // fighters: wing type + which wing, the joiner spawns matching wings
                Wire.ShipDef d = new Wire.ShipDef();
                d.id = e.getValue(); d.owner = s.getOwner(); d.fighterLike = true;
                FighterWingAPI w = s.getWing();
                d.hullId = w != null && w.getWingId() != null ? w.getWingId() : s.getHullSpec().getHullId();
                int key = w == null ? -d.id : wingKeys.computeIfAbsent(w, k -> wingKeys.size() + 1);
                d.name = "wing:" + key;
                Integer pl = playerOf(s, net); d.player = pl == null ? -1 : pl; d.ally = s.isAlly();
                out.add(d);
                continue;
            }
            ShipVariantAPI v = s.getVariant();
            Wire.ShipDef d = new Wire.ShipDef();
            d.id = e.getValue(); d.owner = s.getOwner();
            d.hullId = s.getHullSpec().getHullId();
            d.name = s.getName() == null ? "" : s.getName();
            if (v != null) Ships.fill(d, v);
            Integer pl = playerOf(s, net); d.player = pl == null ? -1 : pl; d.ally = s.isAlly();
            out.add(d);
        }
        return out;
    }

    private int cpFixes;

    /** Client: the stand-in battle zeroes our command points with a negative flat mod; we give our own orders with
     *  our own points, so take it off (checked every frame in case the engine puts it back). */
    private void restoreCommandPoints() {
        try {
            MutableStat cp = engine.getFleetManager(0).getTaskManager(false).getCommandPointsStat();
            for (String k : new ArrayList<>(cp.getFlatMods().keySet())) {
                MutableStat.StatMod m = cp.getFlatStatMod(k);
                if (m == null || m.getValue() >= 0) continue;
                String before = cpInfo();
                cp.unmodifyFlat(k);
                if (cpFixes++ < 3) StarMPPlugin.flog("client: removed command point penalty " + k + " (" + m.getValue() + "): " + before + " -> " + cpInfo());
            }
        } catch (Throwable t) { if (cpFixes++ < 3) StarMPPlugin.flog("client: command points fix failed: " + t); }
    }

    private String cpInfo() {
        try {
            CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
            MutableStat st = tm.getCommandPointsStat();
            return tm.getCommandPointsLeft() + "/" + Math.round(st.getModifiedValue()) + "(base " + Math.round(st.getBaseValue()) + " flat " + st.getFlatMods().keySet() + ")";
        } catch (Throwable t) { return "?"; }
    }

    private String orderName(ShipAPI s) {
        try { CombatFleetManagerAPI.AssignmentInfo a = engine.getFleetManager(0).getTaskManager(true).getAssignmentFor(s); return a == null ? "none" : a.getType().name(); }
        catch (Throwable t) { return "?"; }
    }

    private void hostDiag() {
        ShipAPI ps = engine.getPlayerShip(); ViewportAPI vp = engine.getViewport();
        StringBuilder sb = new StringBuilder("owner diag: me@" + (ps == null ? "-" : Math.round(ps.getLocation().x) + "," + Math.round(ps.getLocation().y))
                + " view@" + (vp == null ? "-" : Math.round(vp.getCenter().x) + "," + Math.round(vp.getCenter().y) + " w" + Math.round(vp.getVisibleWidth())) + " paused=" + engine.isPaused() + " tick=" + tick + " ships=" + ids.size() + " projSent=" + projSent + " retreatCancels=" + retreatCancels + " labels/120f=" + lastLabelCount + " cp=" + cpInfo());
        for (Map.Entry<Integer, ShipAPI> a : assigned.entrySet()) {
            ShipAPI s = a.getValue(); Wire.Input in = CoopState.inputs.get(a.getKey());
            sb.append(" | peer ").append(a.getKey()).append(' ').append(s.getHullSpec().getHullId()).append(" id ").append(ids.get(s))
              .append(" pos=").append(Math.round(s.getLocation().x)).append(',').append(Math.round(s.getLocation().y))
              .append(" alive=").append(s.isAlive())
              .append(" input#").append(in == null ? "none" : in.seq + " bits=" + in.bits)
              .append(" weaponsFiring=").append(firingCount(s));
        }
        for (Map.Entry<ShipAPI, int[]> g : guestShips.entrySet()) {
            ShipAPI s = g.getKey();
            sb.append(" | guest ").append(s.getHullSpec().getHullId()).append(" @").append(Math.round(s.getLocation().x)).append(',').append(Math.round(s.getLocation().y))
              .append(" order=").append(orderName(s)).append(" alive=").append(s.isAlive()).append(" hp=").append(Math.round(s.getHitpoints()))
              .append(" cr=").append(String.format("%.2f", s.getCurrentCR()))
              .append(" inPlay=").append(engine.isEntityInPlay(s)).append(" retreating=").append(s.isRetreating());
        }
        StarMPPlugin.flog(sb.toString());
    }

    /** Host: give every remotely controlled ship back to the normal AI. */
    private void releaseAll(String why) {
        for (Map.Entry<Integer, ShipAPI> a : assigned.entrySet())
            if (a.getValue().isAlive()) a.getValue().resetDefaultAI();
        StarMPPlugin.flog("released " + assigned.size() + " remote ship(s) back to AI (" + why + ")");
        assigned.clear(); ais.clear(); CoopState.inputs.clear();
    }

    private ShipAPI pickShip(int peer) {
        ShipAPI player = engine.getPlayerShip();
        boolean hasOwn = false;
        for (Map.Entry<ShipAPI, int[]> e : guestShips.entrySet()) {
            if (e.getValue()[0] != peer) continue;
            hasOwn = true;
            ShipAPI s = e.getKey();
            if (s.isAlive() && !s.isHulk() && engine.isEntityInPlay(s) && !assigned.containsValue(s)) return s;
        }
        if (hasOwn) return null; // players fly only their own ships
        for (ShipAPI s : engine.getShips()) {
            if (s.getOwner() != 0 || !s.isAlive() || !tracked(s) || s.isHulk() || !engine.isEntityInPlay(s)) continue;
            if (s == player || assigned.containsValue(s)) continue;
            return s;
        }
        return null;
    }

    // ======================================================================= client
    private void clientTick(NetManager net, List<InputEventAPI> events) {
        if (!announced) { announced = true; StarMPPlugin.flog("combat: this side MIRRORS the other player's battle"); }
        if (originalPlayer == null) originalPlayer = engine.getPlayerShip();
        Wire.Snapshot snap = CoopState.latestSnapshot.get();
        if (snap == null || CoopState.defs.isEmpty()) {
            if (diagDue()) StarMPPlugin.flog("client diag: waiting for the host's battle (snapshot=" + (snap != null) + ", defs=" + CoopState.defs.size() + ")");
            return;
        }
        if (!tookOver) takeOver();
        boolean[] ef = starmp.ui.Vanilla.engineEndFlags();
        String efs = ef == null ? "n/a" : "over=" + ef[0] + " doNotEnd=" + ef[1];
        if (!efs.equals(lastEngineFlags)) {
            int dep0 = engine.getFleetManager(0).getDeployedCopy().size(), dep1 = engine.getFleetManager(1).getDeployedCopy().size();
            int res0 = engine.getFleetManager(0).getReservesCopy().size(), res1 = engine.getFleetManager(1).getReservesCopy().size();
            StarMPPlugin.flog("client: engine flags " + lastEngineFlags + " -> " + efs + " t=" + String.format("%.1f", engine.getTotalElapsedTime(false))
                    + " deployed=" + dep0 + "/" + dep1 + " reserves=" + res0 + "/" + res1 + " fullRetreat=" + engine.getFleetManager(0).getTaskManager(false).isInFullRetreat()
                    + "/" + engine.getFleetManager(1).getTaskManager(false).isInFullRetreat() + " notOverFor=" + engine.getCombatNotOverFor());
            lastEngineFlags = efs;
        }
        String dlg = starmp.ui.Vanilla.combatDialogType();
        if (!dlg.equals(lastDialog)) { StarMPPlugin.flog("client: combat dialog " + lastDialog + " -> " + dlg + " (over=" + engine.isCombatOver() + ")"); lastDialog = dlg; }
        if (!endingCombat && ef != null && ef[0] && !undoneOver) {
            undoneOver = true;
            engine.setDoNotEndCombat(true);
            StarMPPlugin.flog("client: the local stand-in battle had ended itself; undone=" + starmp.ui.Vanilla.undoCombatOver());
        }
        if (engine.isCombatOver() && !endingCombat && !clientOverLogged) {
            clientOverLogged = true;
            int[] side = new int[2]; for (ShipAPI x : engine.getShips()) if (x.getOwner() == 0 || x.getOwner() == 1) side[x.getOwner()]++;
            StarMPPlugin.flog("client: WARNING the local battle says it is over while the owner's still runs (ships side0=" + side[0] + " side1=" + side[1]
                    + " doNotEnd=" + doNotEndSet + " paused=" + engine.isPaused() + " lastSnapAgeMs=" + (System.nanoTime() - CoopState.lastSnapRecvNs) / 1_000_000 + ")");
        }
        // Starsector opens its deployment dialog for the stand-in battle; close it (nothing to deploy here)
        if (closeDeployTries < 20 && engine.getCombatUI() != null && engine.getCombatUI().isShowingDeploymentDialog()) {
            closeDeployTries++;
            boolean ok = starmp.ui.Vanilla.closeDeploymentDialog(1);
            StarMPPlugin.flog("client: deployment dialog " + (ok ? "closed automatically" : "could not be closed (try " + closeDeployTries + ")"));
        }
        // the host's battle ended (notice, or snapshots stopped): end ours too
        starmp.campaign.CampWire.Battle hb = starmp.campaign.CoopCampaign.battleOf(CoopState.battleOwner);
        boolean hostEnded = engine.isInCampaign() && hb != null && hb.owner == CoopState.battleOwner && !hb.active && System.nanoTime() - hb.recvNs < 10_000_000_000L;
        if (hostEnded || System.nanoTime() - CoopState.lastSnapRecvNs > 6_000_000_000L) {
            if (!endingCombat) {
                endingCombat = true;
                StarMPPlugin.flog("client: host battle ended (" + (hostEnded ? "notice" : "no snapshots") + "), ending ours");
                engine.setDoNotEndCombat(false);
                engine.endCombat(1.5f);
            }
            return;
        }
        if (snap.tick != lastSnapTick) { lastSnapTick = snap.tick; appliedSnaps++; }

        // shared pause: the host decides; a local pause key press is sent to the host as a request
        if (events != null && keys.pause > 0 && keys.pause < 2000) for (InputEventAPI ev : events) {
            if (!ev.isConsumed() && ev.isKeyDownEvent() && ev.getEventValue() == keys.pause && !ev.isRepeat()) {
                if (!starmp.Session.myCan(starmp.Session.Perm.PAUSE)) { // 0.9.0.0
                    engine.getCombatUI().addMessage(0, java.awt.Color.ORANGE, starmp.Session.onlyWho(starmp.Session.current().pausePerm) + " can pause");
                    continue;
                }
                pauseCount = (pauseCount + 1) & 0xFF;
                StarMPPlugin.flog("client: pause request sent");
            }
        }
        // quick taps (shield toggle, vent, system) can fall between two input packets: remember them until sent
        if (events != null) for (InputEventAPI ev : events) {
            if (ev.isConsumed() || ev.isRepeat()) continue; // 0.9.3.7: consumed first (isRepeat() on a consumed event throws and aborted the whole tick)
            int code = ev.isKeyDownEvent() ? ev.getEventValue() : ev.isMouseDownEvent() ? 2000 + ev.getEventValue() : -1;
            if (code < 0) continue;
            if (code == keys.shield) tapLatch |= Wire.B_SHIELD;
            if (code == keys.vent) tapLatch |= Wire.B_VENT;
            if (code == keys.system) tapLatch |= Wire.B_SYSTEM;
        }
        if (engine.isPaused() != snap.paused) engine.setPaused(snap.paused);

        // keep only the host's battle: remove ships the local battle spawns by itself
        for (ShipAPI s : new ArrayList<>(engine.getShips())) {
            if (mirrorSet.contains(s)) continue;
            if (s.isDrone()) {
                ShipAPI src = s.getDroneSource();
                if (src != null && mirrorSet.contains(src)) { makeInvulnerable(s); continue; } // drones of mirrored ships stay (local only)
            }
            engine.removeEntity(s); removedLocal++; // includes fighters our own carriers launch: we show the owner's
        }
        // spawn any ship the host has that we don't
        for (Wire.ShipDef d : CoopState.defs.values()) if (!mirrors.containsKey(d.id)) { if (d.fighterLike) spawnFighter(d, snap); else spawnMirror(d, snap); }
        dropUnusedFighters();

        int matched = 0;
        boolean freshSnap = snap.tick != lastPosTick;
        lastPosTick = snap.tick;
        for (Wire.ShipState st : snap.ships) {
            ShipAPI s = mirrors.get(st.id);
            if (s == null) {
                if (st.alive && diagUnmatched.add(st.id)) { Wire.ShipDef d = CoopState.defs.get(st.id); StarMPPlugin.flog("client: owner ship " + st.id + " not shown (alive=" + st.alive + " def=" + (d == null ? "none" : d.hullId + (d.fighterLike ? " fighter" : "")) + " tried=" + mirrors.containsKey(st.id) + ")"); }
                continue;
            }
            matched++;
            if (!st.alive) {
                if (s.isAlive()) { makeVulnerable(s); engine.applyDamage(s, s.getLocation(), 1e7f, DamageType.HIGH_EXPLOSIVE, 0f, true, false, null); }
                continue;
            }
            if (freshSnap) {
                if (s == lastExternal) {
                    // the ship we fly: our own game already moves it from our keys (instant response); pull it
                    // gently toward where the owner has it instead of snapping back every frame
                    float ex = st.x - s.getLocation().x, ey = st.y - s.getLocation().y;
                    if (ex * ex + ey * ey > 300f * 300f) { s.getLocation().set(st.x, st.y); s.getVelocity().set(st.vx, st.vy); s.setFacing(st.facing); }
                    else {
                        s.getLocation().set(s.getLocation().x + ex * 0.3f, s.getLocation().y + ey * 0.3f);
                        s.getVelocity().set(s.getVelocity().x + (st.vx - s.getVelocity().x) * 0.5f, s.getVelocity().y + (st.vy - s.getVelocity().y) * 0.5f);
                        float df = st.facing - s.getFacing();
                        while (df > 180f) df -= 360f; while (df < -180f) df += 360f;
                        s.setFacing(Math.abs(df) > 45f ? st.facing : s.getFacing() + df * 0.3f);
                    }
                    s.setAngularVelocity(s.getAngularVelocity() + (st.angVel - s.getAngularVelocity()) * 0.5f);
                    applyWeaponState(s, st);
                    if (st.cr > 0f && s.controlsLocked()) { s.setControlsLocked(false); if (unlockLogs++ < 3) StarMPPlugin.flog("client: cleared 'controls locked' on our ship (cr " + st.cr + ")"); }
                } else {
                    // other ships: set on each new snapshot, then the local physics carries them along their
                    // velocity until the next one (no freezing in place between snapshots)
                    s.getLocation().set(st.x, st.y);
                    s.getVelocity().set(st.vx, st.vy);
                    s.setFacing(st.facing);
                    s.setAngularVelocity(st.angVel);
                }
                if (Math.abs(s.getCurrentCR() - st.cr) > 0.005f) { s.setCurrentCR(st.cr); }
            }
            s.setHitpoints(Math.max(1f, st.hp));
            s.getFluxTracker().setCurrFlux(st.flux);
            ShieldAPI sh = s.getShield();
            if (sh != null && sh.isOn() != st.shieldOn) {
                // our own ship raises its shield locally at once; only correct it if the owner still disagrees a moment later
                boolean apply = s != lastExternal;
                if (!apply) { if (shieldMismatchNs == 0) shieldMismatchNs = System.nanoTime(); apply = System.nanoTime() - shieldMismatchNs > 400_000_000L; }
                if (apply) { if (st.shieldOn) sh.toggleOn(); else sh.toggleOff(); shieldMismatchNs = 0; }
            } else if (s == lastExternal) shieldMismatchNs = 0;
            if (sh != null && s != lastExternal && sh.isOn()) sh.forceFacing(st.shieldFacing);
            mirrorWeapons(s, st);
        }
        // shots: remove everything the local game fired, create the host's
        for (DamagingProjectileAPI p : new ArrayList<>(engine.getProjectiles())) {
            if (!mirroredProj.contains(p)) engine.removeEntity(p);
        }
        mirroredProj.retainAll(new HashSet<CombatEntityAPI>(engine.getProjectiles()));
        Wire.ProjSpawn ps;
        while ((ps = CoopState.projQueue.poll()) != null) {
            ShipAPI src = mirrors.get(ps.sourceId);
            if (src == null) continue;
            try {
                CombatEntityAPI e = engine.spawnProjectile(src, null, ps.weaponId, new Vector2f(ps.x, ps.y), ps.angle, new Vector2f(ps.vx, ps.vy));
                if (e != null) {
                    mirroredProj.add(e); projShown++; diagWpn.put(e, ps.weaponId);
                    if (e instanceof DamagingProjectileAPI) ((DamagingProjectileAPI) e).setDamageAmount(0f);
                    if (ps.missileId > 0 && e instanceof MissileAPI) { MissileAPI mc = (MissileAPI) e; mc.setMissileAI(new HeldMissileAI()); missileCopies.put(ps.missileId, mc); }
                }
            } catch (Throwable t) { /* unknown weapon id etc. */ }
        }
        followMissiles();
        // take control of our assigned ship
        Integer myId = snap.assignments.get(net.myId());
        if (myId != null) {
            ShipAPI mine = mirrors.get(myId);
            if (mine != null && mine != lastExternal && mine.isAlive()) {
                engine.setPlayerShipExternal(mine);
                lastExternal = mine;
                StarMPPlugin.flog("client controls " + mine.getHullSpec().getHullId() + " (" + mine.getName() + ", id " + myId + ")");
            }
        }
        // switched ship on our side (tactical map / transfer command): only to our own ships; ask the owner
        ShipAPI cur = engine.getPlayerShip();
        if (lastExternal != null && cur != null && cur != lastExternal && mirrorSet.contains(cur)) {
            Object mid = mirrorId.get(cur);
            Wire.ShipDef cd = mid instanceof Integer ? CoopState.defs.get((Integer) mid) : null;
            if (cd != null && cd.player == net.myId() && !cd.fighterLike && cur.isAlive()) {
                if (wantShip != cd.id) { wantShip = cd.id; StarMPPlugin.flog("client: switching to own ship " + cd.hullId + " (id " + cd.id + ")"); }
            } else if (lastExternal.isAlive()) engine.setPlayerShipExternal(lastExternal); // not ours: back to our ship
        }
        if (myId != null && myId == wantShip) wantShip = 0;
        restoreCommandPoints();
        clientTestHooks(net);
        sendOrders(net);
        // send our input (wall clock, so it keeps flowing while the local game is paused)
        long now = System.nanoTime();
        if (now - lastInputSentNs >= (long) (INPUT_INTERVAL * 1e9)) { // also before we have a ship: tells the owner we are here
            lastInputSentNs = now;
            Wire.Input in = readInput();
            CoopState.sendTo(net, CoopState.battleOwner, Wire.encodeInput(in));
            sentInputs++; diagLastInput = in;
            lastBits = in.bits;
        }
        if (diagDue()) {
            ShipAPI m = lastExternal;
            StarMPPlugin.flog("client diag: paused=" + engine.isPaused() + " snapTick=" + snap.tick + " snapsApplied=" + appliedSnaps
                    + " labels/120f=" + lastLabelCount + " cp=" + cpInfo() + " t=" + String.format("%.1f", engine.getTotalElapsedTime(false)) + " matched=" + matched + "/" + snap.ships.size() + " mirrors=" + mirrors.size() + " removedLocal=" + removedLocal
                    + " projShown=" + projShown + " fighters=" + fightersShown + " beamFrames=" + beamsShown + " missileFrames=" + missilesSteered + " inputsSent=" + sentInputs + " lastBits=" + lastBits
                    + (m == null ? " (no ship assigned)" : " my " + m.getHullSpec().getHullId() + " pos="
                    + Math.round(m.getLocation().x) + "," + Math.round(m.getLocation().y)
                    + " cr=" + String.format("%.2f", m.getCurrentCR()) + " flameouts=" + m.getNumFlameouts() + " engineOut=" + m.getEngineController().isFlamedOut()
                    + " shield=" + (m.getShield() == null ? "none" : m.getShield().isOn()) + " ppt=" + Math.round(m.getPeakTimeRemaining())));
        }
    }

    private int lastPosTick = -1, tapLatch;
    private long shieldMismatchNs;
    private int unlockLogs;

    /** Client: cooldowns and ammo of the ship we fly, so the weapon panel shows them. */
    private static void applyWeaponState(ShipAPI s, Wire.ShipState st) {
        if (st.weaponCd == null) return;
        List<WeaponAPI> ws = s.getAllWeapons();
        for (int w = 0; w < st.weaponCd.length && w < ws.size(); w++) {
            WeaponAPI wp = ws.get(w);
            try {
                wp.setRemainingCooldownTo(st.weaponCd[w] * wp.getCooldown());
                if (st.weaponAmmo[w] >= 0 && wp.usesAmmo() && wp.getAmmo() != st.weaponAmmo[w]) wp.setAmmo(st.weaponAmmo[w]);
            } catch (Throwable ignored) {}
        }
    }

    /** Client: the mirror fires exactly the beams the owner's ship is firing, at the owner's aim; nothing else fires
     *  locally (the owner's projectiles arrive separately as PROJ). */
    private void mirrorWeapons(ShipAPI s, Wire.ShipState st) {
        List<WeaponAPI> ws = s.getAllWeapons();
        boolean[] on = new boolean[ws.size()];
        for (float[] b : st.beams) {
            int w = (int) b[0];
            if (w < 0 || w >= ws.size() || !ws.get(w).isBeam()) continue;
            on[w] = true;
            ws.get(w).setCurrAngle(b[1]);
            ws.get(w).setForceFireOneFrame(true);
            beamsShown++;
        }
        for (int w = 0; w < ws.size(); w++) if (!on[w]) ws.get(w).setForceNoFireOneFrame(true);
    }

    /** Client: move our missile copies to where the owner's missiles are; copies whose missile is gone explode. */
    private void followMissiles() {
        List<Wire.MissileState> ms = CoopState.latestMissiles;
        if (ms == null) return;
        Set<Integer> live = new HashSet<>();
        int maxAssigned = 0;
        for (Wire.MissileState st : ms) {
            if (st.id <= 0) { maxAssigned = -st.id; continue; }
            live.add(st.id);
            MissileAPI m = missileCopies.get(st.id);
            if (m == null || !engine.isEntityInPlay(m)) continue;
            m.getLocation().set(st.x, st.y); m.getVelocity().set(st.vx, st.vy); m.setFacing(st.facing);
            missilesSteered++;
        }
        for (Iterator<Map.Entry<Integer, MissileAPI>> it = missileCopies.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, MissileAPI> e = it.next();
            MissileAPI m = e.getValue();
            if (!engine.isEntityInPlay(m)) { it.remove(); continue; }
            // gone on the owner's side (hit something or expired): end ours there too, once that packet is newer than the missile
            if (!live.contains(e.getKey()) && e.getKey() <= maxAssigned) { try { m.explode(); } catch (Throwable t) { engine.removeEntity(m); } it.remove(); }
        }
    }

    /** Missile AI that does nothing: the copy's position comes from the owner. */
    private static final class HeldMissileAI implements MissileAIPlugin { public void advance(float amount) {} }

    private int wantShip, ownLogged;
    private String lastOrdersKey = "";
    private long lastOrdersNs, lastOrdersCheckNs;

    /** Client: our tactical-map orders for our own ships, to the owner (on change, and every 2 s). */
    private void sendOrders(NetManager net) {
        long now = System.nanoTime();
        if (now - lastOrdersCheckNs < 250_000_000L) return;
        lastOrdersCheckNs = now;
        CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
        List<Wire.Order> list = new ArrayList<>();
        try {
            for (CombatFleetManagerAPI.AssignmentInfo a : tm.getAllAssignments()) {
                Wire.Order o = new Wire.Order();
                for (DeployedFleetMemberAPI dm : a.getAssignedMembers()) {
                    ShipAPI s = dm.getShip();
                    Object id = s == null ? null : mirrorId.get(s);
                    Wire.ShipDef d = id instanceof Integer ? CoopState.defs.get((Integer) id) : null;
                    if (d != null && d.player == net.myId()) o.ships.add(d.id);
                }
                if (o.ships.isEmpty()) continue;
                o.type = a.getType().ordinal();
                AssignmentTargetAPI t = a.getTarget();
                if (t != null && t.getLocation() != null) { o.x = t.getLocation().x; o.y = t.getLocation().y; }
                if (t instanceof DeployedFleetMemberAPI && ((DeployedFleetMemberAPI) t).getShip() != null) {
                    Object id = mirrorId.get(((DeployedFleetMemberAPI) t).getShip());
                    if (id instanceof Integer && (Integer) id > 0) { o.kind = 1; o.targetShip = (Integer) id; }
                } else if (t instanceof BattleObjectiveAPI) o.kind = 2;
                list.add(o);
            }
            // retreat orders are direct orders, not assignments: read them per ship
            CombatFleetManagerAPI fm0 = engine.getFleetManager(0);
            Wire.Order ret = new Wire.Order(), dret = new Wire.Order();
            ret.type = CombatAssignmentType.RETREAT.ordinal(); dret.type = ret.type; dret.kind = 3;
            for (ShipAPI s : mirrorSet) {
                Integer id = mirrorId.get(s);
                Wire.ShipDef d = id == null ? null : CoopState.defs.get(id);
                if (d == null || d.player != net.myId() || d.fighterLike || !s.isAlive()) continue;
                DeployedFleetMemberAPI dm = fm0.getDeployedFleetMember(s);
                CombatFleetManagerAPI.AssignmentInfo a = tm.getAssignmentFor(s);
                // (isDirectRetreat() is true on our copies even without any order, so it can't tell the two retreat kinds apart)
                boolean retreating = a != null && a.getType() == CombatAssignmentType.RETREAT;
                String sig = d.hullId + ":" + (a == null ? "-" : a.getType()) + "/" + s.isRetreating() + "/" + (dm != null && dm.isDirectRetreat());
                if (!sig.equals(retreatSig.get(id))) { retreatSig.put(id, sig); StarMPPlugin.flog("client: own ship order state " + sig); }
                if (!retreating) continue;
                ret.ships.add(id);
            }
            if (!ret.ships.isEmpty()) list.add(ret);
            if (!dret.ships.isEmpty()) list.add(dret);
            if (tm.isInFullRetreat()) {
                Wire.Order o = new Wire.Order(); o.type = 250;
                for (Wire.ShipDef d : CoopState.defs.values()) if (d.player == net.myId() && !d.fighterLike) o.ships.add(d.id);
                list.add(o);
            }
        } catch (Throwable t) { if (ordersErr++ < 3) StarMPPlugin.flog("client: reading orders failed: " + t); }
        StringBuilder k = new StringBuilder(); for (Wire.Order o : list) k.append(o.key()).append(';');
        boolean changed = !k.toString().equals(lastOrdersKey);
        if (!changed && now - lastOrdersNs < 2_000_000_000L) return;
        lastOrdersNs = now;
        if (changed) StarMPPlugin.flog("client: orders now " + k);
        lastOrdersKey = k.toString();
        CoopState.sendTo(net, CoopState.battleOwner, Wire.encodeOrders(list));
    }
    private int ordersErr;
    private final Map<Integer, String> retreatSig = new HashMap<>();
    private boolean testOrderDone, testSwitchDone, testDiagDone;
    private long tookOverNs;
    private ShipAPI testOrdered;

    /** STARMP_TEST_ORDER=1: 10 s in, order one of our non-flagship ships to a point; STARMP_TEST_SWITCH=1: 20 s in, switch ship. */
    private void clientTestHooks(NetManager net) {
        float t = (System.nanoTime() - tookOverNs) / 1e9f;
        if (!testDiagDone && t > 8f) {
            testDiagDone = true;
            StringBuilder sb = new StringBuilder("client test hooks: order=" + System.getenv("STARMP_TEST_ORDER") + " switch=" + System.getenv("STARMP_TEST_SWITCH") + " me=" + net.myId() + " ships:");
            for (ShipAPI s : mirrorSet) {
                if (s.isFighter()) continue;
                Object id = mirrorId.get(s);
                Wire.ShipDef d = id instanceof Integer ? CoopState.defs.get((Integer) id) : null;
                sb.append(' ').append(s.getHullSpec().getHullId()).append("/id=").append(id).append("/player=").append(d == null ? "nodef" : d.player).append("/ally=").append(s.isAlly()).append("/alive=").append(s.isAlive());
            }
            StarMPPlugin.flog(sb.toString());
        }
        if (!testOrderDone && "1".equals(System.getenv("STARMP_TEST_ORDER")) && t > 10f) {
            testOrderDone = true;
            try {
                CombatFleetManagerAPI fm = engine.getFleetManager(0);
                CombatTaskManagerAPI tm = fm.getTaskManager(false);
                for (ShipAPI s : mirrorSet) {
                    Object id = mirrorId.get(s);
                    Wire.ShipDef d = id instanceof Integer ? CoopState.defs.get((Integer) id) : null;
                    if (d == null || d.player != net.myId() || d.fighterLike || s == lastExternal || !s.isAlive()) continue;
                    DeployedFleetMemberAPI dm = fm.getDeployedFleetMember(s);
                    if (dm == null) continue;
                    AssignmentTargetAPI wp = tm.createWaypoint2(new Vector2f(-3000f, s.getLocation().y + 1500f), false);
                    CombatFleetManagerAPI.AssignmentInfo a = tm.createAssignment(CombatAssignmentType.RALLY_TASK_FORCE, wp, false);
                    tm.giveAssignment(dm, a, false);
                    testOrdered = s;
                    StarMPPlugin.flog("client test: ordered " + d.hullId + " (id " + d.id + ") to rally at -3000," + Math.round(s.getLocation().y + 1500f)
                            + " wp=" + wp.getClass().getSimpleName() + "@" + wp.getLocation() + " target=" + (a == null || a.getTarget() == null ? "null" : a.getTarget().getClass().getSimpleName() + "@" + a.getTarget().getLocation()));
                    break;
                }
            } catch (Throwable x) { StarMPPlugin.flog("client test order failed: " + x); }
        }
        if (!testSwitchDone && "1".equals(System.getenv("STARMP_TEST_SWITCH")) && t > 20f && lastExternal != null) {
            testSwitchDone = true;
            for (ShipAPI s : mirrorSet) {
                Object id = mirrorId.get(s);
                Wire.ShipDef d = id instanceof Integer ? CoopState.defs.get((Integer) id) : null;
                if (d == null || d.player != net.myId() || d.fighterLike || s == lastExternal || s == testOrdered || !s.isAlive()) continue;
                engine.setPlayerShipExternal(s);
                StarMPPlugin.flog("client test: switched to " + d.hullId + " (id " + d.id + ")");
                break;
            }
        }
    }

    private void takeOver() {
        tookOver = true; tookOverNs = System.nanoTime();
        restoreCommandPoints();
        engine.setDoNotEndCombat(true); doNotEndSet = true;
        for (int side = 0; side < 2; side++) engine.getFleetManager(side).setSuppressDeploymentMessages(true); // no "Incoming vessels" spam from mirrors
        int n = 0;
        for (ShipAPI s : new ArrayList<>(engine.getShips())) { engine.removeEntity(s); n++; }
        StarMPPlugin.flog("client: took over the battle (removed " + n + " local ships); rebuilding the host's " + CoopState.defs.size() + " ships");
    }

    private void spawnMirror(Wire.ShipDef d, Wire.Snapshot snap) {
        Wire.ShipState st = null;
        for (Wire.ShipState x : snap.ships) if (x.id == d.id) { st = x; break; }
        if (st == null || !st.alive) return; // not deployed yet / already dead on the host
        try {
            FleetMemberAPI m = Ships.member(d, "starmp_" + d.id);
            NetManager net = StarMPPlugin.net();
            boolean mine = net != null && d.player == net.myId();
            int ls = side(d.owner);
            boolean ally = ls == 0 && !mine && (d.player >= 0 || d.ally);
            if (ally) m.setAlly(true);
            CombatFleetManagerAPI fm = engine.getFleetManager(ls);
            if (mine) fm.addToReserves(m); // a deployment record, so our tactical map can give it orders
            ShipAPI s = fm.spawnFleetMember(m, new Vector2f(st.x, st.y), st.facing, 0f);
            if (s == null) return;
            if (ally && !s.isAlly()) s.setAlly(true);
            if (mine && ownLogged++ < 2) { DeployedFleetMemberAPI dm = fm.getDeployedFleetMember(s); StarMPPlugin.flog("client: own ship " + d.hullId + " record=" + (dm != null) + " orders=" + (dm != null && dm.canBeGivenOrders())); }
            s.setShipAI(new IdleShipAI());
            // real CR and CR effects (the member is outside a campaign fleet, so it spawned at 0% CR); also clears the
            // cached "controls locked", or the joiner sees "Ship is not combat-ready, controls are locked"
            Ships.applyCR(s, d.cr);
            makeInvulnerable(s);
            mirrorId.put(s, d.id);
            mirrors.put(d.id, s); mirrorSet.add(s);
        } catch (Throwable t) {
            StarMPPlugin.flog("client: could not rebuild ship " + d.id + " (" + d.hullId + "): " + t);
            mirrors.put(d.id, null); // don't retry every frame
        }
    }

    /** Spawned-but-unused wing members, per owner wing key, waiting to be matched to the owner's fighters. */
    private final Map<String, LinkedList<ShipAPI>> spareFighters = new HashMap<>();

    private void spawnFighter(Wire.ShipDef d, Wire.Snapshot snap) {
        Wire.ShipState st = null;
        for (Wire.ShipState x : snap.ships) if (x.id == d.id) { st = x; break; }
        if (st == null || !st.alive) return;
        String key = d.owner + "|" + d.player + "|" + d.name;
        LinkedList<ShipAPI> spare = spareFighters.computeIfAbsent(key, k -> new LinkedList<>());
        while (!spare.isEmpty() && !spare.peekFirst().isAlive()) spare.removeFirst();
        if (spare.isEmpty()) {
            try {
                ShipAPI lead = engine.getFleetManager(side(d.owner)).spawnShipOrWing(d.hullId, new Vector2f(st.x, st.y), st.facing, 0f);
                if (lead == null) { mirrors.put(d.id, null); return; }
                if (lead.getWing() != null) spare.addAll(lead.getWing().getWingMembers()); else spare.add(lead);
                NetManager net = StarMPPlugin.net();
                boolean ally = side(d.owner) == 0 && (net == null || d.player != net.myId()) && (d.player >= 0 || d.ally);
                for (ShipAPI f : spare) {
                    if (ally) f.setAlly(true); f.setShipAI(new IdleShipAI()); makeInvulnerable(f); mirrorId.put(f, -1); mirrorSet.add(f); }
            } catch (Throwable t) {
                StarMPPlugin.flog("client: could not show fighter wing " + d.hullId + ": " + t);
                mirrors.put(d.id, null);
                return;
            }
        }
        ShipAPI f = spare.removeFirst();
        mirrorId.put(f, d.id);
        mirrors.put(d.id, f);
        fightersShown++;
    }

    /** Client: wing members we spawned that no owner fighter uses (wings spawn whole). */
    private void dropUnusedFighters() {
        for (LinkedList<ShipAPI> l : spareFighters.values()) {
            for (ShipAPI f : l) if (f.isAlive() && engine.isEntityInPlay(f) && Integer.valueOf(-1).equals(mirrorId.get(f))) { mirrorSet.remove(f); engine.removeEntity(f); }
            l.clear();
        }
    }

    private static void makeInvulnerable(ShipAPI s) {
        MutableShipStatsAPI st = s.getMutableStats();
        st.getHullDamageTakenMult().modifyMult(MIRROR, 0f);
        st.getArmorDamageTakenMult().modifyMult(MIRROR, 0f);
        st.getShieldDamageTakenMult().modifyMult(MIRROR, 0f);
        st.getEmpDamageTakenMult().modifyMult(MIRROR, 0f);
    }

    private static void makeVulnerable(ShipAPI s) {
        MutableShipStatsAPI st = s.getMutableStats();
        st.getHullDamageTakenMult().unmodify(MIRROR);
        st.getArmorDamageTakenMult().unmodify(MIRROR);
        st.getShieldDamageTakenMult().unmodify(MIRROR);
        st.getEmpDamageTakenMult().unmodify(MIRROR);
    }

    /** Client: the link is gone. The mirrored battle can't continue meaningfully; end it. */
    private void restoreClient() {
        StarMPPlugin.flog("client: link lost during a mirrored battle; ending it");
        tookOver = false; lastExternal = null; announced = false;
        CoopState.latestSnapshot.set(null);
        engine.setDoNotEndCombat(false);
        for (ShipAPI s : mirrorSet) { makeVulnerable(s); if (s.isAlive()) s.resetDefaultAI(); }
        engine.endCombat(2f);
    }

    private final starmp.ui.Gui gui = new starmp.ui.Gui();
    private boolean renderLogged, renderErrLogged;

    /** Client: Starsector opens its own deployment screen when the local battle starts; tell the player to close it. */
    @Override public void renderInUICoords(ViewportAPI viewport) {
        if (engine == null || titleScreen) return;
        drawPlayerLabels(viewport);
        try { NetManager pn = StarMPPlugin.net(); if (pn != null && pn.linked()) starmp.campaign.Pings.drawCombat(viewport, myBattle(pn)); } catch (Throwable t) { if (!renderErrLogged) { renderErrLogged = true; StarMPPlugin.flog("pings (combat): " + t); } }
        try { starmp.ui.Chat.draw(); } catch (Throwable t) { if (!renderErrLogged) { renderErrLogged = true; StarMPPlugin.flog("chat draw: " + t); } }
        starmp.diag.Diag.captureGl();
        starmp.diag.Overlay.draw(); // F10
        if (!tookOver) return;
        if (!renderLogged) { renderLogged = true; StarMPPlugin.flog("client: render hook active; deployDialog=" + (engine.getCombatUI() != null && engine.getCombatUI().isShowingDeploymentDialog())); }
        try {
            if (engine.getCombatUI() == null || !engine.getCombatUI().isShowingDeploymentDialog()) return;
            float w = Math.min(760, starmp.ui.Gui.sw() - 40), x = (starmp.ui.Gui.sw() - w) / 2f, y = starmp.ui.Gui.sh() - 120;
            gui.begin();
            starmp.ui.Gui.panel(x, y, w, 64);
            gui.textCentered("StarMP: press CANCEL on the deployment screen", starmp.ui.Gui.MED, x + w / 2f, y + 54, starmp.ui.Gui.GOLD);
            gui.textCentered("You are joining the host's battle - your own ships stay in your fleet", starmp.ui.Gui.TINY, x + w / 2f, y + 26, starmp.ui.Gui.TEXT);
            gui.end();
        } catch (Throwable t) { if (!renderErrLogged) { renderErrLogged = true; StarMPPlugin.flog("client: hint render error " + StarMPPlugin.trace(t)); } }
    }

    private static final java.awt.Color LABEL_BLUE = new java.awt.Color(110, 170, 255), LABEL_SHADOW = new java.awt.Color(0, 0, 0, 200);
    private boolean labelErrLogged;
    private int labelsDrawn, labelFrames, lastLabelCount;
    private long lastLabelLogNs;

    /** Test log: which player every live ship would be labelled with on this side (labels skip our own and AI ships). */
    private void logLabelOwners(NetManager net, boolean owner) {
        Map<String, List<String>> by = new TreeMap<>();
        for (ShipAPI s : engine.getShips()) {
            if (s.isFighter() || s.isDrone() || s.isShuttlePod() || !s.isAlive() || s.isHulk()) continue;
            int player;
            if (owner) { Integer p = playerOf(s, net); player = p == null ? -1 : p; }
            else { Integer id = mirrorId.get(s); Wire.ShipDef d = id == null ? null : CoopState.defs.get(id); player = d == null ? -1 : d.player; }
            String who = player < 0 ? (s.getOwner() == 1 ? "enemy" : "AI") : player == net.myId() ? "me(no label)" : CoopState.nameOf(net, player);
            by.computeIfAbsent(who, k -> new ArrayList<>()).add(s.getHullSpec().getHullId() + (s.isAlly() ? "/ally" : ""));
        }
        StarMPPlugin.flog("labels (" + (owner ? "owner" : "joiner") + ", I am " + net.playerName() + "): " + by);
    }

    /** "(Player)" over the name, above every ship another player owns (not ours, not NPC allies). */
    private void drawPlayerLabels(ViewportAPI vp) {
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.linked() || net.others().isEmpty() || vp == null) return;
        boolean owner = !mirrorRole;
        if (!owner && !tookOver) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return; // tactical map: positions differ
        try {
            boolean begun = false;
            if (++labelFrames % 120 == 0) { lastLabelCount = labelsDrawn; labelsDrawn = 0; }
            for (ShipAPI s : engine.getShips()) {
                if (s.isFighter() || s.isDrone() || s.isShuttlePod() || !s.isAlive() || s.isHulk() || !engine.isEntityInPlay(s)) continue;
                int player;
                if (owner) { Integer p = playerOf(s, net); player = p == null ? -1 : p; }
                else {
                    Object id = mirrorId.get(s);
                    Wire.ShipDef d = id instanceof Integer ? CoopState.defs.get((Integer) id) : null;
                    player = d == null ? -1 : d.player;
                }
                if (player < 0 || player == net.myId()) continue;
                if (!vp.isNearViewport(s.getLocation(), s.getCollisionRadius() + 200f)) continue;
                if (!begun) { gui.begin(); begun = true; }
                labelsDrawn++;
                float x = vp.convertWorldXtoScreenX(s.getLocation().x);
                float y = vp.convertWorldYtoScreenY(s.getLocation().y) + vp.convertWorldHeightToScreenHeight(s.getCollisionRadius()) + 18f;
                String name = CoopState.nameOf(net, player);
                gui.textCentered("(Player)", starmp.ui.Gui.TINY, x + 1, y - 1, LABEL_SHADOW);
                gui.textCentered("(Player)", starmp.ui.Gui.TINY, x, y, LABEL_BLUE);
                gui.textCentered(name, starmp.ui.Gui.TINY, x + 1, y - 15, LABEL_SHADOW);
                gui.textCentered(name, starmp.ui.Gui.TINY, x, y - 14, LABEL_BLUE);
            }
            if (begun) gui.end();
            if (System.nanoTime() - lastLabelLogNs > 15_000_000_000L) { lastLabelLogNs = System.nanoTime(); logLabelOwners(net, owner); }
        } catch (Throwable t) { if (!labelErrLogged) { labelErrLogged = true; StarMPPlugin.flog("combat labels: " + StarMPPlugin.trace(t)); } }
    }

    private static int firingCount(ShipAPI s) {
        int n = 0;
        for (WeaponAPI w : s.getAllWeapons()) if (w.isFiring()) n++;
        return n;
    }

    private boolean diagDue() {
        long now = System.nanoTime();
        if (now - lastDiagNs < 2_000_000_000L) return false;
        lastDiagNs = now;
        return true;
    }

    /** 0.1.6.3: the ship my flown ship has target-locked (R / click), as the owner's id; 0 = none. */
    private int myTargetId() {
        try {
            ShipAPI me = engine.getPlayerShip();
            ShipAPI t = me == null ? null : me.getShipTarget();
            if (t == null && me != null && "1".equals(System.getenv("STARMP_TEST_TARGET"))) { // test hook: lock the nearest enemy, like pressing R
                float best = Float.MAX_VALUE;
                for (ShipAPI o : engine.getShips()) {
                    if (o.getOwner() == me.getOwner() || !o.isAlive() || o.isHulk() || o.isFighter() || !mirrorId.containsKey(o)) continue;
                    float dx = o.getLocation().x - me.getLocation().x, dy = o.getLocation().y - me.getLocation().y;
                    if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; t = o; }
                }
                if (t != null) { me.setShipTarget(t); StarMPPlugin.flog("client: TEST locked target " + t.getHullSpec().getHullId() + " (id " + mirrorId.get(t) + ")"); }
            }
            if (t == null) return 0;
            Integer id = mirrorId.get(t);
            return id == null ? 0 : id;
        } catch (Throwable ignored) { return 0; }
    }

    /** 0.9.0.0: the owner of a battle without pause permission (a guest's own battle) can't pause it either. Runs
     *  before the game reads the keys, so consuming the key stops the pause. */
    @Override public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (engine == null || titleScreen || events == null) return;
        try { starmp.ui.Chat.input(events); } catch (Throwable t) { StarMPPlugin.flog("chat (combat): " + t); } // 0.9.0.0
        try { NetManager pn = StarMPPlugin.net(); if (pn != null) combatPings(pn, events); } catch (Throwable t) { StarMPPlugin.flog("combat pings: " + t); } // before the game takes the click
        // 0.9.3.7: quick taps (shield, vent, system) on the joiner's side are read HERE, before the game uses (consumes) them -
        // the old check ran in advance() after the game had consumed the event, threw, and the tap never reached the owner
        if (tookOver && keys != null) {
            for (InputEventAPI ev : events) {
                if (ev.isConsumed() || ev.isRepeat()) continue; // consumed first: any access to a consumed event throws
                int code = ev.isKeyDownEvent() ? ev.getEventValue() : ev.isMouseDownEvent() ? 2000 + ev.getEventValue() : -1;
                if (code < 0) continue;
                if (code == keys.shield) tapLatch |= Wire.B_SHIELD;
                if (code == keys.vent) tapLatch |= Wire.B_VENT;
                if (code == keys.system) tapLatch |= Wire.B_SYSTEM;
            }
        }
        if (tookOver || keys == null) return;
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.linked() || starmp.Session.myCan(starmp.Session.Perm.PAUSE)) return;
        for (InputEventAPI ev : events) {
            if (ev.isConsumed() || !ev.isKeyDownEvent() || ev.isRepeat() || ev.getEventValue() != keys.pause) continue;
            ev.consume();
            engine.getCombatUI().addMessage(0, java.awt.Color.ORANGE, starmp.Session.onlyWho(starmp.Session.current().pausePerm) + " can pause");
        }
    }

    /** 0.9.0.0: whose battle this screen shows (pings are tagged with it). */
    private int myBattle(NetManager net) { return tookOver ? CoopState.battleOwner : net.myId(); }

    /** 0.9.0.0: ping key in a battle -> ping for everyone in it; and a battle owner without pause permission can't pause. */
    private void combatPings(NetManager net, List<InputEventAPI> events) {
        if (events == null || !net.linked()) return;
        int pk = starmp.campaign.Pings.key();
        boolean sent = false;
        for (InputEventAPI ev : events) {
            if (ev.isConsumed()) continue;
            boolean hit = pk >= 2000 ? ev.isMouseDownEvent() && ev.getEventValue() == pk - 2000 : ev.isKeyDownEvent() && !ev.isRepeat() && ev.getEventValue() == pk;
            if (!hit) continue;
            float ex = pk >= 2000 ? ev.getX() : -1, ey = pk >= 2000 ? ev.getY() : -1; // read before consume (the game refuses after)
            ev.consume();
            if (!sent) sent = combatPingAt(net, ex, ey);
        }
        // 0.9.0.1: in battles the game can take the mouse click before plugins see it - fall back to the button state
        if (pk >= 2000) {
            boolean down = false;
            try { down = Mouse.isButtonDown(pk - 2000); } catch (Throwable ignored) {}
            if (down && !pingBtnWasDown && !sent) combatPingAt(net, -1, -1);
            pingBtnWasDown = down;
        }
    }
    private boolean pingBtnWasDown;

    private boolean combatPingAt(NetManager net, float mx, float my) {
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return false; // tactical map: different view
        ViewportAPI vp = engine.getViewport();
        float uiScale = Global.getSettings().getScreenScaleMult();
        if (mx < 0) { mx = Mouse.getX() / uiScale; my = Mouse.getY() / uiScale; }
        starmp.campaign.Pings.send(starmp.campaign.Pings.COMBAT, "", vp.convertScreenXToWorldX(mx), vp.convertScreenYToWorldY(my), myBattle(net));
        return true;
    }

    private Wire.Input readInput() {
        Wire.Input in = new Wire.Input();
        in.seq = ++inputSeq;
        int b = 0;
        in.targetShip = myTargetId();
        if (starmp.Bot.combatInput(engine, in)) { in.wantShip = wantShip; in.pauseCount = pauseCount; in.group = group; lastAimX = in.aimX; lastAimY = in.aimY; return in; } // 0.1.6.5 bot test mode
        if (starmp.ui.Chat.typing() || !Keyboard.isCreated() || !Mouse.isCreated()) { /* typing in the chat: the ship holds still */ in.wantShip = wantShip; in.pauseCount = pauseCount; in.group = group; in.aimX = lastAimX; in.aimY = lastAimY; return in; }
        Bindings k = keys;
        if (k.down(k.accel)) b |= Wire.B_ACCEL;
        if (k.down(k.back)) b |= Wire.B_BACK;
        if (k.down(k.decel)) b |= Wire.B_DECEL;
        if (k.down(k.left)) b |= Wire.B_LEFT;
        if (k.down(k.right)) b |= Wire.B_RIGHT;
        if (k.down(k.strafeL)) b |= Wire.B_STRAFE_L;
        if (k.down(k.strafeR)) b |= Wire.B_STRAFE_R;
        if (k.down(k.fire)) b |= Wire.B_FIRE;
        if (k.down(k.shield)) b |= Wire.B_SHIELD;
        b |= tapLatch; tapLatch = 0;
        if (k.down(k.vent)) b |= Wire.B_VENT;
        if (k.down(k.system)) b |= Wire.B_SYSTEM;
        if (k.down(k.strafeKey) || starmp.ui.Vanilla.strafeToggledOn()) b |= Wire.B_STRAFE_MOD;
        for (int g = 0; g < 7; g++) if (k.down(k.groups[g])) group = g;
        in.bits = b; in.group = group; in.pauseCount = pauseCount; in.wantShip = wantShip;
        ViewportAPI vp = engine.getViewport();
        float uiScale = Global.getSettings().getScreenScaleMult(); // the viewport works in UI units, the mouse in pixels
        in.aimX = vp.convertScreenXToWorldX(Mouse.getX() / uiScale);
        in.aimY = vp.convertScreenYToWorldY(Mouse.getY() / uiScale);
        lastAimX = in.aimX; lastAimY = in.aimY;
        return in;
    }

    // ======================================================================= 0.1.5.3 debug log
    private boolean diagErrLogged;
    private long diagLastNs, diagPdNs;
    private volatile Wire.Input diagLastInput;
    private Set<DamagingProjectileAPI> diagSeen = Collections.newSetFromMap(new IdentityHashMap<DamagingProjectileAPI, Boolean>());
    private final Map<String, int[]> diagPd = new HashMap<>();
    private final Map<CombatEntityAPI, String> diagWpn = new IdentityHashMap<>();
    private static final Map<String, Boolean> pdCache = new HashMap<>();

    /** Point-defense weapon? (by weapon spec, so owner and joiners classify the same shots the same way) */
    private static boolean diagIsPd(String wid) {
        Boolean b = pdCache.get(wid);
        if (b == null) {
            b = false;
            try {
                com.fs.starfarer.api.loading.WeaponSpecAPI sp = Global.getSettings().getWeaponSpec(wid);
                b = sp != null && (sp.getAIHints().contains(WeaponAPI.AIHints.PD) || sp.getAIHints().contains(WeaponAPI.AIHints.PD_ONLY));
            } catch (Throwable ignored) {}
            pdCache.put(wid, b);
        }
        return b;
    }

    /** The id every player's log uses for this ship (the battle owner's numbering), or "-" for a local-only ship. */
    private String diagId(ShipAPI s) {
        if (s == null) return "-";
        Integer id = mirrorRole ? mirrorId.get(s) : ids.get(s);
        return id == null ? "-" : String.valueOf(id);
    }

    private static String r1(float v) { return String.valueOf(Math.round(v)); }

    /** Every frame: shots fired on THIS screen (owner: real shots; mirror: the copies shown); every 100 ms: every ship. */
    private void combatDiag(NetManager net) {
        if (!starmp.diag.Diag.enabled()) return;
        long t0 = starmp.diag.Perf.begin();
        try {
            float bt = engine.getTotalElapsedTime(false);
            String role = mirrorRole ? "mirror" : (net.linked() && !net.others().isEmpty() ? "owner" : "solo");
            List<DamagingProjectileAPI> projs = engine.getProjectiles();
            Set<DamagingProjectileAPI> now = Collections.newSetFromMap(new IdentityHashMap<DamagingProjectileAPI, Boolean>());
            for (DamagingProjectileAPI p : projs) {
                now.add(p);
                if (diagSeen.contains(p)) continue;
                WeaponAPI w = p.getWeapon();
                // copies first: the weapon id the owner sent (the engine gives spawned copies a stand-in weapon)
                String wid = diagWpn.containsKey(p) ? diagWpn.get(p) : w != null ? w.getId() : String.valueOf(p.getProjectileSpecId());
                String src = diagId(p.getSource());
                boolean pd = diagIsPd(wid);
                if (pd) { int[] c = diagPd.get(src + " " + wid); if (c == null) diagPd.put(src + " " + wid, c = new int[1]); c[0]++; continue; }
                starmp.diag.Diag.line("SHOT", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " role=" + role + " src=" + src + " wpn=" + wid
                        + " x=" + r1(p.getLocation().x) + " y=" + r1(p.getLocation().y) + " ang=" + r1(p.getFacing()) + (p instanceof MissileAPI ? " missile=1" : "")
                        + (mirrorRole && mirroredProj.contains(p) ? " copy=1" : ""));
            }
            diagSeen = now;
            diagWpn.keySet().retainAll(mirroredProj); // (a copy spawned this frame shows up in getProjectiles() next frame)
            long ns = System.nanoTime();
            if (ns - diagPdNs >= 1_000_000_000L) {
                diagPdNs = ns;
                for (Map.Entry<String, int[]> e : diagPd.entrySet()) starmp.diag.Diag.line("PD", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " src_wpn=" + e.getKey().replace(' ', '/') + " shots_last_s=" + e.getValue()[0]);
                diagPd.clear();
            }
            if (ns - diagLastNs < 100_000_000L) return;
            diagLastNs = ns;
            int ships = 0, missiles = engine.getMissiles().size();
            ShipAPI mine = engine.getPlayerShip();
            for (ShipAPI s : engine.getShips()) {
                if (s.isShuttlePod() || s.isDrone()) continue;
                ships++;
                if (s.isFighter()) continue; // fighters: counted, not listed (too many)
                Integer mid = mirrorRole ? mirrorId.get(s) : null;
                Wire.ShipDef d = mid == null ? null : CoopState.defs.get(mid);
                String ctrl = s == mine ? "me" : mirrorRole ? (mirrorSet.contains(s) ? "mirror" : "local") : (assigned.containsValue(s) ? "remote-player" : "ai");
                int firing = 0; for (WeaponAPI w : s.getAllWeapons()) if (w.isFiring()) firing++;
                ShieldAPI sh = s.getShield();
                starmp.diag.Diag.line("SHIP", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " role=" + role + " id=" + diagId(s) + " hull=" + s.getHullSpec().getHullId()
                        + " owner=" + s.getOwner() + (d != null ? " pl=" + d.player : "") + " ctrl=" + ctrl
                        + " x=" + r1(s.getLocation().x) + " y=" + r1(s.getLocation().y) + " f=" + r1(s.getFacing()) + " vx=" + r1(s.getVelocity().x) + " vy=" + r1(s.getVelocity().y)
                        + " hp=" + String.format(java.util.Locale.ROOT, "%.3f", s.getHullLevel()) + " flux=" + String.format(java.util.Locale.ROOT, "%.2f", s.getFluxLevel())
                        + " shield=" + (sh != null && sh.isOn() ? "on@" + r1(sh.getFacing()) : "off") + " cr=" + String.format(java.util.Locale.ROOT, "%.2f", s.getCurrentCR())
                        + " phased=" + (s.isPhased() ? 1 : 0) + " vent=" + (s.getFluxTracker().isVenting() ? 1 : 0) + " firing=" + firing
                        + " alive=" + (s.isAlive() && !s.isHulk() ? 1 : 0) + " retreat=" + (s.isRetreating() ? 1 : 0));
            }
            String extra;
            if (mirrorRole) {
                Wire.Input in = diagLastInput;
                if (in != null) starmp.diag.Diag.line("INPUT", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " seq=" + in.seq + " bits=" + in.bits + " aim=" + r1(in.aimX) + "," + r1(in.aimY) + " want=" + in.wantShip);
                extra = " snap_tick=" + lastSnapTick + " snaps_applied=" + appliedSnaps + " snap_age_ms=" + (CoopState.lastSnapRecvNs == 0 ? -1 : (ns - CoopState.lastSnapRecvNs) / 1_000_000) + " inputs_sent=" + sentInputs;
            } else {
                for (Map.Entry<Integer, Wire.Input> e : CoopState.inputs.entrySet()) {
                    Wire.Input in = e.getValue(); Long rt = CoopState.inputRecvNs.get(e.getKey());
                    starmp.diag.Diag.line("INPUT-RX", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " peer=" + e.getKey() + " seq=" + in.seq + " bits=" + in.bits
                            + " aim=" + r1(in.aimX) + "," + r1(in.aimY) + " age_ms=" + (rt == null ? -1 : (ns - rt) / 1_000_000) + " flying=" + diagId(assigned.get(e.getKey())));
                }
                extra = " snap_tick_sent=" + tick;
            }
            starmp.diag.Diag.line("CB", "t=" + String.format(java.util.Locale.ROOT, "%.2f", bt) + " role=" + role + " ships=" + ships + " projectiles=" + projs.size() + " missiles=" + missiles
                    + " paused=" + (engine.isPaused() ? 1 : 0) + " campaign=" + (engine.isInCampaign() ? 1 : 0) + " over=" + (engine.isCombatOver() ? 1 : 0) + extra);
            starmp.diag.Perf.counts = "combat (" + role + "): ships " + ships + ", projectiles " + projs.size() + ", missiles " + missiles;
        } finally { starmp.diag.Perf.end("Diag", t0); }
    }
}
