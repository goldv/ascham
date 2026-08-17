plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(project(":ascham-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Same JVM flags the arena requires — see docs/java-guide.md "Setup".
val aschamJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

// Readable one-line logs; see src/main/resources/logging.properties.
val demoJvmArgs = aschamJvmArgs + listOf(
    "-Djava.util.logging.config.file=" + file("src/main/resources/logging.properties").absolutePath,
)

tasks.test {
    useJUnitPlatform()
    jvmArgs(aschamJvmArgs)
}

/** Live mock market data into the arena. Ctrl-C to stop. */
tasks.register<JavaExec>("runWriter") {
    group = "demo"
    description = "Write live mock quotes and trades (args: see MarketDataWriterMain --help)"
    mainClass = "io.ascham.samples.MarketDataWriterMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = demoJvmArgs
    standardInput = System.`in`
}

/** Backfill completed past days so the cold-tier roll has something real to archive. */
tasks.register<JavaExec>("backfill") {
    group = "demo"
    description = "Generate N completed past days of quotes and trades (args: --days N ...)"
    mainClass = "io.ascham.samples.BackfillMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = demoJvmArgs
}
