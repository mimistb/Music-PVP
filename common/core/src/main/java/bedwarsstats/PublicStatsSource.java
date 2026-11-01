package bedwarsstats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家统计数据源聚合。
 *
 * <p>玩家 UUID 通过 Minecraft Services / Mojang / PlayerDB 三个公开身份服务解析；
 * Bed Wars 等统计数据按以下优先级获取，任何一个可用就返回：
 * <ol>
 *   <li>用户填了官方 API Key → {@code api.hypixel.net/v2/player}（可选的官方 Key 路径）</li>
 *   <li>用户填了第三方 Key → {@code netherapi.com/api/v2/player}（Hypixel 兼容格式）</li>
 *   <li>未填 Key 或上面的源失败 → Slothpixel → Plancke（免 Key 公开接口）</li>
 * </ol>
 *
 * <p>第三方接口的响应格式不完全统一，这里做兼容解析：
 * <ul>
 *   <li>Hypixel 兼容格式（官方 / NetherAPI / Plancke）：{@code success / player.displayname /
 *       player.stats.Bedwars / player.achievements.bedwars_level / player.networkExp}</li>
 *   <li>Slothpixel 格式：{@code username / stats.BedWars / level / achievements}</li>
 * </ul>
 *
 * <p>所有方法都是同步阻塞的，调用方必须放在自己的后台线程里。
 * 不包含任何 UI 或 Minecraft 依赖。
 */
public final class PublicStatsSource {

    public static final int MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024;
    public static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    private static final String USER_AGENT = "DangoMusic/1.9.1 (public-stats)";

    /** 数据源整体不可用时的冷却时间：避免每个玩家都重复撞同一个挂掉的站。 */
    private static final long PROVIDER_COOLDOWN_MILLIS = 60_000L;

    /** 身份解析：三个都试一遍，第一个成功的返回。 */
    private static final String[] IDENTITY_URLS = {
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/",
            "https://api.mojang.com/users/profiles/minecraft/",
            "https://playerdb.co/api/player/minecraft/",
    };
    private static final String[] IDENTITY_NAMES = {
            "Minecraft Services",
            "Mojang",
            "PlayerDB",
    };

    /** 需要 Key 的统计来源（Key 由设置页提供）。 */
    private static final String OFFICIAL_URL = "https://api.hypixel.net/v2/player?uuid=";
    private static final String OFFICIAL_NAME = "Hypixel 官方";
    private static final String NETHER_URL = "https://netherapi.com/api/v2/player?uuid=";
    private static final String NETHER_NAME = "NetherAPI";

    /** 统计来源：免 Key 的第三方公开接口。顺序即优先级。 */
    private static final String[] PLAYER_URLS = {
            "https://api.slothpixel.me/api/players/",
            "https://api.plancke.io/hypixel/v1/player?player=",
    };
    private static final String[] PLAYER_NAMES = {
            "Slothpixel",
            "Plancke",
    };

    private static final AtomicLong OFFICIAL_COOLDOWN_UNTIL = new AtomicLong(0L);
    private static final AtomicLong NETHER_COOLDOWN_UNTIL = new AtomicLong(0L);

    private static final AtomicLong[] IDENTITY_COOLDOWN_UNTIL =
            new AtomicLong[IDENTITY_URLS.length];
    private static final AtomicLong[] PLAYER_COOLDOWN_UNTIL =
            new AtomicLong[PLAYER_URLS.length];

    static {
        for (int i = 0; i < IDENTITY_COOLDOWN_UNTIL.length; i++) {
            IDENTITY_COOLDOWN_UNTIL[i] = new AtomicLong(0L);
        }
        for (int i = 0; i < PLAYER_COOLDOWN_UNTIL.length; i++) {
            PLAYER_COOLDOWN_UNTIL[i] = new AtomicLong(0L);
        }
    }

    private PublicStatsSource() {
    }

    /** 身份解析结果：uuid / 名字 / 错误（三者中只有一个错误非空）。 */
    public static final class Identity {
        public final String uuid;
        public final String name;
        public final String error;

        private Identity(String uuid, String name, String error) {
            this.uuid = uuid == null ? "" : uuid;
            this.name = name == null ? "" : name;
            this.error = error;
        }

        public boolean ok() {
            return error == null && !uuid.isEmpty();
        }
    }

    /** 统计文档抓取结果：provider 是实际成功的来源名。 */
    public static final class Fetch {
        public final String provider;
        public final String body;
        public final String error;
        public final BedWarsHttp.Response response;

        private Fetch(String provider, String body, String error, BedWarsHttp.Response response) {
            this.provider = provider == null ? "" : provider;
            this.body = body == null ? "" : body;
            this.error = error;
            this.response = response;
        }

