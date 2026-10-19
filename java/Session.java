package starmp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 0.9.0.0: host settings + permissions for a multiplayer session.
 *
 * The host owns them (saved in the host's StarMP settings) and sends them to everyone (SETTINGS, every 5 s and on
 * change). Permissions are levels: 0 = host only, 1 = host + admins, 2 = everyone. Admins are player names the host
 * promotes. Players without a permission can start a vote instead (pause / unpause / fast-forward), if the host
 * allows votes: the host runs it, everyone gets a Yes/No prompt, more than half of the connected players decides,
 * 20 s limit. Kick: the host tells that player to leave and refuses them for the rest of the session (a kicked name
 * that reconnects is told again and ignored). Also enforces the player limit.
 */
public final class Session {
    private Session() {}

    public static final byte SETTINGS = 34, VOTE = 35, KICK = 37, RENAME = 38;
    public static final int HOST_ONLY = 0, ADMINS = 1, EVERYONE = 2;
    public static final int V_PAUSE = 1, V_UNPAUSE = 2, V_FF_ON = 3, V_FF_OFF = 4;
    public enum Perm { PAUSE, SPEED, KICK }

    /** What the host decided (on guests: the copy the host sent). */
    public static final class Settings {
        public int maxPlayers = 10, pausePerm = EVERYONE, speedPerm = ADMINS, kickPerm = ADMINS;
        public boolean pings = true, votePause = true, voteSpeed = true, neverPause = true, npcSync = true, sharedFF;
        public float ffMult = 3f;
        /** 0.9.2.5: PvP (players attacking players) - on; new players protected 30 in-game days; not while docked; 1 day per target. */
        public boolean pvp = true, pvpDocked = false;
        public int pvpProtectDays = 30, pvpCooldownDays = 1;
        public final Set<String> admins = new LinkedHashSet<>();
    }

    private static volatile Settings remote; // guests: last from the host
    private static volatile boolean sharedFF; // host: shared fast-forward running
    private static long lastSendNs;
    private static volatile boolean dirty = true;

    // ------------------------------------------------------------------ settings
    /** The settings in force: the host's own (from its prefs) or what the host sent us. */
    public static Settings current() {
        NetManager n = StarMPPlugin.net();
        if (n != null && !n.isHost() && n.linked() && remote != null) return remote;
        return local();
    }

    /** Guests: the last settings the host sent, or null if none arrived yet. */
    public static Settings remoteSettings() { return remote; }

    /** This PC's host settings (used when we host). */
    public static Settings local() {
        Settings s = new Settings();
        s.maxPlayers = clamp(intPref("hsMax", 10), 2, 10);
        s.pausePerm = clamp(intPref("hsPausePerm", EVERYONE), 0, 2);
        s.speedPerm = clamp(intPref("hsSpeedPerm", ADMINS), 0, 2);
        s.kickPerm = clamp(intPref("hsKickPerm", ADMINS), 0, 1);
        s.pings = Prefs.getBool("hsPings", true);
        s.votePause = Prefs.getBool("hsVotePause", true);
        s.voteSpeed = Prefs.getBool("hsVoteSpeed", true);
        s.neverPause = starmp.campaign.BackgroundSim.enabled();
        s.npcSync = starmp.campaign.NpcSync.enabled();
        s.sharedFF = sharedFF;
        s.pvp = Prefs.getBool("hsPvp", true);
        s.pvpDocked = Prefs.getBool("hsPvpDocked", false);
        s.pvpProtectDays = clamp(intPref("hsPvpProtect", 30), 0, 365);
        s.pvpCooldownDays = clamp(intPref("hsPvpCooldown", 1), 0, 365);
        try { s.ffMult = Global.getSettings().getFloat("campaignSpeedupMult"); } catch (Throwable t) { s.ffMult = 3f; }
        for (String a : Prefs.get("hsAdmins", "").split(",")) if (!a.trim().isEmpty()) s.admins.add(a.trim().toLowerCase(Locale.ROOT));
        return s;
    }

    private static int next(int cur, int... steps) { for (int i = 0; i < steps.length; i++) if (steps[i] == cur) return steps[(i + 1) % steps.length]; return steps[0]; }
    private static int intPref(String k, int def) { try { return Integer.parseInt(Prefs.get(k, String.valueOf(def)).trim()); } catch (Throwable t) { return def; } }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Host: a setting changed - send it now. */
    public static void changed() { dirty = true; }

