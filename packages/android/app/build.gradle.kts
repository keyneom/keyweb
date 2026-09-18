import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/*
 * Release signing resolves from the environment first so CI can restore the
 * keystore from GitHub secrets, exactly as easy-bc does. A local
 * keystore.properties (gitignored) fills in for developer machines. If neither
 * is complete the release build stays unsigned rather than silently falling
 * back to the debug key, which would ship an APK nobody can upgrade.
 */
private val localKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

private fun signingValue(env: String, local: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: localKeystore.getProperty(local)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseKeystorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")

val releaseSigningEnabled = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "app.keyweb"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.keyweb"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.2.0-beta.11"
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        /*
         * Debug installs alongside release rather than over it.
         *
         * They are signed with different keys, so installing a debug build on
         * a phone that has the release one requires uninstalling first — and
         * that takes the vault with it. Testing a change on a real device
         * therefore meant destroying the data being protected, which is a poor
         * trade and a good way to end up not testing. A separate application
         * id removes the choice: both can be present, and the release vault is
         * never touched.
         *
         * Nothing is lost by it. Drive access is granted to the release
         * certificate, so a debug build could never reach Drive under the
         * shared id either.
         */
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
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
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":vault"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Drive authorization. AuthorizationClient asks Google for an access token
    // scoped to the two Drive scopes; the app never sees a client secret.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    implementation("androidx.activity:activity-ktx:1.9.3")

    /*
     * Reading an authenticator's QR code.
     *
     * The Play Services code scanner rather than CameraX plus a decoder,
     * because it needs no CAMERA permission at all: the scanning happens in
     * Play Services' own process and Keyweb is handed the text. A password
     * manager asking for camera access is a thing people are right to hesitate
     * over, and this removes the question rather than answering it. It also
     * costs almost nothing in the APK — the module is fetched on demand.
     */
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // The sharing half: the shared-backup envelope, the Drive transport for it,
    // and the link-carried key exchange. A port of this would have to stay
    // byte-compatible with the web's copy forever, and the whole point of the
    // library is that it already is.
    implementation("com.keyneom:sync-kit-android:0.4.1")

    // Custom Tabs, for handing the Google Picker to a browser. Android has no
    // native `drive.file` chooser, and the app owns the keyneom.github.io link
    // so a plain VIEW intent would come straight back to itself.
    implementation("androidx.browser:browser:1.8.0")

    // Being a Credential Manager provider. Only the provider half is used --
    // Keyweb answers requests, it does not make them -- and the service it
    // registers exists only on Android 14 and later.
    implementation("androidx.credentials:credentials:1.3.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