        public boolean ok() {
            return error == null && !body.isEmpty();
        }
    }

    /** 解析结果：stats / error 互斥。 */
    public static final class Parse {
        public final BedWarsStats stats;
        public final String error;

        private Parse(BedWarsStats stats, String error) {
            this.stats = stats;
            this.error = error;
        }

        public boolean ok() {
            return stats != null && error == null;
        }
    }

    // ------------------------------------------------------------------
    // 身份解析
    // ------------------------------------------------------------------

    /** 输入玩家名或 UUID，返回 UUID。名字会依次尝试三个公开身份服务。 */
    public static Identity resolve(BedWarsHttp.Transport transport, String input) {
        final String requested = input == null ? "" : input.trim();
        if (requested.isEmpty()) {
            return new Identity("", "", "请输入玩家名或 UUID");
        }
        final String normalized = HypixelJson.normalizeUuid(requested);
        if (!normalized.isEmpty()) {
            return new Identity(normalized, "", null);
        }
        if (!HypixelJson.isValidPlayerName(requested)) {
            return new Identity("", "", "不是有效的 Minecraft 玩家名： " + requested);
        }

        final BedWarsHttp.Transport http = transport == null ? BedWarsHttp.DEFAULT : transport;
        final long now = System.currentTimeMillis();
        String lastError = "";
        for (int i = 0; i < IDENTITY_URLS.length; i++) {
            if (IDENTITY_COOLDOWN_UNTIL[i].get() > now) {
                lastError = IDENTITY_NAMES[i] + ": 暂时跳过（最近请求失败）";
                continue;
            }
            final BedWarsHttp.Response response =
                    request(http, IDENTITY_URLS[i] + encode(requested));
            if (!response.ok()) {
                // 404 / 204 是“玩家不存在”的权威回答，不必再撞下一个站。
                if (response.status == 404 || response.status == 204) {
                    return new Identity("", "", "找不到名为 " + requested + " 的玩家");
                }
                lastError = IDENTITY_NAMES[i] + ": " + describe(response);
                if (shouldCooldown(response)) {
                    IDENTITY_COOLDOWN_UNTIL[i].set(now + PROVIDER_COOLDOWN_MILLIS);
                }
                continue;
            }
            if (response.status == 204) {
                // Mojang 对不存在的名字返回 204 No Content。
                return new Identity("", "", "找不到名为 " + requested + " 的玩家");
            }
            final String uuid = uuidFromIdentityBody(IDENTITY_URLS[i], response.body);
            if (!uuid.isEmpty()) {
                IDENTITY_COOLDOWN_UNTIL[i].set(0L);
                return new Identity(uuid, requested, null);
            }
            lastError = IDENTITY_NAMES[i] + ": 返回内容里没有有效的 UUID";
        }
        return new Identity("", "", lastError.isEmpty() ? "所有身份服务都不可用" : lastError);
    }

    private static String uuidFromIdentityBody(String url, String body) {
        if (url.contains("playerdb.co")) {
            final JsonObject root = HypixelJson.parseObject(body);
            final JsonObject data = HypixelJson.objectField(root, "data");
            final JsonObject player = HypixelJson.objectField(data, "player");
            final String raw = HypixelJson.stringField(player, "raw_id",
                    HypixelJson.stringField(player, "id", ""));
            return HypixelJson.normalizeUuid(raw);
        }
        return HypixelJson.uuidFromIdentityDocument(body);
    }

    // ------------------------------------------------------------------
    // 统计抓取
    // ------------------------------------------------------------------

    /** 按顺序请求所有免 Key 数据源，返回第一个有效文档。 */
    public static Fetch fetch(BedWarsHttp.Transport transport, String uuid) {
        return fetch(transport, uuid, "", "");
    }

