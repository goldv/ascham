plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // The arena reader: segment discovery, snapshots, and the batch zone maps the roller verifies
    // day-alignment against. The roll itself reads through the DuckDB extension, not this API.
    api(project(":arena"))
    // The roll engine: one embedded DuckDB does read + sort + Parquet encode + Iceberg commit.
    implementation(libs.duckdb.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The cold tier touches arena mappings (SegmentDirectory/SnapshotReader), so it needs the arena's
// JVM flags — see docs/arena-design-plan.md §2 for why each is required.
val arenaJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

tasks.test {
    useJUnitPlatform {
        // Integration tests need the dev catalog stack (dev/docker-compose.yml) and the built arena
        // extension; they are opt-in via `gradlew :cold:rollIT`.
        excludeTags("catalog")
    }
    jvmArgs(arenaJvmArgs)
}

// Integration tests against the local Lakekeeper/MinIO stack.
//   docker compose -f dev/docker-compose.yml up -d
//   DUCKDB=/path/to/duckdb arena-duckdb/scripts/build_extension.sh
//   ./gradlew :cold:rollIT
tasks.register<Test>("rollIT") {
    group = "verification"
    description = "Roll integration tests against the local Iceberg catalog (requires dev/ stack)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("catalog") }
    jvmArgs(arenaJvmArgs)
    systemProperty("io.ito.cold.arenaExtension",
        rootDir.resolve("arena-duckdb/build/arena.duckdb_extension").absolutePath)
    outputs.upToDateWhen { false } // always re-run: the catalog is external state
}
