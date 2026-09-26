package com.lacuna.document;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void isVersion7WithTheRfcVariant() {
        var uuid = UuidV7.at(Instant.now());

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheTimestampInMilliseconds() {
        var time = Instant.parse("2026-09-26T16:40:45.123Z");

        var uuid = UuidV7.at(time);

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(time.toEpochMilli());
        assertThat(uuid.toString()).startsWith("01a0de97-2f43-7");
    }

    @Test
    void sortsByTime() {
        var earlier = UuidV7.at(Instant.parse("2026-09-26T16:40:45.123Z"));
        var later = UuidV7.at(Instant.parse("2026-09-26T16:40:45.124Z"));

        assertThat(earlier).isLessThan(later);
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void differsWithinTheSameMillisecond() {
        var time = Instant.now();

        var distinct = IntStream.range(0, 10_000).mapToObj(i -> UuidV7.at(time)).distinct().count();

        assertThat(distinct).isEqualTo(10_000);
    }
}
