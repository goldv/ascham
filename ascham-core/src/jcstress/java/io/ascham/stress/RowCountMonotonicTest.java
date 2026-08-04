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
 * Invariant 1 as seen through acquire loads: the writer never rewinds, so two successive
 * acquire-loads of the catalog {@code length} by a reader never observe a decrease.
 */
@JCStressTest
@Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "before both publishes")
@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "first publish landed between reads")
@Outcome(id = "0, 2", expect = ACCEPTABLE, desc = "both publishes landed between reads")
@Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "after first publish")
@Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "second publish landed between reads")
@Outcome(id = "2, 2", expect = ACCEPTABLE, desc = "after both publishes")
@Outcome(expect = FORBIDDEN, desc = "row count went backwards")
@State
public class RowCountMonotonicTest {

    private static final VarHandle LENGTH = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] length = new long[1];

    @Actor
    public void writer() {
        LENGTH.setRelease(length, 0, 1L);
        LENGTH.setRelease(length, 0, 2L);
    }

    @Actor
    public void observer(II_Result r) {
        r.r1 = (int) (long) LENGTH.getAcquire(length, 0);
        r.r2 = (int) (long) LENGTH.getAcquire(length, 0);
    }
}
