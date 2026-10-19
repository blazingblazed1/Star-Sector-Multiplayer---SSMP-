package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * Per-player save identity (0.1.4.6). The host's save holds the whole world AND every guest's own stuff:
 * a record per player name (fleet with exact loadouts incl. s-mods/d-mods, mothballed ships, officers with
 * skills, cargo, credits, character level/XP/story points/skills, portrait, position), kept in the host's
 * sector persistent data so it is written with the host's normal save.
 *
 * Guest: after loading the host's world it asks the host for its record (REQ). If the host has one, the guest's
 * player fleet/character is replaced with it (otherwise the guest is new and keeps the default copy). From then
 * on the guest pushes its record to the host whenever it changes (checked every 3 s, at most every 5 s), and
 * right before it saves. Host: stores pushes, answers requests. Nothing is simulated by the guest; this is only
 * the guest's own inventory, which the guest already owns.
 */
public final class PlayerRecords {
    private PlayerRecords() {}

    public static final byte PDATA = 26;
    private static final byte REQ = 1, REC = 2, NONE = 3, PUSH = 4;
    private static final String KEY = "starmp_players";
    private static final int FORMAT = 1;

    private static final ConcurrentLinkedQueue<Object[]> inbox = new ConcurrentLinkedQueue<>(); // {peer, byte[]}
    private static final Map<String, Long> lastStoreLog = new HashMap<>();

    // guest state (reset on every game load)
    private static int phase; // 0 = not asked, 1 = asked, 2 = restored/new -> pushing
    private static long askedNs, lastCheckNs, lastPushNs, lastStartOpenNs;
    private static int lastHash;
    private static String pendingRecord; // received, applied once the screen is free
    private static boolean pendingStart, testMoved; // 0.1.4.9: no record -> this player picks a vanilla start first
    /** Host: each player's standings (from their record), for their stand-in fleet's faction. */
    static final Map<String, Map<String, Float>> relations = new HashMap<>();

    public static void gameLoaded() { phase = 0; pendingRecord = null; pendingStart = false; NewPlayerStart.done = false; lastHash = 0; inbox.clear(); }

    /** Guest: our own fleet/character is settled (restored, or a new start picked). */
    static boolean ready() { return phase == 2; }

    /** Host: has this campaign a saved character for that name (0.9.2.5: existing players aren't "new" for PvP)? */
    static boolean hasRecord(String name) { try { return store().containsKey(key(name)); } catch (Throwable t) { return false; } }
    static int recordCount() { try { return store().size(); } catch (Throwable t) { return 0; } }

    public static void onPacket(int peer, byte[] d) { inbox.add(new Object[]{peer, d}); }

