package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import starmp.NetManager;
import starmp.StarMPPlugin;
import starmp.combat.CoopState;
import starmp.combat.Ships;
import starmp.combat.Wire;

/**
 * Player-to-player trade: the state of one trade and its packets (TRADE = 25).
 * Both sides send their whole offer (with a version number) on every change; "ready" is only valid for the versions
 * both sides have seen, so any change clears it. Both ready -> local countdown -> COMMIT (my version, their version);
 * each side applies the swap once it has sent its own COMMIT and received the other's for the same versions.
 */
public final class TradeSession {
    public static final byte TRADE = 25;
    static final byte REQ = 1, ACCEPT = 2, DECLINE = 3, OFFER = 4, COMMIT = 5, CANCEL = 6, CLEAR = 7;

    /** One cargo entry in an offer. */
    public static final class Item {
        public CargoAPI.CargoItemType type; public String id = "", data = ""; public float qty;
        Object cargoData() { return type == CargoAPI.CargoItemType.SPECIAL ? new SpecialItemData(id, data.isEmpty() ? null : data) : id; }
        boolean same(CargoStackAPI s) {
            if (s.getType() != type) return false;
            Object d = s.getData();
            if (d instanceof SpecialItemData) return ((SpecialItemData) d).getId().equals(id) && String.valueOf(((SpecialItemData) d).getData()).equals(data.isEmpty() ? "null" : data);
            return id.equals(String.valueOf(d));
        }
        static Item of(CargoStackAPI s, float qty) {
            Item i = new Item(); i.type = s.getType(); i.qty = qty;
            Object d = s.getData();
            if (d instanceof SpecialItemData) { i.id = ((SpecialItemData) d).getId(); i.data = ((SpecialItemData) d).getData() == null ? "" : ((SpecialItemData) d).getData(); }
            else i.id = String.valueOf(d);
            return i;
        }
    }

    /** One side of the trade. */
    public static final class Offer {
        public int version; public boolean ready; public int readyForOther; // the other side's version this ready refers to
        public float credits;
        public final List<Item> items = new ArrayList<>();
        public final List<Wire.ShipDef> ships = new ArrayList<>();
        public final List<FleetMemberAPI> members = new ArrayList<>(); // my side only: the real members offered
        boolean isEmpty() { return items.isEmpty() && ships.isEmpty() && credits <= 0; }
    }

    // ---- current trade (one at a time)
    static volatile int peer = -1;           // who we trade with
    static volatile String peerName = "";
    static volatile boolean active;          // both accepted, screen open
    static volatile boolean waitingAccept;   // I asked, waiting for their answer
    static Offer mine = new Offer(), theirs = new Offer();
    static long countdownNs;                 // 0 = none
    static boolean sentCommit, gotCommit; static int commitMine, commitTheirs;
    static volatile String lastEvent = "";
    static long requestNs;

    // incoming requests (peer, name) -> intel
    static final ConcurrentLinkedQueue<Object[]> incoming = new ConcurrentLinkedQueue<>();
    /** Open requests from other players: peer -> name (until accepted, declined or withdrawn). */
    static final java.util.Map<Integer, String> pending = new java.util.concurrent.ConcurrentHashMap<>();
    static final ConcurrentLinkedQueue<Object[]> inbox = new ConcurrentLinkedQueue<>(); // {peer, byte[]} handled on the game thread

    // ------------------------------------------------------------------ requests
    static void request(int to, String name) {
        NetManager n = StarMPPlugin.net();
        reset(); peer = to; peerName = name; waitingAccept = true; requestNs = System.nanoTime();
        send(n, to, simple(REQ, n.playerName()));
        StarMPPlugin.flog("trade: asked " + name + " (player " + to + ") to trade");
    }

    static void accept(int from, String name) {
        NetManager n = StarMPPlugin.net();
        pending.remove(from); FriendTradeIntel.closeFor(from);
        reset(); peer = from; peerName = name; active = true;
        send(n, from, simple(ACCEPT, n.playerName()));
        StarMPPlugin.flog("trade: accepted " + name + "'s trade request");
    }

