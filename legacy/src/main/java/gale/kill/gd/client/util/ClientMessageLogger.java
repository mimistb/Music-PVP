package gale.kill.gd.client.util;

import java.util.Arrays;

/**
 * 独立化：原实现写 Gale 的日志系统，这里改为 stderr，去掉对 {@code gale.Gale} 的依赖。
 */
public final class ClientMessageLogger {
    public static void chatWarn(String key, Object... args) {
        System.err.println("[GD656] " + key + " " + Arrays.toString(args));
    }
}
