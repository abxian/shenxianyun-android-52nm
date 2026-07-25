# 神仙云 Android 客户端开发文档

本文档用于 `vpnandroid` 仓库后续开发维护。项目基于 Clash Meta for Android 改造，保留原版 VPN 服务能力，同时把入口改成提取码订阅。

## 1. 项目定位

Android 客户端负责：

- 输入提取码并自动导入订阅。
- 启动/停止 VPN。
- 支持规则模式/全局模式。
- 选择节点。
- 定时检查后台订阅更新。
- 检查 APK 更新。
- 向后台上报在线设备。
- 支持网页 `shenxianyun://` 一键导入。

默认后台：

```text
https://sub.jc116.com
```

## 2. 仓库和分支

远端：

```text
https://github.com/abxian/vpnandroid.git
```

主要分支：

```text
main
```

## 3. 目录结构

```text
clashmeta-android/
  README.md
  DEVELOPMENT.md
  build.gradle.kts                 # 版本号、构建配置、签名配置
  local.properties                 # SDK 路径，不提交
  signing.properties               # 签名密码，不要公开
  release.keystore                 # 正式签名，不要公开
  app/
    src/main/
      AndroidManifest.xml          # Activity、scheme、权限
      java/com/github/kr328/clash/
        MainActivity.kt            # 主页面业务、提取码、更新、心跳
        ExternalControlActivity.kt # 一键导入和外部控制
        ProxyActivity.kt           # 代理页
        PropertiesActivity.kt      # 原配置属性页
  design/
    src/main/
      res/layout/design_main.xml   # 首页布局
      res/values/strings.xml       # 文案
      java/.../MainDesign.kt       # 首页 Design 层
  service/                         # Android VPN service 和配置管理
  core/                            # Clash/Mihomo core bridge
  common/                          # 通用工具
```

## 4. 环境要求

推荐：

- JDK 21
- Android SDK Platform 35
- Android Build Tools
- Android NDK 29.0.14206865
- CMake
- Go
- Git

检查：

```powershell
java -version
.\gradlew.bat --version
```

`local.properties` 示例：

```properties
sdk.dir=C\:\\Users\\fucku\\AppData\\Local\\Android\\Sdk
```

## 5. 版本号

位置：

```text
build.gradle.kts
```

示例：

```kotlin
versionName = "2.11.31"
versionCode = 211031
```

后台 APK 更新比较的是 `versionCode`。要触发更新，后台 `latest_version_code` 必须大于用户已安装 APK 的 `versionCode`。

Release 包显示版本：

```text
2.11.31.Meta
```

Debug 包显示版本：

```text
2.11.31.Meta.debug
```

## 6. Debug 和 Release 区别

Debug：

- 调试签名。
- 适合本地临时测试。
- 可能不能覆盖正式包。
- 性能和体积不是正式状态。

Release：

- 使用 `release.keystore` 签名。
- 正式分发推荐。
- 经过 R8/资源压缩。
- 用户后续更新必须使用同一个签名。

正式 APK 更新必须优先使用 Release 包。

## 7. 签名文件

项目当前使用：

```text
release.keystore
signing.properties
```

`signing.properties` 格式：

```properties
keystore.password=...
key.alias=...
key.password=...
```

注意：

- 不要公开签名文件和密码。
- 更换签名后，用户无法直接覆盖安装旧版本。
- GitHub Actions 如果要打 Release，需要用 GitHub Secrets 注入签名文件。

## 8. GitHub Actions 编译

完整编译和 APK 打包统一使用 GitHub Actions，本机不作为正式编译结论来源。本机只运行静态检查、轻量测试和差异审查。

未准备发布时使用隔离分支：

```bash
git push -u origin <branch>
gh workflow run build-apk-simple.yaml --ref <branch>
```

工作流 artifact 仅用于编译验证，不等于发布。没有明确发布批准时不得修改版本、打 tag、创建 Release、上传 Dufs 或更新后台版本元数据。

Release 产物：

```text
app/build/outputs/apk/meta/release/
  cmfa-2.11.31-meta-universal-release.apk
  cmfa-2.11.31-meta-arm64-v8a-release.apk
  cmfa-2.11.31-meta-armeabi-v7a-release.apk
  cmfa-2.11.31-meta-x86-release.apk
  cmfa-2.11.31-meta-x86_64-release.apk
```

推荐上传：

```text
cmfa-2.11.31-meta-universal-release.apk
```

如果想减小包体，可只给普通手机上传：

```text
cmfa-2.11.31-meta-arm64-v8a-release.apk
```

但不同架构用户可能无法安装，所以通用包最省心。

## 9. 主业务流程

### 9.1 手动输入提取码

入口：

```text
MainActivity.kt
```

函数：

- `showCodeImportDialog()`
- `importSubscriptionCode(code, silent)`
- `verifyActivationCode(code, countImport)`

流程：

1. 用户输入提取码。
2. 创建或更新一个 URL Profile。
3. 请求：

```text
GET /api/verify/<code>?import=1&client_id=<device-id>
```

4. 后台记录首次导入次数。
5. 保存：

