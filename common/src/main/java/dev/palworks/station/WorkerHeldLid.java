package dev.palworks.station;

/**
 * What ContainerOpenersCounterMixin adds to vanilla's count of who has a container open: a worker
 * holding it open for a while. Use {@link ChestHelper#holdOpen}. Implemented by Mixin on the
 * counter, never by a lambda.
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface WorkerHeldLid {
    /** Count a worker as having this container open until this game time. */
    void palworks$holdOpenUntil(long gameTime);
}
