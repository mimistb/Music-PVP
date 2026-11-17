package fpsport.chattranslate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * 聊天翻译的多线路客户端（无需 API Key）。
 *
 * <p>线路顺序：Bing 网页翻译 → Google gtx → MyMemory。
 * <ul>
 *   <li>Bing：GET 翻译页拿 IG / IID / abuse 令牌，再 POST ttranslatev3；国内可直连，质量最好；</li>
 *   <li>Google gtx：海外网络下最快，国内通常被拦截，所以放在第二；</li>
 *   <li>MyMemory：通用兜底，必须带 de 参数（不带会稳定 429 限流，实测如此）。</li>
 * </ul>
 *
 * <p>每条线路都有：2 次尝试 + 退避重试、失败熔断（连续失败冷却 10s 起，最长 60s）、
 * 全局 15 秒预算（弱网下不会把后台线程卡死）。全部失败才抛异常。
 *
 * <p>关于“跨域”：这是 Java 客户端（HttpURLConnection），不存在浏览器的同源策略 / CORS，
 * 真正影响连通性的只有 DNS、TLS、系统代理和运营商拦截。
 *
 * <p>注意：{@code translate(...)} 是阻塞调用，不要在客户端主线程直接调。
 */
public final class Translator {

    public static final String AUTO_DETECT = "";
    public static final String ENGLISH = "en";
    public static final String JAPANESE = "ja";
    public static final String CHINESE_SIMPLIFIED = "zh-Hans";
    public static final String CHINESE_TRADITIONAL = "zh-Hant";
    public static final String POLISH = "pl";

    private static final String BING_PATH = "/translator";
    /** 国内优先 cn.bing.com，海外/国内都可用 */
    private static final String[] BING_HOSTS = {"https://cn.bing.com", "https://www.bing.com"};
    private static final String GOOGLE_URL = "https://translate.googleapis.com/translate_a/single";
    private static final String MEMORY_URL = "https://api.mymemory.translated.net/get";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 单条线路的总预算：超过就放弃，避免弱网时后台队列越积越深 */
    private static final long TOTAL_BUDGET_MS = 15000L;
    /**
     * 单次尝试的预算：一条线路卡住不能把整条链路的预算吃光，
     * 否则后面的兜底线路根本没机会被尝试。
     */
    private static final long ATTEMPT_BUDGET_MS = 6000L;
    /** 剩余预算低于这个值就不再发起新的尝试 */
    private static final long MIN_ATTEMPT_MS = 600L;
    /** 每条线路最多尝试几次（含第一次） */
    private static final int ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 250L;

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 7000;

    /** 翻译结果缓存条数：聊天里重复出现的句子（gg / 常见提示）不再走网络。 */
    private static final int CACHE_MAX = 128;
    /** MyMemory 单次 GET 的 q 上限约 500 字节，这里留一点余量。 */
    private static final int MEMORY_MAX_BYTES = 450;
    /**
     * MyMemory 的 de 参数用于统计调用方配额；留空会被当成匿名调用直接 429。
     * 用保留域名 example.com，不指向真实邮箱。
     */
    private static final String MEMORY_CONTACT = "dango-music@example.com";

    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    // ---------------------------------------------------------------- Bing 会话

    private static volatile String bingHost = BING_HOSTS[0];
    private static volatile String bingKey;
    private static volatile String bingToken;
    private static volatile String bingIg;
    private static volatile String bingIid;
    private static volatile String bingCookie;
    private static volatile long bingTokenAt = -1L;
    /** Bing 令牌自带 1 小时有效期，这里 25 分钟就换一次 */
    private static final long BING_TTL_MS = 25L * 60 * 1000;

    // ---------------------------------------------------------------- 熔断

    private static final Map<String, Breaker> BREAKERS = new ConcurrentHashMap<String, Breaker>();

