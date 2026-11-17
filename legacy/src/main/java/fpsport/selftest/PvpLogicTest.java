package fpsport.selftest;

import fpsport.pvp.PvpLogic;

/**
 * PVP 增强纯逻辑的无头测试：连击窗口、击退角度、3D→屏幕投影、脉动与放置校验。
 */
public final class PvpLogicTest {

    private PvpLogicTest() {
    }

    public static void run(ModuleTestRunner.Assertions a) {
        testCombo(a);
        testScreenAngle(a);
        testProject(a);
        testMath(a);
        testPlacement(a);
    }

    private static void testCombo(ModuleTestRunner.Assertions a) {
        PvpLogic.ComboTracker t = new PvpLogic.ComboTracker(2000L);
        a.eq("combo first not chain", t.hit(1000L), false);
        a.eq("combo count one", t.count, 1);
        a.eq("combo second chain", t.hit(1500L), true);
        a.eq("combo count two", t.count, 2);
        a.eq("combo active", t.isActive(1500L), true);
        a.eq("combo duration", t.durationMs(1500L), 500L);
        a.eq("combo remaining", t.remainingMs(1500L), 2000L);
        a.eq("combo expired", t.isActive(3501L), false);
        a.eq("combo expired duration", t.durationMs(3501L), 0L);

        // 超时后重新开始，不再算连击
        a.eq("combo restart chain", t.hit(4000L), false);
        a.eq("combo restart count", t.count, 1);
        a.eq("combo restart duration", t.durationMs(4000L), 0L);

        // 恰好落在窗口边界仍算连击
        t.reset();
        t.hit(0L);
        a.eq("combo boundary chain", t.hit(2000L), true);
        a.eq("combo boundary count", t.count, 2);

        t.reset();
        a.eq("combo reset count", t.count, 0);
        a.eq("combo reset active", t.isActive(0L), false);
    }

    private static void testScreenAngle(ModuleTestRunner.Assertions a) {
        double eps = 1.0E-9D;
        a.eq("angle forward", PvpLogic.screenAngle(0.0D, -1.0D, 0.0F), 0.0D);
        a.eq("angle right", PvpLogic.screenAngle(1.0D, 0.0D, 0.0F), Math.PI * 0.5D);
        a.eq("angle back", Math.abs(PvpLogic.screenAngle(0.0D, 1.0D, 0.0F)), Math.PI);
        // yaw 90° 看向 -X，此时 -Z 在右手边
        a.eq("angle yaw90 forward", PvpLogic.screenAngle(-1.0D, 0.0D, 90.0F), 0.0D);
        a.eq("angle yaw90 right", PvpLogic.screenAngle(0.0D, -1.0D, 90.0F), Math.PI * 0.5D);
        // 方向长度不影响角度
        a.eq("angle length invariant",
                PvpLogic.screenAngle(3.0D, 0.0D, 0.0F) - Math.PI * 0.5D, 0.0D);
        a.check("angle finite", Math.abs(PvpLogic.screenAngle(1.0D, 1.0D, 37.0F)) <= Math.PI + eps);
    }

    private static void testProject(ModuleTestRunner.Assertions a) {
        float[] center = PvpLogic.project(0.0D, 0.0D, -10.0D, 0.0F, 0.0F, 70.0F, 800, 600);
        a.check("project center exists", center != null);
        if (center != null) {
            a.eq("project center x", (double) center[0], 400.0D);
            a.eq("project center y", (double) center[1], 300.0D);
        }

        a.eq("project behind null", PvpLogic.project(0.0D, 0.0D, 10.0D, 0.0F, 0.0F, 70.0F, 800, 600), null);
        a.eq("project invalid size null", PvpLogic.project(0.0D, 0.0D, -10.0D, 0.0F, 0.0F, 70.0F, 0, 600), null);

        float[] right = PvpLogic.project(1.0D, 0.0D, -10.0D, 0.0F, 0.0F, 70.0F, 800, 600);
        a.check("project right exists", right != null);
        if (right != null) {
            a.check("project right side", right[0] > 400.0F);
            a.check("project right vertical", Math.abs(right[1] - 300.0F) < 1.0E-3F);
        }

        float[] up = PvpLogic.project(0.0D, 1.0D, -10.0D, 0.0F, 0.0F, 70.0F, 800, 600);
        a.check("project up exists", up != null);
        if (up != null) {
            a.check("project up above center", up[1] < 300.0F);
        }

        // 低头 45° 时，正前下方 45° 的目标仍在屏幕中心
        float[] pitched = PvpLogic.project(0.0D, -10.0D, -10.0D, 0.0F, 45.0F, 70.0F, 800, 600);
        a.check("project pitched center exists", pitched != null);
        if (pitched != null) {
            a.eq("project pitched x", (double) pitched[0], 400.0D);
            a.eq("project pitched y", (double) pitched[1], 300.0D);
        }
    }

    private static void testMath(ModuleTestRunner.Assertions a) {
        boolean inRange = true;
        for (int i = 0; i <= 2000; i++) {
            float p = PvpLogic.pulse(i, 1.5F);
            if (p < 0.5F - 1.0E-5F || p > 1.0F + 1.0E-5F) {
                inRange = false;
            }
        }
        a.check("pulse in range", inRange);
        a.eq("clamp low", PvpLogic.clamp01(-1.0F), 0.0F);
        a.eq("clamp high", PvpLogic.clamp01(2.0F), 1.0F);
        a.eq("clamp mid", PvpLogic.clamp01(0.25F), 0.25F);
    }

    private static void testPlacement(ModuleTestRunner.Assertions a) {
        a.eq("placement valid", PvpLogic.placementValid(true, true, false, 3.0D, 4.5D), true);
        a.eq("placement not replaceable", PvpLogic.placementValid(false, true, false, 3.0D, 4.5D), false);
        a.eq("placement blocked", PvpLogic.placementValid(true, true, true, 3.0D, 4.5D), false);
        a.eq("placement out of reach", PvpLogic.placementValid(true, true, false, 5.0D, 4.5D), false);
        a.eq("placement cannot place", PvpLogic.placementValid(true, false, false, 3.0D, 4.5D), false);

        // 最近点距离：盒内 / 贴面为 0，单轴外为轴差，角外为三维斜边
        a.eq("box inside", PvpLogic.distanceToBox(0.5D, 0.5D, 0.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), 0.0D);
        a.eq("box on face", PvpLogic.distanceToBox(1.0D, 0.5D, 0.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), 0.0D);
        a.eq("box one axis out", PvpLogic.distanceToBox(2.0D, 0.5D, 0.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), 1.0D);
        a.eq("box negative axis out", PvpLogic.distanceToBox(-2.0D, 0.5D, 0.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), 2.0D);
        a.eq("box corner out", PvpLogic.distanceToBox(2.0D, 2.0D, 2.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                Math.sqrt(3.0D));
        // 中心超距但贴边在触及范围内：应当判为可放置
        double near = PvpLogic.distanceToBox(0.0D, 1.5D, 0.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        a.eq("box near distance", near, 0.5D);
        a.eq("placement edge in reach", PvpLogic.placementValid(true, true, false, near, 4.5D), true);
    }
}
