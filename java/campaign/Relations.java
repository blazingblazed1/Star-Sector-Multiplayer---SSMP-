package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.2.1: reputation between players (later: alliances). Every pair starts at 0 = neutral; -50 or lower = hostile
 * (vanilla's line for factions). The HOST keeps the table (saved in its campaign, sector persistent data) and sends it
 * to everyone; guests only ask the host for changes.
 *
 * Entities are strings: "p:<player name, lower case>" (stage 2 adds "a:<alliance>" and "f:<npc faction>").
 *
 * Hostile players stay unnamed: their "(Player)" label, direction arrow, map markers and pings are hidden until
 * their ships are actually visible (not just a sensor contact), or their transponder is on and they are on sensors.
 *
 * REL (40): host -> all: [40][n:short] n x ([key][value:int])           (the whole table)
 * RELREQ (41): guest -> host: [41][target name][value:int]              (asks to set my standing with target)
 */
public final class Relations {
    private Relations() {}
    public static final byte REL = 40, RELREQ = 41;
    public static final int HOSTILE = -50, MIN = -100, MAX = 100;
    private static final String KEY = "starmp_relations";

    private static final Map<String, Integer> remote = new ConcurrentHashMap<>(); // guests: the host's table
    private static volatile boolean dirty = true;
    private static long lastSendNs;
    private static boolean testApplied;

    // ------------------------------------------------------------------ table
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> store() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(KEY);
        if (!(o instanceof Map)) { o = new HashMap<String, Integer>(); pd.put(KEY, o); }
        return (Map<String, Integer>) o;
    }

    private static boolean hosting() { NetManager n = StarMPPlugin.net(); return n == null || !n.linked() || n.isHost(); }

    private static Map<String, Integer> table() {
        if (hosting()) { try { return store(); } catch (Throwable t) { return remote; } }
        return remote;
    }

    static String key(String a, String b) { return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a; }

    /** The entity a player acts as (stage 2: their alliance while they are in one). */
    public static String entityOf(String playerName) {
        Alliances.Ally a = Alliances.of(playerName); // 0.9.2.2: members act as their alliance
        if (a != null) return "a:" + a.id;
        return "p:" + (playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT));
    }

    public static int standing(String a, String b) {
        if (a.equals(b)) return MAX;
        Integer v = table().get(key(a, b));
        return v == null ? 0 : v;
    }

    /** Is that player hostile to me? */
    public static boolean hostileTo(int peer) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked() || peer == n.myId()) return false;
        String me = entityOf(n.playerName()), them = entityOf(starmp.combat.CoopState.nameOf(n, peer));
        return standing(me, them) <= HOSTILE;
    }

    /** Their ships are actually visible (not just a sensor contact), or their transponder is on and we see them. */
    public static boolean revealed(CampaignFleetAPI f) {
        if (f == null) return false;
        try {
            SectorEntityToken.VisibilityLevel v = f.getVisibilityLevelToPlayerFleet();
            if (v == SectorEntityToken.VisibilityLevel.COMPOSITION_DETAILS || v == SectorEntityToken.VisibilityLevel.COMPOSITION_AND_FACTION_DETAILS) return true;
            return f.isTransponderOn() && v != SectorEntityToken.VisibilityLevel.NONE;
        } catch (Throwable t) { return false; }
    }

    /** Hostile and not revealed: no name, label, arrow, map marker or ping for that player. */
    public static boolean hidden(int peer) {
        if (!hostileTo(peer)) return false;
        CampaignFleetAPI f = CoopCampaign.playerFleets().get(peer);
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (f == null || me == null || f.getContainingLocation() != me.getContainingLocation()) return true;
        return !revealed(f);
    }

    // ------------------------------------------------------------------ changes
    /** Host only: set a standing (clamped) and send the table to everyone. */
    public static void hostSet(String a, String b, int value, String why) {
        if (!hosting() || a.equals(b)) return;
        int v = Math.max(MIN, Math.min(MAX, value));
        Integer old = store().put(key(a, b), v);
        if (old == null || old != v) {
            dirty = true;
            StarMPPlugin.flog("relations: " + a + " <-> " + b + " " + (old == null ? 0 : old) + " -> " + v + " (" + why + ")");
        }
    }

    /** Asks the host to set my standing with that player (the host checks it). */
    public static void request(String targetName, int value) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) return;
        if (n.isHost()) { hostSet(entityOf(n.playerName()), entityOf(targetName), value, "host"); return; }
        byte[] t = targetName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(1 + 2 + t.length + 4);
        b.put(RELREQ).putShort((short) t.length).put(t).putInt(value);
        starmp.combat.CoopState.sendTo(n, 0, b.array());
    }

    /** Host: a player was renamed - their standings move with them. */
    public static void renamed(String oldName, String newName) {
        if (!hosting()) return;
        String o = entityOf(oldName), nw = entityOf(newName);
        Map<String, Integer> t = store();
        for (String k : new ArrayList<>(t.keySet())) {
            String[] p = k.split("\\|", 2);
            if (p.length < 2 || (!p[0].equals(o) && !p[1].equals(o))) continue;
            Integer v = t.remove(k);
            String other = p[0].equals(o) ? p[1] : p[0];
            if (v != null && !other.equals(nw)) t.put(key(nw, other), v);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ network
    /** Campaign thread, while linked. Host: send the table on change and every 10 s. */
    static void tick(NetManager net) {
        if (net == null || !net.linked() || !net.isHost()) return;
        if (!testApplied) { // test hook: STARMP_TEST_HOSTILE=nameA,nameB makes those two players hostile
            testApplied = true;
            String h = System.getenv("STARMP_TEST_HOSTILE");
            if (h != null && h.contains(",")) { String[] p = h.split(",", 2); hostSet(entityOf(p[0].replace('_', ' ')), entityOf(p[1].replace('_', ' ')), HOSTILE, "test hook"); }
        }
        long now = System.nanoTime();
        if (!dirty && now - lastSendNs < 10_000_000_000L) return;
        dirty = false; lastSendNs = now;
        net.broadcast(encode(store()));
    }

    /** Host: someone (re)joined - make sure they get the table now. */
    public static void changed() { dirty = true; }

    private static byte[] encode(Map<String, Integer> t) {
        List<byte[]> ks = new ArrayList<>(); int size = 3;
        List<Integer> vs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : t.entrySet()) { byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8); ks.add(k); vs.add(e.getValue()); size += 2 + k.length + 4; }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(REL).putShort((short) ks.size());
        for (int i = 0; i < ks.size(); i++) b.putShort((short) ks.get(i).length).put(ks.get(i)).putInt(vs.get(i));
        return b.array();
    }

    public static void onPacket(NetManager net, int peer, byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d); byte t = b.get();
            if (t == REL && !net.isHost() && peer == 0) {
                int n = b.getShort() & 0xFFFF;
                Map<String, Integer> fresh = new HashMap<>();
                for (int i = 0; i < n; i++) { byte[] k = new byte[b.getShort() & 0xFFFF]; b.get(k); fresh.put(new String(k, StandardCharsets.UTF_8), b.getInt()); }
                remote.keySet().retainAll(fresh.keySet()); remote.putAll(fresh);
            } else if (t == RELREQ && net.isHost()) {
                byte[] k = new byte[b.getShort() & 0xFFFF]; b.get(k);
                String target = new String(k, StandardCharsets.UTF_8);
                int v = b.getInt();
                // stage 1: a player may only change their OWN standing with another player (stage 3 adds the PvP rules)
                hostSet(entityOf(starmp.combat.CoopState.nameOf(net, peer)), entityOf(target), v, "request from " + starmp.combat.CoopState.nameOf(net, peer));
            }
        } catch (Throwable t) { StarMPPlugin.flog("relations: bad packet from " + peer + ": " + t); }
    }

    /** Debug log: one line per known standing. */
    static void diag(List<String> out) {
        for (Map.Entry<String, Integer> e : table().entrySet()) out.add("REL " + e.getKey() + "=" + e.getValue() + (e.getValue() <= HOSTILE ? " hostile" : ""));
    }
}
