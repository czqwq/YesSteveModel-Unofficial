package com.fox.ysmu.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.Logger;

import com.fox.ysmu.ysmu;

/**
 * A tiny per-side queue of work that must run on the game thread.
 * <p>
 * 1.7.10's {@code SimpleNetworkWrapper} invokes packet handlers inline on the Netty thread, and this mapping
 * has no {@code IThreadListener}/{@code addScheduledTask} helper, so a handler that touches the world, the
 * entity registry or a GUI has to hop back to the main thread itself.
 * <p>
 * The two sides get <b>separate</b> queues. In single-player the integrated server and the client run on
 * different threads in the same JVM, so one shared queue would let a client tick drain server work (and vice
 * versa) - exactly the thread affinity this class exists to provide. Each queue is drained by its own tick in
 * {@link com.fox.ysmu.event.DeferredWorkTicker}.
 */
public final class DeferredWork {

    private static final Logger LOG = ysmu.LOG;

    private static final Queue<Runnable> CLIENT = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> SERVER = new ConcurrentLinkedQueue<>();

    private DeferredWork() {}

    /** Queues work for the client thread. */
    public static void client(Runnable task) {
        if (task != null) {
            CLIENT.add(task);
        }
    }

    /** Queues work for the integrated/dedicated server thread. */
    public static void server(Runnable task) {
        if (task != null) {
            SERVER.add(task);
        }
    }

    /** Runs every queued client task; called from the client tick. */
    public static void drainClient() {
        drain(CLIENT);
    }

    /** Runs every queued server task; called from the server tick. */
    public static void drainServer() {
        drain(SERVER);
    }

    private static void drain(Queue<Runnable> queue) {
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.error("A deferred task failed", t);
            }
        }
    }
}
