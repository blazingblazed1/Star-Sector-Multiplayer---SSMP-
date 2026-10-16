package starmp.combat;

import com.fs.starfarer.api.Global;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The player's real combat key bindings, read through the game API (the prefs store is blocked for mods).
 * Codes are LWJGL key codes; 2000+n means mouse button n (the game's own convention).
 * Defaults are the 0.98a-RC8 defaults, used when a binding can't be read.
 */
public final class Bindings {
    /** The pause key (GENERAL_PAUSE binding). */
    public static int pauseKey() { try { return load().pause; } catch (Throwable t) { return org.lwjgl.input.Keyboard.KEY_SPACE; } }

    int accel, back, decel, left, right, strafeL, strafeR, strafeKey, fire, shield, vent, system, pause;
    final int[] groups = new int[7];
    private final StringBuilder desc = new StringBuilder();

    static Bindings load() {
        Bindings b = new Bindings();
        b.accel = b.read("SHIP_ACCELERATE", Keyboard.KEY_W);
        b.back = b.read("SHIP_ACCELERATE_BACKWARDS", Keyboard.KEY_S);
        b.decel = b.read("SHIP_DECELERATE", Keyboard.KEY_C);
        b.left = b.read("SHIP_TURN_LEFT", Keyboard.KEY_A);
        b.right = b.read("SHIP_TURN_RIGHT", Keyboard.KEY_D);
        b.strafeL = b.read("SHIP_STRAFE_LEFT_NOTURN", Keyboard.KEY_Q);
        b.strafeR = b.read("SHIP_STRAFE_RIGHT_NOTURN", Keyboard.KEY_E);
        b.strafeKey = b.read("SHIP_STRAFE_KEY", Keyboard.KEY_LSHIFT);
        b.fire = b.read("SHIP_FIRE", 2000);
        b.shield = b.read("SHIP_SHIELDS", 2001);
        b.vent = b.read("SHIP_VENT_FLUX", Keyboard.KEY_V);
        b.system = b.read("SHIP_USE_SYSTEM", Keyboard.KEY_F);
        b.pause = b.read("GENERAL_PAUSE", Keyboard.KEY_SPACE);
        for (int g = 0; g < 7; g++) b.groups[g] = b.read("SHIP_SELECT_GROUP_" + (g + 1), Keyboard.KEY_1 + g);
        return b;
    }

    private int read(String control, int def) {
        String raw = null;
        int code = def;
        try { raw = Global.getSettings().getControlStringForEnumName(control); } catch (Throwable ignored) {}
        if (raw != null) {
            String r = raw.trim().toUpperCase();
            if (r.equals("LEFT CLICK")) code = 2000;
            else if (r.equals("RIGHT CLICK")) code = 2001;
            else if (r.equals("MIDDLE CLICK")) code = 2002;
            else if (r.equals("LMB") || r.contains("LEFT MOUSE") || r.equals("MOUSE 1") || r.equals("BUTTON0")) code = 2000;
            else if (r.equals("RMB") || r.contains("RIGHT MOUSE") || r.equals("MOUSE 2") || r.equals("BUTTON1")) code = 2001;
            else if (r.equals("MMB") || r.contains("MIDDLE MOUSE")) code = 2002;
            // the game names mouse bindings just "Left" / "Right" / "Middle" - for controls that are mouse buttons by
            // default these mean the mouse, not the arrow keys (reading them as arrow keys broke shields)
            else if (def >= 2000 && r.equals("LEFT")) code = 2000;
            else if (def >= 2000 && r.equals("RIGHT")) code = 2001;
            else if (def >= 2000 && r.equals("MIDDLE")) code = 2002;
            else {
                int k = Keyboard.getKeyIndex(r);
                if (k == Keyboard.KEY_NONE) k = Keyboard.getKeyIndex(r.replace(' ', '_'));
                if (k != Keyboard.KEY_NONE) code = k;
            }
        }
        desc.append(control.replace("SHIP_", "")).append('=').append(raw).append("->").append(code).append(' ');
        return code;
    }

    boolean down(int code) {
        if (code <= 0) return false;
        if (code >= 2000) return Mouse.isCreated() && Mouse.isButtonDown(code - 2000);
        return Keyboard.isCreated() && Keyboard.isKeyDown(code);
    }

    String describe() { return desc.toString(); }
}
