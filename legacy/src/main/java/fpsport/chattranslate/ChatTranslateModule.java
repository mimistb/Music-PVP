package fpsport.chattranslate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/**
 * 聊天翻译模块（10 行核心 + 1 个网络客户端），从 GlideClient 的
 * {@code ChatTranslateMod} 剥离而来，不含任何 UI。
 *
 * <p>原模块做的三件事：
 * <ol>
 *   <li>收到服务器聊天包后，在消息末尾追加绿色 ✎ 按钮，点击触发翻译；</li>
 *   <li>把点击动作接给 {@code Translator}（微软翻译免费通道），结果打印回聊天框；</li>
 *   <li>可选：发送消息前先把内容翻译成目标语言再发出。</li>
 * </ol>
 *
 * <p>本类不注册事件、不创建 Screen、不改聊天框布局，宿主按 README 的 3 个时机调用即可。
 * 原 Glide 的 {@code GuiChatHook}（聊天界面左下角的 Translate On/Off、To: ja 小面板）、
 * 聊天 Y 轴偏移、{@code .soarcmd translate} 命令体系全部**没有**迁入，
 * 以保证宿主自己的聊天 UI 不被覆盖。
 */
public final class ChatTranslateModule {

    /** 目标语言代码，顺序与原 ComboSetting 一致。 */
    public static final String[] LANGUAGE_CODES = {
            Translator.JAPANESE,
            Translator.ENGLISH,
            Translator.CHINESE_SIMPLIFIED,
            Translator.CHINESE_TRADITIONAL,
            Translator.POLISH
    };

    /** 供宿主设置界面直接使用的语言显示名。 */
    public static final String[] LANGUAGE_NAMES = {
            "日语", "英语", "简体中文", "繁体中文", "波兰语"
    };

    /** 总开关。 */
    public boolean enabled = true;
    /** 收到消息时是否追加 ✎ 翻译按钮（手动点按才翻译，不会自动补译文）。 */
    public boolean showTranslateButton = true;
    /** 收到其他玩家聊天时是否自动补一条译文；默认关闭，与发送前翻译互不影响。 */
    public boolean autoTranslateIncoming = false;
    /** 发送消息前是否先翻译；自动翻译只作用于玩家自己发送的消息。 */
    public boolean translateOutgoing = false;
    /** 发送前翻译时是否先用本地缩写规则库展开 gg / wp / ez 等缩写。 */
    public boolean abbrevEnabled = true;
    /** 接收消息时使用的目标语言（自动翻译 / ✎ 按钮 / /命令 译文），取值见 {@link #LANGUAGE_CODES}。 */
    public String incomingLanguage = Translator.JAPANESE;
    /** 发送消息前翻译使用的目标语言，与 {@link #incomingLanguage} 完全独立。 */
    public String outgoingLanguage = Translator.JAPANESE;
    /** 点击按钮时执行的命令名，宿主需注册同名命令并转调 {@link #handleCommand(String)}。 */
    public String commandName = "translate";
    /** 按钮文本。 */
    public String buttonText = " [\u270e]";
    /** 按钮悬停提示。 */
    public String buttonHoverText = "点击翻译这条消息";
    /** 以 / 或 . 开头的消息不翻译，原样发送。 */
    public boolean skipCommands = true;

