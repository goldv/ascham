package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LivenessTest {

    @TempDir
    Path dir;

    @Test
    void heartbeatDistinguishesAliveFromDead() {
        long[] now = {0L};
        LongSupplier clock = () -> now[0];
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema(), 8, 7L, 1L, new org.agrona.concurrent.SystemEpochNanoClock());
             SnapshotReader reader = SnapshotReader.open(path)) {

            LivenessMonitor monitor = new LivenessMonitor(reader, Duration.ofSeconds(1), clock);
            assertThat(monitor.writerEpoch()).isEqualTo(7L);

            // Heartbeat frozen but still within the stall threshold → ALIVE.
            now[0] = Duration.ofMillis(500).toNanos();
            assertThat(monitor.poll()).isEqualTo(LivenessMonitor.Status.ALIVE);

            // Heartbeat frozen past the threshold → STALLED.
            now[0] = Duration.ofSeconds(2).toNanos();
            assertThat(monitor.poll()).isEqualTo(LivenessMonitor.Status.STALLED);

            // Writer bumps the heartbeat → ALIVE again.
            writer.heartbeat();
            now[0] = Duration.ofSeconds(3).toNanos();
            assertThat(monitor.poll()).isEqualTo(LivenessMonitor.Status.ALIVE);
        }
    }

    @Test
    void reportsInProgressRowCountForStuckDetection() {
        Path path = dir.resolve("seg.arena");
        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema(), 8, 1L, 1L, new org.agrona.concurrent.SystemEpochNanoClock());
             SnapshotReader reader = SnapshotReader.open(path)) {

            GenericAppender a = writer.genericAppender();
            for (int r = 0; r < 3; r++) {
                a.beginRow();
                a.setLong(0, 1000 + r);
                a.setLong(1, r);
                a.endRow();
            }

            LivenessMonitor monitor = new LivenessMonitor(reader, Duration.ofSeconds(1), () -> 0L);
            assertThat(monitor.inProgressRowCount()).isEqualTo(OptionalLong.of(3));
        }
    }

    private static ArenaSchema schema() {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, "64")));
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, new FieldType(true, type, null, Map.of()), List.of());
    }
}
