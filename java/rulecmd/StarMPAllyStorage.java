package starmp.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import java.util.List;
import java.util.Map;
import starmp.StarMPPlugin;
import starmp.campaign.AllianceStorage;

/**
 * 0.9.2.4: rules.csv command for alliance storage (see data/campaign/rules.csv).
 *   StarMPAllyStorage available  - condition: alliance storage can be used at this market
 *   StarMPAllyStorage open       - show the storage menu
 *   StarMPAllyStorage mine       - condition: the picked option is one of the storage menu's
 *   StarMPAllyStorage option     - run the picked storage menu option
 */
public class StarMPAllyStorage extends BaseCommandPlugin {
    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        try {
            String what = params.isEmpty() ? "" : params.get(0).getString(memoryMap);
            String opt = "";
            try { MemoryAPI local = memoryMap.get(MemKeys.LOCAL); if (local != null) opt = local.getString("$option"); } catch (Throwable ignored) {}
            if (opt == null) opt = "";
            switch (what) {
                case "available": return dialog != null && dialog.getInteractionTarget() != null && AllianceStorage.available(dialog.getInteractionTarget().getMarket());
                case "open": AllianceStorage.open(dialog, memoryMap); return true;
                case "mine": return opt.startsWith("starmp_as_") && !opt.equals("starmp_as_open");
                case "option": AllianceStorage.option(opt, dialog, memoryMap); return true;
                default: return false;
            }
        } catch (Throwable t) {
            StarMPPlugin.flog("ally storage rule: " + t);
            return false;
        }
    }
}
