package io.ito.arena.demo;

import io.ito.arena.rotate.DailyRotationPolicy;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Appends mock quote data to an arena table directory, indefinitely (or for a bounded duration), so
 * the data can be queried live from the DuckDB extension while this writes. Prices are Int64
 * implied-scale (1e-4). Args: {@code <baseDir> [table=quotes] [rowsPerSecond=200] [seconds=∞]}.
 */
public final class LiveWriterMain {

    public static void main(String[] args) {
        Path base = Path.of(args[0]);
        String table = args.length > 1 ? args[1] : "quotes";
        int rate = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        long durationMs = args.length > 3 ? Long.parseLong(args[3]) * 1000L : Long.MAX_VALUE;

        SegmentDirectory dir = new SegmentDirectory(base, table);
        long epoch = dir.latestEpoch().orElse(0) + 1;
        RotatingWriter writer = RotatingWriter.open(dir, quotesSchema(table), 4096, epoch, 8,
                new DailyRotationPolicy(), Clock.systemUTC(), new SystemEpochNanoClock());

        String[] symbols = {"AAPL", "MSFT", "GOOG", "AMZN", "NVDA"};
        UnsafeBuffer[] symBuf = new UnsafeBuffer[symbols.length];
        int[] symLen = new int[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            byte[] b = symbols[i].getBytes(StandardCharsets.UTF_8);
            symBuf[i] = new UnsafeBuffer(b);
            symLen[i] = b.length;
        }

        SystemEpochNanoClock clock = new SystemEpochNanoClock();
        long deadline = durationMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + durationMs;
        long intervalNanos = 1_000_000_000L / Math.max(1, rate);
        long g = 0;
        long lastHeartbeat = System.currentTimeMillis();
        System.out.printf("LiveWriter: table '%s' under %s at ~%d rows/s%n", table, base, rate);

        while (System.currentTimeMillis() < deadline) {
            long ts = clock.nanoTime();
            int s = (int) (g % symbols.length);
            long px = 1_000_000L + Math.round(Math.sin(g / 50.0) * 50_000L) + s * 10_000L;  // scaled 1e-4
            UnsafeBuffer sb = symBuf[s];
            int sl = symLen[s];
            // The append lambda captures only per-iteration final values, so a rotation re-invoking it
            // re-writes the identical row (RotatingWriter's side-effect-free contract).
            writer.append(a -> {
                a.beginRow();
                a.setLong(0, ts);
                a.setBytes(1, sb, 0, sl);
                a.setLong(2, px);
                a.endRow();
            });
            g++;
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat >= 100) {
                writer.heartbeat();
                lastHeartbeat = now;
            }
            LockSupport.parkNanos(intervalNanos);
        }
        writer.close();
        System.out.printf("LiveWriter: wrote %d rows%n", g);
    }

    private static ArenaSchema quotesSchema(String table) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("sym", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "512")),
                field("px", new ArrowType.Int(64, true)));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, table,
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px",
                MetadataKeys.BATCH_ROWS, "64")));  // small: batches seal every few seconds at demo rate
    }

    private static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
