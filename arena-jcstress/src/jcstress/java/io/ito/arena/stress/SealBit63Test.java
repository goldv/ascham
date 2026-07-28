package io.ito.arena.stress;

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
 * Seal: the writer plain-writes {@code seal_nanos} (and stats) and then release-stores the catalog
 * {@code length} with bit 63 cleared. A reader that acquire-loads a sealed length (bit 63 clear) must
 * see the final {@code seal_nanos} — so a reader can never treat an in-progress batch's zero stats as
 * final.
 */
@JCStressTest
@Outcome(id = "0, -1", expect = ACCEPTABLE, desc = "still in progress")
@Outcome(id = "1, 99", expect = ACCEPTABLE, desc = "sealed, seal_nanos visible")
@Outcome(id = "1, 0", expect = FORBIDDEN, desc = "sealed observed but seal_nanos not published")
@State
public class SealBit63Test {

    private static final long IN_PROGRESS_BIT = 1L << 63;
    private static final VarHandle LENGTH = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] length = {IN_PROGRESS_BIT | 5L}; // 5 rows, in progress
    private final long[] sealNanos = new long[1];

    @Actor
    public void writer() {
        sealNanos[0] = 99;                  // plain: final seal_nanos
        LENGTH.setRelease(length, 0, 5L);   // release: clear bit 63 (sealed)
    }

    @Actor
    public void observer(II_Result r) {
        long len = (long) LENGTH.getAcquire(length, 0);
        boolean sealed = (len & IN_PROGRESS_BIT) == 0;
        r.r1 = sealed ? 1 : 0;
        r.r2 = sealed ? (int) sealNanos[0] : -1;
    }
}
