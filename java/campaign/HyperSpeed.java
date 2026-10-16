package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import java.nio.ByteBuffer;
import java.util.*;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;

/**
 * Hyperspace speed-up (0.1.4.6): while linked, every fleet in hyperspace gets its max burn multiplied by one
 * tunable value (F8, Prefs "hyperSpeedMult", default 1.5). The HOST's value is the one used everywhere: the host
 * applies it to all fleets in hyperspace it simulates (NPCs too, so interceptions keep their balance), and sends it
 * to guests (HSET packet), who apply it to their own fleet only (their NPCs are the host's puppets, moved by the
 * stream). Modifiers are removed before every save and when unlinked, so nothing sticks in single player.
 */
public final class HyperSpeed {
    private HyperSpeed() {}

    public static final byte HSET = 27;
    private static final String MOD = "starmp_hyperspeed";
    public static final float[] STEPS = {1f, 1.25f, 1.5f, 2f, 3f};
    private static volatile float hostMult = -1f; // guest: the host's value
    private static final Set<CampaignFleetAPI> touched = Collections.newSetFromMap(new WeakHashMap<>());
    private static long lastApplyNs, lastSendNs;
    private static float lastLogged = -1f;

    public static float localSetting() {
        float v = 1.5f;
        try { v = Float.parseFloat(Prefs.get("hyperSpeedMult", "1.5")); } catch (Throwable ignored) {}
        return Math.max(1f, Math.min(5f, v));
    }

    public static void cycle() {
        float cur = localSetting(); float next = STEPS[0];
        for (int i = 0; i < STEPS.length; i++) if (Math.abs(STEPS[i] - cur) < 0.01f) { next = STEPS[(i + 1) % STEPS.length]; break; }
        Prefs.put("hyperSpeedMult", String.valueOf(next));
    }

    /** The value in force on this machine right now. */
    public static float effective(NetManager net) {
        if (net == null) return 1f;
        if (net.isHost()) return localSetting();
        return hostMult > 0 ? hostMult : 1f;
    }

    public static void onPacket(byte[] d) {
        if (d.length >= 5) { float m = ByteBuffer.wrap(d, 1, 4).getFloat(); if (m >= 1f && m <= 5f) hostMult = m; }
    }

    /** Campaign thread, every frame while linked. */
    static void tick(NetManager net, SectorAPI sector, boolean hosting) {
        long now = System.nanoTime();
        float mult = effective(net);
        if (hosting && now - lastSendNs > 3_000_000_000L) {
            lastSendNs = now;
            net.broadcast(ByteBuffer.allocate(5).put(HSET).putFloat(mult).array());
        }
        if (now - lastApplyNs < 250_000_000L) return;
        lastApplyNs = now;
        if (Math.abs(mult - lastLogged) > 0.001f) { lastLogged = mult; StarMPPlugin.flog("hyperspace speed x" + mult + (hosting ? " (host setting)" : " (from the host)")); }
        LocationAPI hyper = sector.getHyperspace();
        Set<CampaignFleetAPI> want = new HashSet<>();
        if (mult > 1.001f) {
            CampaignFleetAPI me = sector.getPlayerFleet();
            if (me != null && me.getContainingLocation() == hyper) want.add(me);
            if (hosting && hyper != null) for (CampaignFleetAPI f : hyper.getFleets()) if (!isPuppet(f)) want.add(f);
        }
        for (CampaignFleetAPI f : new ArrayList<>(touched)) if (!want.contains(f)) { f.getStats().getFleetwideMaxBurnMod().unmodify(MOD); touched.remove(f); }
        for (CampaignFleetAPI f : want) { f.getStats().getFleetwideMaxBurnMod().modifyMult(MOD, mult, "Hyperspace (multiplayer)"); touched.add(f); }
    }

    private static boolean isPuppet(CampaignFleetAPI f) {
        return f.getMemoryWithoutUpdate().contains("$starmp_puppet");
    }

    /** Before a save and when unlinked: take every modifier off. */
    public static void clearAll() {
        for (CampaignFleetAPI f : new ArrayList<>(touched)) { try { f.getStats().getFleetwideMaxBurnMod().unmodify(MOD); } catch (Throwable ignored) {} }
        touched.clear();
        try { CampaignFleetAPI me = Global.getSector().getPlayerFleet(); if (me != null) me.getStats().getFleetwideMaxBurnMod().unmodify(MOD); } catch (Throwable ignored) {}
        lastApplyNs = 0;
    }
}