    static void decline(int from) { pending.remove(from); FriendTradeIntel.closeFor(from); send(StarMPPlugin.net(), from, simple(DECLINE, StarMPPlugin.net().playerName())); }

    /** Close the trade for both (X with empty windows, Esc, or disconnect). */
    static void cancel(String why) {
        if (peer >= 0 && (active || waitingAccept)) send(StarMPPlugin.net(), peer, simple(CANCEL, why));
        StarMPPlugin.flog("trade: cancelled (" + why + ")");
        reset();
    }

    static void reset() {
        returnOfficers();
        active = false; waitingAccept = false; peer = -1;
        mine = new Offer(); theirs = new Offer();
        countdownNs = 0; sentCommit = false; gotCommit = false;
    }

    // ------------------------------------------------------------------ my offer
    /** Offer some of a cargo stack (capped at what I have). */
    static void addItem(CargoStackAPI s, float qty) {
        float already = 0;
        for (Item i : mine.items) if (i.same(s)) already += i.qty;
        qty = Math.min(qty, s.getSize() - already);
        if (qty <= 0) return;
        for (Item i : mine.items) if (i.same(s)) { i.qty += qty; changed(); return; }
        mine.items.add(Item.of(s, qty)); changed();
    }

    static void removeItem(int idx) { if (idx >= 0 && idx < mine.items.size()) { mine.items.remove(idx); changed(); } }

    /** Offer a ship. Its officer is unassigned right away (officers stay with the owner). */
    static String addShip(FleetMemberAPI m) {
        if (mine.members.contains(m)) return null;
        if (m.isFlagship()) return "You can't trade your flagship.";
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        String note = null;
        if (m.getCaptain() != null && !m.getCaptain().isDefault() && !m.getCaptain().isPlayer()) {
            note = m.getCaptain().getNameString() + " left the ship and stays in your fleet.";
            StarMPPlugin.flog("trade: officer " + m.getCaptain().getNameString() + " unassigned from " + m.getShipName());
            try { m.setCaptain(null); } catch (Throwable t) { m.setCaptain(Global.getFactory().createPerson()); }
            me.getFleetData().setSyncNeeded();
        }
        mine.members.add(m); mine.ships.add(Ships.def(mine.ships.size(), 0, m));
        changed(); return note;
    }

    static void removeShip(int idx) {
        if (idx < 0 || idx >= mine.members.size()) return;
        mine.members.remove(idx); mine.ships.clear();
        for (int i = 0; i < mine.members.size(); i++) mine.ships.add(Ships.def(i, 0, mine.members.get(i)));
        changed();
    }

    static void setCredits(float c) {
        float have = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        mine.credits = Math.max(0, Math.min(have, Math.round(c)));
        changed();
    }

    static void clearMine() { mine.items.clear(); mine.ships.clear(); mine.members.clear(); mine.credits = 0; changed(); }

    /** Any change to my offer: new version, both "ready" flags are void. */
    static void changed() {
        mine.version++; mine.ready = false; countdownNs = 0; sentCommit = false; gotCommit = false;
        sendOffer();
    }

    static void setReady(boolean r) {
        mine.ready = r; mine.readyForOther = theirs.version;
        if (!r) { countdownNs = 0; sentCommit = false; }
        sendOffer();
        checkStart();
    }

    static void stopCountdown() { countdownNs = 0; sentCommit = false; gotCommit = false; mine.ready = false; sendOffer(); }

    static boolean bothReady() {
        return mine.ready && theirs.ready && mine.readyForOther == theirs.version && theirs.readyForOther == mine.version;
    }

    static void checkStart() {
        if (bothReady() && countdownNs == 0) { countdownNs = System.nanoTime(); lastEvent = "starting"; StarMPPlugin.flog("trade: both ready - countdown"); }
    }

