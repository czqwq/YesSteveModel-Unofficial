package com.fox.ysmu.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fox.ysmu.Config;

public final class ThreadTools {

    /**
     * 模型 IO / 压缩 / 加密 / 发送共用的后台池。
     *
     * 这里是 {@code corePoolSize == maximumPoolSize == n}（n 至少为 1，取自 {@link Config#THREAD_COUNT}）。
     * 旧实现 {@code new ThreadPoolExecutor(0, 10, ...)} 配合无界队列时 {@code execute()} 只在
     * {@code workerCount == 0} 时补一个 worker，池的实际并发度恒为 1：多玩家并发下载模型会互相排队，
     * 池内任何一个 {@code Thread.sleep}（等待密码、带宽节流）都会拖住同池的所有任务。
     *
     * {@code Config.THREAD_COUNT} 在本类首次被使用时读取一次；{@code CommonProxy.preInit} 会先执行
     * {@code Config.init(...)}，之后才有任何代码向本池提交任务。
     */
    public static final ExecutorService THREAD_POOL = createThreadPool();

    private ThreadTools() {}

    private static ExecutorService createThreadPool() {
        int workers = Math.max(1, Config.THREAD_COUNT);
        return new ThreadPoolExecutor(workers, workers, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }
}
