package dev.palworks.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How a trough's feed is shared between joined troughs, and how full they look. */
class TroughFeedTest {

    @Test
    void feedIsSharedEvenly() {
        assertArrayEquals(new int[] {5, 5}, TroughFeed.share(10, 2));
        // The odd points go to the first
        assertArrayEquals(new int[] {4, 3, 3}, TroughFeed.share(10, 3));
        assertArrayEquals(new int[] {1, 0, 0}, TroughFeed.share(1, 3));
        assertArrayEquals(new int[] {0}, TroughFeed.share(0, 1));
    }

    @Test
    void emptyIsBareAndAnythingAtAllShows() {
        assertEquals(0, TroughFeed.fillLevel(0, 64));
        assertEquals(1, TroughFeed.fillLevel(1, 64));
        assertEquals(1, TroughFeed.fillLevel(1, 64 * 10)); // even along a long line
    }

    @Test
    void itRisesAQuarterAtATimeAndOnlyFullIsTheTop() {
        assertEquals(1, TroughFeed.fillLevel(15, 64));
        assertEquals(2, TroughFeed.fillLevel(16, 64));
        assertEquals(3, TroughFeed.fillLevel(32, 64));
        assertEquals(4, TroughFeed.fillLevel(48, 64));
        assertEquals(4, TroughFeed.fillLevel(63, 64));
        assertEquals(TroughFeed.MAX_FILL, TroughFeed.fillLevel(64, 64));
    }

    @Test
    void aComparatorReadsItLikeAContainer() {
        assertEquals(0, TroughFeed.signal(0, 64));
        assertEquals(1, TroughFeed.signal(1, 64));
        assertEquals(15, TroughFeed.signal(64, 64));
    }
}
