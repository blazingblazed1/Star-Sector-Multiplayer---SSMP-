package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONObject;
import starmp.NetManager;
import starmp.Session;
import starmp.StarMPPlugin;

/**
 * 0.9.2.5: PvP encounters (spec: claude/starmp-alliance-pvp-spec.md). The HOST decides everything.
 *
 * 1. Attacker clicks the other player's fleet -> "Start PvP encounter" (hidden when PvP is off, when allied or friendly,
 *    and for regular alliance members unless their leadership marked the target hostile).
 * 2. Host checks the rules (same location and in range, not docked, new-player protection, cooldown, not busy), sets
 *    both sides (their alliances, if any) hostile (-50) and asks the defender.
 * 3. Defender: try to slip away (25%), spend a story point (always escapes), or fight. No answer in 30 s = caught.
 *    A failed escape = caught, like being ambushed: the battle starts and there is no way out of it.
 * 4. Fight: the attacker's game owns the battle (the attacker's real fleet); the defender's fleet joins it as the
 *    ENEMY side, flown by the defender (CoopCombatPlugin pvpPeer / flip). Losses apply to each player's own fleet.
 *
 * PVP (49) any <-> host: [49][json] {"op":"req"|"offer"|"status"|"choice"|"result", ...}
 */
public final class Pvp {
    private Pvp() {}
    public static final byte PVP = 49;
    public static final float RANGE = 600f;            // fleet edge to fleet edge, same location
    public static final float ESCAPE_CHANCE = 0.25f;
    private static final long ANSWER_NS = 30_000_000_000L;
    private static final String FIRST_KEY = "starmp_pvp_first", LAST_KEY = "starmp_pvp_last";

    // host
    private static final class Enc { int id, att, def; String attName, defName; long startNs; }
    private static final Map<Integer, Enc> encs = new HashMap<>();
    private static int nextId = 1;
    // every game
    private static final ConcurrentLinkedQueue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    static volatile JSONObject offer;         // defender: the open offer
    static volatile String attackStatus;      // attacker: shown in the contact dialog
    static volatile JSONObject attackResult;  // attacker: "result" for my attack
    static volatile int defendJoin = -1;      // defender: join this player's battle as their enemy
    static volatile long defendJoinNs;
    private static PvpDefendDialog defendDialog;

    // ------------------------------------------------------------------ rules
    private static NetManager net() { return StarMPPlugin.net(); }
    private static String nm(int peer) { NetManager n = net(); return n == null ? "?" : starmp.combat.CoopState.nameOf(n, peer); }

    /** Attacker side: show the option at all? (the host checks everything again) */
    public static boolean optionVisible(int target) {
        NetManager n = net();
        if (n == null || !n.linked() || target < 0 || target == n.myId()) return false;
        if (!Session.current().pvp) return false;
        String me = n.playerName(), them = nm(target);
        if (Alliances.same(me, them)) return false;
        int st = Relations.standing(Relations.entityOf(me), Relations.entityOf(them));
        if (st >= 50) return false;                                    // friendly
        Alliances.Ally a = Alliances.of(me);
        if (a != null && !a.isAdmin(Alliances.lc(me)) && st > Relations.HOSTILE) return false; // members can't start hostilities
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> first() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(FIRST_KEY); if (!(o instanceof Map)) { o = new HashMap<String, Long>(); pd.put(FIRST_KEY, o); }
        return (Map<String, Long>) o;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Long> last() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(LAST_KEY); if (!(o instanceof Map)) { o = new HashMap<String, Long>(); pd.put(LAST_KEY, o); }
        return (Map<String, Long>) o;
    }

    /** Host: remember when each player was first seen (players this campaign already knows count as old). */
    private static void register(String name) {
        String k = Alliances.lc(name);
        if (k.isEmpty() || first().containsKey(k)) return;
        boolean old = PlayerRecords.hasRecord(name) || (Alliances.lc(net().playerName()).equals(k) && PlayerRecords.recordCount() > 0);
        first().put(k, old ? 0L : Global.getSector().getClock().getTimestamp());
    }

    private static float daysSince(long ts) { return ts == 0L ? 1e9f : Global.getSector().getClock().getElapsedDaysSince(ts); }

