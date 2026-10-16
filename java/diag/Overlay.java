package starmp.diag;

import java.awt.Color;
import starmp.ui.Gui;

/** 0.1.5.3: the F10 performance overlay (same style as the Wanderburg mod's). Drawn by the campaign HUD, the combat
 * plugin and the title menu, in UI coordinates. */
public final class Overlay {
    private Overlay() {}
    private static final Gui gui = new Gui();
    private static final Color BG = new Color(12, 22, 32, 225), EDGE = new Color(90, 150, 190, 200), TEXT = new Color(235, 242, 248), DIM = new Color(150, 175, 195);

    public static void draw() {
        if (!Diag.overlay) return;
        long t0 = Perf.begin();
        try {
            String[] lines = Perf.overlayLines;
            if (lines.length == 0) lines = new String[]{"StarMP debug overlay - collecting (1 s)..."};
            float lh = 15f, pad = 8f;
            float w = 0;
            for (String l : lines) w = Math.max(w, gui.textWidth(l, Gui.TINY));
            w += pad * 2;
            float h = lines.length * lh + pad * 2;
            // right side, below the combat deployment / command-points panel
            float x = Gui.sw() - w - 12f, y = Gui.sh() - h - 200f;
            if (y < 40f) y = 40f;
            gui.begin();
            org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
            Gui.rect(x, y, w, h, BG);
            Gui.frame(x, y, w, h, EDGE);
            org.lwjgl.opengl.GL11.glPopAttrib();
            float ty = y + h - pad;
            for (int i = 0; i < lines.length; i++) {
                gui.text(lines[i], Gui.TINY, x + pad, ty, i == 0 ? DIM : TEXT);
                ty -= lh;
            }
            gui.end();
        } catch (Throwable ignored) {
        } finally { Perf.end("Overlay", t0); }
    }
}
