# 上游移植状态报告:还没移植什么,以及移植了但没接线什么

**审计对象**:`E:\IDEA\YesSteveModel-Unofficial`(YSMU),基线 = HEAD `80f5f89` + **88 个未提交改动**。
**上游权威**:`tmp/YesSteveModel-dev-1.20`(OpenYSM 1.20,`com/elfmcys/ysm`,958 `.java`)。
**参考移植树**:`tmp/YesSteveModel-Unofficial-perf-previewUI`(473 `.java`,完成版但**不是正确版**,见 `AGENTS.md`)。
**原生参考**:`tmp/YesSteveModel-Native-dev`(仅用于解释上游行为,不是移植目标)。

**这是一份只读报告。** 本轮没有修改 `src/**`、`build/**`、`tmp/**` 或任何已存在的 `.md`;新增的只有本文件与 4 份 `.agent/audit-*.md` 证据文件。

---

## 0. 一页结论

### 0.1 先说"健康度":工作区的编译与测试状态是好的

这一点必须先说,因为它决定了后面每一条"接线"结论的可信度:

| 项 | 实测 |
| --- | --- |
| 未提交批次是否编译过 | **是**。`build/classes/java/main` 与 `build/test-results/test/*.xml` 时间戳 **2026-10-09 01:46–01:52**;`src/` 下 mtime 晚于该时刻的文件数 = **0** |
| 全量测试结果 | **854 tests / 4 failures / 0 errors**;4 个失败全部是既有基线(`CtrlItemMatcherTest` 2 + `ItemTagMatcherTest` 2,见 `.agent/matcher-headless-bootstrap-findings.md`) |
| 改动过的 83 个 `.java` 的语法 | `javac -proc:none`(无 classpath)100 条诊断**全部**是"找不到符号/程序包不存在",**0 条语法错误** |
| 结论 | 批次**整体已编译并跑过全量测试**;两份 ExecPlan 里"not compiled/verified"是写作时刻的状态,**今天已不成立**,不要照抄 |

**仍然缺的不是编译,而是逐项实机验证**:材质 shader 切线/非实心面、PBR 宿主切线、音效的身份/替换/所有权行为、HUD 与第一人称臂的画面一致性。这些只能靠 `runClient`,本报告一条都没有声称已验证。

### 0.2 上游功能面大概移植到什么程度

| 维度 | 数字 | 说明 |
| --- | --- | --- |
| 上游差距审计 G01–G19(早前那轮) | **22 条已关闭 / 7 条仍开 / 2 条旧结论已过期 / 2 条部分** | 其中 20 条关闭**只存在于未提交工作区**(`git show HEAD:` 为 ABSENT) |
| 上游 molang 注册名 | 上游 **287** → 本仓覆盖 **182** / 缺 **105** / 多 49 / 同名不同义 **8** | 见 §2.6;105 里 **1.7.10 有取值来源、真正可移植的约 23 个 + 7 个函数名** |
| 参考分支文件级差额 | 参考 473 → 同路径 417 / 同名异路径 5 / 完全没有 51 | 51 里 31 个是 `rip/ysm/imagestream/**`(已在 `libs/ImageStream--SNAPSHOT.jar` 发货),**真缺口 5 个文件** |
| `Config` 开关 | 64 个 `public static` 字段 = 60 个开关;**59 有行为消费者 / 1 零消费者** | 对比旧审计的"24 个死开关" |
| 顶层死类 | **0**(300 个类型里仅 3 个 0 引用,全是 Mixin 反射加载) | — |

### 0.3 三条最值得先看的东西

1. **一个结构性风险,一次能消掉一整类缺陷**:61 个 `query.*`/`ysm.*` 名字活在 `public static` 的 `MolangParser.VARIABLES` 里,值是**闭包住"最后一次 `apply` 的实体"**的 supplier。渲染帧内安全(先 apply 后 process),但 `/ysm query`、debug overlay、`@sync` 脚本在帧外读它 → **42 个名字**(23 `query.*` + 19 `ysm.*`)会读到**别的实体**的值。本仓已有正确对照物 `MolangFrameContext`(ThreadLocal + `clear()`)。见 §2.6.3。
2. **一条静默错值,两条路径都错**:`query.time_of_day` 在**控制器条件**路径返回原始 `getWorldTime()`(小时数),**关键帧**路径走的是 `t/24000`(同样不是 `[0,1)`)—— 两者都偏离上游的 `(t+6000)/24000 % 1`。包写 `query.time_of_day<0.5`(判断夜晚)在控制器里**恒假**。见 §1.1 #1(附本报告 v1 的一处更正)。
3. **一个"未移植 + 有真实包在用"的名字**:`ysm.first_person_mod_hide` 的注册被注释掉(`AnimationRegister.java:445`),而工作区里 `tmp/可露凯-速度之星v1.1/animations/main.animation.json` 正在用它;当前它静默读 0,第一人称隐藏条件恒假。见 §1.1 #3。

---

## 1. 上游有、本仓没有:仍未移植的功能

分级沿用业主的量表:**R1** 破坏已发货功能 · **R2** 静默错值 · **R3** 缺一项包可能用到的能力 · **R4** 开发者/可观测性 · **R5** 观感。
每条给"上游 `file:line`"、"本仓状态"、"影响"、"1.7.10 是否有取值来源"。

### 1.1 R2 — 静默错值(先修这批)

| # | 项 | 上游 | 本仓 | 1.7.10 有源? | 后果 |
| --- | --- | --- | --- | --- | --- |
| **1** | `query.time_of_day` 两条路径都不在 `[0,1)` | `QueryBinding.java:61`(归一化) | 控制器 `OpenYsmControllerExpressionEvaluator.java:1480-1483` **与 `time_stamp` 共用分支**,返回原始 `getWorldTime()`;关键帧 `AnimationRegister.java:536` 走 `software/bernie/geckolib3/util/MolangUtils.java:8-10` 的 `t/24000`(**无 `%1`、无相位**) | **有** | 控制器条件里的夜晚/白天判断恒错。**一行可修,证据最强**<br>**注(已修正)**:本报告 v1 写"关键帧路径是对的"是**错的** —— 它引的是**另一个** `MolangUtils`(`molang/util/MolangUtils.java:29-31`,那个才是 `(t+6000)/24000%1`),而关键帧 import 的是 `util/MolangUtils.java:8-10`。所以两条路径原本都不在 `[0,1)`。修复后两条都走上游实现(`AnimationRegister.java:775` 与控制器 `:1494`),由 `TimeOfDayPathAgreementTest` 钉住 |
| **2** | `query.is_jumping` 两条路径给相反答案 | `QueryBinding.java:102` | 控制器 `:1870-1873` 仍带 `motionYState(0.0) != 0`(`isFlying` 还把鞘翅算作飞,`:1856-1868`);关键帧 `AnimationRegister.isPlayerJumping:689-691` **已按上游删除该项**,并在 `:680-687` 明文记录了它造成的"跳跃顶点手臂弹回"缺陷 | **有** | 跳跃顶点那一 tick 两条路径分叉;滑翔时同样分叉 |
| **3** | `ysm.first_person_mod_hide` 未注册 | `YSMBinding.java:152` | `AnimationRegister.java:445` 被**注释掉**;工作区 `tmp/可露凯-速度之星v1.1/animations/main.animation.json` 在用(来源:`.agent/audit-pack-facing-surface.md` §3) | **有** | 静默 0 → 第一人称隐藏条件恒假,模型照画 |
| **4** | `@sync` 脚本与 `/ysm query`、debug overlay 在帧外读全局 molang 存储 | 上游每次读取都带 `IContext.entity()`(`geckolib3/core/molang/context/IContext.java:23-24`),没有"当前实体"这种全局状态 | 见 §2.6.3 | **有**(本仓 `MolangFrameContext` 就是现成的修法) | 读到本帧最后绘制的可绑定实体(可能是载具/箭),不是被查询的玩家 |

