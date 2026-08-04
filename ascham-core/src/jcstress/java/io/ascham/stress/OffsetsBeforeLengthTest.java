package io.ascham.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Invariant 2: publication order. The writer writes the varlen {@code offsets[n]} (plain) and only
 * then release-stores the catalog {@code length}. A reader that acquire-loads {@code length} and sees
 * row {@code n} published must find {@code offsets[n]} already published. This mirrors the
 * {@code ControlRegion} acquire/release contract used by {@code BatchCursor.endRow}.
 */
@JCStressTest
@Outcome(id = "0, -1", expect = ACCEPTABLE, desc = "read before publish")
@Outcome(id = "1, 42", expect = ACCEPTABLE, desc = "length and offset both observed")
@Outcome(id = "1, 0", expect = FORBIDDEN, desc = "length observed but offsets[n] not published")
@State
public class OffsetsBeforeLengthTest {

    private static final VarHandle LENGTH = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] length = new long[1]; // catalog length
    private final int[] offsets = new int[1];  // offsets[n]

    @Actor
    public void writer() {
        offsets[0] = 42;                    // (2) write offsets[n] — plain
        LENGTH.setRelease(length, 0, 1L);   // (4) release-store length — the publication point
    }

    @Actor
    public void observer(II_Result r) {
        long len = (long) LENGTH.getAcquire(length, 0);
        r.r1 = (int) len;
        r.r2 = (len == 1) ? offsets[0] : -1;
    }
}
