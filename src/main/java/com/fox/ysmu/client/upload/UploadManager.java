package com.fox.ysmu.client.upload;

/**
 * 模型上传功能的**未实现占位**（phase7 `R-02`，非目标）：{@link #FILE_PATH} 与 {@link #STATUE} 目前全仓
 * 只写不读，因此本类保持原样、不加固。
 * <p>
 * 将来补上真实上传流程时必须同时处理两点：
 * <ol>
 * <li>{@link #finishUpload()} 的调用方是 {@code CompleteFeedback.Handler#onMessage}（在
 * {@code network/message/CompleteFeedback.java}，运行于 netty 线程），任何 GUI/渲染状态改动都必须通过
 * {@code Minecraft.func_152344_a} 回投客户端线程；</li>
 * <li>断线（{@code ClientDisconnectionFromServerEvent}）时取消/复位状态，避免跨服务器的过期回调。</li>
 * </ol>
 */
public class UploadManager {

    public static volatile String FILE_PATH = null;
    public static volatile Statue STATUE = Statue.FULFILL;

    public static void finishUpload() {
        FILE_PATH = null;
        STATUE = Statue.FULFILL;
    }

    public enum Statue {
        PROCESSING,
        FULFILL
    }
}
