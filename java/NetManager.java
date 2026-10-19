package starmp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the smnet helper process and turns its JSON-line events into callbacks. */
public final class NetManager {
    public enum State { IDLE, STARTING, HOSTING, JOINING, CONNECTED }

    public interface Listener {
        void onState(State s);
        void onLog(String line);
        void onPeers(Map<Integer, Peer> peers);
        void onCode(String code);
    }

    public interface MessageListener { void onMessage(int peer, byte[] data); }

    public static final class Peer {
        public final int id; public final String name; public volatile int pingMs = -1;
        /** 0.1.5.3: which network path the link uses ("direct", "relay") and QUIC's own round-trip estimate. */
        public volatile String path = "?"; public volatile int quicRttMs = -1;
        Peer(int id, String name) { this.id = id; this.name = name; }
    }

    private final String exe;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<MessageListener> msgListeners = new CopyOnWriteArrayList<>();
    private volatile int localId = 0;
    private final Map<Integer, Peer> peers = Collections.synchronizedMap(new TreeMap<Integer, Peer>());
    private Process proc;
    private OutputStream toProc;
    private volatile String helperStatus = "not started yet";
    private volatile State state = State.IDLE;
    private volatile boolean host;
    private volatile String code = "";
    /** Guest auto-reconnect: the last code we joined with, and whether the player left on purpose. */
    private volatile String lastJoinCode;
    private volatile boolean userLeft;
    private volatile long rejoinUntilMs;
    private volatile Thread rejoinThread;
    public volatile String reconnectStatus = "";
    private String playerName = "Player";
    // save transfer state (shown by the menu)
    public volatile String newestSave = "", saveStatus = "", receivedSave = "";
    /** 0.9.1.3: a save is being received right now - its files may be half written, so nothing may load it yet. */
    public volatile boolean receivingSave;
    /** 0.1.5.9: other StarMP games on this PC using the same saves folder (multibox from one install). */
    public volatile int sharedSaves;
    public static final String SHARED_SAVES_WARNING = "Another Starsector on this PC uses this same saves folder - saves will clash. Start extra windows from the Multibox copies.";

    public NetManager(String exe) { this.exe = exe; }

    public void addListener(Listener l) { listeners.add(l); }
    public void addMessageListener(MessageListener l) { msgListeners.add(l); }
    public int localId() { return localId; }
    public java.util.List<Integer> peerIds() { synchronized (peers) { return new java.util.ArrayList<>(peers.keySet()); } }
    public State state() { return state; }
    public boolean isHost() { return host; }
    public String code() { return code; }
    public String helperStatus() { return helperStatus; }
    public String helperPath() { return exe; }

    private void log(String s) {
        StarMPPlugin.flog(s);
        for (Listener l : listeners) l.onLog(s);
    }

    private void setState(State s) {
        State old = state;
        state = s;
        if (!host && old == State.CONNECTED && s == State.IDLE && !userLeft && lastJoinCode != null) startRejoin();
        if (s == State.CONNECTED) { rejoinUntilMs = 0; reconnectStatus = ""; }
        for (Listener l : listeners) l.onState(s);
    }

    private void firePeers() {
        Map<Integer, Peer> copy;
        synchronized (peers) { copy = new TreeMap<>(peers); }
        for (Listener l : listeners) l.onPeers(copy);
    }

