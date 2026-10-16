package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

/**
 * 0.1.4.10, Mike's rule: trading with or sending credits to a player who is NOT in the same star system costs the
 * SENDER a tax on top of what is sent - 100% of credits, 50% of the base value of items and ships. The tax is gone
 * (a credit sink). Hyperspace is one huge location, so there "same place" means within 2 light-years.
 */
public final class TradeTax {
    private TradeTax() {}
    public static final float CREDIT_RATE = 1.0f, ITEM_RATE = 0.5f, HYPER_FREE_LY = 2f;

    /** Is that player somewhere else (other star system, or far away in hyperspace)? Unknown position = not taxed. */
    public static boolean remote(int peer) {
        CampWire.Fleet f = CoopCampaign.fleets.get(peer);
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (f == null || f.loc == null || f.loc.isEmpty() || me == null || me.getContainingLocation() == null) return false;
        LocationAPI here = me.getContainingLocation();
        if (!here.getId().equals(f.loc)) return true;
        if (!here.isHyperspace()) return false;
        float dx = me.getLocation().x - f.x, dy = me.getLocation().y - f.y, r = HYPER_FREE_LY * FriendHud.UNITS_PER_LY;
        return dx * dx + dy * dy > r * r;
    }

    public static String where(int peer) {
        CampWire.Fleet f = CoopCampaign.fleets.get(peer);
        if (f == null) return "somewhere";
        LocationAPI l = CoopCampaign.location(f.loc);
        return l == null ? f.loc : l.getName();
    }

    /** Tax on sending this many credits. */
    public static float onCredits(float credits) { return Math.max(0f, credits) * CREDIT_RATE; }

    /** Tax on my side of a trade (what I send). */
    public static float onOffer(TradeSession.Offer o) {
        float v = 0f;
        for (TradeSession.Item i : o.items) v += i.qty * unitValue(i.type, i.id);
        for (FleetMemberAPI m : o.members) { try { v += m.getBaseValue(); } catch (Throwable ignored) {} }
        return onCredits(o.credits) + v * ITEM_RATE;
    }

    static float unitValue(CargoAPI.CargoItemType type, String id) {
        try {
            switch (type) {
                case RESOURCES: return Global.getSettings().getCommoditySpec(id).getBasePrice();
                case WEAPONS: return Global.getSettings().getWeaponSpec(id).getBaseValue();
                case FIGHTER_CHIP: return Global.getSettings().getFighterWingSpec(id).getBaseValue();
                case SPECIAL: return Global.getSettings().getSpecialItemSpec(id).getBasePrice();
                default: return 0f;
            }
        } catch (Throwable t) { return 0f; }
    }
}
