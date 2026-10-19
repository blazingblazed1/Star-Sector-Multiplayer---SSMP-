package starmp.ui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import starmp.StarMPPlugin;

/**
 * Bridge to Starsector's own title screen (0.98a-RC8 internals; no mod API exists for it).
 * Uses java.lang.invoke only (the mod sandbox blocks java.lang.reflect). If anything here doesn't match a future
 * game version, every call fails soft and the vanilla menu simply stays as it is.
 */
public final class Vanilla {
    private Vanilla() {}

    private static final String STATE = "com.fs.starfarer.title.TitleScreenState";
    // Obfuscated names differ between the Windows and Linux builds, so they are looked up at runtime from the game's
    // class files (see Obf), starting from names the game leaves readable.
    private static String ACTION_ENUM, TITLE_WIDGET, MAIN_MENU, COMPONENT, PANEL, MISSION_WIDGET, TITLE_DIALOG_TYPE,
            CS = "com.fs.starfarer.combat.CombatState", CS_FDD, CS_DIALOG_TYPE, CE = "com.fs.starfarer.combat.CombatEngine",
            F_OVER, F_DONOTEND, F_FADER;
    private static boolean broken, resolved;
    /** Why the vanilla menu could not be hidden (shown on screen), or null. */
    public static volatile String problem;

    /** Looks up every internal name once. Logs what it found (for bug reports). */
    public static synchronized void resolve(Object near) {
        if (resolved || near == null) return;
        resolved = true;
        try {
            Obf.setLoader(near.getClass().getClassLoader());
            ACTION_ENUM = Obf.firstParamClass(Obf.methodDesc(STATE, "menuItemSelected"));
            TITLE_WIDGET = Obf.fieldClass(STATE, "titleWidget");
            MAIN_MENU = Obf.returnClass(Obf.methodDesc(TITLE_WIDGET, "getMainMenu"));
            PANEL = Obf.declaring(TITLE_WIDGET, "getChildrenNonCopy");
            for (String d : Obf.methodDescs(PANEL, "remove")) if (d.startsWith("(L") && d.endsWith(";)V")) COMPONENT = Obf.firstParamClass(d);
            MISSION_WIDGET = Obf.fieldClass(STATE, "missionWidget");
            TITLE_DIALOG_TYPE = Obf.fieldClass(STATE, "dialogType");
            CS_FDD = Obf.fieldClass(CS, "fdd");
            CS_DIALOG_TYPE = Obf.fieldClass(CS, "dialogType");
            for (Obf.Ref r : Obf.fieldRefs(CE, "setDoNotEndCombat", "(Z)V")) if (r.op == 0xB5 && "Z".equals(r.desc)) F_DONOTEND = r.name;
            for (Obf.Ref r : Obf.fieldRefs(CE, "endCombat", "(FLcom/fs/starfarer/api/mission/FleetSide;)V")) {
                if (r.op == 0xB5 && "Z".equals(r.desc) && F_DONOTEND != null && !r.name.equals(F_DONOTEND) && F_OVER == null) F_OVER = r.name;
                if (r.op == 0xB4 && "Lcom/fs/graphics/util/Fader;".equals(r.desc) && F_FADER == null) F_FADER = r.name;
            }
            StarMPPlugin.flog("vanilla internals: action=" + ACTION_ENUM + " titleWidget=" + TITLE_WIDGET + " mainMenu=" + MAIN_MENU
                    + " panel=" + PANEL + " component=" + COMPONENT + " missionWidget=" + MISSION_WIDGET + " fdd=" + CS_FDD
                    + " engineFlags=" + (F_OVER != null && F_DONOTEND != null && F_FADER != null ? "found" : "MISSING"));
        } catch (Throwable t) { StarMPPlugin.flog("vanilla internals lookup failed: " + t); }
    }

    private static Object fold(Object k, Class<?> comp) {
        try { MethodHandles.publicLookup().findVirtual(k.getClass(), "forceFoldIn", MethodType.methodType(void.class));
              return MethodHandles.publicLookup().findVirtual(k.getClass(), "getCurr", MethodType.methodType(comp)); }
        catch (Throwable t) { return null; }
    }

