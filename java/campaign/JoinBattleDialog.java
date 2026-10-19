package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import java.util.Map;
import starmp.StarMPPlugin;

/**
 * Guest side: the host is in a battle. Joining starts a local battle that StarMP immediately replaces with the
 * host's (see CoopCombatPlugin). Because this dialog owns the battle, no vanilla loot/reputation/loss handling runs.
 */
public class JoinBattleDialog implements InteractionDialogPlugin {
    private InteractionDialogAPI dialog;
    private final String hostName, enemyName;
    private final CampaignFleetAPI stage;
    private boolean fought;
    private CampaignFleetAPI viewFleet;

    private final int owner;

    public JoinBattleDialog(int owner, String hostName, String enemyName, CampaignFleetAPI stage) {
        this.owner = owner; this.hostName = hostName; this.enemyName = enemyName; this.stage = stage;
    }

    /** 0.9.2.5: I'm the defender of this player's PvP attack - I join as their enemy and can't decline. */
    private boolean pvp() { return Pvp.defendJoin == owner; }

    @Override public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        if (pvp()) {
            dialog.getTextPanel().addPara(hostName + " is attacking you! Your combat ships fight against " + hostName + "'s fleet and you fly your flagship. Ships lost are lost from your fleet.", java.awt.Color.RED);
            dialog.getOptionPanel().addOption("Battle stations", "join");
            dialog.setPromptText("");
            return;
        }
        dialog.getTextPanel().addPara(hostName + " is in battle" + (enemyName.isEmpty() ? "." : " with " + enemyName + "."));
        dialog.getTextPanel().addPara("Join with your own fleet: your combat ships fight on " + hostName + "'s side and you fly your flagship. Ships lost there are lost from your fleet.");
        dialog.getTextPanel().addPara("The battle stays paused for everyone until you have loaded in.", java.awt.Color.YELLOW);
        dialog.getOptionPanel().addOption("Join with all combat ships", "join");
        dialog.getOptionPanel().addOption("Choose which ships to deploy", "pick");
        dialog.getOptionPanel().addOption("Not now", "leave");
        dialog.setPromptText("");
    }

    /** Ships that can fight (what "all" deploys): no civilian hulls, fighters, mothballed or ruined-CR ships. */
    private static java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> combatShips() {
        java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> ships = new java.util.ArrayList<>();
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        for (com.fs.starfarer.api.fleet.FleetMemberAPI m : me.getFleetData().getMembersListCopy()) {
            if (m.isFighterWing() || m.isMothballed() || m.isCivilian() || m.getRepairTracker().getCR() < 0.1f) continue;
            ships.add(m);
        }
        return ships;
    }

    private java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> chosen; // null = all combat ships

    @Override public void optionSelected(String optionText, Object optionData) {
        if ("pick".equals(optionData)) {
            final java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> all = combatShips();
            if (all.isEmpty()) { dialog.getTextPanel().addPara("You have no combat-ready ships to deploy.", java.awt.Color.ORANGE); return; }
            int cols = Math.min(6, Math.max(2, all.size())), rows = Math.max(1, (all.size() + cols - 1) / cols);
            try {
            dialog.showFleetMemberPickerDialog("Choose the ships to deploy", "Deploy", "Back", rows, cols, 64f, true, true, all,
                new FleetMemberPickerListener() {
                    @Override public void pickedFleetMembers(java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> picked) {
                        if (picked == null || picked.isEmpty()) { dialog.getTextPanel().addPara("No ships chosen.", java.awt.Color.ORANGE); return; }
                        chosen = new java.util.ArrayList<>(picked);
                        StarMPPlugin.flog("campaign: deploying " + chosen.size() + " of " + all.size() + " combat ships");
                        optionSelected("Join", "join");
                    }
                    @Override public void cancelledFleetMemberPicking() {}
                });
            } catch (Throwable t) {
                StarMPPlugin.flog("campaign: ship picker failed, deploying all combat ships: " + StarMPPlugin.trace(t));
                chosen = null; optionSelected("Join", "join");
            }
            return;
        }
        if ("join".equals(optionData)) {
            StarMPPlugin.flog("campaign: joining " + hostName + "'s battle (player " + owner + ")");
            starmp.combat.CoopState.battleOwner = owner;
            CoopCampaign.markJoined(owner);
            sendMyFleet();
            starmp.combat.CoopState.joiningBattle = true;
            if (pvp()) { starmp.combat.CoopState.pvpFlip = true; Pvp.defendJoin = -1; StarMPPlugin.flog("pvp: joining as " + hostName + "'s enemy"); }
            // The local battle is only a canvas for the other player's battle. Use a stand-in "view" fleet with nothing
            // deployable, so Starsector has no deployment screen to show and the real fleet is never involved.
            CampaignFleetAPI view = Global.getFactory().createEmptyFleet("player", "StarMP view", false);
            com.fs.starfarer.api.fleet.FleetMemberAPI vm = Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, "kite_Hull");
            vm.getRepairTracker().setMothballed(true);
            view.getFleetData().addFleetMember(vm);
            view.getMemoryWithoutUpdate().set("$starmp_stage", true);
            view.getMemoryWithoutUpdate().set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            view.getMemoryWithoutUpdate().set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
            stage.getContainingLocation().addEntity(view); // battle code needs both fleets on the map
            view.setLocation(stage.getLocation().x, stage.getLocation().y);
            viewFleet = view;
            BattleCreationContext ctx = new BattleCreationContext(view, FleetGoal.ATTACK, stage, FleetGoal.ATTACK);
            ctx.aiRetreatAllowed = false;
            boolean skipped = starmp.ui.Vanilla.skipNextDeploymentDialog(true);
            starmp.ui.Vanilla.suppressHelp("chmDeployCostsCR"); // vanilla's one-time deployment tip would cover the joined battle
            StarMPPlugin.flog("campaign: deployment screen " + (skipped ? "suppressed" : "could not be suppressed - press Cancel"));
            dialog.startBattle(ctx);
        } else {
            dialog.dismiss();
        }
    }

    /** Bring my own ships: send them (exact loadouts, CR, hull) to the host, who spawns them on my side. */
    private void sendMyFleet() {
        try {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> ships = chosen != null ? chosen : combatShips();
            int flag = -1;
            for (int i = 0; i < ships.size(); i++) if (ships.get(i) == me.getFlagship()) flag = i;
            if (flag < 0 && !ships.isEmpty()) flag = 0;
            CoopCampaign.joinedMembers = ships;
            starmp.combat.CoopState.sendTo(starmp.StarMPPlugin.net(), owner, starmp.combat.Wire.encodeDefs(starmp.combat.Wire.JOINFLEET, flag, starmp.combat.Ships.fleetDefs(ships)));
            StarMPPlugin.flog("campaign: sent my fleet to the host (" + ships.size() + " ships, flagship index " + flag + ")");
        } catch (Throwable t) { StarMPPlugin.flog("campaign: could not send my fleet: " + StarMPPlugin.trace(t)); }
    }

    @Override public void backFromEngagement(EngagementResultAPI battleResult) {
        fought = true;
        starmp.ui.Vanilla.skipNextDeploymentDialog(false); // normal battles show it again
        StarMPPlugin.flog("campaign: back from joint battle");
        dialog.getOptionPanel().clearOptions();
        dialog.getTextPanel().addPara("The battle is over.");
        dialog.getOptionPanel().addOption("Leave", "leave");
        if (stage.getMemoryWithoutUpdate().getBoolean("$starmp_stage") && stage.getContainingLocation() != null)
            stage.getContainingLocation().removeEntity(stage);
        if (viewFleet != null && viewFleet.getContainingLocation() != null) viewFleet.getContainingLocation().removeEntity(viewFleet);
    }

    @Override public void optionMousedOver(String optionText, Object optionData) {}
    private boolean testJoined;
    @Override public void advance(float amount) {
        if (!testJoined && !fought && pvp() && System.getenv("STARMP_TEST_PVP_CHOICE") != null) { testJoined = true; optionSelected("Battle stations", "join"); } // test hook
        starmp.Bot.joinDialogTick(this, fought);
    } // 0.1.6.5: no-op unless bot test mode
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
