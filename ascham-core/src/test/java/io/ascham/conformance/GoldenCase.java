package io.ascham.conformance;

import io.ascham.schema.ArenaSchema;
import io.ascham.write.SegmentWriter;
import java.util.function.Consumer;

/**
 * One golden-corpus case: a schema plus a deterministic op script and fixed inputs (epoch, sequence,
 * clock) so the produced segment is byte-reproducible. The op script drives a {@link SegmentWriter}.
 *
 * @param expectedTotalRows sum of row counts across all batches, for the read-back sanity check
 */
record GoldenCase(
        String name,
        ArenaSchema schema,
        int maxBatches,
        long epoch,
        long sequence,
        long clockStart,
        long clockStep,
        int expectedTotalRows,
        Consumer<SegmentWriter> script) {

    FixedClock clock() {
        return new FixedClock(clockStart, clockStep);
    }
}
