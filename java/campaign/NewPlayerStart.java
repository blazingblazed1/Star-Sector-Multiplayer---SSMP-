package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.impl.campaign.rulecmd.NGCAddStandardStartingScript;
import java.awt.Color;
import java.util.*;
import starmp.StarMPPlugin;

/**
 * 0.1.4.9: a player the host has no record of starts like a brand-new player instead of a copy of the host's
 * character: picks one of the five vanilla starts (same ships, credits and cargo as a vanilla new game that skips the
 * tutorial), level 1, no skills, fresh reputation, no commission, contacts or missions, at the vanilla start spot
 * (Corvus -2500,3000). Only this player's own fleet/character in their own game changes; the record is then sent to
 * the host like any other.
 */
public class NewPlayerStart implements InteractionDialogPlugin {
    private InteractionDialogAPI dialog;
    private String first; // wolf / wayfarer, waiting for the second ship
    static volatile boolean done;

    static void open() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        ui.showInteractionDialog(new NewPlayerStart(), Global.getSector().getPlayerFleet());
    }

    @Override public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        TextPanelAPI t = dialog.getTextPanel();
        t.addPara("StarMP: welcome to " + hostName() + "'s sector.", Color.ORANGE);
        t.addPara("You are new here, so you start like a new captain: pick your start. You begin with fresh reputation, "
                + "no commission, level 1, near Corvus (the usual starting spot).");
        t.addPara("Your most recent occupation was as...");
        OptionPanelAPI o = dialog.getOptionPanel();
        o.clearOptions();
        o.addOption("A bounty hunter, commanding a Wolf-class frigate", "wolf");
        o.addOption("A scavenger, commanding a Wayfarer-class combat freighter", "wayfarer");
        o.addOption("An explorer, leading a salvage expedition from aboard an Apogee-class cruiser (faster start)", "explorer");
        o.addOption("A mercenary, leading a small force from aboard a Hammerhead-class destroyer (faster start)", "merc");
        o.addOption("A freelancer, doing whatever jobs you've been able to find (randomized start)", "random");
        dialog.setPromptText("");
    }

    private static String hostName() {
        try { return starmp.combat.CoopState.nameOf(StarMPPlugin.net(), 0); } catch (Throwable t) { return "the host"; }
    }

    @Override public void optionSelected(String text, Object data) {
        String id = String.valueOf(data);
        OptionPanelAPI o = dialog.getOptionPanel();
        if ("wolf".equals(id) || "wayfarer".equals(id)) {
            first = id;
            dialog.getTextPanel().addPara(text, Color.CYAN);
            o.clearOptions();
            o.addOption("A Kite-class shuttle, under the command of an experienced subordinate", "kite");
            o.addOption("A Shepherd class drone tender, with a cargo of heavy machinery", "shepherd");
            return;
        }
        String start = first != null ? first : id, second = first != null ? id : null;
        try {
            String summary = apply(start, second);
            Global.getSector().getCampaignUI().addMessage("StarMP: you start as a new captain - " + summary);
            StarMPPlugin.flog("new player start: " + start + (second != null ? "+" + second : "") + " -> " + summary);
        } catch (Throwable t) { StarMPPlugin.flog("new player start FAILED: " + StarMPPlugin.trace(t)); }
        done = true;
        dialog.dismiss();
    }

    /** Rebuilds this player's own fleet and character as a vanilla new game (skip-tutorial path). */
    static String apply(String start, String second) {
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        FleetDataAPI fd = fleet.getFleetData();
        PersonAPI player = sector.getPlayerPerson();

        // ---- clear the copy of the host's character
        for (OfficerDataAPI od : fd.getOfficersCopy()) if (od.getPerson() != null && !od.getPerson().isPlayer()) fd.removeOfficer(od.getPerson());
        for (FleetMemberAPI m : fd.getMembersListCopy()) fd.removeFleetMember(m);
        CargoAPI cargo = fleet.getCargo();
        cargo.clear();
        cargo.getCredits().set(2000f); // onNewGameCreationStart: NGCAddCredits 2000
        resetCharacter(sector);
        clearPlayerIntel(sector);
        resetReputation(sector);

        // ---- ships (rules.csv ngc*Picked)
        boolean addOfficer = false, explorer = "explorer".equals(start), merc = "merc".equals(start), random = "random".equals(start);
        switch (start) {
            case "wolf": fd.addFleetMember("wolf_Starting"); break;
            case "wayfarer": fd.addFleetMember("wayfarer_Starting"); break;
            case "explorer":
                for (String v : new String[]{"apogee_Starting", "condor_Support", "wayfarer_Starting", "shepherd_Starting", "dram_Light"}) fd.addFleetMember(v);
                cargo.getCredits().add(18000f); break;
            case "merc":
                for (String v : new String[]{"hammerhead_Balanced", "drover_Starting", "centurion_Starting", "lasher_CS", "dram_Light"}) fd.addFleetMember(v);
                cargo.getCredits().add(18000f); addOfficer = true; break;
            default: fd.addFleetMember("hammerhead_Balanced"); break; // ngcRandomPicked: NGCAddShipSilent hammerhead_Balanced
        }
        if ("kite".equals(second)) { fd.addFleetMember("kite_Starting"); addOfficer = true; }
        else if ("shepherd".equals(second)) { fd.addFleetMember("shepherd_Starting"); cargo.addCommodity(Commodities.HEAVY_MACHINERY, 20); }

        // ---- NGCAddStandardStartingScript (skipTutorial = true), same order as vanilla
        int crew = 0, supplies = 0;
        for (FleetMemberAPI m : fd.getMembersListCopy()) {
            crew += Math.ceil(m.getMinCrew() + (m.getMaxCrew() - m.getMinCrew()) * 0.5f);
            supplies += m.getDeploymentCostSupplies() * 4f;
        }
        cargo.initPartialsIfNeeded();
        cargo.addCrew(crew);
        cargo.addSupplies(10);
        cargo.addCommodity(Commodities.HEAVY_MACHINERY, 10);
        cargo.addFuel(cargo.getMaxFuel() * 0.5f);
        if (explorer) {
            cargo.addCommodity(Commodities.HEAVY_MACHINERY, 50); cargo.addSupplies(150); cargo.removeFuel(cargo.getMaxFuel() * 0.2f);
            NGCAddStandardStartingScript.adjustStartingHulls(fleet);
        } else if (merc) {
            cargo.addSupplies(100); cargo.removeFuel(cargo.getMaxFuel() * 0.2f);
            NGCAddStandardStartingScript.adjustStartingHulls(fleet);
        } else if (random) {
            addOfficer = NGCAddStandardStartingScript.genRandomStart(fleet);
        }
        fd.ensureHasFlagship();
        if (fleet.getFlagship() != null) fleet.getFlagship().setCaptain(player);
        if (addOfficer) {
            for (FleetMemberAPI m : fd.getMembersListCopy()) {
                if (m.isFlagship()) continue;
                PersonAPI officer = sector.getPlayerFaction().createRandomPerson(new Random());
                officer.getStats().setSkillLevel(Skills.HELMSMANSHIP, 1);
                officer.setRankId(Ranks.SPACE_LIEUTENANT);
                officer.setPostId(Ranks.POST_OFFICER);
                officer.setPersonality(Personalities.STEADY);
                officer.getStats().refreshCharacterStatsEffects();
                m.setCaptain(officer);
                fd.addOfficer(officer);
                break;
            }
        }
        for (FleetMemberAPI m : fd.getMembersListCopy()) m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
        fd.setSyncNeeded();
        // skip-tutorial extras
        float free = cargo.getSpaceLeft();
        float addMachinery = Math.min(free, random ? 5 + 20 * (float) Math.random() : 15);
        if (addMachinery > 0) { cargo.addCommodity(Commodities.HEAVY_MACHINERY, addMachinery); free -= addMachinery; }
        float addSupplies = Math.min(free, 70);
        if (random) {
            supplies = 0;
            for (FleetMemberAPI m : fd.getMembersListCopy()) supplies += m.getDeploymentCostSupplies() * 4f;
            cargo.removeSupplies(cargo.getSupplies());
            free = cargo.getSpaceLeft();
            addSupplies = Math.min(free, supplies + 20f + 20f * (float) Math.random());
        }
        if (addSupplies > 0) cargo.addSupplies(addSupplies);
        float addFuel = Math.min(cargo.getFreeFuelSpace() - 10f, 70);
        if (random) { cargo.removeFuel(cargo.getFuel()); addFuel = Math.min(cargo.getFreeFuelSpace(), cargo.getMaxFuel() * (0.25f + 0.25f * (float) Math.random())); }
        if (addFuel > 0) cargo.addFuel(addFuel);
        float addCrew = Math.min(cargo.getFreeCrewSpace() - 10f, 100f);
        if (addCrew > 0) cargo.addCrew((int) addCrew);
        if (!random) cargo.getCredits().add(30000f);
        sector.getPlayerStats().addPoints(1); // NGCAddCharacterPoints 1 (skip tutorial)

        ensureAbilities(fleet);
        fleet.setTransponderOn(true);
        PlayerRecords.moveTo(sector, fleet, "corvus", -2500f, 3000f);
        fleet.forceSync();
        return fd.getNumMembers() + " ships, " + Math.round(cargo.getCredits().get()) + " credits";
    }

    /** Level 1, no XP, no skills, no story points (a new character). */
    static void resetCharacter(SectorAPI sector) {
        MutableCharacterStatsAPI st = sector.getPlayerStats();
        for (MutableCharacterStatsAPI.SkillLevelAPI sl : st.getSkillsCopy()) st.setSkillLevel(sl.getSkill().getId(), 0);
        st.setLevel(1); st.setXP(0); st.setBonusXp(0); st.setDeferredBonusXp(0); st.setStoryPoints(0); st.setPoints(0);
        st.refreshCharacterStatsEffects();
    }

    /** The host's commission, contacts and accepted missions are not this player's. Local to this game only. */
    static void clearPlayerIntel(SectorAPI sector) {
        try { com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel ci = com.fs.starfarer.api.util.Misc.getCommissionIntel(); if (ci != null) ci.endMission(); }
        catch (Throwable t) { StarMPPlugin.flog("new player: commission not ended: " + t); }
        int n = 0;
        for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : new ArrayList<>(sector.getIntelManager().getIntel())) {
            try {
                if (i instanceof com.fs.starfarer.api.impl.campaign.intel.contacts.ContactIntel) { ((com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin) i).endImmediately(); n++; }
                else if (i instanceof com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission
                        && ((com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission) i).getCurrentStage() != null) { ((com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission) i).abort(); n++; }
            } catch (Throwable ignored) {}
        }
        if (n > 0) StarMPPlugin.flog("new player: removed " + n + " contacts/missions that belonged to the host's character");
    }

    /** Vanilla starting standings (SectorGen + the skip-tutorial Hegemony bonus). Inherently hostile factions stay hostile. */
    static void resetReputation(SectorAPI sector) {
        FactionAPI pl = sector.getPlayerFaction();
        Map<String, Float> def = new HashMap<>();
        def.put(Factions.PIRATES, -0.65f); def.put(Factions.LUDDIC_PATH, -0.65f); def.put(Factions.HEGEMONY, 0.15f);
        for (String f : new String[]{Factions.TRITACHYON, Factions.PERSEAN, Factions.INDEPENDENT, Factions.KOL, Factions.LUDDIC_CHURCH, Factions.DIKTAT, Factions.LIONS_GUARD}) def.put(f, 0f);
        for (FactionAPI f : sector.getAllFactions()) {
            String id = f.getId();
            if (id.equals(Factions.PLAYER) || id.startsWith("starmp_")) continue;
            Float v = def.get(id);
            if (v != null) { pl.setRelationship(id, v); continue; }
            if (pl.getRelationship(id) <= -0.5f) continue; // remnants, threat, derelicts... (hostile from the start)
            pl.setRelationship(id, 0f);
        }
    }

    /** A new captain's standing with one faction (for a stand-in whose player has no record yet). */
    static float defaultRelation(String id) {
        switch (id) {
            case Factions.PIRATES: case Factions.LUDDIC_PATH: return -0.65f;
            case Factions.HEGEMONY: return 0.15f;
            default: {
                FactionAPI pl = Global.getSector().getPlayerFaction();
                return pl.getRelationship(id) <= -0.5f && !isCore(id) ? pl.getRelationship(id) : 0f;
            }
        }
    }
    private static boolean isCore(String id) {
        return id.equals(Factions.TRITACHYON) || id.equals(Factions.PERSEAN) || id.equals(Factions.INDEPENDENT) || id.equals(Factions.KOL)
                || id.equals(Factions.LUDDIC_CHURCH) || id.equals(Factions.DIKTAT) || id.equals(Factions.LIONS_GUARD);
    }

    static void ensureAbilities(CampaignFleetAPI fleet) {
        String[] ids = {Abilities.TRANSPONDER, Abilities.GO_DARK, Abilities.SENSOR_BURST, Abilities.EMERGENCY_BURN,
                Abilities.SUSTAINED_BURN, Abilities.SCAVENGE, Abilities.INTERDICTION_PULSE, Abilities.DISTRESS_CALL};
        for (String id : ids) if (fleet.getAbility(id) == null) { try { fleet.addAbility(id); } catch (Throwable ignored) {} }
        try { // a tutorial save empties the ability bar: fill the first bar if it's empty
            com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotsAPI slots = Global.getSector().getUIData().getAbilitySlotsAPI();
            slots.setCurrBarIndex(0);
            boolean empty = true;
            for (com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotAPI s : slots.getCurrSlotsCopy()) if (s.getAbilityId() != null) { empty = false; break; }
            if (empty) { List<com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotAPI> l = slots.getCurrSlotsCopy(); for (int i = 0; i < ids.length && i < l.size(); i++) l.get(i).setAbilityId(ids[i]); }
        } catch (Throwable t) { StarMPPlugin.flog("new player: ability bar not filled: " + t); }
    }

    @Override public void optionMousedOver(String optionText, Object optionData) {}
    @Override public void advance(float amount) {}
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> getMemoryMap() { return null; }
}