### 1.2 R3 — 缺一项包可能用到的能力

| # | 项 | 上游 | 本仓状态 | 影响 | 建议 |
| --- | --- | --- | --- | --- | --- |
| **5** | 现行容器/归档准入(`.mxc`、chunk/seekable 容器) | `model/catalog/source/ModelSourceDiscovery.java:158-185`、`ModelSourceResolver.java:50-78`、`format/container/AssetContainerReader.java`(+Writer/ChunkDecoding/ByteArraySeekableChannel) | **ABSENT**。实测 `grep '\.mxc'` = **0**、`ChunkDataSource|readChunk` = **0**、`AssetContainerReader|Writer` = **0**;`OpenYsmFormat.java:31` 仍是旧 `YSGP` 前缀,只收"含 `ysm.json` 的目录"与 `.ysm` | **新版 pack 整个装不进来**(玩家可见) | 格式能力缺口,需独立实现计划;不要只加扩展名 |
| **6** | 模型级 `icon` / `thumbnail` 未摄取 | `RawModelAssembler.java:316-328` | `YSMFolderDeserializer.java:129-163` 只读 `metadata`/`authors[].avatar`;`thumbnail|ThumbGenerator` = **0**;`icon` 只有**包级** `pack_icon`(`ClientPackRegistry.java:90-107`),不是模型级 | 模型选择页无声明图标/缩略图(观感;本仓画实时 FBO 预览作替代) | (a) 有替代物,优先级低 |
| **7** | 模型授权/撤销语义 | `command/sub/AuthCommand.java:28-99` + 选择点校验 | **ABSENT**(`AuthCommand` = 0、`capability/AuthModelsCapability` = 0);`SetModelAndTexture.java:62-79` 仍把客户端字符串直接 `new ResourceLocation` 后写入,**无 catalog/grant 校验** | 任何客户端可直接 set 任意模型(管理员视角,玩家可见)。本仓另有一套 port-only 的 `api/ModelGuiApi` 5 分钟 grant(语义不同,不是上游那套) | (a) 需 owner 定范围:这是权限模型,不是单点补丁 |
| **8** | 投射物替换只覆盖箭 | `client/renderer/replace/ProjectileRendererReplace.java:9-18` 接受通用 Projectile;`GeoProjectilesRenderer.java:16-43` | `mixin/client/MixinRenderArrow.java:27-58` 只 `@Mixin(RenderArrow.class)` + `instanceof EntityArrow`;`IProjectileModelArrow` 唯一实现是 `MixinEntityArrow` | 非箭投射物无模型(玩家可见) | 需按 1.7.10 实体分类逐一接;owner 已排除 `projectile_owner` 实体 id 那一半 |
| **9** | 投射物/载具的禁用开关 | `config/ClientConfig.java:12-13,48-52` `DISABLE_PROJECTILE_MODEL` / `DISABLE_VEHICLE_MODEL` | `Config.java:14-20` 只有 `DISABLE_SELF_MODEL/OTHER_MODEL/SELF_HANDS`;两个名字 grep **各 0** | 无法关掉投射物/载具模型(玩家可见) | (a) 小,与上游 bypass 点一一对应即可 |
| **10** | Opus 模型音频 | `client/sound/instance/CustomSoundInstance.java:30-50` 用托管流;`natives/sound/OpusDecoder.java` | `YSMSoundManager.java:812-851` 只认 Vorbis 识别头;`SOUND_PORT_PLAN.md:42` 自述 "G09 remains an explicit gap" | 用 Opus 的包静音(玩家可见) | (a) **代码内已显式声明 deferred**:只改校验谓词会把不支持的字节省给 CodecJOrbis,需要真的解码器/provider |
| **11** | TLM(东方小女仆)兼容面 | `client/compat/touhoulittlemaid/**`(25 文件) | `touhoulittlemaid` **1 处**引用;`tlm.*` **16 个名字全缺** | 装 TLM 时女仆不能用 YSM 模型(玩家可见) | (a) 大;注意 §2.6.2 的"占位名"问题:上游 `TLMBinding.java:7-9` 的理由是**未安装时这些名字也该存在**,否则包会报错 |
| **12** | 宿主事件面(第三方集成) | `api/rendering/v0/event/{RenderModelEvent,RenderLayerEvent,RegisterRenderStateModifierEvent}`、`api/model/v0/event/RegisterModelLocatorEvent`、`api/annotation/**` + `api/internal/processor/**`(9 文件注解处理器)、`api/molang/MolangNames` | **四名 grep 各 0**;`YsmExtension` 全仓 0。本仓只有 port-only 的 `event/api/SpecialPlayerRenderEvent` + `api/{EntityModelApi,EntityAnimationApi,ModelGuiApi}` | 第三方 mod 无法按上游方式集成(只有开发者可见) | (c) **需 owner 裁定**:`AGENTS.md` 已把 `EntityModelRenderApi.isModelLoaded` 记为 port-only 待裁决,同族 |
| **13** | 存档/会话级协议语义 | `ResourceTransferCancel`(7)/`ResourceTransferFailure`(8)、`SwingHandRequest`(25,全仓 0 命中)、`ProjectileModelState`(26,全仓 0 命中)、`SessionDeltaFragment`(3) | 无对应语义 | 传输中断只能靠超时/断线;`SwingHandRequest` 无等价物 | (a)/(b) 需逐条裁;`PlayerStateReport`(16) 1.7.10 **不需要**(服务端本来就有该状态) |
| **14** | 轮盘"移动锁定" | `PlayerMoveEvent.java:21,28-29,44-46,70-76`(`LOCK_EXTRA_ANIMATION` + `switchLock()`/`isLocked()` + `LOCK_ROULETTE_KEY`)、轮盘锁按钮 `AnimationRouletteScreen.java:177-186`、停止 `:189-194`、Return `:202-203` | **功能缺失**:本仓 `grep 'switchLock|isLocked'` 命中里**没有**这个锁(只有无关的 `HUD_FOLLOW_MODE` 注释与 `refuseGeometryLocked`);本仓只有"动一下就停"(`ClientEventHandler.java:528-543`,且无锁判定) | 表情/额外动画**无法边走边看**(玩家可见) | (a) **上游与参考都有**。注意本仓注释 `AnimationRouletteScreen.java:60-63` 声称这几样"porting would mean inventing the features behind them" —— **被上游实现直接反证**,该注释应改 |
| **15** | 设置行动按钮的 `sound` | `ModelInfoBuilder.java:143` | `ExtraAnimationButton.sound` 未消费 | 按钮音效不响(观感) | (a) 小 |
| **16** | `ModelIndexCache`(服务端指纹侧车) | 上游 `ConvertedSourceIndex`/`ConvertedSourceIndexStore` | **ABSENT**;`ServerModelManager.java:286-291` 六个 cache step **无条件跑** | 每次启动 + 每次 `/ysm reload` 都重跑 `解密→解析→序列化→加密`(开发者可见,大库上可观) | (a) 上游有等价物 → 该接线;这是"上游有等价物"的性能项里唯一还没做的 |

