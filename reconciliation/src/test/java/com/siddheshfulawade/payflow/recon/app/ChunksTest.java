package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunksTest {

    private static List<Integer> items(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    @DisplayName("REQ-PF-040 the chunk size is 1,000 (SPEC PF-10)")
    void size() {
        assertThat(Chunks.SIZE).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-PF-040 2,500 items become chunks of 1,000, 1,000 and 500, in order, none lost")
    void partitions() {
        List<List<Integer>> chunks = Chunks.partition(items(2_500));

        assertThat(chunks).extracting(List::size).containsExactly(1_000, 1_000, 500);
        assertThat(chunks.stream().flatMap(List::stream).toList()).isEqualTo(items(2_500));
    }

    @Test
    @DisplayName("REQ-PF-040 exactly 1,000 items are one chunk; none are no chunk (no empty chunk)")
    void boundaries() {
        assertThat(Chunks.partition(items(1_000))).hasSize(1);
        assertThat(Chunks.partition(items(1_001))).extracting(List::size).containsExactly(1_000, 1);
        assertThat(Chunks.partition(List.<Integer>of())).isEmpty();
    }
}
