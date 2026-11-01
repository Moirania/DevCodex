# DevCodex

Android 原生 AI Agent 应用：模型通过真实工具执行任务——读写文件、执行终端命令、运行 Linux 沙箱、连接 SSH、抓取网页，而非仅输出对话文本。

Jetpack Compose 编写，单 Activity 架构，支持多模型接入与本地优先的数据存储。

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF">
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4">
  <img alt="API" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-yellow">
</p>

## 目录

- [功能特性](#功能特性)
- [系统要求](#系统要求)
- [安装](#安装)
- [快速上手](#快速上手)
- [内置工具](#内置工具)
- [扩展机制](#扩展机制)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [文档](#文档)
- [致谢](#致谢)
- [许可证](#许可证)

## 功能特性

### 多提供商接入

- 内置 OpenAI、Anthropic、Google、DeepSeek、智谱、通义、月之暗面、OpenRouter、Ollama 等提供商配置，支持任意 OpenAI 兼容端点
- 会话级模型切换，上下文窗口档位（默认 / 256k / 512k / 1M）
- 六档推理力度调节：Low / Medium / High / Xhigh / Max / Ultra
- Anthropic 扩展思考（thinking）回传与展示

### 真实工具执行

模型可调用 50 余个内置工具完成任务，执行通道按可用性自动选择：Shizuku（ADB 权限）> Root > Termux（RUN_COMMAND）> Linux 沙箱 > toybox。

- 文件系统：读写、复制、移动、搜索（grep / find）、目录树、压缩、权限修改
- Linux 沙箱：PRoot 运行 Alpine 3.21 或 Ubuntu 24.04（rootfs 内置于安装包，无需 root），支持 `apk` / `apt` 安装软件包，工作区目录自动挂载
- Python / Node.js 运行时（沙箱内）
- SSH：远程命令执行与端口转发
- 网络访问：HTTP 请求、网页抓取与渲染、Web 搜索、无头浏览器截图
- 设备控制：应用安装与启动、UI 快照与点击模拟、通知、剪贴板、闹钟、日历、截图、Wi-Fi 状态
- 媒体生成：图片生成、视频生成与状态查询（需配置 media provider）
- 应用构建：APK / JAR / Native 构建工具链

### 权限体系

- 每个工具独立设置 ALLOW（直接执行）/ ASK（执行前询问）/ FORBID（禁止执行）三档权限
- 全局默认策略 + 单工具例外
- 旧版本审批设置自动迁移

### Agent 工程

- 流式 SSE 响应，支持多工具并行调用
- 请求失败自动重试，中断后补写工具结果
- 步数上限控制，防止循环
- 上下文自动压缩，压缩时保留失败工具结果（失败记忆，避免重复踩坑）
- 调试日志：记录请求失败与工具执行详情

### 扩展机制

| 层 | 说明 |
|---|---|
| MCP 服务器 | Streamable / SSE 双协议，工具自动发现注入 |
| HTTP 插件 | JSON 定义 API 到工具的映射，应用内提供开发指南 |
| 自定义工具 | shell 命令模板，`{{参数}}` 占位符 |
| 技能 | Markdown 编写的技能包（SKILL.md 风格），关键词触发 |

### 使用统计

日 / 周 / 月 / 年 / 累计 Token 用量、费用估算、缓存命中率、连续使用天数、活跃日历热力图、模型用量排行、工具调用次数。

### 界面

- Material 3 动态取色，适配系统深色模式与 AMOLED
- 内置 6 种字体（Google Sans Flex、Anthropic Serif、Oplus Serif、JetBrains Mono 等）
- M3L 富文本渲染：Markdown、LaTeX 公式、代码语法高亮、表格
- 流式渲染：思考过程自动展开与收起、内容自动滚动
- 内嵌 VT100 终端模拟器

## 系统要求

- Android 8.0（API 26）及以上
- arm64-v8a 架构
- 约 200 MB 存储空间（含内置 Linux rootfs）

## 安装

从 [Releases](../../releases) 页面下载最新 `DevCodex-*-arm64-release.apk`，直接安装。各版本使用相同签名密钥，可覆盖升级，无需卸载。

使用 Termux 执行通道前，在 Termux 中执行：

```bash
mkdir -p ~/.termux
echo "allow-external-apps=true" >> ~/.termux/termux.properties
```

## 快速上手

1. 启动应用，进入 设置 > 提供商，配置任意一家模型的 API Key（或添加自定义 OpenAI 兼容端点）
2. 回到会话页，选择模型，输入任务
3. 首次使用建议进入 设置 > 权限，将文件读写设为 ALLOW、其余保持 ASK，按需调整单工具权限
4. 模型执行工具时，审批卡片内联显示在对应工具调用处，可直接批准或拒绝

## 内置工具

以下为工具名（模型侧可见）按类别分组：

| 类别 | 工具 |
|---|---|
| 文件 | `fs_read` `fs_write` `fs_list` `fs_copy` `fs_move` `fs_mkdir` `fs_delete` `fs_grep` `fs_find` `fs_head` `fs_tail` `fs_stat` `fs_wc` `fs_chmod` |
| 执行 | `shell_exec` `linux_exec` `python_run` `node_run` `sandbox_pkg_install` `apk_build` `jar_build` `native_build` `pipeline_run` |
| 网络 | `http_request` `web_search` `browser_render` `open_url` `wifi_state` |
| SSH | `ssh_exec` `ssh_forward` |
| 应用与设备 | `app_list` `app_open` `ui_snapshot` `ui_tap` `ui_back` `ui_home` `ui_scroll` `notify` `notify_post` `clipboard_get` `clipboard_write` `screenshot_take` `vibrate` `alarm_set` `calendar_list` `calendar_add` `calendar_open` `share_content` `device_info` |
| 媒体 | `image_generate` `video_generate` `video_status` `image_info` `image_convert` |
| 记忆与技能 | `memory_save` `memory_read` `skill_list` `skill_read` |
| 后台任务 | `bg_list` `bg_kill` |

具体工具的开放状态受权限设置控制。

## 扩展机制

### 内置插件

安装包附带三个 HTTP 插件示例，位于 `app/src/main/assets/plugins/`：

- `builtin_weather.json` — 天气查询
- `builtin_ip.json` — IP 信息
- `builtin_exchange.json` — 汇率查询

### 内置技能

位于 `app/src/main/assets/skills/`：

- `android.md` — Android 开发
- `prompt.md` — 提示词工程
- `writing.md` — 写作

应用内 设置 > 插件开发文档 提供完整的插件与技能编写指南（Markdown 头部 `name` / `description` / `trigger` 字段、工具引用、变量注入等）。

## 从源码构建

环境要求：

- JDK 21
- Android SDK（compileSdk 37）
- AGP 9.4.0 / Kotlin 2.4.10（由 Gradle 版本目录自动解析）

```bash
git clone https://github.com/Moirania/DevCodex.git
cd DevCodex
./gradlew assembleDebug
```

Debug 构建使用仓库内固定密钥 `app/devcodex-debug.p12` 签名（口令 `devcodex-debug`），便于在设备上就地覆盖升级。

Release 构建通过环境变量指定签名密钥：

```bash
export DC_KEYSTORE_PATH=/path/to/keystore
export DC_KEYSTORE_PASSWORD=***
export DC_KEY_ALIAS=devcodex
export DC_KEY_PASSWORD=***
./gradlew assembleRelease
```

## 项目结构

```
app/src/main/kotlin/ai/devcodex/agent/
├── agent/       # Agent 引擎：循环、权限、审批、重试
├── data/        # 数据层：会话、角色卡、设置、提供商预设（DataStore）
├── m3l/         # M3L 富文本渲染：Markdown + LaTeX + 代码高亮
├── plugin/      # 插件系统：MCP、HTTP 插件加载
├── provider/    # 模型提供商：OpenAI 兼容 / Anthropic / 流式解析
├── service/      # 无障碍服务（UI 自动化）
├── shell/       # 执行通道：Shizuku / Root / Termux / PRoot
├── term/        # VT100 终端模拟器内核
├── theme/       # Material 3 主题与动态取色
├── tools/       # 内置工具定义与实现
├── ui/          # Compose 界面（会话 / 设置 / 统计 / 工作区）
└── util/        # 工具类（CrashHandler、DebugLog 等）
```

内置资产位于 `app/src/main/assets/`：Linux rootfs（Alpine / Ubuntu）、Termux bootstrap、proot、busybox、字体、语法高亮规则、内置插件与技能。

## 文档

- 隐私政策与插件开发指南内置于应用（设置页查看，无需网络）
- 版本变更见 [Releases](../../releases)

交流群：QQ 208181604

## 致谢

- [LobeHub icons](https://icons.lobehub.com)（MIT）— 品牌矢量图标
- [OpenMinis](https://github.com/openminis) — VT 终端模拟器内核参考
- [rikkahub](https://github.com/rikkahub/rikkahub) / [rikkahub-agent-pure](https://github.com/wuyhong715/rikkahub-agent-pure) — Agent 设计参考
- [taixu](https://github.com/wkbin/taixu) — Agent 设计参考
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 特权执行通道
- [proot](https://proot-me.github.io/) — 用户态 Linux

## 许可证

[MIT](LICENSE) © 2026 Moirania