    /** Host: where a player is: {loc, x, y, radius, state}, or null. */
    private static Object[] where(int peer) {
        NetManager n = net();
        if (peer == n.myId()) {
            CampaignFleetAPI f = Global.getSector().getPlayerFleet();
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            InteractionDialogAPI d = ui.getCurrentInteractionDialog();
            SectorEntityToken t = d == null ? null : d.getInteractionTarget();
            int state = (t != null && t.getMarket() != null && !(t instanceof CampaignFleetAPI) ? 1 : 0) | (d != null ? 2 : 0);
            return new Object[]{f.getContainingLocation() == null ? "" : f.getContainingLocation().getId(), f.getLocation().x, f.getLocation().y, f.getRadius(), state};
        }
        CampWire.Fleet w = CoopCampaign.fleets.get(peer);
        if (w == null) return null;
        CampaignFleetAPI f = CoopCampaign.playerFleets().get(peer);
        return new Object[]{w.loc, w.x, w.y, f == null ? 100f : f.getRadius(), w.state};
    }

    /** Host: why this attack can't happen (null = it can). */
    private static String hostWhyNot(int att, int def, boolean attackerIsInMyDialog) {
        Session.Settings s = Session.current();
        NetManager n = net();
        if (!s.pvp) return "PvP is turned off on this server.";
        if (att == def) return "You can't attack yourself.";
        String an = nm(att), dn = nm(def);
        if (Alliances.same(an, dn)) return "You are in the same alliance.";
        int st = Relations.standing(Relations.entityOf(an), Relations.entityOf(dn));
        if (st >= 50) return "You are friendly with " + dn + ".";
        Alliances.Ally a = Alliances.of(an);
        if (a != null && !a.isAdmin(Alliances.lc(an)) && st > Relations.HOSTILE)
            return "Only " + a.name + "'s owner and admins can start hostilities. Ask them to declare war on " + dn + ".";
        for (Enc e : encs.values()) if (e.att == att || e.def == att || e.att == def || e.def == def) return "One of you is already in a PvP encounter.";
        Object[] wa = where(att), wd = where(def);
        if (wa == null || wd == null) return "Can't find " + (wa == null ? an : dn) + "'s fleet.";
        if (!wa[0].equals(wd[0])) return dn + " is not in your star system.";
        float dx = (Float) wa[1] - (Float) wd[1], dy = (Float) wa[2] - (Float) wd[2];
        float gap = (float) Math.sqrt(dx * dx + dy * dy) - (Float) wa[3] - (Float) wd[3];
        if (gap > RANGE) return dn + " is too far away (" + Math.round(gap) + " / " + Math.round(RANGE) + ").";
        int ds = (Integer) wd[4];
        if ((ds & 1) != 0 && !s.pvpDocked) return dn + " is docked.";
        if ((ds & 2) != 0) return dn + " is busy (in a dialog) - try again in a moment.";
        if (CoopCampaign.live(CoopCampaign.battleOf(def)) || CoopCampaign.live(CoopCampaign.battleOf(att))) return "One of you is in a battle.";
        register(an); register(dn);
        float dAge = daysSince(first().get(Alliances.lc(dn)));
        if (s.pvpProtectDays > 0 && dAge < s.pvpProtectDays) return dn + " is a new player: protected for " + (int) Math.ceil(s.pvpProtectDays - dAge) + " more day(s).";
        Long lastT = last().get(Alliances.lc(an) + "|" + Alliances.lc(dn));
        if (lastT != null && s.pvpCooldownDays > 0 && daysSince(lastT) < s.pvpCooldownDays) return "You attacked " + dn + " recently - wait a day.";
        return null;
    }

    // ------------------------------------------------------------------ requests
    /** Attacker: ask the host to start an encounter with that player. */
    public static void request(int target) {
        attackStatus = "You move to intercept " + nm(target) + "...";
        attackResult = null;
        send(0, obj("op", "req", "target", target));
    }

