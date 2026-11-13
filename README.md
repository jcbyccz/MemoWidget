# 正闲拾笺（MemoWidget）

Android 本地便签 + 待办应用，主打**桌面小部件**：把任意一条便签当作文字便签贴到桌面，随时可见、随手可改。

**数据仅保存在本机**：应用无网络权限、不上传任何内容。卸载或清除数据前，请使用应用内「备份与恢复」导出 JSON。

## 功能

- 便签 + 待办，待办支持勾选
- 桌面文字小部件：1×1 / 2×1 / 2×2 / 4×1 及自由拉伸，可绑定任意一条便签，样式可自定义
- 便签编辑：加粗 / 斜体 / 下划线，主题色
- 应用快捷方式（App Shortcuts）：长按图标快捷「新建便签」「添加桌面小部件」
- 备份与恢复：手动导出/导入 JSON、自动备份（可设置间隔）

## 构建

纯命令行构建，无需 Android Studio：

- JDK 17
- Android SDK Platform 34 + Build-Tools 34
- Gradle 8.7（仓库已含 wrapper，可直接使用 `gradlew`）

```bash
# Windows
gradlew.bat assembleDebug
# Linux / macOS
./gradlew assembleDebug
```

产物位置：`app/build/outputs/apk/debug/app-debug.apk`

首次构建前，把本机 SDK 路径写入 `local.properties`（该文件已被 .gitignore 忽略，不会入库）：

```properties
sdk.dir=D\:\\path\\to\\Android\\Sdk
```

依赖仓库已配置阿里云镜像源（见 `settings.gradle`），国内网络可直接拉取。

## 目录说明

- `app/src/main/java/com/cczsx/notepad/` — Kotlin 源码
- `app/src/main/res/` — 资源（图标、小部件预览图、布局）

## 许可证

[MIT](LICENSE) © 2026 jcbyccz
