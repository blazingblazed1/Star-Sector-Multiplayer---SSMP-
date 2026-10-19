package starmp.campaign;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Campaign-layer packets (ByteBuffer only; the mod sandbox blocks java.io streams). */
public final class CampWire {
    private CampWire() {}

    public static final byte FLEET = 3;   // either direction, ~5 Hz: where my fleet is
    public static final byte COMP = 4;    // either direction, on change: what my fleet looks like
    public static final byte HELLO = 5;
    public static final byte BATTLE = 10;
    public static final byte GIFT = 13;   // either direction: credits sent to the other player

    public static byte[] encodeGift(float credits, String from) {
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + strLen(from));
        b.put(GIFT); b.putFloat(credits); putStr(b, from);
        return done(b);
    }
    public static Object[] decodeGift(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        float c = b.getFloat(); String from = getStr(b);
        return new Object[]{c, from};
    }  // host -> clients, ~1 Hz while the host is in a campaign battle   // either direction, once per connection + on game load: sector fingerprint

    public static final class Fleet {
        public String loc = "", name = "";
        public float x, y, vx, vy;
        public int compHash;
        public boolean paused;
        public long clock; // campaign timestamp, for time sync
        public boolean transponder = true; // 0.1.4.7 (appended byte)
        public int state; // 0.9.2.5 (appended byte): 1 = docked at a market, 2 = in a dialog
        public long recvNs; // set on receive
    }

    public static final class Comp {
        public final List<String> hulls = new ArrayList<>();
        public final List<String> names = new ArrayList<>();
    }

    public static final class Hello {
        public String version = "", seed = "", playerName = "";
        public int systems;
        public String portrait = ""; // 0.1.4.10 (appended): for the scoreboard
    }

    // ---- helpers
    private static void putStr(ByteBuffer b, String s) {
        byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (d.length > 65535) d = java.util.Arrays.copyOf(d, 65535);
        b.putShort((short) d.length); b.put(d);
    }
    private static String getStr(ByteBuffer b) {
        byte[] d = new byte[b.getShort() & 0xFFFF]; b.get(d);
        return new String(d, StandardCharsets.UTF_8);
    }
    private static int strLen(String s) { return 2 + (s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length); }
    private static byte[] done(ByteBuffer b) { byte[] o = new byte[b.position()]; b.flip(); b.get(o); return o; }

    public static byte[] encode(Fleet f) {
        ByteBuffer b = ByteBuffer.allocate(1 + strLen(f.loc) + strLen(f.name) + 16 + 4 + 1 + 8 + 1 + 1);
        b.put(FLEET); putStr(b, f.loc); putStr(b, f.name);
        b.putFloat(f.x); b.putFloat(f.y); b.putFloat(f.vx); b.putFloat(f.vy);
        b.putInt(f.compHash); b.put((byte) (f.paused ? 1 : 0)); b.putLong(f.clock);
        b.put((byte) (f.transponder ? 1 : 0));
        b.put((byte) f.state);
        return done(b);
    }
    public static Fleet decodeFleet(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        Fleet f = new Fleet();
        f.loc = getStr(b); f.name = getStr(b);
        f.x = b.getFloat(); f.y = b.getFloat(); f.vx = b.getFloat(); f.vy = b.getFloat();
        f.compHash = b.getInt(); f.paused = b.get() != 0; f.clock = b.getLong();
        if (b.hasRemaining()) f.transponder = b.get() != 0;
        if (b.hasRemaining()) f.state = b.get() & 0xFF;
        return f;
    }

    public static byte[] encode(Comp c) {
        int size = 1 + 2;
        for (int i = 0; i < c.hulls.size(); i++) size += strLen(c.hulls.get(i)) + strLen(c.names.get(i));
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(COMP); b.putShort((short) c.hulls.size());
        for (int i = 0; i < c.hulls.size(); i++) { putStr(b, c.hulls.get(i)); putStr(b, c.names.get(i)); }
        return done(b);
    }
    public static Comp decodeComp(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        Comp c = new Comp();
        int n = b.getShort() & 0xFFFF;
        for (int i = 0; i < n; i++) { c.hulls.add(getStr(b)); c.names.add(getStr(b)); }
        return c;
    }

    public static final class Battle { public boolean active; public int battleId; public String hostName = "", enemyName = "", loc = ""; public float x, y; public boolean hasPos; public long recvNs; public int owner; }

    public static byte[] encode(Battle t) {
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 4 + strLen(t.hostName) + strLen(t.enemyName) + strLen(t.loc) + 8);
        b.put(BATTLE); b.put((byte) (t.active ? 1 : 0)); b.putInt(t.battleId); putStr(b, t.hostName); putStr(b, t.enemyName);
        putStr(b, t.loc); b.putFloat(t.x); b.putFloat(t.y);
        return done(b);
    }
    public static Battle decodeBattle(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        Battle t = new Battle();
        t.active = b.get() != 0; t.battleId = b.getInt(); t.hostName = getStr(b); t.enemyName = getStr(b);
        if (b.remaining() >= 10) { t.loc = getStr(b); t.x = b.getFloat(); t.y = b.getFloat(); t.hasPos = true; }
        return t;
    }

    public static byte[] encode(Hello h) {
        ByteBuffer b = ByteBuffer.allocate(1 + strLen(h.version) + strLen(h.seed) + strLen(h.playerName) + 4 + strLen(h.portrait));
        b.put(HELLO); putStr(b, h.version); putStr(b, h.seed); putStr(b, h.playerName); b.putInt(h.systems); putStr(b, h.portrait);
        return done(b);
    }
    public static Hello decodeHello(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        Hello h = new Hello();
        h.version = getStr(b); h.seed = getStr(b); h.playerName = getStr(b); h.systems = b.getInt();
        if (b.remaining() >= 2) h.portrait = getStr(b);
        return h;
    }
}
