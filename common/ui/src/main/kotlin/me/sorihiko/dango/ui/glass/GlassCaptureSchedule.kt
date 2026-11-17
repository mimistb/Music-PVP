package me.sorihiko.dango.ui.glass

/**
 * 玻璃背景的抓帧时序（纯逻辑，无头可测）。
 *
 * 原版聊天框打开时，`GuiNewChat` 会给整个聊天区铺一层近乎不透明的黑底，而 HUD 玻璃是在
 * `RenderGameOverlayEvent.Post(ALL)` 才画、聊天却在它之前（`CHAT` 元素）就画完了。
 * 所以只要在 HUD 阶段抓屏，抓到的"背后世界"就是聊天黑底，玻璃整块变黑。
 *
 * 约定：
 *  - 帧首（`Pre(ALL)`，原版 HUD 元素之前）抓一次，此时画面里只有世界 / 手部；
 *  - HUD 绘制阶段内**禁止**再抓，所有画布复用帧首那一张；
 *  - 只有真的画过玻璃背景的帧才会在下一帧帧首抓屏，没开玻璃 HUD 时零开销。
 */
class GlassCaptureSchedule {

    /** HUD 绘制阶段内（此时抓屏会抓到原版聊天 / 计分板） */
    var inHudPass: Boolean = false
        private set

    private var usedLastFrame = false
    private var usedThisFrame = false

    /** 本帧帧首是否已经判断/抓取过：同帧不会重复抓，保证帧首那次不被挤掉 */
    private var frameStartHandled = false

    /**
     * 每帧 `Pre(ALL)` 无条件调一次：清掉"本帧已处理"标记。
     *
     * 界面打开时 HUD 会推迟到 `currentScreen` 之后补绘，万一补绘没执行（注入失败 /
     * 异常），`endHudPass` 就不会被调用；没有这行重置，标记会永久卡在 true，
     * 之后每一帧都不再抓帧 → 玻璃背景永远是黑的。
     */
    fun beginFrame() {
        frameStartHandled = false
    }

    /** 帧首（原版 HUD 元素之前）：返回 true 表示此刻应当抓帧（每帧最多一次） */
    fun onFrameStart(): Boolean {
        if (frameStartHandled) return false
        frameStartHandled = true
        return usedLastFrame || usedThisFrame
    }

    /** 本帧有画布请求玻璃背景（`begin(captureBackdrop = true)`） */
    fun noteUsed() {
        usedThisFrame = true
    }

    /** 进入 HUD 绘制阶段：从这里到 [endHudPass] 之间不再抓帧 */
    fun beginHudPass() {
        inHudPass = true
    }

    /** HUD 绘制结束：滚动「本帧用过」标记，供下一次帧首判断 */
    fun endHudPass() {
        inHudPass = false
        usedLastFrame = usedThisFrame
        usedThisFrame = false
        frameStartHandled = false
    }

    /** 离开世界 / 纹理释放：全部归零，下次重新从帧首开始 */
    fun reset() {
        inHudPass = false
        usedLastFrame = false
        usedThisFrame = false
        frameStartHandled = false
    }
}
