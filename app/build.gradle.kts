plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 默认版本与仓库当前发布保持一致；.github/workflows/release.yml 用 -P 参数覆盖它们。
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull ?: "1.0.1"
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.let {
    it.toIntOrNull() ?: throw GradleException("releaseVersionCode 必须是整数，收到：$it")
} ?: 2

if (!releaseVersionName.matches(Regex("""\d+\.\d+\.\d+"""))) {
    throw GradleException("releaseVersionName 必须是形如 1.0.2 的三段版本号，收到：$releaseVersionName")
}

// Release 签名材料只从环境变量读取，仓库不保存私钥或密码。
val signingRequired = providers.environmentVariable("CASHIERHELPER_REQUIRE_SIGNING").orNull == "true"
// 名字不能与 SigningConfig 的属性同名，否则在 signingConfigs {} 里会解析成配置对象自身的属性。
val envKeystorePath = providers.environmentVariable("CASHIERHELPER_KEYSTORE_PATH").orNull
val envKeystorePassword = providers.environmentVariable("CASHIERHELPER_KEYSTORE_PASSWORD").orNull
val envKeyAlias = providers.environmentVariable("CASHIERHELPER_KEY_ALIAS").orNull
val envKeyPassword = providers.environmentVariable("CASHIERHELPER_KEY_PASSWORD").orNull

if (signingRequired) {
    val missing = mapOf(
        "CASHIERHELPER_KEYSTORE_PATH" to envKeystorePath,
        "CASHIERHELPER_KEYSTORE_PASSWORD" to envKeystorePassword,
        "CASHIERHELPER_KEY_ALIAS" to envKeyAlias,
        "CASHIERHELPER_KEY_PASSWORD" to envKeyPassword,
    ).filterValues { it.isNullOrBlank() }.keys.sorted()
    if (missing.isNotEmpty()) {
        throw GradleException("Release 构建要求签名配置，缺少环境变量：${missing.joinToString(", ")}")
    }
    if (!file(envKeystorePath.orEmpty()).isFile) {
        throw GradleException("CASHIERHELPER_KEYSTORE_PATH 指向的文件不存在：$envKeystorePath")
    }
}

android {
    namespace = "pro.xiangyu.cashierhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "pro.xiangyu.cashierhelper"
        minSdk = 30
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!envKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(envKeystorePath)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
