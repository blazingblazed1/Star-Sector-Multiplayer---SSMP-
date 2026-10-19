package starmp.campaign;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import starmp.ui.Obf;
import starmp.StarMPPlugin;

/** 0.9.3.7 (debug only, STARMP_TEST_UIPROBE=1): logs the component tree under a game UI object, to find the Factions list / escape menu buttons. */
final class UiProbe {
    private UiProbe() {}
    static final boolean ON = System.getenv("STARMP_TEST_UIPROBE") != null;
    private static final MethodHandles.Lookup PUB = MethodHandles.publicLookup();

    @SuppressWarnings("unchecked")
    static List<Object> kids(Object o) {
        try {
            if (Obf.declaring(o.getClass().getName(), "getChildrenNonCopy") == null) return Collections.emptyList();
            return (List<Object>) PUB.findVirtual(o.getClass(), "getChildrenNonCopy", MethodType.methodType(List.class)).invoke(o);
        } catch (Throwable t) { return Collections.emptyList(); }
    }

    static Object call(Object o, String method) throws Throwable {
        String desc = Obf.methodDesc(Obf.declaring(o.getClass().getName(), method), method);
        if (desc == null) return null;
        return PUB.findVirtual(o.getClass(), method, MethodType.fromMethodDescriptorString(desc, o.getClass().getClassLoader())).invoke(o);
    }

    static String text(Object o) {
        try {
            if (Obf.declaring(o.getClass().getName(), "getText") == null) return "";
            return String.valueOf(PUB.findVirtual(o.getClass(), "getText", MethodType.methodType(String.class)).invoke(o));
        } catch (Throwable t) { return ""; }
    }

    static void dump(String tag, Object o, int depth, int max) {
        if (o == null || depth > max) return;
        StringBuilder sb = new StringBuilder("uiprobe " + tag + " ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(o.getClass().getName());
        String t = text(o); if (!t.isEmpty() && !t.equals("null")) sb.append(" text='").append(t.length() > 60 ? t.substring(0, 60) : t).append("'");
        try {
            Object pos = PUB.findVirtual(o.getClass(), "getPosition", MethodType.methodType(Class.forName("com.fs.starfarer.api.ui.PositionAPI"))).invoke(o);
            com.fs.starfarer.api.ui.PositionAPI p = (com.fs.starfarer.api.ui.PositionAPI) pos;
            sb.append(" @").append((int) p.getX()).append(',').append((int) p.getY()).append(' ').append((int) p.getWidth()).append('x').append((int) p.getHeight());
        } catch (Throwable ignored) {}
        StarMPPlugin.flog(sb.toString());
        for (Object k : new ArrayList<>(kids(o))) dump(tag, k, depth + 1, max);
    }
}
