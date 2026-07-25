# 52nm Android 网站换绑手册

本仓库是 Android 客户端的 52nm 站点绑定版本：

- 国内主线路：`https://api.52nm.de:5443`
- 国外备用线路：`https://52nm.de`、`https://www.52nm.de`（443）
- 公共发现备份：PC 仓库 `abxian/shenxianyun-52nm` 根目录
  `endpoints.json`

## 后续更换网站域名

1. 先部署兼容的新 vpn-web，保留旧入口。
2. 为新 API 域名配置可信证书，验证 `/api/app-version` 和
   `/api/endpoints`。
3. 修改
   `app/src/main/java/com/github/kr328/clash/DomainProfile.kt` 中的国内线路、
   备用线路、发现入口和官网注册域。
4. 同步修改 PC 仓库的 `src/config/domain-profile.ts` 与根目录
   `endpoints.json`。
5. 如果注册域发生变化，同步修改
   `core/src/main/golang/native/config/process.go` 的直连规则；该文件属于
   Mihomo 内核生成配置，无法直接引用 Kotlin 常量。
6. 执行 Kotlin/Go 静态检查和 `git diff --check`，推送后运行 GitHub Actions
   `Build Android APK`。正式分发必须复用原 Android 签名密钥。
7. 真机验证提取码、短票据导入、订阅更新、心跳、流量、VPN 启停、线路切换和
   覆盖安装。观察期结束后再移除旧入口。

PC 与 Android 是独立发布单元；两端必须分别提交、编译和真机验收。
