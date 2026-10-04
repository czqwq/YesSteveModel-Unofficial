# YesSteveModel-Unofficial

> [!NOTE]
> 注意：[legacy分支](https://github.com/Huli-fox/YesSteveModel-Unofficial/tree/legacy)（基于[1.1.5版本](https://github.com/YesSteveModel/LgeacyYSM)的移植）将不再维护，工作重心转移到将[OpenYSM](https://github.com/OpenYSM/OpenYSM)移植回1.7.10

## 运行依赖

- **动画引擎已内置于 YSMU**（GeckoLib 3 for 1.7.10 的源码快照，见 `PROVENANCE.md`）。本构建自带 `software.bernie.geckolib3`，不再是独立 Mod，也不需要 `required-after:geckolib`。
- **升级必读：请删除 mods 文件夹里的 `geckolib-*.jar`。** FML 1.7.10 只有一个类加载器，本构建已内嵌引擎；如果旧的 GeckoLib jar 还在，两份 `software.bernie.geckolib3` 会互相遮蔽。缺少 GeckoLib 不会导致无法启动（引擎随本 Mod 一起加载）。

## 当前状态：Version1.0
