package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.codegen.TypedAppenderGenerator;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.segment.SegmentFormatException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spec's two-appender equivalence requirement: the generated typed appender and the generic
 * appender must produce byte-identical segments. The typed appender is generated, compiled
 * in-memory, and driven through the same op stream as the generic one; the whole segment files are
 * then compared byte-for-byte.
 */
class AppenderEquivalenceTest {

    @TempDir
    Path dir;

    private static final String PKG = "io.ito.arena.gen";
    private static final String CLS = "AllTypesAppender";

    @Test
    void genericAndTypedAppendersProduceByteIdenticalSegments() throws Exception {
        ArenaSchema schema = WriterFixtures.allTypesSchema(8);
        Class<?> typed = compileTypedAppender(schema);

        Path generic = dir.resolve("generic.arena");
        Path typedSeg = dir.resolve("typed.arena");

        try (SegmentWriter gw = SegmentWriter.createSegment(
                generic, schema, 8, 1L, 1L, new WriterFixtures.FakeClock(1000, 10))) {
            writeRows(new GenericSink(gw.genericAppender()));
            gw.seal();
        }
        try (SegmentWriter tw = SegmentWriter.createSegment(
                typedSeg, schema, 8, 1L, 1L, new WriterFixtures.FakeClock(1000, 10))) {
            Object appender = typed.getConstructor(SegmentWriter.class).newInstance(tw);
            writeRows(new TypedSink(typed, appender));
            tw.seal();
        }

        byte[] a = Files.readAllBytes(generic);
        byte[] b = Files.readAllBytes(typedSeg);
        assertThat(b).as("typed segment is byte-identical to the generic segment").isEqualTo(a);
    }

    @Test
    void typedAppenderRejectsSegmentWithADifferentSchema() throws Exception {
        Class<?> typed = compileTypedAppender(WriterFixtures.allTypesSchema(8));
        // A segment created from a different schema has a different header hash.
        try (SegmentWriter mismatched = SegmentWriter.createSegment(
                dir.resolve("other.arena"), WriterFixtures.varlenSchema(8, 64), 8, 1L, 1L,
                new WriterFixtures.FakeClock(0, 1))) {
            Constructor<?> ctor = typed.getConstructor(SegmentWriter.class);
            assertThatThrownBy(() -> ctor.newInstance(mismatched))
                    .hasCauseInstanceOf(SegmentFormatException.class);
        }
    }

    /** Applies a fixed, seeded op stream (with some nulls) — enough rows to force a row-count seal. */
    private static void writeRows(RowSink s) {
        SplittableRandom rng = new SplittableRandom(42);
        for (int r = 0; r < 10; r++) {
            // Draw every value unconditionally so both sinks consume the RNG identically.
            boolean flag = rng.nextBoolean();
            byte i8 = (byte) rng.nextInt();
            short u16 = (short) rng.nextInt();
            int i32 = rng.nextInt();
            long i64 = rng.nextLong();
            float f32 = rng.nextFloat();
            double f64 = rng.nextDouble();
            long low = rng.nextLong();
            long high = rng.nextLong();
            int d32 = rng.nextInt(20_000);
            long t64 = rng.nextLong() & Long.MAX_VALUE;
            byte[] fsb = randomBytes(rng, 16);
            byte[] sym = randomBytes(rng, rng.nextInt(21));
            byte[] bin = randomBytes(rng, rng.nextInt(31));

            s.begin();
            s.ts(1000 + r);                       // time column: always set
            if (r % 3 != 0) s.flag(flag);
            if (r % 4 != 0) s.i8(i8);
            s.u16(u16);
            s.i32(i32);
            s.i64(i64);                           // stats column: always set
            s.f32(f32);
            s.f64(f64);
            s.dec(low, high);
            s.d32(d32);
            s.t64(t64);
            if (r % 5 != 0) s.fsb(buf(fsb), 0, 16);
            if (r % 2 != 0) s.sym(buf(sym), 0, sym.length);
            s.bin(buf(bin), 0, bin.length);
            s.end();
        }
    }

    // --- Sinks: the same op stream, one over the generic API, one over the compiled typed API. ---

