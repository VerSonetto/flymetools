import java.util.Properties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
}

val featuresJsonFile = file("src/main/assets/features.json")

val dateCode = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt()

val generatedScopeDir = layout.buildDirectory.dir("generated/scope")
val generatedScopeResDir = layout.buildDirectory.dir("generated/scope_res")

val generateScope by tasks.registering {
    inputs.file(featuresJsonFile)
    outputs.dir(generatedScopeDir)
    outputs.dir(generatedScopeResDir)
    doLast {
        val text = featuresJsonFile.readText()
        val packages = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        val scopeFile = generatedScopeDir.get().file("scope").asFile
        scopeFile.parentFile.mkdirs()
        scopeFile.writeText(packages.joinToString("\n") + "\n")

        val resFile = generatedScopeResDir.get().file("values/scope_arr.xml").asFile
        resFile.parentFile.mkdirs()
        resFile.writeText(
            "<resources>\n    <string-array name=\"xposed_scope\">\n" +
                packages.joinToString("\n") { "        <item>$it</item>" } +
                "\n    </string-array>\n</resources>\n"
        )
    }
}

tasks.named("preBuild") {
    dependsOn(generateScope)
}

android {
    namespace = "com.karen.flymetool"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].apply {
        assets.srcDir(generatedScopeDir)
        res.srcDir(generatedScopeResDir)
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias") ?: "sonetto"
            keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
            storeFile = file("../" + (keystoreProperties.getProperty("storeFile") ?: "release.keystore"))
            storePassword = keystoreProperties.getProperty("storePassword") ?: ""
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    defaultConfig {
        applicationId = "com.karen.flymetool"
        minSdk = 31
        targetSdk = 36
        versionCode = dateCode
        versionName = versionProps.getProperty("versionName") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val adbDeviceSerial = providers.gradleProperty("adbDeviceSerial").orNull

tasks.register<Exec>("installSignedRelease") {
    group = "install"
    description = "Build signed release APK and install to device"

    dependsOn("assembleRelease")

    val apkPath = layout.buildDirectory
        .dir("outputs/apk/release/app-release.apk")
        .get()
        .asFile
        .absolutePath

    val installCmd = if (adbDeviceSerial != null) {
        listOf("adb", "-s", adbDeviceSerial, "install", "-r", apkPath)
    } else {
        listOf("adb", "install", "-r", apkPath)
    }
    commandLine(installCmd)

}

dependencies {
    compileOnly(files("libs/api-82.jar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.palette.ktx)
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
