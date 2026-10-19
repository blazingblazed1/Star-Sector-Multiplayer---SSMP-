package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.*;
import org.json.JSONObject;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.9.2.2: "Alliance (StarMP)" - pinned in the Intel tab (E). Outside an alliance: create one, join / ask to join,
 * answer invites. Inside: members and roles, invites and requests, standings (war / peace / friendly) toward players,
 * alliances and NPC factions (owner + admins), settings (owner), leave / disband. Never saved (like Players intel).
 */
public class AllianceIntel extends BaseIntelPlugin {
    private static final float BW = 260f, BH = 20f;
    private TextFieldAPI fName, fTag, fDesc;
    /** 0.9.2.4: also drawn as the Intel screen's 4th tab (AllianceTab): no intel confirm dialogs there - a second click confirms. */
    String confirmId, confirmText;
    Runnable redraw;

    @Override protected String getName() { return "Alliance (StarMP)"; }
    @Override public String getIcon() { return Global.getSettings().getSpriteName("intel", "fleet_log"); }

    @Override public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> t = new LinkedHashSet<>(super.getIntelTags(map));
        t.add("Alliance");
        return t;
    }

    @Override public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        info.addPara(getName(), getTitleColor(mode), 0f);
        Alliances.Ally a = Alliances.mine();
        info.addPara(a == null ? "Not in an alliance" : a.name + " [" + a.tag + "] - " + a.members.size() + " member" + (a.members.size() == 1 ? "" : "s"), Misc.getGrayColor(), 3f);
    }

    @Override public boolean hasSmallDescription() { return false; }
    @Override public boolean hasLargeDescription() { return true; }
    @Override public boolean shouldRemoveIntel() { return isEnded(); }
    @Override public boolean isHidden() { return AllianceTab.working(); } // the 4th tab replaces this entry when it works

    private static String me() { NetManager n = StarMPPlugin.net(); return n == null ? "" : Alliances.lc(n.playerName()); }

    /** A stored (lower case) name as the player typed it, when they are online. */
    static String disp(String lc) {
        NetManager n = StarMPPlugin.net();
        if (n != null && Alliances.lc(n.playerName()).equals(lc)) return n.playerName();
        for (String o : others()) if (Alliances.lc(o).equals(lc)) return o;
        return lc;
    }

    /** Everyone else known in the session. */
    private static List<String> others() {
        NetManager n = StarMPPlugin.net();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (n == null || !n.linked()) return new ArrayList<>();
        for (int id : n.peerIds()) out.add(starmp.combat.CoopState.nameOf(n, id));
        for (Integer id : CoopCampaign.fleets.keySet()) if (id != n.myId()) out.add(starmp.combat.CoopState.nameOf(n, id));
        out.removeIf(s -> s == null || s.isEmpty() || Alliances.lc(s).equals(me()));
        return new ArrayList<>(out);
    }

    @Override public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        TooltipMakerAPI t = panel.createUIElement(width, height, true);
        Color hl = Misc.getHighlightColor(), gray = Misc.getGrayColor();
        float w = Math.min(width - 10f, 640f);
        NetManager n = StarMPPlugin.net();
        if (n == null || !n.linked()) { t.addPara("Alliances work while you are in a multiplayer session.", 0f); panel.addUIElement(t).inTL(0, 0); return; }
        String note = Alliances.lastNote();
        if (note != null) t.addPara(note, hl, 0f);
        if (confirmText != null) t.addPara(confirmText + "  Press the button again to confirm.", Misc.getNegativeHighlightColor(), 4f);
        t.addButton("Refresh", "refresh", 120f, BH, 6f);
        Alliances.Ally a = Alliances.mine();
        if (a == null) outside(t, w, hl, gray); else inside(t, a, w, hl, gray);
        panel.addUIElement(t).inTL(0, 0);
    }

    // ------------------------------------------------------------------ not in an alliance
    private void outside(TooltipMakerAPI t, float w, Color hl, Color gray) {
        String me = me();
        boolean any = false;
        for (Alliances.Ally x : Alliances.all()) if (x.invites.contains(me)) {
            if (!any) { t.addSectionHeading("Invitations", Alignment.MID, 12f); any = true; }
            t.addPara(x.name + " [" + x.tag + "] invited you.", 6f);
            t.addButton("Join " + x.name, "join:" + x.id, BW, BH, 4f);
            t.addButton("Decline", "decline_invite:" + x.id, BW, BH, 2f);
        }
        t.addSectionHeading("Alliances in this campaign", Alignment.MID, 12f);
        if (Alliances.all().isEmpty()) t.addPara("None yet.", gray, 6f);
        for (Alliances.Ally x : Alliances.all()) {
            t.addPara(x.name + " [" + x.tag + "]  -  " + x.members.size() + " member" + (x.members.size() == 1 ? "" : "s") + ", " + policy(x.policy) + (x.desc.isEmpty() ? "" : "\n" + x.desc), 6f);
            if (x.policy == Alliances.OPEN) t.addButton("Join " + x.name, "join:" + x.id, BW, BH, 4f);
            else if (x.policy == Alliances.REQUEST) t.addButton(x.requests.contains(me) ? "Request sent" : "Ask to join " + x.name, "request:" + x.id, BW, BH, 4f);
        }
        t.addSectionHeading("Create an alliance", Alignment.MID, 12f);
        t.addPara("Name (2-32 characters):", 6f);
        fName = t.addTextField(w * 0.6f, 24f, Fonts.DEFAULT_SMALL, 3f); fName.setMaxChars(32);
        t.addPara("Tag (2-5 characters, shown as [TAG] before members' names):", 6f);
        fTag = t.addTextField(120f, 24f, Fonts.DEFAULT_SMALL, 3f); fTag.setMaxChars(5);
        t.addPara("The alliance starts with your current reputations. While you are in it, its reputations replace yours; "
                + "you get your own back exactly when you leave. Joining ends any commission.", gray, 6f);
        t.addButton("Create alliance", "create", BW, BH, 6f);
    }

    // ------------------------------------------------------------------ in an alliance
    private void inside(TooltipMakerAPI t, Alliances.Ally a, float w, Color hl, Color gray) {
        String me = me();
        boolean owner = a.owner.equals(me), admin = a.isAdmin(me);
        Color c = new Color(a.color);
        t.addSectionHeading(a.name + " [" + a.tag + "]", c, new Color(0, 0, 0, 0), Alignment.MID, 6f);
        if (!a.desc.isEmpty()) t.addPara(a.desc, 6f);
        t.addPara("You are the " + a.role(me) + ". " + policy(a.policy) + ". Chat to the alliance only: start a chat line with /a", gray, 6f);

        t.addSectionHeading("Members (" + a.members.size() + ")", Alignment.MID, 12f);
        Set<String> online = new HashSet<>(); for (String o : others()) online.add(Alliances.lc(o)); online.add(me);
        for (String m : a.members) {
            t.addPara(disp(m) + "  (" + a.role(m) + ")" + (online.contains(m) ? "" : "  - offline"), online.contains(m) ? Misc.getTextColor() : gray, 4f);
            if (m.equals(me)) continue;
            if (admin && !m.equals(a.owner) && (owner || !a.admins.contains(m))) t.addButton("Kick " + disp(m), "kick:" + m, BW, BH, 2f);
            if (owner) {
                t.addButton(a.admins.contains(m) ? "Remove admin: " + disp(m) : "Make admin: " + disp(m), (a.admins.contains(m) ? "demote:" : "promote:") + m, BW, BH, 2f);
                t.addButton("Give ownership to " + disp(m), "transfer:" + m, BW, BH, 2f);
            }
        }
        if (admin) {
            if (!a.requests.isEmpty()) {
                t.addSectionHeading("Requests to join", Alignment.MID, 12f);
                for (String r : a.requests) { t.addPara(disp(r), 4f); t.addButton("Accept " + r, "accept:" + r, BW, BH, 2f); t.addButton("Decline " + r, "decline:" + r, BW, BH, 2f); }
            }
            t.addSectionHeading("Invite", Alignment.MID, 12f);
            boolean anyone = false;
            for (String o : others()) {
                if (Alliances.of(o) != null) continue;
                anyone = true;
                t.addButton(a.invites.contains(Alliances.lc(o)) ? "Invited: " + o : "Invite " + o, "invite:" + o, BW, BH, 3f);
            }
            if (!anyone) t.addPara("Nobody online outside an alliance.", gray, 4f);
        }

        // standings toward players / alliances
        t.addSectionHeading("Standings with players and alliances", Alignment.MID, 12f);
        t.addPara(admin ? "Members can't start hostilities on their own: only the owner and admins set war, peace or friendly. "
                + "Anyone marked hostile is free game for every member." : "Only the owner and admins change these.", gray, 4f);
        String self = "a:" + a.id;
        LinkedHashMap<String, String> targets = new LinkedHashMap<>(); // entity -> label
        for (Alliances.Ally x : Alliances.all()) if (x != a) targets.put("a:" + x.id, x.name + " [" + x.tag + "] (alliance)");
        for (String o : others()) if (Alliances.of(o) == null) targets.put(Relations.entityOf(o), o);
        if (targets.isEmpty()) t.addPara("Nobody else yet.", gray, 4f);
        for (Map.Entry<String, String> e : targets.entrySet()) {
            int v = Relations.standing(self, e.getKey());
            t.addPara(e.getValue() + ":  " + standingText(v), v <= Relations.HOSTILE ? Misc.getNegativeHighlightColor() : v >= 50 ? Misc.getPositiveHighlightColor() : Misc.getTextColor(), 4f);
            if (admin) {
                if (v > Relations.HOSTILE) t.addButton("Declare war on " + e.getValue(), "standing:" + e.getKey() + ":-50", BW, BH, 2f);
                if (v != 0) t.addButton("Make peace with " + e.getValue(), "standing:" + e.getKey() + ":0", BW, BH, 2f);
                if (v < 50) t.addButton("Mark friendly: " + e.getValue(), "standing:" + e.getKey() + ":50", BW, BH, 2f);
            }
        }

        // NPC factions
        t.addSectionHeading("Alliance reputation with NPC factions", Alignment.MID, 12f);
        t.addPara("Every member's actions change these. " + (admin ? "You can mark a faction hostile." : ""), gray, 4f);
        List<FactionAPI> fs = new ArrayList<>();
        for (FactionAPI f : Global.getSector().getAllFactions()) if (Alliances.npcFaction(f.getId()) && f.isShowInIntelTab()) fs.add(f);
        fs.sort(Comparator.comparing(f -> f.getDisplayName().toLowerCase(Locale.ROOT)));
        for (FactionAPI f : fs) {
            String fn = Misc.ucFirst(f.getDisplayName());
            float r = a.npc.containsKey(f.getId()) ? a.npc.get(f.getId()) : 0f;
            int v = Math.round(r * 100f);
            t.addPara(fn + ":  " + standingText(v), v <= -50 ? Misc.getNegativeHighlightColor() : v >= 50 ? Misc.getPositiveHighlightColor() : Misc.getTextColor(), 3f);
            if (admin && v > -50) t.addButton("Mark " + fn + " hostile", "standing:f:" + f.getId() + ":-50", BW, BH, 2f);
        }

        if (owner) {
            t.addSectionHeading("Settings (owner)", Alignment.MID, 12f);
            t.addPara("Name:", 6f); fName = t.addTextField(w * 0.6f, 24f, Fonts.DEFAULT_SMALL, 3f); fName.setMaxChars(32); fName.setText(a.name);
            t.addPara("Tag:", 6f); fTag = t.addTextField(120f, 24f, Fonts.DEFAULT_SMALL, 3f); fTag.setMaxChars(5); fTag.setText(a.tag);
            t.addPara("Description:", 6f); fDesc = t.addTextField(w, 24f, Fonts.DEFAULT_SMALL, 3f); fDesc.setMaxChars(300); fDesc.setText(a.desc);
            t.addButton("Save name / tag / description", "save", BW, BH, 6f);
            t.addButton("Joining: " + policy(a.policy) + " (change)", "policy", BW, BH, 4f);
            t.addButton("Change colour", "color", BW, BH, 4f);
        }
        t.addSectionHeading("", Alignment.MID, 12f);
        t.addButton("Leave alliance", "leave", BW, BH, 6f);
        if (owner) t.addButton("Disband alliance", "disband", BW, BH, 4f);
    }

    private static String policy(int p) { return p == Alliances.OPEN ? "open to anyone" : p == Alliances.REQUEST ? "join by request" : "invite only"; }

    private static String standingText(int v) {
        String word = v <= -50 ? "hostile" : v < -10 ? "suspicious" : v <= 10 ? "neutral" : v < 50 ? "favorable" : "friendly";
        return (v > 0 ? "+" : "") + v + " (" + word + ")";
    }

    // ------------------------------------------------------------------ buttons
    @Override public boolean doesButtonHaveConfirmDialog(Object id) {
        String s = String.valueOf(id);
        return s.equals("leave") || s.equals("disband") || s.startsWith("kick:") || s.startsWith("transfer:") || s.startsWith("standing:") && s.endsWith(":-50");
    }

    @Override public void createConfirmationPrompt(Object id, TooltipMakerAPI prompt) {
        String s = String.valueOf(id);
        String text = s.equals("leave") ? "Leave the alliance? You get your own reputations back."
                : s.equals("disband") ? "Disband the alliance? Every member gets their own reputations back."
                : s.startsWith("kick:") ? "Kick " + s.substring(5) + " from the alliance?"
                : s.startsWith("transfer:") ? "Give ownership to " + s.substring(9) + "? You become an admin."
                : "Mark them hostile? Every member of the alliance will be hostile to them.";
        prompt.addPara(text, 0f);
    }

    @Override public void buttonPressConfirmed(Object id, IntelUIAPI ui) {
        String s = String.valueOf(id);
        try {
            JSONObject c = new JSONObject();
            if (s.equals("refresh")) { update(ui); return; }
            if (s.equals("create")) { c.put("op", "create"); c.put("name", fName == null ? "" : fName.getText()); c.put("tag", fTag == null ? "" : fTag.getText()); c.put("reps", Alliances.myReps()); }
            else if (s.equals("save")) { c.put("op", "edit"); c.put("name", fName.getText()); c.put("tag", fTag.getText()); c.put("desc", fDesc.getText()); }
            else if (s.equals("policy")) { Alliances.Ally a = Alliances.mine(); if (a == null) return; c.put("op", "edit"); c.put("policy", (a.policy + 1) % 3); }
            else if (s.equals("color")) {
                Alliances.Ally a = Alliances.mine(); if (a == null) return;
                int i = 0; for (int k = 0; k < Alliances.COLORS.length; k++) if (Alliances.COLORS[k] == a.color) i = k;
                c.put("op", "edit"); c.put("color", Alliances.COLORS[(i + 1) % Alliances.COLORS.length]);
            }
            else if (s.equals("leave") || s.equals("disband")) c.put("op", s);
            else if (s.startsWith("standing:")) { // standing:<entity>:<value>; entity itself contains one ':'
                int last = s.lastIndexOf(':');
                c.put("op", "standing"); c.put("entity", s.substring(9, last)); c.put("value", Integer.parseInt(s.substring(last + 1)));
            } else {
                int k = s.indexOf(':');
                String op = s.substring(0, k), arg = s.substring(k + 1);
                c.put("op", op);
                if (op.equals("join") || op.equals("request") || op.equals("decline_invite")) { c.put("id", arg); c.put("reps", Alliances.myReps()); }
                else c.put("target", arg);
            }
            Alliances.command(c);
        } catch (Throwable t) { StarMPPlugin.flog("alliance intel: " + t); }
        update(ui);
    }

    private void update(IntelUIAPI ui) {
        if (ui != null) ui.updateUIForItem(this);
        else if (redraw != null) redraw.run();
    }

    /** Someone is typing in one of the text fields (don't redraw then). */
    boolean typing() {
        try { return (fName != null && fName.hasFocus()) || (fTag != null && fTag.hasFocus()) || (fDesc != null && fDesc.hasFocus()); } catch (Throwable t) { return false; }
    }

    /** The tab's button handler: confirm-needing buttons take a second click. */
    void tabPressed(Object id) {
        if (doesButtonHaveConfirmDialog(id) && !String.valueOf(id).equals(confirmId)) {
            confirmId = String.valueOf(id);
            String s = confirmId;
            confirmText = s.equals("leave") ? "Leave the alliance? You get your own reputations back."
                    : s.equals("disband") ? "Disband the alliance? Every member gets their own reputations back."
                    : s.startsWith("kick:") ? "Kick " + s.substring(5) + " from the alliance?"
                    : s.startsWith("transfer:") ? "Give ownership to " + s.substring(9) + "? You become an admin."
                    : "Mark them hostile? Every member of the alliance will be hostile to them.";
            if (redraw != null) redraw.run();
            return;
        }
        confirmId = null; confirmText = null;
        buttonPressConfirmed(id, null);
    }
}
