# 可复制站点配置

复制 Android 项目后，只修改仓库根目录 `site-profile.properties`。Gradle 会在构建时：

- 设置 launcher/application 显示名；
- 设置 applicationId；
- 注册新的 deep-link scheme；
- 生成 API、发现地址、官方域名和品牌 BuildConfig；
- 按 API/官方域名生成 Mihomo 直连规则；
- 使用 `android.artifact.basename` 生成固定 APK 别名；
- 为每个 profile 使用独立的端点缓存。

Web `/api/endpoints` 下发的 `brand` 会更新客户端内部显示名、线路名称和设备上报名称。
launcher 名称、applicationId 和 deep-link 注册属于 APK 身份，必须通过 GitHub Actions
重新编译；applicationId 或签名变化会被 Android 视为不同应用或无法覆盖升级。

`shenxianyun_*` 本地存储/密钥别名和 `target=shenxianyun` 是向后兼容协议，不是
展示品牌；复制项目时保留它们，业务数据仍由新的 Web 数据库与 `profile.id` 隔离。
