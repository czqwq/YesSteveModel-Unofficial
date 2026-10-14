# YesSteveModel-Unofficial

> [!NOTE]
> 注意：[legacy分支](https://github.com/Huli-fox/YesSteveModel-Unofficial/tree/legacy)（基于[1.1.5版本](https://github.com/YesSteveModel/LgeacyYSM)的移植）将不再维护，工作重心转移到将[OpenYSM](https://github.com/OpenYSM/OpenYSM)移植回1.7.10

## 运行依赖

- **动画引擎已内置于 YSMU**（GeckoLib 3 for 1.7.10 的源码快照，见 `PROVENANCE.md`）。本构建自带 `software.bernie.geckolib3`，不再是独立 Mod，也不需要 `required-after:geckolib`。
- **升级必读：请删除 mods 文件夹里的 `geckolib-*.jar`。** FML 1.7.10 只有一个类加载器，本构建已内嵌引擎；如果旧的 GeckoLib jar 还在，两份 `software.bernie.geckolib3` 会互相遮蔽。缺少 GeckoLib 不会导致无法启动（引擎随本 Mod 一起加载）。

## 如何配置高版本资产来源

部分模型包会直接播放**原版**音效、使用原版粒子（例如挥剑时响起的原版三叉戟声音）。1.7.10 里没有这些资源，所以 YSMU 支持从你本机安装的**高版本 Minecraft** 里读取它们。

不配置也能正常游玩，只是这类音效和粒子不会出现。配置一次即可，建议用 1.20.1 或更新的版本。

### 填这三项

打开 `config/ysmu.cfg`，在 `local_assets` 一节里填：

| 配置项 | 填什么 |
| --- | --- |
| `HighVersionGamePath` | 含 `assets` 文件夹的那个目录。**Prism / MultiMC 填启动器根目录**（例如 `E:\modpack\gtnh\prismlauncher`），官方启动器填 `.minecraft` 目录。 |
| `HighVersionAssetVersion` | **资产索引名，不是游戏版本号**，见下表。 |
| `HighVersionJarVersion` | 可选，只有想用高版本粒子时才需要；留空则粒子沿用 1.7.10 自带的。 |

> [!IMPORTANT]
> `HighVersionAssetVersion` 最容易填错：它要的是该版本 `assets/indexes` 目录里那个 json 的**文件名**（去掉 `.json`）。例如 1.20.1 要填 `5`，填 `1.20.1` 会提示找不到。

| 游戏版本 | `HighVersionAssetVersion` |
| --- | --- |
| 1.20.1 | `5` |
| 1.21.1 | `17` |
| 1.21.11 | `29` |

表里没有的版本：打开该版本 `assets/indexes` 目录，看里面 json 的文件名，把它填进去即可。

### 生效与确认

- 改完 `ysmu.cfg` 后**重启游戏**；也可以在游戏里用 `/ysm setgamepath <路径> <索引名>` 下发（例如 `/ysm setgamepath E:/modpack/gtnh/prismlauncher 5`），客户端立刻生效。第一次进世界还没配置时，聊天栏会提示这条命令。
- 确认成功：启动日志里出现 `LocalAssetProvider initialized`（以及 `Loaded ... sound events from sounds.json`）。填错不会崩游戏，日志和聊天栏会说明原因，并列出可用的索引名。
- Prism / MultiMC 用户注意：高版本**粒子**目前还读不到（音效不受影响），留空 `HighVersionJarVersion` 即可。

## 当前状态：Version1.0
