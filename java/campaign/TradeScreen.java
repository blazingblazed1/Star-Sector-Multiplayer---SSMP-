package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import starmp.StarMPPlugin;
import starmp.combat.Ships;
import starmp.combat.Wire;
import starmp.ui.Gui;

/**
 * Player-to-player trade screen, shown in a real interaction dialog (so the game pauses like vanilla trading).
 * Left: my cargo + my ships (vanilla-sized 100 px cells). Right: my offer, credits, [X | status | V], view selector,
 * their credits, their offer. The selector cycles Both / Items only / Ships only; every view fills the same frame.
 * Click my cargo: offer the whole stack (Shift+click: 10). Click my ship: offer it. Click an offered item: take it back.
 * Click my credits box and type an amount, Enter to set.
 */
public final class TradeScreen implements CustomUIPanelPlugin {
    static final float CELL = 100f, SEP = 30f;
    enum View { BOTH, ITEMS, SHIPS }

    static View view = View.BOTH;
    static TradeScreen current;
    static volatile boolean openPending;   // open as soon as no dialog is in the way
    static volatile String toast = "";     // message after a trade ends
    static boolean preview;                // F8 draft preview (no other player)

    private final Gui gui = new Gui();
    private CustomVisualDialogDelegate.DialogCallbacks callbacks;
    private InteractionDialogAPI dialog;
    private int scrollCargo, scrollShips, xSteps;
    private boolean editingCredits; private String creditsText = "";
    private String note = ""; private long noteNs;
    private final List<float[]> hitRects = new ArrayList<>(); private final List<Runnable> hitActions = new ArrayList<>();

    // ------------------------------------------------------------------ opening
    /** Called when the other player accepted my request, or I accepted theirs: open once the screen is free. */
    static void openFromAccept() { openPending = true; }

    /** Every frame from CoopCampaign. */
    static void tick() {
        TradeSession.tick();
        SectorAPI s = Global.getSector();
        if (openPending && current == null) {
            CampaignUIAPI ui = s.getCampaignUI();
            if (ui.getCurrentCoreTab() == null && !ui.isShowingDialog() && ui.getCurrentInteractionDialog() == null) {
                openPending = false;
                SectorEntityToken anchor = s.getPlayerFleet();
                ui.showInteractionDialog(new Host(), anchor);
            }
        }
        if (!toast.isEmpty() && current == null) { notifyMsg(toast); toast = ""; }
    }

    static void finished(String msg) {
        play(msg.contains("complete") ? "ui_industry_install_any_item" : "ui_transponder_on");
        toast = msg;
        if (current != null) current.closeScreen();
    }

