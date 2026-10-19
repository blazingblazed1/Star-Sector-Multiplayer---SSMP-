package starmp.combat;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import starmp.NetManager;
import starmp.StarMPPlugin;

/** Shared inbox between the network thread and the combat plugin. */
public final class CoopState {
    private CoopState() {}

    public static final ConcurrentHashMap<Integer, Wire.Input> inputs = new ConcurrentHashMap<>();
    public static final AtomicReference<Wire.Snapshot> latestSnapshot = new AtomicReference<>();
    public static volatile long lastSnapRecvNs;
    /** Set by JoinBattleDialog right before it starts a battle: this battle mirrors the other player's battle. */
    public static volatile boolean joiningBattle;
    /** 0.9.2.5 PvP: set right before starting a battle. Owner: the player whose fleet joins as the enemy (-1 none).
     *  Defender: join the owner's battle on its side 1. */
    public static volatile int pvpEnemy = -1;
    public static volatile boolean pvpFlip;
    /** When the latest input from each peer arrived (owner side). */
    public static final ConcurrentHashMap<Integer, Long> inputRecvNs = new ConcurrentHashMap<>();
    /** Host: fleets guests want to bring into the current battle (peer -> [flagship index, ships]). */
    public static final ConcurrentHashMap<Integer, Object[]> joinFleets = new ConcurrentHashMap<>();
    /** When each peer's JOINFLEET arrived (owner side; for the load gate). */
    public static final ConcurrentHashMap<Integer, Long> joinRecvNs = new ConcurrentHashMap<>();
    /** Ship definitions by id (host -> client). */
    public static final ConcurrentHashMap<Integer, Wire.ShipDef> defs = new ConcurrentHashMap<>();
    /** Projectile spawns waiting to be created on the client. */
    /** Latest MISSILES packet (owner's live missiles); null until the first arrives. */
    public static volatile List<Wire.MissileState> latestMissiles;
    public static final java.util.concurrent.ConcurrentLinkedQueue<Wire.ProjSpawn> projQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Owner: each client's latest tactical-map orders for its own ships. */
    public static final ConcurrentHashMap<Integer, List<Wire.Order>> orders = new ConcurrentHashMap<>();
    private static volatile boolean installed;
    /** Host -> guests: a campaign packet from another guest: [RELAY][origin peer id][original packet]. */
    public static final byte RELAY = 17;

    /** Host: pass a guest's packet on to every other guest as RELAY. */
    static void relay(NetManager net, int origin, byte[] data) {
        byte[] r = new byte[data.length + 2];
        r[0] = RELAY; r[1] = (byte) origin; System.arraycopy(data, 0, r, 2, data.length);
        for (int other : net.peerIds()) if (other != origin) net.sendToPeer(other, r);
    }

    /** Best known name of a player: direct link, lobby state or their campaign fleet. */
    public static String nameOf(NetManager net, int id) {
        if (id == net.myId()) return net.playerName();
        NetManager.Peer p = net.peer(id);
        if (p != null && p.name != null && !p.name.isEmpty()) return remember(id, p.name);
        starmp.ui.Lobby.Member m = starmp.ui.Lobby.members.get(id);
        if (m != null && !m.name.isEmpty()) return remember(id, m.name);
        String f = starmp.campaign.CoopCampaign.fleetName(id);
        if (f != null && !f.isEmpty()) return remember(id, f);
        String k = knownNames.get(id); // players we only hear about through the host stop sending map positions while
        if (k != null) return k;      // they fight, so keep the last name we learned (and the battle owner's NAMES)
        return id == 0 ? "the host" : "player " + id;
    }

    /** Every player name learned this session (also filled from the battle owner's NAMES packets). */
    public static final ConcurrentHashMap<Integer, String> knownNames = new ConcurrentHashMap<>();
    private static String remember(int id, String name) { knownNames.put(id, name); return name; }

    /** Guest -> host: [RELAYTO][target][packet] - the host hands it to that player (or keeps it if target is 0). */
    public static final byte RELAYTO = 21;
    /** Whose battle this game is showing / joining (packets from the owner of a battle; others are ignored). */
    public static volatile int battleOwner = 0;

