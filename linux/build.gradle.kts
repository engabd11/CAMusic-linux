// CAMusic for Linux — Compose Desktop module.
//
// A plain JVM module: the theme, the MPD client and the Jellyfin client all live in
// src/main/kotlin. No audio is decoded here — MPD owns the sound (RemotePlayback
// shape), so the panel is a remote, never a player.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // No explicit jvmTarget: follow the JDK on the box (21) so the Java and
    // Kotlin compile tasks agree.
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // CMP 1.12 deprecates the compose.* accessors to errors — direct coordinates.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.12.1")
    // material3 is no longer pulled by the desktop meta-package: declared
    // directly. 1.12.0-alpha03 is the CMP 1.12.x pairing.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    // The panes draw from the extended icon set (SkipNext, QueueMusic…),
    // which ended at 1.7.x — pure ImageVectors, uncoupled from the ui version.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    // Artwork: Jellyfin serves covers over plain HTTP; Coil 3 is the KMP line
    // (Coil 2 is Android-only). The OkHttp engine fetches covers directly; the
    // panel's own Http client is not needed for them.
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
        // Desktop install: ./gradlew :linux:packageDistributionForCurrentOS produces
        // a self-contained app image under linux/build/compose/binaries/main/app/CAMusic/
        // (bundled JRE, no system Java needed). Installed to /opt/camusic-linux with a
        // desktop entry (packaging/install.sh) so it launches from the menu like a real app.
        // The release workflow builds it on a tag and attaches it to the GitHub release.
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "CAMusic"
            // -PappVersion=1.2.3 from the release workflow (the tag); 1.0.0 locally.
            packageVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
            // The whole JDK in the bundled runtime: a little larger, but no library can
            // fail at runtime on a module jlink left out.
            includeAllModules = true
            description = "CAMusic control panel for the hi-fi panel"
            vendor = "engabd11"
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "Audio"
            }
        }
    }
}

// Revisiting OPENGL now that the real touchscreen is driving this, per the plan this
// comment used to promise: SOFTWARE was rasterising the whole 1280x800 panel on the CPU,
// which was tolerable for a static UI but will make the new touch-drag fling (see
// TouchScroll.kt) visibly janky -- a flick needs a frame every ~16ms to read as smooth, and
// software rasterising a full-panel redraw at that rate is exactly the kind of load a GPU
// path exists for. jvmArgs on compose.desktop.application is a val in CMP 1.12, so the
// property still goes on the run task rather than the application block.
//
// This is its own commit, last, specifically so it can be reverted alone if OPENGL turns
// out not to be available on the NUC's X server after all -- unlike the gesture fix above,
// this one could not be verified on this Windows machine at all (Skiko's GL context needs
// the real GPU pipeline this comment used to say the NUC didn't have).
tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dskiko.renderApi=OPENGL")
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

// How precise the panel's Jellyfin reports are, seen the way HueGhost reads them — run on
// the NUC while music plays. See tools/SyncProbe.kt.
//   ./gradlew :linux:runSyncProbe
tasks.register<JavaExec>("runSyncProbe") {
    group = "verification"
    description = "Measure the noise of this panel's Jellyfin session reports."
    mainClass.set("com.engabd.sendpin.linux.tools.SyncProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment(System.getenv())
}
// Offscreen previews of every pane, drawn with a fake library — the one way to *see* the
// panel's UI from a machine that is not the NUC (`run` needs the Linux Skiko natives this
// module pins). `src/test/.../preview/RenderPreviews.kt` renders each screen at the
// panel's 800x1280 portrait size into linux/build/previews/*.png:
//   ./gradlew :linux:renderPreviews
// Off Linux, the host's Skiko runtime is added to the *test* classpath only, so the
// packaged app is unchanged. Keep the version in step with desktop-jvm-linux-x64's Skiko.
val skikoHostRuntime: String? = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").contains("aarch64")) "arm64" else "x64"
    when {
        os.contains("windows") -> "windows-$arch"
        os.contains("mac") -> "macos-$arch"
        else -> null
    }
}
dependencies {
    skikoHostRuntime?.let { testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$it:0.150.1") }
}

tasks.register<JavaExec>("renderPreviews") {
    group = "verification"
    description = "Render every pane with fake data to linux/build/previews/*.png."
    dependsOn("testClasses")
    mainClass.set("com.engabd.sendpin.linux.preview.RenderPreviewsKt")
    classpath = sourceSets["test"].runtimeClasspath
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}
