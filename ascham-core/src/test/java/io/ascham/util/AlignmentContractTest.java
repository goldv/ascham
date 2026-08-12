package io.ascham.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.segment.SegmentFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link Alignment} stays hand-written (its arithmetic wraps Agrona's {@code BitUtil}); these
 * assertions tie its values to the generated format constants so a manifest change cannot silently
 * diverge from the hand-written arithmetic.
 */
class AlignmentContractTest {

    @Test
    void alignmentValuesMatchGeneratedContract() {
        assertThat(Alignment.BUFFER_ALIGN).isEqualTo(SegmentFormat.BUFFER_ALIGN);
        assertThat(Alignment.PAGE_ALIGN).isEqualTo(SegmentFormat.PAGE_ALIGN);
    }
}