    /** Send to one player, wherever they are: the host reaches everyone; a guest reaches other guests through the host. */
    public static void sendTo(NetManager net, int target, byte[] data) {
        if (net.isHost()) { if (target != 0) net.sendToPeer(target, data); return; }
        if (target == 0) { net.sendToHost(data); return; }
        byte[] r = new byte[data.length + 2];
        r[0] = RELAYTO; r[1] = (byte) target; System.arraycopy(data, 0, r, 2, data.length);
        net.sendToHost(r);
    }

    /** Battle owner -> everyone in its battle (packets the host passes on to the other guests). */
    private static boolean ownerBroadcast(byte t) {
        return t == Wire.SNAPSHOT || t == Wire.SHIPDEFS || t == Wire.PROJ || t == Wire.MISSILES || t == Wire.NAMES || t == 10;
    }
    private static boolean campaignBroadcast(byte t) { return (t >= 3 && t <= 5) || t == 6 || t == starmp.campaign.CoopCampaign.PAUSE || t == starmp.campaign.Pings.PING || t == starmp.ui.Chat.CHAT || t == starmp.Session.RENAME; }

    public static synchronized void install(NetManager net) {
        if (installed) return;
        installed = true;
        net.addMessageListener((peer, data) -> {
            try { handle(net, peer, data, false); }
            catch (Throwable t) { StarMPPlugin.flog("bad packet from " + peer + ": " + t); }
        });
    }

