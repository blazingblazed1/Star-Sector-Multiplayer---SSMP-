package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.Misc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.StarMPPlugin;
import starmp.ui.Obf;

/**
 * 0.9.2.4: "Alliance [4]" - a 4th tab on the Intel screen, next to Intel / Planets / Factions.
 *
 * Starsector has no API for Intel tabs, so this finds the Intel screen panel (the one with getFactionsButton(), found
 * by walking the core UI from CampaignState.getCore()) and adds our button + panel to its own button -> panel map: the
 * game then switches between all four tabs itself (clicking Intel / Planets / Factions hides ours). Only public
 * methods plus one private field read (that map, located by its type with the class-file parser - no reflection).
 * If anything doesn't match (another game version), nothing is changed and the "Alliance (StarMP)" intel entry stays.
 */
public final class AllianceTab {
    private AllianceTab() {}
    private static volatile boolean works;
    private static Object injectedInto;     // the Intel screen panel we added the tab to
    private static boolean selected;        // our tab was the open one (re-opened next time)
    private static boolean failedLogged;
    private static ButtonAPI tabButton;
    private static CustomPanelAPI content;
    private static Object contentFader;

    /** The tab is in place (the intel entry hides itself then). */
    public static boolean working() { return works; }

    private static final MethodHandles.Lookup PUB = MethodHandles.publicLookup();

    private static Object call(Object o, String method) throws Throwable {
        String desc = Obf.methodDesc(Obf.declaring(o.getClass().getName(), method), method);
        if (desc == null) return null;
        MethodHandle mh = PUB.findVirtual(o.getClass(), method, MethodType.fromMethodDescriptorString(desc, o.getClass().getClassLoader()));
        return mh.invoke(o);
    }

