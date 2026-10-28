import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release signing key lives outside the project (build.cmd passes -Pnightlock.keystore=<properties file>).
// Android only installs an update signed with the same key, so whoever holds it controls what runs with
// NightLock's accessibility access. Without it the release APK comes out unsigned: never debug-signed.
val keystoreProperties = Properties().apply {
    providers.gradleProperty("nightlock.keystore").orNull?.let(::file)?.takeIf { it.exists() }?.inputStream()?.use(::load)
}

// TEST_MODE: build with -Pnightlock.testMode=true and every duration (delays, pauses, cooldowns)
// shrinks from minutes to seconds. The app is then called "NightLock TEST" and shows a banner.
val testMode = providers.gradleProperty("nightlock.testMode").orNull.toBoolean()

android {
    namespace = "com.orirose.nightlock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orirose.nightlock"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.2.0"

        buildConfigField("boolean", "TEST_MODE", testMode.toString())
        resValue("string", "app_name", if (testMode) "NightLock TEST" else "NightLock")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

/**
 * Fails the release build if the final, merged manifest (what libraries add included) asks for a
 * permission, lets other apps reach a component, or allows backups beyond what's listed here.
 * Widening this list is a deliberate change: see README.md's Security section.
 */
abstract class CheckManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile).documentElement
        fun elements(vararg tags: String) = tags.flatMap { tag ->
            val list = root.getElementsByTagName(tag)
            (0 until list.length).map { list.item(it) as Element }
        }
        val problems = mutableListOf<String>()

        val allowedPermissions = setOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.POST_NOTIFICATIONS",
            // AndroidX core's guard for its own non-exported receivers; only NightLock can hold it.
            "${applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        elements("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
            .map { it.getAttributeNS(ns, "name") }
            .filter { it !in allowedPermissions }
            .forEach { problems += "unexpected permission $it" }

        val allowedExported = mapOf(
            "com.orirose.nightlock.MainActivity" to "",
            "com.orirose.nightlock.block.BlockerService" to "android.permission.BIND_ACCESSIBILITY_SERVICE",
            // Compose's start-up speed helper; only adb and the system hold DUMP.
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )
        val components = elements("activity", "activity-alias", "service", "receiver", "provider")
        components.filter { it.getAttributeNS(ns, "exported") !in setOf("true", "false") }
            .forEach { problems += "exported must be a literal true/false: ${it.getAttributeNS(ns, "name")}" }
        val exported = components
            .filter { it.getAttributeNS(ns, "exported") == "true" }
            .associate { it.getAttributeNS(ns, "name") to it.getAttributeNS(ns, "permission") }
        if (exported != allowedExported) problems += "exported components changed: $exported"

        elements("permission").map { it.getAttributeNS(ns, "name") }
            .filter { it != "${applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" }
            .forEach { problems += "unexpected declared permission $it" }

        val application = elements("application").single()
        if (application.getAttributeNS(ns, "allowBackup") != "false") problems += "allowBackup must be false"
        if (application.getAttributeNS(ns, "debuggable") == "true") problems += "release must not be debuggable"

        if (problems.isNotEmpty()) throw GradleException("Manifest check failed:\n  " + problems.joinToString("\n  "))
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val check = tasks.register<CheckManifest>("securityCheck${variant.name.replaceFirstChar(Char::uppercase)}Manifest") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            applicationId.set(variant.applicationId)
        }
        tasks.named { it == "assemble${variant.name.replaceFirstChar(Char::uppercase)}" }.configureEach { dependsOn(check) }
    }
}

// A release APK is only built after the unit tests (SecurityTest included) pass.
tasks.named { it == "assembleRelease" }.configureEach { dependsOn("testDebugUnitTest") }

// SecurityTest reads the accessibility config and the source files straight from disk; declare them, so an
// edit to either reruns the tests instead of reusing an old pass.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res/xml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/java").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    // No ui-tooling: in debug builds it adds an exported activity that runs any composable it's given.

    testImplementation(libs.junit)
}
