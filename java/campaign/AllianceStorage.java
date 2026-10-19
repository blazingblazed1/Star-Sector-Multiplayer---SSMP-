package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONArray;
import org.json.JSONObject;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.2.4: alliance storage. Opened from a station's main menu ("Alliance storage") at any market with a Military Base
 * or High Command whose faction the ALLIANCE is +40 or better with. Items: 30 slots, any amount per slot. Ships: 20
 * (officers stay in your fleet). Host-authoritative: every put / take is done by the host, which keeps the storage in
 * its save (plain text, sector persistent data "starmp_ally_storage"), so nothing can be duplicated.
 *
 * Put: my game removes the items / ships first, then asks the host; whatever doesn't fit comes back.
 * Take: my game asks the host; I get only what the host could hand out.
 *
 * STORE (47)  any -> host:      [47][json] {"op":"view"|"put"|"take","market":id,"items":[[t,id,data,q]],"ships":[line],"sids":[sid]}
 * STORED (48) host -> member:   [48][json] {"ally":id,"items":[[t,id,data,q]],"ships":[[sid,line]],"note":..,"got":{...},"back":{...}}
 */
public final class AllianceStorage {
    private AllianceStorage() {}
    public static final byte STORE = 47, STORED = 48;
    public static final int ITEM_SLOTS = 30, SHIP_SLOTS = 20;
    public static final float MIN_REP = 0.40f;
    private static final String KEY = "starmp_ally_storage";

    /** What a game knows of its alliance's storage (from the host). */
    static final class View { String ally = ""; final List<String[]> items = new ArrayList<>(); final List<String[]> ships = new ArrayList<>(); long when; }
    static volatile View view = new View();
    static volatile String note;
    private static final ConcurrentLinkedQueue<Object[]> inbox = new ConcurrentLinkedQueue<>();

    // ------------------------------------------------------------------ where
    static boolean military(MarketAPI m) {
        if (m == null) return false;
        for (Industry i : m.getIndustries()) {
            String id = i.getId();
            if (("militarybase".equals(id) || "highcommand".equals(id)) && !i.isDisrupted()) return true;
        }
        return false;
    }

    static float allianceRep(Alliances.Ally a, String faction) { Float f = a == null ? null : a.npc.get(faction); return f == null ? 0f : f; }