    public static void showDraft() {
        preview = true;
        TradeSession.reset();
        TradeSession.peerName = "Varie";
        CargoAPI mock = Global.getFactory().createCargo(true);
        mock.addCommodity("metals", 120); mock.addCommodity("heavy_machinery", 25); mock.addCommodity("volatiles", 40);
        try { mock.addWeapons("heavymauler", 2); } catch (Throwable ignored) {}
        for (CargoStackAPI st : mock.getStacksCopy()) TradeSession.theirs.items.add(TradeSession.Item.of(st, st.getSize()));
        try { TradeSession.theirs.ships.add(Ships.def(0, 0, Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, "wolf_Assault"))); } catch (Throwable ignored) {}
        TradeSession.theirs.credits = 12500; TradeSession.theirs.ready = true; TradeSession.theirs.readyForOther = 1;
        TradeSession.active = true;
        openPending = true;
        StarMPPlugin.flog("trade: draft preview opened");
    }

    private static void notifyMsg(String m) { try { Global.getSector().getCampaignUI().addMessage("StarMP: " + m, Gui.B_TEXT); } catch (Throwable ignored) {} }
    static void play(String id) { try { Global.getSoundPlayer().playUISound(id, 1f, 1f); } catch (Throwable ignored) {} }

    /** Interaction dialog that only hosts the full-screen trade panel. */
    static final class Host implements InteractionDialogPlugin, CustomVisualDialogDelegate {
        InteractionDialogAPI dialog; TradeScreen screen;
        @Override public void init(InteractionDialogAPI d) {
            dialog = d; screen = new TradeScreen(); screen.dialog = d; current = screen;
            d.getTextPanel().addPara("Trading with " + TradeSession.peerName + ".");
            d.showCustomVisualDialog(Gui.sw(), Gui.sh(), this);
        }
        @Override public void init(CustomPanelAPI panel, DialogCallbacks cb) { screen.callbacks = cb; }
        @Override public CustomUIPanelPlugin getCustomPanelPlugin() { return screen; }
        @Override public float getNoiseAlpha() { return 0f; }
        @Override public void advance(float amount) {}
        @Override public void reportDismissed(int option) {
            if (current == screen) {
                current = null;
                if (TradeSession.active && !preview) TradeSession.cancel("closed");
                if (preview) { preview = false; TradeSession.reset(); }
            }
            dialog.dismiss();
        }
        @Override public void optionSelected(String t, Object d) { dialog.dismiss(); }
        @Override public void optionMousedOver(String t, Object d) {}
        @Override public void backFromEngagement(EngagementResultAPI r) {}
        @Override public Object getContext() { return null; }
        @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
    }

    void closeScreen() { if (callbacks != null) callbacks.dismissDialog(); }

    // ------------------------------------------------------------------ panel plugin
    @Override public void positionChanged(PositionAPI p) {}
    @Override public void renderBelow(float alpha) {}
    @Override public void advance(float amount) {}
    @Override public void buttonPressed(Object id) {}
    @Override public void render(float alpha) {
        try { draw(); } catch (Throwable t) { StarMPPlugin.flog("trade draw: " + StarMPPlugin.trace(t)); }
    }

    @Override public void processInput(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (e.isConsumed()) continue;
            if (e.isMouseMoveEvent()) { gui.mouseX = e.getX(); gui.mouseY = e.getY(); continue; }
            if (e.isKeyDownEvent()) {
                int k = e.getEventValue();
                if (editingCredits) {
                    if (k == Keyboard.KEY_RETURN || k == Keyboard.KEY_NUMPADENTER) { commitCredits(); }
                    else if (k == Keyboard.KEY_BACK) { if (!creditsText.isEmpty()) creditsText = creditsText.substring(0, creditsText.length() - 1); }
                    else if (k == Keyboard.KEY_ESCAPE) { editingCredits = false; }
                    else { char c = e.getEventChar(); if (c >= '0' && c <= '9' && creditsText.length() < 10) creditsText += c; }
                    e.consume(); continue;
                }
                if (k == Keyboard.KEY_ESCAPE) { e.consume(); closeScreen(); return; }
                e.consume(); continue;
            }
            if (e.isMouseScrollEvent()) {
                int d = e.getEventValue() > 0 ? -1 : 1;
                if (gui.mouseY > (Gui.sh() / 2f) - CELL && view != View.SHIPS) scrollCargo = Math.max(0, scrollCargo + d); else scrollShips = Math.max(0, scrollShips + d);
                e.consume(); continue;
            }
            if (e.isLMBDownEvent()) {
                gui.mouseX = e.getX(); gui.mouseY = e.getY();
                boolean shift = e.isShiftDown();
                if (editingCredits) commitCredits();
                if (!gui.click(gui.mouseX, gui.mouseY)) {
                    for (int i = hitRects.size() - 1; i >= 0; i--) {
                        float[] r = hitRects.get(i);
                        if (gui.mouseX >= r[0] && gui.mouseX <= r[0] + r[2] && gui.mouseY >= r[1] && gui.mouseY <= r[1] + r[3]) { shiftClick = shift; hitActions.get(i).run(); break; }
                    }
                }
                e.consume(); continue;
            }
            if (e.isMouseEvent()) e.consume();
        }
    }
    private boolean shiftClick;

    private void hit(float x, float y, float w, float h, Runnable r) { hitRects.add(new float[]{x, y, w, h}); hitActions.add(r); }
    private void say(String s) { note = s; noteNs = System.nanoTime(); }

    // ------------------------------------------------------------------ drawing
    private void draw() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        TradeSession.Offer mine = TradeSession.mine, theirs = TradeSession.theirs;
        float W = Gui.sw(), H = Gui.sh();
        int leftCols = 7, rightCols = 5;
        float gap = 24f, leftW = leftCols * CELL, rightW = rightCols * CELL;
        float x0 = (W - (leftW + 18 + gap + rightW)) / 2f;
        float frameH = 6 * CELL + SEP;
        float top = (H + frameH) / 2f + 10;
        hitRects.clear(); hitActions.clear();
        gui.begin();
        Gui.rect(0, 0, W, H, new Color(0, 0, 0, 200));
        Gui.bpanel(x0 - 12, top - frameH - 50, leftW + 18 + gap + rightW + 24, frameH + 90);
        gui.textCentered("TRADE WITH " + TradeSession.peerName.toUpperCase() + (preview ? " (PREVIEW)" : ""), Gui.BIG, W / 2f, top + 36, Gui.B_TEXT);

        // ---- left: what I still have (offered amounts taken out)
        List<Object[]> stacks = new ArrayList<>(); // {stack, remaining}
        for (CargoStackAPI s : me.getCargo().getStacksCopy()) {
            if (s.isNull() || s.getSize() <= 0) continue;
            float off = 0; for (TradeSession.Item i : mine.items) if (i.same(s)) off += i.qty;
            if (s.getSize() - off > 0.01f) stacks.add(new Object[]{s, s.getSize() - off});
        }
        List<FleetMemberAPI> ships = new ArrayList<>();
        for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) if (!mine.members.contains(m)) ships.add(m);
        if (view == View.BOTH) {
            cargoGrid(x0, top, leftCols, 4, stacks, "Your cargo");
            separator(x0, top - 4 * CELL, leftW + 16, "Your ships");
            shipGrid(x0, top - 4 * CELL - SEP, leftCols, 2, ships, null);
            scrollBar(x0 + leftW + 2, top, 4 * CELL); scrollBar(x0 + leftW + 2, top - 4 * CELL - SEP, 2 * CELL);
        } else if (view == View.ITEMS) {
            cargoGrid(x0, top - SEP / 2f, leftCols, 6, stacks, "Your cargo");
            scrollBar(x0 + leftW + 2, top - SEP / 2f, 6 * CELL);
        } else {
            shipGrid(x0, top - SEP / 2f, leftCols, 6, ships, "Your ships");
            scrollBar(x0 + leftW + 2, top - SEP / 2f, 6 * CELL);
        }

        // ---- right column
        float rx = x0 + leftW + 18 + gap, y = top;
        offerBox(rx, y, rightCols, mine, StarMPPlugin.net() != null ? StarMPPlugin.net().playerName() + " (you)" : "You", true);
        y -= 2 * CELL + SEP;
        creditsBox(rx, y - 8, rightW, mine.credits, true);
        y -= 46;
        float bh = 44, bw = 90;
        gui.vbutton("tx", "", rx, y - bh, bw, bh, true, this::pressX, false);
        drawX(rx + bw / 2f, y - bh / 2f);
        Gui.rect(rx + bw + 6, y - bh, rightW - 2 * bw - 12, bh, Gui.V_FILL);
        Gui.frame(rx + bw + 6, y - bh, rightW - 2 * bw - 12, bh, Gui.V_FRAME);
        String status = statusText();
        boolean green = TradeSession.countdownNs != 0 || status.equals("Trade complete");
        gui.textCentered(status, Gui.SMALL, rx + rightW / 2f, y - 12, green ? new Color(90, 230, 110) : Gui.B_TEXT);
        gui.vbutton("tv", "", rx + rightW - bw, y - bh, bw, bh, true, this::pressV, false);
        drawCheck(rx + rightW - bw / 2f, y - bh / 2f, mine.ready);
        y -= bh + 10;
        gui.vbutton("sel", "View: " + (view == View.BOTH ? "Items + Ships" : view == View.ITEMS ? "Items only" : "Ships only"), rx, y - 36, rightW, 36, true, this::cycle, false);
        y -= 44;
        creditsBox(rx, y, rightW, theirs.credits, false);
        y -= 38;
        offerBox(rx, y, rightCols, theirs, null, false);
        String hint = "Click: offer / take back.  Shift+click cargo: 10.  Click your credits to type.  Esc: close.";
        float tax = preview ? 0f : TradeSession.myTax();
        if (tax > 0) { // the other player is in another star system: the sender pays extra (credits 100%, items/ships 50% of value)
            gui.text("Out-of-system tax - " + TradeSession.peerName + " is in " + TradeTax.where(TradeSession.peer) + ": you pay " + String.format("%,d", Math.round(tax))
                    + " credits on top (credits 100%, items/ships 50% of value)", Gui.TINY, x0, top - frameH - 46, new Color(255, 170, 80));
        }
        if (!note.isEmpty() && System.nanoTime() - noteNs < 4_000_000_000L) gui.text(note, Gui.SMALL, x0, top - frameH - 26, new Color(255, 200, 90));
        else gui.text(hint, Gui.TINY, x0, top - frameH - 30, Gui.B_DIM);
        gui.end();
    }

    private String statusText() {
        long c = TradeSession.countdownNs;
        if (c != 0) {
            float t = (System.nanoTime() - c) / 1e9f;
            if (t < 1f) return "Trade starting";
            int left = 3 - (int) (t - 1f);
            return left > 0 ? String.valueOf(left) : "Trading...";
        }
        if (preview && TradeSession.mine.ready) { TradeSession.countdownNs = System.nanoTime(); }
        if (TradeSession.theirs.ready && TradeSession.theirs.readyForOther == TradeSession.mine.version) return "Trade ready";
        if (TradeSession.mine.ready) return "Waiting for " + TradeSession.peerName;
        return "";
    }

    // ------------------------------------------------------------------ actions
    private void cycle() { view = view == View.BOTH ? View.ITEMS : view == View.ITEMS ? View.SHIPS : View.BOTH; }

    private void pressV() {
        play("ui_industry_install_any_item");
        xSteps = 0;
        if (preview) { TradeSession.mine.ready = !TradeSession.mine.ready; if (!TradeSession.mine.ready) TradeSession.countdownNs = 0; return; }
        TradeSession.setReady(!TradeSession.mine.ready);
    }

    private void pressX() {
        play("ui_transponder_on");
        TradeSession.Offer mine = TradeSession.mine, theirs = TradeSession.theirs;
        if (TradeSession.countdownNs != 0) { if (preview) { mine.ready = false; TradeSession.countdownNs = 0; } else TradeSession.stopCountdown(); say("Countdown stopped"); return; }
        if (!mine.isEmpty() || !theirs.isEmpty()) {
            if (preview) { mine.items.clear(); mine.ships.clear(); mine.members.clear(); mine.credits = 0; theirs.items.clear(); theirs.ships.clear(); theirs.credits = 0; mine.ready = false; theirs.ready = false; }
            else { TradeSession.clearMine(); TradeSession.sendClear(); }
            say("Both trade windows cleared"); return;
        }
        if (!preview) { TradeSession.cancel("cancelled"); toast = "Trade cancelled"; }
        closeScreen();
    }

    private void offerStack(CargoStackAPI s, float remaining) {
        float q = shiftClick ? Math.min(10f, remaining) : remaining;
        if (preview) { TradeSession.mine.items.add(TradeSession.Item.of(s, q)); return; }
        TradeSession.addItem(s, q);
    }

    private void offerShip(FleetMemberAPI m) {
        if (preview) { if (!m.isFlagship()) { TradeSession.mine.members.add(m); TradeSession.mine.ships.add(Ships.def(0, 0, m)); } else say("You can't trade your flagship."); return; }
        String err = TradeSession.addShip(m);
        if (err != null) say(err);
    }

    private void commitCredits() {
        editingCredits = false;
        float v = 0; try { v = creditsText.isEmpty() ? 0 : Float.parseFloat(creditsText); } catch (Throwable ignored) {}
        if (preview) TradeSession.mine.credits = Math.min(v, Global.getSector().getPlayerFleet().getCargo().getCredits().get());
        else TradeSession.setCredits(v);
    }

    // ------------------------------------------------------------------ pieces
    private void header(float x, float y, String s) { if (s != null) gui.text(s, Gui.TINY, x + 2, y + 16, Gui.B_DIM); }

    private void cell(float x, float y) {
        boolean hot = gui.mouseX >= x && gui.mouseX <= x + CELL && gui.mouseY >= y && gui.mouseY <= y + CELL;
        Gui.rect(x + 1, y + 1, CELL - 2, CELL - 2, hot ? new Color(20, 55, 70, 240) : new Color(10, 30, 40, 230));
        Gui.frame(x, y, CELL, CELL, new Color(60, 120, 140, 200));
    }

    private void cargoGrid(float x, float top, int cols, int rows, List<Object[]> stacks, String title) {
        header(x, top, title);
        int maxScroll = Math.max(0, (stacks.size() + cols - 1) / cols - rows); if (scrollCargo > maxScroll) scrollCargo = maxScroll;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            float cx = x + c * CELL, cy = top - (r + 1) * CELL;
            cell(cx, cy);
            int i = (scrollCargo + r) * cols + c;
            if (i < stacks.size()) {
                final CargoStackAPI s = (CargoStackAPI) stacks.get(i)[0]; final float rem = (Float) stacks.get(i)[1];
                stack(cx, cy, s, rem);
                hit(cx, cy, CELL, CELL, () -> offerStack(s, rem));
            }
        }
    }

    private void shipGrid(float x, float top, int cols, int rows, List<FleetMemberAPI> ships, String title) {
        header(x, top, title);
        int maxScroll = Math.max(0, (ships.size() + cols - 1) / cols - rows); if (scrollShips > maxScroll) scrollShips = maxScroll;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            float cx = x + c * CELL, cy = top - (r + 1) * CELL;
            cell(cx, cy);
            int i = (scrollShips + r) * cols + c;
            if (i < ships.size()) {
                final FleetMemberAPI m = ships.get(i);
                ship(cx, cy, m.getHullSpec().getSpriteName(), m.getStatus().getHullFraction(), m.getRepairTracker().getCR(), m.isFlagship());
                hit(cx, cy, CELL, CELL, () -> offerShip(m));
            }
        }
    }

    private void offerBox(float x, float top, int cols, TradeSession.Offer o, String title, boolean mine) {
        header(x, top, title);
        float w = cols * CELL;
        if (view == View.BOTH) {
            offerItems(x, top, cols, 1, o, mine);
            separator(x, top - CELL, w, "Ships");
            offerShips(x, top - CELL - SEP, cols, 1, o, mine);
        } else if (view == View.ITEMS) offerItems(x, top - SEP / 2f, cols, 2, o, mine);
        else offerShips(x, top - SEP / 2f, cols, 2, o, mine);
    }

    private void offerItems(float x, float top, int cols, int rows, TradeSession.Offer o, boolean mine) {
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            float cx = x + c * CELL, cy = top - (r + 1) * CELL;
            cell(cx, cy);
            final int i = r * cols + c;
            if (i < o.items.size()) {
                TradeSession.Item it = o.items.get(i);
                itemIcon(cx, cy, it);
                if (mine) hit(cx, cy, CELL, CELL, () -> { if (preview) TradeSession.mine.items.remove(i); else TradeSession.removeItem(i); });
            }
        }
    }

    private void offerShips(float x, float top, int cols, int rows, TradeSession.Offer o, boolean mine) {
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            float cx = x + c * CELL, cy = top - (r + 1) * CELL;
            cell(cx, cy);
            final int i = r * cols + c;
            if (i < o.ships.size()) {
                Wire.ShipDef d = o.ships.get(i);
                String sprite = null; try { sprite = Global.getSettings().getHullSpec(d.hullId).getSpriteName(); } catch (Throwable ignored) {}
                ship(cx, cy, sprite, d.hullFrac, d.cr, false);
                if (mine) hit(cx, cy, CELL, CELL, () -> { if (preview) { TradeSession.mine.ships.remove(i); TradeSession.mine.members.remove(i); } else TradeSession.removeShip(i); });
            }
        }
    }

    private void separator(float x, float top, float w, String label) {
        Gui.rect(x, top - SEP, w, SEP, new Color(2, 8, 12, 245));
        if (label != null) gui.text(label, Gui.TINY, x + 4, top - 8, Gui.B_DIM);
    }

    private void creditsBox(float x, float y, float w, float credits, boolean mine) {
        boolean editing = mine && editingCredits;
        Gui.rect(x, y - 30, w, 30, editing ? new Color(20, 55, 70, 240) : new Color(10, 30, 40, 230));
        Gui.frame(x, y - 30, w, 30, editing ? new Color(255, 215, 90) : Gui.V_FRAME);
        String label = mine ? "Credits: " : TradeSession.peerName + "'s credits: ";
        String t = editing ? label + creditsText + "_" : label + String.format("%,d", Math.round(credits));
        gui.textCentered(t, Gui.SMALL, x + w / 2f, y - 7, new Color(255, 215, 90));
        if (mine) hit(x, y - 30, w, 30, () -> { editingCredits = true; creditsText = TradeSession.mine.credits > 0 ? String.valueOf(Math.round(TradeSession.mine.credits)) : ""; });
    }

    private void scrollBar(float x, float top, float h) {
        Gui.rect(x, top - h, 14, h, new Color(15, 40, 50, 230));
        Gui.frame(x, top - h, 14, h, new Color(60, 120, 140, 200));
    }

    private static String spriteFor(CargoAPI.CargoItemType type, String id) {
        try {
            if (type == CargoAPI.CargoItemType.RESOURCES) return Global.getSettings().getCommoditySpec(id).getIconName();
            if (type == CargoAPI.CargoItemType.WEAPONS) return Global.getSettings().getWeaponSpec(id).getTurretSpriteName();
            if (type == CargoAPI.CargoItemType.FIGHTER_CHIP) return Global.getSettings().getFighterWingSpec(id).getVariant().getHullSpec().getSpriteName();
            if (type == CargoAPI.CargoItemType.SPECIAL) return Global.getSettings().getSpecialItemSpec(id).getIconName();
        } catch (Throwable ignored) {}
        return null;
    }

    private void stack(float x, float y, CargoStackAPI s, float qty) {
        TradeSession.Item it = TradeSession.Item.of(s, qty);
        itemIcon(x, y, it);
    }

    private void itemIcon(float x, float y, TradeSession.Item it) {
        icon(spriteFor(it.type, it.id), x + CELL / 2f, y + CELL / 2f + 4, CELL - 30);
        String q = String.valueOf(Math.round(it.qty));
        gui.text(q, Gui.SMALL, x + CELL - 8 - gui.textWidth(q, Gui.SMALL), y + CELL - 4, new Color(200, 220, 230));
    }

    private void ship(float x, float y, String sprite, float hull, float cr, boolean flagship) {
        icon(sprite, x + CELL / 2f, y + CELL / 2f + 8, CELL - 34);
        float bw = CELL - 20;
        bar(x + 10, y + 12, bw, hull, new Color(120, 230, 120));
        bar(x + 10, y + 6, bw, cr, new Color(110, 200, 255));
        if (flagship) gui.text("Flagship", Gui.TINY, x + 6, y + CELL - 4, new Color(255, 215, 90));
    }

    private void bar(float x, float y, float w, float f, Color c) {
        Gui.rect(x, y, w, 3, new Color(30, 50, 60, 230));
        Gui.rect(x, y, w * Math.max(0f, Math.min(1f, f)), 3, c);
    }

    private void icon(String sprite, float cx, float cy, float max) {
        if (sprite == null) return;
        try {
            SpriteAPI sp = Global.getSettings().getSprite(sprite);
            if (sp == null || sp.getWidth() <= 0) { Global.getSettings().loadTexture(sprite); sp = Global.getSettings().getSprite(sprite); }
            float ow = sp.getWidth(), oh = sp.getHeight();
            float s = Math.min(Math.min(max / ow, max / oh), 1.6f);
            sp.setSize(ow * s, oh * s); sp.setAngle(0); sp.setAlphaMult(1f);
            sp.renderAtCenter(cx, cy);
            sp.setSize(ow, oh);
        } catch (Throwable ignored) {}
    }

    private void drawX(float cx, float cy) {
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND); GL11.glLineWidth(4f);
        GL11.glColor4ub((byte) 230, (byte) 60, (byte) 60, (byte) 255);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - 12, cy - 12); GL11.glVertex2f(cx + 12, cy + 12);
        GL11.glVertex2f(cx - 12, cy + 12); GL11.glVertex2f(cx + 12, cy - 12);
        GL11.glEnd();
    }

    private void drawCheck(float cx, float cy, boolean on) {
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND); GL11.glLineWidth(on ? 5f : 4f);
        if (on) GL11.glColor4ub((byte) 90, (byte) 240, (byte) 110, (byte) 255); else GL11.glColor4ub((byte) 60, (byte) 170, (byte) 80, (byte) 255);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(cx - 14, cy); GL11.glVertex2f(cx - 4, cy - 10); GL11.glVertex2f(cx + 16, cy + 10);
        GL11.glEnd();
    }
}