    private static Object titleState() {
        Object s = com.fs.state.AppDriver.getInstance().getCurrentState();
        if (s != null) resolve(s);
        return s != null && s.getClass().getName().equals(STATE) ? s : null;
    }

    private static Class<?> cls(Object near, String name) throws ClassNotFoundException {
        resolve(near);
        if (name == null) throw new ClassNotFoundException("internal name not found for this game build");
        return Class.forName(name, false, near.getClass().getClassLoader());
    }

    /** 0.1.6.5 (bot test mode): load one specific save folder from the title screen, the way the vanilla Load Game
     *  dialog does (reset the engine, CampaignGameManager load by path, switch to the campaign state). Unlike Continue
     *  it doesn't use the "continue" path that every Starsector on the PC shares. Call from render, not advance.
     *  Returns null when loading started, else why not. */
    public static String loadSave(String saveDir) {
        try {
            Object st = titleState();
            if (st == null) return "not on the title screen";
            ClassLoader cl = st.getClass().getClassLoader();
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup());
            Object session = priv.findGetter(st.getClass(), "session", java.util.Map.class).invoke(st);
            Object cs = ((java.util.Map<?, ?>) session).get("campaign state in session");
            if (cs == null) return "no campaign state in the title session";
            Obf.setLoader(cl);
            Obf.Ref load = null;
            for (Obf.Ref r : Obf.staticCalls(STATE, "dialogDismissed")) // the Load Game dialog's OK runs this
                if (r.owner.endsWith(".CampaignGameManager") && r.desc.startsWith("(Ljava/lang/String;L") && r.desc.endsWith(")Ljava/lang/String;")
                        && MethodType.fromMethodDescriptorString(r.desc, cl).parameterCount() == 3) load = r;
            if (load == null) return "load call not found in this game build";
            Class<?> mgr = Class.forName(load.owner, false, cl);
            MethodHandle mh = MethodHandles.publicLookup().findStatic(mgr, load.name, MethodType.fromMethodDescriptorString(load.desc, cl));
            Class<?> engine = Class.forName("com.fs.starfarer.campaign.CampaignEngine", false, cl);
            MethodHandles.publicLookup().findStatic(engine, "resetInstance", MethodType.methodType(void.class)).invoke();
            StarMPPlugin.flog("vanilla: loading save " + saveDir);
            Object err = mh.invoke(saveDir, cs, cs);
            if (err != null) return "the game refused the save: " + err;
            priv.findVirtual(st.getClass(), "goToState", MethodType.methodType(void.class, String.class)).invoke(st, "Campaign State");
            return null;
        } catch (Throwable t) {
            StarMPPlugin.flog("vanilla: load save failed: " + StarMPPlugin.trace(t));
            return String.valueOf(t);
        }
    }

    /** Runs a vanilla main-menu action by its enum name: CONTINUE, NEW_GAME, LOAD_GAME, MISSIONS, CODEX, SETTINGS,
     *  CREDITS, QUIT, COMBAT_TUTORIAL_1, COMBAT_TUTORIAL_2, COMMAND_TUTORIAL. Returns false if it couldn't. */
    public static boolean action(String name) {
        try {
            Object st = titleState();
            if (st == null) return false;
            Class<?> e = cls(st, ACTION_ENUM);
            Object constant = null;
            for (Object c : e.getEnumConstants()) if (((Enum<?>) c).name().equals(name)) constant = c;
            if (constant == null) { StarMPPlugin.flog("vanilla menu: no action " + name); return false; }
            MethodHandle mh = MethodHandles.publicLookup().findVirtual(st.getClass(), "menuItemSelected", MethodType.methodType(void.class, e));
            mh.invoke(st, constant);
            return true;
        } catch (Throwable t) {
            StarMPPlugin.flog("vanilla menu: action " + name + " failed: " + t);
            return false;
        }
    }

    private static Object menuFrame, frameState;
    private static final java.util.List<Object> frames = new java.util.ArrayList<>();

    /** Folds away the vanilla button column (it lives inside a fold frame of the title widget) and removes the tip
     *  box, so the StarMP menu is the main menu. Call keepHidden() regularly: vanilla unfolds it again after some screens. */
    public static boolean replaceMainMenu() {
        if (broken) return false;
        try {
            Object st = titleState();
            if (st == null) return false;
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup());
            Class<?> tw = cls(st, TITLE_WIDGET), mm = cls(st, MAIN_MENU), comp = cls(st, COMPONENT), panel = cls(st, PANEL);
            Object title = priv.findGetter(st.getClass(), "titleWidget", tw).invoke(st);
            if (title == null) return false;
            Object menu = MethodHandles.publicLookup().findVirtual(tw, "getMainMenu", MethodType.methodType(mm)).invoke(title);
            java.util.List<?> kids = new java.util.ArrayList<Object>((java.util.List<?>) MethodHandles.publicLookup()
                    .findVirtual(panel, "getChildrenNonCopy", MethodType.methodType(java.util.List.class)).invoke(title));
            MethodHandle remove = MethodHandles.publicLookup().findVirtual(panel, "remove", MethodType.methodType(void.class, comp));
            menuFrame = null; frames.clear();
            for (Object k : kids) {
                MethodHandle curr = (MethodHandle) fold(k, comp);
                if (curr != null) { frames.add(k); if (curr.invoke(k) == menu) menuFrame = k; } // menu column + tips box
                else if (k instanceof com.fs.starfarer.api.ui.LabelAPI && comp.isInstance(k)) remove.invoke(title, k); // stray label
            }
            if (menuFrame == null) { StarMPPlugin.flog("vanilla menu: menu frame not found; keeping the vanilla menu"); broken = true; problem = "menu frame not found"; return false; }
            frameState = st;
            for (Object fr : frames) MethodHandles.publicLookup().findVirtual(fr.getClass(), "forceFoldIn", MethodType.methodType(void.class)).invoke(fr);
            StarMPPlugin.flog("vanilla menu: replaced by the StarMP menu (" + frames.size() + " vanilla panels folded)");
            return true;
        } catch (Throwable t) {
            broken = true; problem = String.valueOf(t);
            StarMPPlugin.flog("vanilla menu: could not replace (" + t + "); keeping the vanilla menu");
            return false;
        }
    }

    /** False once the title screen was rebuilt (e.g. after a failed load or quitting to the menu): replace again. */
    public static boolean stillReplaced() {
        if (broken) return true;
        Object st = titleState();
        return st == null || (menuFrame != null && frameState == st);
    }

    /** Folds the vanilla menu back in if the game unfolded it (e.g. returning from Missions). */
    public static void keepHidden() {
        if (broken || menuFrame == null) return;
        try {
            if (frameState != titleState()) { menuFrame = null; return; }
            Object st = frameState;
            Class<?> fader = cls(st, "com.fs.graphics.util.Fader");
            MethodHandle bright = MethodHandles.publicLookup().findVirtual(fader, "getBrightness", MethodType.methodType(float.class));
            for (Object fr : frames) {
                MethodHandle fan = MethodHandles.publicLookup().findVirtual(fr.getClass(), "getFanOut", MethodType.methodType(fader));
                if ((float) bright.invoke(fan.invoke(fr)) > 0.01f) MethodHandles.publicLookup().findVirtual(fr.getClass(), "forceFoldIn", MethodType.methodType(void.class)).invoke(fr);
            }
        } catch (Throwable t) { /* ignore */ }
    }


    /** Debug: logs the UI tree under the title screen's screen panel (class names, depth). */
    public static void dumpTree() {
        try {
            Object st = titleState();
            if (st == null) return;
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup());
            Class<?> panel = cls(st, PANEL);
            Object root = priv.findGetter(st.getClass(), "screenPanel", panel).invoke(st);
            Object title = priv.findGetter(st.getClass(), "titleWidget", cls(st, TITLE_WIDGET)).invoke(st);
            Object menu = MethodHandles.publicLookup().findVirtual(cls(st, TITLE_WIDGET), "getMainMenu", MethodType.methodType(cls(st, MAIN_MENU))).invoke(title);
            StringBuilder sb = new StringBuilder("vanilla UI tree:\n");
            dump(root, panel, 0, sb, title, menu);
            StarMPPlugin.flog(sb.toString());
        } catch (Throwable t) { StarMPPlugin.flog("dumpTree failed: " + t); }
    }

    private static void dump(Object node, Class<?> panel, int depth, StringBuilder sb, Object title, Object menu) throws Throwable {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(node.getClass().getName()).append(node == title ? "  <-- titleWidget" : "").append(node == menu ? "  <-- mainMenu" : "").append('\n');
        if (depth > 6 || !panel.isInstance(node)) return;
        java.util.List<?> kids = (java.util.List<?>) MethodHandles.publicLookup().findVirtual(panel, "getChildrenNonCopy", MethodType.methodType(java.util.List.class)).invoke(node);
        for (Object k : kids) dump(k, panel, depth + 1, sb, title, menu);
    }

    /** True while the vanilla Missions screen is visible. */
    public static boolean missionsShowing() {
        if (broken) return false;
        try {
            Object st = titleState();
            if (st == null) return false;
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup());
            Class<?> mw = cls(st, MISSION_WIDGET), comp = cls(st, COMPONENT), fader = cls(st, "com.fs.graphics.util.Fader");
            Object w = priv.findGetter(st.getClass(), "missionWidget", mw).invoke(st);
            if (w == null) return false;
            Object f = MethodHandles.publicLookup().findVirtual(comp, "getFader", MethodType.methodType(fader)).invoke(w);
            float b = (float) MethodHandles.publicLookup().findVirtual(fader, "getBrightness", MethodType.methodType(float.class)).invoke(f);
            return b > 0.05f;
        } catch (Throwable t) { return false; }
    }

    /** True while a vanilla dialog (new game, load game, settings, codex, credits, quit...) is open on the title screen. */
    public static boolean dialogOpen() {
        if (broken) return false;
        try {
            Object st = titleState();
            if (st == null) return false;
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(st.getClass(), MethodHandles.lookup());
            Object type = priv.findGetter(st.getClass(), "dialogType", cls(st, TITLE_DIALOG_TYPE)).invoke(st);
            return type != null;
        } catch (Throwable t) { return false; }
    }

    /** Tells Starsector not to open its deployment screen when the next battle starts (the joiner's stand-in battle
     *  has nothing to deploy). Returns false if the internals didn't match. */
    public static boolean skipNextDeploymentDialog(boolean skip) {
        try {
            Object anyState = com.fs.state.AppDriver.getInstance().getCurrentState();
            Class<?> cs = cls(anyState, "com.fs.starfarer.combat.CombatState");
            String id = (String) MethodHandles.publicLookup().findStaticGetter(cs, "STATE_ID", String.class).invoke();
            Object state = com.fs.state.AppDriver.getInstance().getState(id);
            if (state == null) return false;
            MethodHandles.publicLookup().findVirtual(cs, "setShowDeploymentDialogOnStart", MethodType.methodType(void.class, boolean.class)).invoke(state, !skip);
            return true;
        } catch (Throwable t) {
            StarMPPlugin.flog("vanilla: could not change the deployment screen setting: " + t);
            return false;
        }
    }

    /** Closes Starsector's deployment dialog if it is open in the current battle (joiner side only). */
    public static boolean closeDeploymentDialog(int option) {
        try {
            Object anyState = com.fs.state.AppDriver.getInstance().getCurrentState();
            Class<?> cs = cls(anyState, "com.fs.starfarer.combat.CombatState");
            String id = (String) MethodHandles.publicLookup().findStaticGetter(cs, "STATE_ID", String.class).invoke();
            Object state = com.fs.state.AppDriver.getInstance().getState(id);
            if (state == null) return false;
            Class<?> dlg = cls(anyState, CS_FDD);
            Object fdd = MethodHandles.privateLookupIn(cs, MethodHandles.lookup()).findGetter(cs, "fdd", dlg).invoke(state);
            if (fdd == null) return false;
            MethodHandles.publicLookup().findVirtual(dlg, "dismiss", MethodType.methodType(void.class, int.class)).invoke(fdd, option);
            return true;
        } catch (Throwable t) {
            StarMPPlugin.flog("vanilla: could not close the deployment dialog: " + t);
            return false;
        }
    }

    /** Marks one of vanilla's one-time campaign help popups (CampaignHelpManager key, e.g. "chmDeployCostsCR") as
     *  "don't show again" - the same as ticking the box in the popup. */
    public static boolean suppressHelp(String key) {
        try {
            Object sector = com.fs.starfarer.api.Global.getSector();
            Class<?> hm = cls(sector, "com.fs.starfarer.campaign.CampaignHelpManager");
            Object help = MethodHandles.publicLookup().findVirtual(sector.getClass(), "getCampaignHelp", MethodType.methodType(hm)).invoke(sector);
            if (help == null) return false;
            MethodHandles.publicLookup().findVirtual(hm, "setDoNotShow", MethodType.methodType(void.class, String.class)).invoke(help, key);
            return true;
        } catch (Throwable t) {
            StarMPPlugin.flog("vanilla: could not turn off help popup " + key + ": " + t);
            return false;
        }
    }

    private static java.lang.invoke.MethodHandle strafeToggled;
    private static boolean strafeLookupFailed;

    /** Combat: vanilla's "strafe toggled on" state (CombatState.strafeToggledOn), false if unknown. */
    public static boolean strafeToggledOn() {
        if (strafeLookupFailed) return false;
        try {
            Object anyState = com.fs.state.AppDriver.getInstance().getCurrentState();
            Class<?> cs = cls(anyState, "com.fs.starfarer.combat.CombatState");
            if (!cs.isInstance(anyState)) return false;
            if (strafeToggled == null) strafeToggled = MethodHandles.privateLookupIn(cs, MethodHandles.lookup()).findGetter(cs, "strafeToggledOn", boolean.class);
            return (boolean) strafeToggled.invoke(anyState);
        } catch (Throwable t) { strafeLookupFailed = true; starmp.StarMPPlugin.flog("strafe toggle state unavailable: " + t); return false; }
    }

    /** Debug: the combat screen's open dialog as "ordinal:name", or "none" / "?" . */
    public static String combatDialogType() {
        try {
            Object anyState = com.fs.state.AppDriver.getInstance().getCurrentState();
            Class<?> cs = cls(anyState, "com.fs.starfarer.combat.CombatState");
            if (!cs.isInstance(anyState)) return "not in combat";
            Class<?> dt = cls(anyState, CS_DIALOG_TYPE);
            Object t = MethodHandles.privateLookupIn(cs, MethodHandles.lookup()).findGetter(cs, "dialogType", dt).invoke(anyState);
            return t == null ? "none" : ((Enum<?>) t).ordinal() + ":" + ((Enum<?>) t).name();
        } catch (Throwable t) { return "? " + t; }
    }

    /** Debug: raw CombatEngine flags {combatOver, doNotEndCombat} (0.98a-RC8 field names), or null. */
    public static boolean[] engineEndFlags() {
        try {
            Object e = com.fs.starfarer.api.Global.getCombatEngine();
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(e.getClass(), MethodHandles.lookup());
            boolean over = (boolean) l.findGetter(e.getClass(), F_OVER, boolean.class).invoke(e);
            boolean dne = (boolean) l.findGetter(e.getClass(), F_DONOTEND, boolean.class).invoke(e);
            return new boolean[]{over, dne};
        } catch (Throwable t) { return null; }
    }

    /** Undoes a "combat over" the engine decided by itself (0.98a-RC8 field names): clears the flag and its fade-out.
     *  Used on the joiner's stand-in battle, whose own sides are empty at the start. */
    public static boolean undoCombatOver() {
        try {
            Object e = com.fs.starfarer.api.Global.getCombatEngine();
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(e.getClass(), MethodHandles.lookup());
            l.findSetter(e.getClass(), F_OVER, boolean.class).invoke(e, false);
            Class<?> fc = cls(e, "com.fs.graphics.util.Fader");
            Object f = l.findGetter(e.getClass(), F_FADER, fc).invoke(e);
            if (f != null) MethodHandles.publicLookup().findVirtual(fc, "forceOut", MethodType.methodType(void.class)).invoke(f);
            return true;
        } catch (Throwable t) { StarMPPlugin.flog("vanilla: could not undo combat-over: " + t); return false; }
    }
}