```text
KEY_CODE
KEY_PROFILE_UUID
KEY_EXPIRES_AT
KEY_UPDATE_VERSION
KEY_CLIENT_ID
```

6. 拉取订阅并设为当前配置。

### 9.2 自动更新订阅

函数：

```text
checkSubscriptionUpdate()
queryUpdateVersion(code)
```

流程：

1. 定时请求 `/api/update-state/<code>`。
2. 如果远端版本大于本地 `KEY_UPDATE_VERSION`，调用 `importSubscriptionCode(code, silent = true)`。
3. 静默更新不带 `import=1`，不会重复计数。

### 9.3 开启代理

函数：

```text
startClash()
isActivationStillValid(code)
```

逻辑：

- 没有提取码：提示导入。
- 本地过期时间已过期：禁止开启。
- 有有效 Profile：调用原版 `startClashService()`。
- 如系统需要授权，会弹 VPN 授权窗口。

### 9.4 在线统计

事件：

- `Event.ClashStart` 上报在线。
- `Event.ClashStop` 上报离线。
- VPN 运行期间每 30 秒心跳一次。

接口：

```text
GET /api/client/heartbeat/<code>
GET /api/client/offline/<code>
```

参数：

```text
client_id
platform
app_name
app_version
device_name
```

### 9.5 APK 更新

函数：

```text
checkAppUpdate()
```

接口：

```text
GET /api/app-version
```

如果后台 `latest_version_code` 大于本机 `versionCode`，显示更新弹窗。

## 10. 一键导入

AndroidManifest 注册：

```xml
<data
    android:host="install-config"
    android:scheme="shenxianyun" />
```

网页链接：

```text
shenxianyun://install-config?url=<encoded-sub-url>&name=<encoded-name>
```

处理：

```text
ExternalControlActivity.kt
```

流程：

1. 读取 `url`。
2. 从 `/sub/<code>` 或 `?code=<code>` 提取提取码。
3. 验证 `/api/verify/<code>?import=1&client_id=...`。
4. 保存提取码、过期时间、版本。
5. 创建或更新 Profile。
6. 打开 `MainActivity`。

如果 URL 中无法识别提取码，则回退为普通外部订阅导入。

## 11. 关键文件

### `MainActivity.kt`

最重要的业务逻辑：

- 提取码输入。
- 订阅导入。
- 更新检测。
- 在线心跳。
- VPN 启停。
- 新购/续费跳转。

### `ExternalControlActivity.kt`

外部协议和自动化控制：

- `shenxianyun://install-config`
- START/STOP/TOGGLE intent

### `design_main.xml`

首页 UI：

- 大启动按钮。
- 模式选择。
- 提取码区域。
- 节点选择。
- 新购/续费。

### `MainDesign.kt`

首页状态绑定和事件请求。

### `service/`

原版 Clash Meta Android 的 VPN 服务和配置处理，除非明确知道原因，不要随意大改。

## 12. 常见问题

### 12.1 启动后自动停止

检查：

- logcat 崩溃日志。
- Profile 是否成功导入。
- Clash 配置是否有重复 rule-set。
- 提取码是否被判断过期。
- VPN 权限是否授予。
- 后台域名是否直连。

### 12.2 点击一键导入没有反应

检查：

- 安装的是新 APK。
- AndroidManifest 是否有 `shenxianyun://install-config`。
- 浏览器是否允许打开 APP。
- `ExternalControlActivity` 是否崩溃。

### 12.3 更新完还提示更新

检查：

- APK 实际 `versionCode`。
- 后台 `latest_version_code` 是否大于当前。
- 用户安装的是 Debug 还是 Release。
- 是否上传了旧 APK。

### 12.4 覆盖安装失败

签名不一致。必须保证后续 Release 都用同一个 `release.keystore`。

## 13. 发布前检查

1. 打 Release 包。
2. 安装到手机。
3. 首次输入提取码，后台次数加 1。
4. 重复输入同一提取码，次数不重复加。
5. 开启 VPN，出现 VPN 图标，可上网。
6. 打开 Chrome，VPN 不退出。
7. 规则/全局模式可切换。
8. 节点选择生效。
9. 后台在线客户端显示安卓设备和 IP。
10. 停止 VPN 后后台离线。
11. 网页一键导入神仙云可自动导入。
12. 过期提取码不能开启 VPN。
13. APK 更新弹窗可打开下载地址。

## 14. Git 提交规则

每次修改后必须提交并推送；未准备发布时推送隔离分支：

```powershell
git status --short
git add <files>
git commit -m "type: message"
git push -u origin <branch>
```

提交类型：

- `feat: ...`
- `fix: ...`
- `docs: ...`
- `style: ...`
- `chore: ...`
- `build: ...`

提交前至少运行本地静态检查和差异审查：

```bash
git diff --check
```

推送后使用 GitHub Actions 完整编译：

```bash
gh workflow run build-apk-simple.yaml --ref <branch>
```

不要提交：

- `local.properties`
- `signing.properties`
- `release.keystore`
- APK 产物
- Gradle cache
- 私密密码和密钥
