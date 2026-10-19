package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.List;

/** 0.1.5.3: F9 (bug report) and F10 (debug overlay) on the campaign map, from the game's own key events. */
public final class DiagInput implements CampaignInputListener {
    public static void register() {
        Global.getSector().getListenerManager().removeListenerOfClass(DiagInput.class);
        Global.getSector().getListenerManager().addListener(new DiagInput(), true);
    }
    @Override public int getListenerInputPriority() { return 5; }
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {
        starmp.NetManager n = starmp.StarMPPlugin.net();
        boolean linked = n != null && n.linked();
        boolean menu = false;
        if (linked) { try { menu = Global.getSector().getCampaignUI().isShowingMenu(); } catch (Throwable ignored) {} }
        for (InputEventAPI e : events) {
            // 0.9.4.0: the Escape menu's "Load Game" is off while linked (it loaded only this player's game while the others carried on).
            // The menu is centred on the screen; its Load Game row sits just above the middle, 13..233 UI units right of centre.
            if (menu && !e.isConsumed() && e.isMouseEvent() && loadRowHit(e.getX(), e.getY())) {
                boolean down = e.isMouseDownEvent(); // read BEFORE consuming
                e.consume();
                if (down) {
                    try { Global.getSector().getCampaignUI().addMessage("StarMP: Load Game is turned off while you are in a multiplayer session (leave the lobby first)"); } catch (Throwable ignored) {}
                }
                continue;
            }
            if (!e.isConsumed() && e.isKeyDownEvent() && !e.isRepeat()) starmp.diag.Diag.keyEvent(e.getEventValue(), "campaign");
            // 0.9.3.5: no quickload in a multiplayer session - it reloaded only that player's save while everyone else carried on
            if (linked && !e.isConsumed() && e.isKeyboardEvent() && e.getEventValue() == quickLoadKey()) {
                boolean down = e.isKeyDownEvent(), repeat = e.isRepeat(); // read BEFORE consuming: a consumed InputEvent throws on any access
                e.consume();
                if (down && !repeat) {
                    try { Global.getSector().getCampaignUI().addMessage("StarMP: quickload is turned off while you are in a multiplayer session"); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static boolean loadRowHit(int mx, int my) {
        try {
            float sc = Global.getSettings().getScreenScaleMult();
            float w = Global.getSettings().getScreenWidthPixels(), h = Global.getSettings().getScreenHeightPixels();
            float cx = w / 2f, cyTop = h / 2f, yTop = h - my;
            return mx >= cx + 13f * sc && mx <= cx + 233f * sc && yTop >= cyTop - 30f * sc && yTop <= cyTop - 2f * sc;
        } catch (Throwable t) { return false; }
    }

    private static int quickLoadKey = -1;
    /** The key the game's QUICK_LOAD control is bound to (F9 by default). */
    private static int quickLoadKey() {
        if (quickLoadKey >= 0) return quickLoadKey;
        int code = org.lwjgl.input.Keyboard.KEY_F9;
        try {
            String raw = Global.getSettings().getControlStringForEnumName("QUICK_LOAD");
            if (raw != null) {
                String r = raw.trim().toUpperCase();
                int k = org.lwjgl.input.Keyboard.getKeyIndex(r);
                if (k == org.lwjgl.input.Keyboard.KEY_NONE) k = org.lwjgl.input.Keyboard.getKeyIndex(r.replace(' ', '_'));
                if (k != org.lwjgl.input.Keyboard.KEY_NONE) code = k;
            }
        } catch (Throwable ignored) {}
        return quickLoadKey = code;
    }
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPostCore(List<InputEventAPI> events) {}
}
