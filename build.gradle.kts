@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.net.URL
import java.util.*

val siteProfile = Properties().apply {
    rootProject.file("site-profile.properties").inputStream().use { load(it) }
}

fun siteProfileValue(key: String): String =
    siteProfile.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("site-profile.properties missing $key")

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

require(Regex("^[a-z][a-z0-9+.-]{1,31}$").matches(siteProfileValue("deep.link.scheme"))) {
    "deep.link.scheme must be a valid URI scheme"
}
require("{code}" in siteProfileValue("subscription.name.template")) {
    "subscription.name.template must contain {code}"
}
require(
    siteProfileValue("android.artifact.basename").length <= 64 &&
        !Regex("""[\\/:*?"<>|\u0000-\u001f]""").containsMatchIn(siteProfileValue("android.artifact.basename"))
) {
    "android.artifact.basename must be a safe file basename"
}
listOf("api.domestic.base", "api.bases", "discovery.urls").forEach { key ->
    siteProfileValue(key).split(',').map(String::trim).filter(String::isNotEmpty).forEach {
        java.net.URI(it).toURL()
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        // 优先读 gradle 属性（gradle.properties / -P / ORG_GRADLE_PROJECT_*），
        // 其次回退到 local.properties。这样 custom.application.id 等可写在
        // 已提交的 gradle.properties 里，在 CI 构建中也生效。
        project.findProperty(key)?.let { return it }
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            val localProperties = Properties()
            localProperties.load(localPropertiesFile.inputStream())
            return localProperties.getProperty(key)
        }
        return null
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true }
                    ?: siteProfileValue("android.application.id")
                manifestPlaceholders["managedImportScheme"] = siteProfileValue("deep.link.scheme")
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = 21
            targetSdk = 35

            versionName = "2.11.47"
            versionCode = 211047

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")
            buildConfigField("String", "SITE_PROFILE_ID", buildConfigString(siteProfileValue("profile.id")))
            buildConfigField("String", "SITE_NAME", buildConfigString(siteProfileValue("site.name")))
            buildConfigField("String", "CLIENT_NAME", buildConfigString(siteProfileValue("client.name")))
            buildConfigField("String", "NODE_BRAND", buildConfigString(siteProfileValue("node.brand")))
            buildConfigField("String", "SUBSCRIPTION_NAME_TEMPLATE", buildConfigString(siteProfileValue("subscription.name.template")))
            buildConfigField("String", "MANAGED_IMPORT_SCHEME", buildConfigString(siteProfileValue("deep.link.scheme")))
            buildConfigField("String", "DOMESTIC_API_BASE", buildConfigString(siteProfileValue("api.domestic.base")))
            buildConfigField("String", "API_BASES", buildConfigString(siteProfileValue("api.bases")))
            buildConfigField("String", "DISCOVERY_URLS", buildConfigString(siteProfileValue("discovery.urls")))
            buildConfigField("String", "OFFICIAL_DOMAIN_SUFFIXES", buildConfigString(siteProfileValue("official.domain.suffixes")))

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "cmfa-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            val removeSuffix = (queryConfigProperty("remove.suffix") as? String)?.toBoolean() == true

            create("alpha") {
                isDefault = true
                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Alpha"
                }


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", "${siteProfileValue("client.name")} Alpha")
                resValue("string", "application_name", "${siteProfileValue("client.name")} Alpha")

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".alpha"
                }
            }

            create("meta") {

                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Meta"
                }

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", siteProfileValue("client.name"))
                resValue("string", "application_name", siteProfileValue("client.name"))

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".meta"
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val keystore = rootProject.file("signing.properties")
            if (keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    storeFile = rootProject.file("release.keystore")
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}
