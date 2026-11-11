package dev.palworks.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/** Taking turns fairly when there's more to do in a tick than the tick's budget allows. */
final class Turns {
    private Turns() {}

    /**
     * The {@code count} of {@code waiting} whose last turn was longest ago ({@code lastTurn},
     * lowest first, so {@code Long.MIN_VALUE} for "never" goes first), or all of them if there
     * aren't more. Each one gets a turn at least every {@code waiting / count} calls, however
     * many keep coming back without anything changing for them.
     */
    static <T> List<T> longestWaiting(List<T> waiting, ToLongFunction<T> lastTurn, int count) {
        if (waiting.size() <= count) {
            return waiting;
        }
        List<T> sorted = new ArrayList<>(waiting);
        sorted.sort(Comparator.comparingLong(lastTurn));
        return sorted.subList(0, count);
    }
}
