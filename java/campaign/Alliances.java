package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.2.2: player alliances (spec: claude/starmp-alliance-pvp-spec.md). The HOST keeps every alliance (saved in its
 * campaign as plain JSON text - no mod classes in the save) and runs every action after checking the player's role.
 *
 * - Members act as the alliance: Relations.entityOf(name) is "a:<id>" while they are in one, so the alliance's
 *   standings with players / other alliances apply to all members (and members are never hostile to each other).
 * - The alliance has its own NPC faction reputation. While you are a member your game's standings are set to the
 *   alliance's; any change your game makes (missions, fights...) is sent to the host and applied to the alliance.
 *   Your own standings are kept aside when you join and given back exactly when you leave / are kicked / it disbands.
 * - Regular members can't start hostilities: only the owner and admins mark players, alliances or NPC factions hostile.
 *
 * ALLY (42)     host -> all:  [42][json]   every alliance (without the members' kept-aside standings)
 * ALLYCMD (43)  any -> host:  [43][json]   {"op":..., ...}
 * ALLYCHAT (44) any -> host -> members: [44][json] {"from":name,"text":...}
 * ALLYREP (45)  member -> host: [45][json] {"f":faction,"d":delta}
 * ALLYSET (46)  host -> one player: [46][json] {"restore":{faction:value}}  (their own standings back)
 */
public final class Alliances {
    private Alliances() {}
    public static final byte ALLY = 42, ALLYCMD = 43, ALLYCHAT = 44, ALLYREP = 45, ALLYSET = 46;
    public static final int OPEN = 2, REQUEST = 1, INVITE = 0;
    private static final String KEY = "starmp_alliances", PENDING_KEY = "starmp_alliance_restore";
    public static final int[] COLORS = {0x5AB4FF, 0x6EE07A, 0xFFC850, 0xFF7A5A, 0xC88CFF, 0x5AE6DC, 0xFF8CC8, 0xE6E6E6};

    /** One alliance. Names are stored lower case. */
    public static final class Ally {
        public String id = "", name = "", tag = "", desc = "", owner = "";
        public int color = COLORS[0], policy = INVITE;
        public final LinkedHashSet<String> admins = new LinkedHashSet<>(), members = new LinkedHashSet<>(), invites = new LinkedHashSet<>(), requests = new LinkedHashSet<>();
        public final Map<String, Float> npc = new TreeMap<>();
        final Map<String, Map<String, Float>> personal = new HashMap<>(); // host only: each member's own standings, kept aside
        public boolean isAdmin(String n) { return owner.equals(n) || admins.contains(n); }
        public String role(String n) { return owner.equals(n) ? "owner" : admins.contains(n) ? "admin" : "member"; }
    }

    private static final Map<String, Ally> remote = new ConcurrentHashMap<>(); // guests: from the host
    private static Map<String, Ally> hostCache;                                 // host: parsed from the save
    private static volatile boolean dirty = true, changedLocally;
    private static long lastSendNs, lastRepNs;
    private static final Map<String, Float> lastApplied = new HashMap<>();      // this game: alliance value we last set
    private static String lastMyAlliance;
    private static String restoredFrom; // the alliance my own standings were just given back from (until the host's list catches up)

    static String lc(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static NetManager net() { return StarMPPlugin.net(); }
    private static boolean hosting() { NetManager n = net(); return n != null && n.linked() && n.isHost(); }

    // ------------------------------------------------------------------ host storage
    @SuppressWarnings("unchecked")
    private static Map<String, String> store() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(KEY);
        if (!(o instanceof Map)) { o = new HashMap<String, String>(); pd.put(KEY, o); }
        return (Map<String, String>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pending() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(PENDING_KEY);
        if (!(o instanceof Map)) { o = new HashMap<String, String>(); pd.put(PENDING_KEY, o); }
        return (Map<String, String>) o;
    }

    private static Map<String, Ally> hostAll() {
        if (hostCache == null) {
            hostCache = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : store().entrySet()) {
                try { Ally a = fromJson(new JSONObject(e.getValue()), true); hostCache.put(a.id, a); }
                catch (Throwable t) { StarMPPlugin.flog("alliances: bad saved alliance " + e.getKey() + ": " + t); }
            }
        }
        return hostCache;
    }

    private static void hostSave() {
        Map<String, String> s = store(); s.clear();
        for (Ally a : hostAll().values()) { try { s.put(a.id, toJson(a, true).toString()); } catch (Throwable t) { StarMPPlugin.flog("alliances: save failed: " + t); } }
        dirty = true;
    }

    /** A new campaign was loaded / the link ended: read the save again next time. */
    public static void reset() { restoredFrom = null; testStep = 0; testWait = 0f; hostCache = null; remote.clear(); lastApplied.clear(); lastMyAlliance = null; dirty = true; }

    // ------------------------------------------------------------------ queries (any game)
    public static Collection<Ally> all() { return hosting() ? hostAll().values() : remote.values(); }

    public static Ally byId(String id) { for (Ally a : all()) if (a.id.equals(id)) return a; return null; }

    public static Ally of(String playerName) {
        String n = lc(playerName);
        if (n.isEmpty()) return null;
        for (Ally a : all()) if (a.members.contains(n)) return a;
        return null;
    }

    public static Ally mine() { NetManager n = net(); return n == null || !n.linked() ? null : of(n.playerName()); }

    public static boolean same(String a, String b) { Ally x = of(a); return x != null && x == of(b); }

    /** "[TAG] Name" for labels (just the name outside alliances). */
    public static String tagged(String playerName) {
        Ally a = of(playerName);
        return a == null || a.tag.isEmpty() ? playerName : "[" + a.tag + "] " + playerName;
    }

    // ------------------------------------------------------------------ commands
    /** Sends an action to the host (or runs it, on the host). */
    public static void command(JSONObject cmd) {
        NetManager n = net();
        if (n == null || !n.linked()) return;
        try {
            if (n.isHost()) { hostRun(n.playerName(), -1, cmd); return; }
            byte[] j = cmd.toString().getBytes(StandardCharsets.UTF_8);
            byte[] d = new byte[j.length + 1]; d[0] = ALLYCMD; System.arraycopy(j, 0, d, 1, j.length);
            starmp.combat.CoopState.sendTo(n, 0, d);
        } catch (Throwable t) { StarMPPlugin.flog("alliances: command failed: " + t); }
    }

    /** My game's current standings with every NPC faction (sent when creating / joining). */
    static JSONObject myReps() {
        JSONObject o = new JSONObject();
        try {
            FactionAPI pf = Global.getSector().getPlayerFaction();
            for (FactionAPI f : Global.getSector().getAllFactions()) if (npcFaction(f.getId())) o.put(f.getId(), (double) pf.getRelationship(f.getId()));
        } catch (Throwable t) { StarMPPlugin.flog("alliances: reading standings failed: " + t); }
        return o;
    }

    static boolean npcFaction(String id) {
        return id != null && !id.equals("player") && !id.startsWith("starmp_") && !id.equals("neutral") && !id.equals("sleeper") && !id.equals("poor");
    }

    private static String note;
    /** The last answer from the host (shown in the Alliance intel). */
    public static String lastNote() { return note; }

    // ------------------------------------------------------------------ host: running actions
    private static void hostRun(String fromName, int fromPeer, JSONObject c) throws Exception {
        String who = lc(fromName), op = c.optString("op");
        Map<String, Ally> all = hostAll();
        Ally mine = null; for (Ally a : all.values()) if (a.members.contains(who)) mine = a;
        String answer = null;
        switch (op) {
            case "create": {
                if (mine != null) { answer = "Leave your alliance first."; break; }
                String name = clean(c.optString("name"), 32), tag = clean(c.optString("tag"), 5).toUpperCase(Locale.ROOT);
                if (name.length() < 2 || tag.length() < 2) { answer = "Name needs 2+ characters and the tag 2-5."; break; }
                for (Ally a : all.values()) if (lc(a.name).equals(lc(name)) || a.tag.equalsIgnoreCase(tag)) { answer = "That name or tag is taken."; break; }
                if (answer != null) break;
                Ally a = new Ally();
                a.id = Long.toString(System.currentTimeMillis(), 36); a.name = name; a.tag = tag; a.owner = who; a.members.add(who);
                a.color = COLORS[all.size() % COLORS.length];
                Map<String, Float> reps = repsOf(c.optJSONObject("reps"));
                a.npc.putAll(reps); a.personal.put(who, reps); // the founder's standings, as agreed
                all.put(a.id, a);
                answer = "Created " + name + " [" + tag + "].";
                StarMPPlugin.flog("alliances: " + who + " created " + name + " [" + tag + "]");
                break;
            }
            case "join": case "request": {
                Ally a = all.get(c.optString("id"));
                if (a == null) { answer = "No such alliance."; break; }
                if (mine != null) { answer = "Leave your alliance first."; break; }
                boolean may = a.policy == OPEN || a.invites.contains(who);
                if ("join".equals(op) && may) {
                    a.invites.remove(who); a.requests.remove(who); a.members.add(who);
                    a.personal.put(who, repsOf(c.optJSONObject("reps")));
                    answer = "You joined " + a.name + ".";
                    StarMPPlugin.flog("alliances: " + who + " joined " + a.name);
                } else if (a.policy == REQUEST || "request".equals(op)) {
                    if (a.policy == INVITE && !a.invites.contains(who)) { answer = a.name + " is invite only."; break; }
                    a.requests.add(who); answer = "Request sent to " + a.name + ".";
                } else answer = a.name + " is invite only.";
                break;
            }
            case "decline_invite": {
                Ally a = all.get(c.optString("id")); if (a != null) a.invites.remove(who); break;
            }
            case "leave": {
                if (mine == null) break;
                removeMember(mine, who, "left");
                answer = "You left " + mine.name + ".";
                break;
            }
            default: {
                if (mine == null) { answer = "You are not in an alliance."; break; }
                answer = hostAdminOp(mine, who, op, c);
            }
        }
        hostSave();
        if (answer != null) reply(fromPeer, answer);
    }

    private static String hostAdminOp(Ally a, String who, String op, JSONObject c) throws Exception {
        String target = lc(c.optString("target"));
        boolean owner = a.owner.equals(who), admin = a.isAdmin(who);
        switch (op) {
            case "invite":
                if (!admin) return "Only the owner and admins can invite.";
                if (target.isEmpty() || a.members.contains(target)) return null;
                if (of(target) != null) return target + " is in another alliance.";
                a.invites.add(target); return "Invited " + target + ".";
            case "accept":
                if (!admin) return null;
                if (!a.requests.remove(target) || of(target) != null) return null;
                a.invites.add(target); return target + " may join now (they press Join)."; // their game sends its standings when joining
            case "decline": if (admin) a.requests.remove(target); return null;
            case "kick":
                if (!admin || !a.members.contains(target) || target.equals(a.owner) || (a.admins.contains(target) && !owner)) return "You can't kick them.";
                removeMember(a, target, "kicked"); return "Kicked " + target + ".";
            case "promote": if (owner && a.members.contains(target)) a.admins.add(target); return null;
            case "demote": if (owner) a.admins.remove(target); return null;
            case "transfer":
                if (!owner || !a.members.contains(target)) return null;
                a.admins.add(a.owner); a.owner = target; a.admins.remove(target); return "Ownership given to " + target + ".";
            case "edit": {
                if (!owner) return "Only the owner can change the settings.";
                if (c.has("name")) { String n = clean(c.optString("name"), 32); if (n.length() >= 2) a.name = n; }
                if (c.has("tag")) { String t = clean(c.optString("tag"), 5).toUpperCase(Locale.ROOT); if (t.length() >= 2) a.tag = t; }
                if (c.has("desc")) a.desc = clean(c.optString("desc"), 300);
                if (c.has("policy")) a.policy = Math.max(0, Math.min(2, c.optInt("policy")));
                if (c.has("color")) a.color = c.optInt("color");
                return "Settings saved.";
            }
            case "standing": { // owner / admins: war, peace, friendly with a player, an alliance or an NPC faction
                if (!admin) return "Only the owner and admins can change standings.";
                String ent = c.optString("entity"); int v = Math.max(-100, Math.min(100, c.optInt("value")));
                if (ent.startsWith("f:")) {
                    String f = ent.substring(2);
                    if (!npcFaction(f) || Global.getSector().getFaction(f) == null) return null;
                    if (v > -50) return "NPC factions can only be marked hostile.";
                    float cur = a.npc.containsKey(f) ? a.npc.get(f) : 0f;
                    a.npc.put(f, Math.min(cur, v / 100f));
                    StarMPPlugin.flog("alliances: " + a.name + " marked " + f + " hostile (by " + who + ")");
                    return "Marked " + Global.getSector().getFaction(f).getDisplayName() + " hostile.";
                }
                if (ent.equals("a:" + a.id)) return null;
                Relations.hostSet("a:" + a.id, ent, v, a.name + " by " + who);
                return null;
            }
            case "disband": {
                if (!owner) return null;
                for (String m : new ArrayList<>(a.members)) removeMember(a, m, "disbanded");
                hostAll().remove(a.id);
                StarMPPlugin.flog("alliances: " + a.name + " disbanded by " + who);
                return "Alliance disbanded.";
            }
        }
        return null;
    }

    /** Host: take a member out and give them their own standings back (now, or when they next connect). */
    private static void removeMember(Ally a, String member, String why) {
        a.members.remove(member); a.admins.remove(member);
        Map<String, Float> own = a.personal.remove(member);
        if (own != null) {
            try {
                JSONObject r = new JSONObject();
                for (Map.Entry<String, Float> e : own.entrySet()) r.put(e.getKey(), (double) e.getValue());
                pending().put(member, r.toString());
                sendRestore(member);
            } catch (Throwable t) { StarMPPlugin.flog("alliances: restore failed: " + t); }
        }
        if (a.owner.equals(member)) { // pick a new owner: an admin, else the longest member
            a.owner = !a.admins.isEmpty() ? a.admins.iterator().next() : a.members.isEmpty() ? "" : a.members.iterator().next();
            a.admins.remove(a.owner);
        }
        if (a.members.isEmpty()) hostAll().remove(a.id);
        StarMPPlugin.flog("alliances: " + member + " " + why + " (" + a.name + ")");
    }

    private static void sendRestore(String member) {
        NetManager n = net();
        String body = pending().get(member);
        if (n == null || body == null) return;
        try {
            if (lc(n.playerName()).equals(member)) { applyRestore(new JSONObject(body)); pending().remove(member); return; }
            int peer = peerOf(member);
            if (peer < 0) return; // when they next connect
            JSONObject o = new JSONObject(); o.put("restore", new JSONObject(body));
            starmp.combat.CoopState.sendTo(n, peer, packet(ALLYSET, o));
            pending().remove(member);
        } catch (Throwable t) { StarMPPlugin.flog("alliances: restore send failed: " + t); }
    }

    static int peerOf(String name) {
        NetManager n = net(); if (n == null) return -1;
        for (int id : n.peerIds()) if (lc(starmp.combat.CoopState.nameOf(n, id)).equals(lc(name))) return id;
        return -1;
    }

    private static void reply(int peer, String text) {
        if (peer < 0) { note = text; return; }
        try { JSONObject o = new JSONObject(); o.put("note", text); starmp.combat.CoopState.sendTo(net(), peer, packet(ALLYSET, o)); } catch (Throwable ignored) {}
    }

    private static Map<String, Float> repsOf(JSONObject o) {
        Map<String, Float> m = new TreeMap<>();
        if (o == null) return m;
        for (Iterator<?> it = o.keys(); it.hasNext(); ) { String k = String.valueOf(it.next()); if (npcFaction(k)) m.put(k, (float) o.optDouble(k, 0)); }
        return m;
    }

    private static String clean(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ------------------------------------------------------------------ every frame
    /** Campaign thread while linked: host sends the alliances; every game keeps its NPC standings = its alliance's. */
    static void tick(NetManager n) {
        if (n == null || !n.linked()) return;
        long now = System.nanoTime();
        if (n.isHost()) {
            if (dirty || now - lastSendNs > 10_000_000_000L) {
                dirty = false; lastSendNs = now;
                try { JSONObject o = new JSONObject(); JSONArray arr = new JSONArray(); for (Ally a : hostAll().values()) arr.put(toJson(a, false)); o.put("all", arr); n.broadcast(packet(ALLY, o)); }
                catch (Throwable t) { StarMPPlugin.flog("alliances: send failed: " + t); }
                for (String m : new ArrayList<>(pending().keySet())) sendRestore(m); // members who left while away
            }
        }
        if (now - lastRepNs > 1_000_000_000L) { lastRepNs = now; syncReps(n); }
    }

    /** Keeps my game's NPC standings equal to my alliance's; my own changes go to the alliance. */
    private static void syncReps(NetManager n) {
        Ally a = mine();
        String id = a == null ? null : a.id;
        if (!Objects.equals(id, lastMyAlliance)) { // joined / left / switched
            lastApplied.clear();
            if (a != null && lastMyAlliance == null) dropCommission();
            lastMyAlliance = id;
            if (!Objects.equals(id, restoredFrom)) restoredFrom = null;
        }
        if (a == null || a.id.equals(restoredFrom)) return; // left: don't put the alliance's values back while the list is stale
        FactionAPI pf = Global.getSector().getPlayerFaction();
        for (Map.Entry<String, Float> e : a.npc.entrySet()) {
            String f = e.getKey();
            if (Global.getSector().getFaction(f) == null) continue;
            float local = pf.getRelationship(f), want = e.getValue();
            Float applied = lastApplied.get(f);
            if (applied != null && Math.abs(local - applied) > 0.0005f) {
                // something in my game changed it (mission, fight...): give the change to the alliance
                float d = local - applied;
                lastApplied.put(f, local);
                try { JSONObject o = new JSONObject(); o.put("f", f); o.put("d", (double) d); if (n.isHost()) hostRep(a, f, d); else starmp.combat.CoopState.sendTo(n, 0, packet(ALLYREP, o)); }
                catch (Throwable ignored) {}
                continue;
            }
            if (Math.abs(local - want) > 0.0005f) pf.setRelationship(f, want);
            lastApplied.put(f, want);
        }
    }

    private static void hostRep(Ally a, String f, float d) {
        float cur = a.npc.containsKey(f) ? a.npc.get(f) : 0f;
        a.npc.put(f, Math.max(-1f, Math.min(1f, cur + d)));
        hostSave();
    }

    private static void applyRestore(JSONObject r) {
        FactionAPI pf = Global.getSector().getPlayerFaction();
        int k = 0;
        for (Iterator<?> it = r.keys(); it.hasNext(); ) {
            String f = String.valueOf(it.next());
            if (Global.getSector().getFaction(f) != null) { pf.setRelationship(f, (float) r.optDouble(f, 0)); k++; }
        }
        lastApplied.clear();
        restoredFrom = lastMyAlliance;
        StarMPPlugin.flog("alliances: own standings restored (" + k + " factions)");
        try { Global.getSector().getCampaignUI().addMessage("StarMP: you left the alliance - your own reputations are back"); } catch (Throwable ignored) {}
    }

    private static void dropCommission() {
        try {
            com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel ci = com.fs.starfarer.api.util.Misc.getCommissionIntel();
            if (ci != null) { ci.endMission(); StarMPPlugin.flog("alliances: commission ended on joining"); Global.getSector().getCampaignUI().addMessage("StarMP: joining an alliance ended your commission"); }
        } catch (Throwable t) { StarMPPlugin.flog("alliances: could not end the commission: " + t); }
    }

    // ------------------------------------------------------------------ test hook
    private static int testStep; private static float testWait;
    /** STARMP_TEST_ALLY="op,arg,arg;op..." - one step every 6 s once linked (underscores = spaces). Ops: create,Name,TAG |
     *  policy,N | join (the first alliance listed) | chat,text | standing,entity,value | invite,name | leave | disband. */
    static void testHook(float amount) {
        String spec = System.getenv("STARMP_TEST_ALLY");
        NetManager nm = net();
        if (spec == null || nm == null || !nm.linked() || Global.getSector().getPlayerFleet() == null || (!nm.isHost() && !PlayerRecords.ready())) return;
        String[] steps = spec.split(";");
        if (testStep >= steps.length || (testWait += amount) < 6f) return;
        testWait = 0f;
        String[] p = steps[testStep++].replace('_', ' ').split(",");
        FactionAPI pf = Global.getSector().getPlayerFaction();
        StarMPPlugin.flog("TEST ally step: " + String.join(",", p) + " | in=" + (mine() == null ? "-" : mine().tag) + " heg=" + pf.getRelationship("hegemony") + " tt=" + pf.getRelationship("tritachyon") + " pir=" + pf.getRelationship("pirates"));
        if (p[0].equals("wait")) return;
        try {
            JSONObject c = new JSONObject(); c.put("op", p[0]);
            switch (p[0]) {
                case "create": c.put("name", p[1]); c.put("tag", p[2]); c.put("reps", myReps()); break;
                case "policy": c.put("op", "edit"); c.put("policy", Integer.parseInt(p[1])); break;
                case "join": { Ally a = all().isEmpty() ? null : all().iterator().next(); if (a == null) { testStep--; return; } c.put("id", a.id); c.put("reps", myReps()); break; }
                case "chat": chat(p[1]); return;
                case "rep": { Ally a = mine(); if (a != null && hosting()) { a.npc.put(p[1], Float.parseFloat(p[2]) / 100f); hostSave(); StarMPPlugin.flog("TEST: alliance rep " + p[1] + " = " + p[2]); } return; }
                case "milmarkets": {
                    for (com.fs.starfarer.api.campaign.econ.MarketAPI m : Global.getSector().getEconomy().getMarketsCopy())
                        if (AllianceStorage.military(m)) StarMPPlugin.flog("TEST military market: " + m.getId() + " (" + m.getFactionId() + ") storage here: " + AllianceStorage.available(m));
                    return;
                }
                case "store": AllianceStorage.testOp(p); return;
                case "dock": { // open a market's dialog (as if docked there)
                    com.fs.starfarer.api.campaign.econ.MarketAPI m = Global.getSector().getEconomy().getMarket(p[1]);
                    if (m != null && m.getPrimaryEntity() != null) Global.getSector().getCampaignUI().showInteractionDialog(m.getPrimaryEntity());
                    return;
                }
                case "clearpirates": { // host: remove the leftover "Test Raiders" fleets from earlier test runs
                    int k = 0;
                    for (com.fs.starfarer.api.campaign.LocationAPI l : Global.getSector().getAllLocations())
                        for (com.fs.starfarer.api.campaign.CampaignFleetAPI f : new ArrayList<>(l.getFleets()))
                            if (f.getName() != null && f.getName().contains("Test Raiders")) { f.despawn(); k++; }
                    StarMPPlugin.flog("TEST: removed " + k + " test raider fleet(s)");
                    return;
                }
                case "near": { // guest: move right next to the host's fleet
                    com.fs.starfarer.api.campaign.CampaignFleetAPI h = CoopCampaign.playerFleets().get(p.length > 1 ? Integer.parseInt(p[1]) : 0), me = Global.getSector().getPlayerFleet();
                    if (h != null && h.getContainingLocation() != null) { PlayerRecords.moveTo(Global.getSector(), me, h.getContainingLocation().getId(), h.getLocation().x + 250f, h.getLocation().y); StarMPPlugin.flog("TEST: moved next to the host"); }
                    return;
                }
                case "attack": { int peer = Integer.parseInt(p[1]); PlayerFleetDialog.autoPvp = true; PlayerFleetDialog.open(peer, starmp.combat.CoopState.nameOf(net(), peer)); return; }
                case "intel": Global.getSector().getCampaignUI().showCoreUITab(com.fs.starfarer.api.campaign.CoreUITabId.INTEL); return;
                case "standing": c.put("entity", p[1]); c.put("value", Integer.parseInt(p[2])); break;
                case "invite": c.put("target", p[1]); break;
                default: break;
            }
            command(c);
        } catch (Throwable t) { StarMPPlugin.flog("TEST ally step failed: " + t); }
    }

    // ------------------------------------------------------------------ chat
    /** "/a text": to my alliance only. */
    public static boolean chat(String text) {
        Ally a = mine(); NetManager n = net();
        if (a == null || n == null) { starmp.ui.Chat.system("You are not in an alliance."); return true; }
        try {
            JSONObject o = new JSONObject(); o.put("from", n.playerName()); o.put("text", text);
            if (n.isHost()) relayChat(n, o); else starmp.combat.CoopState.sendTo(n, 0, packet(ALLYCHAT, o));
            starmp.ui.Chat.ally("[" + a.tag + "] " + n.playerName() + ": " + text);
        } catch (Throwable ignored) {}
        return true;
    }

    private static void relayChat(NetManager n, JSONObject o) {
        Ally a = of(o.optString("from"));
        if (a == null) return;
        byte[] d = packet(ALLYCHAT, o);
        for (int id : n.peerIds()) if (!lc(starmp.combat.CoopState.nameOf(n, id)).equals(lc(o.optString("from"))) && a.members.contains(lc(starmp.combat.CoopState.nameOf(n, id)))) n.sendToPeer(id, d);
        if (!lc(n.playerName()).equals(lc(o.optString("from"))) && a.members.contains(lc(n.playerName())))
            starmp.ui.Chat.ally("[" + a.tag + "] " + o.optString("from") + ": " + o.optString("text"));
    }

    // ------------------------------------------------------------------ packets
    private static byte[] packet(byte type, JSONObject o) {
        byte[] j = o.toString().getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[j.length + 1]; d[0] = type; System.arraycopy(j, 0, d, 1, j.length);
        return d;
    }

    public static void onPacket(NetManager n, int peer, byte[] d) {
        try {
            byte t = d[0];
            JSONObject o = new JSONObject(new String(d, 1, d.length - 1, StandardCharsets.UTF_8));
            String from = starmp.combat.CoopState.nameOf(n, peer);
            if (t == ALLY && !n.isHost() && peer == 0) {
                Map<String, Ally> fresh = new HashMap<>();
                JSONArray arr = o.optJSONArray("all");
                if (arr != null) for (int i = 0; i < arr.length(); i++) { Ally a = fromJson(arr.getJSONObject(i), false); fresh.put(a.id, a); }
                remote.keySet().retainAll(fresh.keySet()); remote.putAll(fresh);
            } else if (t == ALLYCMD && n.isHost()) {
                pendingCmds.add(new Object[]{from, peer, o}); // run on the game thread
            } else if (t == ALLYREP && n.isHost()) {
                pendingCmds.add(new Object[]{from, peer, o, "rep"});
            } else if (t == ALLYCHAT) {
                if (n.isHost()) { o.put("from", from); relayChat(n, o); }
                else { Ally a = of(o.optString("from")); starmp.ui.Chat.ally("[" + (a == null ? "?" : a.tag) + "] " + o.optString("from") + ": " + o.optString("text")); }
            } else if (t == ALLYSET && peer == 0) {
                if (o.has("note")) note = o.optString("note");
                if (o.has("restore")) pendingRestore = o.getJSONObject("restore");
            }
        } catch (Throwable e) { StarMPPlugin.flog("alliances: bad packet from " + peer + ": " + e); }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingCmds = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile JSONObject pendingRestore;

    /** Game thread: work queued by the network thread. */
    static void drain() {
        Object[] c;
        while ((c = pendingCmds.poll()) != null) {
            try {
                JSONObject o = (JSONObject) c[2];
                if (c.length > 3) { Ally a = of((String) c[0]); if (a != null) hostRep(a, o.optString("f"), (float) o.optDouble("d", 0)); }
                else hostRun((String) c[0], (Integer) c[1], o);
            } catch (Throwable t) { StarMPPlugin.flog("alliances: action failed: " + StarMPPlugin.trace(t)); }
        }
        JSONObject r = pendingRestore;
        if (r != null) { pendingRestore = null; applyRestore(r); }
    }

    // ------------------------------------------------------------------ json
    private static JSONObject toJson(Ally a, boolean withPersonal) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", a.id); o.put("name", a.name); o.put("tag", a.tag); o.put("desc", a.desc); o.put("owner", a.owner);
        o.put("color", a.color); o.put("policy", a.policy);
        o.put("admins", new JSONArray(a.admins)); o.put("members", new JSONArray(a.members));
        o.put("invites", new JSONArray(a.invites)); o.put("requests", new JSONArray(a.requests));
        JSONObject npc = new JSONObject(); for (Map.Entry<String, Float> e : a.npc.entrySet()) npc.put(e.getKey(), (double) e.getValue()); o.put("npc", npc);
        if (withPersonal) {
            JSONObject p = new JSONObject();
            for (Map.Entry<String, Map<String, Float>> e : a.personal.entrySet()) { JSONObject r = new JSONObject(); for (Map.Entry<String, Float> x : e.getValue().entrySet()) r.put(x.getKey(), (double) x.getValue()); p.put(e.getKey(), r); }
            o.put("personal", p);
        }
        return o;
    }

    private static Ally fromJson(JSONObject o, boolean withPersonal) throws Exception {
        Ally a = new Ally();
        a.id = o.optString("id"); a.name = o.optString("name"); a.tag = o.optString("tag"); a.desc = o.optString("desc"); a.owner = o.optString("owner");
        a.color = o.optInt("color", COLORS[0]); a.policy = o.optInt("policy", INVITE);
        fill(a.admins, o.optJSONArray("admins")); fill(a.members, o.optJSONArray("members")); fill(a.invites, o.optJSONArray("invites")); fill(a.requests, o.optJSONArray("requests"));
        a.npc.putAll(repsOf(o.optJSONObject("npc")));
        JSONObject p = withPersonal ? o.optJSONObject("personal") : null;
        if (p != null) for (Iterator<?> it = p.keys(); it.hasNext(); ) { String k = String.valueOf(it.next()); a.personal.put(k, repsOf(p.optJSONObject(k))); }
        return a;
    }

    private static void fill(Set<String> s, JSONArray arr) { if (arr != null) for (int i = 0; i < arr.length(); i++) s.add(arr.optString(i)); }

    /** Debug log. */
    static void diag(List<String> out) {
        for (Ally a : all()) out.add("ALLY id=" + a.id + " name=" + NpcSync.nm(a.name) + " tag=" + a.tag + " owner=" + NpcSync.nm(a.owner) + " members=" + a.members.size() + " admins=" + a.admins.size()
                + " invites=" + a.invites.size() + " requests=" + a.requests.size() + " npc=" + a.npc.size());
    }
}
