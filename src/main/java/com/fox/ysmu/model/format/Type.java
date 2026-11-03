package com.fox.ysmu.model.format;

/**
 * 一个模型来源在磁盘上的文件形态，用于服务端模型管理列表（{@code RequestServerModelInfo.Info} 的“类型”列）。
 *
 * <p>顺序是线协议的一部分：{@code RequestServerModelInfo} 按 {@link #ordinal()} 写、按序号读，所以新值只能追加在
 * 末尾，不能重排既有值。{@link #ZIP} 是最后追加的——{@code config/ysmu/{custom,auth}/*.zip} 现在会被
 * {@link ZipModelFormat} 当作一个 raw model 加载（上游 {@code ModelSourceDiscovery.java:26} 把 {@code .zip} 列为
 * 模型来源），所以管理界面把它列出来、标成 zip 类型；{@link #UNKNOWN} 仍是解码端的兜底值（旧客户端读到不认识的
 * 序号时落到它）。</p>
 *
 * <p>注意 {@code ZIP} 只用于“管理列表里这个来源是什么形态”；模型被烘焙后的缓存载荷类型仍是
 * {@link #FOLDER}/{@link #YSM}（ZIP 的内容会走与同布局目录相同的解析器，见 {@link ZipModelFormat}）。</p>
 */
public enum Type {
    // 文件形式
    FOLDER,
    YSM,
    UNKNOWN,
    /** {@code <name>.zip} 模型包：一个 zip 就是一个 raw model。 */
    ZIP
}
