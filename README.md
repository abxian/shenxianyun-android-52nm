# 吾爱云 Android 客户端

这是吾爱云安卓客户端，基于 Clash Meta for Android 改造。目标是保留稳定的原版 VPN/TUN 开关和 Mihomo 内核能力，同时把使用流程简化为提取码订阅、一键启动、节点选择。

统一品牌、应用身份和连接地址配置见 `docs/SITE_PROFILE.md`。

## 52nm 域名配置

本仓库绑定 52nm 独立 vpn-web：

- 国内 API：`https://api.52nm.de:5443`
- 国外备用：`https://52nm.de`、`https://www.52nm.de`
- 换绑流程：[`docs/DOMAIN_MIGRATION_52NM.md`](./docs/DOMAIN_MIGRATION_52NM.md)

运行时地址集中在 `DomainProfile.kt`，不会读取原神仙云的端点发现源。

## 当前功能

- 提取码订阅
  - 用户首次打开后输入后台提取码。
  - 客户端自动请求 `https://api.52nm.de:5443/api/verify/<code>`。
  - 验证成功后自动创建 URL 订阅配置，保存提取码、配置 UUID、过期时间、订阅更新版本。
  - 同一设备首次导入同一提取码时，后台记录一次提取次数。
  - 自动更新订阅不重复记录提取次数。
- 代理开关
  - 使用原版 Clash Meta for Android 的 VPN 启停能力。
  - 提取码未过期时允许开启代理。
  - 提取码过期后禁止开启代理。
  - 已保存过期时间后，断网时也按本地过期时间判断。
- 订阅更新
  - 每个客户端进程打开后，已有提取码会自动完整刷新一次受管订阅；失败时保留原配置。
  - 客户端定时请求 `/api/update-state/<code>`。
  - 后台推送更新后，客户端静默更新订阅。
  - 连接期间复用每 60 秒心跳同步最新到期时间；未连接但首页在前台时每 60 秒轻量同步。
  - 首页显示当前提取码的最新到期时间，轻量同步不会下载订阅或增加导入次数。
  - 客户端启动时检查 APK 更新。
- 在线统计
  - VPN 启动后向 `/api/client/heartbeat/<code>` 上报在线。
  - VPN 停止后向 `/api/client/offline/<code>` 上报离线。
- 一键导入
  - 支持网页打开 `shenxianyun://install-config?url=<订阅地址>&name=<名称>`。
  - 安卓端会自动解析订阅地址中的提取码，保存并导入，不再进入原配置编辑页。
- UI 简化
  - 首页突出启动按钮、模式切换、提取码、节点选择。
  - 续费/新购入口合并，已有提取码时进入续费，没有提取码时进入新购。

## 目录结构

```text
clashmeta-android/
  build.gradle.kts                         # 全局版本号、构建配置
  local.properties                         # 本地 Android SDK 路径，不提交
  app/
    src/main/AndroidManifest.xml           # 应用、协议、Activity 注册
    src/main/java/com/github/kr328/clash/
      MainActivity.kt                      # 神仙云主流程、提取码、更新、心跳
      ExternalControlActivity.kt           # shenxianyun:// 一键导入
  design/
    src/main/res/layout/design_main.xml    # 首页布局
    src/main/res/values/strings.xml        # 文案
  core/                                    # Mihomo/Clash 内核桥接
  service/                                 # VPN 服务、配置处理
```

## 环境要求

推荐环境：

- JDK 21
- Android Studio 或 Android SDK Command-line Tools
- Android SDK Platform 35
- Android Build Tools
- Android NDK 29.0.14206865
- CMake
- Go，项目内核构建会用到
- Git

本机 `local.properties` 示例：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

## 检查环境

```powershell
java -version
.\gradlew.bat --version
```

如果 Gradle 能正常输出版本，说明 JDK 和 Wrapper 基本可用。

## 修改版本号

版本号在根目录 `build.gradle.kts`：

```kotlin
versionName = "2.11.31"
versionCode = 211031
```

给用户推送 APK 更新时，后台 `latest_version_code` 必须大于用户当前安装版本的 `versionCode`。

## GitHub Actions 编译

安卓端完整编译统一使用 GitHub Actions，本机只做静态检查、轻量测试和差异审查。推荐工作流是 `.github/workflows/build-apk-simple.yaml`。

未准备发布时，推送隔离分支并手动编译该分支：

```bash
git push -u origin <branch>
gh workflow run build-apk-simple.yaml --ref <branch>
gh run list --workflow build-apk-simple.yaml --limit 1
```

