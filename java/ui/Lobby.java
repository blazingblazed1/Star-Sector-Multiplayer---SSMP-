package starmp.ui;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import starmp.NetManager;
import starmp.StarMPPlugin;

/** Lobby state shared through small LOBBY / START packets. */
public final class Lobby {
    private Lobby() {}
    public static final byte LOBBY = 6, START = 7;

    public static final class Member { public String name = ""; public boolean ready; public String version = ""; public String mods = ""; public long seenNs = System.nanoTime(); }

    private static String modList;
    /** Enabled mods (id + version), sorted: compared between players in the lobby (0.1.4.7). */
    public static String modList() {
        if (modList != null) return modList;
        List<String> l = new ArrayList<>();
        try { for (com.fs.starfarer.api.ModSpecAPI m : com.fs.starfarer.api.Global.getSettings().getModManager().getEnabledModsCopy()) l.add(m.getId() + " " + m.getVersion()); } catch (Throwable ignored) {}
        Collections.sort(l);
        return modList = String.join(", ", l);
    }

    /** Remote members by peer id. */
    public static final ConcurrentHashMap<Integer, Member> members = new ConcurrentHashMap<>();
    public static volatile boolean myReady;
    public static volatile String startedWhat = null; // set when the host pressed Start
    /** Id of the host's current Start (0.1.4.6): a guest who went back to the lobby is not pushed to the STARTED page
     * again by the host's 1 s re-broadcast of the same Start - only by a new one. 0 = unknown (older host). */
    public static volatile int startId, dismissedStartId = -1;
    /** Host: a campaign save exists and can be sent to a (re)connecting guest. */
    public static volatile boolean hostCampaignReady = false;
    private static long lastSendNs, lastStartRepeatNs;

