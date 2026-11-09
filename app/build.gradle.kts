import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun localProperties(name: String) = Properties().apply {
    val file = rootProject.file(name)
    if (file.isFile) file.reader(Charsets.UTF_8).use(::load)
}

val localConfig = localProperties("local.properties")
val tencentConfig = localProperties("tencent-asr.local.properties")
val liveConfig = localProperties("doubao-live.local.properties")
val liveFields = mapOf("DOUBAO_LIVE_APP_ID" to "doubao.appId", "DOUBAO_LIVE_API_KEY" to "doubao.apiKey")
val tencentFields = listOf("TENCENT_ASR_APP_ID", "TENCENT_ASR_SECRET_ID", "TENCENT_ASR_SECRET_KEY")

fun localValue(name: String): String = localConfig.getProperty(name).orEmpty().trim()

fun javaString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        append(when (character) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> character
        })
    }
    append('"')
}

android {
    namespace = "com.musepocket.ai"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.musepocket.ai.vision"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.1"
        buildConfigField("String", "ARK_API_KEY", javaString(""))
        buildConfigField("String", "SPEECH_API_KEY", javaString(""))
        tencentFields.forEach { name -> buildConfigField("String", name, javaString("")) }
        liveFields.keys.forEach { name -> buildConfigField("String", name, javaString("")) }
        val voiceType = tencentConfig.getProperty("TENCENT_TTS_VOICE_TYPE")?.trim()
            ?.toIntOrNull()?.takeIf { it > 0 } ?: 101001
        buildConfigField("int", "TENCENT_TTS_VOICE_TYPE", voiceType.toString())
        buildConfigField("String", "SPEECH_TTS_RESOURCE_ID", javaString(localValue("SPEECH_TTS_RESOURCE_ID").ifEmpty { "seed-tts-2.0" }))
        buildConfigField("String", "SPEECH_TTS_SPEAKER", javaString(localValue("SPEECH_TTS_SPEAKER").ifEmpty { "zh_female_vv_uranus_bigtts" }))
        buildConfigField("String", "ARK_BASE_URL", javaString(localValue("ARK_BASE_URL")))
        buildConfigField("String", "ARK_MODEL", javaString(localValue("ARK_MODEL")))
    }

    buildTypes {
        debug {
            val includeKeys = !providers.gradleProperty("museOmitKeys").isPresent
            buildConfigField("String", "ARK_API_KEY", javaString(localValue("ARK_API_KEY").takeIf { includeKeys }.orEmpty()))
            buildConfigField("String", "SPEECH_API_KEY", javaString(localValue("SPEECH_API_KEY").takeIf { includeKeys }.orEmpty()))
            tencentFields.forEach { name ->
                val value = tencentConfig.getProperty(name).orEmpty().trim().takeIf { includeKeys }.orEmpty()
                buildConfigField("String", name, javaString(value))
            }
            liveFields.forEach { (name, property) ->
                val value = liveConfig.getProperty(property).orEmpty().trim().takeIf { includeKeys }.orEmpty()
                buildConfigField("String", name, javaString(value))
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("io.github.stefan99sun:xiaoyi-huiji:1.0.0")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.code.gson:gson:2.11.0")
}