这种验证不得修改 `versionName`/`versionCode`、打 tag、创建 Release、上传 Dufs 或更新后台版本元数据。Actions artifact 只是临时编译产物，不等于发布新版。

工作流基本步骤：

1. checkout
2. setup JDK 21
3. setup Android SDK、NDK、CMake
4. 写入 `local.properties`
5. 如果构建 Release，写入签名文件和 `signing.properties`
6. 执行 `./gradlew :app:assembleMetaDebug` 或 `assembleMetaRelease`
7. 上传 `app/build/outputs/apk/meta/**.apk`

正式发布使用 `.github/workflows/release-android.yaml`：版本提交必须位于
`main`，推送与 `versionName` 完全一致的 `v*.*.*` 标签后，工作流强制检查
4 个签名 Secret、构建 Meta Release APK，并创建非预发布的 GitHub Release。
应用内品牌保持“吾爱云”；GitHub 会改写中文 Release 资产名，因此固定下载
别名使用 `wuaiyun.apk` 和 `wuaiyunall.apk`，避免生成 `a.c.a.apk` 一类乱码名。

## 后台接口

默认后台地址集中在 `DomainProfile.kt`：

```kotlin
const val DOMESTIC_API_BASE = "https://api.52nm.de:5443"
```

客户端使用：

```text
GET /api/verify/<code>
GET /api/update-state/<code>
GET /api/app-version
GET /api/client/heartbeat/<code>
GET /api/client/offline/<code>
GET /sub/<code>
```

首次输入提取码和网页一键导入都会请求：

```text
/api/verify/<code>?import=1&client_id=<设备ID>
```

后台使用 `client_id` 去重，所以同一设备同一提取码只计一次。

## 一键导入协议

AndroidManifest 注册：

```text
shenxianyun://install-config
```

网页按钮格式：

```text
shenxianyun://install-config?url=<encoded subscription url>&name=<encoded name>
```

`ExternalControlActivity.kt` 会：

1. 解析 `url`。
2. 从 `/sub/<code>` 或 `?code=<code>` 中提取提取码。
3. 验证提取码并记录首次导入。
4. 创建或更新当前订阅。
5. 打开主界面。

## 常见修改点

- 改后台域名：`MainActivity.kt`、`ExternalControlActivity.kt`。
- 改版本号：根目录 `build.gradle.kts`。
- 改首页 UI：`design/src/main/res/layout/design_main.xml`。
- 改首页逻辑：`app/src/main/java/com/github/kr328/clash/MainActivity.kt`。
- 改一键导入：`ExternalControlActivity.kt`。
- 改应用名称和图标：`design/src/main/res/values/strings.xml`、`app/src/main/res/mipmap-*`。
- 改 VPN 服务行为：`service/` 目录。

## 发布前检查

1. 安装 APK。
2. 首次输入提取码，确认后台提取次数加 1。
3. 再次打开会自动刷新一次订阅；确认旧配置在刷新失败时保留，且提取次数不重复增加。
4. 点击启动，系统弹出 VPN 授权并能联网。
5. Chrome 打开网页确认 VPN 不自动退出。
6. 后台在线客户端页面能看到安卓设备。
7. 停止代理后后台状态离线。
8. 网页 `一键导入神仙云` 能打开 APP 并自动导入。
9. 过期提取码不能启动代理。
10. 网站续费后，连接中或未连接但首页停留时约 60 秒内显示新的到期时间。

## 当前版本

当前本地版本：

```text
versionCode: 211047
versionName: 2.11.47.Meta
```

52nm 后台 APK 更新配置里的 `latest_version_code` 要使用 `211047` 或更高。

## 神仙云发布与分发流程（安卓端）

