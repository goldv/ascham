plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // The arena reader: segment discovery, snapshots, zero-copy batch roots, and the batch zone
    // maps the roller verifies day-alignment against. The roll reads rows through this API.
    api(project(":arena"))
    // The roll engine: native Iceberg table create/write/commit, parquet encoding via iceberg-data.
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.parquet)
    implementation(libs.iceberg.data)
    // Compile-time only: MessageType appears in Parquet.writeData's createWriterFunc signature but
    // iceberg-parquet's module metadata keeps parquet-column off the consumer compile classpath.
    compileOnly(libs.parquet.column)
    testCompileOnly(libs.parquet.column)
    // S3FileIO properties for REST-vended object storage; the bundle carries the AWS SDK v2.
    implementation(libs.iceberg.aws)
    runtimeOnly(libs.iceberg.aws.bundle)
    // Shaded Hadoop pair: Configuration/FileSystem for HadoopCatalog and parquet-hadoop's
    // `provided` dependencies. Deliberately not full hadoop-common.
    implementation(libs.hadoop.client.api)
    runtimeOnly(libs.hadoop.client.runtime)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // RestCatalogIT reads back through DuckDB's iceberg extension to pin query-side parity.
    testImplementation(libs.duckdb.jdbc)
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
        // Integration tests need the dev catalog stack (dev/docker-compose.yml); they are opt-in
        // via `gradlew :cold:rollIT`. Everything else — including the full roll against a local
        // filesystem warehouse — runs hermetically here.
        excludeTags("catalog")
    }
    jvmArgs(arenaJvmArgs)
}

// Integration tests against the local Lakekeeper/MinIO stack.
//   docker compose -f dev/docker-compose.yml up -d
//   ./gradlew :cold:rollIT
tasks.register<Test>("rollIT") {
    group = "verification"
    description = "Roll integration tests against the local Iceberg catalog (requires dev/ stack)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("catalog") }
    jvmArgs(arenaJvmArgs)
    outputs.upToDateWhen { false } // always re-run: the catalog is external state
}
