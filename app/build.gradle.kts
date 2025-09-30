import org.gradle.api.GradleException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

val skipGeoDownload = providers.environmentVariable("SKIP_GEO_DOWNLOAD")
    .orElse(providers.gradleProperty("skipGeoDownload").orElse("false"))
    .map { it.equals("true", ignoreCase = true) }

val forceGeoDownload = providers.environmentVariable("FORCE_GEO_DOWNLOAD")
    .orElse(providers.gradleProperty("forceGeoDownload").orElse("false"))
    .map { it.equals("true", ignoreCase = true) }

val geoFilesUrls = mapOf(
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
    // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
)

val downloadGeoFilesTask = tasks.register("downloadGeoFiles") {
    outputs.files(geoFilesUrls.values.map { file("$geoFilesDownloadDir/$it") })

    doLast {
        val skipDownload = skipGeoDownload.orElse(false).get()
        val forceDownload = forceGeoDownload.orElse(false).get()

        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            outputPath.parentFile.mkdirs()

            if (skipDownload) {
                if (outputPath.exists()) {
                    logger.lifecycle("Skip downloading $outputFileName (SKIP_GEO_DOWNLOAD=true and file already exists)")
                    return@forEach
                }

                throw GradleException("SKIP_GEO_DOWNLOAD is true but $outputFileName is missing at ${outputPath.relativeTo(projectDir)}")
            }

            if (!forceDownload && outputPath.exists()) {
                logger.lifecycle("$outputFileName already present, skipping download. Set FORCE_GEO_DOWNLOAD=true to refresh.")
                return@forEach
            }

            val url = URL(downloadUrl)
            url.openStream().use { input ->
                Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("$outputFileName downloaded to ${outputPath.relativeTo(projectDir)}")
            }
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("assemble") }.configureEach {
        dependsOn(downloadGeoFilesTask)
    }

    tasks.matching {
        it.name.startsWith("merge", ignoreCase = true) && it.name.endsWith("Assets")
    }.configureEach {
        dependsOn(downloadGeoFilesTask)
    }

    tasks.matching {
        it.name.startsWith("pre") && it.name.endsWith("Build")
    }.configureEach {
        dependsOn(downloadGeoFilesTask)
    }

    tasks.matching {
        it.name.contains("Lint", ignoreCase = true)
    }.configureEach {
        dependsOn(downloadGeoFilesTask)
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}