### 1.3 R4/R5 — 开发者与观感

| # | 项 | 上游/参考 | 本仓状态 | 影响 |
| --- | --- | --- | --- | --- |
| **17** | `/particle` 指令 | 参考 `command/ParticleCommand.java:28-97` + `network/message/SpawnParticleCommand.java:52-53` | **ABSENT**;但 `ParticleEffectUtil.spawnAt(:412)` 是**零调用者的成品缝**,注释自称"/particle 指令入口" | 开发者可见。**照抄必须补 `fromBytes` 的 `len` 上界**,否则对端可送 `Integer.MAX_VALUE` 直接 OOM(参考分支自带的缺陷,`AGENTS.md` 已警告这类照抄风险) |
| **18** | 目录分片包 `S2CSyncIndexChunk17` | 参考 `network/message/S2CSyncIndexChunk17.java:21-72` | ABSENT;但本仓把单包上限设成 **2 MiB**(`S2CModelSyncPayload17.java:20`),而 1.7.10 的真实上限是 `S3FPacketCustomPayload` 的 `0x1FFF9A`(FML 走它) | 只有 >2 MiB 的索引发不出去(参考自报触发规模 976 模型,远低于此)→ **记录残量即可,不要分片** |
| **19** | 卡片 tooltip 内容 | `CatalogModelTooltipFormatter.java:17-206`(240 宽,作者/许可/tips/加载错误) | `ModelButton.java:83-94` 只回 `EXTRA_INFO`;作者/许可在独立 `ModelInfoScreen` | 观感;`metadata.links` 上游 `ModelInfoScreen:112-113` 画两条,本仓**未转发**(真漏接线) |
| **20** | 失败条目是否可见 | `FailedCatalogModelButton.java`、`entry/ClientFailedCatalogEntry.java` | **未定位**(grep `FailedCatalogModelButton|ModelFailureRegistry|FAILED_MODELS` 各 0) | 加载失败的包在浏览器里**静默消失**(玩家可见) |
| **21** | 鹦鹉肩上层 | `CustomParrotOnShoulderLayer.java:1-49` | **未定位**(grep `Parrot` = 0) | 1.7.10 原版无肩头鹦鹉实体层 → **(c) 平台无对应物** |
| **22** | 轮廓 pass(发光) | `IGeoRenderer.java:33-39` 不可见发光实体返回 outline | `IGeoRenderer.java:526-527,533-539` 发光分支 `return null` | 1.7.10 无现成 outline pass。**"没有现成机制" ≠ "写不出来"**,属 (d) 平台约束,需 owner 定 |
| **23** | 逐骨光/透明/色的数值映射 | 上游 14-float 属性数组 + native 消费 | 三个名字都在,但 glow ≥ 0 直接 unlit(固定管线近似) | `AGENTS.md` 已记录;数值映射未定,**不发明** |
| **24** | VM 求值器语义 | 上游 `molang/**` 新 VM(lexer/parser/AST/Evaluator 390 行) | **不使用**:pack 表达式走遗留 `com.eliotlash.mclib.math` + `core.molang.MolangParser` | (b) 设计差异。**但见 §2.6.1**:真正的问题不是"没用新 VM",而是**新 VM 树被 vendored 进来却是死代码,看着像覆盖了** |

### 1.4 明确"不算缺口"的(c 类:上游本来就没有 / 平台无对应物)

以下**不要**再当缺口追,`AGENTS.md` 与各审计已有裁定:

- **`vehicle.origin`** —— 上游是 compat 钩子,只接受 Simple Planes / Immersive Aircraft 两个提供者;两者无 1.7.10 版,未装时上游本来就走渲染器 yaw。**无物可接**。
- **`blend_via_shortest_path`** —— 上游全树只有字段/构造/访问器,**没有运行时消费者**。按"上游没有就不要发明"记为传输数据。
- **`PointType`**(上游 `core/keyframe/event/PointType.java`)—— 实测上游全树 `grep PointType` **仅其自身声明 1 命中**,是上游死代码。**本仓不移植是正确的**。
- **`ExportCommand`** —— 上游自己**禁用**导出(`ExportCommand.java:22-51` 返回"当前模型格式尚未稳定,禁止导出")。不是可声称缺失的功能。
- **`gui_no_lighting`** —— 上游唯一消费者是 native protobuf(`ModelInfoBuilder:95`),本仓读不到。**参考分支有真消费者,但参考不是权威** → 保持记录。
- **`free` / `isFree`** —— 上游也 0 消费者。传输数据。
- **`AndroidCompat`** —— 1.7.10 无 Android 端口。
- **`natives/**` 全部** —— 纯原生在 1.7.10 不可移植,本仓用固定管线 + `MaterialDrawSession`/`MaterialBake` 替代(grep 命中 118)。
- **`start_delay`/`loop_delay`/`override_previous_animation`** —— 上游 `Animation` 构造把它们**固定为 0 / true**,解析名不等于可执行语义。
- **`FaceUv.uv_rotation`** —— 上游自己注释掉了(暂未实现),双方都缺。

### 1.5 需要 owner 裁定的(c 类)

