plugins {
    java
    alias(libs.plugins.jcstress)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The tests exercise the acquire/release primitive that ControlRegion (in :arena) is built on —
// the same VarHandle getAcquire/setRelease contract — over plain arrays, so they need no direct
// buffers and no dependency on :arena. jcstress-core is supplied by the plugin.
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
