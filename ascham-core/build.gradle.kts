plugins {
    `java-library`
    alias(libs.plugins.jmh)
    alias(libs.plugins.jcstress)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Checked-in flatc output from format/Layout.fbs (dev/update_flatbuffers.sh regenerates it).
// Generated at development time, never during the build — the arrow-java arrow-format pattern.
sourceSets["main"].java.srcDir("src/generated/java")

dependencies {
    // arrow-vector: schema types (Schema/Field/ArrowType) and reader-side VectorSchemaRoot views.
    api(libs.arrow.vector)
    // flatbuffers runtime for the generated Layout.fbs bindings. Already on the classpath
    // transitively via arrow-format; pinned explicitly so the version cannot drift from the
    // flatc that generated src/generated/java (dev/update_flatbuffers.sh asserts the match).
    api("com.google.flatbuffers:flatbuffers-java:25.2.10")
    // agrona: off-heap buffers (UnsafeBuffer) and >2 GB segment mapping (M2+).
    api(libs.agrona)
    implementation(libs.arrow.memory.core)
    // Selects Arrow's unsafe allocator for reader-side ArrowBuf wrapping (M3).
    runtimeOnly(libs.arrow.memory.unsafe)
    implementation(libs.arrow.c.data)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// JVM flags every forked JVM (test, jmh) needs — see docs/java-guide.md "Setup":
//  - Agrona 2.x's UnsafeApi references jdk.internal.misc.Unsafe directly, so java.base must EXPORT it
//    (an --add-opens is not enough; the failure is a linkage IllegalAccessError, not reflection).
//  - Arrow's memory-core reflects into java.nio internals (MemoryUtil).
//  - Agrona's IoUtil unmaps mapped segments via sun.nio.ch (M2+).
val aschamJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

// The golden corpus is the cross-language contract; it lives at the repo root, not under the module.
val conformanceDir = rootDir.resolve("conformance")

tasks.test {
    useJUnitPlatform {
        excludeTags("soak") // the long soak runs via the soakTest task
    }
    jvmArgs(aschamJvmArgs)
    systemProperty("io.ascham.conformance.dir", conformanceDir.absolutePath)
}

// JMH throughput/latency benchmarks (src/jmh/java). The zero-allocation gate is a normal test
// (AllocationTest, via ThreadMXBean); these measure performance and run on demand via `gradlew jmh`.
jmh {
    jvmArgs.set(aschamJvmArgs)
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(3)
    warmup.set("1s")
    timeOnIteration.set("1s")
}

// Long-running writer/reader concurrency soak. Duration via -Dio.ascham.soak.seconds.
tasks.register<Test>("soakTest") {
    group = "verification"
    description = "Run the concurrency soak (one writer, N readers) for an extended duration"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
    jvmArgs(aschamJvmArgs)
    systemProperty("io.ascham.conformance.dir", conformanceDir.absolutePath)
}

// Regenerates the golden corpus. Run manually; any diff to the checked-in files is a format change.
tasks.register<JavaExec>("regenerateGoldenCorpus") {
    group = "conformance"
    description = "Regenerate the golden byte corpus under conformance/"
    mainClass = "io.ascham.conformance.GoldenCorpusGenerator"
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs = aschamJvmArgs
    args(conformanceDir.absolutePath)
}

// The C++ half of the cross-language conformance matrix: builds cpp/ (the reference C++ reader)
// and runs its conformance runner against this repo's golden corpus, so a format change is
// validated against both languages before it leaves this repo. Requires cmake + a C++20 compiler.
tasks.register<Exec>("cppConformance") {
    group = "conformance"
    description = "Build and run the C++ conformance runner against conformance/"
    workingDir = rootDir
    commandLine("bash", "dev/run_cpp_conformance.sh")
}

tasks.named("check") {
    dependsOn("cppConformance")
}

// Regenerates the layout conformance vectors (schema → LayoutCodec descriptor bytes). Run manually;
// any diff is a layout-derivation or codec change, i.e. a format change.
tasks.register<JavaExec>("regenerateLayoutVectors") {
    group = "conformance"
    description = "Regenerate conformance/layout_vectors.jsonl"
    mainClass = "io.ascham.layout.LayoutVectorGenerator"
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs = aschamJvmArgs
    args(conformanceDir.absolutePath)
}

tasks.register<JavaExec>("runLiveWriter") {
    group = "demo"
    description = "Append mock quotes to an arena table dir (args: <baseDir> [table] [rowsPerSec] [seconds])"
    mainClass = "io.ascham.demo.LiveWriterMain"
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs = aschamJvmArgs
}

// jcstress ordering harness (src/jcstress/java), folded in from the former :arena-jcstress module.
// The plugin creates its own source set and supplies jcstress-core, so its annotation processor
// stays off the library's compileJava. The tests exercise the VarHandle getAcquire/setRelease
// contract ControlRegion is built on over plain arrays, so they depend on neither the main source
// set nor the JVM flags above.
jcstress {
    // PR CI runs quick mode; nightly can run the default mode. Never gate merges on full mode.
    mode = "quick"
}

// The reyerizo jcstress plugin is not configuration-cache compatible (design plan risk #9).
// Mark its run task so an otherwise config-cache-enabled build degrades gracefully for it
// instead of failing, rather than disabling the cache repo-wide.
tasks.named("jcstress") {
    notCompatibleWithConfigurationCache("jcstress plugin accesses Project at execution time")
}
