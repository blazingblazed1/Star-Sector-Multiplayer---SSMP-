package starmp.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * Shared campaign, first layer: every player flies their own fleet natively in their own game (same save),
 * sends where it is, and sees the other player's fleet as a puppet fleet in a separate StarMP faction.
 * Host: remote players show as faction starmp_guest. Client: the host shows as starmp_host.
 */
public class CoopCampaign implements EveryFrameScript {
    public static final String HOST_FACTION = "starmp_host", GUEST_FACTION = "starmp_guest";
    private static final float SEND_INTERVAL = 0.2f;
    /** A player more than this many days ahead pauses until the other catches up. */
    private long lastHoldMsgNs;

    // network inbox (filled from the helper thread)
    public static final ConcurrentHashMap<Integer, CampWire.Fleet> fleets = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, CampWire.Comp> comps = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, CampWire.Hello> hellos = new ConcurrentHashMap<>();

    private static CoopCampaign instance;
    /** Latest battle notice from anyone (kept for old callers; use battles / battleOf / battleById). */
    public static volatile CampWire.Battle hostBattle;
    /** 0.1.6.1: latest battle notice per battle owner. With one slot, two players fighting at once overwrote each other
     * every second and every other player's battle intel was closed and re-posted (with a message) each time - spam. */
    public static final ConcurrentHashMap<Integer, CampWire.Battle> battles = new ConcurrentHashMap<>();
    /** A battle notice counts as running while it is active and refreshed in the last 4 s (owners send ~1 Hz). */
    public static boolean live(CampWire.Battle b) { return b != null && b.active && System.nanoTime() - b.recvNs < 4_000_000_000L; }
    public static CampWire.Battle battleOf(int owner) { return battles.get(owner); }
    public static CampWire.Battle battleById(int id) { for (CampWire.Battle b : battles.values()) if (b.battleId == id) return b; return null; }
    private static final Set<Integer> joinedBattles = ConcurrentHashMap.newKeySet();   // battles I joined: no more offers
    private static final Set<Integer> announcedBattles = ConcurrentHashMap.newKeySet(); // battles already messaged once
    /** Guest: ships I brought into the host's battle, and what happened to them. */
    public static volatile List<FleetMemberAPI> joinedMembers;
    public static volatile List<starmp.combat.Wire.Outcome> pendingResult;
    public static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingGifts = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static int promptedBattle = -1, farLogged = -1;

    /** Vanilla battle join range (settings.json battleJoinRange + the player-faction bonus; teammates count as player faction). */
    static float joinRange() {
        float r = 500f, bonus = 200f;
        try { r = Global.getSettings().getFloat("battleJoinRange"); } catch (Throwable ignored) {}
        try { bonus = Global.getSettings().getFloat("battleJoinRangePlayerFactionBonus"); } catch (Throwable ignored) {}
        return r + bonus;
    }

    /** Other players' fleets shown on this map: peer -> fleet. */
    public static Map<Integer, CampaignFleetAPI> playerFleets() {
        Map<Integer, CampaignFleetAPI> out = new HashMap<>();
        CoopCampaign c = instance;
        if (c != null) for (Map.Entry<Integer, Puppet> e : c.puppets.entrySet()) if (e.getValue().fleet != null) out.put(e.getKey(), e.getValue().fleet);
        return out;
    }

    /** Host (0.1.4.9): each guest's stand-in has its own faction (starmp_g1..g9) carrying that player's standings,
     * so the host's NPCs react to each player by their own reputation. Slots are kept per player name for the session. */
    private static final Map<String, Integer> slots = new HashMap<>();
    static String slotFaction(NetManager net, int peer) {
        NetManager.Peer p = net.peer(peer);
        String name = p == null || p.name == null ? fleetName(peer) : p.name;
        if (name == null) return GUEST_FACTION;
        String key = PlayerRecords.key(name);
        Integer s = slots.get(key);
        if (s == null) {
            for (int i = 1; i <= 9 && s == null; i++) if (!slots.containsValue(i)) s = i;
            if (s == null) return GUEST_FACTION;
            slots.put(key, s);
            StarMPPlugin.flog("campaign: " + name + " uses stand-in slot " + s);
        }
        return Global.getSector().getFaction("starmp_g" + s) != null ? "starmp_g" + s : GUEST_FACTION;
    }
    /** Host: whose stand-in faction is this (player name key), or null. */
    static String slotOwner(String factionId) {
        for (Map.Entry<String, Integer> e : slots.entrySet()) if (("starmp_g" + e.getValue()).equals(factionId)) return e.getKey();
        return null;
    }

    /** Battle notices per player (sent ~1/s while that player fights): drives the "IN BATTLE" label over their fleet. */
    static final ConcurrentHashMap<Integer, Long> battleSeen = new ConcurrentHashMap<>();
    static boolean playerInBattle(int peer) { Long t = battleSeen.get(peer); return t != null && System.nanoTime() - t < 4_000_000_000L; }

    private static String fleetNameOr(int peer) { String n = fleetName(peer); return n == null ? "a player" : n; }

    /** Send our HELLO (name, portrait) right away - e.g. after a portrait change. */
    static volatile boolean helloNow;

    /** PvP hook: players at war don't get each other's markers. No PvP yet, so never. */
    public static boolean atWar(int peer) { return false; }

    private final Map<Integer, Puppet> puppets = new HashMap<>();
    private final Set<Integer> helloWarned = new HashSet<>();
    private long lastSendNs, lastCompNs, lastHelloNs, lastDiagNs;
    private int lastCompHash;
    private boolean announced;

    private static final class Puppet {
        CampaignFleetAPI fleet;
        SectorEntityToken anchor;
        int compHash, pausedNudges;
        String loc;
    }