| 项 | 两边的理由 | 为什么要你裁 |
| --- | --- | --- |
| 24 个第三方 compat 名字(`ctrl.tac_*` 9 / `im_*` 5 / `carryon_*` 2 / `swem_*` 2 / 其余 6)+ **16 个 `tlm.*`** | 本仓:那些模组没有 1.7.10 版,没源可给。上游:**未安装时也注册占位**,理由写在 `CarryOnCompat.java:47-48`「否则会报错」、`TLMBinding.java:7-9` | 按上游口径这**不是可豁免项**:包写 `tlm.is_sitting` 会静默读 0。补法极简(在 `AnimationRegister.registerYsmVariables` 旁注册 40 个常量),但要不要补是产品决定 |
| 武器骨骼按名字子串隐藏 | 参考分支按 `_Sword`/`_Blade` 子串隐藏骨骼;上游**完全没有**对应物(`grep -i weapon` 上游只有 TacZ 枪械类型串) | **owner 已裁定:不移植** —— 此处只作存档确认 |
| `EntityModelRenderApi.isModelLoaded` 的语义 | port-only 就绪查询;上游宿主面只有事件 | 模型 parked 时它返回 true 而渲染器画的是内置默认 |
| `api/**` 宿主事件面(§1.2 #12) | 上游给第三方 mod 的集成面;本仓是 port-only 形状 | 影响第三方集成方式,不是玩家功能 |

---

## 2. 已移植但没接线

判定口径:**"解析了"不算实现,"有消费者"才算**;"接线"必须能指到真实的消费点。

### 2.1 数据面:解析了、过线了,但没有客户端消费者

四段各自给证据(解析 / 序列化 / 反序列化 / 消费):

| 字段 | 解析 | 过线 | 客户端消费 | 判定 |
| --- | --- | --- | --- | --- |
| `isFree` | `YSMFolderDeserializer.java:172` | `YSMBinarySerializer:541` / `Deserializer:660,728` | **无** | **非缺口**:上游也 0 命中 → 传输数据 |
| `guiNoLighting` | `:176` | `:552` / `:672` | **无** | **真漏接线**:参考分支有真消费者(`perf-previewUI ClientModelManager:112-113,2192` + `RenderUtil:607-614`)。后果:GUI 预览偏亮/偏暗。**但上游唯一消费者是 native,本仓读不到** → 接线等于照参考发明,需 owner 点头 |
| `metadata.links` | `:163` | `:486-487` / `:607` | **无** | **真漏接线**:上游 `ModelInfoScreen:112-113` 会画两条 → 信息页缺链接 |
| `metadata.extraAvatars` | folder 侧**根本不解析**(0 命中) | `:566` / `:694` | 无 | 上游 0 命中 → 参考独有,记录 |
| `RawImage.isPng` | `YSMFolderDeserializer.java:1102` 只写 | — | **无读点**;`Deserializer:689` 对头像硬编码 `false` | 死字段 |
| `all_cutout` / `ysm_all_cutout` | `:174` | `RawYsmModelAdapter:463,609-616` | **有**:`IGeoRenderer.java:104-106` → `MaterialDrawSession` → `OuterFileTexture.java:69-74` → `MaterialBake.bake(..., forceCulling, ...)` | **已接线**(旧审计的"carried, no consumer"已过期)。注意 `RawYsmModelAdapter.java:614-615` 的 javadoc 仍写 "Nothing consumes it yet" —— **文档假话** |
| `gui_background` / `gui_foreground` | `:178-179,202-203,240-251` | `:556-557,586-587` / `:677-678,701-710` | **有**:`backgroundImages` → `ModelData#getGuiImages` → `ModelButton.java:147-167` | **已接线**(旧审计过期)。残留:legacy 通道拿不到图(`EncryptTools` 缓存载荷只带三张表) |

### 2.2 组件在、但没有消费者

| 项 | 位置 | 消费者 | 判定 |
| --- | --- | --- | --- |
| **`AssetManager` 整个空闲驱逐子系统** | `client/asset/AssetManager.java`(注册/`tick`/`geo`/`anim` 全部接好) | **有一层总开关 `Config.ENABLE_IDLE_ASSET_EVICTION`,默认 OFF**,且 `AssetManager.isEnabled()` 为假时**什么都不做** | **"移植了但默认不生效"**。`Config.java:440-441` 自述"DEFAULT OFF AND NOT YET VALIDATED … enabling it can evict geometry the port cannot rebuild"。→ **能力在,开关关着,且开着可能坏**。这是本类里最大的一项 |
| `AssetManager.release(ResourceLocation)` | `AssetManager.java:243`(定义),`:236-240` 注释自认 | **生产零调用者**(只有两个测试) | 参考由 `sweepInUseModels`/`IN_USE_MODELS` 驱动,本仓没有那张表 → 接缝不存在 |
| `UploadManager` | `client/upload/UploadManager.java:15` | 唯一调用是 `CompleteFeedback:32`,而 packet id 12 **无 sender** | 死占位,**不要为它造消费者** |
| `ModDependencyRegistry.getUnmetModIds` | `:88` | 0 调用 | 开发者可见:缺一条日志 |
| `hasControllerSneakHandling` | `OpenYsmAnimationControllerRegistry.java:414` | 0 调用 | 无害:已被 `predicateMain:350-354` 的 `tryApply` 粗化取代 |
| 36 个 0 调用点方法 | 逐条见 `.agent/audit-unwired-inventory.md` §2.3 | 无 | 多数无害/开发者可见(例:`MolangDebugSnapshot.printToChat:336`、`CustomPlayerEntity` preview 三件套 `:452-460`) |
| 6 个对外宿主 API | `api/**` 5 个 + `EntityModelRenderApi.isModelLoaded:33` | 本仓无调用者 | **设计如此**(给第三方用),不算缺陷 |
| `HudPreviewCache` / `PreviewPoseCache` | 都在,都被调用 | **但本仓没有任何测试**(参考有 `HudPreviewSoleDriverBoundTest.java:30`) | 真实覆盖洞(R4) |

### 2.3 注册了但没有真实取值来源的 molang 名字

完整 105 条见 `.agent/audit-pack-facing-surface.md` §3。按"能不能补"分三类:

| 类 | 数量 | 例子 | 说明 |
| --- | --- | --- | --- |
| **1.7.10 有取值来源,只是没接线** | **约 23** | `ysm.texture_name`、`weather`、`dimension_name`、`air_supply`、`block_light`、`sky_light`、`eye_in_water`、`is_open_air`、`ladder_facing`、`attack_time`、`hit_target_id/type`、`attack_damage`、`movement_speed`、`knockback_resistance`、`xxa`、`yya`、`zza`、`input_horizontal`、`ground_speed2`、`time_delta`、`rendering_in_inventory`、`rendering_in_paperdoll` | **这批才是真正的"移植了没接线"** —— 源都在实体/世界上,只是 `AnimationRegister` 没发布 |
| 1.7.10 确实无源 | 约 20 | `attack_speed`、`attack_knockback`、`luck`、`block_reach`、`entity_reach`、`swim_speed`、`frozen_ticks`、`arrow_count`、`stinger_count`、`is_maid`、4 个 shoulder_parrot | 平台约束 |
| 未安装模组的占位名 | 40 | 24 个 `ctrl.*`(tac/im/carryon/swem…) + 16 个 `tlm.*` | **上游在未安装时也注册**;是否补占位见 §1.5 |
| **函数名缺失**(后果最重) | 7 | `query.rotation_to_camera`、`query.biome_has_all_tags`、`query.biome_has_any_tag`、`query.relative_block_has_all_tags`、`ysm.defer`、`ysm.has_any_curios_with_all_tags`、`..._with_any_tag` | **变量名缺失是静默 0;函数名缺失会让整条关键帧表达式解析失败,整个动画通道被丢弃**(`MathBuilder.createFunction` 抛异常)。前四条 1.7.10 无数据驱动标签源;`ysm.defer` 有基础设施 |
| 两边一致地缺 | 1 | `ctrl.riptide` | 1.7.10 无三叉戟(Et Futurum 也无),`AnimationRegister.java:119-124` 已记录 |

**`ysm.bone_color` / `bone_glow` / `bone_transparency` 是特例**:实现都在,但本仓注册的是**裸名**(`MolangParser.java:135-137`),上游挂在 `ysm.` 下(`YSMBinding.java:83-85`)。包写 `ysm.bone_color(...)` 会**函数找不到 → 整条表达式失败**。工作区 88 个包 0 处使用,所以是潜伏缺口。

### 2.4 开关:从"24 个死开关"降到 1 个

| 项 | 状态 |
| --- | --- |
| `Config` 字段总数 | **64** 个 `public static`(含 4 个 `HUD_FOLLOW_*` final 常量)→ **60 个开关** |
| 有行为消费者 | **59** |
| 零消费者 | **1**:`SWAP_CONFIG_SIDES`(`Config.java:38`,同步在 `:372`;GUI 复选框**已撤**,`ConfigScreen:46-47,126-133`) |
| 那一个为什么不算漏接 | 接缝不存在(本仓轮盘是纯扇形,没有参考的"配置面板 + 3D 预览"两块) |
| 旧审计的"24 个" | 已过期。`ENABLE_SYNC_PROTOCOL` / `DEBUG_MERGED_ANIMATIONS` / `TEXTURE_RELEASE_BYTES_ON_IDLE` / `ANIMATION_TRANSITION_TICKS` 是**删字段**,不是接线 |
| 顺带确认 | `TEXTURE_TARGET_SIZE` → `OuterFileTexture.java:306`;`TEXTURE_VRAM_BUDGET_MB` → `ModelTextureRegistry.java:230,285,336`;`DirectBufferWatchdog` 已接(`ClientEventHandler.java:171`);`[YSMU-MODEL]` 日志面从 1 处涨到 36 处 |

### 2.5 半接线 / 单向协议

| # | 项 | 证据 | 后果 |
| --- | --- | --- | --- |
| **1** | **跨端危险组合:服务端 `ENABLE_OPEN_YSM_SYNC_PROTOCOL=ON` + 客户端 `=OFF`** | 服务端 `SyncModelFiles.java:133-138` 按服务端值把 OpenYSM 索引里的模型**从 legacy 通道扣留**;客户端 `S2CVersionCheck17.java:42` 在开关为假时**不回握手** → OpenYSM 通道也没建立 | **该模型两条通道都收不到**。默认是 `true`(`Config.java:95`),所以只有人为关掉一端才触发,但这是静默不可用 |
| **2** | 作者的**结构化元数据**只在 OpenYSM 通道注册 | `acceptStructuredAuthors` 唯一调用是 `OpenYsmModelSyncClient.java:490`;legacy 通道只拿扁平名字(`ClientModelMetadataRegistry.java:98-103`) | legacy-only 模型(含内置)信息页无头像/角色/联系方式 |
| **3** | `preview_animation` / `disable_preview_rotation` 只在 OpenYSM 通道注册 | `ModelPreviewRegistry.accept` 唯一调用是 `OpenYsmModelSyncClient.java:557-560`(虽然 `YSMBinarySerializer:540,548` 会过线) | legacy-only 模型(含内置)的格子**固定 idle + 照常旋转**。默认协议为 true,所以正常配置下不触发 |
| **4** | packet id 10 `RequestServerModelInfo` | 未注册 + 无 sender(`NetworkHandler:296-299` 文档化占位) | 接缝不存在,不要造 |
| **5** | packet id 12 `CompleteFeedback` | 已注册但**无 sender**,只调空占位 `UploadManager` | 同上 |

### 2.6 本次审计新发现的三条(旧清单里没有)

#### 2.6.1 两张 molang 实现:新 VM 树是**死代码**,却看起来像覆盖了

本仓 vendored 了上游的新 VM 树 `software/bernie/geckolib3/molang/**`(含 `builtin/QueryBinding.java` 317 行)。实测:

- 该树在**生产代码里没有任何调用点**:包外唯一 import 是 `AnimationRegister.java:45` 的 `MolangUtils`/`PersonView` 工具类;`MolangEngine.fromCustomBinding` 只在 `molang/MolangParser.java:24` 自用。
- 它注册的 `query.cape_flap_amount`、`query.delta_time`、`query.biome_has_*`、`query.relative_block_has_all_tags`、`query.rotation_to_camera`、`query.all/any_animations_finished` **一个都不对包生效**。

**后果**:**grep 到名字 ≠ 名字是活的**。这正是"只靠 grep 判定缺失"要防的反向误判 —— 一个未来的审计者(或 agent)会因为在这个文件里看到 `query.rotation_to_camera` 而判定"已实现"。同样,`molang/builtin/QueryBinding.java:93,95` 把 `modified_distance_moved` 与 `walk_distance` **写反了**;现在无后果,但**一旦把新 VM 树接活,它会立刻变成真缺陷**。
**建议**:要么在新树入口加一条"本树未接线"的说明,要么明确它是死代码并记录。

#### 2.6.2 `ysm.first_person_mod_hide` 被注释掉,而真实包在用

`AnimationRegister.java:445` 整行是注释:`// parser.register(new LazyVariable("ysm.first_person_mod_hide", MolangUtils.FALSE));`
工作区 `tmp/可露凯-速度之星v1.1/animations/main.animation.json` 使用 `ysm.first_person_mod_hide?2`。
→ 静默读 0,第一人称隐藏条件恒假。**这是"少一行接线"最干净的样本。**

#### 2.6.3 结构性:42 个名字活在全局静态表里,帧外会读到别的实体

| 环节 | 证据 |
| --- | --- |
| 存储 | `MolangParser.java:53` `public static final Map<String, LazyVariable> VARIABLES` |
| 写入 | `EntityQueryBindings.apply(parser, entity, partialTicks)`(`:97`)里 61 行 `parser.setValue(name, () -> ... entity ...)` —— supplier **闭包住写入那一刻的实体** |
| 写者只有三处 | `AnimationRegister.java:496`(玩家)、`VehicleRenderer.java:487`(载具)、`ArrowProjectileRenderer.java:308`(箭) |
| 读取 | 控制器路径的兜底 `OpenYsmControllerExpressionEvaluator.java:1507-1510`(`query`)/`:1633-1636`(`ysm`)直接读这张全局表 |
| 命中范围 | **42 个名字**(23 `query.*` + 19 `ysm.*`);其中 30 余个是**实体状态**(`distance_from_camera`、`head_*`、`body_*`、`eye_target_*`、`has_cape`、`has_rider`、`equipment_count`、`ysm.has_*`、`ysm.head_*`、`ysm.input_vertical`…) |

**三个具体错误读取点**:① `/ysm query <name>` 与 debug overlay 走 `MolangDebugSnapshot.java:151-155` 的裸 `VARIABLES.get`,而 overlay 在帧末绘制 → 读到本帧最后画的那个可绑定实体(可能是载具/箭);② `@sync` 脚本(`S2CMolangSync.java:82` → `PackUserFunctions.fireHook`)不带实体作用域,且不在渲染帧内;③ `evaluateCtrlState:884` 同源。**依据**:上游 `geckolib3/core/molang/context/IContext.java:23-24` 的 `TEntity entity()` 就是"读取永远带 context 实体"的声明。

**边界(必须写清)**:**渲染帧内是安全的** —— `CustomPlayerModel.java:208 setParserValue` → `:210 super.setLivingAnimations` 保证"先 apply 后 process";载具/箭同理;`@player_init`/`@player_update` 也在 `setParserValue` 之内触发。所以问题**不是**"包在动画里读到错的实体",而是帧外入口。
**本仓已有正确对照物**:`MolangFrameContext` 是 ThreadLocal 且类注释(`:38-41`)明文要求"帧外不能读上一个实体的值",并有 `clear()`(`:149-155`)。**这 61 个名字没有这个性质。**

---

## 3. 建议的处置顺序

按"证据强度 × 影响面"排,不是按工作量:

| 批次 | 内容 | 为什么排这里 |
| --- | --- | --- |
| **第 1 批**(各一行~几行,证据最硬) | `query.time_of_day` 控制器路径归一化;`query.is_jumping` 删 `motionYState` 门 + 统一鞘翅口径;`ysm.first_person_mod_hide` 恢复注册;7 个函数名缺失里 `ysm.defer` 与 `ysm.bone_*` 的 `ysm.` 前缀 | 每一条都有**完整因果链**,且都是"包写得到、本仓答错"的静默错值 |
| **第 2 批**(23 个有源的 `ysm.*`/`query.*`) | 把有取值来源的名字补上发布点 | 一次消掉一批"静默 0";源都在实体/世界上 |
| **第 3 批**(结构性) | 全局 `VARIABLES` 的帧外泄漏:给帧外入口(§2.6.3 的三处)接上 `MolangFrameContext` 或等价作用域 | 一次消掉一整类缺陷,且**本仓已有现成设计** |
| **第 4 批**(接线) | `ModelIndexCache`;`metadata.links` 转发;轮盘移动锁定(上游+参考都有);投射物/载具禁用开关 | 性能 + 明确的功能缺口 |
| **第 5 批**(owner 裁定) | 40 个未安装模组占位名;宿主事件面;`guiNoLighting`;授权模型 | 都需要产品决定,不是技术判断 |
| **第 6 批**(文档假话) | `RawYsmModelAdapter.java:614-615`("Nothing consumes it yet");`AnimationRouletteScreen.java:60-63`("porting would mean inventing");`ModelSelectionTarget.java:33-41` + `RevokeModelGuiGrant.java:22-25`(声称 revoke 未接线,实际 `PlayerModelScreen.java:512-516` **已经在发**);`ModelConfigScreen.java:580`+`C2SSubmitRouletteExpression.java:99` 的**表单程序双执行**(见下) | 代码说假话会让下一个审计者重做已做的工作 |

**一条需要实机定性的语义问题**:`ModelConfigScreen.java:580` 本地提交一次表单程序,同时 `:588` 发 C2S;服务端 `C2SSubmitRouletteExpression.java:99` 用 `sendToTrackingPlayers`(→ `NetworkHandler.java:442-444` 的 `sendToAllAround`,1.7.10 **不排除发送者**)广播回来,客户端 `S2CExecuteRouletteExpression.java:67` 再提交一次。`ModelFormExecution.PENDING`(`:28-43`)**没有 source 去重** → 带副作用的程序(`ysm.play_sound`、粒子)可能执行两次。**赋值型程序不受影响**,且现有 854 个测试没有覆盖这条路径。定性只需实机计数。

---

## 4. 覆盖边界(不要把这些当已结论)

1. **全部结论是源码级**。工作区已编译并跑过 854 测试(§0.1),但**没有任何一条经实机验证**:材质 shader 切线与非实心面、PBR 宿主切线、音效的身份/替换/所有权、HUD 与第一人称臂画面一致性、轮盘移动锁的手感。
2. **§2.6.3 的因果链完整,"帧末最后 apply 的实体确实是载具"这一步没有实机日志** —— 取决于实际绘制顺序。最小实验写在 `.agent/audit-pack-facing-surface.md` §12。
3. **"未定位"≠"不存在"**。标"线索级"的有:`format/vfs`、`GeoLocator`/`IRenderCycle`、GUI 失败条目、GUI 键盘焦点、鹦鹉肩上层、`LadderFacingVariable`。这些只做了类名 grep(各 0)+ 打开最近候选文件,没有正面不存在的证据。
4. **105 个缺名里"1.7.10 有源"的判定**基于"该值在本仓别处已能取到",没有逐条写出实现。
5. **数值保真未比对**:粒子行为表(`ParticleBehaviors.java:93-141`)、物理一/二阶、材质 alpha 阈值(native `k241=6/k250=17/k254=29`)只做结构对照。`math.*` 名字面 33/33 全在,数值口径分歧(atan 弧度/角度等)已在 `OpenYsmControllerExpressionEvaluator.mathValue:1247-1345` 逐条注释对齐上游,本报告未复算。
6. **协议未逐包核对** opcode/限额/重放矩阵;上游 `ProtocolLimits` 未与本仓逐条对比。
7. **可选模组**:只判定了"本仓有没有名字级/行为级适配 + 该模组有没有 1.7.10 版",**没有**逐个确认某个 1.7.10 模组是否真的提供上游 compat 依赖的 API。
8. **本报告的数字属于当前工作区**。`AnimationRegister`、`EntityQueryBindings`、`OpenYsmControllerExpressionEvaluator` 都在 88 个未提交改动之内;这些文件再被编辑,需要重跑证据文件里的脚本(全部只读、可复算)。§2.6.2 与 §2.6.3 的原始推导过程在 `.agent/audit-pack-facing-surface.md`(§5.3 与 §3)。
9. **本轮审计新增了 4 个 `.agent/audit-*.md` 证据文件与 1 个仓库根 `.md`(本文件)**;`src/**`、`build/**`、`tmp/**`、既有 `.md` 一字未改。
   - `.agent/` 被 `.gitignore:14` 忽略,所以 4 个证据文件不出现在 `git status` 里;本文件是工作区**唯一新增的未跟踪文件**,`git status --porcelain` 因此从 **88 项变为 89 项** —— 多的那一项就是本报告,`src/` 下的 88 项一字未动。

---

## 5. 证据文件索引

| 文件 | 内容 | 规模 |
| --- | --- | --- |
| [.agent/audit-upstream-feature-map.md](.agent/audit-upstream-feature-map.md) | G01–G19 逐条复验(26 行)+ 12 个子系统 120 行功能图 + 9 个未覆盖族的判定 + 23 条过期结论 | 398 行 |
| [.agent/audit-unwired-inventory.md](.agent/audit-unwired-inventory.md) | `Config` 64 字段全量判定;死类/死方法 36 条;数据面未转发 4 项;单向协议 5 处;扫描边界与假阳性 | 442 行 |
| [.agent/audit-reference-leftovers.md](.agent/audit-reference-leftovers.md) | 参考 473 文件级差额重算(真缺口 5 个文件);旧清单三态复验;13 项点名功能;测试资产差额 | 400 行 |
| [.agent/audit-pack-facing-surface.md](.agent/audit-pack-facing-surface.md) | 上游 287 个 molang 名全量抽取 → 覆盖 182/缺 105/多 49/同名不同义 8;66 个 schema 键;宿主 API 与协议面;11 个只读脚本可复算 | 1489 行 |

**前序材料(本轮之前,行号基线更早,引用前需回源码)**:`tmp/upstream-gap-audit.md`(G01–G19 原始审计)、`tmp/remaining-upstream-port-inventory.md`(含 Revision 3/4)、`tmp/lapluma-checklist-status.md`、`tmp/dead-switches-audit.md`、`AGENTS.md` 的"Known divergences"。

---

## 6. 实施记录:六批次的落地与验证

§1–§5 是**审计快照**;本节记录按 owner 指令派发并落地的实施结果。**先读验证数据,再读逐批内容。**

### 6.1 验证数据(Lead 实测,不是任何 worker 的自述)

| 项 | 结果 |
| --- | --- |
| `.\gradlew.bat compileJava` | **BUILD SUCCESSFUL**(29s;100 条警告全是 `rip/ysm/zstd` 的既有 `Unsafe` 弃用警告) |
| `.\gradlew.bat test` | **960 tests / 4 failures / 0 errors**;4 个失败**全部**是既有基线(`CtrlItemMatcherTest` 2 + `ItemTagMatcherTest` 2,根因是 `OreDictionary` 在无 Forge 引导的 JUnit JVM 里 `ExceptionInInitializerError`,见 `.agent/matcher-headless-bootstrap-findings.md`)。**实施前基线 = 854/4,本轮新增的 106 个测试全部通过** |
| `.\gradlew.bat build -x test` | **BUILD SUCCESSFUL**;产出 `build/libs/ysmu-5.09.52.417.jar`(7,443,744 B)与 `-dev.jar`、`-sources.jar`、`-dev-preshadow.jar` |
| 工作区规模 | 110 个已跟踪改动 + 27 个新文件(含 4 份审计 + 本报告) |

**编译期抓到的真实缺陷(证明仓库那条"worker 报 done ≠ 验证"仍然值钱)**:`ModelLinksForwardingTest` 的 `writeString` 夹具用了声明 `throws IOException` 的单参 `OutputStream.write(byte[])`(`ByteArrayOutputStream` 只覆写无 throws 的三参重载)→ `compileTestJava` 直接失败。

**运行期抓到的 4 条新测试红**:全部定位到"测试自己错了"而非生产代码,见 §6.3。

### 6.2 逐批次落地

| 批次 | 结果 | 关键落点 |
| --- | --- | --- |
| **1**(静默错值) | **已完成** | `query.time_of_day` 两条路径统一走上游 `(t+6000)/24000%1`(`OpenYsmControllerExpressionEvaluator.java:1494`、`AnimationRegister.java:775`,`TimeOfDayPathAgreementTest`);`query.is_jumping`/`is_flying` 控制器侧改为**委托**关键帧那一份判定(删掉 `motionYState` 门与鞘翅口径分叉,`PlayerMotionPredicatesTest`);`ysm.first_person_mod_hide` 恢复注册并按第一人称手臂帧给真实值(`FirstPersonModHideVariable.java`);`ysm.bone_*` 补 `ysm.` 前缀(裸名保留)、`query.rotation_to_camera`(`Rot2CameraFunction`)、`ysm.defer`(按上游语义注册为 no-op,注释说明上游该 handler 全树无写入者) |
| **2**(有源补名) | **已完成 25 个** | 新 `molang/YsmWorldQueries.java` + `molang/YsmEntityQueries.java`,`AnimationRegister.java:214-273` 注册:weather / dimension_name / block_light / sky_light / is_open_air / eye_in_water / ladder_facing / hit_target_id / hit_target_type / air_supply / attack_time / attack_damage / movement_speed / knockback_resistance / xxa / yya / zza / input_horizontal / ground_speed2 / time_delta / is_player / entity_type / texture_name / rendering_in_inventory / rendering_in_paperdoll。**跳过的**:三个 biome/block tag 函数(1.7.10 无数据驱动标签,如实记录) |
| **3**(全局存储泄漏) | **已完成** | 新增 `molang/EntityQueryScope.java`(61 个名字的帧快照 + 读取期按实体发布)+ `client/event/EntityQueryFrameEvents.java`(`TickEvent.RenderTickEvent` START/END,1.7.10 恰好包住整个 `updateCameraAndRender`);`MolangDebugSnapshot` 五处读取点与 `S2CMolangSync` 的 `@sync` 改为"先发布被问的实体、读完还原"。**帧内时序未动**(`CustomPlayerModel:208→210`);`EntityQueryScopeTest`(11 条)+ `MolangEntityScopeWiringTest`(4 条) |
| **4**(接线) | **已完成** | **C**:新 `model/format/ModelIndexCache.java`(988 行 + 22 条测试)——按上游 `ConvertedSourceIndex` 契约,指纹取**内容哈希**而非 mtime(内置模型每轮被重写,mtime 方案永不命中),原子写 + 回读校验 + 损坏即弃,**未命中/索引不可用时逐字节回落到原路径**。**D**:轮盘移动锁(`WheelAnimationLock` + 键位 + 锁按钮 + lang);`metadata.links` 全链转发(legacy 容器升 v6 `LINKS_MODEL_VERSION`,旧 v5 载荷降级为空);`DISABLE_PROJECTILE_MODEL`/`DISABLE_VEHICLE_MODEL` 按上游真实 bypass 点生效;`S2CSyncIndexChunk17` 判定为**记录残量不分片**(本仓单包上限 2 MiB 远高于参考自报触发规模) |
| **5**(占位名 / 授权 / guiNoLighting / 宿主事件面) | **5a、5b-授权 已完成;guiNoLighting 走"记录";宿主事件面 = 决策简报** | **5a**:40 个未安装模组占位名集中注册(`AnimationRegister.java:318-369`),逐名取上游类型与默认值(含 `tlm.gomoku_rank=1`、`tlm.is_entity=true`)。**授权**:新 `eep/ExtendedAuthModels.java` + `api/ModelAuthorization.java` + `/ysm auth` 命令族 + `SetModelAndTexture` 选择点校验 + 撤销重置;默认放行**只用一个机制**(`Config.ENABLE_MODEL_AUTHORIZATION` 默认 false),理由:"空表即放行"会让 `clear` 变成**放行**(安全反转)。`event/CommonEventHandler` 的 EEP 注册/克隆由 Lead 应用(否则 grant 不落盘)。**guiNoLighting**:native 侧唯一提及是**注释掉的** `render_impl.inc:97-101`,且 guard 的常量根本不是该模块模板参数 → **无活读取点,不发明**,写注释 + 决策简报。**宿主事件面**:8 项逐条给触发时机/对应物/风险,**建议不移植**(其中 `RegisterRenderStateModifierEvent` 上游**自己也从未 post 过**) |
| **6**(文档假话 + 协议) | **已完成** | `RawYsmModelAdapter` 的 "Nothing consumes it yet"、`AnimationRouletteScreen:60-63` 的 "porting would mean inventing"(被上游反证)、`ModelSelectionTarget`/`RevokeModelGuiGrant`/`NetworkHandler` 三处"revoke 未接线"(实际早已接线)全部改为事实;**表单程序双执行**:新增 `NetworkHandler.sendToTrackingPlayersExcept` + `TrackingRecipients`(纯函数),回显排除发送者 → 发送者恰好执行一次 |

### 6.3 本轮三个"测试自己错了"的案例(值得记住)

1. **`query.time_of_day` 的断言算错**:测试写"下一个午夜回绕到 0",但 `(24000+6000)/24000 % 1 = 0.25` —— `% 1` 只在 `t ≡ 18000 (mod 24000)` 落回 0。**实现逐字等于上游,错的是断言**。§1.1 #1 已附更正。
2. **`ExtendedAuthModelsTest` 的夹具前提不成立**:1.7.10 的 `NBTTagList.appendTag` 只记**第一个** tag 的类型,类型不符的后续 tag 被**丢弃**(打 `Adding mismatching tag types to tag list`),所以"compound 后面跟 string"的混合 list **经公共 API 根本构造不出来**;而 `getStringTagAt` 对非字符串项是**退回 `toString()` 而非抛异常**。夹具改为直接写私有 `tagList`/`tagType` 才真正覆盖该分支(期望 2 条)。生产代码的防御读取没问题,只是它原来的 javadoc 说错了话,已一并修正。
3. **`ModelIndexCacheTest` 的失败夹具在 Windows 上不成立**:原夹具在 index 路径建**目录**期望原子替换失败,但 Windows 上 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` **乐于用文件替换目录**,于是 `save()` 合法地返回 true。改为把已存在的索引设为**只读**,三个平台上都构成真实写失败,并顺带断言"原索引内容逐字节不变"。**生产代码 `save()` 的失败路径是对的**(catch → warn → false)。

### 6.4 仍然只能实机确认(本轮一条都没验)

锁按钮排版/键位冲突、两个开关确实走原版渲染、信息页链接的打开与剪贴板回退、双人服务器下表单程序的执行次数、`@EventBusSubscriber` 是否真被 GTNHLib 收走(单测看不到)、材质/PBR shader 一致性、音效身份与所有权、大库上索引侧车的启动收益、`Tags.VERSION` 是否每次重建 jar 都变、Windows 真 `config` 路径上的 `ATOMIC_MOVE`。

### 6.5 留给 owner 的决策项(本轮**没有**动)

1. **宿主事件面**(`api/rendering/v0/**`、`api/model/v0/**`、注解处理器、`MolangNames`):简报建议**不移植**,唯一候选是"宿主可取消的渲染事件"。新增对外 API = 长期维护面。
2. **`guiNoLighting`**:保持"解析/过线 + 注释记录",不接线(理由见 §6.2)。
3. **无调用者的旧 `software/bernie/geckolib3/util/MolangUtils.normalizeTime`**(`t/24000`,与上游公式不同):属 vendored 引擎树,删它要按 `PROVENANCE.md` 记偏差,本轮**先不删**。
4. **`query.rotation_to_camera` 只在关键帧函数表**:控制器 `functionValue` 那条分支没加(审计未列该分歧);要保持两路一致请指派。
5. **§1.2 里本轮未纳入的项**:现行容器 `.mxc` 准入、模型级 `icon`/`thumbnail`、Opus 音频、TLM 兼容面、非箭投射物、失败目录条目、卡片 tooltip 内容。
6. **两处近似**:`ysm.yya` 的映射、模组维度 `dimension_name` 返回 `''` —— 都无唯一上游对应物,已在 Track A 简报标明。

---

*本报告由 4 名并行审计员 + 5 名实施者 + 主代理交叉核实产出。审计:`upstream-mapper`(上游功能面)、`unwired-auditor`(未接线扫描)、`reference-differ`(参考分支差额)、`surface-differ`(名字面)。实施:`track-a-names`(批次1/2/5a)、`track-b-framescope`(批次3)、`track-c-index`(批次4-C)、`track-d-ui`(批次4-D/6)、`track-e-auth`(批次5b)。§6.1 的构建/测试数据、§1.1 #1/#2/#3 的代码核对、`CommonEventHandler` 的 EEP 注册、D1 的三行接线、以及 §6.3 三条夹具诊断由主代理独立完成。*