    public static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }

    /** 0.9.0.0, host: is this name free for a renaming player (no other saved character uses it)? */
    public static boolean nameFree(String oldName, String newName) {
        if (key(oldName).equals(key(newName))) return true;
        try { return !store().containsKey(key(newName)); } catch (Throwable t) { return true; }
    }

    /** 0.9.0.0, host (game thread): a player renamed - their saved character moves to the new name. */
    public static void renameRecord(String oldName, String newName) {
        try {
            Map<String, String> st = store();
            String rec = st.remove(key(oldName));
            if (rec != null) st.put(key(newName), rec);
            StarMPPlugin.flog("player records: " + oldName + " -> " + newName + (rec != null ? " (record moved)" : " (no record yet)"));
        } catch (Throwable t) { StarMPPlugin.flog("player records: rename failed: " + t); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> store() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(KEY);
        if (!(o instanceof Map)) { o = new HashMap<String, String>(); pd.put(KEY, o); }
        return (Map<String, String>) o;
    }

    /** Campaign thread, every frame while linked. */
    static void tick(NetManager net, SectorAPI sector, boolean hosting, boolean client) {
        Object[] m;
        while ((m = inbox.poll()) != null) {
            try { handle(net, (Integer) m[0], (byte[]) m[1], hosting, client); }
            catch (Throwable t) { StarMPPlugin.flog("player records: bad packet: " + StarMPPlugin.trace(t)); }
        }
        if (!client) return;
        long now = System.nanoTime();
        CampaignUIAPI ui = sector.getCampaignUI();
        boolean free = ui.getCurrentInteractionDialog() == null && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
        if (phase == 0 || (phase == 1 && now - askedNs > 15_000_000_000L)) { // ask (again after 15 s without an answer)
            phase = 1; askedNs = now;
            starmp.combat.CoopState.sendTo(net, 0, packet(REQ, net.playerName(), ""));
            StarMPPlugin.flog("player records: asked the host for " + net.playerName() + "'s record");
        }
        if (pendingRecord != null && free) {
            String r = pendingRecord; pendingRecord = null;
            try {
                String summary = apply(sector, r);
                ui.addMessage("StarMP: welcome back - your fleet and character were restored (" + summary + ")");
                StarMPPlugin.flog("player records: restored " + summary);
            } catch (Throwable t) { StarMPPlugin.flog("player records: restore FAILED: " + StarMPPlugin.trace(t)); }
            phase = 2; lastHash = 0;
        }
        if (pendingStart) {
            if (NewPlayerStart.done) { pendingStart = false; phase = 2; lastHash = 0; push(net, sector, true); }
            else if (free && now - lastStartOpenNs > 3_000_000_000L) { lastStartOpenNs = now; NewPlayerStart.open(); } // re-offered if closed without a pick
            return;
        }
        if (phase == 2 && !testMoved && System.getenv("STARMP_TEST_MOVE") != null && free) { // test hook: put me in another system
            testMoved = true; moveTo(sector, sector.getPlayerFleet(), System.getenv("STARMP_TEST_MOVE"), 0f, -3000f);
            StarMPPlugin.flog("TEST: moved to " + System.getenv("STARMP_TEST_MOVE"));
        }
        if (phase == 2 && now - lastCheckNs > 3_000_000_000L && now - lastPushNs > 5_000_000_000L && safeToCapture()) {
            lastCheckNs = now;
            push(net, sector, false);
        }
    }

    /** Guest, before its own save: make sure the host has the latest. */
    public static void beforeSave() {
        NetManager net = StarMPPlugin.net();
        if (net == null || net.isHost() || net.state() != NetManager.State.CONNECTED || phase != 2 || !safeToCapture()) return;
        try { push(net, Global.getSector(), true); } catch (Throwable t) { StarMPPlugin.flog("player records: push before save failed: " + t); }
    }

    private static boolean safeToCapture() {
        // a battle in progress moves ships around temporarily: don't record a half-state
        return CoopCampaign.joinedMembers == null && CoopCampaign.pendingResult == null;
    }

    private static void push(NetManager net, SectorAPI sector, boolean force) {
        String r = capture(sector);
        int h = r.hashCode();
        if (!force && h == lastHash) return;
        lastHash = h; lastPushNs = System.nanoTime();
        starmp.combat.CoopState.sendTo(net, 0, packet(PUSH, net.playerName(), r));
        StarMPPlugin.flog("player records: sent my record to the host (" + r.length() + " chars)");
    }

    private static void handle(NetManager net, int peer, byte[] d, boolean hosting, boolean client) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get(); byte k = b.get();
        String name = gs(b), body = gs(b);
        if (hosting) {
            String key = key(name);
            if (key.isEmpty() || key.equals(key(net.playerName()))) return; // never overwrite the host's own character
            if (k == REQ) {
                String r = store().get(key);
                starmp.combat.CoopState.sendTo(net, peer, packet(r == null ? NONE : REC, name, r == null ? "" : r));
                StarMPPlugin.flog("player records: " + name + " asked - " + (r == null ? "new player, no record" : "sent their record (" + r.length() + " chars)"));
            } else if (k == PUSH && !body.isEmpty()) {
                String old = store().put(key, body);
                relations.put(key, parseRelations(body));
                Long last = lastStoreLog.get(key);
                if (old == null || last == null || System.nanoTime() - last > 60_000_000_000L) {
                    lastStoreLog.put(key, System.nanoTime());
                    StarMPPlugin.flog("player records: stored " + name + "'s record (" + body.length() + " chars) - kept in the host's next save");
                }
            }
        } else if (client && phase == 1) {
            if (k == REC && !body.isEmpty()) { pendingRecord = body; StarMPPlugin.flog("player records: got my record from the host"); }
            else if (k == NONE) { pendingStart = true; phase = 3; StarMPPlugin.flog("player records: the host has no record of me yet - new player, picking a start"); }
        }
    }

    // ------------------------------------------------------------------ capture
    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }
    private static String join(Collection<String> c) { StringBuilder sb = new StringBuilder(); for (String s : c) { if (sb.length() > 0) sb.append(','); sb.append(clean(s).replace(',', ' ')); } return sb.toString(); }

    static String capture(SectorAPI sector) {
        CampaignFleetAPI f = sector.getPlayerFleet();
        StringBuilder sb = new StringBuilder();
        sb.append("V\t").append(FORMAT).append('\n');
        LocationAPI loc = f.getContainingLocation();
        sb.append("L\t").append(loc == null ? "" : clean(loc.getId())).append('\t').append(f.getLocation().x).append('\t').append(f.getLocation().y).append('\n');
        sb.append("$\t").append(f.getCargo().getCredits().get()).append('\n');
        PersonAPI pp = sector.getPlayerPerson();
        if (pp != null) {
            sb.append("N\t").append(clean(pp.getName().getFirst())).append('\t').append(clean(pp.getName().getLast())).append('\t')
              .append(pp.getName().getGender()).append('\t').append(clean(pp.getPortraitSprite())).append('\n');
        }
        StringBuilder rel = new StringBuilder();
        for (com.fs.starfarer.api.campaign.FactionAPI fa : sector.getAllFactions()) {
            if (fa.getId().equals("player") || fa.getId().startsWith("starmp_")) continue;
            if (rel.length() > 0) rel.append(',');
            rel.append(fa.getId()).append('=').append(sector.getPlayerFaction().getRelationship(fa.getId()));
        }
        sb.append("R\t").append(rel).append('\n');
        String cf = null; try { cf = com.fs.starfarer.api.util.Misc.getCommissionFactionId(); } catch (Throwable ignored) {}
        sb.append("M\t").append(cf == null ? "" : cf).append('\n');
        MutableCharacterStatsAPI st = sector.getPlayerStats();
        sb.append("P\t").append(st.getLevel()).append('\t').append(st.getXP()).append('\t').append(st.getBonusXp()).append('\t')
          .append(st.getStoryPoints()).append('\t').append(st.getPoints()).append('\t').append(skills(st)).append('\n');
        FleetDataAPI fd = f.getFleetData();
        for (OfficerDataAPI od : fd.getOfficersCopy()) {
            PersonAPI p = od.getPerson();
            if (p == null || p.isPlayer()) continue;
            sb.append("O\t").append(clean(p.getId())).append('\t').append(clean(p.getName().getFirst())).append('\t').append(clean(p.getName().getLast())).append('\t')
              .append(p.getName().getGender()).append('\t').append(clean(p.getPortraitSprite())).append('\t')
              .append(p.getPersonalityAPI() == null ? "steady" : clean(p.getPersonalityAPI().getId())).append('\t')
              .append(p.getStats().getLevel()).append('\t').append(p.getStats().getXP()).append('\t').append(skills(p.getStats())).append('\n');
        }
        for (FleetMemberAPI m : fd.getMembersListCopy()) {
            if (m.isFighterWing()) continue;
            sb.append(shipLine(m));
        }
        for (CargoStackAPI s : f.getCargo().getStacksCopy()) {
            String t, id, data = "";
            switch (s.getType()) {
                case RESOURCES: t = "R"; id = s.getCommodityId(); break;
                case WEAPONS: t = "W"; id = s.getWeaponSpecIfWeapon().getWeaponId(); break;
                case FIGHTER_CHIP: t = "F"; id = s.getFighterWingSpecIfWing().getId(); break;
                case SPECIAL: { SpecialItemData sd = s.getSpecialDataIfSpecial(); t = "X"; id = sd.getId(); data = sd.getData() == null ? "" : sd.getData(); break; }
                default: continue;
            }
            sb.append("C\t").append(t).append('\t').append(clean(id)).append('\t').append(clean(data)).append('\t').append(s.getSize()).append('\n');
        }
        UiData.capture(sb); // 0.9.0.0: toolbar / map / refit settings
        return sb.toString();
    }

    private static String skills(MutableCharacterStatsAPI st) {
        List<String> out = new ArrayList<>();
        for (MutableCharacterStatsAPI.SkillLevelAPI sl : st.getSkillsCopy()) if (sl.getLevel() > 0) out.add(sl.getSkill().getId() + "=" + sl.getLevel());
        return join(out);
    }

    // ------------------------------------------------------------------ apply
    private static List<String> split(String s) { List<String> o = new ArrayList<>(); if (s == null || s.isEmpty()) return o; for (String x : s.split(",")) if (!x.isEmpty()) o.add(x); return o; }
    private static float fl(String s, float def) { try { return Float.parseFloat(s); } catch (Throwable t) { return def; } }
    private static long lg(String s) { try { return Long.parseLong(s); } catch (Throwable t) { return 0; } }
    private static int in(String s) { try { return Integer.parseInt(s); } catch (Throwable t) { return 0; } }
    private static String at(String[] a, int i) { return i < a.length ? a[i] : ""; }

    static String apply(SectorAPI sector, String rec) {
        CampaignFleetAPI f = sector.getPlayerFleet();
        FleetDataAPI fd = f.getFleetData();
        List<String[]> ships = new ArrayList<>(), officers = new ArrayList<>(), cargo = new ArrayList<>();
        String[] pos = null, stats = null, person = null, rel = null, commission = null; float credits = -1;
        for (String line : rec.split("\n")) {
            String[] a = line.split("\t", -1);
            switch (a[0]) {
                case "S": ships.add(a); break;
                case "O": officers.add(a); break;
                case "C": cargo.add(a); break;
                case "L": pos = a; break;
                case "P": stats = a; break;
                case "N": person = a; break;
                case "R": rel = a; break;
                case "M": commission = a; break;
                case "$": credits = fl(at(a, 1), -1); break;
                default: break;
            }
        }
        if (ships.isEmpty()) throw new IllegalStateException("record has no ships");

        // build the new ships first: if anything is badly wrong we fail before touching the current fleet
        List<FleetMemberAPI> built = new ArrayList<>(); List<String> capIds = new ArrayList<>(); int flagIdx = -1;
        for (String[] a : ships) {
            FleetMemberAPI m = ship(a);
            if (m == null) continue;
            if ("1".equals(at(a, 8)) && flagIdx < 0) flagIdx = built.size();
            built.add(m); capIds.add(at(a, 9));
        }
        if (built.isEmpty()) throw new IllegalStateException("no ship in the record could be built");
        if (flagIdx < 0) flagIdx = 0;

        PersonAPI player = sector.getPlayerPerson();
        for (OfficerDataAPI od : fd.getOfficersCopy()) if (od.getPerson() != null && !od.getPerson().isPlayer()) fd.removeOfficer(od.getPerson());
        for (FleetMemberAPI m : fd.getMembersListCopy()) fd.removeFleetMember(m);

        Map<String, PersonAPI> byOldId = new HashMap<>();
        for (String[] a : officers) {
            try {
                PersonAPI p = Global.getFactory().createPerson();
                FullName.Gender g = FullName.Gender.ANY; try { g = FullName.Gender.valueOf(at(a, 4)); } catch (Throwable ignored) {}
                p.setName(new FullName(at(a, 2), at(a, 3), g));
                if (!at(a, 5).isEmpty()) p.setPortraitSprite(at(a, 5));
                if (!at(a, 6).isEmpty()) p.setPersonality(at(a, 6));
                p.setFaction(com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER);
                p.getStats().setLevel(Math.max(1, in(at(a, 7))));
                p.getStats().setXP(lg(at(a, 8)));
                for (String sk : split(at(a, 9))) { int e = sk.indexOf('='); if (e > 0) p.getStats().setSkillLevel(sk.substring(0, e), fl(sk.substring(e + 1), 0)); }
                fd.addOfficer(p);
                byOldId.put(at(a, 1), p);
            } catch (Throwable t) { StarMPPlugin.flog("player records: officer " + at(a, 2) + " skipped: " + t); }
        }
        for (int i = 0; i < built.size(); i++) {
            FleetMemberAPI m = built.get(i);
            fd.addFleetMember(m);
            if ("1".equals(at(ships.get(i), 7))) { try { m.getRepairTracker().setMothballed(true); } catch (Throwable ignored) {} }
        }
        FleetMemberAPI flag = built.get(flagIdx);
        fd.setFlagship(flag);
        flag.setCaptain(player);
        for (int i = 0; i < built.size(); i++) {
            if (i == flagIdx) continue;
            PersonAPI cap = byOldId.get(capIds.get(i));
            if (cap != null) built.get(i).setCaptain(cap);
        }
        for (int i = 0; i < built.size(); i++) { // CR / hull after the ships are in the fleet (adding can reset them)
            String[] a = ships.get(i);
            built.get(i).getStatus().setHullFraction(Math.max(0.01f, Math.min(1f, fl(at(a, 4), 1f))));
            built.get(i).getRepairTracker().setCR(Math.max(0f, Math.min(1f, fl(at(a, 3), 0.7f))));
        }

        CargoAPI c = f.getCargo();
        c.clear();
        int items = 0;
        for (String[] a : cargo) {
            float q = fl(at(a, 4), 0); if (q <= 0) continue;
            try {
                switch (at(a, 1)) {
                    case "R": c.addCommodity(at(a, 2), q); break;
                    case "W": c.addWeapons(at(a, 2), (int) q); break;
                    case "F": c.addFighters(at(a, 2), (int) q); break;
                    case "X": c.addSpecial(new SpecialItemData(at(a, 2), at(a, 3).isEmpty() ? null : at(a, 3)), q); break;
                    default: continue;
                }
                items++;
            } catch (Throwable t) { StarMPPlugin.flog("player records: cargo " + at(a, 2) + " skipped: " + t); }
        }
        if (credits >= 0) c.getCredits().set(credits);

        if (stats != null) {
            MutableCharacterStatsAPI st = sector.getPlayerStats();
            for (MutableCharacterStatsAPI.SkillLevelAPI sl : st.getSkillsCopy()) st.setSkillLevel(sl.getSkill().getId(), 0);
            st.setLevel(Math.max(1, in(at(stats, 1))));
            st.setXP(lg(at(stats, 2)));
            st.setBonusXp(lg(at(stats, 3)));
            st.setStoryPoints(in(at(stats, 4)));
            st.setPoints(in(at(stats, 5)));
            for (String sk : split(at(stats, 6))) { int e = sk.indexOf('='); if (e > 0) st.setSkillLevel(sk.substring(0, e), fl(sk.substring(e + 1), 0)); }
        }
        if (person != null && player != null) {
            try {
                FullName.Gender g = player.getName().getGender(); try { g = FullName.Gender.valueOf(at(person, 3)); } catch (Throwable ignored) {}
                player.setName(new FullName(at(person, 1), at(person, 2), g));
                if (!at(person, 4).isEmpty()) player.setPortraitSprite(at(person, 4));
            } catch (Throwable ignored) {}
        }
        if (commission != null) applyCommission(sector, at(commission, 1));
        if (rel != null) for (Map.Entry<String, Float> e : parseRelations("R\t" + at(rel, 1)).entrySet())
            if (sector.getFaction(e.getKey()) != null) sector.getPlayerFaction().setRelationship(e.getKey(), e.getValue());
        if (pos != null) moveTo(sector, f, at(pos, 1), fl(at(pos, 2), f.getLocation().x), fl(at(pos, 3), f.getLocation().y));
        fd.setSyncNeeded(); f.forceSync();
        try { fd.syncIfNeeded(); } catch (Throwable ignored) {}
        UiData.apply(UiData.pick(rec)); // 0.9.0.0
        return built.size() + " ships, " + byOldId.size() + " officers, " + items + " cargo stacks, " + Math.round(Math.max(0, credits)) + " credits";
    }

    /** Host: a player's standings - from this session's pushes, else from the record in the host's save. */
    static Map<String, Float> relationsFor(String key) {
        Map<String, Float> r = relations.get(key);
        if (r == null) { String rec = store().get(key); if (rec != null) { r = parseRelations(rec); relations.put(key, r); } }
        return r;
    }

    /** "R\tid=rel,..." line(s) of a record -> map. */
    static Map<String, Float> parseRelations(String rec) {
        Map<String, Float> out = new HashMap<>();
        for (String line : rec.split("\n")) {
            if (!line.startsWith("R\t")) continue;
            for (String kv : split(line.substring(2))) { int e = kv.indexOf('='); if (e > 0) out.put(kv.substring(0, e), fl(kv.substring(e + 1), 0f)); }
        }
        return out;
    }

    /** This player's commission (or none), in this player's own game. */
    private static void applyCommission(SectorAPI sector, String factionId) {
        try {
            String cur = com.fs.starfarer.api.util.Misc.getCommissionFactionId();
            if (factionId.equals(cur == null ? "" : cur)) return;
            com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel ci = com.fs.starfarer.api.util.Misc.getCommissionIntel();
            if (ci != null) ci.endMission();
            if (!factionId.isEmpty() && sector.getFaction(factionId) != null)
                new com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel(sector.getFaction(factionId)).missionAccepted();
        } catch (Throwable t) { StarMPPlugin.flog("player records: commission not restored: " + t); }
    }

    /** 0.9.1.9: weapon groups as "TYPE|autofire|slot/slot;TYPE|..." (keys 1-7 stay as the player set them up). */
    private static String groups(ShipVariantAPI v) {
        StringBuilder sb = new StringBuilder();
        try {
            for (com.fs.starfarer.api.loading.WeaponGroupSpec g : v.getWeaponGroups()) {
                if (g == null || g.getSlots().isEmpty()) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(g.getType().name()).append('|').append(g.isAutofireOnByDefault() ? 1 : 0).append('|').append(String.join("/", g.getSlots()));
            }
        } catch (Throwable t) { return ""; }
        return sb.toString();
    }

    private static boolean applyGroups(ShipVariantAPI v, String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            for (String gs : s.split(";")) {
                String[] p = gs.split("\\|", 3);
                if (p.length < 3) continue;
                com.fs.starfarer.api.loading.WeaponGroupSpec g = new com.fs.starfarer.api.loading.WeaponGroupSpec(com.fs.starfarer.api.loading.WeaponGroupType.valueOf(p[0]));
                g.setAutofireOnByDefault("1".equals(p[1]));
                for (String slot : p[2].split("/")) if (!slot.isEmpty() && v.getWeaponId(slot) != null) g.addSlot(slot);
                if (!g.getSlots().isEmpty()) v.addWeaponGroup(g);
            }
            return !v.getWeaponGroups().isEmpty();
        } catch (Throwable t) {
            try { v.getWeaponGroups().clear(); } catch (Throwable ignored) {}
            return false;
        }
    }


    /** One "S" line (with the newline) for a ship - also used by alliance storage (0.9.2.4). */
    static String shipLine(FleetMemberAPI m) {
        ShipVariantAPI v = m.getVariant();
        List<String> w = new ArrayList<>();
        for (String slot : v.getFittedWeaponSlots()) { String id = v.getWeaponId(slot); if (id != null) w.add(slot + "=" + id); }
        Set<String> perma = new LinkedHashSet<>(v.getPermaMods()); perma.removeAll(v.getSMods());
        List<String> normal = new ArrayList<>(v.getNonBuiltInHullmods()); normal.removeAll(v.getPermaMods());
        PersonAPI cap = m.getCaptain();
        String capId = cap == null || cap.isDefault() ? "" : (cap.isPlayer() ? "@player" : cap.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("S\t").append(clean(m.getHullSpec().getHullId())).append('\t').append(clean(m.getShipName())).append('\t')
          .append(m.getRepairTracker().getCR()).append('\t').append(m.getStatus().getHullFraction()).append('\t')
          .append(v.getNumFluxVents()).append('\t').append(v.getNumFluxCapacitors()).append('\t')
          .append(m.isMothballed() ? 1 : 0).append('\t').append(m.isFlagship() ? 1 : 0).append('\t').append(clean(capId)).append('\t')
          .append(join(w)).append('\t').append(join(normal)).append('\t').append(join(perma)).append('\t')
          .append(join(v.getSMods())).append('\t').append(join(v.getSModdedBuiltIns())).append('\t').append(join(v.getFittedWings())).append('\t')
          .append(clean(groups(v))).append('\n'); // 0.9.1.9: weapon groups (field 16)
        return sb.toString();
    }

    /** "C" line fields after the C: type, id, data, amount - or null for stacks that can't be stored. */
    static String[] stackFields(CargoStackAPI s) {
        String t, id, data = "";
        switch (s.getType()) {
            case RESOURCES: t = "R"; id = s.getCommodityId(); break;
            case WEAPONS: t = "W"; id = s.getWeaponSpecIfWeapon().getWeaponId(); break;
            case FIGHTER_CHIP: t = "F"; id = s.getFighterWingSpecIfWing().getId(); break;
            case SPECIAL: { SpecialItemData sd = s.getSpecialDataIfSpecial(); t = "X"; id = sd.getId(); data = sd.getData() == null ? "" : sd.getData(); break; }
            default: return null;
        }
        return new String[]{t, id, data, String.valueOf(s.getSize())};
    }

    /** Adds (q > 0) or removes (q < 0) one kind of item. */
    static void addStack(CargoAPI c, String t, String id, String data, float q) {
        SpecialItemData sd = "X".equals(t) ? new SpecialItemData(id, data == null || data.isEmpty() ? null : data) : null;
        CargoAPI.CargoItemType type = "R".equals(t) ? CargoAPI.CargoItemType.RESOURCES : "W".equals(t) ? CargoAPI.CargoItemType.WEAPONS
                : "F".equals(t) ? CargoAPI.CargoItemType.FIGHTER_CHIP : CargoAPI.CargoItemType.SPECIAL;
        Object key = sd != null ? sd : id;
        if (q > 0) c.addItems(type, key, q); else if (q < 0) c.removeItems(type, key, -q);
    }

    /** Builds a ship from an "S" line (split on tabs). */
    static FleetMemberAPI shipFromLine(String line) { return ship(line.replace("\n", "").split("\t", -1)); }

    private static FleetMemberAPI ship(String[] a) {
        try {
            ShipHullSpecAPI hull = Global.getSettings().getHullSpec(at(a, 1));
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(at(a, 1) + "_pr" + System.nanoTime() % 1_000_000, hull);
            for (String sw : split(at(a, 10))) { int e = sw.indexOf('='); if (e > 0) { try { v.addWeapon(sw.substring(0, e), sw.substring(e + 1)); } catch (Throwable ignored) {} } }
            for (String m : split(at(a, 11))) { try { v.addMod(m); } catch (Throwable ignored) {} }
            for (String m : split(at(a, 12))) { try { v.addPermaMod(m, false); } catch (Throwable ignored) {} }
            for (String m : split(at(a, 13))) { try { v.addPermaMod(m, true); } catch (Throwable ignored) {} }
            for (String m : split(at(a, 14))) { try { v.getSModdedBuiltIns().add(m); } catch (Throwable ignored) {} }
            List<String> wings = split(at(a, 15));
            for (int i = 0; i < wings.size(); i++) { try { v.setWingId(i, wings.get(i)); } catch (Throwable ignored) {} }
            v.setNumFluxVents(in(at(a, 5))); v.setNumFluxCapacitors(in(at(a, 6)));
            v.setSource(com.fs.starfarer.api.loading.VariantSource.REFIT);
            if (!applyGroups(v, at(a, 16))) v.autoGenerateWeaponGroups(); // 0.9.1.9: the player's own weapon groups
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            if (!at(a, 2).isEmpty()) m.setShipName(at(a, 2));
            m.setOwner(0);
            return m;
        } catch (Throwable t) {
            StarMPPlugin.flog("player records: ship " + at(a, 1) + " skipped: " + t);
            return null;
        }
    }

    /** 0.1.6.5 bot test mode only: keep a bot in the host's system. */
    public static void botMoveTo(SectorAPI sector, CampaignFleetAPI f, String locId, float x, float y) { moveTo(sector, f, locId, x, y); }

    static void moveTo(SectorAPI sector, CampaignFleetAPI f, String locId, float x, float y) {
        LocationAPI target = null;
        for (LocationAPI l : sector.getAllLocations()) if (l.getId().equals(locId)) { target = l; break; }
        if (target == null) return;
        LocationAPI cur = f.getContainingLocation();
        if (cur != target) {
            if (cur != null) cur.removeEntity(f);
            target.addEntity(f);
            sector.setCurrentLocation(target);
        }
        f.setLocation(x, y);
        f.setVelocity(0, 0);
        f.setMoveDestination(x, y);
    }

    // ------------------------------------------------------------------ packet
    private static byte[] packet(byte kind, String name, String body) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8), d = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(2 + 4 + n.length + 4 + d.length);
        b.put(PDATA); b.put(kind); b.putInt(n.length); b.put(n); b.putInt(d.length); b.put(d);
        return b.array();
    }
    private static String gs(ByteBuffer b) { int l = b.getInt(); if (l < 0 || l > b.remaining()) return ""; byte[] x = new byte[l]; b.get(x); return new String(x, StandardCharsets.UTF_8); }
}
