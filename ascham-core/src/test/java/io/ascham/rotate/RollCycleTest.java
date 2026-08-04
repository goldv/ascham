package io.ascham.rotate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RollCycleTest {

    @Test
    void acceptsCyclesThatDivideTheDayEvenly() {
        assertThat(RollCycle.of(Duration.ofHours(4)).minutes()).isEqualTo(240);
        assertThat(RollCycle.of(Duration.ofHours(6)).minutes()).isEqualTo(360);
        assertThat(RollCycle.of(Duration.ofMinutes(30)).minutes()).isEqualTo(30);
        assertThat(RollCycle.of(Duration.ofDays(1))).isEqualTo(RollCycle.DAILY);
    }

    @Test
    void rejectsCyclesThatDoNotDivideTheDay() {
        assertThatThrownBy(() -> RollCycle.of(Duration.ofHours(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RollCycle.of(Duration.ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RollCycle.of(Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RollCycle.of(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesHumanReadableCycles() {
        assertThat(RollCycle.parse("30m").minutes()).isEqualTo(30);
        assertThat(RollCycle.parse("4h").minutes()).isEqualTo(240);
        assertThat(RollCycle.parse("1d")).isEqualTo(RollCycle.DAILY);
        assertThatThrownBy(() -> RollCycle.parse("4x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RollCycle.parse("h")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floorAndEndAreMidnightAnchoredUtc() {
        RollCycle fourHours = RollCycle.parse("4h");
        assertThat(fourHours.floor(Instant.parse("2026-08-04T05:59:59Z")))
                .isEqualTo(Instant.parse("2026-08-04T04:00:00Z"));
        assertThat(fourHours.floor(Instant.parse("2026-08-04T04:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-04T04:00:00Z"));
        assertThat(fourHours.end(Instant.parse("2026-08-04T20:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));

        assertThat(RollCycle.DAILY.floor(Instant.parse("2026-08-04T23:59:59Z")))
                .isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
    }
}
