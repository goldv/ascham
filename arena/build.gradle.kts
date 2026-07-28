plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // arrow-vector: schema types (Schema/Field/ArrowType) and reader-side VectorSchemaRoot views.
    api(libs.arrow.vector)
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

// JVM flags every forked JVM (test, and later jmh/jcstress) needs — see docs/arena-design-plan.md §2:
//  - Agrona 2.x's UnsafeApi references jdk.internal.misc.Unsafe directly, so java.base must EXPORT it
//    (an --add-opens is not enough; the failure is a linkage IllegalAccessError, not reflection).
//  - Arrow's memory-core reflects into java.nio internals (MemoryUtil).
//  - Agrona's IoUtil unmaps mapped segments via sun.nio.ch (M2+).
val arenaJvmArgs = listOf(
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
    jvmArgs(arenaJvmArgs)
    systemProperty("io.ito.arena.conformance.dir", conformanceDir.absolutePath)
}

// JMH throughput/latency benchmarks (src/jmh/java). The zero-allocation gate is a normal test
// (AllocationTest, via ThreadMXBean); these measure performance and run on demand via `gradlew jmh`.
jmh {
    jvmArgs.set(arenaJvmArgs)
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(3)
    warmup.set("1s")
    timeOnIteration.set("1s")
}

// Long-running writer/reader concurrency soak. Duration via -Dio.ito.arena.soak.seconds.
tasks.register<Test>("soakTest") {
    group = "verification"
    description = "Run the concurrency soak (one writer, N readers) for an extended duration"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
    jvmArgs(arenaJvmArgs)
    systemProperty("io.ito.arena.conformance.dir", conformanceDir.absolutePath)
}

// Regenerates the golden corpus. Run manually; any diff to the checked-in files is a format change.
tasks.register<JavaExec>("regenerateGoldenCorpus") {
    group = "conformance"
    description = "Regenerate the golden byte corpus under conformance/"
    mainClass = "io.ito.arena.conformance.GoldenCorpusGenerator"
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs = arenaJvmArgs
    args(conformanceDir.absolutePath)
}
