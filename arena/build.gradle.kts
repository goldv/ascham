plugins {
    `java-library`
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

tasks.test {
    useJUnitPlatform()
    jvmArgs(arenaJvmArgs)
}
