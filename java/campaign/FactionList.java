package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import java.util.*;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.4.0: the other players and the alliances appear in the Intel screen's Factions list, like the game's own factions.
 * Each gets one of the view-only factions shipped with the mod (starmp_v1..8 = players, starmp_a1..8 = alliances), switched on in the
 * Intel tab with the player's / alliance's name while the session is linked and switched off again when it ends.
 */
final class FactionList {
    private FactionList() {}
    private static long lastNs;
    private static final Map<String, Integer> playerSlot = new HashMap<>();
    private static final String[] shown = new String[17]; // what each slot currently shows (index 1..16: v1..v8, a1..a8)
    private static boolean logged;

    static void tick() {
        long now = System.nanoTime();
        if (now - lastNs < 1_000_000_000L) return;
        lastNs = now;
        try {
            if (Global.getSector() == null) return;
            NetManager n = StarMPPlugin.net();
            boolean on = n != null && n.linked();
            String[] want = new String[17];
            if (on) {
                String me = n.playerName() == null ? "" : n.playerName();
                LinkedHashSet<String> names = new LinkedHashSet<>();
                for (Integer id : n.peerIds()) { NetManager.Peer p = n.peer(id); if (p != null && p.name != null && !p.name.isEmpty()) names.add(p.name); }
                for (starmp.ui.Lobby.Member m : starmp.ui.Lobby.members.values()) if (m != null && m.name != null && !m.name.isEmpty()) names.add(m.name);
                names.remove(me);
                for (String nm : names) {
                    Integer s = playerSlot.get(nm.toLowerCase(Locale.ROOT));
                    if (s == null) { for (int i = 1; i <= 8 && s == null; i++) if (!playerSlot.containsValue(i)) s = i; if (s != null) playerSlot.put(nm.toLowerCase(Locale.ROOT), s); }
                    if (s == null) continue;
                    Alliances.Ally a = Alliances.of(nm);
                    want[s] = nm + (a == null ? "" : "  [" + a.tag + "]");
                }
                int ai = 1;
                for (Alliances.Ally a : Alliances.all()) { if (ai > 8) break; want[8 + ai] = a.name + "  [" + a.tag + "]"; ai++; }
            }
            for (int i = 1; i <= 16; i++) {
                if (Objects.equals(want[i], shown[i])) continue;
                FactionAPI f = Global.getSector().getFaction(i <= 8 ? "starmp_v" + i : "starmp_a" + (i - 8));
                if (f == null) continue;
                if (want[i] == null) { f.setShowInIntelTab(false); }
                else {
                    f.setDisplayNameOverride(want[i]); f.setDisplayNameWithArticleOverride(want[i]);
                    f.setShowInIntelTab(true);
                }
                shown[i] = want[i];
                if (!logged) { logged = true; StarMPPlugin.flog("factions list: " + (want[i] == null ? "hid" : "showing") + " slot " + i + (want[i] == null ? "" : " '" + want[i] + "'")); }
            }
        } catch (Throwable t) { if (!logged) { logged = true; StarMPPlugin.flog("factions list error: " + StarMPPlugin.trace(t)); } }
    }
}
