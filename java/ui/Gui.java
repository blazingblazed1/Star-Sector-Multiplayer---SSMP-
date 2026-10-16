package starmp.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.opengl.GL11;

/**
 * Tiny immediate-mode UI drawn with GL in UI coordinates (origin bottom-left, virtual pixels).
 * Each frame: begin(), draw widgets (which register hit boxes), end(). Clicks are matched against the
 * hit boxes of the previous frame.
 */
public final class Gui {
    public static final Color BG = new Color(4, 10, 18, 235), PANEL = new Color(10, 24, 38, 240),
            EDGE = new Color(70, 200, 255, 200), EDGE_DIM = new Color(40, 110, 150, 160),
            TEXT = new Color(170, 222, 255), TEXT_DIM = new Color(110, 150, 175), HOT = new Color(25, 70, 100, 240),
            GOOD = new Color(120, 230, 140), BAD = new Color(255, 120, 100), GOLD = new Color(255, 200, 60);

    public static final class Hit { float x, y, w, h; Runnable action; String id; }

    private final Map<String, LabelAPI> labels = new HashMap<>();
    private List<Hit> hits = new ArrayList<>(), building = new ArrayList<>();
    public float mouseX, mouseY;

    public void begin() { building = new ArrayList<>(); }
    public void end() { hits = building; endHover(); }

