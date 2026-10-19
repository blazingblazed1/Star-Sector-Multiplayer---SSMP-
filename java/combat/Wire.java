package starmp.combat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binary packets for the combat co-op prototype. Pure Java so it can be unit tested without the game.
 * Uses ByteBuffer (big-endian) only: the game's mod sandbox blocks java.io data streams.
 */
public final class Wire {
    private Wire() {}

    public static final byte INPUT = 1;     // client -> host
    public static final byte SNAPSHOT = 2;  // host -> clients

    // Input bit flags
    public static final int B_ACCEL = 1, B_BACK = 2, B_DECEL = 4, B_LEFT = 8, B_RIGHT = 16,
            B_STRAFE_L = 32, B_STRAFE_R = 64, B_FIRE = 128, B_VENT = 256, B_SYSTEM = 512, B_SHIELD = 1024,
            B_STRAFE_MOD = 2048; // strafe key held / strafe toggled on: A/D strafe, ship turns to the mouse

    public static final class Input {
        public int seq, bits, group;
        public int pauseCount; // increments each time the client presses pause (host toggles on change)
        public float aimX, aimY;
        public int wantShip; // >0: the client switched to this ship (owner id) of its own fleet
        public int targetShip; // 0.1.6.3: >0: the ship the client has target-locked (R), owner id; 0 = none
    }

    public static final byte NAMES = 20; // owner -> clients: player id -> name for everyone who has ships in the battle

    public static byte[] encodeNames(java.util.Map<Integer, String> names) {
        int size = 2;
        for (String n : names.values()) size += 1 + sl(n);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(NAMES); b.put((byte) names.size());
        for (java.util.Map.Entry<Integer, String> e : names.entrySet()) { b.put((byte) (int) e.getKey()); ps(b, e.getValue()); }
        return done(b);
    }

