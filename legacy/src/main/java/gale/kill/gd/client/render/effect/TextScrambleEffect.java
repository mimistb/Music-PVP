/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render.effect;

import java.util.Random;

public class TextScrambleEffect {
    private static final String SCRAMBLE_LATIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String SCRAMBLE_NUMERIC = "0123456789";
    private static final String SCRAMBLE_CJK = "\u7684\u4e00\u662f\u5728\u4e0d\u4e86\u6709\u548c\u4eba\u8fd9\u4e2d\u5927\u4e3a\u4e0a\u4e2a\u56fd\u6211\u4ee5\u8981\u4ed6\u65f6\u6765\u7528\u4eec\u751f\u5230\u4f5c\u5730\u4e8e\u51fa\u5c31\u5206\u5bf9\u6210\u4f1a\u53ef\u4e3b\u53d1\u5e74\u6837\u80fd\u4e0b\u90e8\u52a8\u65b9\u51fa\u8fdb\u9762\u5bb6\u79cd\u5df2\u5fc3";
    private static final Random RANDOM = new Random();
    private final String target;
    private final boolean[] shouldScramble;
    private final long startTime;
    private final long duration;
    private final long refreshRate;
    private final boolean active;
    private final char[] cachedChars;
    private long lastRefreshTime = 0L;

    public TextScrambleEffect(String text, long duration, long refreshRate, boolean active) {
        this.target = text;
        this.duration = duration;
        this.refreshRate = refreshRate;
        this.active = active;
        this.startTime = System.currentTimeMillis();
        this.shouldScramble = new boolean[text.length()];
        this.cachedChars = new char[text.length()];
        if (active) {
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                this.shouldScramble[i] = this.isScrambleable(c);
                if (!this.shouldScramble[i]) continue;
                this.cachedChars[i] = this.getRandomCharLike(c);
            }
        }
    }

    private boolean isScrambleable(char c) {
        if (Character.isWhitespace(c)) {
            return false;
        }
        if (Character.isDigit(c)) {
            return false;
        }
        return c != '+' && c != '-' && c != '.' && c != ',' && c != '(' && c != ')' && c != ':' && c != '|';
    }

    public String getCurrentText() {
        boolean shouldUpdateCache;
        if (!this.active) {
            return this.target;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - this.startTime;
        float progress = Math.min(1.0f, (float)elapsed / (float)this.duration);
        if (progress >= 1.0f) {
            return this.target;
        }
        boolean bl = shouldUpdateCache = now - this.lastRefreshTime >= this.refreshRate;
        if (shouldUpdateCache) {
            this.lastRefreshTime = now;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.target.length(); ++i) {
            float charRevealDuration;
            if (!this.shouldScramble[i]) {
                sb.append(this.target.charAt(i));
                continue;
            }
            float charRevealStart = (float)i / (float)this.target.length() * 0.4f;
            float charProgress = Math.max(0.0f, Math.min(1.0f, (progress - charRevealStart) / (charRevealDuration = 0.6f)));
            if (charProgress >= 1.0f) {
                sb.append(this.target.charAt(i));
                continue;
            }
            if (shouldUpdateCache) {
                this.cachedChars[i] = this.getRandomCharLike(this.target.charAt(i));
            }
            sb.append(this.cachedChars[i]);
        }
        return sb.toString();
    }

    private char getRandomCharLike(char original) {
        if (original >= '\u4e00' && original <= '\u9fff') {
            return SCRAMBLE_CJK.charAt(RANDOM.nextInt(SCRAMBLE_CJK.length()));
        }
        if (original >= 'a' && original <= 'z' || original >= 'A' && original <= 'Z') {
            return SCRAMBLE_LATIN.charAt(RANDOM.nextInt(SCRAMBLE_LATIN.length()));
        }
        if (original >= '0' && original <= '9') {
            return SCRAMBLE_NUMERIC.charAt(RANDOM.nextInt(SCRAMBLE_NUMERIC.length()));
        }
        return SCRAMBLE_LATIN.charAt(RANDOM.nextInt(SCRAMBLE_LATIN.length()));
    }

    public boolean isFinished() {
        return !this.active || System.currentTimeMillis() - this.startTime >= this.duration;
    }
}

