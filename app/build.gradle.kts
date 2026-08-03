import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().also { props ->
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) props.load(propsFile.inputStream())
}

val localProperties = Properties().also { props ->
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) props.load(propsFile.inputStream())
}

android {
    namespace = "com.raofflineproxy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.raofflineproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.9.0-alpha1"

        // The backend (lambda/raop-support-payment) is a single shared live instance, not
        // separate dev/prod ones, so every build type needs the live publishable key to match
        // the live client secrets it issues — a debug build with the test key can't check out.
        buildConfigField(
            "String",
            "STRIPE_PUBLISHABLE_KEY",
            "\"${localProperties.getProperty("STRIPE_PUBLISHABLE_KEY_LIVE", "")}\""
        )
        // Used only behind the debug-only "test" checkbox in the donation dialog, to pair with
        // client secrets the backend issues in Stripe test mode.
        buildConfigField(
            "String",
            "STRIPE_PUBLISHABLE_KEY_TEST",
            "\"${localProperties.getProperty("STRIPE_PUBLISHABLE_KEY_TEST", "")}\""
        )
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
            storePassword = keystoreProperties["storePassword"] as String?
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val versionName = output.versionName.orNull ?: return@forEach
            output.outputFileName.set("RAOfflineProxy-v${versionName}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.stripe.android)
    implementation(libs.swiperefreshlayout)
    implementation(libs.coil)
    implementation(libs.documentfile)
    implementation(libs.recyclerview)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(variantOf(libs.zstd.jni) { artifactType("aar") })
    testImplementation(libs.junit)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.org.json)
}