    /** Game thread, every frame while trading. */
    static void tick() {
        Object[] in;
        while ((in = inbox.poll()) != null) handle((Integer) in[0], (byte[]) in[1]);
        if (waitingAccept && System.nanoTime() - requestNs > 90_000_000_000L) {
            String who = peerName; cancel("no answer"); TradeScreen.finished(who + " did not answer the trade request");
        }
        if (!active) return;
        if (countdownNs != 0 && !bothReady()) countdownNs = 0;
        if (countdownNs != 0 && !sentCommit && System.nanoTime() - countdownNs > 4_000_000_000L) {
            String bad = validateMine();
            if (bad != null) { lastEvent = bad; setReady(false); return; }
            sentCommit = true; commitMine = mine.version; commitTheirs = theirs.version;
            ByteBuffer b = ByteBuffer.allocate(10); b.put(TRADE); b.put(COMMIT); b.putInt(mine.version); b.putInt(theirs.version);
            send(StarMPPlugin.net(), peer, done(b));
        }
        if (sentCommit && gotCommit) apply();
    }

    /** The out-of-system tax on what I send (0 when we're in the same system). */
    static float myTax() { return peer >= 0 && TradeTax.remote(peer) ? TradeTax.onOffer(mine) : 0f; }

    private static String validateMine() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        CargoAPI c = me.getCargo();
        float tax = myTax();
        if (c.getCredits().get() < mine.credits + tax) return tax > 0 ? "Not enough credits (incl. " + Math.round(tax) + " out-of-system tax)" : "Not enough credits";
        for (Item i : mine.items) if (c.getQuantity(i.type, i.cargoData()) + 0.01f < i.qty) return "You no longer have that cargo";
        for (FleetMemberAPI m : mine.members) if (!me.getFleetData().getMembersListCopy().contains(m) || m.isFlagship()) return "A ship you offered is gone";
        return null;
    }

    private static void apply() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        CargoAPI c = me.getCargo();
        float tax = myTax();
        c.getCredits().subtract(mine.credits + tax);
        if (tax > 0) StarMPPlugin.flog("trade: paid " + Math.round(tax) + " out-of-system tax (" + peerName + " is in " + TradeTax.where(peer) + ")");
        for (Item i : mine.items) c.removeItems(i.type, i.cargoData(), i.qty);
        for (FleetMemberAPI m : mine.members) me.getFleetData().removeFleetMember(m);
        c.getCredits().add(theirs.credits);
        for (Item i : theirs.items) c.addItems(i.type, i.cargoData(), i.qty);
        int n = 0;
        for (Wire.ShipDef d : theirs.ships) {
            try {
                FleetMemberAPI m = Ships.member(d, "trade_" + System.nanoTime() % 100000 + "_" + n++);
                m.setOwner(0);
                me.getFleetData().addFleetMember(m);
                m.getStatus().setHullFraction(Math.max(0.05f, d.hullFrac));
                m.getRepairTracker().setCR(Math.max(0f, d.cr));
            } catch (Throwable t) { StarMPPlugin.flog("trade: could not add ship " + d.hullId + ": " + t); }
        }
        me.getFleetData().setSyncNeeded(); me.forceSync();
        StarMPPlugin.flog("trade: done with " + peerName + ": gave " + mine.items.size() + " stacks, " + mine.members.size() + " ships, " + Math.round(mine.credits)
                + " cr; got " + theirs.items.size() + " stacks, " + theirs.ships.size() + " ships, " + Math.round(theirs.credits) + " cr");
        lastEvent = "complete";
        mine.members.clear(); // they're gone; nothing to hand officers back to
        String who = peerName;
        active = false; waitingAccept = false; peer = -1;
        mine = new Offer(); theirs = new Offer(); countdownNs = 0; sentCommit = false; gotCommit = false;
        TradeScreen.finished("Trade with " + who + " complete");
    }

    /** Officers were unassigned when their ship was offered; nothing to restore (they stay in the officer pool). */
    private static void returnOfficers() {}

    // ------------------------------------------------------------------ packets
    public static void onPacket(int from, byte[] d) { inbox.add(new Object[]{from, d}); }

    private static void handle(int from, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get(); byte k = b.get();
        if (k == REQ) {
            String name = gs(b);
            if (active) { decline(from); return; }
            pending.put(from, name);
            incoming.add(new Object[]{from, name});
            StarMPPlugin.flog("trade: " + name + " (player " + from + ") wants to trade");
            return;
        }
        if (k == CANCEL && pending.remove(from) != null && from != peer) { FriendTradeIntel.closeFor(from); return; }
        if (from != peer) return; // not our trade
        if (k == ACCEPT) { if (waitingAccept) { waitingAccept = false; active = true; peerName = gs(b); lastEvent = "accepted"; TradeScreen.openFromAccept(); sendOffer(); } }
        else if (k == DECLINE) { if (waitingAccept) { lastEvent = "declined"; StarMPPlugin.flog("trade: declined by " + peerName); reset(); TradeScreen.finished(peerName + " declined the trade"); } }
        else if (k == CANCEL) { String who = peerName; StarMPPlugin.flog("trade: cancelled by " + who); reset(); TradeScreen.finished(who + " cancelled the trade"); }
        else if (k == CLEAR) { mine.items.clear(); mine.ships.clear(); mine.members.clear(); mine.credits = 0; changed(); }
        else if (k == OFFER) {
            Offer o = decodeOffer(b);
            boolean wasReady = theirs.ready;
            boolean contentChanged = o.version != theirs.version;
            theirs = o;
            if (contentChanged) { mine.ready = false; countdownNs = 0; sentCommit = false; gotCommit = false; sendOffer(); }
            if (!wasReady && theirs.ready) TradeScreen.play("ui_sensor_burst_on");
            checkStart();
        } else if (k == COMMIT) {
            int theirVer = b.getInt(), myVer = b.getInt();
            if (theirVer == theirs.version && myVer == mine.version) gotCommit = true;
            else StarMPPlugin.flog("trade: commit for old versions ignored");
        }
    }

    static void sendOffer() {
        if (peer < 0 || !active) return;
        send(StarMPPlugin.net(), peer, encodeOffer(mine));
    }

    private static byte[] encodeOffer(Offer o) {
        byte[] defs = Wire.encodeDefs(Wire.SHIPDEFS, -1, o.ships);
        int size = 2 + 4 + 1 + 4 + 4 + 2 + defs.length + 4;
        for (Item i : o.items) size += 1 + 4 + sl(i.id) + sl(i.data);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(TRADE); b.put(OFFER); b.putInt(o.version); b.put((byte) (o.ready ? 1 : 0)); b.putInt(o.readyForOther); b.putFloat(o.credits);
        b.putShort((short) o.items.size());
        for (Item i : o.items) { b.put((byte) i.type.ordinal()); b.putFloat(i.qty); ps(b, i.id); ps(b, i.data); }
        b.putInt(defs.length); b.put(defs);
        return done(b);
    }

    private static Offer decodeOffer(ByteBuffer b) {
        Offer o = new Offer();
        o.version = b.getInt(); o.ready = b.get() != 0; o.readyForOther = b.getInt(); o.credits = b.getFloat();
        int n = b.getShort() & 0xFFFF;
        for (int k = 0; k < n; k++) { Item i = new Item(); i.type = CargoAPI.CargoItemType.values()[b.get()]; i.qty = b.getFloat(); i.id = gs(b); i.data = gs(b); o.items.add(i); }
        byte[] defs = new byte[b.getInt()]; b.get(defs);
        o.ships.addAll(Wire.decodeDefs(defs));
        return o;
    }

    private static void send(NetManager n, int to, byte[] d) { if (n != null && n.linked()) CoopState.sendTo(n, to, d); }
    private static byte[] simple(byte k, String s) { ByteBuffer b = ByteBuffer.allocate(4 + s.length() * 3); b.put(TRADE); b.put(k); ps(b, s); return done(b); }
    private static int sl(String s) { return 2 + s.getBytes(StandardCharsets.UTF_8).length; }
    private static void ps(ByteBuffer b, String s) { byte[] x = s.getBytes(StandardCharsets.UTF_8); b.putShort((short) x.length); b.put(x); }
    private static String gs(ByteBuffer b) { byte[] x = new byte[b.getShort() & 0xFFFF]; b.get(x); return new String(x, StandardCharsets.UTF_8); }
    private static byte[] done(ByteBuffer b) { byte[] o = new byte[b.position()]; b.flip(); b.get(o); return o; }
    static void sendClear() { if (peer >= 0) send(StarMPPlugin.net(), peer, simple(CLEAR, "")); }
}
