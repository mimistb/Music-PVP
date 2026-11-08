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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微软翻译（Edge 免费通道）+ MyMemory 回落的最小客户端。
 *
 * <p>原实现只依赖 {@code https://edge.microsoft.com/translate/auth} 拿临时 JWT，
 * 该地址已不可用（返回 404），导致所有翻译请求都以鉴权失败告终。
 * 现在按“微软 → MyMemory”的顺序尝试，只要有一个通路可用就能翻译，
 * 并对每个响应做空值 / 错误码 / 配额校验，失败会抛带原因的中文异常。
 *
 * <p>注意：{@code translate(...)} 是阻塞调用，永远不要在客户端主线程直接调；
 * 用 {@link ChatTranslateModule#translateAsync} 或自己放到异步线程。
 */
public final class Translator {

    public static final String AUTO_DETECT = "";
    public static final String ENGLISH = "en";
    public static final String JAPANESE = "ja";
    public static final String CHINESE_SIMPLIFIED = "zh-Hans";
    public static final String CHINESE_TRADITIONAL = "zh-Hant";
    public static final String POLISH = "pl";

    private static final String AUTH_URL = "https://edge.microsoft.com/translate/auth";
    private static final String AZURE_URL = "https://api.cognitive.microsofttranslator.com/translate";
    private static final String MEMORY_URL = "https://api.mymemory.translated.net/get";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";

    /** 鉴权 token 缓存 5 分钟，避免每条消息都多打一次鉴权请求。 */
    private static final long AUTH_TTL_MS = 300000L;
    /** 翻译结果缓存条数：聊天里重复出现的句子（gg / 常见提示）不再走网络。 */
    private static final int CACHE_MAX = 128;
    /** MyMemory 单次 GET 的 q 上限约 500 字节，这里留一点余量。 */
    private static final int MEMORY_MAX_BYTES = 450;

    private static String authCache;
    private static long authFetchedAt = -1L;

    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private Translator() {
    }

    /**
     * 同步翻译。所有通路都失败会抛出 IOException（带原因），
     * 由调用方决定是否回落原文。
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

        IOException lastError = null;

        try {
            String result = translateAzure(sourceText, source, target);
            if (result != null && !result.trim().isEmpty()) {
                CACHE.put(cacheKey, result);
                return result;
            }
            lastError = new IOException("微软翻译返回空结果");
        } catch (Throwable t) {
            lastError = asIOException("微软翻译失败", t);
        }

        try {
            String result = translateMemory(sourceText, source, target);
            if (result != null && !result.trim().isEmpty()) {
                CACHE.put(cacheKey, result);
                return result;
            }
            lastError = new IOException("备用翻译服务返回空结果");
        } catch (Throwable t) {
            IOException e = asIOException("备用翻译服务失败", t);
            if (lastError != null) {
                e.addSuppressed(lastError);
                throw e;
            }
            throw e;
        }

        throw lastError != null ? lastError : new IOException("没有可用的翻译服务");
    }

    /** 失败返回 null，不抛异常；用于“翻译失败就保持原文”的场景。 */
    public static String translateQuietly(String text, String from, String to) {
        try {
            return translate(text, from, to);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 微软

    private static synchronized String auth() throws IOException {
        long now = System.currentTimeMillis();
        if (authCache != null && authFetchedAt > 0L && now - authFetchedAt < AUTH_TTL_MS) {
            return authCache;
        }
        HttpURLConnection con = (HttpURLConnection) new URL(AUTH_URL).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        con.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("鉴权请求返回 HTTP " + code);
            }
            String token = read(con).trim();
            // Edge 返回的是 JWT（三段用点分隔）；拿到 HTML / 空串说明通道已失效
            if (token.isEmpty() || token.indexOf('.') < 0 || token.length() > 4096) {
                throw new IOException("鉴权响应格式异常");
            }
            authCache = token;
            authFetchedAt = now;
            return token;
        } finally {
            con.disconnect();
        }
    }

    private static String translateAzure(String text, String source, String target) throws IOException {
        StringBuilder url = new StringBuilder(AZURE_URL).append("?api-version=3.0");
        if (!source.isEmpty()) {
            url.append("&from=").append(encode(source));
        }
        url.append("&to=").append(encode(target));

        HttpURLConnection con = (HttpURLConnection) new URL(url.toString()).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("authorization", "Bearer " + auth());

        JsonArray body = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("Text", text);
        body.add(item);
        try {
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // token 过期 / 端点失效：清缓存，下次重新鉴权
                authCache = null;
                authFetchedAt = -1L;
                throw new IOException("翻译请求返回 HTTP " + code + errorBody(con));
            }
            return firstTranslation(read(con));
        } finally {
            con.disconnect();
        }
    }

    private static String firstTranslation(String json) throws IOException {
        try {
            JsonArray array = new JsonParser().parse(json).getAsJsonArray();
            for (JsonElement element : array) {
                JsonArray translations = element.getAsJsonObject().getAsJsonArray("translations");
                if (translations == null) continue;
                for (JsonElement trans : translations) {
                    JsonElement text = trans.getAsJsonObject().get("text");
                    if (text != null && !text.getAsString().trim().isEmpty()) {
                        return text.getAsString();
                    }
                }
            }
        } catch (Throwable t) {
            throw new IOException("翻译响应解析失败", t);
        }
        throw new IOException("翻译响应里没有译文");
    }

    // ---------------------------------------------------------------- MyMemory

    private static String translateMemory(String text, String source, String target) throws IOException {
        List<String> parts = splitByBytes(text, MEMORY_MAX_BYTES);
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String piece = requestMemory(part, source, target);
            if (piece == null || piece.trim().isEmpty()) {
                throw new IOException("备用翻译服务返回空结果");
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(piece);
        }
        return out.toString();
    }

    private static String requestMemory(String text, String source, String target) throws IOException {
        String pair = memoryCode(source) + "|" + memoryCode(target);
        // mt=1：优先走机器翻译，避免翻译记忆库把 "hello world" 原样返回
        String url = MEMORY_URL + "?q=" + encode(text) + "&langpair=" + encode(pair) + "&mt=1";
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("备用翻译服务返回 HTTP " + code);
            }
            String body = read(con);
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonElement quota = root.get("quotaFinished");
            if (quota != null && quota.isJsonPrimitive() && quota.getAsBoolean()) {
                throw new IOException("备用翻译服务今日配额已用完");
            }
            JsonObject data = root.getAsJsonObject("responseData");
            if (data == null) {
                throw new IOException("备用翻译响应缺少 responseData");
            }
            JsonElement translated = data.get("translatedText");
            if (translated == null || translated.isJsonNull()) {
                throw new IOException("备用翻译响应缺少 translatedText");
            }
            String result = translated.getAsString();
            if (result != null && result.contains("MYMEMORY WARNING")) {
                throw new IOException("备用翻译服务提示额度不足");
            }
            return result == null ? null : unescapeHtml(result);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("备用翻译响应解析失败", t);
        } finally {
            con.disconnect();
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

    /** 出错时把服务端的错误正文带进异常里，方便排查是 401 还是限流。 */
    private static String errorBody(HttpURLConnection con) {
        try {
            InputStream stream = con.getErrorStream();
            if (stream == null) return "";
            StringBuilder content = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    content.append(line);
                }
            }
            String body = content.toString().trim();
            if (body.length() > 120) body = body.substring(0, 120);
            return body.isEmpty() ? "" : ("：" + body);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 部分翻译服务会把引号 / 尖括号转成 HTML 实体，直接打印会露出 &#39; 这类字符。 */
    private static String unescapeHtml(String value) {
        if (value == null || value.indexOf('&') < 0) return value;
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
            if (end == start) end = start + 1;
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
