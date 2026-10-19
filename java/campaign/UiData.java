package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI;
import com.fs.starfarer.api.ui.MapFilterData;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import starmp.StarMPPlugin;

/**
 * 0.9.0.0: a guest's own UI settings, kept in their player record on the host (they live in the save, and a guest
 * loads the HOST's save every session, so they were lost): every ability bar (both normal and hyperspace slots), the
 * selected bar + lock, the map filters (starscape, names, constellations, factions, missions, fuel range, exploration,
 * legend), campaign / map zoom, and the refit screen's hull mod filters and checked tags.
 * Record lines: "U\t<kind>\t...". Uses only the API plus public getters of the game's UI data class (java.lang.invoke).
 */
final class UiData {
    private UiData() {}
    private static boolean warned;

    private static Object ui() { return Global.getSector().getUIData(); }

    private static Object call(Object o, String name, Class<?> ret) throws Throwable {
        return MethodHandles.publicLookup().findVirtual(o.getClass(), name, MethodType.methodType(ret)).invoke(o);
    }
    private static void set(Object o, String name, Class<?> arg, Object v) throws Throwable {
        MethodHandles.publicLookup().findVirtual(o.getClass(), name, MethodType.methodType(void.class, arg)).invoke(o, v);
    }

    /** All ability bars: slots[bar][slot] (the API only exposes the current bar). */
    static Object[][] bars(Object a) throws Throwable {
        Class<?> slot = Class.forName(a.getClass().getName().replace("$AbilitySlots", "$AbilitySlot"), false, a.getClass().getClassLoader());
        MethodHandle mh = MethodHandles.publicLookup().findVirtual(a.getClass(), "getSlots", MethodType.methodType(slot.arrayType().arrayType()));
        return (Object[][]) mh.invoke(a);
    }

    static void capture(StringBuilder sb) {
        try {
            PersistentUIDataAPI u = Global.getSector().getUIData();
            PersistentUIDataAPI.AbilitySlotsAPI a = u.getAbilitySlotsAPI();
            sb.append("U\tA\t").append(a.getCurrBarIndex()).append('\t').append(a.isLocked() ? 1 : 0).append('\n');
            Object[][] bars = bars(a);
            if (bars != null) for (int b = 0; b < bars.length; b++) {
                if (bars[b] == null) continue;
                for (int i = 0; i < bars[b].length; i++) {
                    PersistentUIDataAPI.AbilitySlotAPI s = (PersistentUIDataAPI.AbilitySlotAPI) bars[b][i];
                    if (s == null) continue;
                    sb.append("U\tB\t").append(b).append('\t').append(i).append('\t').append(nz(s.getAbilityId())).append('\t').append(nz(s.getInHyperAbilityId())).append('\n');
                }
            }
            MapFilterData f = (MapFilterData) call(u, "getMapFilterData", MapFilterData.class);
            if (f != null) sb.append("U\tF\t").append(b(f.starscape)).append(b(f.names)).append(b(f.constellations)).append(b(f.factions)).append(b(f.missions))
                    .append(b(f.fuel)).append(b(f.exploration)).append(b(f.legend)).append('\t').append(f.fuelColorAlphaMult).append('\t').append(f.fuelRangeMult).append('\n');
            sb.append("U\tZ\t").append(call(u, "getCampaignZoom", float.class)).append('\t').append(call(u, "getCampaignMapZoom", float.class)).append('\t').append(call(u, "getHyperMapZoom", float.class)).append('\n');
            sb.append("U\tH\t").append(b((Boolean) call(u, "isShowOnlyInstallableHullmods", boolean.class))).append(b((Boolean) call(u, "isHideInstalledHullmods", boolean.class))).append('\n');
            sb.append("U\tT\t").append(String.join(",", u.getCheckedRefitTags()).replace('\t', ' ').replace('\n', ' ')).append('\n');
        } catch (Throwable t) { if (!warned) { warned = true; StarMPPlugin.flog("ui data: capture failed: " + t); } }
    }

    static void apply(List<String[]> lines) {
        if (lines.isEmpty()) return;
        int n = 0;
        try {
            PersistentUIDataAPI u = Global.getSector().getUIData();
            PersistentUIDataAPI.AbilitySlotsAPI a = u.getAbilitySlotsAPI();
            Object[][] bars = bars(a);
            for (String[] l : lines) {
                try {
                    switch (l[1]) {
                        case "A": a.setCurrBarIndex(Integer.parseInt(l[2])); a.setLocked("1".equals(l[3])); n++; break;
                        case "B": {
                            int b = Integer.parseInt(l[2]), i = Integer.parseInt(l[3]);
                            if (bars == null || b >= bars.length || bars[b] == null || i >= bars[b].length || bars[b][i] == null) break;
                            PersistentUIDataAPI.AbilitySlotAPI s = (PersistentUIDataAPI.AbilitySlotAPI) bars[b][i];
                            s.setAbilityId(l[4].isEmpty() ? null : l[4]); s.setInHyperAbilityId(l[5].isEmpty() ? null : l[5]); n++; break;
                        }
                        case "F": {
                            MapFilterData f = (MapFilterData) call(u, "getMapFilterData", MapFilterData.class);
                            String bits = l[2];
                            if (f == null || bits.length() < 8) break;
                            f.starscape = bits.charAt(0) == '1'; f.names = bits.charAt(1) == '1'; f.constellations = bits.charAt(2) == '1'; f.factions = bits.charAt(3) == '1';
                            f.missions = bits.charAt(4) == '1'; f.fuel = bits.charAt(5) == '1'; f.exploration = bits.charAt(6) == '1'; f.legend = bits.charAt(7) == '1';
                            f.fuelColorAlphaMult = Float.parseFloat(l[3]); f.fuelRangeMult = Float.parseFloat(l[4]); n++; break;
                        }
                        case "Z": set(u, "setCampaignZoom", float.class, Float.parseFloat(l[2])); set(u, "setCampaignMapZoom", float.class, Float.parseFloat(l[3])); set(u, "setHyperMapZoom", float.class, Float.parseFloat(l[4])); n++; break;
                        case "H": set(u, "setShowOnlyInstallableHullmods", boolean.class, l[2].charAt(0) == '1'); set(u, "setHideInstalledHullmods", boolean.class, l[2].charAt(1) == '1'); n++; break;
                        case "T": { u.getCheckedRefitTags().clear(); for (String t : l[2].split(",")) if (!t.isEmpty()) u.getCheckedRefitTags().add(t); n++; break; }
                    }
                } catch (Throwable t) { StarMPPlugin.flog("ui data: line " + String.join("|", l) + " skipped: " + t); }
            }
        } catch (Throwable t) { StarMPPlugin.flog("ui data: apply failed: " + t); }
        String b2 = "?"; try { Object[][] bb = bars(Global.getSector().getUIData().getAbilitySlotsAPI()); b2 = ((PersistentUIDataAPI.AbilitySlotAPI) bb[1][3]).getAbilityId(); } catch (Throwable ignored) {}
        StarMPPlugin.flog("ui data: restored " + n + " toolbar / map / refit settings (bar 2 slot 4: " + b2 + ")");
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static char b(boolean v) { return v ? '1' : '0'; }
    static List<String[]> pick(String rec) { List<String[]> o = new ArrayList<>(); for (String line : rec.split("\n")) if (line.startsWith("U\t")) o.add(line.split("\t", -1)); return o; }
}