    /**
     * 带可选 Key 的抓取：官方 Key → 第三方 Key → 免 Key 公开源，逐级回退。
     * Key 为空时跳过对应来源，所以不填 Key 时行为与原来的免 Key 路径完全一致。
     */
    public static Fetch fetch(BedWarsHttp.Transport transport, String uuid,
                              String officialKey, String netherKey) {
        final String normalized = HypixelJson.normalizeUuid(uuid);
        if (normalized.isEmpty()) {
            return new Fetch("", "", "无效的玩家 UUID", null);
        }
        final BedWarsHttp.Transport http = transport == null ? BedWarsHttp.DEFAULT : transport;
        final long now = System.currentTimeMillis();
        String lastError = "";

        final String official = officialKey == null ? "" : officialKey.trim();
        if (!official.isEmpty()) {
            if (OFFICIAL_COOLDOWN_UNTIL.get() > now) {
                lastError = OFFICIAL_NAME + ": 暂时跳过（最近请求失败）";
            } else {
                final BedWarsHttp.Response response =
                        request(http, OFFICIAL_URL + encode(normalized), official);
                if (response.ok()) {
                    final String documentError = documentError(response.body);
                    if (documentError == null) {
                        OFFICIAL_COOLDOWN_UNTIL.set(0L);
                        return new Fetch(OFFICIAL_NAME, response.body, null, response);
                    }
                    lastError = OFFICIAL_NAME + ": " + documentError;
                } else {
                    lastError = OFFICIAL_NAME + ": " + describe(response);
                    if (shouldCooldown(response)) {
                        OFFICIAL_COOLDOWN_UNTIL.set(now + PROVIDER_COOLDOWN_MILLIS);
                    }
                }
            }
        }

        final String nether = netherKey == null ? "" : netherKey.trim();
        if (!nether.isEmpty()) {
            if (NETHER_COOLDOWN_UNTIL.get() > now) {
                lastError = NETHER_NAME + ": 暂时跳过（最近请求失败）";
            } else {
                final BedWarsHttp.Response response =
                        request(http, NETHER_URL + encode(normalized), nether);
                if (response.ok()) {
                    final String documentError = documentError(response.body);
                    if (documentError == null) {
                        NETHER_COOLDOWN_UNTIL.set(0L);
                        return new Fetch(NETHER_NAME, response.body, null, response);
                    }
                    lastError = NETHER_NAME + ": " + documentError;
                } else {
                    lastError = NETHER_NAME + ": " + describe(response);
                    if (shouldCooldown(response)) {
                        NETHER_COOLDOWN_UNTIL.set(now + PROVIDER_COOLDOWN_MILLIS);
                    }
                }
            }
        }

        for (int i = 0; i < PLAYER_URLS.length; i++) {
            if (PLAYER_COOLDOWN_UNTIL[i].get() > now) {
                lastError = PLAYER_NAMES[i] + ": 暂时跳过（最近请求失败）";
                continue;
            }
            final BedWarsHttp.Response response =
                    request(http, PLAYER_URLS[i] + encode(normalized));
            if (!response.ok()) {
                lastError = PLAYER_NAMES[i] + ": " + describe(response);
                if (shouldCooldown(response)) {
                    PLAYER_COOLDOWN_UNTIL[i].set(now + PROVIDER_COOLDOWN_MILLIS);
                }
                continue;
            }
            final String documentError = documentError(response.body);
            if (documentError == null) {
                PLAYER_COOLDOWN_UNTIL[i].set(0L);
                return new Fetch(PLAYER_NAMES[i], response.body, null, response);
            }
            lastError = PLAYER_NAMES[i] + ": " + documentError;
        }
        return new Fetch("", "",
                lastError.isEmpty() ? "公开数据源暂时不可用" : lastError, null);
    }

    /**
     * 包一层 transport：任何运行时异常都转成带错误描述的响应，绝不让网络层异常
     * 冒泡到调用方的线程。
     */
    private static BedWarsHttp.Response request(BedWarsHttp.Transport http, String url) {
        return request(http, url, null);
    }

    /** apiKey 为空时不会带 API-Key 头；官方与 NetherAPI 都接受 API-Key。 */
    private static BedWarsHttp.Response request(BedWarsHttp.Transport http, String url,
                                                String apiKey) {
        try {
            final BedWarsHttp.Response response = http.get(
                    url, apiKey, USER_AGENT, MAXIMUM_RESPONSE_BYTES, REQUEST_TIMEOUT_MILLIS);
            if (response == null) {
                return new BedWarsHttp.Response(-1, "", false, "传输层返回空结果", -1, -1, -1);
            }
            return response;
        } catch (RuntimeException error) {
            return new BedWarsHttp.Response(-1, "", false, "网络异常: " + error, -1, -1, -1);
        }
    }

    /** 只有“站点本身不可用”才进入冷却；玩家不存在之类的业务错误不冷却。 */
    private static boolean shouldCooldown(BedWarsHttp.Response response) {
        if (response.networkError != null) return true;
        final int status = response.status;
        return status == 401 || status == 403 || status == 408
                || status == 429 || status >= 500;
    }