> 每次更新提交、编译、发布都要遵循以下流程。PC 桌面端流程见
> [shenxianyun](https://github.com/abxian/shenxianyun) 的 README。

### 一、只有准备发布时才升版本号

安卓端版本号在 **`build.gradle.kts`** 一处（约第 64~65 行）：

```kotlin
versionName = "2.11.33"
versionCode = 211033
```

- `versionName` 用语义版本（如 `2.11.33`）。
- `versionCode` 用 `211033` 这种整数，**每次发布必须比上一次大**（后台 APK 更新检测、Play 安装升级都靠它）。
- 普通开发、预览验证和 Actions 编译不得升版本号。
- 获得发布批准后，升级版本并同步后台 `latest_version_code`。

### 二、提交并触发 GitHub Actions 编译

Build Android APK（`.github/workflows/build-apk-simple.yaml`）支持手动 `workflow_dispatch`。
未发布改动先在隔离分支验证；只有明确准备合入/发布时才推送 `main`。

```bash
git add <本任务文件>
git commit -m "feat: xxx (v2.11.33)"
git push -u origin <branch>
gh workflow run build-apk-simple.yaml --ref <branch>
```

构建会拉取 clash 内核子模块、应用 Go 补丁、执行 `./gradlew app:assembleMetaRelease`，
产物作为 **workflow artifact `<仓库名>-apk`** 上传（非 GitHub Release）。
APK 按 ABI 拆分 + 一个 universal 包，文件名形如：

| 用途 | Action 产物文件名 |
| --- | --- |
| 常用手机包（arm64） | `cmfa-<ver>-meta-arm64-v8a-release.apk` |
| 全架构通用包 | `cmfa-<ver>-meta-universal-release.apk` |

（`<ver>` 即 `versionName`，如 `2.11.33`；另有 armeabi-v7a / x86 / x86_64 包。）

需要发布预览版时，使用 `Build Pre-Release`
（`.github/workflows/build-pre-release.yaml`）对指定隔离分支执行
`workflow_dispatch`。该流程构建签名的 Alpha Release APK，并更新固定的
`Prerelease-alpha` GitHub Pre-release；tag 必须指向本次 Actions 的实际
`github.sha`。预览发布不得替换正式 tag、Dufs 固定 APK 或后台正式升级元数据。

### 三、签名密钥（重要，要记清楚）

- **签名密钥统一存放在私有仓库
  [`abxian/shenxianyun-keys`](https://github.com/abxian/shenxianyun-keys) 的 `android/` 目录**，
  绝不能提交进本公开仓库。包含 `release.keystore`（PKCS12）、`release.keystore.base64`、
  `keystore-password.txt` 等，详见该目录的 README。
  - 别名 alias：`shenxianyun`；store 与 key 同密码（见 `keystore-password.txt`）。
  - ⚠️ Android 覆盖安装要求新旧 APK **同一密钥**，此 keystore 丢了老用户就无法升级，务必保管好。
- **CI 已接通正式签名**：公开仓库已配置 4 个 Actions Secret —
  `SIGNING_KEYSTORE_BASE64`（keystore 的 base64）、`SIGNING_STORE_PASSWORD`、
  `SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
  `build-apk-simple.yaml` 的「Use release signing」步骤会把 base64 解码成 `release.keystore`
  并写 `signing.properties`，`build.gradle.kts` 的 `signingConfigs` 检测到即启用 **release 正式签名**
  （4 个 secret 缺任一则回退 debug 签名）。
- 需要重配 secret 时，按 `shenxianyun-keys/android/README.md` 里的 `gh secret set` 命令执行。
- 如需本地手动签名（不走 CI）：取 `release.keystore` 后
  `apksigner sign --ks release.keystore --ks-key-alias shenxianyun ...`。

### 四、52nm 分发边界

本仓库的正式 APK 只发布到 `abxian/shenxianyun-android-52nm` 的 GitHub
Release，不上传或覆盖原神仙云 Dufs，也不写入旧客户端仓库。后续如果要建立
52nm 独立下载站，先配置独立存储、域名和凭据，再增加发布步骤。

未发布验证从 Action artifact 下载；正式版本从对应 `v*.*.*` Release 下载。
可按下表选择安装包：

| 固定分发名 | 来源产物 |
| --- | --- |
| `wuaiyun.apk`（常用手机） | `cmfa-<ver>-meta-arm64-v8a-release.apk` |
| `wuaiyunall.apk`（全架构通用） | `cmfa-<ver>-meta-universal-release.apk` |

> 正式分发仍须使用原 Android 签名密钥，保证可覆盖安装。

### 五、流程速记（每次发布都照做）

1. 改代码 → 改 `build.gradle.kts` 的 `versionName` / `versionCode`（code 必须递增）。
2. `git commit` → `git push origin main`，自动触发 Build Android APK。
3. 等 Action 跑完 → 下载 artifact `shenxianyun-android-apk` 并解压。
4. 先真机验证；获得正式发布批准后，推送 `v<versionName>` 标签。
5. 等 `Release Android` 成功，核对 Release 为非 draft、非 prerelease，
   APK 证书与 `shenxianyun-keys` 中的正式证书一致，再更新 52nm 后台下载地址。
