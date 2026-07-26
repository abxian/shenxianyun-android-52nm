# 吾爱云 Android 52nm v2.11.47

- 52nm 客户端展示品牌统一更新为“吾爱云”。
- 应用名称、线路名称和节点品牌由统一站点配置生成；APK 使用 GitHub 兼容的固定别名 `wuaiyun.apk`、`wuaiyunall.apk`。
- 保留原 applicationId、本地配置键、正式签名和 `shenxianyun://` 深链，可覆盖安装旧版。
- 继续支持一次性 Ticket、加密托管凭据、受保护订阅、心跳、离线和幂等流量协议。
- 国内主 API 为 `https://api.52nm.de:5443`，国外备用 API 为 `https://52nm.de`、`https://www.52nm.de`。
- 本版本只发布到 52nm 独立 GitHub 仓库，不覆盖 sxnn Dufs 或旧仓库 Release。
