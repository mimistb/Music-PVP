package fpsport.pvp;

/**
 * PVP 增强的纯逻辑：连击计时、击退方向、3D → 屏幕投影、脉动、放置校验。
 *
 * <p>本类刻意不 import 任何 Minecraft / 渲染类型，方便用 {@code javac} 在无头环境直接跑单测。
 * 所有坐标系的约定都写在各方法注释里，功能类只负责取数据与画图。
 */
public final class PvpLogic {

    private PvpLogic() {
    }

    // ---------------------------------------------------------------- 连击

    /**
     * 连击状态机。
     *
     * <p>窗口语义：距上一次命中超过 {@code windowMs} 就视为脱战，下一次命中从 1 重新开始。
     * 时间由宿主的毫秒时钟传入（通常是 {@code System.currentTimeMillis()}），本类不做取时。
     */
    public static final class ComboTracker {

        /** 当前连击数；0 表示没有连击。 */
        public int count;
        /** 本次连击第一次命中的时间戳。 */
        public long firstMs;
        /** 本次连击最后一次命中的时间戳。 */
        public long lastMs;

        private long windowMs;

        public ComboTracker(long windowMs) {
            this.windowMs = Math.max(1L, windowMs);
        }

        public void setWindow(long windowMs) {
            this.windowMs = Math.max(1L, windowMs);
        }

        public long windowMs() {
            return windowMs;
        }

        /** 记录一次命中；返回是否为连击（第 2 次及以后）。 */
        public boolean hit(long now) {
            if (count <= 0 || now - lastMs > windowMs) {
                count = 0;
                firstMs = now;
            }
            count++;
            lastMs = now;
            return count > 1;
        }

        public boolean isActive(long now) {
            return count > 0 && now - lastMs <= windowMs;
        }

        /** 距离脱战还剩多少毫秒，用于淡出。 */
        public long remainingMs(long now) {
            if (count <= 0) {
                return 0L;
            }
            long left = windowMs - (now - lastMs);
            return left < 0L ? 0L : left;
        }

        /** 连击持续时间：从第一次命中到现在；不活跃时为 0。 */
        public long durationMs(long now) {
            if (!isActive(now)) {
                return 0L;
            }
            long d = now - firstMs;
            return d < 0L ? 0L : d;
        }

        public void reset() {
            count = 0;
            firstMs = 0L;
            lastMs = 0L;
        }
    }

    // ---------------------------------------------------------------- 方向 / 投影

    /**
     * 把世界水平方向转成屏幕角度。
     *
     * @param dx    击退方向 X（不需要归一化）
     * @param dz    击退方向 Z
     * @param yawDeg 玩家朝向 yaw（Minecraft 角度：0 = 朝向 -Z）
     * @return 弧度：0 = 屏幕上方（正前方），顺时针为正（右侧 / 后方依次增大）
     */
    public static double screenAngle(double dx, double dz, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // MC 坐标：yaw=0 看向 -Z；右手方向 +X。
        double right = dx * cos - dz * sin;
        double forward = -dx * sin - dz * cos;
        return Math.atan2(right, forward);
    }

    /**
     * 世界相对坐标 → 屏幕像素。
     *
     * @param relX     目标相对相机的 X
     * @param relY     目标相对相机的 Y
     * @param relZ     目标相对相机的 Z
     * @param yawDeg   相机 yaw
     * @param pitchDeg 相机 pitch（向下为正）
     * @param fovY     纵向视野角（度）
     * @param width    屏幕宽（GUI 像素）
     * @param height   屏幕高（GUI 像素）
     * @return {x, y}；目标在相机后方 / 视野边界外返回 {@code null}
     */
    public static float[] project(double relX, double relY, double relZ,
                                  float yawDeg, float pitchDeg, float fovY,
                                  int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double sinY = Math.sin(yaw);
        double cosY = Math.cos(yaw);
        double sinP = Math.sin(pitch);
        double cosP = Math.cos(pitch);

        // 相机基向量：right / up / forward（forward 为可见方向）
        double rx = cosY, ry = 0.0D, rz = -sinY;
        double fx = -sinY * cosP, fy = -sinP, fz = -cosY * cosP;
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        double camX = relX * rx + relZ * rz;
        double camY = relX * ux + relY * uy + relZ * uz;
        double camZ = relX * fx + relY * fy + relZ * fz;

        if (camZ <= 0.05D) {
            return null;
        }
        double tan = Math.tan(Math.toRadians(Math.max(1.0F, fovY)) * 0.5D);
        double aspect = (double) width / (double) height;
        double ndcX = camX / (camZ * tan * aspect);
        double ndcY = camY / (camZ * tan);
        float sx = (float) ((ndcX * 0.5D + 0.5D) * width);
        float sy = (float) ((0.5D - ndcY * 0.5D) * height);
        return new float[]{sx, sy};
    }

    // ---------------------------------------------------------------- 通用数学

    /** 0.5 ~ 1.0 的正弦脉动，0.5 为谷、1.0 为峰。 */
    public static float pulse(long timeMs, float speed) {
        double phase = (Math.abs(timeMs) % 1000000L) / 1000.0D * Math.max(0.0F, speed) * Math.PI * 2.0D;
        return (float) (0.75D + 0.25D * Math.sin(phase));
    }

    /**
     * 点到轴对齐包围盒的最近距离；点在盒内返回 0。
     *
     * <p>用于放置距离判定：原版允许贴边放置，按包围盒中心算距离会把合法放置误判成超距。
     */
    public static double distanceToBox(double px, double py, double pz,
                                       double x0, double y0, double z0,
                                       double x1, double y1, double z1) {
        double dx = axisDistance(px, x0, x1);
        double dy = axisDistance(py, y0, y1);
        double dz = axisDistance(pz, z0, z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    public static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    // ---------------------------------------------------------------- 放置检测

    /** 放置结果：必须可替换、方块允许放置、没有实体碰撞、且在触及距离内。 */
    public static boolean placementValid(boolean replaceable, boolean canPlace,
                                         boolean entityBlocked, double distance, double reach) {
        return replaceable && canPlace && !entityBlocked && distance <= reach;
    }
}
