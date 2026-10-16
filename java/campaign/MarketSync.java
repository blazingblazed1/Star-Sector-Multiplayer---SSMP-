package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import starmp.NetManager;
import starmp.StarMPPlugin;
import starmp.combat.CoopState;
import starmp.combat.Ships;
import starmp.combat.Wire;

/**
 * Shared markets (0.1.4.9). The host's market stock (all submarkets incl. storage, which is shared) is the real one.
 *  - A guest opening a market asks the host (REQ); the host refreshes it as if visited and sends the stock (SNAP); the
 *    guest's copy of that market is replaced with it.
 *  - A guest's trades (vanilla trade screen, reported by the game) are sent to the host (TX), which applies them to
 *    the real stock. Anything already gone there (someone else bought it first) comes back as a correction (FIX):
 *    the guest gives it back and is refunded. Credits are each player's own.
 *  - More players, more stock: when a submarket's weapons/ships stock rolls on the host, the new stock is scaled by
 *    1 + 0.4 x (connected players - 1).
 */
public final class MarketSync {
    private MarketSync() {}
    public static final byte MKT = 31;
    private static final byte REQ = 1, SNAP = 2, TX = 3, FIX = 4;
    private static final ConcurrentLinkedQueue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    private static final Map<SubmarketAPI, Float> lastSW = new WeakHashMap<>();
    private static Object requestedFor; // guest: the dialog we already asked about
    private static String waitingMarket;
    private static boolean testOpened, testTxSent;

    public static void onPacket(int peer, byte[] d) { inbox.add(new Object[]{peer, d}); }

    static float multiplier(NetManager net) { return 1f + 0.4f * Math.max(0, net.peerIds().size()); }

    /** Registers the transaction listener (guest side sends its trades). */
    static void install() {
        try {
            Global.getSector().addTransientListener(new BaseCampaignEventListener(false) {
                @Override public void reportPlayerMarketTransaction(PlayerMarketTransaction t) {
                    try { guestTransaction(t); } catch (Throwable e) { StarMPPlugin.flog("market tx error: " + StarMPPlugin.trace(e)); }
                }
            });
        } catch (Throwable t) { StarMPPlugin.flog("market sync: listener failed: " + t); }
    }

