package starmp.ui;

import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Keyboard;
import starmp.NetManager;
import starmp.Prefs;
import starmp.StarMPPlugin;

/**
 * 0.9.0.0: text chat. A see-through window (no background, white text) sitting above the ability bar on the campaign
 * map and in battles. Enter: type, Enter: send, Esc: cancel. While typing: drag anywhere inside to move it, drag its
 * outline (the edges and corners) to resize. Mouse wheel over it scrolls back. Position and size are remembered (this PC).
 * Moving / resizing / scrolling work while the chat is open (after Enter), so it never blocks clicks on the map.
 * Also shows StarMP's own notices (kicks, votes, fast-forward) in grey. Chat is sent to everyone in the game.
 *
 * CHAT (39): [39][utf-8 text] - to everyone (the host passes it on).
 */
public final class Chat {
    private Chat() {}
    public static final byte CHAT = 39;
    private static final int KEEP = 300, EDGE = 7;
    private static final float MIN_W = 220, MIN_H = 70;
    private static final Color WHITE = new Color(255, 255, 255, 245), GREY = new Color(200, 200, 200, 200), SHADOW = new Color(0, 0, 0, 170),
            OUTLINE = new Color(255, 255, 255, 70), OUTLINE_HOT = new Color(255, 255, 255, 150);

    private static final class Line { final String text; final boolean system; boolean ally; Line(String t, boolean s) { text = t; system = s; } }
    private static final Color ALLY = new Color(120, 235, 140, 245); // 0.9.2.2: alliance chat
    private static final List<Line> lines = new ArrayList<>();
    private static float x = -1, y, w, h;
    private static boolean typing, hover;
    private static final StringBuilder input = new StringBuilder();
    private static int drag; // 0 none, 1 move, else bit flags 2 left 4 right 8 bottom 16 top
    private static float lastMx, lastMy;
    private static int scroll; // lines scrolled back
    private static final Gui gui = new Gui();

    public static boolean enabled() { return Prefs.getBool("chatOn", true); }
    public static void toggle() { Prefs.put("chatOn", String.valueOf(!enabled())); typing = false; }
    public static boolean typing() { return typing; }

    private static boolean visible() {
        NetManager n = StarMPPlugin.net();
        return enabled() && n != null && n.linked();
    }

    private static void ensureRect() {
        if (x >= 0) return;
        float dw = 590, dh = 150;
        try {
            x = Float.parseFloat(Prefs.get("chatX", "-1")); y = Float.parseFloat(Prefs.get("chatY", "-1"));
            w = Float.parseFloat(Prefs.get("chatW", String.valueOf(dw))); h = Float.parseFloat(Prefs.get("chatH", String.valueOf(dh)));
        } catch (Throwable t) { x = -1; }
        if (x < 0 || y < 0) { w = dw; h = dh; x = 283; y = 118; } // just above the ability bar (1-0) at the bottom
        clampRect();
    }

    private static void clampRect() {
        float W = Gui.sw(), H = Gui.sh();
        w = Math.max(MIN_W, Math.min(w, W)); h = Math.max(MIN_H, Math.min(h, H));
        x = Math.max(0, Math.min(x, W - w)); y = Math.max(0, Math.min(y, H - h));
    }

    private static void saveRect() {
        Prefs.put("chatX", String.valueOf(Math.round(x))); Prefs.put("chatY", String.valueOf(Math.round(y)));
        Prefs.put("chatW", String.valueOf(Math.round(w))); Prefs.put("chatH", String.valueOf(Math.round(h)));
    }

    // ------------------------------------------------------------------ messages
    public static synchronized void add(String text, boolean system) {
        lines.add(new Line(text, system));
        while (lines.size() > KEEP) lines.remove(0);
        if (scroll > 0) scroll++; // keep the view where the player scrolled to
    }

    public static void system(String text) { add(text, true); }

