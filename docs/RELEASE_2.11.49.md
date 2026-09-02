# 吾爱云 Android 52nm v2.11.49

- 在线心跳间隔由 60 秒改为 120–140 秒抖动区间，避免大量设备同一时刻齐发造成后台峰值。
- 心跳上报失败时把状态写入本地并只保留最新一条，网络恢复后由下一轮覆盖上报，不堆积、不重复。
- 继续使用 52nm 独立后端、独立 GitHub Release 和吾爱云品牌，不与 sxnn 数据或分发文件混用。

**Full Changelog**: https://github.com/abxian/shenxianyun-android-52nm/compare/v2.11.48...v2.11.49

---
（补记于 2026-09-02：本文件在 v2.11.49 发版时缺失，当时工作流 `body_path` 硬编码为
`docs/RELEASE_2.11.48.md`，导致该版 Release 正文误用了 v2.11.48 的发布说明。
根因已由 `dc6273b` 修复——`body_path` 改为按 `docs/RELEASE_${VERSION}.md` 动态取。）