    /** Campaign thread, every frame while linked. */
    static void tick(NetManager net, SectorAPI sector, boolean hosting, boolean client) {
        Object[] m;
        while ((m = inbox.poll()) != null) {
            try { handle(net, sector, (Integer) m[0], (byte[]) m[1], hosting, client); }
            catch (Throwable t) { StarMPPlugin.flog("market sync: bad packet: " + StarMPPlugin.trace(t)); }
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        InteractionDialogAPI dlg = ui.getCurrentInteractionDialog();
        MarketAPI market = dlg == null || dlg.getInteractionTarget() == null ? null : dlg.getInteractionTarget().getMarket();
        if (market != null && market.isPlanetConditionMarketOnly()) market = null;
        if (hosting && market != null) checkRolls(net, market, "host visit");
        if (hosting && System.getenv("STARMP_TEST_HOST_DOCK") != null) testHostDock(net, sector, ui, dlg); // test hook
        if (System.getenv("STARMP_TEST_MEMPROBE") != null) memProbe(sector, dlg); // test hook
        if (client && !testOpened && System.getenv("STARMP_TEST_MARKET") != null && PlayerRecords.ready() && dlg == null && !ui.isShowingDialog()) { // test hook
            MarketAPI tm = sector.getEconomy().getMarket(System.getenv("STARMP_TEST_MARKET"));
            if (tm != null && tm.getPrimaryEntity() != null) { testOpened = true; StarMPPlugin.flog("market test: opening " + tm.getName()); ui.showInteractionDialog(tm.getPrimaryEntity()); return; }
        }
        if (client) {
            if (dlg == null) { requestedFor = null; return; }
            if (market != null && requestedFor != dlg) {
                requestedFor = dlg; waitingMarket = market.getId();
                CoopState.sendTo(net, 0, simple(REQ, market.getId()));
                StarMPPlugin.flog("market sync: asked the host for " + market.getName() + "'s stock");
            }
        }
    }

    private static Object probeDlg; private static long probeNs; private static int probeLogs;
    private static com.fs.starfarer.api.characters.PersonAPI probePerson;
    /** Test hook: while a dialog is open, a value set with timer 0 (like bar missions use) - does it survive? */
    private static void memProbe(SectorAPI sector, InteractionDialogAPI dlg) {
        if (dlg == null) { probeDlg = null; return; }
        long now = System.nanoTime();
        if (probeDlg != dlg) {
            probeDlg = dlg; probeNs = now;
            dlg.getInteractionTarget().getMemoryWithoutUpdate().set("$starmp_probe", true, 0f);
            probePerson = Global.getFactory().createPerson();
            probePerson.getMemoryWithoutUpdate().set("$starmp_probe", true, 0f);
            try { Global.getSector().getImportantPeople().addPerson(probePerson); } catch (Throwable t) { StarMPPlugin.flog("mem probe: " + t); }
            try { if (dlg.getInteractionTarget().getMarket() != null) dlg.getInteractionTarget().getMarket().addPerson(probePerson); } catch (Throwable t) { StarMPPlugin.flog("mem probe: " + t); }
            StarMPPlugin.flog("mem probe: set (paused=" + sector.isPaused() + ")");
            return;
        }
        if (now - probeNs > 1_000_000_000L && probeLogs < 40) {
            probeNs = now; probeLogs++;
            StarMPPlugin.flog("mem probe: target=" + dlg.getInteractionTarget().getMemoryWithoutUpdate().contains("$starmp_probe")
                    + " person=" + probePerson.getMemoryWithoutUpdate().contains("$starmp_probe") + " paused=" + sector.isPaused() + " date=" + sector.getClock().getDateString());
        }
    }

    private static long testDockNs, testDockLogNs; private static int testDockOpens; private static int testLastOpts = -1;
    /** Test hook (host): once a guest is linked, open the named market's dialog and log its option count every second. */
    private static void testHostDock(NetManager net, SectorAPI sector, CampaignUIAPI ui, InteractionDialogAPI dlg) {
        long now = System.nanoTime();
        if (dlg == null) {
            if (net.peerIds().isEmpty() || sector.getPlayerFleet() == null || sector.isPaused() || ui.isShowingDialog() || ui.getCurrentCoreTab() != null || testDockOpens >= 3) return;
            if (testDockNs == 0) { testDockNs = now; return; } // first ready frame: start the clock
            if (now - testDockNs < 10_000_000_000L) return;      // 10 s of running world first, and between opens
            MarketAPI tm = sector.getEconomy().getMarket(System.getenv("STARMP_TEST_HOST_DOCK"));
            if (tm == null || tm.getPrimaryEntity() == null) return;
            testDockNs = now; testDockOpens++; testLastOpts = -2;
            StarMPPlugin.flog("dock test: host opening " + tm.getName() + " (#" + testDockOpens + ")");
            ui.showInteractionDialog(tm.getPrimaryEntity());
            return;
        }
        int opts = -1;
        try { opts = dlg.getOptionPanel().getSavedOptionList().size(); } catch (Throwable ignored) {}
        if (opts != testLastOpts || now - testDockLogNs > 5_000_000_000L) {
            StarMPPlugin.flog("dock test: options " + testLastOpts + " -> " + opts + " paused=" + sector.isPaused() + " bg=" + BackgroundSim.wasRunning());
            testLastOpts = opts; testDockLogNs = now;
        }
    }

    /** Host: a submarket whose weapons/ships timer just reset has rolled new stock: scale it for the player count. */
    private static void checkRolls(NetManager net, MarketAPI market, String why) {
        for (SubmarketAPI sm : market.getSubmarketsCopy()) {
            if (!(sm.getPlugin() instanceof BaseSubmarketPlugin)) continue;
            float now = ((BaseSubmarketPlugin) sm.getPlugin()).getSinceSWUpdate();
            Float last = lastSW.put(sm, now);
            if (last != null && now < last - 0.01f) scale(net, sm, why);
        }
    }

    private static void scale(NetManager net, SubmarketAPI sm, String why) {
        float mult = multiplier(net);
        if (mult <= 1.001f) return;
        CargoAPI c = sm.getCargo();
        int stacks = 0, ships = 0;
        for (CargoStackAPI s : c.getStacksCopy()) {
            if (s.isNull() || s.getSize() <= 0) continue;
            float add = (float) Math.floor(s.getSize() * (mult - 1f));
            if (add >= 1f) { c.addItems(s.getType(), s.getData(), add); stacks++; }
        }
        FleetDataAPI fd = c.getMothballedShips();
        if (fd != null) {
            List<FleetMemberAPI> base = fd.getMembersListCopy();
            int extra = Math.round(base.size() * (mult - 1f));
            Random r = new Random();
            for (int i = 0; i < extra && !base.isEmpty(); i++) {
                FleetMemberAPI src = base.get(r.nextInt(base.size()));
                try { fd.addFleetMember(Global.getFactory().createFleetMember(FleetMemberType.SHIP, src.getVariant().clone())); ships++; } catch (Throwable ignored) {}
            }
        }
        StarMPPlugin.flog("market sync: " + sm.getMarket().getName() + " / " + sm.getNameOneLine() + " restocked (" + why + ") - scaled x" + String.format("%.1f", mult)
                + " for " + (1 + net.peerIds().size()) + " players (" + stacks + " stacks, +" + ships + " ships)");
    }

    // ------------------------------------------------------------------ packets
    private static void handle(NetManager net, SectorAPI sector, int peer, byte[] d, boolean hosting, boolean client) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get(); byte k = b.get();
        String marketId = gs(b);
        MarketAPI market = sector.getEconomy().getMarket(marketId);
        if (market == null) return;
        if (hosting && k == REQ) {
            for (SubmarketAPI sm : market.getSubmarketsCopy()) {
                if (sm.getPlugin() instanceof BaseSubmarketPlugin) lastSW.put(sm, ((BaseSubmarketPlugin) sm.getPlugin()).getSinceSWUpdate());
                try { sm.getPlugin().updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {} // as if visited
            }
            checkRolls(net, market, CoopState.nameOf(net, peer) + " visited");
            CoopState.sendTo(net, peer, snapshot(market));
        } else if (client && k == SNAP) {
            if (!marketId.equals(waitingMarket)) return;
            applySnapshot(market, b);
        } else if (hosting && k == TX) {
            applyTransaction(net, peer, market, b);
        } else if (client && k == FIX) {
            applyFix(sector, market, b);
        }
    }

    private static boolean skip(SubmarketAPI sm) { return "local_resources".equals(sm.getSpecId()); }

    private static byte[] snapshot(MarketAPI market) {
        Bytes o = new Bytes(); o.b(MKT).b(SNAP).s(market.getId());
        List<SubmarketAPI> subs = new ArrayList<>();
        for (SubmarketAPI sm : market.getSubmarketsCopy()) if (!skip(sm)) subs.add(sm);
        o.i(subs.size());
        for (SubmarketAPI sm : subs) {
            o.s(sm.getSpecId());
            CargoAPI c = sm.getCargo();
            List<CargoStackAPI> st = new ArrayList<>();
            for (CargoStackAPI s : c.getStacksCopy()) if (key(s) != null) st.add(s);
            o.i(st.size());
            for (CargoStackAPI s : st) { String[] kk = key(s); o.s(kk[0]).s(kk[1]).s(kk[2]).f(s.getSize()); }
            List<FleetMemberAPI> ships = c.getMothballedShips() == null ? Collections.<FleetMemberAPI>emptyList() : c.getMothballedShips().getMembersListCopy();
            byte[] defs = Wire.encodeDefs(Ships.fleetDefs(ships));
            o.i(defs.length).raw(defs);
        }
        return o.done();
    }

    private static void applySnapshot(MarketAPI market, ByteBuffer b) {
        int n = b.getInt(), total = 0, ships = 0;
        for (int i = 0; i < n; i++) {
            String spec = gs(b);
            SubmarketAPI sm = market.getSubmarket(spec);
            int ns = b.getInt();
            List<Object[]> stacks = new ArrayList<>();
            for (int j = 0; j < ns; j++) stacks.add(new Object[]{gs(b), gs(b), gs(b), b.getFloat()});
            byte[] defs = new byte[b.getInt()]; b.get(defs);
            if (sm == null) continue;
            CargoAPI c = sm.getCargo();
            c.clear();
            for (Object[] s : stacks) { if (add(c, (String) s[0], (String) s[1], (String) s[2], (Float) s[3])) total++; }
            if (c.getMothballedShips() == null) c.initMothballedShips(sm.getFaction().getId());
            FleetDataAPI fd = c.getMothballedShips();
            for (FleetMemberAPI mm : fd.getMembersListCopy()) fd.removeFleetMember(mm);
            int k = 0;
            for (Wire.ShipDef def : Wire.decodeDefs(defs)) {
                try { FleetMemberAPI mm = Ships.member(def, "mkt" + (k++)); mm.getStatus().setHullFraction(Math.max(0.05f, def.hullFrac)); fd.addFleetMember(mm); ships++; } catch (Throwable ignored) {}
            }
            // our own copy must not re-roll this stock when the trade screen opens
            if (sm.getPlugin() instanceof BaseSubmarketPlugin) { ((BaseSubmarketPlugin) sm.getPlugin()).setSinceSWUpdate(0f); ((BaseSubmarketPlugin) sm.getPlugin()).setSinceLastCargoUpdate(0f); }
        }
        StarMPPlugin.flog("market sync: " + market.getName() + " now shows the host's stock (" + total + " stacks, " + ships + " ships)");
        if (!testTxSent && "1".equals(System.getenv("STARMP_TEST_MARKET_TX"))) testTransaction(market); // test hook
    }

    // ---- guest trades
    private static void guestTransaction(PlayerMarketTransaction t) {
        NetManager net = StarMPPlugin.net();
        if (net == null || net.isHost() || net.state() != NetManager.State.CONNECTED || t.getMarket() == null || t.getSubmarket() == null) return;
        Bytes o = new Bytes(); o.b(MKT).b(TX).s(t.getMarket().getId()).s(t.getSubmarket().getSpecId());
        writeStacks(o, t.getBought(), t);
        writeStacks(o, t.getSold(), null);
        List<Wire.ShipDef> bought = new ArrayList<>(), sold = new ArrayList<>();
        List<Float> boughtPrice = new ArrayList<>();
        for (PlayerMarketTransaction.ShipSaleInfo s : t.getShipsBought()) { bought.add(Ships.def(0, 0, s.getMember())); boughtPrice.add(s.getPrice()); }
        for (PlayerMarketTransaction.ShipSaleInfo s : t.getShipsSold()) sold.add(Ships.def(0, 0, s.getMember()));
        byte[] bd = Wire.encodeDefs(bought), sd = Wire.encodeDefs(sold);
        o.i(bd.length).raw(bd).i(sd.length).raw(sd).i(boughtPrice.size());
        for (Float p : boughtPrice) o.f(p);
        CoopState.sendTo(net, 0, o.done());
        StarMPPlugin.flog("market sync: sent my trade at " + t.getMarket().getName() + " / " + t.getSubmarket().getNameOneLine() + " to the host");
    }

    /** {type, id, data, qty, unitPrice} per stack. */
    private static void writeStacks(Bytes o, CargoAPI cargo, PlayerMarketTransaction prices) {
        List<CargoStackAPI> st = new ArrayList<>();
        if (cargo != null) for (CargoStackAPI s : cargo.getStacksCopy()) if (key(s) != null) st.add(s);
        o.i(st.size());
        for (CargoStackAPI s : st) {
            String[] k = key(s);
            float unit = 0f;
            if (prices != null) for (PlayerMarketTransaction.TransactionLineItem li : prices.getLineItems())
                if (k[1].equals(li.getId()) && li.getQuantity() > 0) { unit = li.getPrice() / li.getQuantity(); break; }
            o.s(k[0]).s(k[1]).s(k[2]).f(s.getSize()).f(unit);
        }
    }

    // ---- host: apply a guest's trade to the real stock
    private static void applyTransaction(NetManager net, int peer, MarketAPI market, ByteBuffer b) {
        SubmarketAPI sm = market.getSubmarket(gs(b));
        int nb = b.getInt();
        List<Object[]> bought = new ArrayList<>();
        for (int i = 0; i < nb; i++) bought.add(new Object[]{gs(b), gs(b), gs(b), b.getFloat(), b.getFloat()});
        int ns = b.getInt();
        List<Object[]> sold = new ArrayList<>();
        for (int i = 0; i < ns; i++) sold.add(new Object[]{gs(b), gs(b), gs(b), b.getFloat(), b.getFloat()});
        byte[] bd = new byte[b.getInt()]; b.get(bd);
        byte[] sd = new byte[b.getInt()]; b.get(sd);
        int np = b.getInt(); float[] shipPrice = new float[np]; for (int i = 0; i < np; i++) shipPrice[i] = b.getFloat();
        if (sm == null) return;
        CargoAPI c = sm.getCargo();
        String who = CoopState.nameOf(net, peer);
        List<Object[]> missing = new ArrayList<>(); float refund = 0f;
        for (Object[] s : bought) {
            Object data = data((String) s[0], (String) s[1], (String) s[2]);
            CargoAPI.CargoItemType type = type((String) s[0]);
            if (data == null || type == null) continue;
            float want = (Float) s[3], have = c.getQuantity(type, data), take = Math.min(want, have);
            if (take > 0) c.removeItems(type, data, take);
            if (want - take > 0.01f) { missing.add(new Object[]{s[0], s[1], s[2], want - take}); refund += (want - take) * (Float) s[4]; }
        }
        for (Object[] s : sold) add(c, (String) s[0], (String) s[1], (String) s[2], (Float) s[3]);
        if (c.getMothballedShips() == null) c.initMothballedShips(sm.getFaction().getId());
        FleetDataAPI fd = c.getMothballedShips();
        List<Wire.ShipDef> bShips = Wire.decodeDefs(bd);
        List<String> missingShips = new ArrayList<>();
        for (int i = 0; i < bShips.size(); i++) {
            Wire.ShipDef def = bShips.get(i);
            FleetMemberAPI found = null;
            for (FleetMemberAPI mm : fd.getMembersListCopy())
                if (mm.getHullSpec().getHullId().equals(def.hullId) && (def.name.isEmpty() || def.name.equals(mm.getShipName()))) { found = mm; break; }
            if (found == null) for (FleetMemberAPI mm : fd.getMembersListCopy()) if (mm.getHullSpec().getHullId().equals(def.hullId)) { found = mm; break; }
            if (found != null) fd.removeFleetMember(found);
            else { missingShips.add(def.hullId + "\t" + def.name); refund += i < shipPrice.length ? shipPrice[i] : 0f; }
        }
        int k = 0;
        for (Wire.ShipDef def : Wire.decodeDefs(sd)) { try { fd.addFleetMember(Ships.member(def, "sold" + (k++))); } catch (Throwable ignored) {} }
        StarMPPlugin.flog("market sync: applied " + who + "'s trade at " + market.getName() + " / " + sm.getNameOneLine() + " (" + bought.size() + " bought, " + sold.size() + " sold stacks, "
                + bShips.size() + "/" + k + " ships)" + (missing.isEmpty() && missingShips.isEmpty() ? "" : " - " + (missing.size() + missingShips.size()) + " item(s) already gone, refunding " + Math.round(refund)));
        if (missing.isEmpty() && missingShips.isEmpty()) return;
        Bytes o = new Bytes(); o.b(MKT).b(FIX).s(market.getId()).i(missing.size());
        for (Object[] s : missing) o.s((String) s[0]).s((String) s[1]).s((String) s[2]).f((Float) s[3]);
        o.i(missingShips.size()); for (String x : missingShips) o.s(x);
        o.f(refund);
        CoopState.sendTo(net, peer, o.done());
    }

    // ---- guest: give back what was already gone on the host, get the money back
    private static void applyFix(SectorAPI sector, MarketAPI market, ByteBuffer b) {
        CampaignFleetAPI me = sector.getPlayerFleet();
        CargoAPI c = me.getCargo();
        List<String> names = new ArrayList<>();
        int n = b.getInt();
        for (int i = 0; i < n; i++) {
            String t = gs(b), id = gs(b), dt = gs(b); float q = b.getFloat();
            Object data = data(t, id, dt); CargoAPI.CargoItemType type = type(t);
            if (data == null || type == null) continue;
            c.removeItems(type, data, Math.min(q, c.getQuantity(type, data)));
            names.add(Math.round(q) + "x " + id);
        }
        int ns = b.getInt();
        for (int i = 0; i < ns; i++) {
            String[] hn = gs(b).split("\t", -1);
            for (FleetMemberAPI mm : me.getFleetData().getMembersListCopy())
                if (!mm.isFlagship() && mm.getHullSpec().getHullId().equals(hn[0]) && (hn.length < 2 || hn[1].isEmpty() || hn[1].equals(mm.getShipName()))) { me.getFleetData().removeFleetMember(mm); names.add(mm.getShipName()); break; }
        }
        float refund = b.getFloat();
        if (refund > 0) c.getCredits().add(refund);
        String msg = "StarMP: another player got there first - " + String.join(", ", names) + " went back, refunded " + Math.round(refund) + " credits";
        sector.getCampaignUI().addMessage(msg);
        StarMPPlugin.flog("market sync: " + msg);
    }

    /** Test hook: "buy" 3 more of the open market's first commodity than the host has -> expect a FIX refund. */
    private static void testTransaction(MarketAPI market) {
        testTxSent = true;
        SubmarketAPI sm = market.getSubmarket("open_market");
        if (sm == null) return;
        CargoStackAPI first = null;
        for (CargoStackAPI s : sm.getCargo().getStacksCopy()) if (s.isCommodityStack() && s.getSize() >= 1) { first = s; break; }
        if (first == null) return;
        float q = first.getSize() + 3f;
        PlayerMarketTransaction t = new PlayerMarketTransaction(market, sm, CampaignUIAPI.CoreUITradeMode.OPEN);
        CargoAPI bought = Global.getFactory().createCargo(true);
        bought.addCommodity(first.getCommodityId(), q);
        t.setBought(bought);
        t.getLineItems().add(new PlayerMarketTransaction.TransactionLineItem(first.getCommodityId(), PlayerMarketTransaction.LineItemType.BOUGHT, CargoAPI.CargoItemType.RESOURCES, sm, q, q * 10f, 0f, 0L));
        Global.getSector().getPlayerFleet().getCargo().addCommodity(first.getCommodityId(), q);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(q * 10f);
        StarMPPlugin.flog("market test: buying " + q + " " + first.getCommodityId() + " (host has " + first.getSize() + ")");
        guestTransaction(t);
    }

    // ------------------------------------------------------------------ helpers
    private static String[] key(CargoStackAPI s) {
        if (s == null || s.isNull()) return null;
        switch (s.getType()) {
            case RESOURCES: return new String[]{"R", s.getCommodityId(), ""};
            case WEAPONS: return new String[]{"W", s.getWeaponSpecIfWeapon().getWeaponId(), ""};
            case FIGHTER_CHIP: return new String[]{"F", s.getFighterWingSpecIfWing().getId(), ""};
            case SPECIAL: { SpecialItemData d = s.getSpecialDataIfSpecial(); return new String[]{"S", d.getId(), d.getData() == null ? "" : d.getData()}; }
            default: return null;
        }
    }
    private static CargoAPI.CargoItemType type(String t) {
        switch (t) { case "R": return CargoAPI.CargoItemType.RESOURCES; case "W": return CargoAPI.CargoItemType.WEAPONS;
            case "F": return CargoAPI.CargoItemType.FIGHTER_CHIP; case "S": return CargoAPI.CargoItemType.SPECIAL; default: return null; }
    }
    private static Object data(String t, String id, String dt) { return "S".equals(t) ? new SpecialItemData(id, dt.isEmpty() ? null : dt) : id; }
    private static boolean add(CargoAPI c, String t, String id, String dt, float q) {
        CargoAPI.CargoItemType type = type(t);
        if (type == null || q <= 0) return false;
        try { c.addItems(type, data(t, id, dt), q); return true; } catch (Throwable e) { return false; }
    }
    private static byte[] simple(byte kind, String s) { return new Bytes().b(MKT).b(kind).s(s).done(); }
    private static String gs(ByteBuffer b) { int l = b.getInt(); if (l < 0 || l > b.remaining()) return ""; byte[] x = new byte[l]; b.get(x); return new String(x, StandardCharsets.UTF_8); }

    /** Tiny growable writer. */
    private static final class Bytes {
        private ByteBuffer b = ByteBuffer.allocate(4096);
        private void room(int n) { if (b.remaining() < n) { ByteBuffer nb = ByteBuffer.allocate(Math.max(b.capacity() * 2, b.position() + n + 1024)); b.flip(); nb.put(b); b = nb; } }
        Bytes b(byte v) { room(1); b.put(v); return this; }
        Bytes i(int v) { room(4); b.putInt(v); return this; }
        Bytes f(float v) { room(4); b.putFloat(v); return this; }
        Bytes s(String v) { byte[] x = (v == null ? "" : v).getBytes(StandardCharsets.UTF_8); room(4 + x.length); b.putInt(x.length); b.put(x); return this; }
        Bytes raw(byte[] x) { room(x.length); b.put(x); return this; }
        byte[] done() { byte[] o = new byte[b.position()]; b.flip(); b.get(o); return o; }
    }
}