    private static boolean has(Object o, String method) {
        try { return Obf.declaring(o.getClass().getName(), method) != null; } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> kids(Object o) {
        try {
            if (!has(o, "getChildrenNonCopy")) return Collections.emptyList();
            return (List<Object>) PUB.findVirtual(o.getClass(), "getChildrenNonCopy", MethodType.methodType(List.class)).invoke(o);
        } catch (Throwable t) { return Collections.emptyList(); }
    }

    private static Object find(Object o, int depth) {
        if (o == null || depth > 8) return null;
        if (has(o, "getFactionsButton") && has(o, "getEventsPanel")) return o;
        for (Object k : new ArrayList<>(kids(o))) { Object r = find(k, depth + 1); if (r != null) return r; }
        return null;
    }

    private static Object fader(Object panel) throws Throwable { return call(panel, "getFader"); }
    private static void fade(Object fader, boolean in) throws Throwable { PUB.findVirtual(fader.getClass(), in ? "forceIn" : "forceOut", MethodType.methodType(void.class)).invoke(fader); }
    private static float brightness(Object fader) throws Throwable { return (float) PUB.findVirtual(fader.getClass(), "getBrightness", MethodType.methodType(float.class)).invoke(fader); }

    /** Every frame (campaign script, also while paused). */
    static void tick() {
        CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
        NetManager n = StarMPPlugin.net();
        if (ui == null || ui.getCurrentCoreTab() != CoreUITabId.INTEL || n == null || !n.linked()) {
            if (ui != null && ui.getCurrentCoreTab() == null) injectedInto = null;
            return;
        }
        try {
            Object core = call(ui, "getCore");
            Object intel = find(core, 0);
            if (intel == null) return;
            if (intel != injectedInto) inject(intel);
            else if (selected && contentFader != null && brightness(contentFader) < 0.5f) selected = false; // another tab was picked
        } catch (Throwable t) {
            if (!failedLogged) { failedLogged = true; StarMPPlugin.flog("alliance tab: " + StarMPPlugin.trace(t)); }
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(Object intel) throws Throwable {
        injectedInto = intel;
        String cls = intel.getClass().getName();
        ButtonAPI factions = (ButtonAPI) call(intel, "getFactionsButton"), planets = (ButtonAPI) call(intel, "getPlanetsButton");
        UIComponentAPI events = (UIComponentAPI) call(intel, "getEventsPanel");
        List<String> maps = Obf.fieldsWithDesc(cls, "Ljava/util/Map;");
        if (factions == null || events == null || maps.size() != 1) { log("unexpected Intel screen (" + maps.size() + " maps)"); return; }
        Map<Object, Object> tabs = (Map<Object, Object>) MethodHandles.privateLookupIn(intel.getClass(), MethodHandles.lookup())
                .findGetter(intel.getClass(), maps.get(0), Map.class).invoke(intel);
        if (tabs == null || !tabs.containsKey(factions)) { log("tab map not found"); return; }
        UIPanelAPI screen = (UIPanelAPI) intel;
        PositionAPI sp = ((UIComponentAPI) intel).getPosition(), ep = events.getPosition(), fp = factions.getPosition();

        // the tab's page, where the Intel page is
        AllianceIntel view = new AllianceIntel();
        Page page = new Page(view);
        CustomPanelAPI c = Global.getSettings().createCustom(ep.getWidth(), ep.getHeight(), page);
        Backing bkPlugin = new Backing();
        CustomPanelAPI bk = Global.getSettings().createCustom(ep.getWidth(), ep.getHeight(), bkPlugin);
        bkPlugin.panel = bk;
        screen.addComponent(bk).inTL(ep.getX() - sp.getX(), (sp.getY() + sp.getHeight()) - (ep.getY() + ep.getHeight()));
        screen.addComponent(c).inTL(ep.getX() - sp.getX(), (sp.getY() + sp.getHeight()) - (ep.getY() + ep.getHeight()));
        page.panel = c; page.build();

        // the tab button, right of Factions
        float gap = planets == null ? 4f : fp.getX() - (planets.getPosition().getX() + planets.getPosition().getWidth());
        CustomPanelAPI strip = Global.getSettings().createCustom(fp.getWidth(), fp.getHeight(), page);
        TooltipMakerAPI tt = strip.createUIElement(fp.getWidth(), fp.getHeight(), false);
        ButtonAPI b = tt.addButton("Alliance", "starmp_alliance_tab", Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, CutStyle.TOP, fp.getWidth(), fp.getHeight(), 0f);
        strip.addUIElement(tt).inTL(0, 0);
        screen.addComponent(strip).rightOfTop(factions, Math.max(0f, gap));
        try { b.setShortcut(Keyboard.KEY_4, true); } catch (Throwable ignored) {}

        // the game switches tabs through its map: only add ours if it holds the same kinds of button and panel
        if (!factions.getClass().isInstance(b)) { screen.removeComponent(strip); screen.removeComponent(c); screen.removeComponent(bk); log("button type differs: " + b.getClass().getName()); return; }
        Object f = fader(c);
        if (f == null) { screen.removeComponent(strip); screen.removeComponent(c); screen.removeComponent(bk); log("our page has no fader"); return; }
        Class<?> want = null; for (Object v : tabs.values()) { want = commonBase(want, v.getClass()); }
        if (want != null && !want.isInstance(c)) { screen.removeComponent(strip); screen.removeComponent(c); screen.removeComponent(bk); log("page type differs: " + c.getClass().getName() + " vs " + want.getName()); return; }
        if (UiProbe.ON) { try { UiProbe.dump("factions-panel", tabs.get(factions), 0, 4); UiProbe.dump("intel-screen-kids", intel, 0, 1); } catch (Throwable t) { StarMPPlugin.flog("uiprobe: " + t); } }
        tabs.put(b, c);
        tabButton = b; content = c; contentFader = f;
        fade(f, false);
        b.unhighlight();
        if (!works) StarMPPlugin.flog("alliance tab: added to the Intel screen");
        works = true;
        if (selected) show();
    }

    private static Class<?> commonBase(Class<?> a, Class<?> b) {
        if (a == null) return b;
        for (Class<?> x = a; x != null; x = x.getSuperclass()) if (x.isAssignableFrom(b)) return x;
        return Object.class;
    }

    private static void log(String s) { if (!failedLogged) { failedLogged = true; StarMPPlugin.flog("alliance tab: not added - " + s); } }

    /** Our tab was clicked (or 4): show our page, hide the others, like the game's own tab switch. */
    @SuppressWarnings("unchecked")
    private static void show() {
        try {
            Object intel = injectedInto;
            String cls = intel.getClass().getName();
            Map<Object, Object> tabs = (Map<Object, Object>) MethodHandles.privateLookupIn(intel.getClass(), MethodHandles.lookup())
                    .findGetter(intel.getClass(), Obf.fieldsWithDesc(cls, "Ljava/util/Map;").get(0), Map.class).invoke(intel);
            for (Map.Entry<Object, Object> e : tabs.entrySet()) {
                boolean mine = e.getValue() == content;
                fade(fader(e.getValue()), mine);
                if (mine) ((ButtonAPI) e.getKey()).highlight(); else ((ButtonAPI) e.getKey()).unhighlight();
            }
            selected = true;
        } catch (Throwable t) { StarMPPlugin.flog("alliance tab: switch failed: " + t); }
    }

    /** Our page: the same view as the Alliance intel entry; redrawn when something changes. */
    private static final class Page extends BaseCustomUIPanelPlugin {
        final AllianceIntel view;
        CustomPanelAPI panel, inner;
        String lastSig = "";
        float since;
        Page(AllianceIntel v) { view = v; v.redraw = this::build; }

        void build() {
            if (panel == null) return;
            if (inner != null) panel.removeComponent(inner);
            PositionAPI p = panel.getPosition();
            inner = panel.createCustomPanel(p.getWidth(), p.getHeight(), this);
            view.createLargeDescription(inner, p.getWidth(), p.getHeight());
            panel.addComponent(inner).inTL(0, 0);
            lastSig = sig();
            brightLogs = 0;
            forceBright(inner, 0);
        }

        static String sig() {
            StringBuilder sb = new StringBuilder();
            for (Alliances.Ally a : Alliances.all()) sb.append(a.id).append(a.name).append(a.tag).append(a.desc).append(a.owner).append(a.policy).append(a.color)
                    .append(a.members).append(a.admins).append(a.invites).append(a.requests).append(a.npc).append('|');
            sb.append(Alliances.lastNote());
            NetManager n = StarMPPlugin.net(); if (n != null) sb.append(n.peerIds());
            return sb.toString();
        }

        int brightLogs;
        /** 0.9.3.7: the page was dim: its inner panel (and the text boxes in it) have faders of their own that were never forced fully in. */
        void forceBright(Object o, int depth) {
            if (o == null || depth > 6) return;
            try { Object f = call(o, "getFader"); if (f != null) fade(f, true); } catch (Throwable ignored) {}
            try { for (Object k : new ArrayList<>(kids(o))) forceBright(k, depth + 1); } catch (Throwable ignored) {}
        }

        @Override public void advance(float amount) {
            // 0.9.3.7 (debug): once per rebuild, the brightness of our page panel and its inner panel
            if (brightLogs < 1 && inner != null) {
                try {
                    Object fo = call(panel, "getFader"), fi = call(inner, "getFader");
                    if (fo != null && brightness(fo) > 0.5f) brightLogs++; else fo = null; // wait until our page is the one showing
                    if (fo != null) StarMPPlugin.flog("alliance tab: page brightness outer=" + (fo == null ? "-" : String.valueOf(brightness(fo))) + " inner=" + (fi == null ? "-" : String.valueOf(brightness(fi))));
                } catch (Throwable t) { brightLogs = 1; StarMPPlugin.flog("alliance tab: brightness check failed: " + t); }
            }
            if ((since += amount) < 0.5f) return;
            since = 0f;
            if (!view.typing() && !sig().equals(lastSig)) build();
        }

        @Override public void buttonPressed(Object id) {
            if ("starmp_alliance_tab".equals(id)) { show(); return; }
            view.tabPressed(id);
        }

        static boolean dbgNoBack, dbgInnerBack;
        void dumpFaders(Object o, int depth) {
            if (o == null || depth > 7) return;
            String b = "-";
            try { Object f = call(o, "getFader"); if (f != null) b = String.valueOf(brightness(f)); } catch (Throwable t) { b = "err"; }
            StarMPPlugin.flog("alliance dbg: fader " + depth + " " + o.getClass().getName() + " brightness=" + b);
            for (Object k : new ArrayList<>(kids(o))) dumpFaders(k, depth + 1);
        }
        @Override public void processInput(List<InputEventAPI> events) {
            if (!UiProbe.ON || events == null) return;
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyDownEvent()) continue;
                if (e.getEventValue() == Keyboard.KEY_F6) { dbgNoBack = !dbgNoBack; StarMPPlugin.flog("alliance dbg: noBack=" + dbgNoBack); e.consume(); }
                else if (e.getEventValue() == Keyboard.KEY_F8) { dumpFaders(inner, 0); dumpFaders(panel, 0); e.consume(); }
                else if (e.getEventValue() == Keyboard.KEY_F7) { dbgInnerBack = !dbgInnerBack; StarMPPlugin.flog("alliance dbg: innerBack=" + dbgInnerBack); e.consume(); }
            }
        }

        // 0.9.4.0: the dark backing is no longer drawn by this panel (it also drew over the page's own text: the page looked dim) - see Backing
    }

    /** 0.9.4.0: the page's dark backing (the campaign map would show through otherwise), a panel of its own placed UNDER the page. */
    private static final class Backing extends BaseCustomUIPanelPlugin {
        CustomPanelAPI panel;
        @Override public void renderBelow(float alphaMult) {
            if (panel == null) return;
            float vis = alphaMult;
            try { if (contentFader != null) vis *= Math.max(0f, Math.min(1f, brightness(contentFader))); } catch (Throwable ignored) {}
            if (vis <= 0.01f) return;
            PositionAPI p = panel.getPosition();
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT | org.lwjgl.opengl.GL11.GL_CURRENT_BIT | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            try {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
                org.lwjgl.opengl.GL11.glColor4f(0f, 0f, 0f, 0.98f * vis);
                org.lwjgl.opengl.GL11.glRectf(p.getX(), p.getY(), p.getX() + p.getWidth(), p.getY() + p.getHeight());
            } finally {
                org.lwjgl.opengl.GL11.glPopAttrib();
            }
        }
    }
}