    /**
     * 翻译线程池：弱网下单条请求可能占满十几秒，单线程会让后续消息全部排队；
     * 两条线程 + 无界队列，界面线程永远不阻塞，同时又不会因为并发过高被服务端限流。
     */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "ChatTranslate");
        thread.setDaemon(true);
        return thread;
    });

    // ------------------------------------------------------------------ 收消息

    /**
     * 宿主收到聊天包（{@code S02PacketChat}）时调用。
     *
     * @param message  原始聊天组件
     * @param chatType 包类型，2 表示 actionbar（原实现跳过）
     * @return true 表示本模块已经打印了处理后的消息，宿主应取消原版显示；
     *         false 表示不处理，让原版照常显示。
     */
    public boolean onChatReceived(IChatComponent message, int chatType) {
        if (!enabled || message == null) {
            return false;
        }
        if (chatType == 2) {
            return false;
        }
        String raw = message.getUnformattedText();
        if (raw == null || raw.replaceAll(" ", "").isEmpty()) {
            return false;
        }
        // 自动翻译只作用于玩家自己发送的消息；收到的消息只在玩家点击 ✎ 时翻译。
        // 开启「自动翻译玩家话语」后，玩家聊天（type 0）会额外补一条译文，与 ✎ 相互独立。
        final boolean auto = autoTranslateIncoming && chatType == 0;
        if (!showTranslateButton && !auto) {
            return false;
        }
        // 打印失败（界面还没就绪）时不要取消原版显示，否则这条消息会彻底消失
        if (!print(showTranslateButton ? decorate(message, raw) : message)) return false;
        if (auto) {
            translateAsync(raw, incomingLanguage, translated -> {
                if (translated == null) return;
                String result = translated.trim();
                // 原文已经是目标语言（或翻译结果与原文相同）时不重复刷屏
                if (result.isEmpty() || result.equals(raw.trim())) return;
                print(EnumChatFormatting.GRAY + "[译] " + EnumChatFormatting.WHITE + result);
            }, error -> {
                // 自动翻译失败静默，不打断聊天
            });
        }
        return true;
    }

    /**
     * 只给消息追加 ✎ 按钮，不打印。适合宿主想自己接管显示顺序的场景。
     *
     * @param message 原始聊天组件
     * @param raw     与 message 对应的纯文本，用于拼命令
     */
    public IChatComponent decorate(IChatComponent message, String raw) {
        ChatStyle style = new ChatStyle();
        style.setColor(EnumChatFormatting.GREEN);
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/" + commandName + " " + raw));
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(buttonHoverText)));
        IChatComponent button = new ChatComponentText(buttonText).setChatStyle(style);
        return message.appendSibling(button);
    }

    // ------------------------------------------------------------------ 发消息

    /**
     * 宿主在“玩家按回车发送聊天”时调用。
     *
     * <p>未开启 {@link #translateOutgoing} 或消息以 / 、. 开头时直接原样发送；
     * 否则异步翻译后再发送，翻译失败回落原文。
     */
    public void onChatSend(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (!enabled || !translateOutgoing || (skipCommands && isCommand(message))) {
            sendDirect(message);
            return;
        }
        // 本地缩写规则库：gg / wp / ez / nt …… 先展开成完整英文，再走网络翻译
        final String expanded = abbrevEnabled ? AbbrevDictionary.expand(message) : null;
        final String source = expanded != null ? expanded : message;
        translateAsync(source, outgoingLanguage,
                translated -> sendDirect(translated),
                error -> sendDirect(source));
    }

    /** 宿主如果自己拦截发送逻辑，可用它判断当前是否该走翻译分支。 */
    public boolean shouldTranslateOutgoing(String message) {
        return enabled && translateOutgoing && message != null && !message.trim().isEmpty()
                && !(skipCommands && isCommand(message));
    }

    /** 直接发送（含加入上翻历史），与 Glide 的 sendTranslatedMessage 行为一致。 */
    public static void sendDirect(String message) {
        if (message == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        // 翻译回调跑在后台线程：切回主线程再改聊天历史，避免与 drawChat 并发；
        // 顺便避免玩家已经退出世界时把译文发到别的连接里。
        if (!mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(() -> sendDirect(message));
            return;
        }
        if (mc.thePlayer == null || mc.ingameGUI == null) {
            return;
        }
        mc.ingameGUI.getChatGUI().addToSentMessages(message);
        mc.thePlayer.sendChatMessage(message);
    }

    private static boolean isCommand(String message) {
        return message.startsWith("/") || message.startsWith(".");
    }

    // ------------------------------------------------------------------ 翻译

    /** 异步翻译并打印到聊天框；回调在后台线程执行。 */
    public void translateAndPrint(String text, String to) {
        translateAsync(text, to,
                translated -> print(EnumChatFormatting.GREEN + "[Translate] "
                        + EnumChatFormatting.WHITE + translated),
                error -> print(EnumChatFormatting.RED + "[Translate] 翻译失败: " + errorText(error)));
    }

    /**
     * 异常兜底文案：把底层网络异常转成玩家能看懂的中文提示
     * （不再直接打 "java.net.SocketTimeoutException" 这类文案，玩家看不出发生了什么）。
     */
    private static String errorText(Throwable error) {
        if (error == null) return "未知错误";
        String hint = Translator.hint(error);
        return hint == null || hint.trim().isEmpty() ? "未知错误" : hint;
    }

    /** 同步等待结果，仅建议在后台线程里调用；失败返回 null。 */
    public static String translateNow(String text, String to) {
        return Translator.translateQuietly(text, Translator.AUTO_DETECT, normalizeLanguage(to));
    }

    /**
     * 异步翻译。
     *
     * @param onSuccess 成功回调，运行在单线程池里（可以直接打印聊天，但不要碰 GL）
     * @param onError   失败回调，同上
     */
    public void translateAsync(String text, String to,
                               Consumer<String> onSuccess, Consumer<Throwable> onError) {
        final String source = text;
        final String language = normalizeLanguage(to);
        EXECUTOR.execute(() -> {
            try {
                // 已经是目标语言（例如目标英语且全是拉丁字母）就不必再走网络，既准确又快
                if (alreadyTargetLanguage(source, language)) {
                    onSuccess.accept(source);
                    return;
                }
                String cached = cacheGet(language, source);
                if (cached != null) {
                    onSuccess.accept(cached);
                    return;
                }
                String translated = Translator.translate(source, Translator.AUTO_DETECT, language);
                cachePut(language, source, translated);
                onSuccess.accept(translated);
            } catch (Throwable t) {
                if (onError != null) {
                    onError.accept(t);
                }
            }
        });
    }

    // 翻译结果缓存：同一句话反复出现（gg、wp、ty）时不再重复请求网络
    private static final int CACHE_LIMIT = 64;
    private static final Map<String, String> CACHE =
            new LinkedHashMap<String, String>(CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private static synchronized String cacheGet(String language, String text) {
        return CACHE.get(language + '\u0000' + text);
    }

    private static synchronized void cachePut(String language, String text, String result) {
        if (result != null && !result.trim().isEmpty()) {
            CACHE.put(language + '\u0000' + text, result);
        }
    }

    /** 简单语种判断：目标为中文时纯中文文本、目标为英语时纯拉丁文本都可以直接复用原文。 */
    private static boolean alreadyTargetLanguage(String text, String language) {
        if (text == null) return false;
        boolean latinOnly = true;
        boolean hasLatin = false;
        boolean hasCjk = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                hasLatin = true;
            } else if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                hasCjk = true;
                latinOnly = false;
            } else if (Character.isLetter(c)) {
                latinOnly = false;
            }
        }
        if (Translator.CHINESE_SIMPLIFIED.equals(language) || Translator.CHINESE_TRADITIONAL.equals(language)) {
            return hasCjk && !hasLatin;
        }
        if (Translator.ENGLISH.equals(language)) {
            return hasLatin && latinOnly;
        }
        return false;
    }

    /**
     * 供宿主命令处理器转调：{@code /translate <任意文本>}。
     * 与 Glide 的 {@code TranslateCommand} 等价，只是不再依赖它的命令框架。
     */
    public void handleCommand(String args) {
        if (args == null || args.trim().isEmpty()) {
            print(EnumChatFormatting.RED + "[Translate] 用法: /" + commandName + " <文本>");
            return;
        }
        translateAndPrint(args.trim(), incomingLanguage);
    }

    /** 把任意语言代码规整到 {@link #LANGUAGE_CODES} 之一，非法值回落日语（与原默认一致）。 */
    public static String normalizeLanguage(String code) {
        if (code != null) {
            for (String candidate : LANGUAGE_CODES) {
                if (candidate.equalsIgnoreCase(code)) {
                    return candidate;
                }
            }
            if ("zh-CN".equalsIgnoreCase(code)) {
                return Translator.CHINESE_SIMPLIFIED;
            }
            if ("zh-TW".equalsIgnoreCase(code)) {
                return Translator.CHINESE_TRADITIONAL;
            }
        }
        return Translator.JAPANESE;
    }

    private static boolean print(String text) {
        return print(new ChatComponentText(text));
    }

    /** @return false 表示当前界面还没准备好，调用方应让原版照常显示这条消息 */
    private static boolean print(IChatComponent component) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) {
            return false;
        }
        // 译文回调在后台线程；调度回主线程再改聊天列表，避免和 drawChat 并发
        mc.addScheduledTask(() -> {
            if (mc.ingameGUI != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(component);
            }
        });
        return true;
    }
}
