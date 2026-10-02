import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.homecam.app"
    compileSdk = 37

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.homecam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // libwebrtc and TFLite each ship a native library per ABI, and they dominate the
        // APK — all four ABIs is 119 MB against 30 for one. Both phones in this project
        // are arm64; pass -Phomecam.abis=arm64-v8a,x86_64 to add the emulator's, or the
        // full set for a release meant to run anywhere.
        ndk { abiFilters += (findProperty("homecam.abis") as String? ?: "arm64-v8a").split(",") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

    buildFeatures { compose = true }

    androidResources {
        // A compressed .tflite cannot be memory-mapped, and the interpreter falls back to
        // copying the whole model into the heap on every load.
        noCompress += "tflite"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.webrtc.android)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.media3.common)
    implementation(libs.litert)

    implementation(libs.java.websocket)
    implementation(libs.slf4j.api)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

/**
 * Android 15 devices may use a 16 KB memory page, and a native library whose LOAD segments
 * are aligned to 4 KB cannot be mapped on one. Every `.so` in this APK comes from a
 * prebuilt dependency, so the property is entirely decided by which versions are pinned —
 * which makes it exactly the kind of thing that regresses silently on a routine bump.
 *
 * So it is checked at build time rather than trusted. The ELF headers are parsed here
 * instead of shelling out to `readelf`, because the NDK is not a build dependency of this
 * project and adding one to run a check would be a poor trade.
 *
 * One task per variant, taking that variant's APK directory as a declared input. Globbing
 * `outputs/apk` instead reads another task's output without depending on it, which Gradle
 * now fails the build over — correctly, since whether the check saw an APK at all then
 * depended on task ordering.
 */
androidComponents {
    onVariants { variant ->
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val verify = tasks.register("verify${variant.name.replaceFirstChar(Char::titlecase)}NativeAlignment") {
            description = "Fails if any packaged native library cannot be mapped on a 16 KB page device."
            group = "verification"
            inputs.dir(apkDirectory)

            doLast {
                val required = 16 * 1024L
                val offenders = mutableListOf<String>()
                var checked = 0

                apkDirectory.get().asFile.walkTopDown().filter { it.extension == "apk" }.forEach { apk ->
                    ZipFile(apk).use { zip ->
                        zip.entries().asSequence()
                            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                            .forEach { entry ->
                                val bytes = zip.getInputStream(entry).readBytes()
                                val alignment = maxLoadAlignment(bytes)
                                checked++
                                if (alignment != null && alignment < required) {
                                    offenders += "${apk.name}!${entry.name} aligned to $alignment"
                                }
                            }
                    }
                }

                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "Not 16 KB page compatible:\n" + offenders.joinToString("\n") { "  $it" } +
                            "\nThese come from prebuilt dependencies; the fix is a version that ships " +
                            "16 KB-aligned libraries, not a build flag."
                    )
                }
                logger.lifecycle("16 KB alignment: $checked native libraries OK")
            }
        }
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar(Char::titlecase)}" }
            .configureEach { finalizedBy(verify) }
    }
}

/** Largest `p_align` across the PT_LOAD segments, or null if this is not an ELF file. */
fun maxLoadAlignment(bytes: ByteArray): Long? {
    if (bytes.size < 64 || bytes[0] != 0x7F.toByte() ||
        bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) return null

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val is64Bit = bytes[4] == 2.toByte()

    val programHeaderOffset: Long
    val entrySize: Int
    val entryCount: Int
    if (is64Bit) {
        programHeaderOffset = buffer.getLong(32)
        entrySize = buffer.getShort(54).toInt()
        entryCount = buffer.getShort(56).toInt()
    } else {
        programHeaderOffset = buffer.getInt(28).toLong()
        entrySize = buffer.getShort(42).toInt()
        entryCount = buffer.getShort(44).toInt()
    }

    var maximum = 0L
    for (i in 0 until entryCount) {
        val base = (programHeaderOffset + i.toLong() * entrySize).toInt()
        if (base < 0 || base + entrySize > bytes.size) return maximum.takeIf { it > 0 }
        if (buffer.getInt(base) != 1) continue  // PT_LOAD
        val align = if (is64Bit) buffer.getLong(base + 48) else buffer.getInt(base + 28).toLong()
        if (align > maximum) maximum = align
    }
    return maximum.takeIf { it > 0 }
}

