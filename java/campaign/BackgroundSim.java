package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import starmp.NetManager;
import java.util.ArrayList;
import java.util.List;
import starmp.StarMPPlugin;

/**
 * "Never pause" (0.1.4.6): the host owns the one campaign simulation, so it must keep running while the host
 * player sits in a market / talks to a fleet / has a core tab or the options menu open. Vanilla pauses the
 * campaign engine for those screens; here the host advances the engine itself, once per frame, with the real
 * frame time. Nothing is handed to anyone else: still a single simulator (the host), guests keep mirroring it.
 *
 * Only while hosting with at least one guest, and only for a UI pause - a deliberate pause (pause key, shared
 * with everyone) still stops the world. The host's own fleet is held in place and ignored by other fleets while
 * the host is in a screen, so nothing can try to start a second encounter with it.
 *
 * Called from CoopCampaign.advance (a runWhilePaused transient script, i.e. from inside the engine's own advance;
 * the engine copies its script lists before iterating, so a nested advance is safe - CoopCampaign skips itself
 * while nested).
 */
public final class BackgroundSim {
    private BackgroundSim() {}

    /** True while we are inside our own nested engine advance. */
    static volatile boolean nested;
    /** True while that nested advance comes from combat (the campaign has no frame of its own then, so the co-op
     * campaign script must run inside it - minus anything that opens screens or changes cargo). */
    static volatile boolean inCombat;
    private static long lastCombatNs;
    private static int combatFrames;
    private static MethodHandle advance;   // CampaignEngine.advance(float, <obfuscated input class>)
    private static boolean broken, announced;
    private static int errors;
    private static long lastLogNs;
    private static float simulatedDays;
    private static boolean wasRunning;

    static boolean wasRunning() { return wasRunning; }

    public static boolean enabled() { return starmp.Prefs.getBool("neverPause", true); }

