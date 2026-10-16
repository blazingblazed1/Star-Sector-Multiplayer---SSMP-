package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
import com.fs.starfarer.api.campaign.SpecialItemData;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * Loot split for joint battles (Mike's rule):
 *  - 50% of the loot is a "damage dealt" pool, 50% a "damage taken" pool.
 *  - Each pool is shared by every player who was in the fight, by their share of that kind of damage
 *    (joining late doesn't matter). If nobody dealt (or took) any damage, that pool is split evenly.
 *  - 0.1.4.10: each pool percentage is rounded UP to the next 5% (4.27% -> 5%, 0% stays 0%); a player's share is
 *    half of each, rounded up. Shares may add up to more than 100% - a little duplicated loot is fine (Mike).
 *  - Amounts are rounded up; when rounding up needs more items than dropped, copies are made.
 *  - Reputation is not divided: every player in the fight gets the same standing change the owner got (RepShare).
 * The battle owner computes it and keeps their share in the vanilla loot screen; the others get theirs by packet.
 */
public final class LootSplit {
    private LootSplit() {}

    public static final byte LOOT = 15; // owner -> guest: your share of the loot

    /** Combat side: damage per player id in the current joint battle: {dealt, taken}. */
    private static final Map<Integer, double[]> dmg = new LinkedHashMap<>();
    /** Shares (percent) from the last finished joint battle, waiting for the loot screen. */
    private static volatile Map<Integer, Integer> pendingShares;
    private static volatile long pendingAtMs;
    /** Shares of the last joint battle, for ships recovered after it (cleared once used). */
    private static volatile Map<Integer, Integer> recoveryShares;
    /** Other credits the battle paid the owner (bounties, commission pay per kill...): measured as the owner's credit
     *  change from the end of the battle to the close of the post-battle dialog, minus the loot credits we already split. */
    private static volatile Map<Integer, Integer> incomeShares;
    private static volatile float incomeCreditsAtEnd, incomeKnownAdds;
    private static volatile long incomeWatchMs;
    private static volatile boolean incomeSeenDialog;
    public static final byte SHIPS = 16; // owner -> guest: recovered ships that are theirs

    public static synchronized void battleStarted(int ownerId) { dmg.clear(); dmg.put(ownerId, new double[2]); }
    public static synchronized void addPlayer(int id) { dmg.putIfAbsent(id, new double[2]); }
    public static synchronized void dealt(int id, double amount) { double[] d = dmg.get(id); if (d != null && amount > 0) d[0] += amount; }
    public static synchronized void taken(int id, double amount) { double[] d = dmg.get(id); if (d != null && amount > 0) d[1] += amount; }

    private static volatile Map<Integer, Integer> repShares;
    /** The participants of the last joint battle, for RepShare (once). */
    static Map<Integer, Integer> takeRepShares() { Map<Integer, Integer> s = repShares; repShares = null; return s; }

    /** Round up to the next 5% (0 stays 0). */
    static long tier(double pct) { if (pct <= 1e-6) return 0; return Math.min(100, (long) (Math.ceil(pct / 5.0 - 1e-9) * 5)); }

    /** Called by the owner when a joint battle ends. */
    public static synchronized void battleEnded() {
        if (dmg.size() < 2) { pendingShares = null; return; }
        double sumD = 0, sumT = 0;
        for (double[] d : dmg.values()) { sumD += d[0]; sumT += d[1]; }
        int n = dmg.size();
        Map<Integer, Integer> shares = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("loot split:");
        for (Map.Entry<Integer, double[]> e : dmg.entrySet()) {
            long dealtPct = tier(sumD > 0 ? 100.0 * e.getValue()[0] / sumD : 100.0 / n);
            long takenPct = tier(sumT > 0 ? 100.0 * e.getValue()[1] / sumT : 100.0 / n);
            int share = (int) Math.ceil(0.5 * dealtPct + 0.5 * takenPct);
            shares.put(e.getKey(), share);
            sb.append(" player ").append(e.getKey()).append(": dealt ").append(Math.round(e.getValue()[0])).append(" (").append(dealtPct)
              .append("%) taken ").append(Math.round(e.getValue()[1])).append(" (").append(takenPct).append("%) -> ").append(share).append("% of the loot;");
        }
        StarMPPlugin.flog(sb.toString());
        pendingShares = shares;
        repShares = shares;
        pendingAtMs = System.currentTimeMillis();
        recoveryShares = shares;
        incomeShares = shares; incomeKnownAdds = 0; incomeSeenDialog = false;
        try { incomeCreditsAtEnd = Global.getSector().getPlayerFleet().getCargo().getCredits().get(); incomeWatchMs = System.currentTimeMillis(); }
        catch (Throwable t) { incomeShares = null; }
    }

    /** Campaign listener on the battle owner's side: the loot screen's cargo is cut to the owner's share and each
     *  other player is sent theirs. */
    public static final class Listener extends BaseCampaignEventListener {
        public Listener() { super(false); }
        @Override
        public void reportEncounterLootGenerated(FleetEncounterContextPlugin plugin, CargoAPI loot) {
            try { split(plugin, loot); } catch (Throwable t) { StarMPPlugin.flog("loot split failed: " + StarMPPlugin.trace(t)); }
        }
    }

    /** Campaign thread, every frame: once the post-battle dialog has closed, split any other credits the battle paid. */
    static void checkOtherIncome() {
        Map<Integer, Integer> shares = incomeShares;
        if (shares == null) return;
        if (System.currentTimeMillis() - incomeWatchMs > 30 * 60_000L) { incomeShares = null; return; }
        boolean dialogOpen = Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null;
        if (dialogOpen) { incomeSeenDialog = true; return; }
        if (!incomeSeenDialog || Global.getCurrentState() != com.fs.starfarer.api.GameState.CAMPAIGN) return;
        incomeShares = null;
        NetManager net = StarMPPlugin.net();
        if (net == null) return;
        com.fs.starfarer.api.util.MutableValue cr = Global.getSector().getPlayerFleet().getCargo().getCredits();
        float other = cr.get() - incomeCreditsAtEnd - incomeKnownAdds;
        StarMPPlugin.flog("battle income: credits " + Math.round(incomeCreditsAtEnd) + " -> " + Math.round(cr.get()) + ", loot credits already split " + Math.round(incomeKnownAdds) + ", other income " + Math.round(other));
        if (other < 1f) return;
        int me = net.myId();
        Integer mine = shares.get(me);
        if (mine == null) return;
        for (Map.Entry<Integer, Integer> e : shares.entrySet()) {
            if (e.getKey() == me) continue;
            float c = (float) Math.ceil(other * e.getValue() / 100.0);
            starmp.combat.CoopState.sendTo(net, e.getKey(), encode(c, new ArrayList<Object[]>(), e.getValue(), true));
            StarMPPlugin.flog("battle income: sent player " + e.getKey() + " their " + e.getValue() + "% = " + Math.round(c) + " credits (bounties etc.)");
        }
        float keep = (float) Math.ceil(other * mine / 100.0);
        cr.add(keep - other);
        Global.getSector().getCampaignUI().addMessage("StarMP: battle bounties split - you keep " + Math.round(keep) + " of " + Math.round(other) + " credits");
    }

    public static void register() {
        Global.getSector().addTransientListener(new Listener());
        Global.getSector().getListenerManager().removeListenerOfClass(Recovery.class);
        Global.getSector().getListenerManager().addListener(new Recovery(), true);
    }

    /** Ships recovered after a joint battle are dealt out by value so each player's part of the total value comes as
     *  close as possible to their loot share: most valuable ship first, each to whoever is furthest below their share. */
    public static final class Recovery implements com.fs.starfarer.api.campaign.listeners.ShipRecoveryListener {
        @Override
        public void reportShipsRecovered(List<com.fs.starfarer.api.fleet.FleetMemberAPI> ships, com.fs.starfarer.api.campaign.InteractionDialogAPI dialog) {
            try { splitShips(ships, dialog); } catch (Throwable t) { StarMPPlugin.flog("ship split failed: " + StarMPPlugin.trace(t)); }
        }
    }

    private static void splitShips(List<com.fs.starfarer.api.fleet.FleetMemberAPI> ships, com.fs.starfarer.api.campaign.InteractionDialogAPI dialog) {
        Map<Integer, Integer> shares = recoveryShares;
        NetManager net = StarMPPlugin.net();
        if (shares == null || net == null || ships == null || ships.isEmpty()) return;
        recoveryShares = null;
        int me = net.myId();
        List<com.fs.starfarer.api.fleet.FleetMemberAPI> sorted = new ArrayList<>(ships);
        sorted.sort((a, b) -> Float.compare(b.getBaseValue(), a.getBaseValue()));
        double total = 0; for (com.fs.starfarer.api.fleet.FleetMemberAPI m : sorted) total += m.getBaseValue();
        double shareSum = 0; for (int v : shares.values()) shareSum += v;
        Map<Integer, Double> got = new HashMap<>();
        Map<Integer, List<starmp.combat.Wire.ShipDef>> out = new HashMap<>();
        StringBuilder sb = new StringBuilder("ship split:");
        for (com.fs.starfarer.api.fleet.FleetMemberAPI m : sorted) {
            int best = me; double bestGap = -1e18;
            for (Map.Entry<Integer, Integer> e : shares.entrySet()) {
                double gap = total * e.getValue() / shareSum - got.getOrDefault(e.getKey(), 0.0);
                if (gap > bestGap) { bestGap = gap; best = e.getKey(); }
            }
            got.merge(best, (double) m.getBaseValue(), Double::sum);
            sb.append(' ').append(m.getHullSpec().getHullId()).append("->").append(best);
            if (best == me) continue;
            starmp.combat.Wire.ShipDef d = starmp.combat.Ships.def(0, 0, m);
            out.computeIfAbsent(best, k -> new ArrayList<>()).add(d);
            Global.getSector().getPlayerFleet().getFleetData().removeFleetMember(m);
            if (dialog != null) dialog.getTextPanel().addPara("StarMP: the " + m.getHullSpec().getHullNameWithDashClass() + " goes to " + playerName(net, best) + ".");
        }
        StarMPPlugin.flog(sb.toString());
        for (Map.Entry<Integer, List<starmp.combat.Wire.ShipDef>> e : out.entrySet()) {
            starmp.combat.CoopState.sendTo(net, e.getKey(), starmp.combat.Wire.encodeDefs(SHIPS, 0, e.getValue()));
            StarMPPlugin.flog("ship split: sent " + e.getValue().size() + " recovered ships to player " + e.getKey());
        }
    }

    private static String playerName(NetManager net, int id) {
        try { NetManager.Peer p = net.peer(id); if (p != null && p.name != null) return p.name; } catch (Throwable ignored) {}
        return "player " + id;
    }

    public static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> receivedShips = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Guest side: adds the recovered ships we were given to our fleet. */
    static String applyShips(byte[] d, com.fs.starfarer.api.campaign.CampaignFleetAPI fleet) {
        List<starmp.combat.Wire.ShipDef> defs = starmp.combat.Wire.decodeDefs(d);
        List<String> names = new ArrayList<>();
        for (starmp.combat.Wire.ShipDef def : defs) {
            try {
                com.fs.starfarer.api.fleet.FleetMemberAPI m = starmp.combat.Ships.member(def, "rec" + System.nanoTime());
                m.setOwner(0);
                fleet.getFleetData().addFleetMember(m);
                m.getStatus().setHullFraction(Math.max(0.05f, Math.min(1f, def.hullFrac)));
                m.getRepairTracker().setCR(Math.max(0f, def.cr));
                names.add(m.getHullSpec().getHullNameWithDashClass());
            } catch (Throwable t) { StarMPPlugin.flog("ship split: could not add " + def.hullId + ": " + t); }
        }
        StarMPPlugin.flog("ship split: received " + names);
        return "StarMP: recovered ships added to your fleet: " + String.join(", ", names);
    }

    private static void split(FleetEncounterContextPlugin plugin, CargoAPI loot) {
        Map<Integer, Integer> shares = pendingShares;
        if (shares == null || System.currentTimeMillis() - pendingAtMs > 15 * 60_000L) return;
        pendingShares = null;
        NetManager net = StarMPPlugin.net();
        if (net == null) return;
        int me = net.myId();
        Integer mine = shares.get(me);
        if (mine == null) return;
        float credits = loot.getCredits().get();
        // credits salvaged from wrecks ("CPU cores") are paid straight to the player, not put in the loot cargo
        int salvageCredits = 0;
        try { if (plugin instanceof com.fs.starfarer.api.impl.campaign.FleetEncounterContext) salvageCredits = ((com.fs.starfarer.api.impl.campaign.FleetEncounterContext) plugin).getCreditsLooted(); } catch (Throwable ignored) {}
        List<CargoStackAPI> stacks = loot.getStacksCopy();
        // the others' parts, computed from the original amounts
        for (Map.Entry<Integer, Integer> e : shares.entrySet()) {
            if (e.getKey() == me) continue;
            int pct = e.getValue();
            List<Object[]> items = new ArrayList<>();
            for (CargoStackAPI s : stacks) {
                int q = (int) Math.ceil(s.getSize() * pct / 100.0);
                if (q <= 0) continue;
                String[] k = key(s);
                if (k != null) items.add(new Object[]{k[0], k[1], k[2], q});
            }
            float c = (float) Math.ceil(credits * pct / 100.0) + (float) Math.ceil(salvageCredits * pct / 100.0);
            starmp.combat.CoopState.sendTo(net, e.getKey(), encode(c, items, pct));
            StarMPPlugin.flog("loot: sent player " + e.getKey() + " their " + pct + "% (" + Math.round(c) + " credits, " + items.size() + " item stacks)");
        }
        // our part: change each stack in the loot screen to our rounded-up amount (copies made if needed)
        for (CargoStackAPI s : stacks) {
            float want = (float) Math.ceil(s.getSize() * mine / 100.0);
            float diff = want - s.getSize();
            if (diff > 0) loot.addItems(s.getType(), s.getData(), diff);
            else if (diff < 0) loot.removeItems(s.getType(), s.getData(), -diff);
        }
        loot.getCredits().set((float) Math.ceil(credits * mine / 100.0));
        incomeKnownAdds += loot.getCredits().get(); // assumes the loot screen's credits get taken
        if (salvageCredits > 0) {
            // the game pays (or has paid) us all of it; keep only our share. Same result whichever happens first.
            float keep = (float) Math.ceil(salvageCredits * mine / 100.0);
            incomeKnownAdds += keep;
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(keep - salvageCredits);
            StarMPPlugin.flog("loot: salvage credits " + salvageCredits + ", we keep " + Math.round(keep));
        }
        loot.removeEmptyStacks();
        StarMPPlugin.flog("loot: kept our " + mine + "% (" + Math.round(loot.getCredits().get()) + " of " + Math.round(credits) + " credits)");
    }

    /** {type, id, specialData} or null for kinds we can't send. */
    private static String[] key(CargoStackAPI s) {
        switch (s.getType()) {
            case RESOURCES: return new String[]{"R", s.getCommodityId(), ""};
            case WEAPONS: return new String[]{"W", s.getWeaponSpecIfWeapon().getWeaponId(), ""};
            case FIGHTER_CHIP: return new String[]{"F", s.getFighterWingSpecIfWing().getId(), ""};
            case SPECIAL: { SpecialItemData d = s.getSpecialDataIfSpecial(); return new String[]{"S", d.getId(), d.getData() == null ? "" : d.getData()}; }
            default: return null;
        }
    }

    // ---- packet
    private static void ps(ByteBuffer b, String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); b.putShort((short) d.length); b.put(d); }
    private static String gs(ByteBuffer b) { byte[] d = new byte[b.getShort() & 0xFFFF]; b.get(d); return new String(d, StandardCharsets.UTF_8); }

    static byte[] encode(float credits, List<Object[]> items, int pct) { return encode(credits, items, pct, false); }

    static byte[] encode(float credits, List<Object[]> items, int pct, boolean bounty) {
        int size = 1 + 4 + 1 + 2;
        for (Object[] it : items) size += 3 * 2 + 4 + ((String) it[0]).length() * 4 + ((String) it[1]).length() * 4 + ((String) it[2]).length() * 4;
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(LOOT); b.putFloat(credits); b.put((byte) ((pct & 0x7F) | (bounty ? 0x80 : 0))); b.putShort((short) items.size());
        for (Object[] it : items) { ps(b, (String) it[0]); ps(b, (String) it[1]); ps(b, (String) it[2]); b.putInt((Integer) it[3]); }
        byte[] out = new byte[b.position()]; b.flip(); b.get(out); return out;
    }

    /** Guest side: queued and applied on the campaign thread. */
    public static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> received = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Applies a received share to the player's fleet; returns a summary line. */
    static String apply(byte[] d, CargoAPI cargo) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        float credits = b.getFloat(); int pb = b.get() & 0xFF; int pct = pb & 0x7F; boolean bounty = (pb & 0x80) != 0; int n = b.getShort() & 0xFFFF;
        int count = 0;
        for (int i = 0; i < n; i++) {
            String t = gs(b), id = gs(b), data = gs(b); int q = b.getInt();
            if (q <= 0 || q > 1_000_000) continue;
            try {
                switch (t) {
                    case "R": cargo.addCommodity(id, q); break;
                    case "W": cargo.addWeapons(id, q); break;
                    case "F": cargo.addFighters(id, q); break;
                    case "S": cargo.addSpecial(new SpecialItemData(id, data.isEmpty() ? null : data), q); break;
                    default: continue;
                }
                count += q;
            } catch (Throwable ex) { StarMPPlugin.flog("loot: could not add " + t + " " + id + ": " + ex); }
        }
        if (credits > 0 && credits < 1e9f) cargo.getCredits().add(credits);
        StarMPPlugin.flog("loot: received our " + pct + "% share" + (bounty ? " of the bounties" : "") + ": " + Math.round(credits) + " credits, " + count + " items");
        if (bounty) return "StarMP: your " + pct + "% share of the battle's bounties: " + Math.round(credits) + " credits";
        return "StarMP: your " + pct + "% share of the battle loot: " + Math.round(credits) + " credits and " + count + " items";
    }
}
