package starmp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.Random;
import starmp.campaign.CampWire;
import starmp.campaign.CoopCampaign;
import starmp.combat.Wire;

/**
 * 0.1.6.5: bot test mode, for load-testing on one PC (tools: "SSMP bot test" script). OFF unless the game was started
 * with the environment variable STARMP_BOT=1 - a normal game never runs any of this.
 *
 * A bot is an ordinary guest client that plays itself: it joins the host's code (STARMP_BOT_CODE), loads the host's
 * save when it arrives, then flies around the host's fleet (STARMP_BOT_MODE = follow | wander | mixed | idle), joins
 * every battle a friend starts nearby and fights in it (faces / approaches / fires at the nearest enemy). It closes
 * stray dialogs after a few seconds and never pauses. Frame rate is capped (STARMP_BOT_FPS, default 30).
 */
public final class Bot {
    private Bot() {}

    public static final boolean ACTIVE = "1".equals(env("STARMP_BOT"));
    private static final String CODE = env("STARMP_BOT_CODE");
    private static final String MODE = env("STARMP_BOT_MODE").isEmpty() ? "mixed" : env("STARMP_BOT_MODE").toLowerCase();
    public static final int FPS = intEnv("STARMP_BOT_FPS", 30, 5, 120);
    private static final Random rnd = new Random();

    private static String env(String k) { String v = System.getenv(k); return v == null ? "" : v.trim(); }
    private static int intEnv(String k, int def, int lo, int hi) { try { return Math.max(lo, Math.min(hi, Integer.parseInt(env(k)))); } catch (Throwable t) { return def; } }

    private static void log(String s) { StarMPPlugin.flog("bot: " + s); }

    // ------------------------------------------------------------------ title screen
    private static long lastJoinNs, nextLoadNs;
    private static boolean announced;

    /** Title screen, every frame (advance): keep trying to join the host. */
    public static void titleTick(NetManager net) {
        if (!ACTIVE || net == null) return;
        if (!announced) { announced = true; log("bot mode ON (mode " + MODE + ", fps " + FPS + ", code " + (CODE.startsWith("SM1:") ? "set" : "MISSING") + ")"); }
        long now = System.nanoTime();
        if (net.state() == NetManager.State.CONNECTED && !starmp.ui.Lobby.myReady) { starmp.ui.Lobby.myReady = true; starmp.ui.Lobby.sendNow(net); log("ready in the lobby"); }
        if (net.state() == NetManager.State.IDLE && CODE.startsWith("SM1:") && !Session.wasKicked && now - lastJoinNs > 15_000_000_000L) {
            lastJoinNs = now;
            log("joining the host");
            net.join(CODE);
        }
    }

    /** Title screen, from render (loading must not start inside advance): load the host's save once it is here. */
    public static boolean titleRender(NetManager net) {
        if (!ACTIVE || net == null || net.state() != NetManager.State.CONNECTED) return false;
        String what = starmp.ui.Lobby.startedWhat, save = net.receivedSave;
        if (what == null || !(what.equals("campaign") || what.equals("newcampaign")) || save == null || save.isEmpty() || net.receivingSave) return false;
        long now = System.nanoTime();
        if (now < nextLoadNs) return false;
        nextLoadNs = now + 30_000_000_000L; // if this load fails, try again in 30 s
        String dir = NetManager.savesDir().replace('\\', '/');
        boolean absolute = dir.startsWith("/") || (dir.length() > 1 && dir.charAt(1) == ':');
        String path = (absolute ? dir : System.getProperty("user.dir").replace('\\', '/') + "/" + dir) + "/" + save;
        log("host save " + save + " is here - loading it");
        String err = starmp.ui.Vanilla.loadSave(path);
        if (err != null) { log("load failed: " + err); return false; }
        return true;
    }

    // ------------------------------------------------------------------ campaign
    private static float goalX, goalY, offX, offY;
    private static boolean following;
    private static long nextGoalNs, dialogSinceNs, hostElsewhereNs;