    private synchronized void ensureProcess() throws IOException {
        if (proc != null && proc.isAlive()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // zip extraction can drop the executable bit; java.io.File.setExecutable is blocked, so use chmod
            try { new ProcessBuilder("chmod", "+x", exe).start().waitFor(); } catch (Throwable ignored) {}
        }
        try {
            proc = new ProcessBuilder(exe).start();
        } catch (IOException e) {
            helperStatus = "MISSING or cannot run (" + e.getMessage() + ")";
            throw new IOException("Cannot run network helper " + exe + ": " + e.getMessage());
        }
        helperStatus = "running";
        final Process p = proc;
        toProc = p.getOutputStream();
        refreshSaves();
        Thread out = new Thread(() -> readEvents(p), "StarMP-events");
        out.setDaemon(true); out.start();
        Thread err = new Thread(() -> drainErr(p), "StarMP-stderr");
        err.setDaemon(true); err.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 0.1.5.3: tell the helper whether this is a normal exit (it deletes the crash report then), let it
            // finish, and only then stop it
            try {
                if (starmp.diag.Diag.cleanExit()) send(Json.obj("cmd", "diag_clean"));
                send(Json.obj("cmd", "quit"));
                if (!p.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy();
            } catch (Throwable t) { try { p.destroy(); } catch (Throwable ignored) {} }
        }));
        try { starmp.diag.Diag.helperStarted(this); } catch (Throwable t) { log("diag: " + t); }
    }

    private void readEvents(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try { handle(Json.parse(line)); } catch (Throwable t) { log("event error: " + t + " for " + line); }
            }
        } catch (IOException e) {
            log("helper stream closed: " + e.getMessage());
        }
        log("helper exited");
        boolean wasHosting = host && state == State.HOSTING;
        peers.clear(); code = "";
        setState(State.IDLE); firePeers();
        if (wasHosting && !userLeft) startRehost();
    }

    private void drainErr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println("[smnet] " + line);
        } catch (IOException ignored) {}
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : -1; }

    private void handle(Map<String, Object> e) {
        String ev = String.valueOf(e.get("ev"));
        switch (ev) {
            case "hello": log("helper ready, version " + e.get("version")); break;
            case "ready":
                code = String.valueOf(e.get("code"));
                log("hosting; join code ready" + (Boolean.TRUE.equals(e.get("relay")) ? " (relay online)" : " (LAN / direct only)"));
                setState(State.HOSTING);
                for (Listener l : listeners) l.onCode(code);
                break;
            case "connected": {
                int peer = num(e.get("peer"));
                String name = String.valueOf(e.get("name"));
                peers.put(peer, new Peer(peer, name));
                log((host ? "player joined: " : "connected to host: ") + name + " (id " + peer + ")");
                if (host) { connectedPeers.add(peer); starmp.campaign.Relations.changed(); } // 0.9.2.1: send them the standings
                if (!host) { localId = num(e.get("you")); setState(State.CONNECTED); }
                firePeers();
                break;
            }
            case "peer_left": {
                int peer = num(e.get("peer"));
                Peer gone = peers.remove(peer);
                log("peer left: " + (gone != null ? gone.name : "#" + peer) + " (" + e.get("reason") + ")");
                if (!host) setState(State.IDLE);
                firePeers();
                break;
            }
            case "latency": {
                Peer pr = peers.get(num(e.get("peer")));
                if (pr != null) {
                    pr.pingMs = num(e.get("ms"));
                    if (e.get("path") != null) pr.path = String.valueOf(e.get("path"));
                    if (e.get("qrtt") != null) pr.quicRttMs = num(e.get("qrtt"));
                    firePeers();
                }
                break;
            }
            case "message": {
                try {
                    byte[] data = java.util.Base64.getDecoder().decode(String.valueOf(e.get("data")));
                    int from = num(e.get("peer"));
                    starmp.diag.Perf.netIn(data);
                    for (MessageListener ml : msgListeners) ml.onMessage(from, data);
                } catch (Throwable t) { log("bad message: " + t); }
                break;
            }
            case "lan_hosts": {
                List<String[]> hs = new ArrayList<>();
                String list = e.get("list") == null ? "" : String.valueOf(e.get("list"));
                for (String ln : list.split("\n")) { String[] f = ln.split("\t"); if (f.length >= 3) hs.add(new String[]{f[0], f[1], f[2]}); }
                lanHosts = hs; lanScanning = false;
                if (hs.size() != lastLanCount) { lastLanCount = hs.size(); log("LAN scan: " + hs.size() + " game(s) found"); }
                break;
            }
            case "saves": newestSave = e.get("newest") == null ? "" : String.valueOf(e.get("newest")); break;
            case "save_begin": receivingSave = true; saveStatus = "Receiving save " + saveLabel(String.valueOf(e.get("name"))) + "..."; log(saveStatus); break;
            case "save_progress": saveStatus = "Receiving save: " + (num(e.get("bytes")) / (1024 * 1024)) + " MB"; break;
            case "save_received":
                receivingSave = false;
                receivedSave = String.valueOf(e.get("name"));
                saveStatus = "Save received: " + saveLabel(receivedSave);
                log(saveStatus + " (" + (num(e.get("bytes")) / 1024) + " KB)");
                break;
            case "save_sent": saveStatus = "Save sent: " + saveLabel(String.valueOf(e.get("name"))); log(saveStatus + " to peer " + e.get("peer")); break;
            case "save_error": {
                receivingSave = false;
                String t = String.valueOf(e.get("text"));
                log("Save transfer failed: " + t);
                saveStatus = "Save transfer failed" + (sharedSaves > 0 ? " - another Starsector on this PC uses the same saves folder" : ": " + (t.length() > 70 ? t.substring(0, 70) + "... (see log)" : t));
                break;
            }
            case "shared_saves":
                sharedSaves = (int) num(e.get("others"));
                log(sharedSaves > 0 ? "WARNING: " + sharedSaves + " other StarMP game(s) on this PC use the same saves folder (" + e.get("dir") + ") - saves will clash; use separate copies (Multibox script)" : "saves folder not shared");
                break;
            case "stopped":
                peers.clear(); code = "";
                log("stopped");
                setState(State.IDLE); firePeers();
                break;
            case "error":
                log("ERROR: " + e.get("text"));
                if (state == State.JOINING || state == State.STARTING) setState(State.IDLE);
                break;
            case "hw": case "sys": case "diag_clip": starmp.diag.Diag.onHelperEvent(ev, String.valueOf(e.get(ev.equals("diag_clip") ? "path" : "text"))); break;
            default: log("unknown event: " + ev);
        }
    }

    private synchronized void send(String json) throws IOException {
        if (toProc == null) throw new IOException("helper not running");
        long t0 = System.nanoTime();
        toProc.write((json + "\n").getBytes(StandardCharsets.UTF_8)); toProc.flush();
        starmp.diag.Perf.pipeWrite(System.nanoTime() - t0);
    }

    /** 0.1.5.3: start the helper at game launch (the crash report needs it running the whole session). */
    public void startHelper() {
        try { ensureProcess(); } catch (Throwable t) { log("helper start failed: " + t.getMessage()); }
    }

    /** 0.1.5.3: debug-log command to the helper (never throws). */
    public boolean diagCmd(Object... kv) {
        if (toProc == null) return false;
        try { send(Json.obj(kv)); return true; } catch (Throwable t) { return false; }
    }

    public String playerName() { return playerName; }
    public Peer peer(int id) { return peers.get(id); }

    /** 0.9.0.0: a player changed their name (host's decision, sent to everyone). */
    public void renamePeer(int id, String name) {
        synchronized (peers) {
            Peer old = peers.get(id);
            if (old == null) return;
            Peer p = new Peer(id, name); p.pingMs = old.pingMs; p.path = old.path; p.quicRttMs = old.quicRttMs;
            peers.put(id, p);
        }
    }

    /** "save_HelenArgyre_8348..." -> "HelenArgyre" (the character name shown in Load Game). */
    public static String saveLabel(String folder) {
        if (folder == null || !folder.startsWith("save_")) return String.valueOf(folder);
        String s = folder.substring(5);
        int u = s.lastIndexOf('_');
        return u > 0 ? s.substring(0, u) : s;
    }

    public static String savesDir() {
        return System.getProperty("com.fs.starfarer.settings.paths.saves", "saves");
    }

    /** Tell the helper where saves live and get the list back (newest first). */
    public void refreshSaves() {
        try { if (toProc != null) send(Json.obj("cmd", "save_root", "dir", savesDir())); } catch (IOException e) { log("save_root: " + e.getMessage()); }
    }

    /** Host: send a save folder ("" = newest) to every connected player. */
    public void sendSave(String name) {
        try { send(Json.obj("cmd", "send_save", "name", name == null ? "" : name)); saveStatus = "Sending save..."; }
        catch (IOException e) { log("send_save: " + e.getMessage()); }
    }

    /** Host: send a save folder ("" = newest) to one player only (used to resync a reconnecting guest). */
    public void sendSaveTo(int peer, String name) {
        try { send(Json.obj("cmd", "send_save", "name", name == null ? "" : name, "peer", peer)); }
        catch (IOException e) { log("send_save to " + peer + ": " + e.getMessage()); }
    }

    /** True while an automatic reconnect to the host is in progress (brief drop, not a deliberate leave). */
    public boolean reconnecting() { return !userLeft && rejoinUntilMs > System.currentTimeMillis(); }
    /** True once the user has chosen to leave (disconnect / back to lobby). */
    public boolean userLeft() { return userLeft; }
    /** Peers (host side) that connected since the last drain - used to resend campaign state on reconnect. */
    public final java.util.concurrent.ConcurrentLinkedQueue<Integer> connectedPeers = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public void setName(String n) { playerName = (n == null || n.trim().isEmpty()) ? "Player" : n.trim(); }

    /** 0.9.4.0: games found on the local network (name, address, players); refreshed by lanScanIfDue(). */
    public volatile List<String[]> lanHosts = new ArrayList<>();
    private volatile boolean lanScanning; private volatile long lastLanScanMs; private int lastLanCount = -1;
    public void lanScanIfDue() {
        long now = System.currentTimeMillis();
        if (state != State.IDLE || (lanScanning && now - lastLanScanMs < 5000) || now - lastLanScanMs < 2500) return;
        lastLanScanMs = now; lanScanning = true;
        try { ensureProcess(); send(Json.obj("cmd", "lan_scan")); } catch (Throwable t) { lanScanning = false; }
    }
    /** Join a LAN game by address ("192.168.1.5" or "192.168.1.5:24780"). */
    public void joinLan(String addr) { join("LAN:" + addr.trim()); }

    public void host(boolean lanOnly) {
        if (state != State.IDLE) return;
        lastLan = lanOnly; userLeft = false;
        try {
            host = true; setState(State.STARTING);
            ensureProcess();
            send(Json.obj("cmd", "host", "name", playerName, "lan", lanOnly));
        } catch (IOException e) { log("cannot start: " + e.getMessage()); setState(State.IDLE); }
    }

    public void join(String joinCode) {
        if (state != State.IDLE) return;
        lastJoinCode = joinCode; userLeft = false;
        try {
            host = false; setState(State.JOINING);
            ensureProcess();
            send(Json.obj("cmd", "join", "code", joinCode.trim(), "name", playerName));
        } catch (IOException e) { log("cannot start: " + e.getMessage()); setState(State.IDLE); }
    }

    /** True while linked (hosting with the session up, or connected to a host). */
    public boolean linked() { return (host && state == State.HOSTING) || (!host && state == State.CONNECTED); }

    /** Everyone else in the session, as ids from my point of view (host: client ids; client: [0] = host). */
    public List<Integer> others() { return host ? peerIds() : (state == State.CONNECTED ? java.util.Collections.singletonList(0) : java.util.Collections.<Integer>emptyList()); }

    /** Send to every other player (host: broadcast; client: to the host). */
    public void sendAll(byte[] data) { if (host) broadcast(data); else sendToHost(data); }

    /** Send to one player by id from my point of view. */
    public void sendTo(int id, byte[] data) { if (host) sendToPeer(id, data); else sendToHost(data); }

    /** My id as the other players see me (host = 0). */
    public int myId() { return host ? 0 : localId; }

    /** Client: send to the host. */
    public void sendToHost(byte[] data) {
        starmp.diag.Perf.netOut(data, 1);
        try { send(Json.obj("cmd", "send", "peer", 0, "data", java.util.Base64.getEncoder().encodeToString(data))); }
        catch (IOException e) { log("send failed: " + e.getMessage()); }
    }

    /** Host: send to one client. */
    public void sendToPeer(int peer, byte[] data) {
        starmp.diag.Perf.netOut(data, 1);
        try { send(Json.obj("cmd", "send", "peer", peer, "data", java.util.Base64.getEncoder().encodeToString(data))); }
        catch (IOException e) { log("send failed: " + e.getMessage()); }
    }

    /** Host: send to every client. */
    public void broadcast(byte[] data) {
        starmp.diag.Perf.netOut(data, Math.max(1, peers.size()));
        try { send(Json.obj("cmd", "broadcast", "data", java.util.Base64.getEncoder().encodeToString(data))); }
        catch (IOException e) { log("broadcast failed: " + e.getMessage()); }
    }

    public void disconnect() {
        userLeft = true; rejoinUntilMs = 0; reconnectStatus = "";
        if (state == State.IDLE) return;
        try { send(Json.obj("cmd", "disconnect")); } catch (IOException e) { log("disconnect: " + e.getMessage()); setState(State.IDLE); }
    }

    private volatile boolean lastLan;

    /** Host: the helper died while hosting. Start it again; it reuses its saved identity and port, so the join code
     *  stays the same and the guests' automatic reconnect finds us. */
    private void startRehost() {
        log("network helper stopped while hosting - restarting it (same join code)");
        Thread t = new Thread(() -> {
            for (int i = 0; i < 5 && !userLeft; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                if (state == State.HOSTING) return;
                if (state == State.IDLE) { proc = null; host(lastLan); }
                for (int w = 0; w < 20 && state != State.HOSTING; w++) { try { Thread.sleep(500); } catch (InterruptedException e) { return; } }
                if (state == State.HOSTING) { log("hosting again, code " + (code.length() > 24 ? code.substring(0, 24) + "..." : code)); return; }
            }
            log("could not restart hosting - use F8 to host again");
        }, "StarMP-rehost");
        t.setDaemon(true); t.start();
    }

    /** Guest lost the host without choosing to: try the same join code every 5 s for up to 3 minutes. */
    private synchronized void startRejoin() {
        rejoinUntilMs = System.currentTimeMillis() + 180_000L;
        if (rejoinThread != null && rejoinThread.isAlive()) return;
        log("link to the host lost - reconnecting automatically for up to 3 minutes");
        Thread t = new Thread(() -> {
            int tries = 0;
            while (System.currentTimeMillis() < rejoinUntilMs && !userLeft) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                if (state == State.CONNECTED || userLeft) break;
                if (state == State.IDLE) {
                    tries++;
                    reconnectStatus = "Reconnecting to the host (try " + tries + ")...";
                    log("reconnect try " + tries);
                    // the helper may still hold the dead session ("already hosting or connected"): drop it first
                    try { send(Json.obj("cmd", "disconnect")); } catch (Throwable ignored) {}
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    if (state != State.IDLE || userLeft) continue;
                    String c = lastJoinCode;
                    if (c != null) join(c);
                }
            }
            if (state != State.CONNECTED && !userLeft) { reconnectStatus = "Could not reconnect - use F8 to join again"; log("gave up reconnecting"); }
        }, "StarMP-rejoin");
        t.setDaemon(true); rejoinThread = t; t.start();
    }

    public void shutdown() {
        try { send(Json.obj("cmd", "diag_clean")); } catch (Throwable ignored) {} // quitting from our menu = clean exit
        try { send(Json.obj("cmd", "quit")); } catch (Throwable ignored) {}
        Process p = proc;
        if (p != null) {
            try { if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy(); } catch (Throwable t) { p.destroy(); }
        }
    }
}
