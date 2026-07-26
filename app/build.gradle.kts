import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

task("downloadGeoFiles") {

    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // 不内置 ASN 数据库（~6MB，仅 ASN 分流规则才用；需要时 clash 会按 geox-url 自动下载），减小体积
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
    )

    doLast {
        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val url = URL(downloadUrl)
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            outputPath.parentFile.mkdirs()
            if (outputPath.exists()) {
                println("$outputFileName already exists, skipping download")
            } else {
                runCatching {
                    url.openStream().use { input ->
                        Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        println("$outputFileName downloaded to $outputPath")
                    }
                }.onFailure {
                    println("skip downloading $outputFileName: ${it.message}")
                }
            }
        }
    }
}

val appSiteProfile = Properties().apply {
    rootProject.file("site-profile.properties").inputStream().use { load(it) }
}
val artifactBasename = appSiteProfile.getProperty("android.artifact.basename")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: error("site-profile.properties missing android.artifact.basename")

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }

    tasks.findByName("assembleMetaRelease")?.finalizedBy("copyBrandedReleaseApks")
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}

tasks.register("copyBrandedReleaseApks") {
    doLast {
        val releaseDir = file("build/outputs/apk/meta/release")
        val arm64Apk = releaseDir.listFiles()
            ?.firstOrNull { it.name.endsWith("-meta-arm64-v8a-release.apk") }
        val universalApk = releaseDir.listFiles()
            ?.firstOrNull { it.name.endsWith("-meta-universal-release.apk") }

        requireNotNull(arm64Apk) { "arm64 release APK not found in ${releaseDir.path}" }
        requireNotNull(universalApk) { "universal release APK not found in ${releaseDir.path}" }

        val arm64Name = "$artifactBasename.apk"
        val universalName = "${artifactBasename}all.apk"
        Files.copy(arm64Apk.toPath(), releaseDir.resolve(arm64Name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(universalApk.toPath(), releaseDir.resolve(universalName).toPath(), StandardCopyOption.REPLACE_EXISTING)
        println("Copied signed release APKs: $arm64Name, $universalName")
    }
}
