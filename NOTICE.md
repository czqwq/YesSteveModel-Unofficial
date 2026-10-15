# NOTICE

本仓库的**源代码**以 MIT 许可发布（见 `LICENSE`）。

## 内置模型资产不属于 MIT

`src/main/resources/assets/ysmu/builtin/**` 下的内置模型资产**不适用** MIT 许可。每个内置模型的
许可证以其自身 `ysm.json` 的 `metadata.license` 声明为**权威**（与上游 Yes Steve Model 的
`NOTICE.md` 同一规则）；模型清单未显式声明许可时，按 `All rights reserved` 解释。

| 范围 | 许可 | 说明 |
| --- | --- | --- |
| `builtin/default`、`builtin/misc/{1_alex,2_steve,3_default_boy,4_default_controllers}` | CC 0 | 公共领域奉献，来自上游 |
| `builtin/misc/{5_qingluka,6_wine_fox}` | 随包声明 | 移植特有，来自参考分支 |
| `builtin/wine_fox/*`（19 个） | CC BY-NC-SA 4.0 | **非商业**；署名与许可声明随各 `ysm.json` 与 `wine_fox/15_kluonoa/LICENSE` 一并分发 |

上游 22 个 `wine_fox` 内置模型中有 3 个（`09_hailuo`、`10_zhiban`、`17_mini`）声明
`All Rights Reserved`，**未收录**在本仓库中（本仓库没有它们的再分发授权）。

因此：**不得将本仓库的内置模型资产用于商业用途**，即使代码本身是 MIT。

## 第三方源码

- `src/main/java/{software,com/eliotlash,net/geckominecraft}`：GeckoLib 3 动画引擎，MIT 许可，见
  `LICENSE-geckolib-engine.txt` 与 `PROVENANCE.md`。
- `src/main/java/rip/ysm/imagestream`（238 个文件）与 `src/main/resources/META-INF/services/javax.imageio.spi.*`：
  ImageStream 图片解码库（JPEG/WebP/AVIF），取自 <https://github.com/OpenYSM/ImageStream> 提交
  `71fa2bf3ce9ac646b8c68c0acfc3da7f7337cc14`（2026-05-05；上游声明为 `rip.ysm:imagestream:1.0-SNAPSHOT`，
  `source/target=17`）。**上游仓库未声明任何许可证**；本仓按上游 YSM 相同的方式随包分发这些解码器，
  Owner 已知悉并接受。