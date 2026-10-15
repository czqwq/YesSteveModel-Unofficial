package com.fox.ysmu.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import com.fox.ysmu.ysmu;

/**
 * 由外部驱动（客户端 tick）推进的间隔计时器。
 *
 * <p>存在的理由：同步看门狗 / 完成等待 / 「等世界加载」这类**轮询**任务原先都是
 * {@code ThreadTools.THREAD_POOL.submit} 一个 {@code Thread.sleep} 循环。ThreadCount=1
 * （{@code ysm_sync/ThreadCount}，Config 允许）时，池里只有这一个工作线程：睡眠中的看门狗
 * 一旦先被取走，它等待的解析任务与后续下载包处理就全部堵在队列里，直到 60 秒停滞超时
 * 才被放行 —— 也就是「看门狗把它自己的生产者饿死」。计时任务不消耗解析线程，这类死锁
 * 从结构上不存在。</p>
 *
 * <p>被驱动方每 tick 调用一次 {@link #runDueTasks()}；到期任务在**调用线程**上执行（客户端
 * 就是主线程），因此任务体可以安全读写主线程状态。时钟可注入，便于用假时钟做确定性测试。</p>
 */
public final class TickScheduler {

    /** 取消句柄。{@link #cancel()} 幂等，可从任务体内调用（自取消）。 */
    public interface Handle {

        void cancel();

        boolean isCancelled();
    }

    private static final TickScheduler CLIENT = new TickScheduler(System::currentTimeMillis);

    private final LongSupplier clock;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private boolean driving;

    public TickScheduler(LongSupplier clock) {
        this.clock = clock;
    }

    /** 客户端共享实例：由 {@code ClientEventHandler.onClientTick} 推进。 */
    public static TickScheduler client() {
        return CLIENT;
    }

    /**
     * 注册一个每 {@code intervalMs} 执行一次的任务，立即开始计时（首次执行在一个间隔之后，
     * 错过多个间隔时只执行一次，不补跑）。任务抛异常即自动注销并打一条 WARN：
     * 每 tick 重复打印同一个异常比丢失一个计时任务更糟。
     */
    public synchronized Handle scheduleEvery(long intervalMs, Runnable action) {
        if (intervalMs < 1) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        Task task = new Task(intervalMs, action, clock.getAsLong());
        tasks.add(task);
        return task;
    }

    /** 推进一次：执行所有到期任务。仅由驱动方调用（客户端 tick / 测试）。 */
    public void runDueTasks() {
        // Never hold the scheduler monitor across callbacks: packet threads may schedule while a callback locks
        // its own owner. A callback can also drive again after advancing the clock; reject nested/concurrent drives.
        synchronized (this) {
            if (driving) return;
            driving = true;
        }
        try {
            long now = clock.getAsLong();
            for (Task task : tasks) {
                if (task.cancelled) {
                    tasks.remove(task);
                    continue;
                }
                if (now - task.lastRunMs < task.intervalMs) {
                    continue;
                }
                task.lastRunMs = now;
                task.fire();
            }
        } finally {
            synchronized (this) {
                driving = false;
            }
        }
    }

    /** 未注销的计时任务数（诊断 + 测试断言「会话结束后不留计时器」）。 */
    public int pendingCount() {
        return tasks.size();
    }

    /** 卸载会话时清空（连接断开 / 客户端退出）。 */
    public synchronized void clear() {
        for (Task task : tasks) {
            task.cancelled = true;
        }
        tasks.clear();
    }

    private static final class Task implements Handle {

        private final long intervalMs;
        private final Runnable action;
        private volatile boolean cancelled;
        private long lastRunMs;

        private Task(long intervalMs, Runnable action, long nowMs) {
            this.intervalMs = intervalMs;
            this.action = action;
            this.lastRunMs = nowMs;
        }

        private void fire() {
            try {
                action.run();
            } catch (Throwable t) {
                cancelled = true;
                ysmu.LOG.warn("[YSMU-TICK] scheduled task failed and was cancelled: {}", t.toString(), t);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
