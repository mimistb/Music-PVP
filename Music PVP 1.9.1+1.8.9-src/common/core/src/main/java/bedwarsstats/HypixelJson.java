package bedwarsstats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hypixel / Minecraft Services JSON 解析辅助。使用 1.8.9 自带的 Gson 2.2.4
 * （new JsonParser().parse(String)），不依赖任何新版本 API。
 */
public final class HypixelJson {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9A-Fa-f]{32}$");
    private static final Pattern PLAYER_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private HypixelJson() {
    }

    /** 解析失败返回 null。 */
    public static JsonObject parseObject(String document) {
        if (document == null || document.isEmpty()) return null;
        try {
            final JsonElement root = new JsonParser().parse(document);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (JsonParseException error) {
            return null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static JsonObject objectField(JsonObject object, String name) {
        if (object == null) return new JsonObject();
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return new JsonObject();
        }
        return element.getAsJsonObject();
    }

    /** 数字或数字字符串 -> long；其余情况返回 0。 */
    public static long integerField(JsonObject object, String name) {
        if (object == null) return 0L;
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return 0L;
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            final double number = primitive.getAsDouble();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) return (long) number;
        } else if (primitive.isString()) {
            try {
                return Long.parseLong(primitive.getAsString());
            } catch (NumberFormatException error) {
                return 0L;
            }
        }
        return 0L;
    }

    public static double doubleField(JsonObject object, String name, double fallback) {
        if (object == null) return fallback;
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        final double number;
        if (primitive.isNumber()) {
            number = primitive.getAsDouble();
        } else if (primitive.isString()) {
            try {
                number = Double.parseDouble(primitive.getAsString());
            } catch (NumberFormatException error) {
                return fallback;
            }
        } else {
            return fallback;
        }
        return Double.isNaN(number) || Double.isInfinite(number) ? fallback : number;
    }

    public static boolean booleanField(JsonObject object, String name) {
        if (object == null) return false;
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return false;
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isString()) return Boolean.parseBoolean(primitive.getAsString());
        return false;
    }

    public static String stringField(JsonObject object, String name, String fallback) {
        if (object == null) return fallback;
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        return element.getAsString();
    }

    /** 去掉 '-' 并校验 32 位十六进制；非法返回空字符串（与源码 normalizeUuid 一致）。 */
    public static String normalizeUuid(String uuid) {
        if (uuid == null) return "";
        final String normalized = uuid.trim().replace("-", "");
        return UUID_PATTERN.matcher(normalized).matches()
                ? normalized.toLowerCase(Locale.ROOT) : "";
    }

    public static boolean isValidPlayerName(String name) {
        return name != null && PLAYER_NAME_PATTERN.matcher(name).matches();
    }

    /** networkExp -> 网络等级（Hypixel 公式，与源码一致）。 */
    public static int networkLevel(double experience) {
        if (Double.isNaN(experience) || Double.isInfinite(experience) || experience <= 0.0) {
            return 1;
        }
        return Math.max(1, (int) Math.floor(
                (Math.sqrt(2.0 * experience + 30625.0) / 50.0) - 2.5));
    }

    /** Minecraft Services 身份响应里的 "id"，非法返回空字符串。 */
    public static String uuidFromIdentityDocument(String document) {
        return normalizeUuid(stringField(parseObject(document), "id", ""));
    }

    /** Hypixel 错误响应里的 cause；缺失返回空字符串。 */
    public static String cause(String document) {
        return stringField(parseObject(document), "cause", "");
    }
}
