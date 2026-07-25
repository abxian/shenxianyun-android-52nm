package com.github.kr328.clash

/**
 * 52nm 站点绑定配置。
 *
 * 更换网站时优先只改本文件。安装包下载/更新是独立发布通道，不应在这里混入
 * 旧神仙云网站地址。
 */
object DomainProfile {
    const val DOMESTIC_API_BASE = "https://api.52nm.de:5443"
    const val DOMESTIC_API_HOST = "api.52nm.de"
    const val OFFICIAL_DOMAIN_SUFFIX = "52nm.de"

    val API_BASES = listOf(
        DOMESTIC_API_BASE,
        "https://52nm.de",
        "https://www.52nm.de",
    )

    val DISCOVERY_URLS = listOf(
        "$DOMESTIC_API_BASE/api/endpoints",
        "https://raw.githubusercontent.com/abxian/shenxianyun-52nm/main/endpoints.json",
        "https://52nm.de/api/endpoints",
        "https://www.52nm.de/api/endpoints",
    )
}