    /** 文档是否可用于解析；可用返回 null，否则返回原因。 */
    private static String documentError(String body) {
        final JsonObject root = HypixelJson.parseObject(body);
        if (root == null) return "返回内容不是有效 JSON";
        final JsonElement playerElement = root.get("player");
        if (playerElement != null && playerElement.isJsonObject()) {
            if (!HypixelJson.booleanField(root, "success")) {
                final String cause = HypixelJson.stringField(root, "cause", "");
                return cause.isEmpty() ? "数据源报告查询失败" : cause;
            }
            return null;
        }
        if (playerElement != null && !playerElement.isJsonNull()) {
            return "player 字段格式错误";
        }
        // Slothpixel 形状：顶层就带 stats / username / uuid
        if (root.has("stats") || root.has("username") || root.has("uuid")) return null;
        return "返回内容缺少玩家数据";
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把数据源文档解析成 {@link BedWarsStats}。
     *
     * <p>兼容 Hypixel 兼容格式与 Slothpixel 格式；缺失字段按 0 处理，
     * 不会因为单个字段类型异常而整条失败。
     */
    public static Parse parse(String document, String uuid, String fallbackName, long nowMillis) {
        final JsonObject root = HypixelJson.parseObject(document);
        if (root == null) {
            return new Parse(null, "数据源返回了无法解析的内容");
        }

        final JsonObject nestedPlayer = HypixelJson.objectField(root, "player");
        final boolean nested = nestedPlayer.entrySet().size() > 0;
        // success:false 无论有没有 player 字段都是明确的失败，不能当成全 0 统计返回。
        if (root.has("success") && !HypixelJson.booleanField(root, "success")) {
            final String cause = HypixelJson.stringField(root, "cause", "");
            return new Parse(null, cause.isEmpty() ? "数据源报告查询失败" : cause);
        }
        final JsonObject player = nested ? nestedPlayer : root;

        final JsonObject stats = HypixelJson.objectField(player, "stats");
        JsonObject bedWars = pickObject(stats, "Bedwars", "BedWars", "bedwars", "BEDWARS");
        if (bedWars.entrySet().isEmpty() && !nested) {
            bedWars = pickObject(HypixelJson.objectField(root, "stats"), "Bedwars", "BedWars");
        }

        JsonObject achievements = HypixelJson.objectField(player, "achievements");
        if (achievements.entrySet().isEmpty()) {
            achievements = HypixelJson.objectField(root, "achievements");
        }

        final String displayName = HypixelJson.stringField(player, "displayname",
                HypixelJson.stringField(player, "username",
                        HypixelJson.stringField(root, "username", fallbackName)));

        final long wins = atLeastZero(HypixelJson.integerField(bedWars, "wins_bedwars"));
        final long losses = atLeastZero(HypixelJson.integerField(bedWars, "losses_bedwars"));
        final long finalKills = atLeastZero(HypixelJson.integerField(bedWars, "final_kills_bedwars"));
        final long finalDeaths = atLeastZero(HypixelJson.integerField(bedWars, "final_deaths_bedwars"));
        final long bedsBroken = atLeastZero(HypixelJson.integerField(bedWars, "beds_broken_bedwars"));
        final long bedsLost = atLeastZero(HypixelJson.integerField(bedWars, "beds_lost_bedwars"));

        int stars = (int) HypixelJson.integerField(achievements, "bedwars_level");
        if (stars <= 0) stars = (int) HypixelJson.integerField(bedWars, "bedwars_level");
        final int winStreak = (int) HypixelJson.integerField(bedWars, "winstreak");

        int networkLevel = (int) HypixelJson.integerField(player, "networkLevel");
        if (networkLevel <= 0) {
            final double level = HypixelJson.doubleField(player, "level", 0.0);
            if (level > 0) networkLevel = (int) Math.floor(level);
        }
        if (networkLevel <= 0) {
            double experience = HypixelJson.doubleField(player, "networkExp", 0.0);
            if (experience <= 0) experience = HypixelJson.doubleField(root, "networkExp", 0.0);
            if (experience > 0) networkLevel = HypixelJson.networkLevel(experience);
        }

        final String resolvedUuid = HypixelJson.normalizeUuid(uuid);
        final BedWarsStats parsed = new BedWarsStats(
                resolvedUuid.isEmpty() ? uuid : resolvedUuid,
                displayName,
                wins, losses,
                finalKills, finalDeaths,
                bedsBroken, bedsLost,
                clamp(stars, 0, 100000),
                clamp(winStreak, 0, 1_000_000),
                clamp(networkLevel, 0, 100000),
                nowMillis > 0 ? nowMillis : System.currentTimeMillis());
        return new Parse(parsed, null);
    }

    private static JsonObject pickObject(JsonObject source, String... names) {
        for (String name : names) {
            final JsonObject value = HypixelJson.objectField(source, name);
            if (value.entrySet().size() > 0) return value;
        }
        return new JsonObject();
    }

    private static long atLeastZero(long value) {
        return Math.max(0L, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String describe(BedWarsHttp.Response response) {
        if (response.networkError != null) return response.networkError;
        return "HTTP " + response.status;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return value;
        }
    }
}
