package starmp;

import com.fs.starfarer.api.Global;
import java.util.LinkedHashMap;
import java.util.Map;

/** StarMP settings in saves/common/starmp_settings.txt (game API; java.io file access is blocked for mods). */
public final class Prefs {
    private Prefs() {}
    private static final String FILE = "starmp_settings.txt";
    private static final Map<String, String> map = new LinkedHashMap<>();
    private static boolean loaded;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            String txt = Global.getSettings().readTextFileFromCommon(FILE);
            if (txt != null) for (String line : txt.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized String get(String k, String def) { load(); String v = map.get(k); return v == null ? def : v; }
    public static boolean getBool(String k, boolean def) { return Boolean.parseBoolean(get(k, String.valueOf(def))); }

    public static synchronized void put(String k, String v) {
        load();
        map.put(k, v.replace('\n', ' '));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try { Global.getSettings().writeTextFileToCommon(FILE, sb.toString()); }
        catch (Throwable t) { StarMPPlugin.flog("could not save settings: " + t); }
    }
}