    public static void install() {
        removeBattleIntel();
        instance = new CoopCampaign();
        Global.getSector().addTransientScript(instance);
        try { PlayerFleetDialog.register(); } catch (Throwable t) { StarMPPlugin.flog("dialog plugin: " + t); }
        try { StarMPDialog.register(); } catch (Throwable t) { StarMPPlugin.flog("F8 hotkey: " + t); }
        try { Scoreboard.register(); } catch (Throwable t) { StarMPPlugin.flog("scoreboard: " + t); }
        try { DiagInput.register(); } catch (Throwable t) { StarMPPlugin.flog("diag input: " + t); }
        try { MarketSync.install(); } catch (Throwable t) { StarMPPlugin.flog("market sync install: " + t); }
        try { LootSplit.register(); } catch (Throwable t) { StarMPPlugin.flog("loot split: " + t); }
        try { Global.getSector().getListenerManager().removeListenerOfClass(FriendHud.class); Global.getSector().getListenerManager().addListener(new FriendHud(), true); }
        catch (Throwable t) { StarMPPlugin.flog("friend hud: " + t); }
        try { Global.getSector().getListenerManager().removeListenerOfClass(PauseKeyWatcher.class); Global.getSector().getListenerManager().addListener(new PauseKeyWatcher(), true); }
        catch (Throwable t) { StarMPPlugin.flog("pause watcher: " + t); }
        StarMPPlugin.flog("campaign: co-op script installed");
    }

    /** Called before a save: puppets must never end up in a save file. They are recreated next frame. */
    public static void beforeSave() { HyperSpeed.clearAll(); if (instance != null) instance.removeAllPuppets("saving"); removeBattleIntel(); NpcSync.restoreAll("saving"); }

    /** Shared pause: one player pressing the pause key pauses / unpauses everyone. */
    public static final byte PAUSE = 18;
    private static volatile boolean pauseKeyPressed;
    private static volatile int remotePause = -1; private static volatile int remotePauseFrom = -1;

    public static final class PauseKeyWatcher implements com.fs.starfarer.api.campaign.listeners.CampaignInputListener {
        private final int key = starmp.combat.Bindings.pauseKey();
        @Override public int getListenerInputPriority() { return 0; }
        @Override public void processCampaignInputPreCore(List<com.fs.starfarer.api.input.InputEventAPI> events) {
            // seen before the game handles it (it consumes the key); the new pause state is read next frame
            for (com.fs.starfarer.api.input.InputEventAPI e : events) if (!e.isConsumed() && e.isKeyDownEvent() && !e.isRepeat() && e.getEventValue() == key) pauseKeyPressed = true;
        }
        @Override public void processCampaignInputPreFleetControl(List<com.fs.starfarer.api.input.InputEventAPI> events) {}
        @Override public void processCampaignInputPostCore(List<com.fs.starfarer.api.input.InputEventAPI> events) {}
    }

    /** Name a player's fleet was last seen with (for players known only through the host). */
    public static String fleetName(int peer) {
        CampWire.Fleet f = fleets.get(peer);
        if (f == null || f.name == null) return null;
        return f.name.endsWith("'s fleet") ? f.name.substring(0, f.name.length() - 8) : f.name;
    }

    public static void onPacket(int peer, byte type, byte[] d) {
        if (type == PAUSE && d.length >= 2) { remotePause = d[1]; remotePauseFrom = peer; return; }
        if (type == CampWire.FLEET) { CampWire.Fleet f = CampWire.decodeFleet(d); f.recvNs = System.nanoTime(); fleets.put(peer, f); }
        else if (type == CampWire.COMP) comps.put(peer, CampWire.decodeComp(d));
        else if (type == CampWire.HELLO) hellos.put(peer, CampWire.decodeHello(d));
        else if (type == CampWire.GIFT) { Object[] g = CampWire.decodeGift(d); pendingGifts.add(g); }
        else if (type == CampWire.BATTLE) { CampWire.Battle b = CampWire.decodeBattle(d); b.recvNs = System.nanoTime(); b.owner = peer; hostBattle = b; battles.put(peer, b); battleSeen.put(peer, b.recvNs); }
    }

    @Override public boolean isDone() { return false; }
    @Override public boolean runWhilePaused() { return true; }

    @Override
    public void advance(float amount) {
        if (BackgroundSim.nested && !BackgroundSim.inCombat) return; // our own nested engine step (never-pause): this frame was already handled
        starmp.ui.Background.frame(); // 0.1.5.9: keep running unfocused, capped
        // 0.1.5.3 debug log / performance (one call per rendered frame)
        try {
            starmp.diag.Perf.frame("campaign");
            starmp.diag.Diag.pollKeys("campaign");
            String note = starmp.diag.Diag.takeNote();
            if (note != null && Global.getSector() != null) Global.getSector().getCampaignUI().addMessage(note);
            CampDiag.tick(StarMPPlugin.net(), Global.getSector());
        } catch (Throwable t) { StarMPPlugin.flog("diag (campaign): " + t); }
        long perfT0 = starmp.diag.Perf.begin();
        try { advanceInner(amount); } finally { starmp.diag.Perf.end("Campaign", perfT0); }
    }

    private void advanceInner(float amount) {
        if (!tradeDraftShown && System.getenv("STARMP_TEST_TRADEUI") != null && Global.getSector().getPlayerFleet() != null && (tradeDraftDelay += amount) > 3f && !Global.getSector().getCampaignUI().isShowingDialog()) { tradeDraftShown = true; TradeScreen.showDraft(); }
        if (BackgroundSim.inCombat) { // host in a battle: only the network side of the campaign (positions, NPC stream, clock)
            try { tick(amount); } catch (Throwable t) { StarMPPlugin.flog("campaign tick (combat bg) error: " + StarMPPlugin.trace(t)); }
            return;
        }
        try {
            TradeScreen.tick();
            Object[] rq;
            while ((rq = TradeSession.incoming.poll()) != null) FriendTradeIntel.post((Integer) rq[0], (String) rq[1]);
        } catch (Throwable t) { StarMPPlugin.flog("trade tick error: " + StarMPPlugin.trace(t)); }
        try { tick(amount); } catch (Throwable t) { StarMPPlugin.flog("campaign tick error: " + StarMPPlugin.trace(t)); }
    }