    /** One background step if the host's world is held only by a screen. Returns true if it advanced. */
    static boolean hostTick(SectorAPI sector, NetManager net, float amount, boolean userPaused) {
        if (broken || nested || !enabled()) return false;
        if (net == null || !net.isHost() || net.state() != NetManager.State.HOSTING || net.peerIds().isEmpty()) { stop(); return false; }
        if (!sector.isPaused() || userPaused) { stop(); return false; }
        CampaignUIAPI ui = sector.getCampaignUI();
        boolean screen = ui.getCurrentInteractionDialog() != null || ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null;
        if (!screen) { stop(); return false; }
        // NEVER while an encounter is open: vanilla's fleet-encounter dialog assumes the world is frozen; advancing
        // under it changed the battle's sides and crashed the game ("BattleAPI.getSideFor ... is null", 0.1.4.6/7).
        // Markets, planets, stations, menus and tabs keep running; a fleet dialog / battle holds the host's world.
        CampaignFleetAPI meB = sector.getPlayerFleet();
        if (meB != null && meB.getBattle() != null) { stop(); return false; }
        if (ui.getCurrentInteractionDialog() != null && ui.getCurrentInteractionDialog().getInteractionTarget() instanceof CampaignFleetAPI
                && !((CampaignFleetAPI) ui.getCurrentInteractionDialog().getInteractionTarget()).isPlayerFleet()) { stop(); return false; } // (F8 / StarMP dialogs are on our own fleet: fine)
        if (HotJoin.busy()) return false; // a save is being made / sent: hold still
        if (advance == null && !resolve(sector)) return false;
        if (amount <= 0f) return false;
        amount = Math.min(amount, 0.2f); // a hitch must not turn into a big jump (0.2: slow machines still keep up)

        CampaignFleetAPI me = sector.getPlayerFleet();
        float x = me == null ? 0 : me.getLocation().x, y = me == null ? 0 : me.getLocation().y;
        if (me != null) {
            me.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true, 0.1f);
            me.setNoEngaging(0.5f);
        }
        long before = sector.getClock().getTimestamp();
        List<Object[]> held = holdDialogMemory(ui);
        nested = true;
        try {
            sector.setPaused(false);
            advance.invoke(sector, amount, (Object) null);
        } catch (Throwable t) {
            if (++errors >= 3) { broken = true; StarMPPlugin.flog("never-pause: OFF after repeated errors: " + StarMPPlugin.trace(t)); }
            else StarMPPlugin.flog("never-pause: advance error " + StarMPPlugin.trace(t));
        } finally {
            sector.setPaused(true);
            nested = false;
        }
        restoreDialogMemory(held);
        try { NpcSync.pinHeld(); } catch (Throwable ignored) {}
        if (me != null) { // the fleet stays where it docked / talked
            me.setLocation(x, y);
            me.setVelocity(0f, 0f);
            me.setMoveDestination(x, y);
        }
        simulatedDays += (sector.getClock().getTimestamp() - before) / 86_400_000f;
        if (!wasRunning) { wasRunning = true; StarMPPlugin.flog("never-pause: host is in a screen - world keeps running"); }
        if (System.nanoTime() - lastLogNs > 15_000_000_000L) {
            lastLogNs = System.nanoTime();
            StarMPPlugin.flog("never-pause: running in background, " + String.format("%.2f", simulatedDays) + " days so far, date " + sector.getClock().getDateString());
        }
        return true;
    }

    private static int memRestoreLogs;

    /**
     * 0.1.5.8 (Toasty: "host can't leave the station/planet half the time, all the buttons get removed"):
     * vanilla's planet/station/market dialogs keep short-lived values in memory (rules memory: the planet, its market,
     * faction, player, global) and expect the world to be frozen while the dialog is up. Running the world under the
     * dialog let those values time out; when the dialog then rebuilt its options (closing Trade / the fleet screen,
     * going back from a sub-menu) no option rule matched any more - "You decide to..." with no buttons, stuck.
     * Reproduced on the test rig: dock, open Trade, close it -> no buttons. Fix: the timed values the open dialog
     * can see are held for as long as the dialog is up (any of them the world step removed is put back with the same
     * value and timer). Values the game removes on purpose have no timer and are left alone.
     */
    private static List<Object[]> holdDialogMemory(CampaignUIAPI ui) {
        com.fs.starfarer.api.campaign.InteractionDialogAPI d = ui.getCurrentInteractionDialog();
        if (d == null) return null;
        // every memory the open dialog's rules can read (label -> memory, identity-deduplicated)
        java.util.Map<Object, String> mems = new java.util.IdentityHashMap<>();
        try {
            java.util.Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> map = d.getPlugin() == null ? null : d.getPlugin().getMemoryMap();
            if (map != null) for (java.util.Map.Entry<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> e : map.entrySet()) if (e.getValue() != null) mems.put(e.getValue(), e.getKey());
        } catch (Throwable ignored) {}
        try {
            com.fs.starfarer.api.campaign.SectorEntityToken t = d.getInteractionTarget();
            if (t != null) {
                mems.put(t.getMemoryWithoutUpdate(), "target");
                if (t.getActivePerson() != null) mems.put(t.getActivePerson().getMemoryWithoutUpdate(), "person");
                if (t.getMarket() != null) {
                    mems.put(t.getMarket().getMemoryWithoutUpdate(), "market");
                    for (com.fs.starfarer.api.characters.PersonAPI p : t.getMarket().getPeopleCopy()) mems.put(p.getMemoryWithoutUpdate(), "marketPerson");
                }
            }
        } catch (Throwable ignored) {}
        // 0.1.6.2 (ZincPizza: bar arms dealer -> "no rule found for option cpc_startBar"): bar missions write their
        // dialog values (expire 0) into the mission PERSON's memory, which is only in the dialog's memory map while
        // the option is being added - so the hold above missed them and the world step removed $cpc_armsDealer
        // before the option was picked. Hold every active bar event's person memory too.
        try {
            for (com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent ev : com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager.getInstance().getActive().getItems()) {
                if (ev instanceof com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionBarEventWrapper) {
                    com.fs.starfarer.api.impl.campaign.missions.hub.HubMission m = ((com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionBarEventWrapper) ev).getMission();
                    if (m != null && m.getPerson() != null) mems.put(m.getPerson().getMemoryWithoutUpdate(), "barPerson");
                }
            }
        } catch (Throwable ignored) {}
        if (mems.isEmpty()) return null;
        List<Object[]> out = new ArrayList<>();
        for (java.util.Map.Entry<Object, String> e : mems.entrySet()) {
            com.fs.starfarer.api.campaign.rules.MemoryAPI m = (com.fs.starfarer.api.campaign.rules.MemoryAPI) e.getKey();
            for (String k : new ArrayList<>(m.getKeys())) {
                float exp = m.getExpire(k);
                if (exp < 0f) continue; // no timer: only the game itself removes it
                out.add(new Object[]{m, k, m.get(k), exp, e.getValue()});
            }
        }
        return out;
    }

    private static void restoreDialogMemory(List<Object[]> held) {
        if (held == null) return;
        int n = 0; StringBuilder names = null;
        for (Object[] h : held) {
            com.fs.starfarer.api.campaign.rules.MemoryAPI m = (com.fs.starfarer.api.campaign.rules.MemoryAPI) h[0];
            String k = (String) h[1];
            if (m.contains(k)) continue;
            m.set(k, h[2], (Float) h[3]);
            n++;
            if (memRestoreLogs < 30) { if (names == null) names = new StringBuilder(); if (names.length() < 300) names.append(h[4]).append('.').append(k).append(' '); }
        }
        if (n > 0 && memRestoreLogs < 30) { memRestoreLogs++; StarMPPlugin.flog("never-pause: held " + n + " dialog value(s) that would have timed out: " + names); }
    }

    /**
     * Step 1b: the host is in a battle (its own, a guest's, or the refit simulator). The campaign state is suspended,
     * so nothing advances the sector at all; advance it here, with wall-clock time (a paused battle does not stop the
     * world for players who aren't in it). The host's campaign fleet is parked in its battle and ignored by others.
     */
    public static void combatTick(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        // OFF since 0.1.4.8: advancing the whole campaign engine during the host's battle changes that battle under
        // vanilla's feet - on returning from combat FleetInteractionDialogPluginImpl.backFromEngagement crashed
        // ("BattleAPI.getPlayerSideSnapshot() is null", reproduced live). The host's world holds while it fights,
        // as before 0.1.4.6. Kept for research only (env STARMP_EXPERIMENTAL_COMBAT_BG=1).
        if (!"1".equals(System.getenv("STARMP_EXPERIMENTAL_COMBAT_BG"))) return;
        if (broken || nested || !enabled()) return;
        NetManager net = StarMPPlugin.net();
        if (net == null || !net.isHost() || net.state() != NetManager.State.HOSTING || net.peerIds().isEmpty()) { lastCombatNs = 0; return; }
        if (engine == null || !(engine.isInCampaign() || engine.isInCampaignSim())) return;
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null || HotJoin.busy()) return;
        if (advance == null && !resolve(sector)) return;
        long now = System.nanoTime();
        if (lastCombatNs == 0) { lastCombatNs = now; StarMPPlugin.flog("never-pause: host entered combat - campaign keeps running in the background"); return; }
        float amount = Math.min(0.2f, (now - lastCombatNs) / 1e9f);
        lastCombatNs = now;
        if (amount <= 0f) return;
        CampaignFleetAPI me = sector.getPlayerFleet();
        float x = me.getLocation().x, y = me.getLocation().y;
        me.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true, 0.1f);
        me.setNoEngaging(0.5f);
        boolean wasPaused = sector.isPaused();
        long before = sector.getClock().getTimestamp();
        // everyone in the host's battle stays exactly as it is while the rest of the world runs
        List<CampaignFleetAPI> frozen = new ArrayList<>();
        com.fs.starfarer.api.campaign.BattleAPI battle = me.getBattle();
        if (battle != null) for (CampaignFleetAPI f : battle.getBothSides()) if (!f.isPlayerFleet()) { f.setDoNotAdvanceAI(true); frozen.add(f); }
        nested = true; inCombat = true;
        try {
            sector.setPaused(false);
            advance.invoke(sector, amount, (Object) null);
        } catch (Throwable t) {
            if (++errors >= 3) { broken = true; StarMPPlugin.flog("never-pause: OFF after repeated errors (combat): " + StarMPPlugin.trace(t)); }
            else StarMPPlugin.flog("never-pause: combat advance error " + StarMPPlugin.trace(t));
        } finally {
            sector.setPaused(wasPaused);
            nested = false; inCombat = false;
            for (CampaignFleetAPI f : frozen) { try { f.setDoNotAdvanceAI(null); } catch (Throwable ignored) {} }
        }
        try { NpcSync.pinHeld(); } catch (Throwable ignored) {}
        me.setLocation(x, y); me.setVelocity(0f, 0f); me.setMoveDestination(x, y);
        simulatedDays += (sector.getClock().getTimestamp() - before) / 86_400_000f;
        if (++combatFrames % 600 == 0) StarMPPlugin.flog("never-pause: combat background, " + String.format("%.2f", simulatedDays) + " days so far, date " + sector.getClock().getDateString());
    }

    /** From the combat plugin's init: a new battle starts (resets the wall clock). */
    public static void combatStarted() { lastCombatNs = 0; combatFrames = 0; simulatedDays = 0f; }

    private static void stop() {
        if (wasRunning) { wasRunning = false; StarMPPlugin.flog("never-pause: screen closed (" + String.format("%.2f", simulatedDays) + " days simulated in background)"); simulatedDays = 0f; }
    }

    /** Finds CampaignEngine.advance(float, X) from the class file (X is obfuscated, differs per OS). */
    private static boolean resolve(SectorAPI sector) {
        try {
            Class<?> c = sector.getClass();
            starmp.ui.Obf.setLoader(c.getClassLoader());
            String desc = null;
            for (String d : starmp.ui.Obf.methodDescs(c.getName(), "advance")) if (d.startsWith("(FL") && d.endsWith(";)V")) { desc = d; break; }
            if (desc == null) throw new IllegalStateException("advance(float, ?) not found on " + c.getName());
            MethodType mt = MethodType.fromMethodDescriptorString(desc, c.getClassLoader());
            advance = MethodHandles.publicLookup().findVirtual(c, "advance", mt)
                    .asType(MethodType.methodType(void.class, Object.class, float.class, Object.class));
            if (!announced) { announced = true; StarMPPlugin.flog("never-pause: engine hook ready " + c.getName() + ".advance" + desc); }
            return true;
        } catch (Throwable t) {
            broken = true;
            StarMPPlugin.flog("never-pause: cannot hook the engine - feature off: " + StarMPPlugin.trace(t));
            return false;
        }
    }
}