    /** Can I use alliance storage at this market right now? */
    public static boolean available(MarketAPI m) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked() || m == null) return false;
        Alliances.Ally a = Alliances.mine();
        return a != null && military(m) && allianceRep(a, m.getFactionId()) >= MIN_REP - 0.0001f;
    }

    // ------------------------------------------------------------------ host storage
    @SuppressWarnings("unchecked")
    private static Map<String, String> store() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(KEY);
        if (!(o instanceof Map)) { o = new HashMap<String, String>(); pd.put(KEY, o); }
        return (Map<String, String>) o;
    }

    /** Host: an alliance's storage. Lines "I\tt\tid\tdata\tq" and "H\tsid\t<ship line>". */
    private static View load(String ally) {
        View v = new View(); v.ally = ally;
        String body = store().get(ally);
        if (body == null) return v;
        for (String line : body.split("\n")) {
            if (line.startsWith("I\t")) { String[] a = line.split("\t", -1); if (a.length >= 5) v.items.add(new String[]{a[1], a[2], a[3], a[4]}); }
            else if (line.startsWith("H\t")) { int k = line.indexOf('\t', 2); if (k > 0) v.ships.add(new String[]{line.substring(2, k), line.substring(k + 1)}); }
        }
        return v;
    }

    private static void save(View v) {
        StringBuilder sb = new StringBuilder();
        for (String[] i : v.items) sb.append("I\t").append(i[0]).append('\t').append(i[1]).append('\t').append(i[2]).append('\t').append(i[3]).append('\n');
        for (String[] s : v.ships) sb.append("H\t").append(s[0]).append('\t').append(s[1]).append('\n');
        store().put(v.ally, sb.toString());
    }

    private static float q(String s) { try { return Float.parseFloat(s); } catch (Throwable t) { return 0f; } }
    private static String fmt(float f) { return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f); }
    private static boolean sameKind(String[] a, String t, String id, String data) { return a[0].equals(t) && a[1].equals(id) && a[2].equals(data == null ? "" : data); }

    /** Host: runs a member's action. Returns the reply to send them. */
    private static JSONObject hostRun(String who, JSONObject c) throws Exception {
        JSONObject r = new JSONObject();
        Alliances.Ally a = Alliances.of(who);
        String op = c.optString("op");
        JSONObject back = new JSONObject(), got = new JSONObject();
        JSONArray backItems = new JSONArray(), backShips = new JSONArray(), gotItems = new JSONArray(), gotShips = new JSONArray();
        MarketAPI m = Global.getSector().getEconomy().getMarket(c.optString("market"));
        boolean ok = a != null && (op.equals("view") || (military(m) && allianceRep(a, m.getFactionId()) >= MIN_REP - 0.0001f));
        if (!ok) {
            r.put("note", a == null ? "You are not in an alliance." : "Alliance storage isn't available here.");
            if (op.equals("put")) { r.put("back", c); } // give everything back
            return r;
        }
        View v = load(a.id);
        if (op.equals("put")) {
            JSONArray items = c.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONArray it = items.getJSONArray(i);
                String t = it.optString(0), id = it.optString(1), data = it.optString(2); float amt = (float) it.optDouble(3, 0);
                if (amt <= 0) continue;
                String[] slot = null; for (String[] x : v.items) if (sameKind(x, t, id, data)) slot = x;
                if (slot == null && v.items.size() < ITEM_SLOTS) { slot = new String[]{t, id, data, "0"}; v.items.add(slot); }
                if (slot == null) { backItems.put(it); continue; }
                slot[3] = fmt(q(slot[3]) + amt);
            }
            JSONArray ships = c.optJSONArray("ships");
            if (ships != null) for (int i = 0; i < ships.length(); i++) {
                String line = ships.optString(i);
                if (v.ships.size() >= SHIP_SLOTS) { backShips.put(line); continue; }
                v.ships.add(new String[]{Long.toString(System.nanoTime(), 36) + i, line});
            }
            int nb = backItems.length() + backShips.length();
            r.put("note", nb == 0 ? "Stored." : "Storage full - " + nb + " kind(s) of item / ship came back to you.");
            StarMPPlugin.flog("ally storage: " + who + " put " + (items == null ? 0 : items.length()) + " item kind(s), " + (ships == null ? 0 : ships.length()) + " ship(s) into " + a.name + (nb > 0 ? " (" + nb + " returned)" : ""));
        } else if (op.equals("take")) {
            JSONArray items = c.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONArray it = items.getJSONArray(i);
                String t = it.optString(0), id = it.optString(1), data = it.optString(2); float amt = (float) it.optDouble(3, 0);
                for (Iterator<String[]> x = v.items.iterator(); x.hasNext(); ) {
                    String[] slot = x.next();
                    if (!sameKind(slot, t, id, data)) continue;
                    float give = Math.min(amt, q(slot[3]));
                    if (give <= 0) break;
                    float left = q(slot[3]) - give;
                    if (left <= 0.0001f) x.remove(); else slot[3] = fmt(left);
                    JSONArray g = new JSONArray(); g.put(t); g.put(id); g.put(data); g.put((double) give); gotItems.put(g);
                    break;
                }
            }
            JSONArray sids = c.optJSONArray("sids");
            if (sids != null) for (int i = 0; i < sids.length(); i++) {
                String sid = sids.optString(i);
                for (Iterator<String[]> x = v.ships.iterator(); x.hasNext(); ) { String[] s = x.next(); if (s[0].equals(sid)) { x.remove(); gotShips.put(s[1]); break; } }
            }
            int asked = (items == null ? 0 : items.length()) + (sids == null ? 0 : sids.length()), gave = gotItems.length() + gotShips.length();
            r.put("note", gave == asked ? "Taken." : "Someone took some of it first - you got what was left.");
            StarMPPlugin.flog("ally storage: " + who + " took " + gotItems.length() + " item kind(s), " + gotShips.length() + " ship(s) from " + a.name);
        }
        if (!op.equals("view")) save(v);
        back.put("items", backItems); back.put("ships", backShips); got.put("items", gotItems); got.put("ships", gotShips);
        r.put("back", back); r.put("got", got);
        r.put("ally", a.id); putView(r, v);
        return r;
    }

    private static void putView(JSONObject r, View v) throws Exception {
        JSONArray items = new JSONArray(), ships = new JSONArray();
        for (String[] i : v.items) { JSONArray x = new JSONArray(); for (String s : i) x.put(s); items.put(x); }
        for (String[] s : v.ships) { JSONArray x = new JSONArray(); x.put(s[0]); x.put(s[1]); ships.put(x); }
        r.put("items", items); r.put("ships", ships);
    }

    // ------------------------------------------------------------------ requests (any game)
    static void send(JSONObject c) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) return;
        try {
            if (n.isHost()) { inbox.add(new Object[]{n.playerName(), -1, c}); return; }
            byte[] j = c.toString().getBytes(StandardCharsets.UTF_8);
            byte[] d = new byte[j.length + 1]; d[0] = STORE; System.arraycopy(j, 0, d, 1, j.length);
            starmp.combat.CoopState.sendTo(n, 0, d);
        } catch (Throwable t) { StarMPPlugin.flog("ally storage: send failed: " + t); }
    }

    public static void onPacket(NetManager n, int peer, byte[] d) {
        try {
            JSONObject o = new JSONObject(new String(d, 1, d.length - 1, StandardCharsets.UTF_8));
            if (d[0] == STORE && n.isHost()) inbox.add(new Object[]{starmp.combat.CoopState.nameOf(n, peer), peer, o});
            else if (d[0] == STORED && peer == 0) inbox.add(new Object[]{null, 0, o});
        } catch (Throwable t) { StarMPPlugin.flog("ally storage: bad packet from " + peer + ": " + t); }
    }

    /** Game thread (CoopCampaign): run queued actions (host) and apply answers. */
    static void drain() {
        Object[] e;
        NetManager n = StarMPPlugin.net();
        while ((e = inbox.poll()) != null) {
            try {
                JSONObject o = (JSONObject) e[2];
                if (e[0] == null) { applyReply(o); continue; }        // an answer from the host
                JSONObject r = hostRun((String) e[0], o);
                int peer = (Integer) e[1];
                if (peer < 0) applyReply(r);
                else {
                    byte[] j = r.toString().getBytes(StandardCharsets.UTF_8);
                    byte[] d = new byte[j.length + 1]; d[0] = STORED; System.arraycopy(j, 0, d, 1, j.length);
                    starmp.combat.CoopState.sendTo(n, peer, d);
                }
            } catch (Throwable t) { StarMPPlugin.flog("ally storage: " + StarMPPlugin.trace(t)); }
        }
    }

    private static void applyReply(JSONObject r) throws Exception {
        if (r.has("note")) note = r.optString("note");
        if (r.has("ally")) {
            View v = new View(); v.ally = r.optString("ally"); v.when = System.currentTimeMillis();
            JSONArray items = r.optJSONArray("items"), ships = r.optJSONArray("ships");
            if (items != null) for (int i = 0; i < items.length(); i++) { JSONArray x = items.getJSONArray(i); v.items.add(new String[]{x.optString(0), x.optString(1), x.optString(2), x.optString(3)}); }
            if (ships != null) for (int i = 0; i < ships.length(); i++) { JSONArray x = ships.getJSONArray(i); v.ships.add(new String[]{x.optString(0), x.optString(1)}); }
            view = v;
        }
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        int added = 0;
        for (String k : new String[]{"back", "got"}) {
            JSONObject x = r.optJSONObject(k);
            if (x == null) continue;
            JSONArray items = x.optJSONArray("items"), ships = x.optJSONArray("ships");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONArray it = items.getJSONArray(i);
                try { PlayerRecords.addStack(me.getCargo(), it.optString(0), it.optString(1), it.optString(2), (float) it.optDouble(3, 0)); added++; }
                catch (Throwable t) { StarMPPlugin.flog("ally storage: item " + it + " failed: " + t); }
            }
            if (ships != null) for (int i = 0; i < ships.length(); i++) {
                FleetMemberAPI m = rebuild(ships.optString(i));
                if (m != null) { me.getFleetData().addFleetMember(m); added++; }
            }
        }
        if (added > 0) { me.getFleetData().setSyncNeeded(); me.getFleetData().syncIfNeeded(); }
        InteractionDialogAPI d = openDialog;
        if (d != null) {
            if (Global.getSector().getCampaignUI().getCurrentInteractionDialog() == d) { try { menu(d); } catch (Throwable ignored) {} }
            else openDialog = null;
        }
    }

    /** A stored ship back into a fleet member (hull and CR as stored). */
    static FleetMemberAPI rebuild(String line) {
        FleetMemberAPI m = PlayerRecords.shipFromLine(line);
        if (m == null) return null;
        String[] a = line.split("\t", -1);
        try { m.getStatus().setHullFraction(Math.max(0.01f, Math.min(1f, Float.parseFloat(a[4])))); } catch (Throwable ignored) {}
        try { m.getRepairTracker().setCR(Math.max(0f, Math.min(1f, Float.parseFloat(a[3])))); } catch (Throwable ignored) {}
        return m;
    }

    // ------------------------------------------------------------------ the station dialog
    static volatile InteractionDialogAPI openDialog; // the open storage menu, redrawn when an answer arrives
    private static MarketAPI marketOf(InteractionDialogAPI d) {
        SectorEntityToken t = d == null ? null : d.getInteractionTarget();
        return t == null ? null : t.getMarket();
    }

    /** "Alliance storage" was picked: show the storage menu. */
    public static void open(InteractionDialogAPI dialog, Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> mem) {
        MarketAPI m = marketOf(dialog);
        try { JSONObject c = new JSONObject(); c.put("op", "view"); c.put("market", m == null ? "" : m.getId()); send(c); } catch (Throwable ignored) {}
        openDialog = dialog; lastStatus = null;
        menu(dialog);
    }

    private static String lastStatus;
    static void menu(InteractionDialogAPI dialog) {
        Alliances.Ally a = Alliances.mine();
        OptionPanelAPI o = dialog.getOptionPanel();
        o.clearOptions();
        TextPanelAPI tp = dialog.getTextPanel();
        View v = view;
        boolean fresh = a != null && a.id.equals(v.ally);
        if (note != null) { tp.addPara(note, Misc.getHighlightColor()); note = null; }
        String status = a == null ? "You are not in an alliance." : !fresh ? "Contacting " + a.name + "'s storage..."
                : a.name + " storage: " + v.items.size() + " / " + ITEM_SLOTS + " item slots (any amount per slot), " + v.ships.size() + " / " + SHIP_SLOTS + " ships.";
        if (!status.equals(lastStatus)) { tp.addPara(status); lastStatus = status; } // only when it changed
        if (a != null) {
            o.addOption("Deposit items", "starmp_as_dep");
            o.addOption("Withdraw items", "starmp_as_wd");
            o.addOption("Store ships", "starmp_as_sput");
            o.addOption("Retrieve ships", "starmp_as_sget");
            if (!fresh) { o.setEnabled("starmp_as_wd", false); o.setEnabled("starmp_as_sget", false); }
            if (fresh && v.items.isEmpty()) o.setEnabled("starmp_as_wd", false);
            if (fresh && v.ships.isEmpty()) o.setEnabled("starmp_as_sget", false);
        }
        o.addOption("Back", "starmp_as_back");
    }

    /** A storage menu option was picked. */
    public static void option(String opt, InteractionDialogAPI dialog, Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> mem) {
        MarketAPI m = marketOf(dialog);
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        String market = m == null ? "" : m.getId();
        switch (opt) {
            case "starmp_as_back": {
                openDialog = null;
                dialog.getOptionPanel().clearOptions();
                com.fs.starfarer.api.impl.campaign.rulecmd.FireAll.fire(null, dialog, mem, "PopulateOptions");
                return;
            }
            case "starmp_as_dep": {
                CargoAPI copy = me.getCargo().createCopy();
                copy.removeEmptyStacks();
                for (CargoStackAPI s : copy.getStacksCopy()) if (PlayerRecords.stackFields(s) == null) copy.removeStack(s); // crew, marines... stay
                dialog.showCargoPickerDialog("Deposit into alliance storage", "Deposit", "Cancel", false, 310f, copy, new Picker(dialog) {
                    @Override public void pickedCargo(CargoAPI picked) {
                        try {
                            JSONArray items = new JSONArray();
                            for (CargoStackAPI s : picked.getStacksCopy()) {
                                String[] f = PlayerRecords.stackFields(s);
                                if (f == null || s.getSize() <= 0) continue;
                                PlayerRecords.addStack(me.getCargo(), f[0], f[1], f[2], -s.getSize()); // gone from my cargo now; the host gives back what doesn't fit
                                JSONArray it = new JSONArray(); it.put(f[0]); it.put(f[1]); it.put(f[2]); it.put((double) s.getSize()); items.put(it);
                            }
                            if (items.length() == 0) { menu(dialog); return; }
                            JSONObject c = new JSONObject(); c.put("op", "put"); c.put("market", market); c.put("items", items); send(c);
                            dialog.getTextPanel().addPara("Sending " + items.length() + " kind(s) of item to the alliance storage...");
                        } catch (Throwable t) { StarMPPlugin.flog("ally storage: deposit failed: " + t); }
                        menu(dialog);
                    }
                });
                return;
            }
            case "starmp_as_wd": {
                CargoAPI c = Global.getFactory().createCargo(true);
                for (String[] i : view.items) { try { PlayerRecords.addStack(c, i[0], i[1], i[2], q(i[3])); } catch (Throwable ignored) {} }
                dialog.showCargoPickerDialog("Withdraw from alliance storage", "Withdraw", "Cancel", false, 310f, c, new Picker(dialog) {
                    @Override public void pickedCargo(CargoAPI picked) {
                        try {
                            JSONArray items = new JSONArray();
                            for (CargoStackAPI s : picked.getStacksCopy()) {
                                String[] f = PlayerRecords.stackFields(s);
                                if (f == null || s.getSize() <= 0) continue;
                                JSONArray it = new JSONArray(); it.put(f[0]); it.put(f[1]); it.put(f[2]); it.put((double) s.getSize()); items.put(it);
                            }
                            if (items.length() > 0) { JSONObject r = new JSONObject(); r.put("op", "take"); r.put("market", market); r.put("items", items); send(r); }
                        } catch (Throwable t) { StarMPPlugin.flog("ally storage: withdraw failed: " + t); }
                        menu(dialog);
                    }
                });
                return;
            }
            case "starmp_as_sput": {
                List<FleetMemberAPI> pool = new ArrayList<>();
                for (FleetMemberAPI f : me.getFleetData().getMembersListCopy()) if (!f.isFlagship() && !f.isFighterWing()) pool.add(f);
                if (pool.isEmpty()) { dialog.getTextPanel().addPara("Your flagship can't be stored, and you have no other ships."); menu(dialog); return; }
                dialog.showFleetMemberPickerDialog("Store ships (officers stay in your fleet)", "Store", "Cancel", 3, 7, 58f, true, true, pool, new FleetMemberPickerListener() {
                    @Override public void pickedFleetMembers(List<FleetMemberAPI> picked) {
                        try {
                            JSONArray ships = new JSONArray();
                            for (FleetMemberAPI f : picked) {
                                if (f.isFlagship()) continue;
                                if (f.getCaptain() != null && !f.getCaptain().isDefault()) f.setCaptain(null);
                                String line = PlayerRecords.shipLine(f);
                                if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                                me.getFleetData().removeFleetMember(f);
                                ships.put(line);
                            }
                            if (ships.length() > 0) { JSONObject c = new JSONObject(); c.put("op", "put"); c.put("market", market); c.put("ships", ships); send(c); }
                        } catch (Throwable t) { StarMPPlugin.flog("ally storage: storing ships failed: " + t); }
                        menu(dialog);
                    }
                    @Override public void cancelledFleetMemberPicking() { menu(dialog); }
                });
                return;
            }
            case "starmp_as_sget": {
                List<FleetMemberAPI> pool = new ArrayList<>();
                Map<FleetMemberAPI, String> sid = new IdentityHashMap<>();
                for (String[] s : view.ships) { FleetMemberAPI f = rebuild(s[1]); if (f != null) { pool.add(f); sid.put(f, s[0]); } }
                dialog.showFleetMemberPickerDialog("Retrieve ships from alliance storage", "Retrieve", "Cancel", 3, 7, 58f, true, true, pool, new FleetMemberPickerListener() {
                    @Override public void pickedFleetMembers(List<FleetMemberAPI> picked) {
                        try {
                            JSONArray sids = new JSONArray();
                            for (FleetMemberAPI f : picked) if (sid.containsKey(f)) sids.put(sid.get(f));
                            if (sids.length() > 0) { JSONObject c = new JSONObject(); c.put("op", "take"); c.put("market", market); c.put("sids", sids); send(c); }
                        } catch (Throwable t) { StarMPPlugin.flog("ally storage: retrieving ships failed: " + t); }
                        menu(dialog);
                    }
                    @Override public void cancelledFleetMemberPicking() { menu(dialog); }
                });
                return;
            }
            default: menu(dialog);
        }
    }

    private abstract static class Picker implements CargoPickerListener {
        final InteractionDialogAPI dialog;
        Picker(InteractionDialogAPI d) { dialog = d; }
        @Override public void cancelledCargoSelection() { menu(dialog); }
        @Override public void recreateTextPanel(TooltipMakerAPI panel, CargoAPI cargo, CargoStackAPI pickedUp, boolean pickedUpFromSource, CargoAPI combined) {
            panel.addPara("Picked: " + combined.getStacksCopy().size() + " kind(s) of item.", 0f);
        }
    }

    /** Test hook (STARMP_TEST_ALLY "store,..."): store,put,<commodity>,<q>,<market> | store,putship,<market> |
     *  store,take,<commodity>,<q>,<market> | store,takeship,<market> | store,view,<market>. Same requests as the dialog. */
    static void testOp(String[] p) {
        try {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            String op = p[1], market = p[p.length - 1];
            if (market.equals("auto")) for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) if (available(m)) { market = m.getId(); break; }
            JSONObject c = new JSONObject(); c.put("market", market);
            StarMPPlugin.flog("TEST storage " + String.join(",", p) + " | supplies=" + me.getCargo().getSupplies() + " ships=" + me.getFleetData().getNumMembers() + " view=" + view.items.size() + "/" + view.ships.size());
            switch (op) {
                case "put": {
                    float q = Float.parseFloat(p[3]);
                    PlayerRecords.addStack(me.getCargo(), "R", p[2], "", -q);
                    JSONArray it = new JSONArray(); it.put("R"); it.put(p[2]); it.put(""); it.put((double) q);
                    c.put("op", "put"); c.put("items", new JSONArray().put(it)); break;
                }
                case "putship": {
                    FleetMemberAPI f = null; for (FleetMemberAPI x : me.getFleetData().getMembersListCopy()) if (!x.isFlagship()) f = x;
                    if (f == null) { StarMPPlugin.flog("TEST storage: no ship to store"); return; }
                    if (f.getCaptain() != null && !f.getCaptain().isDefault()) f.setCaptain(null);
                    String line = PlayerRecords.shipLine(f); line = line.substring(0, line.length() - 1);
                    me.getFleetData().removeFleetMember(f);
                    StarMPPlugin.flog("TEST storage: storing " + f.getHullSpec().getHullId() + " " + f.getShipName());
                    c.put("op", "put"); c.put("ships", new JSONArray().put(line)); break;
                }
                case "take": {
                    JSONArray it = new JSONArray(); it.put("R"); it.put(p[2]); it.put(""); it.put(Double.parseDouble(p[3]));
                    c.put("op", "take"); c.put("items", new JSONArray().put(it)); break;
                }
                case "takeship": {
                    if (view.ships.isEmpty()) { StarMPPlugin.flog("TEST storage: no stored ship in view"); return; }
                    c.put("op", "take"); c.put("sids", new JSONArray().put(view.ships.get(0)[0])); break;
                }
                default: c.put("op", "view");
            }
            send(c);
        } catch (Throwable t) { StarMPPlugin.flog("TEST storage failed: " + t); }
    }

    /** Debug log. */
    static void diag(List<String> out) {
        View v = view;
        if (!v.ally.isEmpty()) out.add("ASTORE ally=" + v.ally + " items=" + v.items.size() + " ships=" + v.ships.size());
    }

    /** A new campaign / link ended. */
    static void reset() { view = new View(); note = null; openDialog = null; inbox.clear(); }
}