    /** 0.9.2.2: an alliance chat line (green). */
    public static synchronized void ally(String text) { add(text, false); lines.get(lines.size() - 1).ally = true; StarMPPlugin.flog("chat (alliance): " + text); }

    private static void send(String text) {
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) return;
        if (text.startsWith("/a ") || text.equals("/a")) { if (text.length() > 3) starmp.campaign.Alliances.chat(text.substring(3).trim()); return; } // 0.9.2.2
        byte[] u = text.getBytes(StandardCharsets.UTF_8);
        if (u.length > 400) u = java.util.Arrays.copyOf(u, 400);
        byte[] d = new byte[u.length + 1]; d[0] = CHAT; System.arraycopy(u, 0, d, 1, u.length);
        n.sendAll(d);
        add(n.playerName() + ": " + new String(u, StandardCharsets.UTF_8), false);
        StarMPPlugin.flog("chat: (me) " + text);
    }

    public static void onPacket(NetManager net, int peer, byte[] d) {
        String text = new String(d, 1, d.length - 1, StandardCharsets.UTF_8).replace('\n', ' ');
        add(starmp.combat.CoopState.nameOf(net, peer) + ": " + text, false);
        StarMPPlugin.flog("chat: " + starmp.combat.CoopState.nameOf(net, peer) + ": " + text);
    }

    // ------------------------------------------------------------------ input
    /** Campaign (before the game's own input) and combat (before the core controls). */
    public static void input(List<InputEventAPI> events) {
        if (!visible() || events == null) { typing = false; drag = 0; return; }
        ensureRect();
        for (InputEventAPI e : events) {
            if (e.isConsumed()) continue;
            if (e.isMouseEvent()) {
                // the window only takes the mouse while the chat is open (Enter): otherwise clicks on the map / battle
                // under it work as usual
                if (!typing && drag == 0) { hover = false; continue; }
                float mx = e.getX(), my = e.getY();
                hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
                if (drag != 0 && e.isMouseMoveEvent()) {
                    float dx = mx - lastMx, dy = my - lastMy;
                    if (drag == 1) { x += dx; y += dy; }
                    else {
                        if ((drag & 2) != 0) { float nx = Math.min(x + dx, x + w - MIN_W); w += x - nx; x = nx; }
                        if ((drag & 4) != 0) w = Math.max(MIN_W, w + dx);
                        if ((drag & 8) != 0) { float ny = Math.min(y + dy, y + h - MIN_H); h += y - ny; y = ny; }
                        if ((drag & 16) != 0) h = Math.max(MIN_H, h + dy);
                    }
                    clampRect();
                    lastMx = mx; lastMy = my; e.consume(); continue;
                }
                if (e.isLMBDownEvent() && hover) {
                    int z = 0;
                    if (mx - x < EDGE) z |= 2; if (x + w - mx < EDGE) z |= 4; if (my - y < EDGE) z |= 8; if (y + h - my < EDGE) z |= 16;
                    drag = z == 0 ? 1 : z; lastMx = mx; lastMy = my; e.consume(); continue;
                }
                if (e.isLMBUpEvent() && drag != 0) { drag = 0; saveRect(); e.consume(); continue; }
                if (e.isMouseScrollEvent() && hover) { scroll = Math.max(0, scroll + (e.getEventValue() > 0 ? 3 : -3)); e.consume(); continue; }
                continue;
            }
            if (!e.isKeyboardEvent()) continue;
            if (typing) {
                if (e.isKeyDownEvent()) {
                    int k = e.getEventValue();
                    if (k == Keyboard.KEY_RETURN || k == Keyboard.KEY_NUMPADENTER) { String t = input.toString().trim(); if (!t.isEmpty()) send(t); input.setLength(0); typing = false; scroll = 0; }
                    else if (k == Keyboard.KEY_ESCAPE) { input.setLength(0); typing = false; }
                    else if (k == Keyboard.KEY_BACK) { if (input.length() > 0) input.setLength(e.isCtrlDown() ? 0 : input.length() - 1); }
                    else if (k == Keyboard.KEY_V && e.isCtrlDown()) { String c = clipboard(); if (input.length() + c.length() < 300) input.append(c.replace('\n', ' ')); }
                    else { char c = e.getEventChar(); if (c >= 32 && c != 127 && input.length() < 300) input.append(c); }
                }
                e.consume(); // nothing else reacts to keys while typing (hotkeys, pause...)
                continue;
            }
            if (e.isKeyDownEvent() && !e.isRepeat() && (e.getEventValue() == Keyboard.KEY_RETURN || e.getEventValue() == Keyboard.KEY_NUMPADENTER)) {
                typing = true; input.setLength(0); e.consume();
            }
        }
    }

    private static String clipboard() {
        try { Object o = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); return o == null ? "" : o.toString().trim(); }
        catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------ drawing
    /** UI coordinates, above the game's UI. */
    public static synchronized void draw() {
        if (!visible()) return;
        ensureRect();
        boolean hot = hover || typing || drag != 0;
        if (hot) Gui.frame(x, y, w, h, drag != 0 ? OUTLINE_HOT : OUTLINE); // the outline only shows when you point at it
        float lh = 16f, pad = 6f, bottom = y + pad;
        gui.begin();
        if (typing) {
            String caret = (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            String shown = "> " + input + caret;
            while (shown.length() > 4 && gui.textWidth(shown, Gui.TINY) > w - 2 * pad) shown = "> …" + shown.substring(4);
            shadow(shown, x + pad, bottom + lh, WHITE);
            bottom += lh + 2;
            shadow("Enter: send   /a: alliance only   Esc: cancel   drag inside: move   drag the edge: resize", x + pad, bottom + lh, GREY);
            bottom += lh;
        }
        // wrap the newest lines into the space left, newest at the bottom
        List<String[]> rows = new ArrayList<>(); // {text, system?}
        int maxRows = (int) ((y + h - pad - bottom) / lh);
        int skip = scroll;
        for (int i = lines.size() - 1; i >= 0 && rows.size() < maxRows + skip; i--) {
            List<String> wrapped = wrap(lines.get(i).text, w - 2 * pad);
            for (int j = wrapped.size() - 1; j >= 0; j--) rows.add(new String[]{wrapped.get(j), lines.get(i).system ? "1" : lines.get(i).ally ? "a" : ""});
        }
        if (scroll > Math.max(0, rows.size() - maxRows)) scroll = Math.max(0, rows.size() - maxRows);
        float ty = bottom + lh;
        for (int r = scroll; r < rows.size() && r < scroll + maxRows; r++) {
            shadow(rows.get(r)[0], x + pad, ty, rows.get(r)[1].isEmpty() ? WHITE : "a".equals(rows.get(r)[1]) ? ALLY : GREY);
            ty += lh;
        }
        gui.end();
    }

    private static void shadow(String s, float tx, float yTop, Color c) {
        gui.text(s, Gui.TINY, tx + 1, yTop - 1, SHADOW);
        gui.text(s, Gui.TINY, tx, yTop, c);
    }

    private static List<String> wrap(String s, float width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (gui.textWidth(test, Gui.TINY) <= width || cur.length() == 0) {
                if (cur.length() == 0 && gui.textWidth(word, Gui.TINY) > width) { // one very long word: hard cut
                    String rest = word;
                    while (gui.textWidth(rest, Gui.TINY) > width && rest.length() > 1) {
                        int n = rest.length();
                        while (n > 1 && gui.textWidth(rest.substring(0, n), Gui.TINY) > width) n--;
                        out.add(rest.substring(0, n)); rest = rest.substring(n);
                    }
                    cur.setLength(0); cur.append(rest);
                } else { cur.setLength(0); cur.append(test); }
            } else { out.add(cur.toString()); cur.setLength(0); cur.append(word); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
