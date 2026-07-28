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
 * Invariant 3: the validity bitmap is a set-only byte read-modify-write. The byte already holds
 * published bits (row 0 here); the writer only ever ORs in the new row's bit, and no one else writes
 * the byte, then release-stores {@code length}. A reader sees old-or-new — both correct below the
 * published count — and never loses an already-published bit.
 */
@JCStressTest
@Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "before the new bit's publish; row 0 bit set")
@Outcome(id = "1, 3", expect = ACCEPTABLE, desc = "new byte seen early via plain read (old-or-new)")
@Outcome(id = "2, 3", expect = ACCEPTABLE, desc = "row 1 published, both bits set")
@Outcome(id = "2, 1", expect = FORBIDDEN, desc = "row 1 published but its validity bit missing")
@Outcome(expect = FORBIDDEN, desc = "row 0's published bit was lost")
@State
public class ValidityByteRmwTest {

    private static final VarHandle LENGTH = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] length = {1L};       // row 0 already published
    private final byte[] validity = {(byte) 1}; // row 0 valid (bit 0)

    @Actor
    public void writer() {
        validity[0] |= (byte) 2;            // set-only RMW: add row 1's validity bit
        LENGTH.setRelease(length, 0, 2L);   // publish row 1
    }

    @Actor
    public void observer(II_Result r) {
        long len = (long) LENGTH.getAcquire(length, 0);
        r.r1 = (int) len;
        r.r2 = validity[0] & 0xFF;
    }
}
