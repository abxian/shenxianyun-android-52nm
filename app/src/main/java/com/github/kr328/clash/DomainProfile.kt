package com.github.kr328.clash

/** 由仓库根目录 site-profile.properties 在构建时生成。 */
object DomainProfile {
    val PROFILE_ID = BuildConfig.SITE_PROFILE_ID
    val SITE_NAME = BuildConfig.SITE_NAME
    val CLIENT_NAME = BuildConfig.CLIENT_NAME
    val NODE_BRAND = BuildConfig.NODE_BRAND
    val SUBSCRIPTION_NAME_TEMPLATE = BuildConfig.SUBSCRIPTION_NAME_TEMPLATE
    val MANAGED_IMPORT_SCHEME = BuildConfig.MANAGED_IMPORT_SCHEME
    val DOMESTIC_API_BASE = BuildConfig.DOMESTIC_API_BASE.trimEnd('/')
    val DOMESTIC_API_HOST = java.net.URI(DOMESTIC_API_BASE).host
    val OFFICIAL_DOMAIN_SUFFIXES = BuildConfig.OFFICIAL_DOMAIN_SUFFIXES
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    val OFFICIAL_DOMAIN_SUFFIX = OFFICIAL_DOMAIN_SUFFIXES.first()

    val API_BASES = BuildConfig.API_BASES
        .split(',')
        .map { it.trim().trimEnd('/') }
        .filter(String::isNotEmpty)

    val DISCOVERY_URLS = BuildConfig.DISCOVERY_URLS
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