    public static String permLabel(int p) { return p == HOST_ONLY ? "host only" : p == ADMINS ? "host + admins" : "everyone"; }
    /** 0.9.0.1: "Only the host can pause" / "Only the host and admins can pause". */
    public static String onlyWho(int p) { return p == HOST_ONLY ? "Only the host" : "Only the host and admins"; }

    public static void setAdmin(String name, boolean admin) {
        Settings s = local();
        String k = name.trim().toLowerCase(Locale.ROOT);
        if (admin) s.admins.add(k); else s.admins.remove(k);
        Prefs.put("hsAdmins", String.join(",", s.admins));
        changed();
        StarMPPlugin.flog("session: " + name + (admin ? " is now an admin" : " is no longer an admin"));
    }

    /** Host settings shown as rows (title-screen panel and F8): keys in order. */
    public static final String[] HOST_ROWS = {"max", "pause", "votePause", "speed", "voteSpeed", "kick", "pings", "neverPause", "npcSync", "hyper", "pvp", "pvpProtect", "pvpDocked", "pvpCooldown"};

    public static String rowLabel(String k) {
        Settings s = current();
        switch (k) {
            case "max": return "Max players: " + s.maxPlayers;
            case "pause": return "Who can pause: " + permLabel(s.pausePerm);
            case "votePause": return "Others can vote to pause: " + (s.votePause ? "ON" : "OFF");
            case "speed": return "Who can fast-forward everyone: " + permLabel(s.speedPerm);
            case "voteSpeed": return "Others can vote to fast-forward: " + (s.voteSpeed ? "ON" : "OFF");
            case "kick": return "Who can kick players: " + permLabel(s.kickPerm);
            case "pings": return "Map pings: " + (s.pings ? "ON" : "OFF");
            case "neverPause": return "Never pause (world keeps running in menus/dialogs): " + (s.neverPause ? "ON" : "OFF");
            case "npcSync": return "Shared NPC fleets (host-authoritative): " + (s.npcSync ? "ON" : "OFF");
            case "hyper": return "Hyperspace fleet speed: x" + starmp.campaign.HyperSpeed.localSetting();
            case "pvp": return "PvP (players can attack players): " + (s.pvp ? "ON" : "OFF");
            case "pvpProtect": return "PvP protection for new players: " + (s.pvpProtectDays == 0 ? "none" : s.pvpProtectDays + " in-game days");
            case "pvpDocked": return "PvP against docked players: " + (s.pvpDocked ? "allowed" : "not allowed");
            case "pvpCooldown": return "PvP cooldown per target: " + (s.pvpCooldownDays == 0 ? "none" : s.pvpCooldownDays + " in-game day" + (s.pvpCooldownDays == 1 ? "" : "s"));
        }
        return k;
    }

    /** Host: next value of a setting row. */
    public static void cycleRow(String k) {
        Settings s = local();
        switch (k) {
            case "max": Prefs.put("hsMax", String.valueOf(s.maxPlayers >= 10 ? 2 : s.maxPlayers + 1)); break;
            case "pause": Prefs.put("hsPausePerm", String.valueOf((s.pausePerm + 2) % 3)); break; // everyone -> admins -> host -> everyone
            case "speed": Prefs.put("hsSpeedPerm", String.valueOf((s.speedPerm + 2) % 3)); break;
            case "kick": Prefs.put("hsKickPerm", String.valueOf(s.kickPerm == ADMINS ? HOST_ONLY : ADMINS)); break;
            case "votePause": Prefs.put("hsVotePause", String.valueOf(!s.votePause)); break;
            case "voteSpeed": Prefs.put("hsVoteSpeed", String.valueOf(!s.voteSpeed)); break;
            case "pings": Prefs.put("hsPings", String.valueOf(!s.pings)); break;
            case "neverPause": Prefs.put("neverPause", String.valueOf(!s.neverPause)); break;
            case "npcSync": Prefs.put("npcSync", String.valueOf(!s.npcSync)); break;
            case "hyper": starmp.campaign.HyperSpeed.cycle(); break;
            case "pvp": Prefs.put("hsPvp", String.valueOf(!s.pvp)); break;
            case "pvpDocked": Prefs.put("hsPvpDocked", String.valueOf(!s.pvpDocked)); break;
            case "pvpProtect": Prefs.put("hsPvpProtect", String.valueOf(next(s.pvpProtectDays, 0, 10, 30, 60))); break;
            case "pvpCooldown": Prefs.put("hsPvpCooldown", String.valueOf(next(s.pvpCooldownDays, 0, 1, 3, 7))); break;
        }
        changed();
    }

