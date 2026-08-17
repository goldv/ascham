rootProject.name = "ascham"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The arena ingest/storage library (schema, layout, segment, writer, reader), with the JMH
// benchmarks and the jcstress ordering harness as extra source sets rather than subprojects.
// Future tiers slot in beside it: include("ascham-query").
include("ascham-core")
// Mock market-data writer and demo drivers (docs/flight-sql-design-plan.md §7).
include("ascham-samples")
