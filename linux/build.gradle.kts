// CAMusic for Linux — Compose Desktop module (local testing only, not published).
//
// A JVM module sharing source with :app (see the syncSharedSources task): the
// theme, the MPD client and the Jellyfin client are shared as source, not
// hand-matched. No audio is decoded here — MPD owns the sound (RemotePlayback
// shape), so this module needs media3 for nothing.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The shared :app files are pure JVM but live under app/src/main/java — copied
// in by syncSharedSources below so the explicit include list in one place is
// the whole sharing contract.
val sharedFiles = listOf(
        "ui/theme/Color.kt",
        "ui/theme/Type.kt",
        "ui/theme/AccentTextFieldColors.kt",
        "mpd/MpdClient.kt",
        "mpd/MpdRemote.kt",
        "mpd/MpdArt.kt",
        "jellyfin/JellyfinClient.kt",
        "library/MusicSource.kt",
        "library/JellyfinSource.kt",
        "library/ServerConfig.kt",
        "ma/MaModels.kt",
        "audio/RemotePlayback.kt",
        "audio/LocalTrack.kt",
        "audio/StreamQuality.kt",
        "data/LanOnlyCleartext.kt",
    )

val sharedSrc = layout.buildDirectory.dir("sharedSrc")

tasks.register<Sync>("syncSharedSources") {
    from(rootProject.file("app/src/main/java/com/engabd/sendpin")) {
        include(sharedFiles)
    }
    into(sharedSrc)
}

kotlin {
    // No explicit jvmTarget: follow the JDK on the box (21) so the Java and
    // Kotlin compile tasks agree. Desktop-only module, so JVM 21 bytecode is
    // fine — :app's Android targets are untouched by this.
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(sharedSrc)
            // Android-symbol shims so the shared files compile unedited on the JVM.
            srcDir("src/main/compat/kotlin")
        }
    }
}

// The Sync never runs by itself: every Kotlin compile depends on it so a shared
// file edited in :app is re-copied before :linux compiles, and a stale build dir
// can never outlive the edit that should have changed it.
tasks.named("compileKotlin") { dependsOn("syncSharedSources") }
tasks.named("compileTestKotlin") { dependsOn("syncSharedSources") }

// The shared files compile against the same versions :app pins, so the desktop
// module reads identical APIs — including the material3 Expressive surface.
dependencies {
    // CMP 1.12 deprecates the compose.* accessors to errors — direct coordinates.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.12.1")
    // material3 is no longer pulled by the desktop meta-package: declared
    // directly. 1.12.0-alpha03 is the CMP 1.12.x pairing.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    // The shared panes draw from the extended icon set (SkipNext, QueueMusic…),
    // which ended at 1.7.x — pure ImageVectors, uncoupled from the ui version.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    // Artwork: Jellyfin serves covers over plain HTTP; Coil 3 is the KMP line
    // (the repo's Coil 2.7.0 is Android-only). OkHttp engine reuses our client
    // settings-free; the shared Http client is not needed for covers.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.engabd.sendpin.linux.MainKt"
    }
}

// SOFTWARE renderer: Skiko cannot create a GL context on this headless NUC's X
// server (no GPU pipeline for it). jvmArgs on compose.desktop.application is a
// val in CMP 1.12, so the property goes on the run task instead. Revisit OPENGL
// when the real touchscreen is driving this.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dskiko.renderApi=SOFTWARE")
}

// Step-4 smoke: MPD transport end to end without the UI.
//   MPD_ADDRESS=... MPD_TEST_PATH=... ./gradlew :linux:runSmoke
tasks.register<JavaExec>("runSmoke") {
    group = "verification"
    description = "Play one hardcoded MPD path through the shared MpdRemote transport."
    mainClass.set("com.engabd.sendpin.linux.tools.MpdSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment(System.getenv())
}