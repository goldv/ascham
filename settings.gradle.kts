rootProject.name = "ito-db"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The arena ingest/storage module (schema, layout, segment, writer, reader).
// Future tiers slot in beside it: include("query").
// The jcstress ordering harness gets its own subproject at milestone M4: include("arena-jcstress").
include("arena")
include("arena-jcstress")
// The cold tier: rolls completed days out of the arena into Iceberg/Parquet (docs/cold-tier-design-plan.md).
include("cold")