    private interface RowSink {
        void begin();
        void end();
        void ts(long v);
        void flag(boolean v);
        void i8(byte v);
        void u16(short v);
        void i32(int v);
        void i64(long v);
        void f32(float v);
        void f64(double v);
        void dec(long low, long high);
        void d32(int v);
        void t64(long v);
        void fsb(DirectBuffer b, int o, int l);
        void sym(DirectBuffer b, int o, int l);
        void bin(DirectBuffer b, int o, int l);
    }

    private record GenericSink(GenericAppender a) implements RowSink {
        // Ordinals from WriterFixtures.allTypesSchema.
        public void begin() { a.beginRow(); }
        public void end() { a.endRow(); }
        public void ts(long v) { a.setLong(0, v); }
        public void flag(boolean v) { a.setBool(1, v); }
        public void i8(byte v) { a.setByte(2, v); }
        public void u16(short v) { a.setShort(3, v); }
        public void i32(int v) { a.setInt(4, v); }
        public void i64(long v) { a.setLong(5, v); }
        public void f32(float v) { a.setFloat(6, v); }
        public void f64(double v) { a.setDouble(7, v); }
        public void dec(long low, long high) { a.setDecimal128(8, low, high); }
        public void d32(int v) { a.setInt(9, v); }
        public void t64(long v) { a.setLong(10, v); }
        public void fsb(DirectBuffer b, int o, int l) { a.setFixedBytes(11, b, o, l); }
        public void sym(DirectBuffer b, int o, int l) { a.setBytes(12, b, o, l); }
        public void bin(DirectBuffer b, int o, int l) { a.setBytes(13, b, o, l); }
    }

    /** Drives the generated typed appender by reflection into its named setters. */
    private static final class TypedSink implements RowSink {
        private final Object appender;
        private final Map<String, Method> methods = new HashMap<>();

        TypedSink(Class<?> cls, Object appender) {
            this.appender = appender;
            for (Method m : cls.getMethods()) {
                methods.put(m.getName(), m);
            }
        }

        private void call(String name, Object... args) {
            try {
                methods.get(name).invoke(appender, args);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("invoke " + name, e);
            }
        }

        public void begin() { call("beginRow"); }
        public void end() { call("endRow"); }
        public void ts(long v) { call("setTs", v); }
        public void flag(boolean v) { call("setFlag", v); }
        public void i8(byte v) { call("setI8", v); }
        public void u16(short v) { call("setU16", v); }
        public void i32(int v) { call("setI32", v); }
        public void i64(long v) { call("setI64", v); }
        public void f32(float v) { call("setF32", v); }
        public void f64(double v) { call("setF64", v); }
        public void dec(long low, long high) { call("setDec", low, high); }
        public void d32(int v) { call("setD32", v); }
        public void t64(long v) { call("setT64", v); }
        public void fsb(DirectBuffer b, int o, int l) { call("setFsb", b, o, l); }
        public void sym(DirectBuffer b, int o, int l) { call("setSym", b, o, l); }
        public void bin(DirectBuffer b, int o, int l) { call("setBin", b, o, l); }
    }

    // --- In-memory compile of the generated appender. ---

    private Class<?> compileTypedAppender(ArenaSchema schema) throws Exception {
        String source = TypedAppenderGenerator.generate(schema, PKG, CLS);
        Path srcDir = Files.createDirectories(dir.resolve("src"));
        Path javaFile = srcDir.resolve(PKG.replace('.', '/')).resolve(CLS + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source);

        Path classesDir = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not JRE) is required to run this test").isNotNull();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, err,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                javaFile.toString());
        assertThat(rc).as("compile output:%n%s", err.toString(StandardCharsets.UTF_8)).isZero();

        URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()}, getClass().getClassLoader());
        return Class.forName(PKG + "." + CLS, true, loader);
    }

    private static byte[] randomBytes(SplittableRandom rng, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) rng.nextInt();
        }
        return b;
    }

    private static UnsafeBuffer buf(byte[] bytes) {
        return new UnsafeBuffer(bytes);
    }
}