    /**
     * 线路请求跑在这个池子里，外层用 Future.get(剩余预算) 兜底。
     *
     * <p>只靠 socket 的 connect/read 超时不够：走代理时 JDK 内部会自己重试，
     * 实测能把 4 秒的连接超时拖成 15 秒，直接把总预算冲破。
     */
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "Dango-Translate");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Breaker {
        int failures;
        int strikes;
        long blockedUntil;
    }

    private Translator() {
    }

    /** 一条翻译线路 */
    private interface Line {
        String name();

        String translate(String text, String source, String target, long deadline) throws IOException;
    }

    private static final Line[] LINES = new Line[]{
            new Line() {
                @Override
                public String name() {
                    return "Bing";
                }

                @Override
                public String translate(String text, String source, String target, long deadline)
                        throws IOException {
                    return translateBing(text, source, target, deadline);
                }
            },
            new Line() {
                @Override
                public String name() {
                    return "Google";
                }

                @Override
                public String translate(String text, String source, String target, long deadline)
                        throws IOException {
                    return translateGoogle(text, source, target, deadline);
                }
            },
            new Line() {
                @Override
                public String name() {
                    return "MyMemory";
                }

                @Override
                public String translate(String text, String source, String target, long deadline)
                        throws IOException {
                    return translateMemory(text, source, target, deadline);
                }
            },
    };

    /**
     * 同步翻译。所有线路都失败会抛出 IOException（带中文原因），
     * 调用方可用 {@link #hint(Throwable)} 转成给用户看的提示。
     *
     * @param text 待翻译文本
     * @param from 源语言，传 {@link #AUTO_DETECT} 表示自动识别
     * @param to   目标语言
     */
    public static String translate(String text, String from, String to) throws IOException {
        String sourceText = text == null ? "" : text.trim();
        if (sourceText.isEmpty()) {
            return "";
        }
        String target = normalize(to);
        String source = normalizeSource(from);
        if (source.isEmpty()) {
            source = detectSource(sourceText);
        }
        // 源语言和目标语言一致时没有翻译的必要，直接原样返回
        if (sameLanguage(source, target)) {
            return sourceText;
        }

        String cacheKey = target + '\u0001' + sourceText;
        String cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        IOException lastError = null;
        // 所有线路都在冷却：直接失败，不要再让玩家等十几秒（冷却最长 60 秒，之后自动恢复）
        boolean anyAvailable = false;
        for (Line line : LINES) {
            if (!isBlocked(line.name())) {
                anyAvailable = true;
                break;
            }
        }
        if (!anyAvailable) {
            throw new IOException("翻译线路均处于冷却中，请稍后重试");
        }

        for (Line line : LINES) {
            if (remaining(deadline) < MIN_ATTEMPT_MS) {
                break;
            }
            if (isBlocked(line.name())) {
                continue;
            }
            IOException lineError = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                if (remaining(deadline) < MIN_ATTEMPT_MS) {
                    break;
                }
                try {
                    long attemptDeadline = Math.min(deadline, System.currentTimeMillis() + ATTEMPT_BUDGET_MS);
                    String result = runBounded(line, sourceText, source, target, attemptDeadline);
                    if (result != null && !result.trim().isEmpty()) {
                        onSuccess(line.name());
                        CACHE.put(cacheKey, result);
                        return result;
                    }
                    lineError = new IOException(line.name() + " 返回空结果");
                    onFailure(line.name());
                } catch (Throwable t) {
                    lineError = asIOException(line.name() + " 翻译失败", t);
                    // 按「尝试」计失败：一次对话就把线路连续试两次都失败，说明这条线路现在确实不通
                    onFailure(line.name());
                    if (!retryable(lineError)) {
                        break;
                    }
                    if (attempt + 1 < ATTEMPTS) {
                        sleep(RETRY_BACKOFF_MS * (attempt + 1));
                    }
                }
            }
            if (lineError != null) {
                lastError = lineError;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("所有翻译线路均不可用（网络不通或已被拦截）");
    }

    /** 失败返回 null，不抛异常；用于“翻译失败就保持原文”的场景。 */
    public static String translateQuietly(String text, String from, String to) {
        try {
            return translate(text, from, to);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在 NETWORK 池里执行单条线路，并保证等待时间不超过剩余预算。 */
    private static String runBounded(final Line line, final String text, final String source,
                                     final String target, final long deadline) throws IOException {
        Future<String> future = NETWORK.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return line.translate(text, source, target, deadline);
            }
        });
        try {
            return future.get(Math.max(200L, remaining(deadline)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException(line.name() + " 请求超时", new SocketTimeoutException("connect timed out"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(line.name() + " 翻译失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("翻译被中断");
        }
    }

    /**
     * 把异常翻译成给玩家看的中文提示。
     * 直接展示 "java.net.SocketTimeoutException: connect timed out" 对玩家没有意义。
     */
    public static String hint(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof UnknownHostException) {
            return "无法解析翻译服务器地址（DNS 异常或网络已断开）";
        }
        if (root instanceof ConnectException) {
            return "连接翻译服务器失败（网络不通或被防火墙拦截）";
        }
        if (root instanceof SocketTimeoutException) {
            String m = root.getMessage() == null ? "" : root.getMessage();
            return m.contains("connect") ? "连接翻译服务器超时（弱网或线路被拦截）"
                    : "翻译服务器响应超时（网络太慢，请稍后重试）";
        }
        if (root instanceof SSLException) {
            return "与翻译服务器的 TLS 握手失败（可能被中间网络拦截）";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        // 有些 IOException 只包了文字没有 cause（例如第三方库抛的），按文案兜底
        if (message.contains("connect timed out")) {
            return "连接翻译服务器超时（弱网或线路被拦截）";
        }
        if (message.contains("Read timed out")) {
            return "翻译服务器响应超时（网络太慢，请稍后重试）";
        }
        if (message.contains("HTTP 429")) {
            return "翻译线路请求过于频繁，已自动切换其他线路，请稍后再试";
        }
        if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
            return "翻译线路鉴权失效（线路可能已关闭），已自动切换其他线路";
        }
        if (message.contains("HTTP 5")) {
            return "翻译服务端暂时不可用，请稍后重试";
        }
        if (message.contains("冷却")) {
            return "翻译线路暂时不可用（连续失败后自动冷却），请稍后再试";
        }
        if (message.contains("均不可用")) {
            return "所有翻译线路均不可用，请检查网络后重试";
        }
        return message.trim().isEmpty() ? root.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------- 线路：Bing

    private static String translateBing(String text, String source, String target, long deadline)
            throws IOException {
        String to = bingCode(target);
        String from = source.isEmpty() ? "auto-detect" : bingCode(source);
        IOException last = null;
        // 令牌过期 / 被拒时刷新一次重试
        for (int round = 0; round < 2; round++) {
            ensureBingSession(deadline);
            try {
                String result = requestBing(text, from, to, deadline);
                if (result != null && !result.trim().isEmpty()) {
                    return result;
                }
                throw new IOException("Bing 返回空结果");
            } catch (IOException e) {
                last = e;
                // 403/429 说明令牌或会话失效，换一份再试
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (round == 0 && (m.contains("HTTP 403") || m.contains("HTTP 401") || m.contains("HTTP 429"))) {
                    invalidateBingSession();
                    continue;
                }
                throw e;
            }
        }
        throw last != null ? last : new IOException("Bing 会话建立失败");
    }

    private static synchronized void ensureBingSession(long deadline) throws IOException {
        long now = System.currentTimeMillis();
        if (bingToken != null && bingIg != null && bingIid != null && now - bingTokenAt < BING_TTL_MS) {
            return;
        }
        IOException last = null;
        for (String host : BING_HOSTS) {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(host + BING_PATH).openConnection();
                con.setRequestMethod("GET");
                applyTimeouts(con, deadline);
                con.setRequestProperty("User-Agent", USER_AGENT);
                con.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                try {
                    int code = con.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        last = new IOException("Bing 会话页返回 HTTP " + code);
                        continue;
                    }
                    String html = read(con);
                    String cookie = collectCookies(con);
                    String abuse = firstGroup(html,
                            "params_AbusePreventionHelper\\s*=\\s*\\[(\\d+),\"([^\"]+)\",(\\d+)\\]");
                    String token = secondGroup(html,
                            "params_AbusePreventionHelper\\s*=\\s*\\[(\\d+),\"([^\"]+)\",(\\d+)\\]");
                    String ig = firstGroup(html, "IG:\"([^\"]+)\"");
                    String iid = firstGroup(html, "data-iid=\"([^\"]+)\"");
                    if (abuse == null || token == null || ig == null || iid == null) {
                        last = new IOException("Bing 会话页里没有找到翻译令牌（页面结构变化）");
                        continue;
                    }
                    bingHost = host;
                    bingKey = abuse;
                    bingToken = token;
                    bingIg = ig;
                    bingIid = iid;
                    bingCookie = cookie;
                    bingTokenAt = now;
                    return;
                } finally {
                    con.disconnect();
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Bing 会话建立失败");
    }

    private static synchronized void invalidateBingSession() {
        bingToken = null;
        bingIg = null;
        bingIid = null;
        bingTokenAt = -1L;
    }

    private static String requestBing(String text, String from, String to, long deadline)
            throws IOException {
        String url = bingHost + "/ttranslatev3?isVertical=1&&IG=" + encode(bingIg) + "&IID=" + encode(bingIid);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        applyTimeouts(con, deadline);
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Referer", bingHost + BING_PATH);
        con.setRequestProperty("Origin", bingHost);
        if (bingCookie != null && !bingCookie.isEmpty()) {
            con.setRequestProperty("Cookie", bingCookie);
        }
        String body = "fromLang=" + encode(from)
                + "&text=" + encode(text)
                + "&to=" + encode(to)
                + "&token=" + encode(bingToken)
                + "&key=" + encode(bingKey);
        try {
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Bing 返回 HTTP " + code + errorBody(con));
            }
            return parseBing(read(con));
        } finally {
            con.disconnect();
        }
    }

    private static String parseBing(String json) throws IOException {
        try {
            JsonElement root = new JsonParser().parse(json);
            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonElement translations = element.getAsJsonObject().get("translations");
                    if (translations == null || !translations.isJsonArray()) {
                        continue;
                    }
                    for (JsonElement trans : translations.getAsJsonArray()) {
                        if (!trans.isJsonObject()) {
                            continue;
                        }
                        JsonElement t = trans.getAsJsonObject().get("text");
                        if (t != null && !t.isJsonNull() && !t.getAsString().trim().isEmpty()) {
                            return t.getAsString();
                        }
                    }
                }
            } else if (root.isJsonObject()) {
                JsonElement status = root.getAsJsonObject().get("statusCode");
                if (status != null) {
                    // 把服务端正文带出来：Bing 的 400 通常只回一个空 errorMessage，排查全靠这段
                    String raw = json.length() > 160 ? json.substring(0, 160) : json;
                    throw new IOException("Bing 返回错误码 " + status.getAsInt() + "：" + raw);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Bing 响应解析失败", t);
        }
        throw new IOException("Bing 响应里没有译文");
    }

    // ---------------------------------------------------------------- 线路：Google

    private static String translateGoogle(String text, String source, String target, long deadline)
            throws IOException {
        StringBuilder url = new StringBuilder(GOOGLE_URL)
                .append("?client=gtx&dt=t")
                .append("&sl=").append(encode(source.isEmpty() ? "auto" : googleCode(source)))
                .append("&tl=").append(encode(googleCode(target)))
                .append("&q=").append(encode(text));
        HttpURLConnection con = (HttpURLConnection) new URL(url.toString()).openConnection();
        con.setRequestMethod("GET");
        applyTimeouts(con, deadline);
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", "application/json");
        try {
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Google 返回 HTTP " + code);
            }
            return parseGoogle(read(con));
        } finally {
            con.disconnect();
        }
    }

    private static String parseGoogle(String json) throws IOException {
        try {
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonArray() || root.getAsJsonArray().size() == 0) {
                throw new IOException("Google 响应格式异常");
            }
            JsonElement first = root.getAsJsonArray().get(0);
            if (!first.isJsonArray()) {
                throw new IOException("Google 响应格式异常");
            }
            StringBuilder out = new StringBuilder();
            for (JsonElement seg : first.getAsJsonArray()) {
                if (!seg.isJsonArray() || seg.getAsJsonArray().size() == 0) {
                    continue;
                }
                JsonElement piece = seg.getAsJsonArray().get(0);
                if (piece != null && !piece.isJsonNull()) {
                    out.append(piece.getAsString());
                }
            }
            String result = out.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("Google 响应里没有译文");
            }
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Google 响应解析失败", t);
        }
    }

    // ---------------------------------------------------------------- 线路：MyMemory

    private static String translateMemory(String text, String source, String target, long deadline)
            throws IOException {
        List<String> parts = splitByBytes(text, MEMORY_MAX_BYTES);
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String piece = requestMemory(part, source, target, deadline);
            if (piece == null || piece.trim().isEmpty()) {
                throw new IOException("MyMemory 返回空结果");
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(piece);
        }
        return out.toString();
    }

    private static String requestMemory(String text, String source, String target, long deadline)
            throws IOException {
        String pair = memoryCode(source) + "|" + memoryCode(target);
        // mt=1：优先走机器翻译，避免翻译记忆库把 "hello world" 原样返回
        // de：统计调用方；缺了它匿名请求会稳定 429
        String url = MEMORY_URL + "?q=" + encode(text) + "&langpair=" + encode(pair)
                + "&mt=1&de=" + encode(MEMORY_CONTACT);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        applyTimeouts(con, deadline);
        con.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("MyMemory 返回 HTTP " + code);
            }
            String body = read(con);
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonElement quota = root.get("quotaFinished");
            if (quota != null && quota.isJsonPrimitive() && quota.getAsBoolean()) {
                throw new IOException("MyMemory 今日配额已用完");
            }
            JsonObject data = root.getAsJsonObject("responseData");
            if (data == null) {
                throw new IOException("MyMemory 响应缺少 responseData");
            }
            JsonElement translated = data.get("translatedText");
            if (translated == null || translated.isJsonNull()) {
                throw new IOException("MyMemory 响应缺少 translatedText");
            }
            String result = translated.getAsString();
            if (result != null && result.contains("MYMEMORY WARNING")) {
                throw new IOException("MyMemory 提示额度不足");
            }
            return result == null ? null : unescapeHtml(result);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("MyMemory 响应解析失败", t);
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- 熔断 / 预算

    private static Breaker breaker(String name) {
        Breaker b = BREAKERS.get(name);
        if (b == null) {
            b = new Breaker();
            Breaker old = BREAKERS.putIfAbsent(name, b);
            if (old != null) {
                b = old;
            }
        }
        return b;
    }

    private static boolean isBlocked(String name) {
        return breaker(name).blockedUntil > System.currentTimeMillis();
    }

    private static void onSuccess(String name) {
        Breaker b = breaker(name);
        b.failures = 0;
        b.strikes = 0;
        b.blockedUntil = 0L;
    }

    private static void onFailure(String name) {
        Breaker b = breaker(name);
        b.failures = Math.min(100, b.failures + 1);
        if (b.failures < 2) {
            return;
        }
        // 15s、30s、60s：失败计数不清零，冷却结束后再失败会立刻进入更长的冷却，
        // 不会又从头开始"试两次才熔断"（那正好把每条消息都拖成十几秒）
        long cooldown = Math.min(60000L, 15000L * (1L << Math.min(2, b.strikes)));
        b.strikes = Math.min(6, b.strikes + 1);
        b.blockedUntil = System.currentTimeMillis() + cooldown;
    }

    private static long remaining(long deadline) {
        return deadline - System.currentTimeMillis();
    }

    /** 连接 + 读取超时之和不超过剩余预算，避免一次尝试就把 15 秒总预算冲破 */
    private static void applyTimeouts(HttpURLConnection con, long deadline) {
        long left = Math.max(400L, remaining(deadline));
        int connect = (int) Math.min(CONNECT_TIMEOUT_MS, Math.max(300L, left / 2));
        int read = (int) Math.min(READ_TIMEOUT_MS, Math.max(300L, left - connect));
        con.setConnectTimeout(connect);
        con.setReadTimeout(read);
    }

    /** 只有网络异常 / 429 值得重试；超时和 4xx 都说明重试只是再等一遍 */
    private static boolean retryable(IOException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof UnknownHostException || root instanceof SocketTimeoutException) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("timed out") || message.contains("超时")) {
            return false;
        }
        if (message.contains("HTTP 4")) {
            return message.contains("HTTP 429");
        }
        return root instanceof IOException;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String read(HttpURLConnection con) throws IOException {
        InputStream stream = con.getInputStream();
        StringBuilder content = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }

    /** 把 Set-Cookie 拼成请求头用的 Cookie（Bing 会话校验用） */
    private static String collectCookies(HttpURLConnection con) {
        Map<String, List<String>> headers = con.getHeaderFields();
        if (headers == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null) {
                    continue;
                }
                int semi = value.indexOf(';');
                String pair = semi >= 0 ? value.substring(0, semi) : value;
                if (pair.indexOf('=') <= 0) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(pair.trim());
            }
        }
        return out.toString();
    }

    private static String firstGroup(String input, String regex) {
        return group(input, regex, 1);
    }

    private static String secondGroup(String input, String regex) {
        return group(input, regex, 2);
    }

    private static String group(String input, String regex, int index) {
        if (input == null) {
            return null;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(input);
            if (!m.find() || m.groupCount() < index) {
                return null;
            }
            String value = m.group(index);
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 出错时把服务端的错误正文带进异常里，方便排查是鉴权还是限流。 */
    private static String errorBody(HttpURLConnection con) {
        try {
            InputStream stream = con.getErrorStream();
            if (stream == null) {
                return "";
            }
            StringBuilder content = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    content.append(line);
                }
            }
            String body = content.toString().trim();
            if (body.length() > 120) {
                body = body.substring(0, 120);
            }
            return body.isEmpty() ? "" : ("：" + body);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 部分翻译服务会把引号 / 尖括号转成 HTML 实体，直接打印会露出 &#39; 这类字符。 */
    private static String unescapeHtml(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        return value
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static IOException asIOException(String prefix, Throwable t) {
        if (t instanceof IOException && t.getMessage() != null) {
            return new IOException(prefix + "：" + t.getMessage(), t);
        }
        String message = t.getMessage();
        return new IOException(prefix + (message == null ? "" : "：" + message), t);
    }

    /** 目标语言规整：非法值回落日语（与原默认一致）。 */
    private static String normalize(String code) {
        if (code != null) {
            for (String candidate : new String[]{
                    JAPANESE, ENGLISH, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, POLISH
            }) {
                if (candidate.equalsIgnoreCase(code)) {
                    return candidate;
                }
            }
            if ("zh-CN".equalsIgnoreCase(code)) return CHINESE_SIMPLIFIED;
            if ("zh-TW".equalsIgnoreCase(code)) return CHINESE_TRADITIONAL;
        }
        return JAPANESE;
    }

    /** 源语言规整：空串 / auto 必须返回空，交给 detectSource，不能回落成日语。 */
    private static String normalizeSource(String code) {
        if (code == null || code.trim().isEmpty()
                || "auto".equalsIgnoreCase(code)
                || "autodetect".equalsIgnoreCase(code)) {
            return "";
        }
        for (String candidate : new String[]{
                JAPANESE, ENGLISH, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, POLISH
        }) {
            if (candidate.equalsIgnoreCase(code)) {
                return candidate;
            }
        }
        if ("zh-CN".equalsIgnoreCase(code)) return CHINESE_SIMPLIFIED;
        if ("zh-TW".equalsIgnoreCase(code)) return CHINESE_TRADITIONAL;
        return "";
    }

    /**
     * Bing 的语言代码：中文必须用 zh-Hans / zh-Hant。
     * 传成 zh-CN 会直接 400（服务端只回 {"statusCode":400,"errorMessage":""}），
     * 所以这里不能复用 Google 的映射。
     */
    private static String bingCode(String code) {
        return code;
    }

    private static String googleCode(String code) {
        if (CHINESE_SIMPLIFIED.equals(code)) return "zh-CN";
        if (CHINESE_TRADITIONAL.equals(code)) return "zh-TW";
        if ("ko".equals(code)) return "ko";
        return code;
    }

    /** MyMemory 用 zh-CN / zh-TW，其余与内部代码一致。 */
    private static String memoryCode(String code) {
        if (CHINESE_SIMPLIFIED.equals(code)) return "zh-CN";
        if (CHINESE_TRADITIONAL.equals(code)) return "zh-TW";
        return code;
    }

    private static boolean sameLanguage(String source, String target) {
        return memoryCode(source).equalsIgnoreCase(memoryCode(target));
    }

    /**
     * 源语言识别：MyMemory 不支持 autodetect，只能按字符范围猜。
     * 假名 → 日语；谚文 → 韩语；汉字 → 简体中文；其余按英语处理。
     */
    private static String detectSource(String text) {
        boolean han = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u309F') || (c >= '\u30A0' && c <= '\u30FF')) {
                return JAPANESE;
            }
            if (c >= '\uAC00' && c <= '\uD7AF') {
                return "ko";
            }
            if (c >= '\u4E00' && c <= '\u9FFF') {
                han = true;
            }
        }
        return han ? "zh-CN" : ENGLISH;
    }

    /** 按 UTF-8 字节数切分，保证每段不超 MyMemory 的 q 上限。 */
    private static List<String> splitByBytes(String text, int maxBytes) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int bytes = 0;
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                int charBytes;
                if (Character.isHighSurrogate(c) && end + 1 < text.length()) {
                    charBytes = 4;
                    end++;
                } else if (c < 0x80) {
                    charBytes = 1;
                } else if (c < 0x800) {
                    charBytes = 2;
                } else {
                    charBytes = 3;
                }
                if (bytes + charBytes > maxBytes && end > start) {
                    break;
                }
                bytes += charBytes;
                end++;
            }
            if (end == start) {
                end = start + 1;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value == null ? "" : value;
        }
    }
}

