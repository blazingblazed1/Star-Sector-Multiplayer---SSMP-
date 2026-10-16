package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.1.4.10: reputation from a joint battle is not divided - every player who fought gets the same standing change
 * the battle owner got. The owner snapshots its standings when the encounter opens (so the "attacking a friendly"
 * hit at engage time counts too) and, once the joint battle is over and its dialogs are closed, sends the difference
 * to everyone in the fight (REP). Each player applies it to their own standings (kept in their player record).
 */
public final class RepShare {
    private RepShare() {}
    public static final byte REP = 32;
    private static Map<String, Float> snap;
    private static Object snapDialog;
    static final ConcurrentLinkedQueue<byte[]> inbox = new ConcurrentLinkedQueue<>();

    public static void onPacket(byte[] d) { inbox.add(d); }

    private static Map<String, Float> standings(SectorAPI sector) {
        Map<String, Float> m = new HashMap<>();
        FactionAPI pl = sector.getPlayerFaction();
        for (FactionAPI f : sector.getAllFactions()) if (!f.getId().equals("player") && !f.getId().startsWith("starmp_")) m.put(f.getId(), pl.getRelationship(f.getId()));
        return m;
    }

    /** Campaign thread, every frame while linked. */
    static void tick(NetManager net, SectorAPI sector) {
        byte[] p;
        while ((p = inbox.poll()) != null) { try { apply(sector, p); } catch (Throwable t) { StarMPPlugin.flog("rep share: bad packet: " + t); } }
        InteractionDialogAPI dlg = sector.getCampaignUI().getCurrentInteractionDialog();
        if (dlg != null && dlg != snapDialog && dlg.getInteractionTarget() instanceof CampaignFleetAPI && !((CampaignFleetAPI) dlg.getInteractionTarget()).isPlayerFleet()) {
            snapDialog = dlg; snap = standings(sector); // an encounter starts: remember where we stood
        }
        if (dlg != null || snap == null) return;
        Map<Integer, Integer> shares = LootSplit.takeRepShares();
        Map<String, Float> before = snap; snap = null; snapDialog = null;
        if (shares == null || shares.size() < 2) return; // not a joint battle
        Map<String, Float> after = standings(sector);
        List<Object[]> delta = new ArrayList<>();
        for (Map.Entry<String, Float> e : after.entrySet()) {
            Float b = before.get(e.getKey());
            if (b == null) continue;
            float dv = e.getValue() - b;
            if (Math.abs(dv) >= 0.0005f) delta.add(new Object[]{e.getKey(), dv});
        }
        if (delta.isEmpty()) { StarMPPlugin.flog("rep share: the joint battle changed no standings"); return; }
        int size = 1 + 2; for (Object[] d : delta) size += 2 + ((String) d[0]).length() * 3 + 4;
        ByteBuffer b = ByteBuffer.allocate(size); b.put(REP); b.putShort((short) delta.size());
        for (Object[] d : delta) { byte[] id = ((String) d[0]).getBytes(StandardCharsets.UTF_8); b.putShort((short) id.length); b.put(id); b.putFloat((Float) d[1]); }
        byte[] out = Arrays.copyOf(b.array(), b.position());
        for (Integer peer : shares.keySet()) if (peer != net.myId()) starmp.combat.CoopState.sendTo(net, peer, out);
        StarMPPlugin.flog("rep share: sent " + delta.size() + " standing change(s) to " + (shares.size() - 1) + " player(s)");
    }

    private static void apply(SectorAPI sector, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d); b.get();
        int n = b.getShort() & 0xFFFF;
        FactionAPI pl = sector.getPlayerFaction();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] id = new byte[b.getShort() & 0xFFFF]; b.get(id); float dv = b.getFloat();
            String fid = new String(id, StandardCharsets.UTF_8);
            FactionAPI f = sector.getFaction(fid);
            if (f == null) continue;
            pl.adjustRelationship(fid, dv);
            parts.add(f.getDisplayName() + " " + (dv > 0 ? "+" : "") + Math.round(dv * 100));
        }
        String msg = "StarMP: reputation from the joint battle: " + String.join(", ", parts);
        sector.getCampaignUI().addMessage(msg);
        StarMPPlugin.flog(msg);
    }
}
