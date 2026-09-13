plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * Pure Kotlin, no Android dependencies, so the vault semantics can be tested on
 * the JVM in milliseconds rather than on an emulator. Anything platform
 * specific (Room, Drive, Credential Manager) lives in :app behind the
 * interfaces declared here.
 */
kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Argon2, ChaCha20 and Salsa20, for reading KeePass files. The JDK ships
    // none of them, and hand-rolling a password-hashing function inside a
    // password manager is not a risk worth taking. `api` so :app links it too.
    api("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