    /** Guest, linked, campaign map, every frame. */
    public static void campaignTick(SectorAPI sector) {
        if (!ACTIVE || sector == null) return;
        CampaignFleetAPI me = sector.getPlayerFleet();
        if (me == null || me.getContainingLocation() == null) return;
        CampaignUIAPI ui = sector.getCampaignUI();
        long now = System.nanoTime();
        InteractionDialogAPI d = ui.getCurrentInteractionDialog();
        if (d != null && d.getPlugin() instanceof starmp.campaign.NewPlayerStart) { // new to this sector: take the mercenary start (5 ships)
            if (dialogSinceNs == 0) dialogSinceNs = now;
            else if (now - dialogSinceNs > 1_500_000_000L) { dialogSinceNs = 0; log("new player start: mercenary"); d.getPlugin().optionSelected("A mercenary", "merc"); }
            return;
        }
        if (d != null && !(d.getPlugin() instanceof starmp.campaign.JoinBattleDialog)) {
            if (dialogSinceNs == 0) dialogSinceNs = now;
            else if (now - dialogSinceNs > 6_000_000_000L) {
                log("closing a dialog (" + (d.getPlugin() == null ? "?" : d.getPlugin().getClass().getSimpleName()) + ")");
                try { d.dismiss(); } catch (Throwable t) { log("could not close the dialog: " + t); }
                dialogSinceNs = 0;
            }
            return;
        }
        dialogSinceNs = 0;
        if (d != null || ui.isShowingDialog() || ui.getCurrentCoreTab() != null) return;
        if (Session.openId != 0 && !Session.myBallotCast) { log("voting yes: " + Session.openText); Session.ballot(true); } // 0.9.0.0: bots say yes
        if (sector.isPaused() && Session.myCan(Session.Perm.PAUSE)) sector.setPaused(false);
        if ("idle".equals(MODE)) return;

        CampWire.Fleet host = CoopCampaign.fleets.get(0);
        String here = me.getContainingLocation().getId();
        boolean hostHere = host != null && here.equals(host.loc);
        // keep the test together: if the host has been in another system for 20 s, jump next to them
        if (host != null && !hostHere && !host.loc.isEmpty() && !"wander".equals(MODE)) {
            if (hostElsewhereNs == 0) hostElsewhereNs = now;
            else if (now - hostElsewhereNs > 20_000_000_000L) {
                hostElsewhereNs = 0;
                log("host is in " + host.loc + " - moving there");
                starmp.campaign.PlayerRecords.botMoveTo(sector, me, host.loc, host.x + 300 - rnd.nextFloat() * 600, host.y + 300 - rnd.nextFloat() * 600);
                nextGoalNs = 0;
                return;
            }
        } else hostElsewhereNs = 0;

        if (now >= nextGoalNs) {
            nextGoalNs = now + (20 + rnd.nextInt(20)) * 1_000_000_000L;
            boolean follow = "follow".equals(MODE) || ("mixed".equals(MODE) && rnd.nextFloat() < 0.7f);
            following = follow && hostHere;
            float a = rnd.nextFloat() * 6.2832f, r = following ? 150 + rnd.nextFloat() * 250 : 800 + rnd.nextFloat() * 2000;
            offX = (float) Math.cos(a) * r; offY = (float) Math.sin(a) * r;
            if (!following) { goalX = me.getLocation().x + offX; goalY = me.getLocation().y + offY; }
            log(following ? "following the host" : "wandering");
        }
        if (following && hostHere) { goalX = host.x + offX; goalY = host.y + offY; }
        me.setMoveDestinationOverride(goalX, goalY);
    }

    // ------------------------------------------------------------------ join dialog
    private static long joinDialogNs;
    private static Object joinedDialog; // "Join" already pressed in this dialog - wait for the battle

    /** JoinBattleDialog.advance: press "Join the battle" (and "Leave" afterwards) by itself. */
    public static void joinDialogTick(starmp.campaign.JoinBattleDialog dlg, boolean fought) {
        if (!ACTIVE || (!fought && joinedDialog == dlg)) return;
        long now = System.nanoTime();
        if (joinDialogNs == 0) { joinDialogNs = now; return; }
        if (now - joinDialogNs < 1_500_000_000L) return;
        joinDialogNs = 0;
        joinedDialog = fought ? null : dlg;
        log(fought ? "battle over - leaving the dialog" : "joining the battle");
        dlg.optionSelected(fought ? "Leave" : "Join the battle", fought ? "leave" : "join");
    }

    // ------------------------------------------------------------------ combat
    private static long strafeFlipNs;
    private static boolean strafeLeft;

    /** Guest in a joined battle: fill in this frame's input from a simple fight-the-nearest-enemy routine.
     *  Returns false when there is no ship to fly yet (the normal input is used then). */
    public static boolean combatInput(CombatEngineAPI engine, Wire.Input in) {
        if (!ACTIVE || engine == null) return false;
        ShipAPI me = engine.getPlayerShip();
        if (me == null || !me.isAlive()) return false;
        ShipAPI best = null; float bestD = Float.MAX_VALUE;
        for (ShipAPI s : engine.getShips()) {
            if (!s.isAlive() || s.isHulk() || s.isFighter() || s.getOwner() == me.getOwner() || s.getOwner() > 1) continue;
            float dx = s.getLocation().x - me.getLocation().x, dy = s.getLocation().y - me.getLocation().y, dd = dx * dx + dy * dy;
            if (dd < bestD) { bestD = dd; best = s; }
        }
        int b = Wire.B_STRAFE_MOD;
        if (best == null) {
            in.aimX = me.getLocation().x + (float) Math.cos(Math.toRadians(me.getFacing())) * 500;
            in.aimY = me.getLocation().y + (float) Math.sin(Math.toRadians(me.getFacing())) * 500;
        } else {
            float dist = (float) Math.sqrt(bestD);
            in.aimX = best.getLocation().x; in.aimY = best.getLocation().y;
            if (dist > 700) b |= Wire.B_ACCEL;
            else if (dist < 350) b |= Wire.B_BACK;
            if (dist < 1100) b |= Wire.B_FIRE;
            long now = System.nanoTime();
            if (now > strafeFlipNs) { strafeFlipNs = now + (2 + rnd.nextInt(3)) * 1_000_000_000L; strafeLeft = rnd.nextBoolean(); }
            if (dist < 1100) b |= strafeLeft ? Wire.B_STRAFE_L : Wire.B_STRAFE_R;
        }
        in.bits = b;
        return true;
    }
}