    /** Returns true and runs the action if a hit box from the last frame contains the point. */
    public boolean click(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) { if (h.action != null) h.action.run(); return true; }
        }
        return false;
    }

    public boolean over(float x, float y, float w, float h) { return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h; }

    public static float sw() { return Global.getSettings().getScreenWidth(); }
    public static float sh() { return Global.getSettings().getScreenHeight(); }

    // ---- primitives
    public static void rect(float x, float y, float w, float h, Color c) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4ub((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) c.getAlpha());
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    public static void frame(float x, float y, float w, float h, Color c) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(1.5f);
        GL11.glColor4ub((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) c.getAlpha());
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x + 0.5f, y + 0.5f); GL11.glVertex2f(x + w - 0.5f, y + 0.5f);
        GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f); GL11.glVertex2f(x + 0.5f, y + h - 0.5f);
        GL11.glEnd();
    }

    /** Starsector-style panel: dark fill, cut corner accent, thin cyan edge. */
    public static void panel(float x, float y, float w, float h) {
        rect(x, y, w, h, PANEL);
        frame(x, y, w, h, EDGE_DIM);
        rect(x, y + h - 3, w, 3, EDGE);
    }

    private LabelAPI label(String text, String font) {
        String key = font + "\u0000" + text;
        LabelAPI l = labels.get(key);
        if (l == null) {
            if (labels.size() > 400) labels.clear();
            l = Global.getSettings().createLabel(text, font);
            labels.put(key, l);
        }
        return l;
    }

    public static final String BIG = Fonts.ORBITRON_24AABOLD, MED = Fonts.ORBITRON_20AA, SMALL = Fonts.INSIGNIA_LARGE, TINY = Fonts.DEFAULT_SMALL;

    public float textWidth(String text, String font) { return label(text, font).computeTextWidth(text); }
    public float textHeight(String text, String font) { return label(text, font).computeTextHeight(text); }

    /** Draws text with its top-left at (x, yTop). */
    public void text(String text, String font, float x, float yTop, Color c) {
        if (text == null || text.isEmpty()) return;
        LabelAPI l = label(text, font);
        l.setColor(c);
        float w = l.computeTextWidth(text) + 4, h = l.computeTextHeight(text);
        l.getPosition().setSize(w, h);
        l.getPosition().setLocation(x, yTop - h);
        l.render(1f);
    }

    public void textCentered(String text, String font, float cx, float yTop, Color c) {
        text(text, font, cx - textWidth(text, font) / 2f, yTop, c);
    }

    // ---- widgets
    public void button(String id, String text, float x, float y, float w, float h, boolean enabled, Runnable action) {
        boolean hot = enabled && over(x, y, w, h);
        rect(x, y, w, h, hot ? HOT : PANEL);
        frame(x, y, w, h, enabled ? (hot ? EDGE : EDGE_DIM) : new Color(60, 70, 80, 150));
        if (hot) rect(x, y, 4, h, EDGE);
        String font = h >= 44 ? BIG : MED;
        float th = textHeight(text, font);
        textCentered(text, font, x + w / 2f, y + h / 2f + th / 2f, enabled ? (hot ? Color.WHITE : TEXT) : TEXT_DIM);
        if (enabled) { Hit hb = new Hit(); hb.x = x; hb.y = y; hb.w = w; hb.h = h; hb.action = action; hb.id = id; building.add(hb); }
    }

    /** Single-line text field. Returns nothing; editing is handled by the owner through focus id. */
    public void field(String id, String value, float x, float y, float w, float h, boolean focused, Runnable onFocus) {
        rect(x, y, w, h, new Color(2, 8, 14, 245));
        frame(x, y, w, h, focused ? EDGE : EDGE_DIM);
        String shown = value == null ? "" : value;
        String font = MED;
        // show the tail if too long
        while (shown.length() > 3 && textWidth(shown, font) > w - 16) shown = shown.substring(1);
        if (shown != value && value != null && !value.isEmpty() && shown.length() < value.length()) shown = "…" + shown.substring(1);
        float th = textHeight("Ag", font);
        String caret = focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
        text(shown + caret, font, x + 8, y + h / 2f + th / 2f, Color.WHITE);
        Hit hb = new Hit(); hb.x = x; hb.y = y; hb.w = w; hb.h = h; hb.action = onFocus; hb.id = id; building.add(hb);
    }

    // ---- Starsector-style widgets (copied from the vanilla main menu: dark framed panel, teal cut-corner buttons,
    //      Orbitron text in the game's button colours; sampled from the 0.98a title screen)
    public static final Color V_FILL = new Color(21, 65, 77, 240), V_FILL_HOT = new Color(36, 104, 122, 250), V_FILL_OFF = new Color(22, 36, 42, 220),
            V_EDGE_HOT = new Color(70, 222, 255, 230), V_TEXT = new Color(170, 222, 255), V_TEXT_OFF = new Color(90, 120, 135),
            V_PANEL = new Color(9, 11, 15, 235), V_FRAME = new Color(150, 175, 185, 230), V_FRAME_IN = new Color(40, 110, 130, 200),
            V_BOX = new Color(4, 14, 20, 240);
    // kept for the lobby texts
    public static final Color B_TEXT = V_TEXT, B_DIM = new Color(120, 160, 180), B_BOX = V_BOX;

    /** Vanilla-style panel: dark fill, light outer frame, thin cyan inner frame. */
    public static void bpanel(float x, float y, float w, float h) {
        rect(x, y, w, h, V_PANEL);
        frame(x, y, w, h, V_FRAME);
        frame(x + 3, y + 3, w - 6, h - 6, V_FRAME_IN);
    }

    private static void cutShape(float x, float y, float w, float h, float c, Color col) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4ub((byte) col.getRed(), (byte) col.getGreen(), (byte) col.getBlue(), (byte) col.getAlpha());
        GL11.glBegin(GL11.GL_POLYGON); // top-left and bottom-right corners cut, like the vanilla menu buttons
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w - c, y); GL11.glVertex2f(x + w, y + c);
        GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + c, y + h); GL11.glVertex2f(x, y + h - c);
        GL11.glEnd();
    }

    private static void cutOutline(float x, float y, float w, float h, float c, Color col) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(1f);
        GL11.glColor4ub((byte) col.getRed(), (byte) col.getGreen(), (byte) col.getBlue(), (byte) col.getAlpha());
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x + 0.5f, y + 0.5f); GL11.glVertex2f(x + w - c, y + 0.5f); GL11.glVertex2f(x + w - 0.5f, y + c);
        GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f); GL11.glVertex2f(x + c, y + h - 0.5f); GL11.glVertex2f(x + 0.5f, y + h - c);
        GL11.glEnd();
    }

    private String hoverId;

    public void bbutton(String id, String text, float x, float y, float w, float h, boolean enabled, Runnable action) {
        vbutton(id, text, x, y, w, h, enabled, action, false);
    }

    /** Vanilla-style button. rightAlign = main-menu look (text against the right edge). */
    public void vbutton(String id, String text, float x, float y, float w, float h, boolean enabled, Runnable action, boolean rightAlign) {
        boolean hot = enabled && over(x, y, w, h);
        float c = Math.min(8f, h * 0.25f);
        cutShape(x, y, w, h, c, !enabled ? V_FILL_OFF : hot ? V_FILL_HOT : V_FILL);
        if (hot) cutOutline(x, y, w, h, c, V_EDGE_HOT);
        if (hot && !id.equals(hoverId)) { hoverId = id; sound("ui_button_mouseover"); }
        String font = h >= 28 ? (rightAlign ? com.fs.starfarer.api.ui.Fonts.ORBITRON_24AABOLD : com.fs.starfarer.api.ui.Fonts.ORBITRON_20AA) : TINY;
        if (textWidth(text, font) > w - 24) font = TINY; // long labels on narrow buttons
        float th = textHeight(text, font);
        Color tc = enabled ? (hot ? Color.WHITE : V_TEXT) : V_TEXT_OFF;
        if (rightAlign) text(text, font, x + w - 16 - textWidth(text, font), y + h / 2f + th / 2f, tc);
        else textCentered(text, font, x + w / 2f, y + h / 2f + th / 2f, tc);
        if (enabled) {
            Hit hb = new Hit(); hb.x = x; hb.y = y; hb.w = w; hb.h = h; hb.id = id;
            hb.action = () -> { sound("ui_button_pressed"); action.run(); };
            building.add(hb);
        }
    }

    public void endHover() { boolean any = false; for (Hit h : building) if (h.id != null && h.id.equals(hoverId) && over(h.x, h.y, h.w, h.h)) any = true; if (!any) hoverId = null; }

    static void sound(String id) {
        try { Global.getSoundPlayer().playUISound(id, 1f, 1f); } catch (Throwable ignored) {}
    }

    /** Single-line field: dark box with a thin cyan edge; value shown with a caret when focused. onClick may be null (read-only). */
    public void bfield(String id, String value, String placeholder, float x, float y, float w, float h, boolean focused, Color fill, Runnable onClick) {
        rect(x, y, w, h, fill == null ? V_BOX : fill);
        frame(x, y, w, h, focused ? V_EDGE_HOT : V_FRAME_IN);
        String shown = value == null ? "" : value;
        boolean empty = shown.isEmpty();
        if (empty) shown = placeholder == null ? "" : placeholder;
        String font = TINY;
        while (shown.length() > 3 && textWidth(shown + "...", font) > w - 16) shown = shown.substring(0, shown.length() - 1);
        if (!empty && value != null && shown.length() < value.length()) shown = shown + "...";
        float th = textHeight("Ag", font);
        String caret = focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
        text(shown + caret, font, x + 8, y + h / 2f + th / 2f, empty ? B_DIM : V_TEXT);
        if (onClick != null) { Hit hb = new Hit(); hb.x = x; hb.y = y; hb.w = w; hb.h = h; hb.action = onClick; hb.id = id; building.add(hb); }
    }

    /** A click-catcher that does nothing (used for the full-screen backdrop so clicks don't fall through). */
    public void block(float x, float y, float w, float h) {
        Hit hb = new Hit(); hb.x = x; hb.y = y; hb.w = w; hb.h = h; hb.action = null; hb.id = "block"; building.add(0, hb);
    }
}
