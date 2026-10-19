package starmp.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI;
import starmp.StarMPPlugin;

/** Test hook (STARMP_TEST_UIDATA=1): capture the UI settings, change a toolbar slot + a map filter + zoom, restore
 *  the capture, and check that everything came back. */
final class UiDataTest {
    private UiDataTest() {}
    static void run() {
        try {
            StringBuilder a = new StringBuilder(); UiData.capture(a);
            PersistentUIDataAPI u = Global.getSector().getUIData();
            PersistentUIDataAPI.AbilitySlotAPI s0 = (PersistentUIDataAPI.AbilitySlotAPI) UiData.bars(u.getAbilitySlotsAPI())[1][3]; // bar 2, slot 4 (live)
            String before = s0.getAbilityId();
            s0.setAbilityId("emergency_burn".equals(before) ? "sensor_burst" : "emergency_burn");
            com.fs.starfarer.api.ui.MapFilterData f = (com.fs.starfarer.api.ui.MapFilterData) java.lang.invoke.MethodHandles.publicLookup()
                    .findVirtual(u.getClass(), "getMapFilterData", java.lang.invoke.MethodType.methodType(com.fs.starfarer.api.ui.MapFilterData.class)).invoke(u);
            f.names = !f.names;
            StringBuilder b = new StringBuilder(); UiData.capture(b);
            UiData.apply(UiData.pick(a.toString()));
            StringBuilder c = new StringBuilder(); UiData.capture(c);
            int lines = UiData.pick(a.toString()).size();
            StarMPPlugin.flog("TEST uidata: " + lines + " lines; changed differs=" + !a.toString().equals(b.toString()) + "; restored equals original=" + a.toString().equals(c.toString())
                    + "; slot0 " + before + " -> " + s0.getAbilityId() + "; first lines: " + a.toString().replace('\n', '|').substring(0, Math.min(300, a.length())));
        } catch (Throwable t) { StarMPPlugin.flog("TEST uidata failed: " + StarMPPlugin.trace(t)); }
    }
}
