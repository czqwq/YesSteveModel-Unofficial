package com.fox.ysmu.model.format;

import com.fox.ysmu.data.ConfigDeclarations;

/**
 * 一次模型烘焙的产物：缓存登记与模型自带的设置声明快照。
 * <p>
 * phase78 起 {@code ServerModelManager.registerModel} 除 {@link ServerModelInfo} 外还要保存
 * {@link ConfigDeclarations}（服务端 config 动作/roaming 校验的前提）。声明就来自烘 {@code ServerModelInfo} 的
 * 同一个 {@code ModelData}，所以两个格式类（{@link FolderFormat}/{@link YsmFormat}）把两样东西一起返回，避免调用方
 * 为了拿声明再解析一遍，也避免"登记了模型却忘了传声明"这种两处漏一处的问题。
 * <p>
 * 只在烘焙成功时存在；写缓存失败时调用方拿到的就是 {@code null}（与改前的 {@code ServerModelInfo == null} 同一
 * 约定），声明没有单独的失败态。
 */
final class BakedModel {

    final ServerModelInfo info;
    final ConfigDeclarations declarations;

    BakedModel(ServerModelInfo info, ConfigDeclarations declarations) {
        this.info = info;
        this.declarations = declarations;
    }
}