    private void tick(float amount) {
        NetManager net = StarMPPlugin.net();
        SectorAPI sector = Global.getSector();
        if (net == null || sector == null || sector.getPlayerFleet() == null) return;
        if (!BackgroundSim.inCombat) try { HotJoin.tick(net); } catch (Throwable t) { StarMPPlugin.flog("hot join tick: " + t); }
        if (!testJumped && System.getenv("STARMP_TEST_JUMP") != null) {
            testJumped = true;
            try {
                float days = Float.parseFloat(System.getenv("STARMP_TEST_JUMP"));
                Global.getSector().getListenerManager().addListener(new com.fs.starfarer.api.campaign.listeners.EconomyTickListener() {
                    public void reportEconomyTick(int iterIndex) {}
                    public void reportEconomyMonthEnd() { StarMPPlugin.flog("TEST: economy month end fired at " + Global.getSector().getClock().getDateString()); }
                }, true);
                String before = sector.getClock().getDateString();
                float credits = sector.getPlayerFleet().getCargo().getCredits().get(), supplies = sector.getPlayerFleet().getCargo().getSupplies();
                setClock(sector, sector.getClock().getTimestamp() + (long) (days * 86_400_000L));
                StarMPPlugin.flog("TEST: jumped " + days + " days " + before + " -> " + sector.getClock().getDateString() + " (credits " + Math.round(credits) + ", supplies " + Math.round(supplies) + ")");
            } catch (Throwable t) { StarMPPlugin.flog("TEST: jump failed " + t); }
        }
        boolean hosting = net.isHost() && net.state() == NetManager.State.HOSTING;
        boolean client = !net.isHost() && net.state() == NetManager.State.CONNECTED;
        updatePlayersIntel(hosting || client);
        List<Integer> peers = new ArrayList<>(net.peerIds());
        if (client) { // other guests reach us through the host; count them while their positions keep coming
            long n = System.nanoTime();
            for (Map.Entry<Integer, CampWire.Fleet> e : fleets.entrySet())
                if (!peers.contains(e.getKey()) && e.getKey() != net.myId() && n - e.getValue().recvNs < 10_000_000_000L) peers.add(e.getKey());
        }
        // a player who reconnected comes back with a new id; the old id lingers until it times out - drop it now
        if (hosting) {
            Map<String, Integer> newestByName = new HashMap<>();
            for (int p : peers) { NetManager.Peer pr = net.peer(p); if (pr != null) newestByName.merge(pr.name, p, Math::max); }
            for (int p : new ArrayList<>(peers)) {
                NetManager.Peer pr = net.peer(p);
                if (pr != null && newestByName.get(pr.name) != p) {
                    CampWire.Fleet f = fleets.get(p);
                    if (f == null || System.nanoTime() - f.recvNs > 2_000_000_000L) peers.remove((Integer) p); // the stale one stops sending
                }
            }
        }
        if (!(hosting || client) || peers.isEmpty()) {
            if (!puppets.isEmpty()) {
                if (hosting) AutoSave.request(puppets.size() == 1 ? fleetNameOr(puppets.keySet().iterator().next()) + " left" : "players left");
                removeAllPuppets("not connected");
            }
            if (hosting) { try { AutoSave.hostTick(net, sector); } catch (Throwable t) { StarMPPlugin.flog("autosave error: " + t); } }
            NpcSync.restoreAll("not connected");
            HyperSpeed.clearAll();
            try { TimeLock.tick(false); } catch (Throwable ignored) {}
            return;
        }
        long pt = starmp.diag.Perf.begin();
        try { if (hosting) OrbitSync.hostTick(net, fleets); else OrbitSync.guestTick(sector); } catch (Throwable t) { StarMPPlugin.flog("orbit sync error: " + t); }
        starmp.diag.Perf.end("Sub:Orbits", pt);
        try { TimeLock.tick(client); } catch (Throwable t) { StarMPPlugin.flog("time lock error: " + t); }
        try { RepShare.tick(net, sector); } catch (Throwable t) { StarMPPlugin.flog("rep share error: " + StarMPPlugin.trace(t)); }
        pt = starmp.diag.Perf.begin();
        try { MarketSync.tick(net, sector, hosting, client); } catch (Throwable t) { StarMPPlugin.flog("market sync error: " + StarMPPlugin.trace(t)); }
        starmp.diag.Perf.end("Sub:Markets", pt);
        if (hosting) { try { AutoSave.hostTick(net, sector); } catch (Throwable t) { StarMPPlugin.flog("autosave error: " + t); } }
        try { HyperSpeed.tick(net, sector, hosting); } catch (Throwable t) { StarMPPlugin.flog("hyperspace speed error: " + StarMPPlugin.trace(t)); }
        pt = starmp.diag.Perf.begin();
        try { PlayerRecords.tick(net, sector, hosting, client); } catch (Throwable t) { StarMPPlugin.flog("player records error: " + StarMPPlugin.trace(t)); }
        starmp.diag.Perf.end("Sub:Records", pt);
        if (!announced) { announced = true; StarMPPlugin.flog("campaign: linked as " + (hosting ? "HOST" : "CLIENT")); }
        if (net.sharedSaves > 0 && !sharedSavesWarned) { sharedSavesWarned = true; notify("StarMP: " + NetManager.SHARED_SAVES_WARNING); }
        sharedPause(sector, net);
        if (hosting) {
            CampaignUIAPI uiB = sector.getCampaignUI();
            if (uiB.getCurrentInteractionDialog() == null && !uiB.isShowingDialog() && !uiB.isShowingMenu() && uiB.getCurrentCoreTab() == null) userPaused = sector.isPaused();
            long bt = starmp.diag.Perf.begin();
            try { BackgroundSim.hostTick(sector, net, amount, userPaused); } catch (Throwable t) { StarMPPlugin.flog("never-pause error: " + StarMPPlugin.trace(t)); }
            starmp.diag.Perf.end("Vanilla:NeverPause", bt);
        }
        long nt = starmp.diag.Perf.begin();
        try {
            if (NpcSync.enabled()) { if (hosting) NpcSync.hostTick(net, fleets); else NpcSync.guestTick(net, sector); }
            else NpcSync.restoreAll("shared NPC fleets turned off");
        }
        catch (Throwable t) { if (npcErrors++ < 5) StarMPPlugin.flog("npc sync error: " + StarMPPlugin.trace(t)); }
        starmp.diag.Perf.end("Sub:NpcSync", nt);
        long now = System.nanoTime();
        CampaignFleetAPI me = sector.getPlayerFleet();

        // ---- send my state
        if (now - lastSendNs >= (long) (SEND_INTERVAL * 1e9)) {
            lastSendNs = now;
            CampWire.Fleet f = new CampWire.Fleet();
            LocationAPI loc = me.getContainingLocation();
            f.loc = loc == null ? "" : loc.getId();
            f.name = net.playerName() + "'s fleet";
            f.x = me.getLocation().x; f.y = me.getLocation().y;
            f.vx = me.getVelocity().x; f.vy = me.getVelocity().y;
            CampWire.Comp comp = composition(me);
            f.compHash = comp.hulls.hashCode() * 31 + comp.names.hashCode();
            CampaignUIAPI uiP = sector.getCampaignUI();
            if (uiP.getCurrentInteractionDialog() == null && !uiP.isShowingDialog() && !uiP.isShowingMenu() && uiP.getCurrentCoreTab() == null) userPaused = sector.isPaused();
            f.paused = userPaused; // a dialog pausing my game is not a pause for everyone
            f.clock = sector.getClock().getTimestamp();
            f.transponder = me.isTransponderOn();
            send(net, CampWire.encode(f));
            if (f.compHash != lastCompHash || now - lastCompNs > 5_000_000_000L) {
                lastCompHash = f.compHash; lastCompNs = now;
                send(net, CampWire.encode(comp));
            }
        }
        if (helloNow) { helloNow = false; lastHelloNs = 0; }
        if (now - lastHelloNs > 10_000_000_000L) {
            lastHelloNs = now;
            send(net, CampWire.encode(hello(net)));
        }

        // ---- show the other players
        for (Integer peer : peers) {
            CampWire.Fleet st = fleets.get(peer);
            CampWire.Comp comp = comps.get(peer);
            if (st == null || comp == null || comp.hulls.isEmpty()) continue;
            checkHello(peer);
            updatePuppet(peer, st, comp, peer == 0 ? HOST_FACTION : (hosting ? slotFaction(net, peer) : GUEST_FACTION)); // peer 0 is always the host
        }
        if (hosting && NpcSync.enabled()) { try { NpcSync.hostStandIns(net, playerFleets()); } catch (Throwable t) { if (npcErrors++ < 5) StarMPPlugin.flog("npc stand-ins error: " + StarMPPlugin.trace(t)); } }
        timeSync(sector, peers);
        if (BackgroundSim.inCombat) return; // no screens, no cargo changes from inside a battle
        if (!testGuestPirates && hosting && "1".equals(System.getenv("STARMP_TEST_PIRATES_AT_GUEST"))) { // test hook: interception of a guest
            for (Map.Entry<Integer, Puppet> e : puppets.entrySet()) {
                CampaignFleetAPI g = e.getValue().fleet;
                if (g == null || e.getKey() == 0 || g.getContainingLocation() == null) continue;
                testGuestPirates = true;
                CampaignFleetAPI f = Global.getFactory().createEmptyFleet("pirates", "Test Interceptors", true);
                for (String v : new String[]{"buffalo2_FS", "buffalo2_FS"}) {
                    String id = Global.getSettings().doesVariantExist(v) ? v : v.substring(0, v.indexOf('_')) + "_Hull";
                    try { f.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, id)); } catch (Throwable ignored) {}
                }
                for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) m.getRepairTracker().setCR(0.7f);
                g.getContainingLocation().addEntity(f);
                f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
                f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
                f.setLocation(g.getLocation().x + 600, g.getLocation().y);
                f.addAssignment(FleetAssignment.INTERCEPT, g, 100f);
                StarMPPlugin.flog("TEST: spawned pirates next to player " + e.getKey() + "'s stand-in (" + g.getName() + ")");
                break;
            }
        }
        if (!testPiratesSpawned && System.getenv("STARMP_TEST_PIRATES") != null) spawnTestPirates(me);
        maybePromptBattle(sector);
        if (pendingResult != null) applyResult(sector);
        Object[] gift;
        while ((gift = pendingGifts.poll()) != null) {
            float c = (Float) gift[0];
            if (c <= 0 || c > 1e9f) continue;
            me.getCargo().getCredits().add(c);
            notify("StarMP: received " + Math.round(c) + " credits from " + gift[1]);
        }
        LootSplit.checkOtherIncome();
        byte[] shipPkt;
        while ((shipPkt = LootSplit.receivedShips.poll()) != null) {
            try { notify(LootSplit.applyShips(shipPkt, me)); } catch (Throwable t) { StarMPPlugin.flog("ship apply failed: " + t); }
        }
        byte[] lootPkt;
        while ((lootPkt = LootSplit.received.poll()) != null) {
            try { notify(LootSplit.apply(lootPkt, me.getCargo())); } catch (Throwable t) { StarMPPlugin.flog("loot apply failed: " + t); }
        }
        for (Iterator<Map.Entry<Integer, Puppet>> it = puppets.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Puppet> e = it.next();
            if (!peers.contains(e.getKey())) { despawn(e.getValue()); it.remove(); fleets.remove(e.getKey()); comps.remove(e.getKey());
                if (StarMPPlugin.net() != null && StarMPPlugin.net().isHost()) AutoSave.request(fleetNameOr(e.getKey()) + " left");
                StarMPPlugin.flog("campaign: removed fleet of departed peer " + e.getKey()); }
        }

        if (now - lastDiagNs > 5_000_000_000L) {
            lastDiagNs = now;
            StringBuilder sb = new StringBuilder("campaign diag: me ").append(locName(me.getContainingLocation()))
                    .append(" @").append(Math.round(me.getLocation().x)).append(',').append(Math.round(me.getLocation().y));
            for (Map.Entry<Integer, Puppet> e : puppets.entrySet()) {
                CampaignFleetAPI p = e.getValue().fleet;
                CampWire.Fleet st = fleets.get(e.getKey());
                sb.append(" | peer ").append(e.getKey()).append(" puppet ").append(locName(p.getContainingLocation()))
                  .append(" @").append(Math.round(p.getLocation().x)).append(',').append(Math.round(p.getLocation().y))
                  .append(" ships=").append(p.getFleetData().getNumMembers());
                if (st != null) sb.append(" reported ").append(st.loc).append(" @").append(Math.round(st.x)).append(',').append(Math.round(st.y));
            }
            StarMPPlugin.flog(sb.toString());
        }
    }

    /** Keep both campaign clocks together: whoever is ahead waits (pauses) until the others catch up. */
    private long lastOwnPauseNs;
    private boolean userPaused;
    private int pauseReadIn;

    private void sharedPause(SectorAPI sector, NetManager net) {
        // guests follow the host's pause state (it comes with every position update), so a late loader or a
        // missed pause message fixes itself; not while a dialog is open or right after our own pause key
        if (!net.isHost()) {
            CampWire.Fleet h = fleets.get(0);
            CampaignUIAPI ui0 = sector.getCampaignUI();
            if (h != null && System.nanoTime() - h.recvNs < 2_000_000_000L && System.nanoTime() - lastOwnPauseNs > 1_500_000_000L
                    && ui0.getCurrentInteractionDialog() == null && !ui0.isShowingDialog() && !ui0.isShowingMenu()
                    && h.recvNs > lastOwnPauseNs && sector.isPaused() != h.paused) {
                sector.setPaused(h.paused);
                StarMPPlugin.flog("campaign: following the host's pause state (" + (h.paused ? "paused" : "running") + ")");
            }
        }
        if (pauseKeyPressed) { pauseKeyPressed = false; pauseReadIn = 2; lastOwnPauseNs = System.nanoTime(); }
        if (pauseReadIn > 0 && --pauseReadIn == 0) { // read the new state a couple of frames after the key (the game applies it after us)
            lastOwnPauseNs = System.nanoTime();
            boolean p = sector.isPaused();
            net.sendAll(new byte[]{PAUSE, (byte) (p ? 1 : 0)});
            StarMPPlugin.flog("campaign: I " + (p ? "paused" : "unpaused") + " - telling the others");
        }
        int rp = remotePause;
        if (rp >= 0) {
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog() || ui.isShowingMenu()) return; // apply once the screen is free
            remotePause = -1;
            sector.setPaused(rp == 1);
            String name = starmp.combat.CoopState.nameOf(net, remotePauseFrom);
            notify("StarMP: " + name + (rp == 1 ? " paused" : " unpaused") + " the game");
        }
    }

    /**
     * Time sync: nobody waits for anybody. Whoever is behind the furthest-ahead player (a slow window, a player
     * coming out of a battle, a late loader) jumps their date forward to it. Skipped time is not simulated, so it
     * costs nothing (no supplies, fuel or crew) and repairs nothing.
     */
    private void timeSync(SectorAPI sector, List<Integer> peers) {
        // 0.1.4.7, host authority: the host owns the calendar and never jumps to a guest's date (that skipped the
        // world's simulation); guests catch up to the host only. The host keeps running through its own menus and
        // battles (never-pause), so it doesn't fall behind.
        NetManager n0 = StarMPPlugin.net();
        boolean host = n0 != null && n0.isHost();
        if (host && BackgroundSim.enabled()) return; // (with never-pause off the host still catches up, as before)
        long mine = sector.getClock().getTimestamp(), target = mine; String who = null;
        for (Integer peer : peers) {
            if (!host && peer != 0) continue; // guests: only the host's clock counts
            CampWire.Fleet st = fleets.get(peer);
            if (st == null || st.clock == 0) continue;
            if (System.nanoTime() - st.recvNs > 5_000_000_000L) continue; // stale (in a battle, saving, gone)
            if (st.clock > target) { target = st.clock; who = st.name; }
        }
        float behindDays = sector.getClock().getElapsedDaysSince(target) * -1f;
        if (behindDays < JUMP_MIN_DAYS) return;
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui.getCurrentInteractionDialog() != null) return; // jump once the dialog is closed
        if (setClock(sector, target)) {
            jumpedDays += behindDays;
            if (behindDays >= 0.5f) notify("StarMP: caught up " + String.format("%.1f", behindDays) + " days to " + (who == null ? "the others" : who) + "'s date");
            if (System.nanoTime() - lastJumpLogNs > 10_000_000_000L) {
                lastJumpLogNs = System.nanoTime();
                StarMPPlugin.flog("campaign: time jump +" + String.format("%.2f", behindDays) + " days (total " + String.format("%.1f", jumpedDays) + ")");
            }
        }
    }

    /** Jumps further than this are applied (about 1.2 game hours: smaller gaps are just network timing). */
    public static float JUMP_MIN_DAYS = 0.05f;
    private float jumpedDays; private long lastJumpLogNs;
    private static boolean clockBroken;

    /** Moves the campaign clock forward to the given timestamp (calendar and the saved timestamp field). */
    static boolean setClock(SectorAPI sector, long ts) {
        if (clockBroken) return false;
        try {
            CampaignClockAPI c = sector.getClock();
            c.getCal().setTimeInMillis(ts);
            java.lang.invoke.MethodHandles.privateLookupIn(c.getClass(), java.lang.invoke.MethodHandles.lookup())
                    .findSetter(c.getClass(), "timestamp", long.class).invoke(c, ts);
            return true;
        } catch (Throwable t) {
            clockBroken = true;
            StarMPPlugin.flog("campaign: cannot set the clock (" + t + ") - time sync off");
            return false;
        }
    }

    private boolean testPiratesSpawned, testJumped, testGuestPirates, sharedSavesWarned;

    /** Test hook only (env STARMP_TEST_PIRATES): a small hostile fleet next to the host, to start a campaign battle. */
    private void spawnTestPirates(CampaignFleetAPI me) {
        testPiratesSpawned = true;
        if ("1".equals(System.getenv("STARMP_TEST_COMMISSION"))) { // test hook: a Hegemony commission pays per pirate ship destroyed
            try {
                com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel ci = new com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel(Global.getSector().getFaction("hegemony"));
                ci.missionAccepted();
                StarMPPlugin.flog("TEST: commission with the Hegemony accepted");
            } catch (Throwable t) { StarMPPlugin.flog("TEST: commission failed: " + t); }
        }
        String tf = System.getenv("STARMP_TEST_PIRATES_FACTION") != null ? System.getenv("STARMP_TEST_PIRATES_FACTION") : "pirates"; // test: a non-hostile faction -> rep change
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(tf, "Test Raiders", true);
        boolean passive = "passive".equals(System.getenv("STARMP_TEST_PIRATES")); // sits still, never attacks (NPC sync tests)
        boolean slow = passive || "slow".equals(System.getenv("STARMP_TEST_PIRATES")); // slow, weak ships: short test battles
        for (String v : slow ? new String[]{"buffalo2_FS", "buffalo2_FS", "colossus3_Pirate"} : new String[]{"lasher_CS", "wolf_CS", "kite_pirates_Raider"}) {
            String id = Global.getSettings().doesVariantExist(v) ? v : v.substring(0, v.indexOf('_')) + "_Hull";
            try { f.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, id)); } catch (Throwable ignored) {}
        }
        for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) m.getRepairTracker().setCR(0.7f);
        me.getContainingLocation().addEntity(f);
        if (passive) {
            f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
            f.setLocation(me.getLocation().x + 700, me.getLocation().y + 400);
            f.addAssignment(FleetAssignment.HOLD, me.getContainingLocation().createToken(me.getLocation().x + 700, me.getLocation().y + 400), 1000f);
        } else {
            f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
            f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
            f.setLocation(me.getLocation().x + 300, me.getLocation().y);
            f.addAssignment(FleetAssignment.INTERCEPT, me, 100f);
        }
        StarMPPlugin.flog("TEST: spawned pirate fleet (" + f.getFleetData().getNumMembers() + " ships) next to the host");
    }

    /** Guest: apply the host's battle outcome to my real fleet (losses, hull, CR). */
    private void applyResult(SectorAPI sector) {
        List<starmp.combat.Wire.Outcome> res = pendingResult; pendingResult = null;
        List<FleetMemberAPI> ships = joinedMembers; joinedMembers = null;
        if (res == null || ships == null) return;
        CampaignFleetAPI me = sector.getPlayerFleet();
        int lost = 0, damaged = 0;
        for (starmp.combat.Wire.Outcome o : res) {
            if (o.index < 0 || o.index >= ships.size()) continue;
            FleetMemberAPI m = ships.get(o.index);
            if (!me.getFleetData().getMembersListCopy().contains(m)) continue;
            if (o.destroyed) {
                if (me.getFleetData().getNumMembers() > 1 && m != me.getFlagship()) { me.getFleetData().removeFleetMember(m); lost++; }
                else { m.getStatus().setHullFraction(0.05f); m.getRepairTracker().setCR(0f); damaged++; }
            } else {
                if (o.hullFrac < 0.999f) damaged++;
                m.getStatus().setHullFraction(Math.max(0.05f, Math.min(1f, o.hullFrac)));
                // like vanilla after a battle: the CR left at the end, minus the cost of deploying the ship
                float cr = Math.max(0f, Math.min(1f, o.cr) - m.getDeployCost());
                StarMPPlugin.flog("campaign: result " + m.getHullSpec().getHullId() + " cr " + String.format("%.2f", m.getRepairTracker().getCR()) + " -> " + String.format("%.2f", cr) + " (battle end " + String.format("%.2f", o.cr) + ", deploy cost " + String.format("%.2f", m.getDeployCost()) + ")");
                m.getRepairTracker().setCR(cr);
            }
        }
        me.getFleetData().setSyncNeeded();
        me.forceSync();
        notify("StarMP: joint battle result - " + lost + " ship(s) lost, " + damaged + " damaged");
        StarMPPlugin.flog("campaign: applied battle result: " + res.size() + " ships, " + lost + " lost, " + damaged + " damaged");
    }

    /** Client: the host started a campaign battle -> offer to join it. */
    private int npcErrors;
    private boolean tradeDraftShown;
    private float tradeDraftDelay;
    private boolean autoJoinPending;

    /** Friends fighting: one intel item per running battle (holds the Join button); a message only the first time. */
    private void maybePromptBattle(SectorAPI sector) {
        long now = System.nanoTime();
        // close intel for battles that ended / went quiet / that I joined
        for (Iterator<Map.Entry<Integer, FriendBattleIntel>> it = battleIntels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, FriendBattleIntel> e = it.next();
            CampWire.Battle b = battleById(e.getKey());
            if (live(b) && !joinedBattles.contains(e.getKey())) continue;
            StarMPPlugin.flog("campaign: battle intel closed (" + (b == null ? "no notice" : "active=" + b.active + " ageMs=" + (now - b.recvNs) / 1_000_000 + " id=" + b.battleId + " joined=" + joinedBattles.contains(e.getKey())) + ")");
            e.getValue().close(); it.remove();
        }
        // forget notices nobody has refreshed for a minute
        for (Iterator<Map.Entry<Integer, CampWire.Battle>> it = battles.entrySet().iterator(); it.hasNext(); ) if (now - it.next().getValue().recvNs > 60_000_000_000L) it.remove();
        for (CampWire.Battle b : battles.values()) {
            if (!live(b) || joinedBattles.contains(b.battleId)) continue;
            if (autoJoinPending) {
                float d = battleDistance(b);
                if (d >= 0 && d <= joinRange() && !sector.getCampaignUI().isShowingDialog()) { autoJoinPending = false; openJoin(b.battleId, null); }
            }
            if (joinRequested == b.battleId) {
                CampaignUIAPI cui = sector.getCampaignUI();
                if (cui.getCurrentCoreTab() == null && !cui.isShowingDialog() && cui.getCurrentInteractionDialog() == null) { joinRequested = -1; openJoin(b.battleId, null); }
            }
            if (battleIntels.containsKey(b.battleId)) continue;
            FriendBattleIntel intel = new FriendBattleIntel(b.battleId, b.owner, b.hostName, b.enemyName);
            battleIntels.put(b.battleId, intel);
            boolean first = announcedBattles.add(b.battleId);
            try {
                sector.getIntelManager().addIntel(intel, true);
                if (first) sector.getCampaignUI().addMessage(intel, com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction.INTEL_TAB, intel);
            } catch (Throwable t) { StarMPPlugin.flog("campaign: battle intel failed: " + t); }
            StarMPPlugin.flog("campaign: " + b.hostName + " (player " + b.owner + ") is in battle " + b.battleId + " vs " + b.enemyName + " -> intel " + (first ? "posted" : "re-added (no message)"));
            if (first && "1".equals(System.getenv("STARMP_TEST_AUTOJOIN"))) autoJoinPending = true; // test hook
        }
    }
    private final Map<Integer, FriendBattleIntel> battleIntels = new HashMap<>();

    /** Distance from my fleet's edge to a friend's battle, or -1 if it is in another location (or unknown). */
    static float battleDistance(CampWire.Battle b) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (b == null || me == null || !b.hasPos) return b != null && !b.hasPos ? 0f : -1f;
        LocationAPI here = me.getContainingLocation();
        if (here == null || !here.getId().equals(b.loc)) return -1f;
        float dx = me.getLocation().x - b.x, dy = me.getLocation().y - b.y;
        return Math.max(0f, (float) Math.sqrt(dx * dx + dy * dy) - me.getRadius());
    }

    /** Intel's Join button: remember the battle; the dialog opens once the Intel screen is closed. */
    static volatile int joinRequested = -1;

    /** The intel's Join button: open the join dialog for this battle (if still running and in range). */
    static void openJoin(int battleId, com.fs.starfarer.api.ui.IntelUIAPI intelUi) {
        CampWire.Battle b = battleById(battleId);
        if (!live(b)) return;
        float d = battleDistance(b);
        if (d < 0 || d > joinRange()) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        String stageFaction = StarMPPlugin.net().isHost() ? GUEST_FACTION : HOST_FACTION;
        CampaignFleetAPI stage = Global.getFactory().createEmptyFleet(Global.getSector().getFaction(stageFaction) != null ? stageFaction : "independent", b.hostName + "'s battle", true);
        CampWire.Comp comp = comps.get(b.owner);
        String hull = comp != null && !comp.hulls.isEmpty() ? comp.hulls.get(0) : "kite";
        try { stage.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, hull + "_Hull")); }
        catch (Throwable t) { stage.getFleetData().addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, "kite_Hull")); }
        stage.getMemoryWithoutUpdate().set("$starmp_stage", true);
        stage.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
        stage.setNoAutoDespawn(true);
        me.getContainingLocation().addEntity(stage);
        stage.setLocation(me.getLocation().x + 50, me.getLocation().y + 50);
        StarMPPlugin.flog("campaign: opening join dialog for " + b.hostName + "'s battle " + b.battleId);
        JoinBattleDialog dlg = new JoinBattleDialog(b.owner, b.hostName, b.enemyName, stage);
        // never start the battle from a dialog opened inside the Intel screen: when combat ends the game looks for
        // the campaign encounter dialog, finds none and crashes ("encounterDialog is null")
        Global.getSector().getCampaignUI().showInteractionDialog(dlg, stage);
    }

    /** Joined: stop offering this battle. */
    static void markJoined(int owner) { CampWire.Battle b = battleOf(owner); if (b != null) { promptedBattle = b.battleId; joinedBattles.add(b.battleId); } }

    /** Before saving: our intel items must not end up in the save. */
    static void removeBattleIntel() {
        try {
            for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : new ArrayList<>(Global.getSector().getIntelManager().getIntel(FriendBattleIntel.class)))
                Global.getSector().getIntelManager().removeIntel(i);
            for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : new ArrayList<>(Global.getSector().getIntelManager().getIntel(FriendTradeIntel.class)))
                Global.getSector().getIntelManager().removeIntel(i);
            for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : new ArrayList<>(Global.getSector().getIntelManager().getIntel(PlayersIntel.class)))
                Global.getSector().getIntelManager().removeIntel(i);
        } catch (Throwable ignored) {}
        if (instance != null) { instance.battleIntels.clear(); instance.playersIntel = null; }
    }

    private PlayersIntel playersIntel;

    /** Keep exactly one Players intel while linked; drop it when the link ends (it is never saved). */
    private void updatePlayersIntel(boolean linked) {
        try {
            if (linked && playersIntel == null) {
                playersIntel = new PlayersIntel();
                Global.getSector().getIntelManager().addIntel(playersIntel, true);
            } else if (!linked && playersIntel != null) {
                playersIntel.endImmediately(); playersIntel = null;
            }
        } catch (Throwable t) { StarMPPlugin.flog("players intel: " + t); }
    }

    private static void send(NetManager net, byte[] data) {
        if (net.isHost()) net.broadcast(data); else net.sendToHost(data);
    }

    private static CampWire.Comp composition(CampaignFleetAPI fleet) {
        CampWire.Comp c = new CampWire.Comp();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m.isFighterWing()) continue;
            c.hulls.add(m.getHullSpec().getBaseHullId());
            c.names.add(m.getShipName() == null ? "" : m.getShipName());
        }
        return c;
    }

    private static CampWire.Hello hello(NetManager net) {
        CampWire.Hello h = new CampWire.Hello();
        h.version = StarMPPlugin.VERSION;
        h.seed = Global.getSector().getSeedString();
        h.systems = Global.getSector().getStarSystems().size();
        h.playerName = net.playerName();
        try { h.portrait = Global.getSector().getPlayerPerson().getPortraitSprite(); } catch (Throwable ignored) {}
        return h;
    }

    private void checkHello(int peer) {
        CampWire.Hello h = hellos.get(peer);
        if (h == null || helloWarned.contains(peer)) return;
        CampWire.Hello mine = hello(StarMPPlugin.net());
        helloWarned.add(peer);
        if (!h.version.equals(mine.version))
            notify("StarMP: version mismatch (you " + mine.version + ", " + h.playerName + " " + h.version + ")");
        if (!String.valueOf(h.seed).equals(String.valueOf(mine.seed)) || h.systems != mine.systems) {
            notify("StarMP: " + h.playerName + " is in a different sector (load the same save on both PCs)");
            StarMPPlugin.flog("campaign: SECTOR MISMATCH seed " + mine.seed + "/" + h.seed + " systems " + mine.systems + "/" + h.systems);
        } else {
            StarMPPlugin.flog("campaign: same sector confirmed with peer " + peer + " (seed " + h.seed + ", " + h.systems + " systems)");
            notify("StarMP: linked with " + h.playerName + " - same sector");
        }
    }

    private static void notify(String msg) {
        StarMPPlugin.flog(msg);
        try { Global.getSector().getCampaignUI().addMessage(msg); } catch (Throwable ignored) {}
    }

    static LocationAPI location(String id) { return findLocation(id); }

    private static LocationAPI findLocation(String id) {
        SectorAPI s = Global.getSector();
        if (id == null || id.isEmpty()) return null;
        if (s.getHyperspace() != null && id.equals(s.getHyperspace().getId())) return s.getHyperspace();
        for (StarSystemAPI sys : s.getStarSystems()) if (id.equals(sys.getId())) return sys;
        return null;
    }

    private static String locName(LocationAPI l) { return l == null ? "nowhere" : l.getName(); }

    private void updatePuppet(int peer, CampWire.Fleet st, CampWire.Comp comp, String factionId) {
        Puppet p = puppets.get(peer);
        if (p != null && p.compHash != st.compHash && comp.hulls.size() > 0) { // fleet changed: rebuild
            despawn(p); puppets.remove(peer); p = null;
        }
        LocationAPI target = findLocation(st.loc);
        if (target == null) { if (p != null) { despawn(p); puppets.remove(peer); } return; }
        float age = Math.min(1f, (System.nanoTime() - st.recvNs) / 1e9f);
        float px = st.x + st.vx * age, py = st.y + st.vy * age;
        if (p == null) {
            p = spawn(peer, st, comp, factionId, target, px, py);
            if (p == null) return;
            puppets.put(peer, p);
        }
        CampaignFleetAPI f = p.fleet;
        if (f.getContainingLocation() != target) { // they jumped: move the puppet to the new location
            if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
            if (p.anchor != null && p.anchor.getContainingLocation() != null) p.anchor.getContainingLocation().removeEntity(p.anchor);
            target.addEntity(f);
            p.anchor = target.createToken(px, py);
            f.clearAssignments();
            f.addAssignment(FleetAssignment.HOLD, p.anchor, 1e6f);
            f.setLocation(px, py);
            StarMPPlugin.flog("campaign: peer " + peer + " fleet moved to " + target.getName());
        }
        p.anchor.setLocation(px, py);
        float dx = px - f.getLocation().x, dy = py - f.getLocation().y;
        if (dx * dx + dy * dy > 500f * 500f) f.setLocation(px, py);                 // too far: snap
        else f.setLocation(f.getLocation().x + dx * 0.25f, f.getLocation().y + dy * 0.25f); // smooth
        f.setVelocity(st.vx, st.vy);
        f.setMoveDestinationOverride(px + st.vx * 0.5f, py + st.vy * 0.5f);
        f.setName(st.name);
        if (f.isTransponderOn() != st.transponder) f.setTransponderOn(st.transponder); // NPCs react to it (host stand-ins)
        // while this game is paused the engine doesn't advance fleets, so a newly shown fleet would stay invisible
        // until unpause; advance it a hair so its sprite gets built
        if (Global.getSector().isPaused()) { try { f.advance(0.001f); } catch (Throwable ignored) {} }
    }

    private Puppet spawn(int peer, CampWire.Fleet st, CampWire.Comp comp, String factionId, LocationAPI loc, float x, float y) {
        FactionAPI faction = Global.getSector().getFaction(factionId);
        if (faction == null) {
            StarMPPlugin.flog("campaign: faction " + factionId + " missing in this save, using independent");
            factionId = "independent";
            faction = Global.getSector().getFaction(factionId);
        }
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(factionId, st.name, true);
        int added = 0;
        for (int i = 0; i < comp.hulls.size(); i++) {
            try {
                FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, comp.hulls.get(i) + "_Hull");
                if (comp.names.get(i) != null && !comp.names.get(i).isEmpty()) m.setShipName(comp.names.get(i));
                f.getFleetData().addFleetMember(m);
                added++;
            } catch (Throwable t) { StarMPPlugin.flog("campaign: cannot show hull " + comp.hulls.get(i) + ": " + t); }
        }
        if (added == 0) return null;
        f.getFleetData().sort();
        f.forceSync();
        f.setNoAutoDespawn(true);
        MemoryAPI mem = f.getMemoryWithoutUpdate();
        mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        mem.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
        mem.set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
        mem.set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
        mem.set(MemFlags.MEMORY_KEY_NO_JUMP, true);
        mem.set(MemFlags.MEMORY_KEY_IGNORE_PLAYER_COMMS, true);
        mem.set("$starmp_puppet", true);
        mem.set("$starmp_peer", peer);
        try { faction.setRelationship("player", 0.3f); } catch (Throwable ignored) {}
        loc.addEntity(f);
        f.setLocation(x, y);
        Puppet p = new Puppet();
        p.fleet = f; p.compHash = st.compHash; p.loc = loc.getId();
        p.anchor = loc.createToken(x, y);
        f.clearAssignments();
        f.addAssignment(FleetAssignment.HOLD, p.anchor, 1e6f);
        StarMPPlugin.flog("campaign: showing peer " + peer + " fleet '" + st.name + "' (" + added + " ships, faction " + factionId + ") in " + loc.getName());
        return p;
    }

    private static void despawn(Puppet p) {
        try {
            if (p.fleet.getContainingLocation() != null) p.fleet.getContainingLocation().removeEntity(p.fleet);
            if (p.anchor != null && p.anchor.getContainingLocation() != null) p.anchor.getContainingLocation().removeEntity(p.anchor);
        } catch (Throwable ignored) {}
    }

    private void removeAllPuppets(String why) {
        if (puppets.isEmpty()) return;
        for (Puppet p : puppets.values()) despawn(p);
        StarMPPlugin.flog("campaign: removed " + puppets.size() + " puppet fleet(s) (" + why + ")");
        puppets.clear();
        helloWarned.clear();
        announced = false;
    }
}
