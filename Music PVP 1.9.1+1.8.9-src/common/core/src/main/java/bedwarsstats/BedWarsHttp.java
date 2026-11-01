package bedwarsstats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 有上限的 HTTPS GET。等价于 Arcveil 的 QNetworkReply 读取策略：
 * 10 秒超时、最多读取 maxBytes + 1 字节、Content-Length 预检、
 * 读取 RateLimit-* 响应头。
 *
 * 只用 Java 8 自带的 HttpURLConnection，不引入任何第三方网络库，
 * 也不含任何 UI / Minecraft 依赖。
 */
public final class BedWarsHttp {

    /** 可注入的传输层，便于无网络测试。 */
    public interface Transport {
        Response get(String url, String apiKey, String userAgent,
                     int maxBytes, int timeoutMillis);
    }

    public static final Transport DEFAULT = new Transport() {
        @Override
        public Response get(String url, String apiKey, String userAgent,
                            int maxBytes, int timeoutMillis) {
            return BedWarsHttp.get(url, apiKey, userAgent, maxBytes, timeoutMillis);
        }
    };

    /** 一次 HTTP 结果。 */
    public static final class Response {
        /** HTTP 状态码；网络层失败时为 -1。 */
        public final int status;
        public final String body;
        public final boolean tooLarge;
        /** 网络层错误描述；正常收到响应时为 null。 */
        public final String networkError;
        public final int rateLimit;
        public final int rateRemaining;
        public final int rateResetSeconds;

        public Response(int status, String body, boolean tooLarge, String networkError,
                        int rateLimit, int rateRemaining, int rateResetSeconds) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.tooLarge = tooLarge;
            this.networkError = networkError;
            this.rateLimit = rateLimit;
            this.rateRemaining = rateRemaining;
            this.rateResetSeconds = rateResetSeconds;
        }

        public boolean ok() {
            return networkError == null && !tooLarge && status >= 200 && status < 300;
        }
    }

    private BedWarsHttp() {
    }

    public static Response get(String url, String apiKey, String userAgent,
                               int maxBytes, int timeoutMillis) {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("API-Key", apiKey);
            }

            final int status = connection.getResponseCode();
            final int rateLimit = headerInteger(connection, "RateLimit-Limit");
            final int rateRemaining = headerInteger(connection, "RateLimit-Remaining");
            final int rateReset = headerInteger(connection, "RateLimit-Reset");

            final long declaredLength = connection.getContentLengthLong();
            if (declaredLength > maxBytes) {
                return new Response(status, "", true, null, rateLimit, rateRemaining, rateReset);
            }

            stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return new Response(status, "", false,
                        status >= 400 ? null : "empty response body",
                        rateLimit, rateRemaining, rateReset);
            }

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            final byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    // 超过上限后不再保留任何服务端可控内容。
                    return new Response(status, "", true, null,
                            rateLimit, rateRemaining, rateReset);
                }
                buffer.write(chunk, 0, read);
            }
            return new Response(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8),
                    false, null, rateLimit, rateRemaining, rateReset);
        } catch (IOException error) {
            return new Response(-1, "", false, error.toString(), -1, -1, -1);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结果。
                }
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static int headerInteger(HttpURLConnection connection, String name) {
        final String value = connection.getHeaderField(name);
        if (value == null) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