    // ------------------------------------------------------------------ permissions
    public static boolean isAdmin(String name) { return name != null && current().admins.contains(name.trim().toLowerCase(Locale.ROOT)); }

    private static String nameOf(int peer) {
        NetManager n = StarMPPlugin.net();
        if (n != null && peer == n.myId()) return n.playerName();
        if (n != null && n.peer(peer) != null) return n.peer(peer).name;
        return starmp.combat.CoopState.nameOf(n, peer);
    }

    private static int level(Perm p) { Settings s = current(); return p == Perm.PAUSE ? s.pausePerm : p == Perm.SPEED ? s.speedPerm : s.kickPerm; }

    /** May this player do it directly? */
    public static boolean can(int peer, Perm p) {
        if (peer == 0) return true; // the host
        int lv = level(p);
        return lv == EVERYONE || (lv == ADMINS && isAdmin(nameOf(peer)));
    }

    public static boolean myCan(Perm p) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) return true;
        return can(n.myId(), p);
    }

    public static boolean voteAllowed(int kind) {
        Settings s = current();
        return kind == V_PAUSE || kind == V_UNPAUSE ? s.votePause : s.voteSpeed;
    }

    public static boolean sharedFastForward() {
        NetManager n = StarMPPlugin.net();
        return n != null && n.linked() && current().sharedFF;
    }

    public static String roleOf(int peer) { return peer == 0 ? "host" : isAdmin(nameOf(peer)) ? "admin" : "player"; }

    // ------------------------------------------------------------------ kick
    private static final Set<String> kicked = new HashSet<>();      // host: names refused this session
    private static final Map<Integer, Long> kickSentNs = new HashMap<>();
    public static volatile String kickedNotice; // guest: why we were removed (shown on screen)
    public static volatile boolean wasKicked;

    /** Kick a player (host does it; anyone else with permission asks the host). */
    public static void kick(int target) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked() || target == 0 || target == n.myId()) return;
        if (!n.isHost()) {
            if (!myCan(Perm.KICK)) return;
            n.sendToHost(new byte[]{KICK, 1, (byte) target});
            return;
        }
        hostKick(n, target, nameOf(n.myId()), "kicked by " + n.playerName());
    }

    private static void hostKick(NetManager n, int target, String by, String reason) {
        String name = nameOf(target);
        if (name != null) kicked.add(name.trim().toLowerCase(Locale.ROOT));
        sendKick(n, target, reason);
        StarMPPlugin.flog("session: kicked " + name + " (player " + target + ") - " + reason);
        notifyAll(n, name + " was removed from the game (" + reason + ")");
    }

    private static void sendKick(NetManager n, int target, String reason) {
        kickSentNs.put(target, System.nanoTime());
        n.sendToPeer(target, cat(new byte[]{KICK, 0}, str(reason)));
    }

    /** Host: drop everything from a kicked player. */
    public static boolean refused(int peer) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.isHost() || peer == 0) return false;
        NetManager.Peer p = n.peer(peer);
        return p != null && kicked.contains(p.name.trim().toLowerCase(Locale.ROOT));
    }

    public static void clearKicks() { kicked.clear(); kickSentNs.clear(); }

    /** Host: will this newly connected player be turned away (kicked earlier / game full)? Then no save is sent. */
    public static boolean wouldRefuse(NetManager n, int peer) {
        if (refused(peer)) return true;
        int lower = 0;
        for (int id : n.peerIds()) if (id < peer && !refused(id)) lower++;
        return lower >= local().maxPlayers - 1;
    }

    // ------------------------------------------------------------------ votes
    private static final class Vote { int id, kind, from; long endNs; final Set<Integer> yes = new HashSet<>(), no = new HashSet<>(); }
    private static Vote hostVote;
    private static final java.util.concurrent.ConcurrentLinkedQueue<int[]> hostQ = new java.util.concurrent.ConcurrentLinkedQueue<>();                  // host: the running vote
    public static volatile int openId, openKind, openFrom = -1; // everyone: the vote on screen
    public static volatile long openEndMs;
    public static volatile boolean myBallotCast;
    private static int nextVoteId = 1;

    public static String voteText(int kind) {
        switch (kind) {
            case V_PAUSE: return "pause the game";
            case V_UNPAUSE: return "unpause the game";
            case V_FF_ON: return "fast-forward for everyone";
            default: return "stop fast-forward";
        }
    }

    /** Ask for an action: done directly when allowed, else a vote (if votes are on). Returns a line for the player. */
    public static String request(int kind) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) return null;
        if (n.isHost()) { hostRequest(n, 0, kind); return null; }
        n.sendToHost(new byte[]{VOTE, 0, (byte) kind});
        Perm p = kind == V_PAUSE || kind == V_UNPAUSE ? Perm.PAUSE : Perm.SPEED;
        if (myCan(p)) return null;
        return voteAllowed(kind) ? "StarMP: vote started - " + voteText(kind) : "StarMP: only " + permLabel(level(p)) + " can " + voteText(kind) + " (votes are off)";
    }

    public static void ballot(boolean yes) {
        NetManager n = StarMPPlugin.net();
        if (n == null || openId == 0 || myBallotCast) return;
        myBallotCast = true;
        byte[] b = ByteBuffer.allocate(7).put(VOTE).put((byte) 2).putInt(openId).put((byte) (yes ? 1 : 0)).array();
        if (n.isHost()) onBallot(n, 0, openId, yes); else n.sendToHost(b);
    }

    private static void hostRequest(NetManager n, int from, int kind) {
        Perm p = kind == V_PAUSE || kind == V_UNPAUSE ? Perm.PAUSE : Perm.SPEED;
        if (can(from, p)) { apply(n, kind, nameOf(from)); return; }
        if (!voteAllowed(kind) || hostVote != null) { StarMPPlugin.flog("session: request " + kind + " from " + from + " refused (" + (hostVote != null ? "a vote is running" : "votes off") + ")"); return; }
        Vote v = new Vote();
        v.id = nextVoteId++; v.kind = kind; v.from = from; v.endNs = System.nanoTime() + 20_000_000_000L;
        v.yes.add(from);
        hostVote = v;
        byte[] name = str(nameOf(from));
        ByteBuffer b = ByteBuffer.allocate(8 + name.length).put(VOTE).put((byte) 1).putInt(v.id).put((byte) kind).put((byte) from);
        b.put(name);
        n.broadcast(b.array());
        onOpen(v.id, kind, from, nameOf(from));
        if (from == 0) myBallotCast = true;
        StarMPPlugin.flog("session: vote " + v.id + " opened by " + nameOf(from) + ": " + voteText(kind));
    }

    private static void onBallot(NetManager n, int from, int id, boolean yes) {
        Vote v = hostVote;
        if (v == null || v.id != id) return;
        (yes ? v.yes : v.no).add(from);
        (yes ? v.no : v.yes).remove(from);
    }

    private static void hostVoteTick(NetManager n) {
        Vote v = hostVote;
        if (v == null) return;
        int voters = 1 + n.peerIds().size();
        v.yes.retainAll(voters(n)); v.no.retainAll(voters(n));
        boolean passed = v.yes.size() * 2 > voters;
        boolean failed = !passed && (v.no.size() * 2 >= voters || System.nanoTime() > v.endNs);
        if (!passed && !failed) return;
        hostVote = null;
        ByteBuffer b = ByteBuffer.allocate(9).put(VOTE).put((byte) 3).putInt(v.id).put((byte) (passed ? 1 : 0)).put((byte) v.yes.size()).put((byte) voters);
        n.broadcast(b.array());
        onClose(v.id, passed, v.yes.size(), voters);
        StarMPPlugin.flog("session: vote " + v.id + " " + (passed ? "passed" : "failed") + " (" + v.yes.size() + " yes / " + voters + ")");
        if (passed) apply(n, v.kind, "vote");
    }

    private static Set<Integer> voters(NetManager n) { Set<Integer> s = new HashSet<>(n.peerIds()); s.add(0); return s; }

    /** Host carries out an allowed / voted action. */
    private static void apply(NetManager n, int kind, String by) {
        if (kind == V_FF_ON || kind == V_FF_OFF) {
            sharedFF = kind == V_FF_ON;
            changed();
            notifyAll(n, "fast-forward for everyone " + (sharedFF ? "ON" : "OFF") + " (" + by + ")");
            return;
        }
        SectorAPI sector = null;
        try { sector = Global.getSector(); } catch (Throwable ignored) {}
        if (sector == null || sector.getPlayerFleet() == null) return;
        starmp.campaign.CoopCampaign.hostSetPause(kind == V_PAUSE, by);
    }

    private static void onOpen(int id, int kind, int from, String name) {
        openId = id; openKind = kind; openFrom = from; openEndMs = System.currentTimeMillis() + 20_000L; myBallotCast = false;
        openText = name + " wants to " + voteText(kind);
        voteSeq++;
    }

    private static void onClose(int id, boolean passed, int yes, int total) {
        if (openId != id) return;
        String txt = "Vote to " + voteText(openKind) + (passed ? " passed" : " failed") + " (" + yes + " of " + total + " yes)";
        openId = 0;
        notices.add(txt);
        voteSeq++;
    }

    /** UI polls these: voteSeq changes whenever a vote opens or closes; openId != 0 while one is open. */
    public static volatile int voteSeq;
    public static volatile String openText = "";

    // ------------------------------------------------------------------ rename (0.9.0.0)
    public static final long RENAME_COOLDOWN_MS = 5 * 60_000L;
    private static final Map<Integer, Long> renamedAtMs = new HashMap<>(); // host: per player
    public static volatile String renameNotice;

    /** Milliseconds until this PC may rename again (0 = now). */
    public static long renameWaitMs() {
        long last; try { last = Long.parseLong(Prefs.get("lastRenameMs", "0")); } catch (Throwable t) { last = 0; }
        return Math.max(0, last + RENAME_COOLDOWN_MS - System.currentTimeMillis());
    }

    /** Problem with a new name, or null if it is fine. */
    public static String nameProblem(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "the name is empty";
        if (n.length() > 24) return "24 characters at most";
        for (char c : n.toCharArray()) if (c < 32 || c == ',' || c == '\t') return "the name has a character that can't be used";
        return null;
    }

    /** Change my player name. Linked: the host checks it and tells everyone; it moves my saved character too. */
    public static String rename(String newName) {
        NetManager n = StarMPPlugin.net();
        String p = nameProblem(newName);
        if (p != null) return p;
        if (renameWaitMs() > 0) return "you can change your name again in " + (renameWaitMs() / 1000) + " s";
        newName = newName.trim();
        if (n == null || !n.linked()) { applyMyName(newName); return null; }
        if (n.isHost()) { hostQ.add(new int[]{3, 0}); pendingHostName = newName; return null; }
        n.sendToHost(cat(new byte[]{RENAME, 0}, str(newName)));
        return null;
    }
    private static volatile String pendingHostName;
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> renameQ = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void applyMyName(String name) {
        NetManager n = StarMPPlugin.net();
        Prefs.put("name", name);
        Prefs.put("lastRenameMs", String.valueOf(System.currentTimeMillis()));
        if (n != null) n.setName(name);
        starmp.campaign.CoopCampaign.helloNow = true;
        StarMPPlugin.flog("session: my name is now " + name);
    }

    /** Host, game thread: carry out a rename for player `peer` (0 = me). */
    private static void hostRename(NetManager n, int peer, String newName) {
        String old = nameOf(peer);
        String why = nameProblem(newName);
        Long last = renamedAtMs.get(peer);
        if (why == null && peer != 0 && last != null && System.currentTimeMillis() - last < RENAME_COOLDOWN_MS) why = "you can change your name once every 5 minutes";
        if (why == null) for (int id : n.peerIds()) if (id != peer && nameOf(id).trim().equalsIgnoreCase(newName)) why = "another player is called that";
        if (why == null && peer != 0 && n.playerName().trim().equalsIgnoreCase(newName)) why = "another player is called that";
        boolean inGame = false; try { inGame = Global.getSector() != null && Global.getSector().getPlayerFleet() != null; } catch (Throwable ignored) {}
        if (why == null && inGame && !starmp.campaign.PlayerRecords.nameFree(old, newName)) why = "a saved character in this game already has that name";
        if (why != null) {
            if (peer == 0) renameNotice = "Name not changed: " + why;
            else n.sendToPeer(peer, cat(new byte[]{RENAME, 2}, str(why)));
            return;
        }
        renamedAtMs.put(peer, System.currentTimeMillis());
        if (inGame) starmp.campaign.PlayerRecords.renameRecord(old, newName);
        try { starmp.campaign.Relations.renamed(old, newName); } catch (Throwable ignored) {} // 0.9.2.1
        if (isAdmin(old)) { setAdmin(old, false); setAdmin(newName, true); }
        if (peer == 0) applyMyName(newName); else n.renamePeer(peer, newName);
        starmp.combat.CoopState.knownNames.put(peer, newName);
        n.broadcast(cat(cat(new byte[]{RENAME, 1, (byte) peer}, str(old)), str(newName)));
        notifyAll(n, old + " is now called " + newName);
        if (inGame) starmp.campaign.AutoSave.request("rename"); // the moved record goes into the save
    }

    // ------------------------------------------------------------------ network
    /** Every frame wherever we are (title, campaign, combat). */
    public static void tick(NetManager n) {
        if (n == null) return;
        if (!n.linked()) { remote = null; if (openId != 0) { openId = 0; } if (n.state() == NetManager.State.IDLE) { hostVote = null; sharedFF = false; } return; }
        if (!n.isHost()) return;
        long now = System.nanoTime();
        // player limit + people who were kicked
        Settings s = local();
        List<Integer> ids = new ArrayList<>(n.peerIds());
        Collections.sort(ids);
        int allowed = s.maxPlayers - 1, counted = 0;
        for (int id : ids) {
            NetManager.Peer p = n.peer(id);
            boolean banned = p != null && kicked.contains(p.name.trim().toLowerCase(Locale.ROOT));
            boolean full = !banned && ++counted > allowed;
            if (!banned && !full) continue;
            Long last = kickSentNs.get(id);
            if (last != null && now - last < 3_000_000_000L) continue;
            sendKick(n, id, banned ? "you were removed from this game" : "the game is full (" + s.maxPlayers + " players)");
            if (last == null) StarMPPlugin.flog("session: refused player " + id + " (" + (banned ? "kicked earlier" : "game full") + ")");
        }
        int[] q;
        while ((q = hostQ.poll()) != null) { // packets arrive on the network thread; act on the game thread
            if (q[0] == 0) hostRequest(n, q[1], q[2]);
            else if (q[0] == 1) { if (can(q[1], Perm.KICK) && q[2] != 0) hostKick(n, q[2], nameOf(q[1]), "kicked by " + nameOf(q[1])); }
            else if (q[0] == 2) onBallot(n, q[1], q[2], q[3] != 0);
            else if (q[0] == 3 && pendingHostName != null) { String nm = pendingHostName; pendingHostName = null; hostRename(n, 0, nm); }
        }
        Object[] rq;
        while ((rq = renameQ.poll()) != null) hostRename(n, (Integer) rq[0], (String) rq[1]);
        {
        }
        hostVoteTick(n);
        if (dirty || now - lastSendNs > 5_000_000_000L) {
            dirty = false; lastSendNs = now;
            n.broadcast(encode(s));
        }
    }

    public static void onPacket(int peer, byte[] d) {
        NetManager n = StarMPPlugin.net();
        if (n == null || d.length < 2) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(d); byte t = b.get(); int op = b.get();
            if (t == SETTINGS) { if (!n.isHost() && peer == 0) { b.position(1); remote = decode(b); } return; }
            if (t == KICK) {
                if (op == 0 && !n.isHost() && peer == 0) {
                    String why = gs(b);
                    kickedNotice = "Removed from the game: " + why; wasKicked = true;
                    StarMPPlugin.flog("session: the host removed us - " + why);
                    n.disconnect();
                } else if (op == 1 && n.isHost()) {
                    int target = b.get() & 0xFF;
                    hostQ.add(new int[]{1, peer, target});
                } else if (op == 2) { /* reserved */ }
                return;
            }
            if (t == RENAME) {
                if (op == 0 && n.isHost()) renameQ.add(new Object[]{peer, gs(b)});
                else if (op == 1 && !n.isHost() && peer == 0) {
                    int who = b.get() & 0xFF; String old = gs(b), nu = gs(b);
                    if (who == n.myId()) applyMyName(nu);
                    else { n.renamePeer(who, nu); starmp.combat.CoopState.knownNames.put(who, nu); }
                } else if (op == 2 && !n.isHost() && peer == 0) renameNotice = "Name not changed: " + gs(b);
                return;
            }
            if (t == VOTE) {
                switch (op) {
                    case 0: if (n.isHost()) hostQ.add(new int[]{0, peer, b.get() & 0xFF}); break;
                    case 1: if (!n.isHost() && peer == 0) { int id = b.getInt(), kind = b.get() & 0xFF, from = b.get() & 0xFF; String nm = gs(b); onOpen(id, kind, from, nm); if (from == n.myId()) myBallotCast = true; } break;
                    case 2: if (n.isHost()) { int id = b.getInt(); hostQ.add(new int[]{2, peer, id, b.get()}); } break;
                    case 3: if (!n.isHost() && peer == 0) { int id = b.getInt(); boolean ok = b.get() != 0; int yes = b.get() & 0xFF, tot = b.get() & 0xFF; onClose(id, ok, yes, tot); } break;
                    case 4: if (!n.isHost() && peer == 0) notices.add(gs(b)); break;
                }
            }
        } catch (Throwable e) { StarMPPlugin.flog("session: bad packet " + d[0] + " from " + peer + ": " + e); }
    }

    /** Lines to show players (kicks, fast-forward changes), drained by the campaign / title screen. */
    public static final java.util.concurrent.ConcurrentLinkedQueue<String> notices = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void notifyAll(NetManager n, String text) {
        notices.add(text);
        n.broadcast(cat(new byte[]{VOTE, 4}, str(text)));
    }

    private static byte[] encode(Settings s) {
        int flags = (s.pings ? 1 : 0) | (s.votePause ? 2 : 0) | (s.voteSpeed ? 4 : 0) | (s.neverPause ? 8 : 0) | (s.npcSync ? 16 : 0) | (s.sharedFF ? 32 : 0)
                | (s.pvp ? 64 : 0) | (s.pvpDocked ? 128 : 0);
        List<byte[]> names = new ArrayList<>(); int len = 0;
        for (String a : s.admins) { byte[] x = str(a); names.add(x); len += x.length; }
        ByteBuffer b = ByteBuffer.allocate(20 + len);
        b.put(SETTINGS).put((byte) 1).put((byte) s.maxPlayers).put((byte) flags).put((byte) s.pausePerm).put((byte) s.speedPerm).put((byte) s.kickPerm)
         .putFloat(s.ffMult).put((byte) names.size());
        for (byte[] x : names) b.put(x);
        b.putShort((short) s.pvpProtectDays).putShort((short) s.pvpCooldownDays); // 0.9.2.5
        return Arrays.copyOf(b.array(), b.position());
    }

    private static Settings decode(ByteBuffer b) {
        Settings s = new Settings();
        b.get(); // version
        s.maxPlayers = b.get() & 0xFF; int f = b.get() & 0xFF;
        s.pings = (f & 1) != 0; s.votePause = (f & 2) != 0; s.voteSpeed = (f & 4) != 0; s.neverPause = (f & 8) != 0; s.npcSync = (f & 16) != 0; s.sharedFF = (f & 32) != 0;
        s.pvp = (f & 64) != 0; s.pvpDocked = (f & 128) != 0;
        s.pausePerm = b.get(); s.speedPerm = b.get(); s.kickPerm = b.get(); s.ffMult = b.getFloat();
        int na = b.get() & 0xFF;
        for (int i = 0; i < na; i++) s.admins.add(gs(b));
        if (b.remaining() >= 4) { s.pvpProtectDays = b.getShort(); s.pvpCooldownDays = b.getShort(); }
        return s;
    }

    static byte[] str(String s) {
        byte[] u = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (u.length > 1000) u = Arrays.copyOf(u, 1000);
        return ByteBuffer.allocate(2 + u.length).putShort((short) u.length).put(u).array();
    }
    static String gs(ByteBuffer b) { int l = b.getShort() & 0xFFFF; byte[] u = new byte[l]; b.get(u); return new String(u, StandardCharsets.UTF_8); }
    static byte[] cat(byte[] a, byte[] c) { byte[] r = Arrays.copyOf(a, a.length + c.length); System.arraycopy(c, 0, r, a.length, c.length); return r; }
}
