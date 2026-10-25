plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号：在 GitHub Actions 上用运行序号自动递增，本地编译时为 1
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// 正式签名：从环境变量读取（GitHub Actions 里由仓库 Secrets 提供）。
// 没有配置时，release 包退回用调试签名，方便本地直接编译。
val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.overwhelmedyy.espvocab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.overwhelmedyy.espvocab"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    // 网页、词库和字体都放在仓库根目录的 web/ 里，直接作为 assets 打包，
    // 这样同一份网页既能在浏览器里打开，也能打进 APK。
    sourceSets["main"].assets.srcDirs("../web")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
