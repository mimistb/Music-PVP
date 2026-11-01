# tasks — UI 精修批次（batch5）

- [x] T1 设置页分段：段标题通栏 + 段内两列瀑布流 + 卡片淡描边（SettingsPage）
- [x] T2 滚动位置持久化：ScreenState 五个字段 + 各页面读/写回（Library/GridPage/TracksPage/Sidebar/SettingsPage/MusicScreen）
- [x] T3 TextInput 统一输入态（光标/选区/Ctrl+A·C·X·V）+ Keys 键码 + 四处输入框接入
- [x] T4 方块高亮色板 8 色（黑 / 浅灰）+ 深色色块描边 + FpsToolsModule 范围 0..7
- [x] T5 HudInfo 拆成独立胶囊（addBlock / chipWidth / 分块 drawGlass）
- [x] T6 自查与全量构建通过（BUILD SUCCESSFUL）
- [ ] T7 version.properties → 1.7.0
- [ ] T8 以 1.7.0 重新构建
- [ ] T9 备份 jar + src.zip 到 music pvp 目录
- [ ] T10 中文交付说明

## 验证命令

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot'
.\gradlew.bat --no-daemon --console=plain --offline build
```

cwd = `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-main\legacy`
