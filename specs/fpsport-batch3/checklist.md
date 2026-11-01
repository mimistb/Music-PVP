# 验证清单

- [x] C1 构建成功，产物为 `DangoMusic-1.5.0+1.8.9.jar`
- [x] C2 `BetterChat.onChatPacket` 在字节码里是 `public static`，与 transformer 的 INVOKESTATIC 一致
- [x] C3 `HudInfoModule` companion 生成静态 `armorSegments/armorWarn/cpsLeft/cpsRight` 访问器
- [x] C4 设置页新分组「护甲与点击速度」可见，护甲 / CPS 子项依赖正确变暗
- [x] C5 方块高亮的颜色色板与透明度滑条可调，ARGB 由调色板 + 透明度合成
- [x] C6 物品物理 6 项参数默认值与旧硬编码等值（90 / 0.5 / 1.1 / 0.15 / 0.05375 / true）
- [x] C7 CPS 开启「只在游戏内统计」后，打开界面点击不计数
- [x] C8 信息胶囊最多 4 基础段 + 4 护甲段 + 1 CPS 段，容量 24 不越界
- [x] C9 BlockOverlay 结尾恢复 blend / alpha / color / depth / lineWidth
- [x] C10 备份 `music pvp 1.5.0+1.8.9.jar` 与 `music pvp 1.5.0+1.8.9-src.zip`
