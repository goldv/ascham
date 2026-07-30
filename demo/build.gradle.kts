plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(project(":arena"))
    // The roll demo drives the cold tier; the writer and backfill do not need it.
    implementation(project(":cold"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Same JVM flags the arena requires — see docs/arena-design-plan.md §2.
val arenaJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

// Readable one-line logs; see src/main/resources/logging.properties.
val demoJvmArgs = arenaJvmArgs + listOf(
    "-Djava.util.logging.config.file=" + file("src/main/resources/logging.properties").absolutePath,
)

tasks.test {
    useJUnitPlatform()
    jvmArgs(arenaJvmArgs)
}

/** Live mock market data into the arena. Ctrl-C to stop. */
tasks.register<JavaExec>("runWriter") {
    group = "demo"
    description = "Write live mock quotes and trades (args: see MarketDataWriterMain --help)"
    mainClass = "io.ito.demo.MarketDataWriterMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = demoJvmArgs
    standardInput = System.`in`
}

/** Roll whatever the demo has written into the Iceberg catalog (needs the dev/ stack up). */
tasks.register<JavaExec>("roll") {
    group = "demo"
    description = "Roll demo data from the arena into Iceberg, then reclaim the segments"
    mainClass = "io.ito.demo.RollDemoMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = demoJvmArgs
    systemProperty("io.ito.demo.arenaExtension",
        rootDir.resolve("arena-duckdb/build/arena.duckdb_extension").absolutePath)
}

/** Backfill completed past days so the cold-tier roll has something real to archive. */
tasks.register<JavaExec>("backfill") {
    group = "demo"
    description = "Generate N completed past days of quotes and trades (args: --days N ...)"
    mainClass = "io.ito.demo.BackfillMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = demoJvmArgs
}