    private static void handle(NetManager net, int peer, byte[] data, boolean relayed) {
        byte t = Wire.type(data);
        if (!relayed && starmp.Session.refused(peer)) return; // 0.9.0.0: a kicked player is ignored until they leave
        // 3+ players: guests only talk to the host, so the host passes guests' broadcasts on to the other guests
        if (!relayed && net.isHost() && (campaignBroadcast(t) || ownerBroadcast(t))) relay(net, peer, data);
        if (t == RELAY && !net.isHost() && data.length > 2) {
            int origin = data[1] & 0xFF;
            if (origin == 0 || origin == net.myId()) return;
            handle(net, origin, java.util.Arrays.copyOfRange(data, 2, data.length), true);
            return;
        }
        if (t == RELAYTO && net.isHost() && data.length > 2) {
            int target = data[1] & 0xFF;
            byte[] inner = java.util.Arrays.copyOfRange(data, 2, data.length);
            if (target == 0) { handle(net, peer, inner, true); return; }
            byte[] r = new byte[inner.length + 2];
            r[0] = RELAY; r[1] = (byte) peer; System.arraycopy(inner, 0, r, 2, inner.length);
            net.sendToPeer(target, r);
            return;
        }
        // what a battle's owner sends: only from the battle we are in
        boolean fromOwner = peer == battleOwner;
        if (t == Wire.INPUT) {
            Wire.Input in = Wire.decodeInput(data);
            Wire.Input old = inputs.get(peer);
            if (old == null || in.seq - old.seq > 0) inputs.put(peer, in);
            inputRecvNs.put(peer, System.nanoTime());
        } else if (t == Wire.SHIPDEFS) {
            if (fromOwner) for (Wire.ShipDef d : Wire.decodeDefs(data)) defs.put(d.id, d);
        } else if (t == Wire.JOINFLEET) {
            joinFleets.put(peer, new Object[]{Wire.defsFlag(data), Wire.decodeDefs(data)});
            joinRecvNs.put(peer, System.nanoTime());
            StarMPPlugin.flog("owner: peer " + peer + " wants to bring " + Wire.decodeDefs(data).size() + " ships into the battle");
        } else if (t == Wire.NAMES) {
            for (java.util.Map.Entry<Integer, String> e : Wire.decodeNames(data).entrySet())
                if (e.getKey() != net.myId() && !e.getValue().isEmpty()) knownNames.put(e.getKey(), e.getValue());
        } else if (t == Wire.ORDERS) {
            orders.put(peer, Wire.decodeOrders(data));
        } else if (t == Wire.RESULT) {
            starmp.campaign.CoopCampaign.pendingResult = Wire.decodeResult(data);
        } else if (t == Wire.PROJ) {
            if (!fromOwner) return;
            List<Wire.ProjSpawn> l = Wire.decodeProj(data);
            if (projQueue.size() < 2000) projQueue.addAll(l);
        } else if (t == Wire.MISSILES) {
            if (fromOwner) latestMissiles = Wire.decodeMissiles(data);
        } else if (t == 6 || t == 7) {
            starmp.ui.Lobby.onPacket(peer, data);
        } else if (t == starmp.campaign.LootSplit.SHIPS) {
            starmp.campaign.LootSplit.receivedShips.add(data);
        } else if (t == starmp.campaign.LootSplit.LOOT) {
            starmp.campaign.LootSplit.received.add(data);
        } else if (t == starmp.campaign.RepShare.REP) {
            starmp.campaign.RepShare.onPacket(data);
        } else if (t == starmp.campaign.MarketSync.MKT) {
            starmp.campaign.MarketSync.onPacket(peer, data);
        } else if (t == starmp.diag.Diag.TSYNC) {
            starmp.diag.Diag.onTsync(net, peer, data);
        } else if (t == starmp.campaign.OrbitSync.ORBITS) {
            if (!net.isHost() && peer == 0) starmp.campaign.OrbitSync.onPacket(data);
        } else if (t == starmp.Session.SETTINGS || t == starmp.Session.VOTE || t == starmp.Session.KICK || t == starmp.Session.RENAME) {
            starmp.Session.onPacket(peer, data);
        } else if (t >= starmp.campaign.Alliances.ALLY && t <= starmp.campaign.Alliances.ALLYSET) { // 0.9.2.2
            starmp.campaign.Alliances.onPacket(net, peer, data);
        } else if (t == starmp.campaign.ColonySync.COL) { // 0.9.4.2
            starmp.campaign.ColonySync.onPacket(net, peer, data);
        } else if (t == starmp.campaign.Pvp.PVP) { // 0.9.2.5
            starmp.campaign.Pvp.onPacket(net, peer, data);
        } else if (t == starmp.campaign.AllianceStorage.STORE || t == starmp.campaign.AllianceStorage.STORED) { // 0.9.2.4
            starmp.campaign.AllianceStorage.onPacket(net, peer, data);
        } else if (t == starmp.campaign.Relations.REL || t == starmp.campaign.Relations.RELREQ) { // 0.9.2.1
            starmp.campaign.Relations.onPacket(net, peer, data);
        } else if (t == starmp.ui.Chat.CHAT) {
            starmp.ui.Chat.onPacket(net, peer, data);
        } else if (t == starmp.campaign.Pings.PING) {
            starmp.campaign.Pings.onPacket(net, peer, data);
        } else if (t == starmp.campaign.HyperSpeed.HSET) {
            starmp.campaign.HyperSpeed.onPacket(data);
        } else if (t == starmp.campaign.PlayerRecords.PDATA) {
            starmp.campaign.PlayerRecords.onPacket(peer, data);
        } else if (t == 25) {
            starmp.campaign.TradeSession.onPacket(peer, data);
        } else if (t == 22 || t == 23 || t == 24 || t == starmp.campaign.NpcSync.NPCHOLD || t == starmp.campaign.NpcSync.NPCENGAGE) {
            starmp.campaign.NpcSync.dispatch(peer, t, data);
        } else if ((t >= 3 && t <= 5) || t == 10 || t == 13 || t == starmp.campaign.CoopCampaign.PAUSE) {
            starmp.campaign.CoopCampaign.onPacket(peer, t, data);
        } else if (t == Wire.SNAPSHOT) {
            if (!fromOwner) return;
            lastSnapRecvNs = System.nanoTime();
            Wire.Snapshot s = Wire.decodeSnapshot(data);
            Wire.Snapshot old = latestSnapshot.get();
            if (old == null || s.tick - old.tick > 0) latestSnapshot.set(s);
        }
    }

    public static void reset() { inputs.clear(); inputRecvNs.clear(); joinRecvNs.clear(); latestSnapshot.set(null); defs.clear(); projQueue.clear(); latestMissiles = null; orders.clear(); }
}
