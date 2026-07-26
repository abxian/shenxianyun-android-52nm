import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin
import java.net.URI
import java.util.Properties

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")
val siteProfileFile = rootProject.file("site-profile.properties")
val generatedSiteProfileGo = file("src/main/golang/native/config/site_profile_generated.go")
val generateSiteProfileGo by tasks.registering {
    inputs.file(siteProfileFile)
    outputs.file(generatedSiteProfileGo)
    doLast {
        val profile = Properties().apply {
            siteProfileFile.inputStream().use { load(it) }
        }
        fun values(key: String): List<String> =
            profile.getProperty(key)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: error("site-profile.properties missing $key")
        val directRules = linkedSetOf<String>()
        values("api.bases").forEach { value ->
            val host = URI(value).host ?: error("api.bases contains an invalid URL: $value")
            directRules += "DOMAIN,$host,DIRECT"
        }
        values("official.domain.suffixes").forEach { suffix ->
            directRules += "DOMAIN-SUFFIX,$suffix,DIRECT"
        }
        val source = buildString {
            appendLine("// Code generated from site-profile.properties by Gradle. DO NOT EDIT.")
            appendLine("package config")
            appendLine()
            appendLine("var backendDirectRules = []string{")
            directRules.forEach { rule ->
                appendLine("\t\"${rule.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
            }
            appendLine("}")
        }
        generatedSiteProfileGo.writeText(source)
    }
}

golang {
    sourceSets {
        create("alpha") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        dependsOn(generateSiteProfileGo)
        it.inputs.dir(golangSource)
        it.inputs.file(siteProfileFile)
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