    static void choose(int id, String choice) { send(0, obj("op", "choice", "id", id, "c", choice)); }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]); } catch (Throwable ignored) {}
        return o;
    }

    private static void send(int to, JSONObject o) {
        NetManager n = net();
        if (n == null || !n.linked()) return;
        if (to == n.myId() || (to == 0 && n.isHost())) { inbox.add(new Object[]{n.myId(), o}); return; }
        byte[] j = o.toString().getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[j.length + 1]; d[0] = PVP; System.arraycopy(j, 0, d, 1, j.length);
        starmp.combat.CoopState.sendTo(n, to, d);
    }

    public static void onPacket(NetManager n, int peer, byte[] d) {
        try { inbox.add(new Object[]{peer, new JSONObject(new String(d, 1, d.length - 1, StandardCharsets.UTF_8))}); }
        catch (Throwable t) { StarMPPlugin.flog("pvp: bad packet from " + peer + ": " + t); }
    }

    // ------------------------------------------------------------------ every frame (campaign)
    static void tick(NetManager n) {
        if (n == null || !n.linked()) return;
        Object[] e;
        while ((e = inbox.poll()) != null) {
            try { handle(n, (Integer) e[0], (JSONObject) e[1]); } catch (Throwable t) { StarMPPlugin.flog("pvp: " + StarMPPlugin.trace(t)); }
        }
        if (n.isHost()) {
            try {
                register(n.playerName());
                for (int p : n.peerIds()) register(nm(p));
            } catch (Throwable ignored) {}
            long now = System.nanoTime();
            for (Enc x : new ArrayList<>(encs.values())) if (now - x.startNs > ANSWER_NS) { StarMPPlugin.flog("pvp: " + x.defName + " didn't answer - caught"); resolve(x, "fight", true); }
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        boolean free = ui.getCurrentInteractionDialog() == null && !ui.isShowingDialog() && ui.getCurrentCoreTab() == null;
        // defender: show the offer
        JSONObject o = offer;
        if (o != null && defendDialog == null && free) {
            defendDialog = new PvpDefendDialog(o);
            ui.showInteractionDialog(defendDialog, Global.getSector().getPlayerFleet());
        }
        // defender: once the attacker's battle is up, join it (as their enemy)
        if (defendJoin >= 0) {
            CampWire.Battle b = CoopCampaign.battleOf(defendJoin);
            if (CoopCampaign.live(b) && free) { StarMPPlugin.flog("pvp: joining " + nm(defendJoin) + "'s battle as the enemy"); CoopCampaign.openJoin(b.battleId, null, true); }
            else if (System.nanoTime() - defendJoinNs > 90_000_000_000L) { StarMPPlugin.flog("pvp: " + nm(defendJoin) + "'s battle never started"); defendJoin = -1; }
        }
    }

    static void defendDialogClosed() { defendDialog = null; }

    private static void handle(NetManager n, int from, JSONObject o) throws Exception {
        String op = o.optString("op");
        switch (op) {
            case "req": { // host
                if (!n.isHost()) return;
                int att = from, def = o.optInt("target", -1);
                String why = hostWhyNot(att, def, true);
                if (why != null) { send(att, obj("op", "status", "text", why, "denied", true)); StarMPPlugin.flog("pvp: " + nm(att) + " -> " + nm(def) + " refused: " + why); return; }
                Enc x = new Enc(); x.id = nextId++; x.att = att; x.def = def; x.attName = nm(att); x.defName = nm(def); x.startNs = System.nanoTime();
                encs.put(x.id, x);
                // both sides hostile (their alliances when in one); members of the attacker's own alliance can't attack it anyway
                Relations.hostSet(Relations.entityOf(x.attName), Relations.entityOf(x.defName), Relations.HOSTILE, "PvP: " + x.attName + " attacked " + x.defName);
                Session.Settings s = Session.current();
                if (s.pvpProtectDays > 0) first().put(Alliances.lc(x.attName), 0L); // attacking ends your own protection
                last().put(Alliances.lc(x.attName) + "|" + Alliances.lc(x.defName), Global.getSector().getClock().getTimestamp());
                StarMPPlugin.flog("pvp: encounter " + x.id + ": " + x.attName + " attacks " + x.defName);
                send(def, obj("op", "offer", "id", x.id, "attacker", x.attName, "attackerPeer", att));
                send(att, obj("op", "status", "text", x.defName + "'s fleet has spotted you. Waiting for their move..."));
                return;
            }
            case "choice": { // host
                if (!n.isHost()) return;
                Enc x = encs.get(o.optInt("id"));
                if (x == null || x.def != from) return;
                String c = o.optString("c");
                if (c.equals("sp")) resolve(x, "escaped", false);
                else if (c.equals("roll")) {
                    boolean ok = new Random().nextFloat() < ESCAPE_CHANCE;
                    StarMPPlugin.flog("pvp: " + x.defName + " tries to slip away: " + (ok ? "escaped" : "caught"));
                    resolve(x, ok ? "escaped" : "fight", !ok);
                } else resolve(x, "fight", false);
                return;
            }
            case "offer": { // defender
                offer = o;
                StarMPPlugin.flog("pvp: " + o.optString("attacker") + " is attacking me");
                return;
            }
            case "status": { attackStatus = o.optString("text"); if (o.optBoolean("denied")) attackResult = o; return; } // attacker
            case "result": {
                StarMPPlugin.flog("pvp: encounter " + o.optInt("id") + " result " + o.optString("outcome") + (o.optBoolean("ambush") ? " (caught)" : ""));
                if (o.optInt("attackerPeer", -2) == n.myId()) attackResult = o;
                if (o.optInt("defenderPeer", -2) == n.myId()) {
                    offer = null;
                    PvpDefendDialog dd = defendDialog;
                    if (dd != null) dd.result(o);
                    if ("fight".equals(o.optString("outcome"))) { defendJoin = o.optInt("attackerPeer"); defendJoinNs = System.nanoTime(); }
                }
                return;
            }
        }
    }

    /** Host: the encounter is decided. */
    private static void resolve(Enc x, String outcome, boolean ambush) {
        encs.remove(x.id);
        JSONObject r = obj("op", "result", "id", x.id, "outcome", outcome, "ambush", ambush, "attacker", x.attName, "defender", x.defName, "attackerPeer", x.att, "defenderPeer", x.def);
        send(x.att, r);
        send(x.def, r);
        StarMPPlugin.flog("pvp: encounter " + x.id + " " + x.attName + " vs " + x.defName + ": " + outcome + (ambush ? " (caught)" : ""));
    }

    /** Attacker: start the battle (the defender's fleet joins it as the enemy). Called from the contact dialog. */
    static void startBattle(InteractionDialogAPI dialog, int defender) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        // the enemy side starts empty: a stand-in fleet with nothing deployable (the defender's ships are spawned in)
        CampaignFleetAPI stage = Global.getFactory().createEmptyFleet("independent", nm(defender) + "'s fleet", true);
        com.fs.starfarer.api.fleet.FleetMemberAPI vm = Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, "kite_Hull");
        vm.getRepairTracker().setMothballed(true);
        stage.getFleetData().addFleetMember(vm);
        stage.getMemoryWithoutUpdate().set("$starmp_stage", true);
        stage.getMemoryWithoutUpdate().set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
        stage.getMemoryWithoutUpdate().set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        stage.setNoAutoDespawn(true);
        me.getContainingLocation().addEntity(stage);
        stage.setLocation(me.getLocation().x + 60, me.getLocation().y + 60);
        attackStage = stage;
        starmp.combat.CoopState.pvpEnemy = defender;
        starmp.combat.CoopState.battleOwner = net().myId();
        com.fs.starfarer.api.combat.BattleCreationContext ctx = new com.fs.starfarer.api.combat.BattleCreationContext(me, com.fs.starfarer.api.fleet.FleetGoal.ATTACK, stage, com.fs.starfarer.api.fleet.FleetGoal.ATTACK);
        ctx.aiRetreatAllowed = false;
        StarMPPlugin.flog("pvp: starting my battle against " + nm(defender));
        dialog.startBattle(ctx);
    }
    private static CampaignFleetAPI attackStage;

    /** Attacker: back from the PvP battle - apply my own losses (the vanilla encounter code doesn't run here). */
    static String afterBattle(EngagementResultAPI r) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (attackStage != null && attackStage.getContainingLocation() != null) attackStage.getContainingLocation().removeEntity(attackStage);
        attackStage = null;
        if (r == null) return "The battle is over.";
        com.fs.starfarer.api.campaign.EngagementResultForFleetAPI mine = r.getWinnerResult() != null && r.getWinnerResult().getFleet() == me ? r.getWinnerResult() : r.getLoserResult();
        boolean won = r.getWinnerResult() != null && r.getWinnerResult().getFleet() == me;
        int lost = 0;
        if (mine != null) {
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> gone = new ArrayList<>(mine.getDestroyed()); gone.addAll(mine.getDisabled());
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : gone) {
                if (!me.getFleetData().getMembersListCopy().contains(m)) continue;
                if (me.getFleetData().getNumMembers() > 1 && m != me.getFlagship()) { me.getFleetData().removeFleetMember(m); lost++; }
                else { m.getStatus().setHullFraction(0.05f); m.getRepairTracker().setCR(0f); }
            }
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> fought = new ArrayList<>(mine.getDeployed()); fought.addAll(mine.getRetreated());
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fought) {
                if (!me.getFleetData().getMembersListCopy().contains(m)) continue;
                m.getRepairTracker().setCR(Math.max(0f, m.getRepairTracker().getCR() - m.getDeployCost()));
            }
        }
        me.getFleetData().setSyncNeeded(); me.forceSync();
        StarMPPlugin.flog("pvp: my battle ended - " + (won ? "won" : "not won") + ", " + lost + " ship(s) lost");
        return (won ? "You won the fight." : "The fight is over.") + (lost > 0 ? " You lost " + lost + " ship(s)." : "");
    }

    /** A new campaign / link ended. */
    static void reset() { encs.clear(); inbox.clear(); offer = null; attackStatus = null; attackResult = null; defendJoin = -1; defendDialog = null; attackStage = null; }

    // ------------------------------------------------------------------ defender's dialog
    public static final class PvpDefendDialog implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;
        private final JSONObject offer;
        private boolean chose, done;
        PvpDefendDialog(JSONObject o) { offer = o; }

        @Override public void init(InteractionDialogAPI d) {
            dialog = d;
            String a = offer.optString("attacker");
            CampaignFleetAPI af = CoopCampaign.playerFleets().get(offer.optInt("attackerPeer"));
            if (af != null) d.getVisualPanel().showFleetInfo("Your fleet", Global.getSector().getPlayerFleet(), a + "'s fleet", af);
            d.getTextPanel().addPara(a + "'s fleet is moving to attack you!", java.awt.Color.RED);
            d.getTextPanel().addPara("Try to slip away (" + Math.round(ESCAPE_CHANCE * 100) + "% chance - if it fails you are caught and must fight), spend a story point to get away for sure, or turn and fight. "
                    + "You have " + ANSWER_NS / 1_000_000_000L + " seconds.", java.awt.Color.GRAY);
            options();
            d.setPromptText("");
            String test = System.getenv("STARMP_TEST_PVP_CHOICE"); // test hook: roll / sp / fight
            if (test != null) { StarMPPlugin.flog("TEST: PvP defender picks " + test); optionSelected(test, test); }
        }

        private void options() {
            OptionPanelAPI o = dialog.getOptionPanel();
            o.clearOptions();
            if (done) { o.addOption("Leave", "leave"); return; }
            if (chose) return;
            o.addOption("Try to slip away (" + Math.round(ESCAPE_CHANCE * 100) + "%)", "roll");
            int sp = Global.getSector().getPlayerStats().getStoryPoints();
            o.addOption("Spend a story point - get away for sure (" + sp + " left)", "sp");
            if (sp < 1) o.setEnabled("sp", false);
            o.addOption("Turn and fight", "fight");
        }

        @Override public void optionSelected(String text, Object data) {
            if ("leave".equals(data)) { dialog.dismiss(); defendDialogClosed(); return; }
            if (chose) return;
            chose = true;
            if ("sp".equals(data)) {
                Global.getSector().getPlayerStats().spendStoryPoints(1, true, null, false, 0f, "Escaped a PvP attack");
                dialog.getTextPanel().addPara("You spend a story point.", java.awt.Color.YELLOW);
            }
            dialog.getTextPanel().addPara("Waiting for the outcome...");
            choose(offer.optInt("id"), String.valueOf(data));
            options();
        }

        /** The host's decision. */
        void result(JSONObject r) {
            done = true;
            if ("escaped".equals(r.optString("outcome"))) {
                dialog.getTextPanel().addPara("You slip away from " + r.optString("attacker") + ".", java.awt.Color.GREEN);
                options();
            } else {
                dialog.getTextPanel().addPara(r.optBoolean("ambush") ? "You're caught! There's no way out - battle stations." : "Battle stations!", java.awt.Color.RED);
                dialog.dismiss(); defendDialogClosed(); // the join dialog opens once the attacker's battle is up
            }
        }

        @Override public void optionMousedOver(String t, Object d) {}
        @Override public void advance(float amount) {}
        @Override public void backFromEngagement(EngagementResultAPI r) {}
        @Override public Object getContext() { return null; }
        @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
    }
}