    public static byte[] encodeMember(String name, boolean ready) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8), v = StarMPPlugin.VERSION.getBytes(StandardCharsets.UTF_8);
        byte[] md = modList().getBytes(StandardCharsets.UTF_8); if (md.length > 60000) md = Arrays.copyOf(md, 60000);
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 2 + n.length + 2 + v.length + 2 + md.length);
        b.put(LOBBY); b.put((byte) (ready ? 1 : 0));
        b.putShort((short) n.length); b.put(n); b.putShort((short) v.length); b.put(v);
        b.putShort((short) md.length); b.put(md); // appended in 0.1.4.7; older builds ignore it
        return b.array();
    }

    public static void onPacket(int peer, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d);
        byte t = b.get();
        if (t == LOBBY) {
            Member m = new Member();
            m.ready = b.get() != 0;
            byte[] n = new byte[b.getShort() & 0xFFFF]; b.get(n); m.name = new String(n, StandardCharsets.UTF_8);
            byte[] v = new byte[b.getShort() & 0xFFFF]; b.get(v); m.version = new String(v, StandardCharsets.UTF_8);
            if (b.remaining() >= 2) { byte[] md = new byte[b.getShort() & 0xFFFF]; b.get(md); m.mods = new String(md, StandardCharsets.UTF_8); }
            members.put(peer, m);
        } else if (t == START) {
            byte[] w = new byte[b.getShort() & 0xFFFF]; b.get(w);
            String what = new String(w, StandardCharsets.UTF_8);
            int id = b.remaining() >= 4 ? b.getInt() : 0;
            if (what.isEmpty()) { // host went back to the lobby: everyone does
                if (startedWhat != null) StarMPPlugin.flog("lobby: host returned to the lobby");
                startedWhat = null; startId = 0; myReady = false; return;
            }
            if (!what.equals(startedWhat) || id != startId) StarMPPlugin.flog("lobby: host started: " + what + " (#" + id + ")");
            startedWhat = what; startId = id;
        }
    }

    public static byte[] encodeStart(String what) {
        byte[] w = (what == null ? "" : what).getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(1 + 2 + w.length + 4);
        b.put(START); b.putShort((short) w.length); b.put(w); b.putInt(startId);
        return b.array();
    }

    /** Host: a new Start (fresh id). */
    public static void hostStart(NetManager net, String what) {
        startedWhat = what; startId = 1 + (int) (Math.random() * 2_000_000_000);
        net.broadcast(encodeStart(what));
    }

    /** Back to the lobby. Host: ends the Start for everyone. Guest: just this player (until the host starts again). */
    public static void backToLobby(NetManager net) {
        myReady = false;
        if (net != null && net.isHost()) {
            startedWhat = null; hostCampaignReady = false;
            net.broadcast(encodeStart(""));
            StarMPPlugin.flog("lobby: host went back to the lobby - ended the start for everyone");
        } else {
            dismissedStartId = startId;
            StarMPPlugin.flog("lobby: back to the lobby (start #" + startId + " dismissed)");
        }
        sendNow(net);
    }

    /** Periodic: send my lobby state, drop members that left. */
    public static void tick(NetManager net) {
        boolean linked = (net.isHost() && net.state() == NetManager.State.HOSTING) || net.state() == NetManager.State.CONNECTED;
        if (!linked) {
            // A brief drop with an auto-reconnect in progress must NOT wipe the campaign state, or the guest falls
            // back to the co-op-battle screen and gets sent to a skirmish instead of loading the shared save.
            if (net.reconnecting()) return;
            // 0.1.4.11: a guest that left and joins again must get the host's game screen again, even if it had
            // pressed LOBBY on that same Start before leaving (the dismissed id used to survive the disconnect)
            if (!net.isHost()) { dismissedStartId = -1; startId = 0; }
            members.clear(); myReady = false; startedWhat = null; hostCampaignReady = false; return;
        }
        if (net.isHost()) {
            members.keySet().retainAll(net.peerIds());
            serviceHostReconnects(net);
        } else { // other guests reach us through the host: keep them while their lobby state keeps coming
            long t = System.nanoTime();
            members.entrySet().removeIf(e -> e.getKey() != 0 && t - e.getValue().seenNs > 5_000_000_000L);
            members.keySet().removeIf(id -> id == 0 && !net.peerIds().contains(0));
        }
        long now = System.nanoTime();
        if (now - lastSendNs < 1_000_000_000L) return;
        lastSendNs = now;
        byte[] d = encodeMember(net.playerName(), myReady);
        if (net.isHost()) {
            net.broadcast(d);
            if (startedWhat != null) net.broadcast(encodeStart(startedWhat)); // keep late/reconnecting guests on the right page
        } else net.sendToHost(d);
    }

    public static void sendNow(NetManager net) { lastSendNs = 0; tick(net); }

    /** Host, callable at the title screen and in-campaign: resend campaign state + save to any guest that just
     * (re)connected, so a brief drop never leaves them stranded on the wrong screen or without the save. */
    public static void serviceHostReconnects(NetManager net) {
        if (!net.isHost()) return;
        // 0.1.4.11: in-campaign the title-screen re-broadcast doesn't run; repeat the Start every 2 s from here so a
        // guest whose one-off Start got lost (or arrived mid-load) still gets its game screen
        long now = System.nanoTime();
        if (startedWhat != null && net.state() == NetManager.State.HOSTING && !net.peerIds().isEmpty() && now - lastStartRepeatNs > 2_000_000_000L
                && com.fs.starfarer.api.Global.getCurrentState() != com.fs.starfarer.api.GameState.TITLE) {
            lastStartRepeatNs = now;
            net.broadcast(encodeStart(startedWhat));
        }
        Integer p;
        while ((p = net.connectedPeers.poll()) != null) {
            if (starmp.Session.wouldRefuse(net, p)) { StarMPPlugin.flog("lobby: not sending the game to player " + p + " (refused)"); continue; } // 0.9.0.0
            if (startedWhat != null) net.sendTo(p, encodeStart(startedWhat));
            if (hostCampaignReady) { net.sendSaveTo(p, ""); StarMPPlugin.flog("lobby: resent campaign state + save to (re)connected player " + p); }
        }
    }
}