    public static java.util.Map<Integer, String> decodeNames(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data); b.get();
        int n = b.get() & 0xFF;
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) { int id = b.get() & 0xFF; out.put(id, gs(b)); }
        return out;
    }

    public static final byte ORDERS = 19; // client -> owner: the client's tactical-map orders for its own ships

    /** One tactical-map order. kind: 0 = point (waypoint), 1 = ship, 2 = objective. */
    public static final class Order {
        public int type, kind, targetShip;
        public float x, y;
        public final List<Integer> ships = new ArrayList<>();
        public String key() { return type + "|" + kind + "|" + targetShip + "|" + Math.round(x / 50) + "," + Math.round(y / 50) + "|" + ships; }
    }

    public static byte[] encodeOrders(List<Order> list) {
        int size = 1 + 2;
        for (Order o : list) size += 1 + 1 + 4 + 8 + 2 + 4 * o.ships.size();
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(ORDERS); b.putShort((short) list.size());
        for (Order o : list) {
            b.put((byte) o.type); b.put((byte) o.kind); b.putInt(o.targetShip); b.putFloat(o.x); b.putFloat(o.y);
            b.putShort((short) o.ships.size()); for (int id : o.ships) b.putInt(id);
        }
        return done(b);
    }

    public static List<Order> decodeOrders(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data); b.get();
        int n = b.getShort() & 0xFFFF;
        List<Order> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Order o = new Order();
            o.type = b.get() & 0xFF; o.kind = b.get(); o.targetShip = b.getInt(); o.x = b.getFloat(); o.y = b.getFloat();
            int c = b.getShort() & 0xFFFF; for (int k = 0; k < c; k++) o.ships.add(b.getInt());
            out.add(o);
        }
        return out;
    }

    public static final class ShipState {
        public int id;
        public float x, y, vx, vy, facing, angVel, hp, flux;
        public boolean alive, firing, shieldOn, venting, phased;
        /** Beam weapons firing on the owner: index into getAllWeapons() and the weapon's aim angle. */
        public final List<float[]> beams = new ArrayList<>();
        public float cr = 0.7f, shieldFacing;
        /** Only for ships a player flies: per weapon, cooldown left as a fraction (0..1) and ammo (-1 = no ammo). */
        public float[] weaponCd; public int[] weaponAmmo;
    }

    public static final byte SHIPDEFS = 8;  // host -> clients: what each ship id is (hull + exact loadout)
    public static final byte PROJ = 9;      // host -> clients: projectiles/missiles fired since the last packet
    public static final byte JOINFLEET = 11; // guest -> host: my fleet, to bring into the host's battle
    public static final byte RESULT = 12;    // host -> guest: what happened to each of the guest's ships

    public static final class Outcome { public int index; public boolean destroyed; public float hullFrac, cr; }

    public static final class ShipDef {
        public int id, owner, vents, caps;
        public float cr = 0.7f, hullFrac = 1f;
        public String hullId = "", name = "";
        public final List<String> slots = new ArrayList<>(), weapons = new ArrayList<>(), mods = new ArrayList<>(), wings = new ArrayList<>();
        public boolean fighterLike;
        public int player = -1;   // net id of the player who owns this ship (-1 = AI/NPC)
        public boolean ally;      // spawned as an ally (blue, not commandable)
        /** 0.9.1.9: the player's own weapon groups, each "TYPE|autofire 0/1|slot,slot..." (empty = let the game make them) */
        public final List<String> groups = new ArrayList<>();
    }

    public static final class ProjSpawn {
        public int sourceId;
        public String weaponId = "";
        public float x, y, angle, vx, vy;
        public int missileId; // >0 for missiles: the owner keeps sending their position (MISSILES)
    }

    public static final byte MISSILES = 14; // owner -> clients: position of every live missile
    public static final class MissileState { public int id; public float x, y, facing, vx, vy; }

    public static byte[] encodeMissiles(List<MissileState> list, int maxId) {
        int n = Math.min(list.size(), 4000);
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 2 + n * 24);
        b.put(MISSILES); b.putInt(maxId); b.putShort((short) n);
        for (int k = 0; k < n; k++) { MissileState m = list.get(k); b.putInt(m.id); b.putFloat(m.x); b.putFloat(m.y); b.putFloat(m.facing); b.putFloat(m.vx); b.putFloat(m.vy); }
        return done(b);
    }

    public static List<MissileState> decodeMissiles(byte[] data) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data); b.get();
            int maxId = b.getInt();
            int n = b.getShort() & 0xFFFF;
            List<MissileState> out = new ArrayList<>(n);
            MissileState hdr = new MissileState(); hdr.id = -maxId; out.add(hdr); // first entry: header (id = -highest id assigned)
            for (int k = 0; k < n; k++) { MissileState m = new MissileState(); m.id = b.getInt(); m.x = b.getFloat(); m.y = b.getFloat(); m.facing = b.getFloat(); m.vx = b.getFloat(); m.vy = b.getFloat(); out.add(m); }
            return out;
        } catch (java.nio.BufferUnderflowException e) { throw new BadPacket("short missiles packet"); }
    }

    public static final class Snapshot {
        public int tick;
        public boolean paused; // host's pause state; clients mirror it
        public final Map<Integer, Integer> assignments = new LinkedHashMap<>(); // peer id -> ship id
        public final List<ShipState> ships = new ArrayList<>();
    }

    /** Thrown for malformed packets (unchecked so callers need no java.io types). */
    public static final class BadPacket extends RuntimeException {
        BadPacket(String m) { super(m); }
    }

    private static byte[] done(ByteBuffer b) {
        byte[] out = new byte[b.position()];
        b.flip(); b.get(out);
        return out;
    }

    public static byte[] encodeInput(Input in) {
        ByteBuffer o = ByteBuffer.allocate(25);
        o.put(INPUT); o.putInt(in.seq); o.putShort((short) in.bits);
        o.putFloat(in.aimX); o.putFloat(in.aimY); o.put((byte) in.group); o.put((byte) in.pauseCount); o.putInt(in.wantShip);
        o.putInt(in.targetShip);
        return done(o);
    }

    public static Input decodeInput(byte[] d) {
        try {
            ByteBuffer i = ByteBuffer.wrap(d);
            if (i.get() != INPUT) throw new BadPacket("not an input packet");
            Input in = new Input();
            in.seq = i.getInt(); in.bits = i.getShort() & 0xFFFF;
            in.aimX = i.getFloat(); in.aimY = i.getFloat(); in.group = i.get() & 0xFF; in.pauseCount = i.get() & 0xFF;
            if (i.remaining() >= 4) in.wantShip = i.getInt();
            if (i.remaining() >= 4) in.targetShip = i.getInt();
            return in;
        } catch (java.nio.BufferUnderflowException e) { throw new BadPacket("short input packet"); }
    }

    public static byte[] encodeSnapshot(Snapshot s) {
        int beamBytes = 0;
        for (ShipState st : s.ships) beamBytes += 1 + Math.min(255, st.beams.size()) * 5;
        ByteBuffer o = ByteBuffer.allocate(1 + 4 + 1 + 1 + s.assignments.size() * 5 + 2 + s.ships.size() * (4 + 8 * 4 + 1 + 1 + 1 + 2) + beamBytes + weaponBytes(s));
        o.put(SNAPSHOT); o.putInt(s.tick); o.put((byte) (s.paused ? 1 : 0));
        o.put((byte) s.assignments.size());
        for (Map.Entry<Integer, Integer> e : s.assignments.entrySet()) { o.put((byte) (int) e.getKey()); o.putInt(e.getValue()); }
        o.putShort((short) s.ships.size());
        for (ShipState st : s.ships) {
            o.putInt(st.id);
            o.putFloat(st.x); o.putFloat(st.y); o.putFloat(st.vx); o.putFloat(st.vy);
            o.putFloat(st.facing); o.putFloat(st.angVel); o.putFloat(st.hp); o.putFloat(st.flux);
            o.put((byte) ((st.alive ? 1 : 0) | (st.firing ? 2 : 0) | (st.shieldOn ? 4 : 0) | (st.venting ? 8 : 0) | (st.phased ? 16 : 0)));
            int nb = Math.min(255, st.beams.size());
            o.put((byte) nb);
            for (int b = 0; b < nb; b++) { o.put((byte) (int) st.beams.get(b)[0]); o.putFloat(st.beams.get(b)[1]); }
            o.put((byte) Math.round(Math.max(0f, Math.min(1f, st.cr)) * 255f));
            o.putShort((short) Math.round(((st.shieldFacing % 360f) + 360f) % 360f * 10f));
            int nw = st.weaponCd == null ? 0 : Math.min(255, st.weaponCd.length);
            o.put((byte) nw);
            for (int w = 0; w < nw; w++) { o.put((byte) Math.round(Math.max(0f, Math.min(1f, st.weaponCd[w])) * 255f)); o.putShort((short) st.weaponAmmo[w]); }
        }
        return done(o);
    }

    private static int weaponBytes(Snapshot s) { int n = 0; for (ShipState st : s.ships) if (st.weaponCd != null) n += Math.min(255, st.weaponCd.length) * 3; return n; }

    public static Snapshot decodeSnapshot(byte[] d) {
        try {
            ByteBuffer i = ByteBuffer.wrap(d);
            if (i.get() != SNAPSHOT) throw new BadPacket("not a snapshot packet");
            Snapshot s = new Snapshot();
            s.tick = i.getInt(); s.paused = (i.get() & 1) != 0;
            int na = i.get() & 0xFF;
            for (int a = 0; a < na; a++) { int peer = i.get() & 0xFF; s.assignments.put(peer, i.getInt()); }
            int n = i.getShort() & 0xFFFF;
            for (int k = 0; k < n; k++) {
                ShipState st = new ShipState();
                st.id = i.getInt();
                st.x = i.getFloat(); st.y = i.getFloat(); st.vx = i.getFloat(); st.vy = i.getFloat();
                st.facing = i.getFloat(); st.angVel = i.getFloat(); st.hp = i.getFloat(); st.flux = i.getFloat();
                int f = i.get() & 0xFF;
                st.alive = (f & 1) != 0; st.firing = (f & 2) != 0; st.shieldOn = (f & 4) != 0; st.venting = (f & 8) != 0; st.phased = (f & 16) != 0;
                int nb = i.get() & 0xFF;
                for (int b = 0; b < nb; b++) { int w = i.get() & 0xFF; st.beams.add(new float[] { w, i.getFloat() }); }
                st.cr = (i.get() & 0xFF) / 255f;
                st.shieldFacing = (i.getShort() & 0xFFFF) / 10f;
                int nw = i.get() & 0xFF;
                if (nw > 0) { st.weaponCd = new float[nw]; st.weaponAmmo = new int[nw]; for (int w = 0; w < nw; w++) { st.weaponCd[w] = (i.get() & 0xFF) / 255f; st.weaponAmmo[w] = i.getShort(); } }
                s.ships.add(st);
            }
            return s;
        } catch (java.nio.BufferUnderflowException e) { throw new BadPacket("short snapshot packet"); }
    }

    // ---- strings
    private static int sl(String s) { return 2 + (s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length); }
    private static void ps(ByteBuffer b, String s) { byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8); b.putShort((short) d.length); b.put(d); }
    private static String gs(ByteBuffer b) { byte[] d = new byte[b.getShort() & 0xFFFF]; b.get(d); return new String(d, StandardCharsets.UTF_8); }
    private static int listLen(List<String> l) { int n = 2; for (String x : l) n += sl(x); return n; }
    private static void pl(ByteBuffer b, List<String> l) { b.putShort((short) l.size()); for (String x : l) ps(b, x); }
    private static void gl(ByteBuffer b, List<String> l) { int n = b.getShort() & 0xFFFF; for (int i = 0; i < n; i++) l.add(gs(b)); }

    public static byte[] encodeDefs(List<ShipDef> defs) { return encodeDefs(SHIPDEFS, -1, defs); }

    /** type = SHIPDEFS or JOINFLEET; flag = flagship id for JOINFLEET. */
    public static byte[] encodeDefs(byte type, int flag, List<ShipDef> defs) {
        int size = 1 + 4 + 2;
        for (ShipDef d : defs) size += 8 + 4 + 1 + 2 + 2 + 1 + 2 + sl(d.hullId) + sl(d.name) + listLen(d.slots) + listLen(d.weapons) + listLen(d.mods) + listLen(d.wings) + listLen(d.groups);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(type); b.putInt(flag); b.putShort((short) defs.size());
        for (ShipDef d : defs) {
            b.putFloat(d.cr); b.putFloat(d.hullFrac);
            b.putInt(d.id); b.put((byte) d.owner); b.putShort((short) d.vents); b.putShort((short) d.caps); b.put((byte) (d.fighterLike ? 1 : 0)); b.put((byte) d.player); b.put((byte) (d.ally ? 1 : 0));
            ps(b, d.hullId); ps(b, d.name); pl(b, d.slots); pl(b, d.weapons); pl(b, d.mods); pl(b, d.wings); pl(b, d.groups);
        }
        return done(b);
    }

    public static int defsFlag(byte[] data) { return ByteBuffer.wrap(data, 1, 4).getInt(); }

    public static List<ShipDef> decodeDefs(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data); b.get(); b.getInt();
        int n = b.getShort() & 0xFFFF;
        List<ShipDef> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            ShipDef d = new ShipDef();
            d.cr = b.getFloat(); d.hullFrac = b.getFloat();
            d.id = b.getInt(); d.owner = b.get(); d.vents = b.getShort(); d.caps = b.getShort(); d.fighterLike = b.get() != 0; d.player = b.get(); d.ally = b.get() != 0;
            d.hullId = gs(b); d.name = gs(b); gl(b, d.slots); gl(b, d.weapons); gl(b, d.mods); gl(b, d.wings); gl(b, d.groups);
            out.add(d);
        }
        return out;
    }

    public static byte[] encodeResult(List<Outcome> list) {
        ByteBuffer b = ByteBuffer.allocate(1 + 2 + list.size() * 13);
        b.put(RESULT); b.putShort((short) list.size());
        for (Outcome o : list) { b.putInt(o.index); b.put((byte) (o.destroyed ? 1 : 0)); b.putFloat(o.hullFrac); b.putFloat(o.cr); }
        return done(b);
    }

    public static List<Outcome> decodeResult(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data); b.get();
        int n = b.getShort() & 0xFFFF;
        List<Outcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) { Outcome o = new Outcome(); o.index = b.getInt(); o.destroyed = b.get() != 0; o.hullFrac = b.getFloat(); o.cr = b.getFloat(); out.add(o); }
        return out;
    }

    public static byte[] encodeProj(List<ProjSpawn> list) {
        int size = 1 + 2;
        for (ProjSpawn p : list) size += 4 + sl(p.weaponId) + 5 * 4 + 4;
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(PROJ); b.putShort((short) list.size());
        for (ProjSpawn p : list) { b.putInt(p.sourceId); ps(b, p.weaponId); b.putFloat(p.x); b.putFloat(p.y); b.putFloat(p.angle); b.putFloat(p.vx); b.putFloat(p.vy); b.putInt(p.missileId); }
        return done(b);
    }

    public static List<ProjSpawn> decodeProj(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data); b.get();
        int n = b.getShort() & 0xFFFF;
        List<ProjSpawn> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            ProjSpawn p = new ProjSpawn();
            p.sourceId = b.getInt(); p.weaponId = gs(b); p.x = b.getFloat(); p.y = b.getFloat(); p.angle = b.getFloat(); p.vx = b.getFloat(); p.vy = b.getFloat(); p.missileId = b.getInt();
            out.add(p);
        }
        return out;
    }

    public static byte type(byte[] d) { return d.length == 0 ? 0 : d[0]; }
}
