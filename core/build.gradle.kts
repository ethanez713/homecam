import com.google.protobuf.gradle.id
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    api(libs.protobuf.kotlin.lite)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// GATE-SEC talks to a real camera, so this source set is not created — and therefore
// not compiled, tested, or packaged — unless the address is passed in. :core:test and
// the assemble tasks do not depend on it.
val deviceHost = findProperty("device.host") as String?
val devicePort = findProperty("device.port") as String?
if (deviceHost != null && devicePort != null) {
    val mainSource = sourceSets.getByName("main")
    sourceSets.create("deviceTest") {
        compileClasspath += mainSource.output
        compileClasspath += mainSource.compileClasspath
        runtimeClasspath += mainSource.output
        runtimeClasspath += mainSource.runtimeClasspath
    }
    kotlin.sourceSets.getByName("deviceTest").kotlin.srcDir("src/deviceTest/kotlin")
    dependencies { add("deviceTestImplementation", libs.junit) }

    tasks.register<Test>("deviceTest") {
        group = "verification"
        description = "GATE-SEC against a camera. Not part of check or :core:test."
        testClassesDirs = sourceSets.getByName("deviceTest").output.classesDirs
        classpath = sourceSets.getByName("deviceTest").runtimeClasspath
        useJUnit()
        testLogging { events("passed", "skipped", "failed") }
        systemProperty("device.host", deviceHost)
        systemProperty("device.port", devicePort)
        // HttpClient latches this when its internal Utils class loads, so it has to be
        // set on the JVM, not from a test method after the first connection.
        systemProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
        (findProperty("device.invite") as String?)?.let { systemProperty("device.invite", it) }
        (findProperty("device.inviteFile") as String?)?.let { systemProperty("device.inviteFile", it) }
        (findProperty("device.pin") as String?)?.let { systemProperty("device.pin", it) }
    }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
