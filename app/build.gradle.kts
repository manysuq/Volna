import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи берётся из keystore.properties рядом с проектом.
// Сам файл в git не попадает (см. .gitignore): пароли не должны лежать в репозитории.
// Без него релизная сборка всё равно собирается, но остаётся неподписанной.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// ── Сборка FOR_ISLAND ────────────────────────────────────────────────────────
// Некоторые оболочки (vivo/OriginOS и другие китайские) показывают развёрнутый
// плавающий плеер только для пакетов из захардкоженного белого списка. Сборка
// с ключом forIslandPackage меняет ТОЛЬКО applicationId — имя пакета на
// устройстве, — и ничего больше:
//
//   ./gradlew assembleRelease -PforIslandPackage=com.spotify.music
//
// namespace остаётся com.volna.player: он отвечает за R-класс, BuildConfig и
// разрешение относительных имён в манифесте, и его смена сломала бы сборку.
// Основной пакет com.volna.player при этом не меняется — вариант собирается
// только по явному ключу.
//
// Что нужно помнить: такой APK занимает чужое имя пакета, поэтому
// установить его вместе с оригиналом нельзя — придётся удалить оригинал.
// Это костыль для конкретных оболочек, а не универсальное решение.
val forIslandPackage: String? = providers.gradleProperty("forIslandPackage").orNull
val isIslandBuild = forIslandPackage != null && forIslandPackage.isNotBlank()
val ISLAND_TAG = "FOR_ISLAND"


android {
    namespace = "com.volna.player"
    compileSdk = 35

    defaultConfig {
        applicationId = forIslandPackage ?: "com.volna.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // Метка попадает в versionName, чтобы вариант был опознаваем на
        // устройстве и в списке установленных пакетов.
        versionName = if (isIslandBuild) "1.0-$ISLAND_TAG" else "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без ключа сборка не падает, а остаётся unsigned — так её можно
            // запустить на чистой машине, просто не устанавливая в магазин.
            if (hasReleaseKeystore) {
                val release = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                    // v1 нужен для Android 6 и старше, v2/v3 — для современных.
                    // Без v3 APK не обновляется поверх установленного на Android 11+.
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                }
                signingConfig = release
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        compose = true
        // Нужно для BuildConfig.VERSION_NAME в настройках и в заголовке логов.
        buildConfig = true
    }

    defaultConfig {
        // Языки, которые переведены полностью: без этого в настройках
        // показывается весь список системных локалей устройства.
        resourceConfigurations += setOf("en", "ru", "fr", "es", "zh", "ar")
    }

    testOptions {
        unitTests {
            // Парсеры пишут в android.util.Log, которого нет на JVM:
            // без этого любой Log.d в тестах роняет их с "Method not mocked".
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Каждый релиз копируется в dist/ под своим именем.
//
// Раньше вариант FOR_ISLAND просто переименовывался на месте, и в
// outputs/apk/release/app-release.apk оказывался именно он — обычный APK
// исчезал, и его ставили по ошибке. Теперь в dist/ лежат оба сразу:
//   volna-1.0-release.apk                          — обычный, com.volna.player
//   volna-1.0-FOR_ISLAND-<пакет>.apk               — вариант для динамического острова
//
// outputs/ остаётся служебной папкой Gradle и перезаписывается каждой сборкой.
tasks.register("publishReleaseApk") {
    doLast {
        val built = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val source = File(built, "app-release.apk")
        if (!source.exists()) return@doLast
        val dist = rootProject.file("dist").apply { mkdirs() }
        val name = if (isIslandBuild) {
            "volna-1.0-$ISLAND_TAG-$forIslandPackage.apk"
        } else {
            "volna-1.0-release.apk"
        }
        val target = File(dist, name)
        source.copyTo(target, overwrite = true)
        logger.lifecycle("Релиз: ${target.path}")
    }
}

// matching + configureEach, а не tasks.named: на момент конфигурации задача
// assembleRelease ещё не зарегистрирована AGP, и named() падал с
// «Task with name 'assembleRelease' not found».
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("publishReleaseApk")
}

dependencies {
    implementation(project(":ytdl"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Ради переключения языка внутри приложения: AppCompatDelegate сам
    // пересоздаёт активности и помнит выбор на уровне процесса.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // Material 3 Expressive
    implementation("androidx.compose.material3:material3:1.4.0-alpha17")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Плеер
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")

    // Загрузка картинок
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Тесты
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
