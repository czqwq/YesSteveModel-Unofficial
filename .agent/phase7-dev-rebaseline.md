# Phase 7 在 dev 基线上收口：把 phase6 的清单重新归仓后逐条修完（YSMU 侧 + Geckolib 引擎侧）

本 ExecPlan 是一份活文档。`Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 四个章节必须在实施过程中持续更新。它遵循仓库根目录的 `.agent/PLANS.md`（`E:\IDEA\YesSteveModel-Unofficial\.agent\PLANS.md`）：格式（单文件、标题层级、两空行、只在 `Progress` 用复选框、其余章节散文优先）与内容要求都以该文件为准。

## Purpose / Big Picture

做完这一阶段的工作后，玩家能直接看到三类变化，且每一项都能在 `runClient` 里复现。

第一，**崩溃类问题消失**：在轮盘里选中一个动画不再让客户端崩溃（默认配置下必崩）；在主菜单或已断开时按 `Alt+Y` 不再崩溃；聊天框里输入 `z` 不再把聊天界面顶掉；把模型设成"没有贴图"后渲染时不再 `bindTexture(null)` 崩客户端。

第二，**模型格式容忍与注册面收口**：声明 `1.8.0`/`1.10.0`/`1.12.0` 的第三方几何文档都能被解析并进入几何构建（今天 `1.8.0`/`1.10.0` 会在引擎侧整份解析失败，`1.14.0` 会被宿主闸门静默跳过）；带未建模字段（Blockbench 的工具版本戳、`description.sound_effects` 等）的文档不再整份被拒；"哪些版本可构建"这条规则从"两个仓库各存一份"合并成一份，改一处不会漏另一处；引擎源码树与 `libs/geckolib-*.jar` 对齐后，引擎侧的两处 NPE guard（缺 `uv` 的 cube、缺 `minecraft:geometry` 数组的旧布局）才真正落地。

第三，**同步与缓存链不再"看起来成功其实是坏的"**：密码包与加载请求重新有顺序保证；两套同步通道不再把同一批模型完整下载/解析/注册两遍；分块传输的半成品不再被永久当成缓存命中；后台线程池真的有多个 worker（配置里写着 4，实际永远 1）；缓存在 reload 时会清理并复用；一个 0 字节或损坏的 `.ysm` 不再打断服务器启动。

**怎么看到它工作**：交付物是"用户按 `.agent/phase7-dev-rebaseline.md` 的 `Concrete Steps` 跑一次 `.\gradlew.bat build` 与 `runClient`，逐条核对 `Validation and Acceptance` 里的 12 个场景"。沙箱内不能跑 Gradle（新 `AGENTS.md`：构建只在全部改动结束后的最终确认时跑，且构建期间独占工作树），所以阶段内所有修复任务只提交**静态证据**，编译门槛集中在收尾一次 `.\gradlew.bat compileJava`。

**与 phase6 的关键差别**（读者必须知道，否则会把 phase6 的结论套错）：本阶段的基线是 **`dev` 分支**，不是 `master`。`dev` 只有 184 个 java（含 `src/test`），**不内嵌 GeckoLib**：引擎来自 `libs/geckolib-5.09.52.417-dev.jar`（独立 mod，`required-after:geckolib`），宿主已经实现了引擎要的契约（`CustomPlayerEntity implements IMolangPhysicsScope`、`ClientEventHandler` 往引擎的 `RemoteAnimationVariables` 推远端变量），并新增了 `com/fox/ysmu/api/` 三个类。因此 phase6 里所有"改内嵌引擎源码"的条目在这里要么作废（引擎已经不在树里），要么变成"改引擎仓库"。

## Progress

本清单是本阶段全部【本次修复】条目（= `tmp/audit-dev/delta.md` 的**本仓条目 92 条**（91 条来自 §1/§6，另加 §4 里的宿主侧 `MON-01`）+【已转移到 Geckolib 引擎侧】**12 条**（`E-01`..`E-12`：其中 `E-05` 是本轮唯一的引擎侧必修，`E-11` 已完成并勾选，`E-02`/`E-04`/`E-01`/`E-06`/`E-12` 为"已核实/仅记录/后续项"，`E-03` 为追溯记录），合计 104 行，另有收尾的 `[verify]`/`[user]` 两行），每条以发现 id 开头并标注归属任务。尚未开始的都是 `- [ ]`；完成后改 `- [x]` 并在行尾补 UTC 时间戳（形如 `(2026-09-29 20:10Z)`）。计数口径见 `delta.md` §7.2。

- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-01` 轮盘消息 `%d` → `%s` + 字符串参数（blocker）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-02` `/ysm reload` 的 `%.2f` → `%s` + `String.format`
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-03` `thePlayer == null` 时不再打开模型界面（blocker）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-04` 五个热键 handler 先消费 `isPressed()` 再判界面
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-05` `StarButton`/`TextureCountButton` 接收 `target`
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-07` 模型变更广播半径 64 → 512
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-09` 配置与 GUI 拖拽的越界钳制
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-10` 客户端断开清理改挂可触发事件，并清 6 项（含 `A-10`/`NF-02`/`D-A1`）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-11` `EXTRA_PLAYER` 加 `try/finally`
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-12` 客户端 `dirty` 与悬空 `player`
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-13` 三处 `GL_SCISSOR_TEST` 异常安全
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-14` 免责声明"关闭"语义补类注释
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-15` 贴图列表排序前拷贝 + 默认贴图走 `Config`
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-16` GUI 每帧分配（只做缓存缩放因子/已格式化文本）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-17` 超长模型名省略号
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-18` 空模型列表空态文本（两份 lang 同步补键）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-19` `ModelInfoButton` + `RequestServerModelInfo` 死代码处置（保留 id）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CU-20` 记录级说明（`ThreadCount` 变真配置后同步注释）
- [x] (2026-09-29 20:05Z) `[core-ui]` `CUI-02` `/ysm reload` 广播全部维度
- [x] (2026-09-29 20:05Z) `[core-ui]` `NF-01` 模型选择授权在关闭界面时撤销
- [x] (2026-09-29 20:05Z) `[model]` `CU-06` = `M-06` 线程池接入 `Config.THREAD_COUNT`（承接头为 `M-06`）
- [x] (2026-09-29 20:05Z) `[model]` `M-01` `writeOpenYsm` 门控（不可桥接则跳过并 warn）
- [x] (2026-09-29 20:05Z) `[model]` `M-02` 动画关键帧/timeline/音效/blend_weight 解析
- [x] (2026-09-29 20:05Z) `[model]` `M-03` pivot/rotation 内部约定换算（与 `M-01` 同批）
- [x] (2026-09-29 20:05Z) `[model]` `M-04` blacklist 生效 + 不再覆盖用户 `custom`
- [x] (2026-09-29 20:05Z) `[model]` `M-05` `BUILT` 死目录处置
- [x] (2026-09-29 20:05Z) `[model]` `M-06` 线程池并发度 + 池内 sleep 迁移
- [x] (2026-09-29 20:05Z) `[model]` `M-07` `Md5Utils` 去掉静态共享 `MessageDigest`
- [x] (2026-09-29 20:05Z) `[model]` `M-08` `DeflateUtil` 忙等 + `EncryptTools` 头 MD5 失败即中止
- [x] (2026-09-29 20:05Z) `[model]` `M-09` 缓存清理与复用
- [x] (2026-09-29 20:05Z) `[model]` `M-10` 缓存命中元数据（内容 MD5 校验口径）
- [x] (2026-09-29 20:05Z) `[model]` `M-11` `YsmZstd.decompress` 就地改写入参
- [x] (2026-09-29 20:05Z) `[model]` `M-12` 三个扫描器递归性统一
- [x] (2026-09-29 20:05Z) `[model]` `M-13` 手拼 id 旁路改走 helper
- [x] (2026-09-29 20:05Z) `[model]` `M-14` `getExtraAnimationNames` 槽位约定
- [x] (2026-09-29 20:05Z) `[model]` `M-15` `merge_multiline_expr` 默认值（非目标，仅记录）
- [x] (2026-09-29 20:05Z) `[model]` `M-16` 加载失败统一进日志
- [x] (2026-09-29 20:05Z) `[model]` `M-17` `hold_on_last_frame` → 3
- [x] (2026-09-29 20:05Z) `[model]` `M-18` 畸形 `.ysm` 长度守卫 + 逐条 catch + `reloadPacks` 兜底
- [x] (2026-09-29 20:05Z) `[model]` `M-19` 加密失败抛异常 + 登记侧完整性校验
- [x] (2026-09-29 20:05Z) `[model]` `M-20` 解析默认值偏差（非目标，仅记录）
- [x] (2026-09-29 20:05Z) `[model]` `M-21` 混合目录只登记一次
- [x] (2026-09-29 20:05Z) `[model]` `M-22` `soundFiles`/`functionFiles` 消费者（跨仓，见 `[engine]`）
- [x] (2026-09-29 20:05Z) `[model]` `M-23` 删除 `RAW_MODEL_INFO` + 不再就地改共享对象
- [x] (2026-09-29 20:05Z) `[model]` `M-24` format > 32 的宽容处理与明确 warn
- [x] (2026-09-29 20:05Z) `[model]` `S-01` 服务端模型索引并发安全（含 network 侧 `S-01`）
- [x] (2026-09-29 20:05Z) `[model]` `S-02` zstd 静态初始化失败的兜底
- [x] (2026-09-29 20:05Z) `[model]` `S-03` `properties.sha256` 字段名与内容一致性
- [x] (2026-09-29 20:05Z) `[model]` `S-04` 缓存名 seed（记录级）
- [x] (2026-09-29 20:05Z) `[model]` `S-05` `RenderUtil.renderModel` 状态保存/清空/恢复进 `try`
- [x] (2026-09-29 20:05Z) `[model]` `D-01` `SyncModelFiles` count 上限与 null md5 过滤
- [x] (2026-09-29 20:05Z) `[model]` `D-02` `hasModel` 与不安全磁盘名规范化
- [x] (2026-09-29 20:05Z) `[model]` `D-03` 新 API 的默认模型 id 走 `Config`
- [x] (2026-09-29 20:05Z) `[core-ui]` `[network]` `N-01` 密码包与 `RequestLoadModel` 同线程保序
- [x] (2026-09-29 20:05Z) `[network]` `N-02` 双通道去重（含 `M-01` 门控后的语义）
- [x] (2026-09-29 20:05Z) `[network]` `N-03` `SyncModelInfo` 应用 NBT 回客户端线程
- [x] (2026-09-29 20:05Z) `[network]` `N-04` 收包线程磁盘 IO 移到池
- [x] (2026-09-29 20:05Z) `[network]` `N-05` C2S payload 预算 ≤ 32767（或分片）
- [x] (2026-09-29 20:05Z) `[network]` `N-06` int 前缀上限校验
- [x] (2026-09-29 20:05Z) `[network]` `N-07` 分块内容校验 + TTL + 上报侧过滤
- [x] (2026-09-29 20:05Z) `[network]` `N-08` legacy 下发背压/限速
- [x] (2026-09-29 20:05Z) `[network]` `N-09` `CompleteFeedback`/`RequestServerModelInfo` 两个死包处置
- [x] (2026-09-29 20:05Z) `[network]` `N-10` 畸形载荷只忽略不断连（覆盖 `fromBytes`）
- [x] (2026-09-29 20:05Z) `[network]` `N-11` 17 协议 session 标识（+ `D-02(net)` 的 bump 规则）
- [x] (2026-09-29 20:05Z) `[network]` `N-12` 不再占住 worker 空等
- [x] (2026-09-29 20:05Z) `[network]` `CUI-01` `sendSyncModelMessage` 的调用线程与无超时等待
- [x] (2026-09-29 20:05Z) `[network]` `D-03(net)` `setEntityModel` 返回值与失败反馈
- [x] (2026-09-29 20:05Z) `[network]` `D-04(net)` `SetNpcModelAndTexture` 的畸形值校验
- [x] (2026-09-29 20:05Z) `[render]` `R-01` GL 纹理释放
- [x] (2026-09-29 20:05Z) `[render]` `R-02` `UploadManager` 占位处置（非目标，仅记录）
- [x] (2026-09-29 20:05Z) `[render]` `R-03` Angelica 反射缺失时的一致降级
- [x] (2026-09-29 20:05Z) `[render]` `R-04` 矩阵 push/pop 进 `finally` + 纹理绑定契约
- [x] (2026-09-29 20:05Z) `[render]` `R-05` 无覆盖实体不沿用上一实体
- [x] (2026-09-29 20:05Z) `[render]` `R-07` 贴图尺寸/字节上限
- [x] (2026-09-29 20:05Z) `[render]` `R-09` 首人称重复解析与每帧对象
- [x] (2026-09-29 20:05Z) `[render]` `R-10` `getCurrentModel()` 判空（相机高度接线为非目标）
- [x] (2026-09-29 20:05Z) `[render]` `N-1` 空 `selectTexture` 不得进 EEP（崩溃级）
- [x] (2026-09-29 20:05Z) `[render]` `N-4` `geoModel.properties` 判空
- [x] (2026-09-29 20:05Z) `[render]` `N-6` `EntityModelData`/`NPCData.put` 分量加固
- [x] (2026-09-29 20:05Z) `[render]` `N-7` `resolveOverride` 每帧分配
- [x] (2026-09-29 20:05Z) `[render]` `N-5` `renderPartialTicks` 来源（记录）
- [x] (2026-09-29 20:05Z) `[network]` `[animation]` `A-01` `hold_mainhand:empty`/`hold_offhand:empty` 可达
- [x] (2026-09-29 20:05Z) `[animation]` `A-02` 副手 swing 分类与 `swing_offhand` 回退
- [x] (2026-09-29 20:05Z) `[animation]` `A-03` `v.*` 委托引擎 `MolangPhysicsRuntime`
- [x] (2026-09-29 20:05Z) `[animation]` `A-04` 去掉 `v.jump` 实时特判（与 `A-03` 同批）
- [x] (2026-09-29 20:05Z) `[animation]` `A-05` `query.position_delta(axis)` 按当前帧实体求值
- [x] (2026-09-29 20:05Z) `[animation]` `A-06①` 未注册函数的加载期诊断
- [x] (2026-09-29 20:05Z) `[animation]` `A-06③` `ctrl.hold` 桩与控制器侧统一
- [x] (2026-09-29 20:05Z) `[animation]` `A-07` 条件名告警 + `InnerClassify` 与求值器统一
- [x] (2026-09-29 20:05Z) `[animation]` `A-11` 逐玩家进度表清理入口（`clearPlayerState`）
- [x] (2026-09-29 20:05Z) `[animation]` `S-03` 遗留 TODO 注释清理
- [x] (2026-09-29 20:05Z) `[animation]` `S-04` 同名控制器覆盖时 warnOnce
- [x] (2026-09-29 20:05Z) `[animation]` `S-05` 注册幂等守卫
- [x] (2026-09-29 20:05Z) `[animation]` `D-A1` `EntityClips` 修剪/过期
- [x] (2026-09-29 20:05Z) `[animation]` `D-A2` 动画 id 解析规则收敛为一份
- [x] (2026-09-29 20:05Z) `[animation]` `D-A3` "物品 → 条件名"共享 helper
- [x] (2026-09-29 20:05Z) `[animation]` `D-A5` `playIfPresent` 改用 `hasAnimation`
- [x] (2026-09-29 20:05Z) `[animation]` `D-A6` API 侧约定写入 javadoc
- [x] (2026-09-29) `[engine]` `E-11` 引擎可构建 + 新 jar 已放入本仓 `libs/`（captain 已执行并验证：引擎 `master` 1154d09 构建成功，jar 与本仓 `libs/` 逐字节相同，无版本漂移；旧 jar 备份在 `tmp/backup-libs/`）
- [x] (2026-09-29 20:05Z) `[engine]` `E-05` `GeoCube` 空 uv / Box-UV 缺失 guard（引擎侧**唯一**确认缺失项）
- [x] (2026-09-29 20:05Z) `[engine]` `MON-01` 版本闸门接受 `1.14.0`（用户裁定四版本全接受；落点 `com/fox/ysmu/client/ClientModelManager.java`）
- [x] (2026-09-29 20:05Z) `[engine]` `E-08` 版本判定单一来源（可选：引擎 `FormatVersion.isBuildableByThisPort()`，宿主删本地表；本轮引擎侧只做 `E-05` 则本项记为后续）
- [x] (2026-09-29 20:05Z) `[engine]` `E-09` 三个安装点（声音/存储/调试）由宿主安装或明确非目标
- [x] (2026-09-29 20:05Z) `[engine]` `E-10` `soundFiles` 交付给引擎声音管理器（`M-22` 的对端）
- [x] (2026-09-29 20:05Z) `[engine]` `E-01` 记录 `FormatVersion` 现状（已核实满足，无需动作）
- [x] (2026-09-29 20:05Z) `[engine]` `E-06` 记录 `A-06②` 已由引擎修复（无需动作）
- [x] (2026-09-29 20:05Z) `[engine]` `E-07` `A-05` 引擎侧是否收口的裁决
- [x] (2026-09-29 20:05Z) `[engine]` `E-12` （后续项，不进本轮）`molang.binding.HostBindingContributor` + `MolangHostBindings.setContributor/contributor()`，仅在将来迁到现代 runtime 时才需要
- [x] (2026-09-29 20:05Z) `[engine]` `E-04` `RawGeometryTree` 旧布局 guard（**已核实引擎侧文件与能力均在**，仅记录；若要加可读 IOException 再单列）
- [x] (2026-09-29 20:05Z) `[engine]` `E-03` （追溯记录）引擎源树/出货 jar 不同步一事属于分支 `clean-job` 的半成品，**已随分支切换解除**，不作为前置条件
- [x] (2026-09-29 20:05Z) `[verify]` 收尾：`.\gradlew.bat compileJava` 一次（由集成/验证任务执行）
- [ ] `[user]` 用户执行 `.\gradlew.bat build` 与 `runClient`，按 12 个场景验收（**尚未执行**）

## Surprises & Discoveries

这些结论会改变实施顺序或范围，全部来自 `tmp/audit-dev/` 的六份核验报告。

- 观察：**phase6 的"已修复"几乎全部没有进入 dev**。三份报告独立得出同一结论：`model.md` 的 29/29 条、`network.md` 的 12/12 条、`core-ui.md` 的 21 条中 20 条都是【dev 仍存在】；dev 的 `model/format/**`、`model/resource/**` 与 master 修复前的行数完全一致（FolderFormat 140、YsmFormat 110、OpenYsmFormat 137、ModelCacheWriter 77、ServerModelInfo 42、YSMFolderDeserializer 1011、RawYsmModelAdapter 747、ServerModelManager 345）。
  证据：`tmp/audit-dev/model.md:8`、`tmp/audit-dev/network.md:32`、`tmp/audit-dev/core-ui.md:7`。**含义**：dev 与 master 是两条独立历史，phase6 的修复分支（t8..t13）不会被 dev 继承，本阶段必须自己修一遍。

- 观察：**`tmp/Geckolib` 不是符号链接、也不是引擎仓库，而且只是一份不完整的拷贝**。`Get-Item` 显示 `ReparsePoint=None`；它在本仓**未被跟踪**（0 文件）且被 `.gitignore:11` 的 `/tmp/` 忽略，所以在它里面跑 git 得到的是**本仓**的状态（`--show-toplevel` = `E:/IDEA/YesSteveModel-Unofficial`、分支 `dev`、HEAD `43cc628`）。它缺 `Converter.java`、`GeoCube.java`、`AnimationProcessor.java`，且 `core/controller/` 是空目录。真引擎仓是 **`E:\IDEA\Geckolib`**（工作区外）。
  证据：`tmp/audit-dev/engine-side.md:10-11,113-117`、`tmp/audit-dev/animation.md:243-249`，以及 captain 的复核。**含义**：phase7 的"引擎侧 inScope"必须写成 `E:\IDEA\Geckolib`；`tmp/Geckolib` 既不能改（改不到 jar）也不能当基线，**不得出现在任何 inScope 里**。

- 观察：**引擎现在可构建，且本仓 `libs/` 的 jar 就是引擎 `master` 的构建产物（无版本漂移）。** captain 亲自核实并构建：`E:\IDEA\Geckolib` 当前分支 **`master`**、HEAD **`1154d090f337c5960a787c21571575681d5594bb`**，`git status` 只有 ` M .gitignore` → 可作固定基线；`.\gradlew.bat build --offline` → `BUILD SUCCESSFUL in 38s`（compileJava/jar/shadowJar/reobfJar 全过），产物 `build/libs/geckolib-5.09.52.417-dev.jar` = 2,339,441 B、SHA256 `142DF706C8B117A63FB97C546D9D34C7EE2CD3A6CE8AB24E018C3AAFCC04AB56`，与本仓 `libs/` 里那份**逐字节相同**。此前 `t27` 报告里"18 个已暂存删除、源树无法自洽编译"的状态属于分支 **`clean-job`** 上的半成品，随分支切换已解除，`Converter`/`GeoCube`/`AnimationProcessor`/`MolangParser` **都已回到工作树**。旧 jar 已备份到 `tmp/backup-libs/`。
  证据：captain 的核实与构建输出。**含义**：用户要求的"从引擎仓构建最新 jar 放进本仓"已完成并验证 → `E-11` 直接勾选，**不再需要"让引擎自洽编译"这个里程碑**；`tmp/audit-dev/engine-side.md` 的 b7/§5 与 `animation.md` 的 D-A4 应以本条为准（那两条是过时前提，保留在报告里仅供追溯）。

- 观察：**引擎侧真正待补的只有一处：`GeoCube` 的裸解引用。** 已核实存在的：`Converter.java:89` 的 `FAIL_ON_UNKNOWN_PROPERTIES=false` ✅（无 BOM 容忍）、`FormatVersion.java:24-27` 四版本齐且注释明确"不重映射到 `VERSION_1_12_0`" ✅、宿主契约 `molang/context/` 14 个文件 + `MolangSoundManagers`/`MolangForeignStorage`/`MolangDebug` + `RemoteAnimationVariables`/`IMolangPhysicsScope` ✅ 齐备。**唯一缺失**：`geo/render/built/GeoCube.java:104` `UvFaces faces = uvUnion.faceUV;` 与 `:105` `uvUnion.isBoxUV` 仍裸解引用（这是 phase6 那次"同步"从未提交、带守卫的版本已不在的残留）。注意同文件 `:59` 已有 `properties == null || getTextureHeight() == null ? 64F` 的守卫，所以是**部分加固**。
  证据：captain 的逐文件核实 + `tmp/audit-dev/engine-side.md` b3（HEAD 文本 `:104`/`:105`/`:113`）。**含义**：`E-05` 成为本轮引擎侧唯一的【本次修复】；`E-02`（Converter 恢复）与 `E-04`（RawGeometryTree guard）降级为"已满足/仅记录"。

- 观察：**审查期间在引擎工作树里看到的两处 guard 从未提交**。带 guard 的 `GeoCube`/`RawGeometryTree`、带 BOM 容忍的 `Converter` 只存在于工作树，`git show HEAD:` 的文本里没有，现在又随文件删除而消失。因此"引擎已修"的判断只能以 **jar 字节码**为准：`A-06②`（坏关键帧表达式不再连累整条通道）确实在 jar 里（`JsonAnimationUtils.deserializeJsonToAnimation` 有 3 条 `java/lang/Exception` 异常表）。
  证据：`tmp/audit-dev/engine-side.md:38`、`tmp/audit-dev/animation.md:102-104`。

- 观察：**"同一规则两份"在新架构下仍然存在，而且是被新 `AGENTS.md` 明确定义为缺陷的那一类**。dev 的 `ClientModelManager.java:177-180` 自己维护一张"哪些版本可构建"的表（只放行 1.8.0/1.10.0/1.12.0，故意拒收 1.14.0），而引擎 `FormatVersion.isSupportedLayout()` 恒 true；另外 `AnimationManager` 的动画 id 解析（`CustomPlayerEntity.java:117-124`）与 `api/EntityAnimationApi.animationIdFor`（`:153-170`）是同一规则的两份实现，新增的实体路径还引入了第三份"物品 → 条件名"取法（`AnimationManager.java:389-400` vs `ConditionalHold.java:111-115`）。
  证据：`tmp/audit-dev/engine-side.md` c5/§4.2、`tmp/audit-dev/animation.md` D-A2/D-A3。

- 观察：**dev 新增的 `DeferredWork` 层建立在一个与 1.7.10 事实不符的前提上**。它的注释断言"`SimpleNetworkWrapper` 在 Netty 线程上内联调用 handler"，但 1.7.10 的非 priority 包只入队、在 tick 里 drain（`Packet.hasPriority()` 默认 false），且 `func_152344_a` 就是 `addScheduledTask` 的等价物——而 dev 里两套机制并存。
  证据：`tmp/audit-dev/network.md` D-01（含 `Packet.java:80-83`、`NetworkManager.java:118-131,221-248`、`MinecraftServer.java:730`、`NetworkSystem.java:182`、`Minecraft.java:2152`）。**含义**：功能上今天无害（四个使用点都只做"延迟一个 tick 也正确"的工作），但注释会误导后续修复；`N-10` 的加固不能只依赖它。

- 观察：**引擎在 `dev` 上已经不提供宿主契约的"另一半"**：9 个 `molang.context.*` 接口与 3 个安装点在引擎里齐备、签名够用，但 YSMU 全树对 `MolangSoundManagers`/`MolangForeignStorage`/`MolangDebug` **0 命中**——即三个安装点一个都没装（最典型的后果是声音关键帧静默不响）。
  证据：`tmp/audit-dev/engine-side.md` b5/c6。**含义**：这不再是"不做迁移"导致的，而是"契约已开放、宿主没去装"，属于本阶段可以真正收口的范围（见 Decision Log 的裁决）。

- 观察(captain 补记,2026-09-29 20:10Z)：**本文件先前记录的"AGENTS.md 的 Cross-Project Ownership 说 `tmp/Geckolib` 是符号链接、与实测不符"这条待办，现已作废 —— 不需要改 AGENTS.md。** captain 复核确认 `tmp/Geckolib` 已恢复为**有效符号链接**：`Attributes` 含 `ReparsePoint`、`LinkType=SymbolicLink`、`Target=..\..\Geckolib`、`fsutil` 的 Reparse Tag = `0xa000000c`(IO_REPARSE_TAG_SYMLINK)、`git -C tmp/Geckolib rev-parse --show-toplevel` = `E:/IDEA/Geckolib`、其 317 个 java 与真引擎仓逐一对应。因此 AGENTS.md 那句话重新变成正确的；同时本文件 Plan of Work 表里"`tmp/Geckolib` **不是**引擎仓"的判断也已过时(已在表内更正)。

- 观察：**即使符号链接可达，subagent 也写不进引擎仓。** `t35` 实测：`Set-Content E:\IDEA\Geckolib\tmp\...` → `Access denied`；`edit` 工具 → `[sandbox: file access denied under workspace-write mode]`；且它无法提权。所以引擎侧改动的可行流程是**"成员产出补丁 + 应用步骤 + 影响面说明，由 captain/用户套用"** —— `t35` 正是这么做的(它交付的 `GeoCube.java` 与 phase6 验证过的版本逐字节相同)，captain 随后应用、重建引擎、换入 `libs/`。

- 观察：**"以代码为准 + 允许复核者推翻自己"这条规则被证明有效。** 验证者本阶段有两次偏差结论，两次都被纠正而不是留档：`D-04(net)` 它上轮查错了两个文件(`SetModelAndTexture`/`SetStarModel` 而非 `SetNpcModelAndTexture`)，t40 自查后**改判 verified**；`D-A6` 它的 "verified" 对应的是 `t43` 在途又被回滚的编辑，captain 复核当前工作树后确认未落地并新建 `t44` 收口。另有 `M-14`：复核者报"仍定长 8 槽"，实现者给出 `RawYsmModelAdapter.java:420 new String[lastDefined + 1]` 的反证，captain 自查确认实现者正确、复核者误读了 `:39` 常量与 `:412` 循环上界。

## Decision Log

- 决定：**以 `dev` 分支（`43cc628891263ebece5a44c4971caa8dc4d3cd7a`）为本阶段的本仓基线；引擎侧以 `E:\IDEA\Geckolib` 的 `master` / HEAD `1154d09` / `git status` 仅 ` M .gitignore` 为固定基线。** 明确声明：**`tmp/Geckolib` 不是引擎仓库**（`ReparsePoint=None`、在本仓未被跟踪且被 `.gitignore:11` 的 `/tmp/` 忽略、只含一份不完整拷贝），它**不得出现在任何 inScope 或"改动目标"里**；本计划里凡提到引擎源码路径的，都是 `E:\IDEA\Geckolib`。
  理由：`engine-side.md:10-11,113-117` 的 `Get-Item`/`rev-parse --show-toplevel`/`--git-dir` 三联证据 + captain 的复核共同推翻了"符号链接"前提（新 `AGENTS.md` 里"symlinked into this workspace as `tmp/Geckolib`"这句与实际不符；以实测为准，并在 `Outcomes & Retrospective` 里提出修正该句）。引擎侧基线已在 captain 切换分支后被固定为 `master` @ `1154d09`（此前 `clean-job` 上的 18 个暂存删除已解除）。
  日期/作者：2026-09-29 / synthesizer（据 captain 决策更新）

- 决定：**引擎可构建这件事已经完成并验证，`E-11` 直接关闭；引擎侧本轮的产出目标收窄为"补 `GeoCube` 的 guard 并换 jar"（`E-05`）。** captain 亲自核实并构建：`E:\IDEA\Geckolib` 当前分支 `master`、HEAD `1154d090`、`git status` 只有 ` M .gitignore`；`.\gradlew.bat build --offline` → `BUILD SUCCESSFUL in 38s`，产物 `build/libs/geckolib-5.09.52.417-dev.jar`（2,339,441 B、SHA256 `142DF706…AB56`）与本仓 `libs/` 里那份**逐字节相同** → 用户要求的"从引擎仓构建最新 jar 放进本仓"已完成，当前不存在版本漂移（旧 jar 备份在 `tmp/backup-libs/`）。
  理由：`t27`/`t26` 的"18 个暂存删除、源树编不过"是分支 `clean-job` 上的半成品状态，随分支切换已解除，且 captain 已用真实构建证伪了"引擎不可构建"这个前提。**因此本计划不再包含"让引擎自洽编译"的里程碑，也不再把 `E-03` 当 P0 前置**（`E-03` 保留编号仅为追溯）。仍需用户/captain 执行的只有：`E-05` 改完后在引擎仓重新构建并把新 jar 放回 `libs/`（这两步 Gradle 由 captain/用户做，成员不得在中途跑）。
  日期/作者：2026-09-29 / synthesizer（据 captain 的构建核实更正）

- 决定：**`MON-01` 改为【本次修复】：`ClientModelManager` 的版本闸门必须接受四个版本，含 `1.14.0`。** dev 现在（`ClientModelManager.java:162,170,177-180`）有意只接受 `1.8.0`/`1.10.0`/`1.12.0` 并明确拒绝 `1.14.0`；**用户裁定这是错的**：引擎的 `FormatVersion.isSupportedLayout()` 对 `1.14.0` 返回 true，且 `RawGeometryTree`/`GeoBuilder` 从不读版本。
  理由：新 `AGENTS.md` 的 *Prefer one implementation to two*（同一规则不得两份）+ 用户裁定；phase6 也是同一结论（`geckolib-compat.md` 的 F-02：`isSupportedLayout()` 不是"1.12 family"判定）。**归属**：`fix-engine`，落点仍是本仓的 `src/main/java/com/fox/ysmu/client/ClientModelManager.java`（引擎虽在另一个仓库，但这条的宿主半边在本仓）。**去重方案**：按 `t27` §4 的建议，由引擎暴露单一判定（`FormatVersion.isBuildableByThisPort()`），YSMU 删掉本地表改调用它（`E-08`）；**该去重列为可选**——本轮引擎侧只做 `E-05` 时，退化为"先把 YSMU 本地表改为接受 `1.14.0`（四版本全放行），把两个仓库各有一份判定记入残余风险"，等引擎侧下次改动时合并。两条路都必须让 `1.14.0` 进几何构建。
  日期/作者：2026-09-29 / synthesizer（据 captain 用户裁定更新）

- 决定：**引擎侧修复走"改引擎仓 `E:\IDEA\Geckolib` → 在引擎仓构建 → 把新的 `geckolib-*.jar` 放回本仓 `libs/` → 再构建 YSMU"的跨仓工作流；`tmp/Geckolib` 与此无关。** 明确哪几步由谁执行：**改引擎源**由引擎侧任务做；**引擎仓 `gradlew build` 与 jar 替换**由 captain/用户做；**本仓 `compileJava`** 由收尾的集成/验证任务做一次；**`build`/`runClient`** 由用户做。成员不得在任务中途驱动任何构建（包括引擎仓）。
  理由：新 `AGENTS.md` 的 Cross-Project Ownership 说明 GeckoLib 是同一所有者的可改项目；同时 `AGENTS.md` 的 Gradle 纪律规定"构建只在全部改动结束后、且没有其它人在工作树里时"运行（引擎仓在工作区外、且构建会长时间占用它）。
  日期/作者：2026-09-29 / synthesizer（据 captain 决策更新）

- 决定：**`E-12`（`molang.binding.HostBindingContributor` + `MolangHostBindings.setContributor/contributor()`）记为后续项，不进本轮。**
  理由：它只在"YSMU 迁到现代 `molang/**` runtime"时才需要；dev 当前的动画文件 Molang 求值走 jar 里的 legacy `core.molang.MolangParser`（`GeckoLibCache.parser` 的字段类型由 javap 证实），因此现在加这个接口没有调用者（新 AGENTS.md 的"一个实现优先于两个"也要求先有真实需求再开接口）。
  日期/作者：2026-09-29 / synthesizer（据 captain 更正）

- 决定：**验收纪律按新 `AGENTS.md` 执行**：不再用 `tools/jcheck.ps1`（它扫 Gradle 缓存拼 classpath，已废弃，`tmp/phase6-artifacts/` 里保留的是历史产物）；每个修复任务的验收**只能是静态证据**（`文件:行` + 语义论证 + 复跑报告里的 grep），不得在任务中途运行 Gradle；唯一的编译门槛是全部改动结束后由集成/验证任务运行**一次** `.\gradlew.bat compileJava`，随后由用户执行 `.\gradlew.bat build` 与 `runClient`。
  理由：新 `AGENTS.md` 的 Gradle and Verification 一节原文（不要扫缓存拼 classpath；构建独占工作树，只能在最后跑）。**注意**：`engine-side.md:116` 记录 `E:\IDEA\Geckolib` 里也有一份 `tools/jcheck.ps1`，它同样不可用（硬编码工作区外路径 + 只按中文 `错误:` 计数，在 en-US javac 下会把有错的树报成 `ERRORS=0`）——不要用，也不要修它来替代 Gradle。
  日期/作者：2026-09-29 / synthesizer

- 决定：**`E-09`（三个安装点）与 `E-10`（`soundFiles` 交付）列入本次修复，而不是继续当非目标。**
  理由：phase6 把"不实现 `molang.context.*`、不安装三个 setFactory/setResolver/setGlobalSource"定为非目标（`DC-05`）的前提是"YSMU 不打包那 154 个类、全树 0 引用"——那是 **master 内嵌引擎时代**的推理。在 dev 上引擎是独立 jar，接口齐备、签名够用，YSMU 也已经实现了 `IMolangPhysicsScope` 并推远端变量；剩下的只是"宿主没去装"，且后果可观察（声音关键帧静默不响）。新 `AGENTS.md` 明确"一个功能不得因为要改另一个仓库就被做半截"，因此这三条从"非目标"升级为"本次修复"。**但保留一个降级出口**：若实施者证明某个安装点在 1.7.10 上无法落地（例如 `MolangDebug` 的游戏内浮层需要新 GUI 且验收只能在 `runClient`），允许把它明确改回非目标并在 `Outcomes & Retrospective` 写明理由与残余风险。
  日期/作者：2026-09-29 / synthesizer

- 决定：**`A-08`（控制器每帧重解析/AST 缓存）、`A-09`（warn-once 后永久 FALSE）、`A-06` 里"补注册 `ctrl.set_animation`/`ysm.play_sound`/`particle` 等新能力"、`DC-04`（`M-15`/`M-20` 默认值）、`DC-07`（`M-13` 手拼 id）、`DC-08`（`R-02`）、`DC-09`（`R-10` 相机高度接线）继续列为【非目标（不修）】。**
  理由：这几条在 phase6 已被裁决为非目标，dev 上它们"仍然存在"但没有新的证据改变裁决——`A-08` 是纯性能（正确性无缺陷，改法会显著扩大 `[animation]` 的改动面并引入回归风险）；`A-09` 是纯可观测性；"补注册新指令"是新功能（需要声音/粒子/控制器改写等运行时设施）；`DC-04`/`DC-07` 只影响将来 `.ysm` 导出的字节可比性与"管理员改 `DefaultModelId`"的边缘情形；`R-02` 是未实现功能的只写占位（无可验证收益）；`DC-09` 的接线需要改首人称相机，验收只能在 `runClient`。**残余风险**：使用 timeline 事件做换动作/音效/变量初始化的模型包行为仍缺失（本轮只保证"缺注册"在加载期告警一次）；控制器运行时每帧产生稳定垃圾；有缺陷的模型包只有一次日志；`M-15`/`M-20` 的字节与真 YSM 不一致；`R-02` 一旦补上真实上传逻辑，`CompleteFeedback.Handler`（netty/游戏线程）就是跨线程改状态的现成入口。它们的详细证据保留在 `tmp/audit-dev/delta.md`，随时可以再取用。
  日期/作者：2026-09-29 / synthesizer

- 决定：**`N-02`（双通道重复传输）不通过"关掉 legacy"来修，改为"17 握手失败才 fallback + 客户端对同一模型幂等去重"**，并保留 legacy 通道。
  理由：phase6 已裁决过同一取舍，dev 上的证据更强：`M-01` 让 17 通道对文件夹模型不可用（`OpenYsmFormat.java:83` 无条件写出的 format32 缓存 `isBridgeable=false`），legacy 是这类模型唯一可用路径。若把 legacy 当"开关关掉"，`ENABLE_OPEN_YSM_SYNC_PROTOCOL` 默认开启时一旦 17 通道故障（`N-05` 的 C2S 上限、`N-11` 的会话竞争、`M-01`）客户端将失去唯一可用路径。去重落在 `ClientModelManager.registerAll`（两条通道的共同汇合点，dev 上无任何去重）。
  日期/作者：2026-09-29 / synthesizer

- 决定：**`N-01` 的修法必须是"同一线程内联发送密码包"，禁止改用 `DeferredWork`。**
  理由：`network.md:180,232` 明确警告：`DeferredWork` 会把工作推迟到下一个 tick，等于在顺序问题上再加一个 tick 的不确定性，反而削弱"`SendModelPassword` 先于 `RequestLoadModel`"这条不变量。这条来自对 dev 新结构的核验，是 phase6 没有的约束。
  日期/作者：2026-09-29 / synthesizer

- 决定：**归属按 dev 的实际文件所有权重新划分，而不是照抄 phase6 的任务名。** 具体调整：phase6 归 `[engine]` 的 `MON-07`/`MON-09`/`MON-10`/`MON-11`/`MON-12` 全部回到 `[render]`/`[network]`/`[model]`（因为 `ClientModelManager.java`、`ModelCacheWriter.java`、`OpenYsmFormat.java` 都在本仓树内）；phase6 归 `[engine]` 的"引擎格式容忍"条目改由新的 `[engine]` 任务在**引擎仓库**承接；`A-01` 的修法跨 `[network]`（分类表是 `network` 层登记的？不——见下条裁决）与 `[animation]`，按文件落在 `client/animation/**` → 归 `[animation]`。
  理由：新 `AGENTS.md` 的 inScope 归属规则以"正确修法所在文件"为准；phase6 的 engine 归类是 master 内嵌引擎时代的产物，如果不重划，这些条目会落在不存在的引擎源码上（无人执行）。
  日期/作者：2026-09-29 / synthesizer

- 决定：**`A-01` 归 `[animation]` 独占，`[network]` 不参与**（phase7 的 Progress 里那一行标注为 `[network] [animation]` 只是提醒两条 predicate 的调用点在动画包里，执行者仍是 `[animation]`）。
  理由：dev 的 `ConditionalHold`/`AnimationManager` 都在 `src/main/java/com/fox/ysmu/client/animation/**`（`[animation]` 的 inScope），与 `network/` 无关；避免同一条链挂在两个任务上造成重复改动。
  日期/作者：2026-09-29 / synthesizer

## Outcomes & Retrospective

**（本阶段结论，captain 填写于 2026-09-29 20:10Z。代码层面已全部完成，唯一未执行项是用户侧的 `build`/`runClient` 实战验收。）**

**交付的可观察行为**（对照 Purpose / Big Picture；均为 dev 基线上的实际改动）

- **崩溃级清零**：`CU-01`(轮盘 `%d` → `%s`，两份 lang + `AnimationRouletteScreen`)、`CU-03`(`thePlayer == null` 三处入口判空 + `PlayerModelTarget` 四个出口安全值)、`N-1`(玩家自选贴图把 null 装进 EEP 的完整链路：入口拒绝 + EEP 拒绝 null + `saveNBTData` 用 `x == null ? "" : x.toString()` + 渲染/实体/模型三处正交兜底 → 引擎 `TextureManager.bindTexture(null)` 不可达)。
- **静默失效收口**：`M-18` 短 `.ysm` 不再打断 `reloadPacks`；`M-07` 静态共享 `MessageDigest` 改 per-call；`M-19` 加密失败不再产出 24 字节空缓存；`N-07` 分块校验在改名之前 + ACCUMULATORS TTL；`A-06①③` 未注册函数与两侧 `ctrl.hold` 语义统一；`A-07` 条件名诊断 + `InnerClassify` 与求值器收敛为一份。
- **功能恢复**：`A-01` 空手条件名链路可达(`hold_mainhand:empty` 5 命中)；`A-02` 副手 swing 独立分类 + `swing_offhand` 回退(21 命中)；`CU-04` 五个热键先消费 `isPressed()` 再判界面；`CU-07` 广播半径 512。
- **协议与同步**：`N-01` 密码包与 `RequestLoadModel` 同线程内联；`NETWORK_PROTOCOL` bump 到 **2**、新增 id **98** `RevokeModelGuiGrant`(NF-01 端到端闭环，服务端按发送者 UUID 授权，伪造 id 撤不掉他人)；`N-02` 收敛为"17 主路径 + 失败才 legacy"的 **gate**(四触发点互斥、健康会话不多发 legacy、调度器为独立单线程 daemon 不占共享池)。
- **版本闸门**：`MON-01` 按用户裁定**接受四版本**，且按 `E-08` 的裁决**删掉宿主本地表、直接调用引擎 `FormatVersion.isSupportedLayout()`**(符合 AGENTS.md 的 *Prefer one implementation to two*)。
- **引擎侧**：`E-05` GeoCube 空 uv / 缺 description 守卫由 captain 应用成员补丁 → 引擎重建(`BUILD SUCCESSFUL`)→ 新 jar 换入 `libs/`(`GeoCube.class` 11,327 → 11,708 B)。

**降级回非目标（理由）**：`E-09` 三个安装点(`setFactory` 仅现代 runtime 用；`setResolver` 方向与宿主 `RemoteAnimationVariables` 推送相反；`setGlobalSource` 无消费者)、`E-10`(引擎两条声音路径齐备，但**宿主侧完全未接线 → `sound_effects` 关键帧今天静音**)、`E-12`(`HostBindingContributor` 留给将来迁现代 runtime)、`MC-02`/`MC-03`(完整 cube 烘焙移植；本轮只做门控 + 散件烘焙补齐)、`M-04`/`M-05` 的 BUILT 迁移(需同时改三个 inScope 外文件，且改覆盖策略会让 AGENTS.md 的 "intentionally overwrites the built-in copies" 变成错的；`processBlacklist` 半边已实现)、`R-02`、`A-08`/`A-09`、`DC-04`/`DC-06`/`DC-07`/`DC-08`/`DC-09`。

**引擎侧基线**：`E:\IDEA\Geckolib` @ `5ca328b`(master；`1154d09..5ca328b` 只有一行 `.gitignore` 差异)。`E-05` 的源码改动**目前仍是未提交的工作树修改**，需在引擎仓提交。`tmp/Geckolib` 已恢复为指向该仓的有效符号链接。

**`N-02` 与 `M-01` 的相互作用**：符合预期。`M-01` 的门控用新谓词 `RawYsmModelAdapter.isBinaryPayloadBridgeable`(忽略 `sourceJson`、只看已烘焙 face 数)决定"能否产出可桥接二进制"，从而让 17 协议不再下发坏缓存；`N-02` 的 gate 则保证 17 失败时 legacy 仍然兜住。两者叠加后的语义是"17 只发能用的，legacy 永远可用"。

**六份核验报告里"疑似"的运行期结论**：本轮**全部无法在沙箱内运行期证实/推翻**(subagent 跑不了 Gradle、更没有 runClient)，因此 `S-01`/`S-02`(render)、`A-V-03`、`DCS-09`/`DCS-10` 等仍为**未验证**，已登记进 `tmp/audit-dev/verification-phase7.md` 的残余清单；其中 `A-03`(v.* 跨域读写)需要第三方 `4_default_controllers` 包在 runClient 里观察。

**最终统计与验证证据**：条目复核 **verified 86 / 部分落地 4 / failed 0 / 未确认 0**(`M-14` 经实现者反证 + captain 自查判为**已正确**、`M-21` 由 t43 补齐；`D-A6` 由 t44 以 javadoc 闭环)。编译门槛由 captain 执行三轮后通过(轮 1 `JsonArray.add(float)` ×4、轮 2 缺 `JsonPrimitive` import ×4、**轮 3 `BUILD SUCCESSFUL in 13s`**)，t43/t44 之后再跑两次均通过(15s / **12s，0 错误**)。反例搜索：Java 8 库 API 真实命中 0、版本判定与动画 id 解析各收敛为一份、`NetworkHandler` 既有 id 一行未改。证据落点：`tmp/audit-dev/verification-phase7.md`、`tmp/audit-dev/delta.md`、六份 `tmp/audit-dev/*.md` 核验报告、`tmp/compile-dev{,2,3,4,5}.log`、各任务 output。

**交付规模**：98 files changed，+4607 / −1026，另新增 `network/message/RevokeModelGuiGrant.java`；引擎仓 1 文件(GeoCube)已改待提交。

**三条结构性问题（本阶段实际踩到的，建议下一轮在建契约时作为检查项）**

1. **ExecPlan 的"条目清单"与"inScope 表"不同步** → 条目按发现主题归类、inScope 按文件/包划分，两者交叉处产生"主题归 A、文件归 B"的空档，导致条目落在**已完成任务**的文件里而无人承接。本阶段发生 **4 次**：`N-1` 的 EEP 半边、`N-5` 注释、`N-2` 的重复触发点(以上三处补入 `t36`/新建 `t38`)、`N-02` 的服务端 gate(amend 进 `t31` + 新建 `t39`)。
2. **subagent 无法运行 Gradle** → `GRADLE_USER_HOME=D:\gradle_cache` 在工作区之外，workspace-write 沙箱打不开 `gradle-*.zip.lck`；`t37` 三次尝试(wrapper / 直接 `gradle.bat` / `--no-daemon -Dorg.gradle.native=false`)全部停在 Gradle 启动阶段，javac 一次未运行。**captain 可以(danger-full-access)**。后果：成员的"无 classpath 语法解析"自检抓不到类型错误与缺失 import，本轮因此靠 captain 的编译才发现两个编译阻断。
3. **验证与在途编辑的竞态** → 复核结论可能对应一个已不存在的工作树状态：`t37` 的 `compileJava` 撞上 `t39` 的在途编辑；`D-A6` 的 "verified" 撞上 `t43` 自行回滚的编辑。缓解方式是"依赖全部实现任务 + 只有所有编辑停止后才跑编译门槛"，但**跨任务的复核仍会撞车**，建议下一轮让验证任务显式依赖它要复核的全部任务。

**原占位说明（已被上文取代，保留以便对照）**

未完成。本节在本阶段全部修复任务完成、引擎侧 jar 与源树对齐、收尾的 `compileJava` 通过、并由用户跑通 `build`/`runClient` 的 12 个场景之后填写。届时至少要写明：实际交付了哪些可观察行为（对照 Purpose / Big Picture）、哪些条目被降级回非目标（`DC-04`/`DC-06`/`DC-07`/`DC-08`/`DC-09` 与允许降级的 `E-09`）及其理由、引擎侧基线最终固定在哪次 commit、`N-02` 与 `M-01` 的相互作用是否如预期、以及六份核验报告里哪些"疑似"被运行期证实或推翻（`S-01`/`S-02`(render)、`A-V-03`、`DCS-09`、`DCS-10`）。

## Context and Orientation

这一节让一个完全没有上下文的新人也能独立执行本计划。所有路径都是仓库相对路径，除非明确写了绝对路径。

**仓库与分支**。这是 Minecraft Forge 1.7.10 的模组 YSMU（mod id `ysmu`，根包 `com.fox.ysmu`，Forge 入口 `src/main/java/com/fox/ysmu/ysmu.java`）。当前签出的是 `dev` 分支（HEAD `43cc628`）。构建走 GTNH 的 Gradle 约定插件（`settings.gradle.kts`、`build.gradle.kts`），`gradle.properties` 把目标定为 Minecraft `1.7.10` / Forge `10.13.4.1614` / MCP stable `12`，开启 Mixin 与 Jabel（现代语法、JVM 8 产物），`dependencies.gradle` 声明依赖。

**引擎已经外置**。`dev` 的 `src/main/java` 只有 `com/` 与 `rip/` 两个顶层目录，共 180 个 java 文件；**没有** `software/bernie`、`com/eliotlash`、`net/geckominecraft`。GeckoLib 3 引擎以独立 mod 形式来自 `libs/geckolib-5.09.52.417-dev.jar`（放在 compileOnly / testImplementation / devOnlyNonPublishable 三条 classpath 上），YSMU 声明 `required-after:geckolib`。运行时玩家需要把 GeckoLib 的 reobfuscated release jar 与 YSMU 一起安装。引擎仓库是 `E:\IDEA\Geckolib`（分支 `master`，HEAD `1154d090`），它的源码副本在 `tmp/Geckolib`（**只读参考**，见 Decision Log 与 Surprises）。

**宿主契约已经实现了一半**。`src/main/java/com/fox/ysmu/client/entity/CustomPlayerEntity.java:31` `implements IAnimatable, IMolangPhysicsScope`，`:167-183` 提供 `getMolangEntity/getMolangModelId/getMolangAnimationId`；`src/main/java/com/fox/ysmu/event/ClientEventHandler.java:124-128` 用 `RemoteAnimationVariables.put(event.getEntity(), event.getRoamingVars())` 往引擎推远端变量，`:227` 清空。引擎侧 `MolangPhysicsRuntime.begin(IMolangPhysicsScope, double, AnimationProcessor)` 与 `applyRemoteVariables` 消费这些值（`tmp/Geckolib/.../core/molang/MolangPhysicsRuntime.java:27,36,44-50`）。YSMU 自己**不再**有 Molang 物理运行时（旧副本已删），`src/main/java/com/fox/ysmu/client/animation/molang/` 只剩 3 个宿主胶水类（`CtrlHoldFunction`、`MolangInstructionExecutor`、`QueryPositionDeltaFunction`）。

**新增的宿主 API 面**。`src/main/java/com/fox/ysmu/api/` 有三个类：`EntityAnimationApi.java`（客户端：给实体推 clip，键是 `data/EntityClips.java` 的 `int entityId` 表）、`EntityModelApi.java`（服务端：给 NPC/实体设模型，写 `data/NPCData.java` 并广播）、`ModelGuiApi.java`（服务端：给实体开模型选择界面的授权，5 分钟 TTL）。这三个类引入了本阶段若干"新增发现"（`NF-01`、`D-A1`、`D-A2`、`D-A6`、`D-02`、`D-03`、`D-03(net)`）。

**新增的线程亲和层**。`src/main/java/com/fox/ysmu/util/DeferredWork.java` 有 client/server 两条 `ConcurrentLinkedQueue`，由 `event/DeferredWorkTicker.java` 各 tick drain 一次，使用点四处（`HandshakeMessage`、`OpenModelGuiMessage`、`SetModelAndTexture`、`SetNpcModelAndTexture`）。它的注释有一个与 1.7.10 事实不符的断言（见 Surprises），本阶段要在 `D-04` 里把注释与事实对齐。**不要**用它来实现 `N-01`。

**网络与同步（读者必须知道的背景）**。`dependencies`/`NetworkHandler.java` 注册 24 个包（23 个旧 id + dev 新增的 `SERVERBOUND_HANDSHAKE` = 97），id 不得重编号；`:18` `PROTOCOL_VERSION = Tags.VERSION`，`:20-25` `NETWORK_PROTOCOL = 1`（改包布局要 bump）。模型同步有两条通道：legacy（MD5/AES + `SendModelFile`/`SendModelFileChunk`，上限 1900 KiB 直发 / 512 KiB 分块）与 17 协议（`S2CVersionCheck17` → payload 01..05，服务端加带宽节流）。`AGENTS.md` 要求"`SendModelPassword` 必须先于 `RequestLoadModel`"这条不变量。`N-01` 修的是这条不变量在代码里没有被强制执行。

**模型格式与缓存**。文件夹模型在 `config/ysmu/custom/<模型名>`，必须含 `main.json`、`arm.json` 与至少一张 `.png`；可选动画文件 `main/arm/extra.animation.json`，缺失回落到内置 default。`.ysm` 归档同理。三个扫描器是 `model/format/FolderFormat.java`（一层）、`YsmFormat.java`（不递归）、`OpenYsmFormat.java`（递归）；缓存由 `model/format/ModelCacheWriter.java` 写（legacy 名 = 内容 MD5，17 协议名 = `%016x%016x`），格式容忍由 `Converter.fromJsonString`（引擎）与 `ClientModelManager.java` 的版本闸门（宿主）共同决定。

**术语表**。"format32" 指 OpenYSM 二进制容器格式号 32（`YSMBinarySerializer` 写出的加密缓存）；"可桥接（bridgeable）"指 `RawYsmModelAdapter.isBridgeable` 为 true，即该内部模型能降级成 GeckoLib 可用的几何+动画，false 时客户端只 warn 后丢弃；"池"指 `util/ThreadTools.java:11-16` 的 `THREAD_POOL`（`corePoolSize=0` + 无界队列，实际并发度 1）；"warnOnce" 指用集合去重、同一原因只打印一次的日志做法；"blocker/high/medium/low" 沿用 phase6 的判据（`blocker` = 默认配置下正常操作路径上必然发生且不可恢复；`high` = 常见输入下功能整体失效/数据丢失/崩溃；`medium` = 特定配置或输入才触发，或造成可察觉的资源浪费/状态不一致；`low` = 可观测性、日志噪音、死代码、性能微优化、文档）。

**证据地图**。六份 dev 核验报告在 `tmp/audit-dev/`：`core-ui.md`（CU-xx/V-03 + NF-01/NF-02）、`network.md`（N-xx/S-01/CUI-01 + D-01..D-04）、`model.md`（M-xx/S-xx + D-01..D-05）、`render.md`（R-xx/S-xx + N-1..N-5）、`animation.md`（A-xx/S-xx + D-A1..D-A6）、`engine-side.md`（桶 a/b/c + 引擎待办清单）。归位结果与统计在 `tmp/audit-dev/delta.md`。master 时代的六份报告仍在 `tmp/audit/`（含 `fix-engine.md`、`verification.md`），phase6 的 ExecPlan 在 dev 上已被移除但可从 git 恢复（`git show 832109e:.agent/phase6-geckolib-compat-sync.md`）。`tmp/Geckolib/` 是引擎源码副本；`tmp/phase6-artifacts/` 是历史产物（含已废弃的 `jcheck.ps1`）。

**本阶段的 id 命名**。本阶段沿用 phase6 的发现 id（`CU-xx`、`CUI-xx`、`M-xx`、`N-xx`、`R-xx`、`A-xx`），引擎侧新增 `E-xx`（`E-01`..`E-10`），dev 新增发现沿用各报告自己的编号（`N-1`..`N-7` 来自 render、`NF-01`/`NF-02` 来自 core-ui、`D-01`..`D-04` 来自 model、`D-02(net)`..`D-04(net)` 来自 network、`D-A1`..`D-A6` 来自 animation）。**同一个 id 在两份报告里含义不同时必须看括号里的来源**（例如 `D-02` 在 model 报告里是 `hasModel` 键不匹配，在 network 报告里是双版本轴，本计划分别写作 `D-02` 与 `D-02(net)`）。

## Plan of Work

本节是执行顺序与归属。每一条都给出：归属任务、该任务在 dev 上的 inScope 路径（仓库相对 POSIX；引擎侧写成 `E:\IDEA\Geckolib\...`，**不要**用 `tmp/Geckolib/` 前缀，它既不是引擎仓也不完整）、dev 的 `文件:行` 证据、以及验收方式（静态证据为主）。**只列【本次修复】= delta 的【dev 仍存在】+【已转移到 Geckolib 引擎侧】**；【已修复】（`R-11`、`R-06`）与【已作废】的条目不在修复清单里，只在 `tmp/audit-dev/delta.md` 留档。

修复任务的 inScope 汇总（六个任务，五个在本仓、一个在引擎仓库）：

| 任务 | 仓库 | inScope 路径 |
|---|---|---|
| `fix-core-ui` | YSMU | `src/main/java/com/fox/ysmu/ysmu.java`、`CommonProxy.java`、`Config.java`、`command/**`、`event/**`、`eep/**`、`compat/**`、`client/ClientProxy.java`、`client/ClientEventHandler.java`、`client/gui/**`、`client/input/**`、`resources/assets/ysmu/lang/{en_US,zh_CN}.lang`（原表在此列了 `data/**`，captain 裁决归 `fix-model`） |
| `fix-network` | YSMU | `src/main/java/com/fox/ysmu/network/**`、`src/main/java/com/fox/ysmu/client/sync/**`（原表在此列了「仅触发逻辑的 `model/ServerModelManager.java:100-105`」，captain 裁决该文件整体归 `fix-model`：N-02 的**服务端 gate 由 `fix-model` 交付**，`network/**` 侧的 3 处 hook 接线由后补任务 `t39` 完成） |
| `fix-model` | YSMU | `src/main/java/com/fox/ysmu/model/**`、`src/main/java/rip/ysm/**`、`src/main/java/com/fox/ysmu/util/**`、`src/main/java/com/fox/ysmu/data/**`、`src/main/java/com/fox/ysmu/api/**`（**`api/**` 为原表漏列，V-P7-06**） |
| `fix-render` | YSMU | `src/main/java/com/fox/ysmu/client/renderer/**`、`client/model/**`、`client/texture/**`、`client/upload/**`、`client/compat/**`、`client/entity/**`（**`client/entity/**` 为原表漏列，V-P7-06**）、`mixin/**`、`resources/mixins.ysmu.json`、`resources/META-INF/ysmu_at.cfg`（原表在此列了 `client/ClientModelManager.java`，已改由下面的专职任务收口） |
| `fix-animation` | YSMU | `src/main/java/com/fox/ysmu/client/animation/**` |
| `fix-clientmodel`（captain 新增，原表没有） | YSMU | `src/main/java/com/fox/ysmu/client/ClientModelManager.java`。**理由**：该文件被 `fix-render`(R-01)、`fix-engine`(MON-01/E-08)、`fix-network`(N-02 去重 / CUI-01 / N-07 名字半边)、`fix-model`(M-06 sleep / M-10 消费侧) 四个领域同时需要，而运行时不允许 inScope 重叠、塞给任何一方都会让那个任务失衡；故新建专职任务一次收口这 7 项 |
| `fix-engine` | **YSMU + Geckolib** | YSMU 侧已并入 `fix-clientmodel`；引擎侧：`E:\IDEA\Geckolib\src\main\java\software\bernie\geckolib3\**` —— 真引擎仓，经 **`tmp/Geckolib` 符号链接**在工作区内可见。**captain 已核实该链接恢复为有效符号链接**(`ReparsePoint=True`、`LinkType=SymbolicLink`、`Target=..\..\Geckolib`、`git -C tmp/Geckolib rev-parse --show-toplevel` = `E:/IDEA/Geckolib`)，故原文"`tmp/Geckolib` 不是引擎仓"的说法**已过时**；但引擎文件在工作区外，**subagent 的 workspace-write 沙箱仍写不进去**(见 Surprises)，引擎侧改动由 captain 应用成员的补丁 |

### t-fix-core-ui（YSMU）

逐条（id｜dev 证据｜验收）：

- `CU-01`（blocker）｜`client/gui/AnimationRouletteScreen.java:52-53` + `resources/assets/ysmu/lang/en_US.lang:249` / `zh_CN.lang:249` + `Config.java:13`｜两份语言串改 `%s`、参数改 `"extra" + selectId`；验收：`Select-String` 确认两份 lang 的第 249 行不再含 `%d`，且 `AnimationRouletteScreen` 的构造调用传入 `String`。
- `CU-02`｜`command/YsmCommand.java:57` + `en_US.lang:247` / `zh_CN.lang:247`｜语言串改 `%sms` + 参数 `String.format(Locale.ROOT, "%.2f", (double) watch.getTime())`（必须传 `double`，传 `long` 到 `%.2f` 会抛 `IllegalFormatConversionException`）；验收：确认 `%.2f` 只出现在 Java 的 `String.format` 里、不在任何 lang 串里。
- `CU-03`（blocker）｜`client/input/PlayerModelScreenKey.java:22-27`、`client/gui/DisclaimerScreen.java:43-46`、`client/gui/PlayerModelScreen.java:39-42,221,224`、`client/gui/ModelSelectionTarget.java:41-43`、`client/gui/PlayerModelTarget.java:29-37`｜两个入口判空 + 显式传玩家；`PlayerModelTarget` 对 null 的四个出口（`getPreviewEntity`/`getModelId`/`getTextureId`/`apply`）各自返回安全值；验收：`Select-String` 确认两个入口都有 `thePlayer == null` 判断，且 `ModelSelectionTarget.of` 不再被传 null。
- `CU-04`｜五个 handler（`AnimationRouletteKey.java:17-22`、`ExtraAnimationKey.java:29-37`、`ExtraPlayerConfigKey.java:17-23`、`PlayerModelScreenKey.java:19-29`、`ClientEventHandler.java:211-220`）｜**必须"先消费 `isPressed()` 再判界面"**（`core-ui.md:104-110` 给了反例）；验收：逐 handler 贴出改动后的 3 行代码，确认消费在前。
- `CU-05`｜`client/gui/button/StarButton.java:25,44-45`、`TextureCountButton.java:23`、`PlayerModelScreen.java:112,114-119,64-69,138`｜两个按钮接收 `ModelSelectionTarget`；对 `!supportsStars()` 的 target 明确禁用；验收：确认两个按钮的构造器签名含 target，且 `PlayerModelScreen` 的构造点传入 `this.target`。
- `CU-07`｜`event/CommonEventHandler.java:224-241`（`:233-241` 的 `64.0D`）｜半径改 512 或改定向发送；验收：贴出改动后的 `getPlayerTrackingPoint`，确认不再有 `64.0D`。
- `CU-09`｜`client/gui/ExtraPlayerConfigScreen.java:104-113,136-141`、`Config.java:112-130`｜GUI 内钳制 scale ∈ [8,360]、pos 在屏幕范围；`syncInt/syncDouble` 在 load 分支 clamp；验收：贴出 clamp 代码与取值范围常量。
- `CU-10`（合并 `A-10`/`NF-02`/`D-A1`）｜`client/ClientEventHandler.java:222-230`（失效入口）、`:73-84`、`event/CommonEventHandler.java:56-65`｜清理挂到 `FMLNetworkEvent.ClientDisconnectionFromServerEvent`；去掉 `onWorldUnload` 的 `dimensionId == 0` 限制；join 时同时清 `RemotePlayerMotionStates`；清 6 项（`ClientModelManager.clearConnectionState`、`RemotePlayerAnimationQueries`、`RemotePlayerMotionStates`、`RemoteAnimationVariables`、`NPCData`、`EntityClips`）+ 调 `[animation]` 提供的 `clearPlayerState()`；验收：贴出新的清理入口与调用它的订阅方法，确认事件类型是客户端确实会收到的那一个。
- `CU-11`｜`client/ClientEventHandler.java:206-208`｜`try/finally`；验收：贴出改动。
- `CU-12`｜`eep/ExtendedModelInfo.java:22,141-144`、`event/CommonEventHandler.java:201-211`｜删/重绑 `player` 字段 + 客户端不再 `markDirty`；验收：贴出 `init` 与 `markDirty` 调用点的改动。
- `CU-13`｜`PlayerModelScreen.java:218-222`、`ModelButton.java:56-61`、`TextureButton.java:43-47`｜`try/finally`；验收：三处都贴出。
- `CU-14`｜`DisclaimerScreen.java:42-49`｜补类注释（不改行为）；验收：注释文本。
- `CU-15`｜`PlayerTextureScreen.java:30-36`、`ModelButton.java:36,60`、`ExtendedModelInfo.java:28`｜排序前 `new ArrayList<>(textures)`；默认贴图优先 `Config.DEFAULT_MODEL_TEXTURE`；验收：贴出两处改动。
- `CU-16`｜`PlayerModelScreen.java:213`、`ModelButton.java:50`、`TextureButton.java:38`、`AnimationRouletteScreen.java:65-91`｜只做低风险缓存（`initGui` 缓存缩放因子、缓存已格式化按键文本）；验收：贴出缓存字段与初始化点。
- `CU-17`｜`ModelButton.java:64-69`｜第二行加省略号；验收：贴出。
- `CU-18`｜`PlayerModelScreen.java:81,241`｜`models.isEmpty()` 空态 + 新增键到两份 lang；验收：两份 lang 均有该键（`Select-String` 各命中 1 次）。
- `CU-19`｜`ModelInfoButton.java:11-44`、`NetworkHandler.java:133-134`｜删除死按钮类与 `RequestServerModelInfo` 的注册（**保留 id 注释说明已弃用，不得重编号**）；验收：`Select-String` 确认 `ModelInfoButton` 不再存在引用、`NetworkHandler` 里 id 10 的注释写明弃用。
- `CU-20`｜`Config.java:29,80`｜与 `CU-06`/`M-06` 协同：`ThreadCount` 变成真配置后，注释写明"该值被 `ThreadTools` 读取"；验收：注释文本。
- `CUI-02`｜`command/YsmCommand.java:54`｜改为遍历服务端玩家列表或 `sendToAll`；验收：贴出改动。
- `NF-01`｜`client/gui/ModelSelectionTarget.java:33-39`、`PlayerModelTarget.java:57-60`、`EntityModelTarget.java:47-50`、`api/ModelGuiApi.java:72-88`｜`PlayerModelScreen.onGuiClosed` 在 `getGrantId() != -1` 时撤销授权（需要把 `revokeSelectionGrant` 暴露给客户端，或增一条 C2S 撤销包——若走新包，**必须按 `NetworkHandler.java:20-25` 的规则 bump `NETWORK_PROTOCOL`**）；最低限度把文档与实际行为对齐；验收：贴出 `onGuiClosed` 与撤销调用。

### t-fix-network（YSMU）

- `N-01`（high）｜`network/message/SyncModelFiles.java:64-72,135-147`、`RequestLoadModel.java:65-77`｜密码包改为**同一线程内联发送**（`sendPassword` 里直接 `NetworkHandler.sendToClientPlayer(new SendModelPassword(...), sender)`）；**禁止**改用 `DeferredWork`（见 Decision Log）；验收：贴出改动后的 `sendPassword`，确认没有 `THREAD_POOL.submit`。
- `N-02`（high）｜`model/ServerModelManager.java:100-105`、`network/message/RequestSyncModel.java:24-27`、`client/ClientModelManager.java:72-92`、`client/sync/OpenYsmModelSyncClient.java:259`、`RequestLoadModel.java:85-86`、`client/ClientEventHandler.java:81-83`、`client/gui/PlayerModelScreen.java:90`｜17 握手失败才 fallback；`RequestSyncModel` 纳入开关；`ClientModelManager.registerAll` 幂等去重（内容签名相同则跳过）；去掉重复触发点；验收：贴出去重判定代码 + 开关条件，并说明 `registerAll` 的签名从哪来。
- `N-03`｜`network/message/SyncModelInfo.java:54-77`｜应用 NBT 回客户端线程（`Minecraft.getMinecraft().func_152344_a`）；验收：贴出改动。
- `N-04`｜`SendModelFile.java:44-61`、`SendModelFileChunk.java:77-121,123-131,133-150`｜落盘移到池；验收：贴出改动，确认收包 handler 里不再有 `FileUtils.writeByteArrayToFile`/`RandomAccessFile`。
- `N-05`｜`C2SModelSyncPayload17.java:14,38-46`、`S2CModelSyncPayload17.java:23,28`、`client/sync/OpenYsmModelSyncClient.java:228-247`｜C2S 预算改 `32767 - 头部`，或请求列表分片；S2C 上限写 `2_097_050`；验收：贴出常量与分片逻辑（若分片，`sendPacket04` 的循环）。
- `N-06`｜`SyncModelFiles.java:44-51`、`SendModelFile.java:29-33`、`SendModelPassword.java:25-30`、`RequestServerModelInfo.java:24-32,63`、`SyncStarModels.java:27-35`、`C2SModelSyncPayload17.java:38-46`、`OpenYsmModelSyncClient.java:200-205`｜每处读长度前与 `readableBytes()` 和业务上限比较；`RequestServerModelInfo` 的枚举索引加边界；验收：逐处贴出校验行。
- `N-07`｜`SendModelFileChunk.java:133-150,28,167-183`、`client/ClientModelManager.java:348-360`｜改名**之前**做内容 MD5 校验（`md5Hex(bytes).equalsIgnoreCase(fileName)`，注意 `M-07` 未修时会误报，可自行 `getInstance`）；`ACCUMULATORS` 加 TTL；`getMd5Info` 过滤非 32 位 hex 文件名；验收：贴出校验与过滤代码。
- `N-08`｜`SyncModelFiles.java:93-133`｜复用 `Config.BANDWIDTH_LIMIT` 节流 + 尊重 `channel.isWritable()`；验收：贴出改动。
- `N-09`（残余）｜`CompleteFeedback.java:20-27`、`RequestServerModelInfo.java:42-51`｜处置这 2 个死包（删注册 + 注释保留 id，或补发送方）；**不要动** `SetNpcModelAndTexture`（它已是真实实现）；验收：贴出 `NetworkHandler` 的改动与注释。
- `N-10`｜`SendModelFileChunk.java:50,66-72`、`C2SModelSyncPayload17.java:41`、`RequestServerModelInfo.java:63`、`SendModelFile.java:45-47`、各 handler｜`fromBytes` 软失败 + handler 体 try/catch（只记日志）；删除或补全 48 字节死分支；验收：贴出改动，并说明 `DeferredWork` 只覆盖被延迟的 handler 体（`D-04` 的注释修正）。
- `N-11`｜`network/sync/OpenYsmModelSyncServer.java:70-91,107-125,234-236`、`C2SCompleteFeedback17.java:13-50`｜反馈包加 per-attempt session id 且服务端只接受匹配的；缺缓存时给"已跳过"信号或计入完成数；若改包布局**必须 bump `NETWORK_PROTOCOL`**；验收：贴出 session 字段、校验条件与 `NETWORK_PROTOCOL` 的新值。
- `N-12`｜`RequestLoadModel.java:28-29,63-94`、`util/ThreadTools.java:11-16`｜把"等密码"改成事件/回调驱动或在客户端 tick 上计数，不占用共享池；验收：贴出改动，确认池里不再有 `Thread.sleep`。
- `CUI-01`｜`client/ClientModelManager.java:323-346`｜`sendSyncModelMessage` 整体回投客户端线程，清缓存与枚举缓存目录在同一任务里顺序执行；`theWorld == null` 的等待加超时与中断检查；验收：贴出改动后的方法体。
- `D-03(net)`｜`network/message/SetNpcModelAndTexture.java:103`、`api/EntityModelApi.java:32-56`｜检查返回值并在失败时给玩家反馈（或至少 warn 到位）；验收：贴出返回值处理。
- `D-04(net)`｜`SetNpcModelAndTexture.java:26-27,94-119`｜补显式字符集/长度校验（或把 javadoc 改成与实现一致）；验收：贴出校验与 javadoc。

### t-fix-model（YSMU）

- `M-01`（high）｜`model/format/OpenYsmFormat.java:74,83`、`model/resource/RawYsmModelAdapter.java:109-124`、`model/resource/YSMFolderDeserializer.java:434-468`｜`cacheFolderModel` 在无法产出可桥接二进制时**跳过 `writeOpenYsm` 并 warn**（新增"二进制载荷可桥接"谓词，**不能**用 `isBridgeable` 做门控——它对文件夹模型因 `sourceJson != null` 恒 true）；验收：贴出新谓词与调用点，说明它对文件夹模型返回 false 的依据。
- `M-02`（high）｜`YSMFolderDeserializer.java:470-495`｜补齐关键帧/timeline/sound_effects/blend_weight 解析（参考 `OpenYSM/` 的对应实现与 `tmp/Geckolib/tmp/YesSteveModel-dev-1.20/` 的 `GeoBuilder`）；若本阶段不做完整烘焙，则与 `M-01` 的门控一起保证"不下发坏缓存"，并在 `Outcomes` 写明残缺；验收：贴出新增的解析方法与它对 `keyframes` 的断言点（若没有运行期探针，用"字段被写入 `RawAnimation` 的哪几行"论证）。
- `M-03`｜`YSMFolderDeserializer.java:462-463`、`RawYsmModelAdapter.java:407-419`｜`parseGeometry` 内做"取反 X / deg→rad"；验收：贴出变换代码与内部约定出处。
- `M-04`/`M-05`｜`model/ServerModelManager.java:135-140,148-235,297-310,46,126,143,312-321`｜内置模型复制目标改 `BUILT`（每 reload 前清空）或至少"不覆盖已存在文件"；实现 `processBlacklist`；**若改内置复制策略必须同步改 `AGENTS.md` 的 Model and Resource Rules 那句"the runtime reload path intentionally overwrites the built-in copies"**；验收：贴出改动与 `AGENTS.md` 的 diff。
- `M-06`（=`CU-06` 承接头）｜`util/ThreadTools.java:10-16`、`Config.java:29,80`｜`core=max=Math.max(1, Config.THREAD_COUNT)`（或 `newFixedThreadPool` + 有界队列 + 拒绝策略）；把池内 `Thread.sleep` 迁移出去（`RequestLoadModel.java:69`、`SyncModelInfo.java:62`、`OpenYsmModelSyncServer.java:313`、`ClientModelManager.java:339`）；验收：贴出线程池构造与每个 sleep 站点的去向。
- `M-07`｜`util/Md5Utils.java:8,10-24`｜per-call/`ThreadLocal`，或改用 classpath 上的 commons-codec `DigestUtils`；验收：贴出改动，并列出 8 个调用点不变（签名兼容）。
- `M-08`｜`util/DeflateUtil.java:27-41`、`data/EncryptTools.java:216-226`｜`needsInput()/needsDictionary()` 守卫 + 空转计数；`EncryptTools` 头 MD5 不一致时**直接失败**；验收：贴出两处改动。
- `M-09`｜`model/format/ModelCacheWriter.java:27-53`、`model/ServerModelManager.java:107-116`｜写前用 `verifyServerCache` 复用；`reloadPacks()` 末尾按本次登记清理 `CACHE_SERVER`（保留 `PASSWORD`）；验收：贴出复用判定与清理代码。
- `M-10`｜`model/format/ServerModelInfo.java:8-42`、`SendModelFile.java:49-56`、`SendModelFileChunk.java:133-142,152`、`ClientModelManager.java:348-354`、`SyncModelFiles.java:80-89`｜元数据侧提供"名字 == 内容 MD5"的校验口径（与 `[network]` 的 `N-07` 对接：一侧提供、一侧消费）；验收：贴出校验函数签名与消费点。
- `M-11`｜`rip/ysm/algorithms/YsmZstd.java:10-13`｜入口 `data.clone()` 或 javadoc 写明 mutates；验收：贴出改动。
- `M-12`｜`FolderFormat.java:26-28`、`YsmFormat.java:26`｜三扫描器统一 `walkFileTree` + `relativize`（可复用 `OpenYsmFormat.toModelName`），或改文档并在日志里 warn；验收：贴出改动或文档 diff。
- `M-13`｜`client/model/CustomPlayerModel.java:33-36`、`api/EntityAnimationApi.java:41-42`｜改走 `ModelIdUtil` + `Config.DEFAULT_MODEL_ID/TEXTURE`；验收：贴出两处改动。
- `M-14`｜`RawYsmModelAdapter.java:346-371`｜只回填到最后一个已定义槽（或写 count）；验收：贴出改动，并说明消费端长度语义。
- `M-16`｜`FolderFormat.java:75,136`、`YsmFormat.java:51,61`、`util/GetJarResources.java:24-26,33`、`util/ObjectStreamUtil.java:14,25`、`rip/ysm/security/YSMClientCache.java:59-61`、`util/YesModelUtils.java:76,188`、`data/EncryptTools.java:149,245`｜统一 `ysmu.LOG.warn`；验收：逐处贴出。
- `M-17`｜`YSMFolderDeserializer.java:509-511`、`RawYsmModelAdapter.java:511-519`｜`hold_on_last_frame` → `3`；验收：贴出改动；`M-15`/`M-20` **不改**（非目标，见 Decision Log）。
- `M-18`｜`util/YesModelUtils.java:47-62`、`model/format/YsmFormat.java:50`、`model/ServerModelManager.java:107-116`、`CommonProxy.java:20`、`command/YsmCommand.java:52`｜`data.length < 24` 前置守卫 + 长度上限校验 + `catch (Exception | LinkageError)` + `reloadPacks` 单模型失败不中断；验收：贴出守卫与 catch 改动。
- `M-19`｜`data/EncryptTools.java:148-151`、`model/format/ModelCacheWriter.java:27-37`、登记点三处｜`encryptModel` 失败抛异常 + `write` 加最小长度/完整性检查；验收：贴出改动。
- `M-21`｜`OpenYsmFormat.java:80-84`、`FolderFormat.java:63-66`、`ServerModelManager.java:143-145`｜`FolderFormat` 跳过带 `ysm.json` 的目录（或按 `Type` 优先级去重）；验收：贴出跳过判定。
- `M-22`（跨仓）｜`model/resource/pojo/RawYsmModel.java:17-18`、`ModelCacheWriter.java:55-65`、`YSMBinarySerializer.java:56-57`、`YSMBinaryDeserializer.java:857-876`｜本仓侧把 `soundFiles` 交付给引擎的声音管理器（对端见 `[engine]` 的 `E-10`）；验收：贴出交付点。
- `M-23`｜`ServerModelManager.java:69,120`、`OpenYsmFormat.java:5,73,102`、`ModelCacheWriter.java:55-65`、`ServerModelInfo.java:22-33`、`RequestServerModelInfo.java:45,72,98`｜删除 `RAW_MODEL_INFO`（改局部变量）；`serializeForOpenYsmSync` 不再就地改共享对象；`Info.size` 补来源或删列；验收：贴出改动，`Select-String` 确认 `RAW_MODEL_INFO` 0 命中。
- `M-24`｜`YSMBinaryDeserializer.java:33-42`、`OpenYsmFormat.java:115-116`｜对 format > 32 记明确 warn（含版本号）或按参考宽容解析；验收：贴出改动。
- `S-01`（含 network 侧）｜`ServerModelManager.java:68-70,118-122,77-83`、`SyncModelFiles.java:75`、`OpenYsmModelSyncServer.java:50`｜改 `ConcurrentHashMap` 或"构建新 Map 后整体替换引用"；验收：贴出字段与重建方式。
- `S-02`｜`rip/ysm/zstd/UnsafeUtil.java:29-43`、`OpenYsmFormat.java:90-120`｜catch 放宽到 `Exception | LinkageError`，让该类文件失败变成"跳过并 warn"；验收：贴出改动。
- `S-03`｜`YSMFolderDeserializer.java:66-67,959-970`｜改字段名或改内容为真 SHA-256（**注意与客户端/服务端的 hash 对协议耦合**，选一种并在注释里写明；若选改名，需确认 `ModelCacheWriter.java:67-76` 的 `getHashSource` 一致）；验收：贴出改动与协议影响分析。
- `S-04`｜`rip/ysm/security/YSMClientCache.java:19`｜记录级：注释说明与参考逐字节相同；验收：注释。
- `S-05`｜`util/RenderUtil.java:359-371,381-408`｜保存/清空/恢复 + `enableStandardItemLighting` 整体进 `try/finally`；验收：贴出改动后的方法骨架。
- `D-01`｜`network/message/SyncModelFiles.java:44-51,75-88`｜count 上限校验 + null md5 过滤；验收：贴出两处（与 `[network]` 的 `N-06` 协调，避免重复改同一段：**本项由 `[model]` 执行、`[network]` 只做收包解码侧**）。
- `D-02`｜`model/ServerModelManager.java:77-83`、`api/EntityModelApi.java:42-56`｜`hasModel` 先经 `ModelIdUtil.getInternalModelId` 规范化（或同时尝试两种形式）；验收：贴出改动。
- `D-03`｜`api/EntityAnimationApi.java:40-42`｜改走 `Config.DEFAULT_MODEL_ID`；验收：贴出改动。

### t-fix-render（YSMU）

- `R-01`｜`client/ClientModelManager.java:216-220,364-379`、`client/texture/OuterFileTexture.java:17-43`（全仓 `deleteTexture` 0 命中）｜在 `registerTexture(id, data)` 内先 `deleteTexture(id)` 再 `loadTexture`（幂等、单点），或 `clearRuntimeModelCaches` 前逐个释放（须在客户端线程）；验收：贴出改动，`Select-String deleteTexture` 命中数 > 0。
- `R-03`｜`client/compat/AngelicaCompat.java:34-41`、`mixin/MixinItemRenderer.java:23-27`｜区分"数据缺失"与"不透明"（记录 `translucentInfoAvailable`，缺数据时在 translucent pass 跳过一次，或打一次 debug 日志）；验收：贴出改动。
- `R-04`｜`client/renderer/layer/CustomPlayerItemInHandLayer.java:64-67,84-103`、`FirstPersonHandRenderer.java:237-238`｜两处 push/pop 进 `finally`；首人称收尾补纹理绑定恢复或写明契约注释；验收：贴出改动。
- `R-05`｜`client/renderer/CustomPlayerRenderer.java:74-79,163-176`、`client/entity/CustomPlayerEntity.java:104-111,191-193`｜`eep == null` 或 `getModelId() == null` 时早退/重置 model+texture（不得沿用上一实体）；注意与 `N-1` 的先后（先保证 texture 非 null）；验收：贴出改动与可达条件说明。
- `R-07`｜`client/texture/OuterFileTexture.java:27-43`｜header 预检尺寸上限（建议 4096）+ `data.length` 上限（建议 8 MiB），超限 warn 并渲染 missing texture；验收：贴出预检代码。
- `R-09`｜`FirstPersonHandRenderer.java:229,404,316,319,418,442-453`｜同一帧复用已解析的 bone（消除重复 `findRightArmBone`）；`ArmRollPivot` 的骨骼级缓存可选；验收：贴出改动并说明未做缓存的部分。
- `R-10`｜`client/model/CustomPlayerModel.java:37,97,106,112,109`｜`getCurrentModel()` 判空；字段本身保留并加"尚未接线"注释（相机高度接线 = 非目标）；验收：贴出判空代码与注释。
- `N-1`（崩溃级）｜`network/message/SetModelAndTexture.java:58-60` → `eep/ExtendedModelInfo.java:37-41,119-120` → `client/renderer/CustomPlayerRenderer.java:135-139,174` → `client/entity/CustomPlayerEntity.java:191-193` → `client/model/CustomPlayerModel.java:51-56`｜空串→null 改为"拒绝/移除"而不是存 null；`setModelAndTexture/setSelectTexture` 拒绝 null；`getTextureLocation` 补默认兜底；验收：贴出三处改动，并说明 `saveNBTData`（`ExtendedModelInfo.java:119-120`）不再可能 NPE。
- `N-4`｜`client/renderer/layer/CustomPlayerItemInHandLayer.java:55`｜`geoModel.properties` 判空；验收：贴出。
- `N-6`｜`data/EntityModelData.java:19-22`、`data/NPCData.java:78-84`｜构造器与 `put` 拒绝 null 分量；验收：贴出。
- `N-7`｜`client/renderer/CustomPlayerRenderer.java:138`｜复用/缓存 `EntityModelData` 或改为惰性构造；验收：贴出。
- `N-5`｜`client/ClientEventHandler.java:115-121`｜记录级：注释说明 `renderPartialTicks` 的来源与差异；验收：注释。
- `R-02`｜`client/upload/UploadManager.java:5-11`、`network/message/CompleteFeedback.java:22-25`｜**非目标**：仅加"未实现占位"注释；验收：注释（本条在 Progress 里已标注非目标，若实施者选择实现则须补断线取消与内容校验）。

### t-fix-animation（YSMU）

- `A-01`（high）｜`ConditionalHold.java:32,91-94`、`AnimationManager.java:220-225,252-254,389-400`｜空手返回 `hold_mainhand:empty`/`hold_offhand:empty`；去掉主手 predicate 的非空前置；给这两个名字加"模型里确实有该动画才播"的存在性检查（复用 `AnimationManager.hasAnimation:202-207`）；验收：贴出三处改动，`Select-String hold_mainhand:empty` 命中数 > 0。
- `A-02`（high）｜`ConditionalSwing.java:18-22,30-61`、`ConditionManager.java:11,26,50-52`、`AnimationManager.java:279-283,410-413`｜补副手前缀集 + `SWING_OFFHAND`/`getSwingOffhand`；回退名按手切换 `swing_hand`/`swing_offhand`；验收：贴出改动，`Select-String swing_offhand` 命中数 > 0。
- `A-03`｜`OpenYsmPlayerControllerRuntime.java:313-322,334-370,218-223`、`OpenYsmControllerExpressionEvaluator.java:82,424-430`｜`RuntimeState` 的读写委托引擎 `MolangPhysicsRuntime.get/setVariable`，键用 `"v." + name`（与引擎 `ScopedMolangVariable.getName()` 一致）；验收：贴出委托代码 + 说明键格式（必须与引擎一致）。
- `A-04`｜`OpenYsmControllerExpressionEvaluator.java:424-430`、`OpenYsmPlayerControllerRuntime.java:218-223`｜`"jump"` 特判改为"有存储值优先"；**必须与 `A-03` 同批**（顺序反了会退化成恒 0）；验收：贴出改动并说明与 `A-03` 的先后。
- `A-05`｜`client/animation/molang/QueryPositionDeltaFunction.java:8-10,31-33`、`AnimationRegister.java:79`｜按当前帧实体求值并删除静态字段；验收：贴出改动，`Select-String "static double dx"` 0 命中。
- `A-06①`｜`MolangInstructionExecutor.java:41-51,58-63`｜解析前做函数名诊断（一次性告警含语句与函数名）；验收：贴出诊断代码。
- `A-06③`｜`CtrlHoldFunction.java:12-14`、`OpenYsmControllerExpressionEvaluator.java:651-666`｜两侧共享同一实现（至少语义一致）；验收：贴出改动或说明为何保留两处并保证等价。
- `A-07`｜`ConditionManager.java:18-39`、`InnerClassify.java:31-71`、`OpenYsmControllerExpressionEvaluator.java:668-724`、内置资源 `use_mainhand:spyglass`｜"条件名但选不中"加一次性告警；`InnerClassify` 与求值器统一（spyglass/trident 别名、`#` 语义）；验收：贴出告警点与统一后的分类表。
- `A-11`｜`AnimationManager.java:40-41,269,296,323`｜提供 `public static void clearPlayerState()`（幂等，清两个进度表）；由 `[core-ui]` 的 `CU-10` 调用；验收：贴出方法签名与被调用点。
- `S-03`｜`AnimationManager.java:41`、`ConditionalHold.java:18`｜清理/更新遗留 TODO；验收：注释 diff。
- `S-04`｜`OpenYsmAnimationControllerRegistry.java:87-95`｜覆盖时 `warnOnce`；验收：贴出。
- `S-05`｜`ClientProxy.java:28-29`、`AnimationRegister.java:31,77`｜加一次性注册守卫；验收：贴出。
- `D-A1`｜`api/EntityAnimationApi.java:60-90`、`data/EntityClips.java:49-73`、`ClientEventHandler.java:222-230`｜给 `EntityClips` 加按当前世界实体集合裁剪（与 `NPCData.retainAll` 同形）或 TTL；验收：贴出裁剪入口。
- `D-A2`｜`client/entity/CustomPlayerEntity.java:117-124` vs `api/EntityAnimationApi.java:119-122,153-170`｜收敛为一份动画 id 解析；验收：贴出收敛后的单一入口与被替换的两处。
- `D-A3`｜`AnimationManager.java:389-400` vs `ConditionalHold.java:111-115`｜"物品 → 条件名"共享 helper；验收：贴出 helper 与两个调用点。
- `D-A5`｜`AnimationManager.java:202-207` vs `:374-379`｜`playIfPresent` 改用 `hasAnimation`；验收：贴出改动。
- `D-A6`｜`api/EntityAnimationApi.java:35-36,119-122,153-170`、`api/EntityModelApi.java:42-56`｜把"服务端调用无效"写进 javadoc（或拆包）；验收：javadoc diff。

### t-fix-engine（YSMU 宿主侧 + `E:\IDEA\Geckolib` 引擎仓库）

**执行顺序**：引擎侧本轮只剩 `E-05`（`GeoCube` guard），交付后由 captain/用户在引擎仓构建并换 jar，然后宿主侧的 `E-08`（可选的版本判定去重）才可能落地。宿主侧的 `MON-01`（接受 `1.14.0`）**不依赖**引擎侧，可以立刻做。`E-09`/`E-10` 是能力收口，可在 `E-05` 之后并行。**前提已核实**：引擎仓 `master` @ `1154d09` 可构建（captain 已构建，产物与本仓 `libs/` 逐字节相同），因此**不再有"先让引擎自洽编译"这个里程碑**。

- `E-05`（本轮引擎侧唯一【本次修复】）｜引擎仓 `E:\IDEA\Geckolib\src\main\java\software\bernie\geckolib3\geo\render\built\GeoCube.java`（`git show HEAD:` 的 478 行文本）`:104 UvFaces faces = uvUnion.faceUV;`、`:105 boolean isBoxUV = uvUnion.isBoxUV;` → 空 `uv` 直接 NPE；`:113 float textureHeight = properties.getTextureHeight()`（**注意**同文件 `:59` 已有 `properties == null \|\| getTextureHeight() == null ? 64F` 的守卫，所以 `createFromPojoCube` 是**部分加固**）｜修法：空 `uvUnion` / Box-UV 缺失 / 缺 `description` 一律**降级为 null quad**（`IGeoRenderer` 的 `quad == null` 会跳过），而不是抛 NPE；`faces == null` 时六个 `FaceUv` 全部取 null；`isBoxUV` 用 `uvUnion == null` 时的安全默认值｜验收：贴出改动后的 `:104-120` 区间（含守卫与新默认值分支），并用"`uvUnion == null` / `faceUV == null` / `properties == null` 三种输入各自走哪条分支"的语义论证说明它降级而不是抛出；**交付**：改完后由 captain/用户在引擎仓 `.\gradlew.bat build` 并把新的 `geckolib-*.jar` 放回本仓 `libs/`（旧 jar 已在 `tmp/backup-libs/`）。
- `E-11`（已完成，仅记录）｜captain 核实并构建：引擎 `master` @ `1154d09`，`git status` 只有 ` M .gitignore`；`.\gradlew.bat build --offline` → `BUILD SUCCESSFUL in 38s`；产物 `build/libs/geckolib-5.09.52.417-dev.jar`（2,339,441 B、SHA256 `142DF706…AB56`）与本仓 `libs/` 逐字节相同 → **无版本漂移，用户要求的"构建最新 jar"已完成**。
- `E-02`（**已核实满足，仅记录**）｜引擎 `Converter.java:29,:89` 的 `FAIL_ON_UNKNOWN_PROPERTIES=false` 仍在；文件已回到工作树（不再是 staged-delete）。**不补 BOM 容忍**（HEAD 无 `charAt` 处理）；若将来有模型包因 BOM 解析失败，再单独提一条。验收：引用 `Converter.java:89` 一行即可。
- `E-04`（**已核实引擎侧已具备，仅记录**）｜引擎 `RawGeometryTree` 的 `parseHierarchy` 已能处理旧布局（不再是被删状态）；如需把 NPE 换成可读 `IOException`，作为可选加固单列，不进本轮。
- `MON-01`（宿主侧，可立刻做，不依赖引擎）｜`src/main/java/com/fox/ysmu/client/ClientModelManager.java:125-126,150,162,170,177-180`（本地表 + "1.14.0 需要另一套布局解析"的注释 + `warnUnsupportedLayout`）｜把本地闸门改为**接受四个版本（含 `1.14.0`）**；同时删掉/改写那句"故意不放行 1.14.0"的注释（它现在是错的），把 `:150` 的 warn 收窄到真正的未知版本（`null` 或未来枚举外值）；验收：贴出改动后的判定与注释，`Select-String 1.14.0` 确认没有任何"拒绝"语义的残留。
- `E-08`（可选·去重）｜引擎 `FormatVersion.isSupportedLayout()` vs 宿主 `ClientModelManager.java:177-180` + `MON-01` 改完后的本地表｜引擎提供**单一**判定（`FormatVersion.isBuildableByThisPort()`），宿主删本地表改调用它；**两仓按"引擎先、换 jar 后宿主再改"的顺序**；**降级出口**：本轮引擎侧只做 `E-05` 时，本项记为"待引擎可改时再提"，保留 `MON-01` 的四版本放行，并把"两个仓库各有一份判定"记入残余风险与 `Outcomes & Retrospective`；验收：贴出引擎新签名的 javadoc + 宿主调用点，或贴出降级声明。
- `E-09`｜引擎 `molang/context/MolangSoundManagers.java:26`、`MolangForeignStorage.java:16`、`molang/MolangDebug.java:29`；本仓 0 命中｜决定每一项是"安装"还是"明确非目标"：安装则 YSMU 侧在 `ClientProxy.init` 调用（`IMolangSoundManager` 需要新建一个客户端实现）；验收：贴出安装点或非目标声明。
- `E-10`（`M-22` 对端）｜引擎声音管理器接口 + 本仓 `soundFiles` 数据流｜让 `sound_effects` 关键帧能出声（引擎侧播放器 + 宿主交付 `RawYsmModel.soundFiles`）；验收：贴出数据交付点与播放器注册点。
- `E-01`（**已核实满足，仅记录**）｜引擎 `FormatVersion.java:24-27` 四版本齐、注释明确"不重映射到 `VERSION_1_12_0`"；验收：引用行号即可。
- `E-06`（**无需动作**）｜jar 字节码 `JsonAnimationUtils.deserializeJsonToAnimation` 的 3 条 `java/lang/Exception` 异常表｜只记录"`A-06②` 已由引擎修复，本仓不要重复实现"；验收：报告里的引用。
- `E-07`｜引擎 `molang/builtin/QueryBinding.java:64`、`builtin/query/PositionDelta.java:10-26`；jar 的 `GeckoLibCache.parser` 仍是 legacy｜裁决：`A-05` 是否改成消费引擎现代运行时的 `position_delta`（若裁决为"本仓先修、引擎侧不动"，把结论写进 `E-07`）；验收：裁决文字 + 依据。
- `E-12`（后续项，**不进本轮**）｜引擎 `molang/binding/HostBindingContributor` + `molang/MolangHostBindings.setContributor/contributor()`｜仅在 YSMU 将来迁到现代 `molang/**` runtime 时才需要（当前动画文件 Molang 求值走 jar 里的 legacy `core.molang.MolangParser`，没有调用者）；验收：仅记录。

## Concrete Steps

以下命令一律从仓库根 `E:\IDEA\YesSteveModel-Unofficial` 执行；引擎侧命令的目录单独标出。**任何 Gradle 命令都不由代理人发起**，除收尾那一次 `compileJava`。

第 0 步（每个修复任务开始时）：确认基线与工作树。

    cd E:\IDEA\YesSteveModel-Unofficial
    git rev-parse --abbrev-ref HEAD
    git rev-parse HEAD
    git status --porcelain

期望：分支 `dev`、HEAD `43cc628891263ebece5a44c4971caa8dc4d3cd7a`；`git status --porcelain` 在本阶段开始时应只有 `?? .agent-teams/` 与 `?? tmp/audit-dev/` 之类的未跟踪项（若已有其它改动，先弄清是谁的）。

第 1 步（读计划与证据）：完整读本文件与 `tmp/audit-dev/delta.md`，再读与你任务相关的那份核验报告（`core-ui.md` / `network.md` / `model.md` / `render.md` / `animation.md` / `engine-side.md`）。不要只读条目摘要——每条证据的"为什么"都在报告里。

第 2 步（实施）：按 `Plan of Work` 改代码。一次只改一组相关条目。**不要**改 `dependencies.gradle`（唯一例外见第 5 步）、**不要**动 `tmp/Geckolib`（它既不是引擎仓、也不完整，改它没有任何效果）、**不要**新建/删除 `libs/*.jar`（替换 jar 是第 5 步的用户动作）。

第 3 步（静态自证）：对每条负责的条目在任务输出里给出：id、改动文件与行号、验证用的 `Select-String`/`git diff` 命令与结果、以及语义论证（为什么这个改动消掉了报告里的根因）。**不要**在任务中途跑 Gradle——包括引擎仓的构建。

第 4 步（引擎侧，仅 t-fix-engine；目录是 `E:\IDEA\Geckolib`，不是 `tmp/Geckolib`）：本轮只需补 `E-05`（`GeoCube` 的 `:104`/`:105` 裸解引用 → 降级为 null quad）。引擎已是可构建状态（`master` @ `1154d09`，captain 已构建验证），所以**没有"先让源树自洽"这个前置**。静态自证用 `git diff --stat` 与 `git show HEAD:<path>` 的前后文本对照。**宿主侧的 `MON-01`（接受 `1.14.0`）不依赖这一步，可以立刻做。**

第 5 步（跨仓交接，需要 captain/用户；只有本阶段确实改了引擎源码时才需要）：`E-05` 改完后，**由 captain/用户**在引擎仓库构建并把新的 `geckolib-*.jar` 交回本仓 `libs/`：

    cd E:\IDEA\Geckolib
    git status --porcelain         # 期望先看到已被处置的结果（不再有 18 个未处置的 D 行）
    .\gradlew.bat build            # 由用户执行；代理人不得代跑（captain 已在后台试过 --offline）
    # 把产出的 geckolib-<version>-dev.jar 覆盖到
    # E:\IDEA\YesSteveModel-Unofficial\libs\geckolib-5.09.52.417-dev.jar

期望：引擎构建成功；`libs/` 下的 jar 被替换为新版本；**替换前先备份旧 jar**；若文件名带版本号变化，需同步更新 `dependencies.gradle:21-23` 的三处 `files(...)`（**这是本阶段唯一允许触碰 `dependencies.gradle` 的情形**，且只在文件名确实变化时）。若引擎侧本轮只改了宿主侧（`MON-01`）而没有动引擎源码，则**本步整体跳过，不需要换 jar**。

第 6 步（收尾编译门槛，由集成/验证任务执行一次）：

    cd E:\IDEA\YesSteveModel-Unofficial
    .\gradlew.bat compileJava

期望：`BUILD SUCCESSFUL`。若失败，把错误贴回对应任务；**不要**在修复任务里自己跑它。

第 7 步（用户最终验收）：

    .\gradlew.bat build
    .\gradlew.bat runClient

期望见 `Validation and Acceptance` 的 12 个场景。

## Validation and Acceptance

**验证纪律（来自新 `AGENTS.md`，本阶段必须遵守）**：(a) 不再使用 `tools/jcheck.ps1` 式的 classpath 拼装检查——它扫 Gradle 缓存、慢且会把多个版本的 jar 混在一起产生误导性错误；`tmp/phase6-artifacts/jcheck.ps1` 是历史产物，不要复活；`E:\IDEA\Geckolib` 里那份同样不可用（硬编码工作区外路径 + 只按中文 `错误:` 计数）。(b) 各修复任务的验收**只能是静态证据**：`文件:行` + 语义论证 + 可复跑的检索命令输出。(c) 唯一的编译门槛是全部改动结束后的**一次** `.\gradlew.bat compileJava`，由集成/验证任务执行；随后由用户执行 `.\gradlew.bat build` 与 `runClient`。构建独占工作树，任务中途跑它会与他人冲突或丢工作。

**静态验收清单（按任务，逐条对应 Progress 的条目）**：每个任务必须提交一份"id → 改动文件:行 → 验证命令与输出 → 语义论证"的表。特别注意三处最容易"看起来修好了其实没修好"的地方：

1. `N-01`：`sendPassword` 里**不能**再有 `THREAD_POOL.submit`（必须同线程内联）。
2. `N-02`：去重必须落在两条通道的**共同汇合点**（`ClientModelManager.registerAll`），并在任务输出里说明签名来源与"两条通道各调一次"如何被拦掉。
3. `M-01`：门控谓词**不能**是 `isBridgeable`（它对文件夹模型恒 true），必须能对文件夹模型返回"不可桥接"。

**用户侧 12 个场景（`runClient`，按优先级排列）**：

1. `CU-01`：游戏内按 `Z` 打开轮盘、点一个动画 → 聊天栏出现 `...Play 'extra0' animation`，**不崩溃**。
2. `CU-03`：主菜单按 `Alt+Y` → 不崩溃（什么都不发生或给出提示）。
3. `CU-04`：聊天框里输入含 `z` 的句子 → 不弹出轮盘界面；容器界面里按 `Z` → 容器不被顶掉。
4. `CU-02`：输入 `/ysm reload` → 聊天栏出现完成消息与耗时（不再是"未知错误"）。
5. `N-1`：把一个模型设成"无贴图"（或让 GUI 传空串）→ 渲染时不崩溃。
6. `M-18`：往 `config/ysmu/custom` 放一个 0 字节/2 字节的 `.ysm` → 服务器/客户端仍能启动，日志有 warn。
7. `A-01`/`A-02`：用带 `hold_mainhand:empty` / `swing_offhand` 的第三方包 → 空手站姿动画能播、副手挥动走副手表。
8. `M-01`/`M-02`：用带 `ysm.json` 的文件夹模型 → 若本阶段未完成烘焙，17 通道应**跳过并 warn**而不是下发坏缓存；legacy 通道模型可用。
9. `E-02`/`E-04`/`E-05`/`MON-01`：放一个带未知字段、声明 `1.8.0`、声明 `1.14.0`、以及缺 `uv` 的几何文档 → 都不再整份消失（未知字段被忽略；`1.8.0` 与 **`1.14.0`** 都进入几何构建、`ClientModelManager` 不再打印 "not supported"；缺 `uv` 降级而不是 NPE）。
10. `M-06`/`N-12`：多人/多模型同步时观察不再"一个模型卡 20 秒串行"（日志时间戳）；线程池实际并发 > 1（可在任务输出里用静态证据 + 用户运行期观感共同确认）。
11. `N-07`：中断一次分块下载（关客户端）后重进 → 半成品不再被当成命中（日志显示重新下载）。
12. `M-09`：反复 `/ysm reload` 后 `config/ysmu/cache/server` 的文件数不无限增长。

**引擎侧验收**：`E-11` 已完成并验证（引擎 `master` @ `1154d09` 可构建，产物与本仓 `libs/` 逐字节相同、旧 jar 已备份）；本轮引擎侧只剩 `E-05`：改完后由 captain/用户在引擎仓 `.\gradlew.bat build`（`--offline` 亦可）并把新的 `geckolib-*.jar` 放回本仓 `libs/`，验收以"用户给出构建成功与产物文件名 + 本仓 `libs/` 已替换（或确认内容未变）"为准；`E-02`（Converter 的 `FAIL_ON_UNKNOWN_PROPERTIES=false`）与 `E-04`（`RawGeometryTree`）**已核实满足，仅引用行号**；`E-08` 若本轮不做，按降级出口记为"待引擎可改时再提"；`MON-01` 的成败以场景 9 为准且**不依赖**引擎侧。

## Idempotence and Recovery

每条修复都是就地、可重复的：改的是既有 Java/资源文件，重复执行同一改动不会累积副作用。回滚依据是 `git diff`——每个任务开始前确认工作树状态（第 0 步），失败时用 `git checkout -- <path>` **逐文件**回滚，不要整树 `git reset`（会把并行任务的改动一起丢掉）。

**危险的改动有两处。** 第一处是 `M-04`（改变内置模型写 `config/ysmu/custom` 的策略）：它会改变玩家已有的模型目录内容，实施时必须①确认 `copyBuiltInModels` 的目标与 `custom` 的关系；②保留"若 `custom` 下已有同名内置模型则不动它"的语义或给出明确迁移说明；③**同步更新 `AGENTS.md` 的 Model and Resource Rules 那句话**（它现在写着"intentionally overwrites the built-in copies"）。若三点无法同时满足，只实现 `processBlacklist` 与"不覆盖已存在文件"这一半，并在 `Outcomes & Retrospective` 记录另一半被推迟。第二处是 `E-11`（引擎源树与出货 jar 对齐）：在提交之前，引擎仓处于"源树编不过"的状态，任何引擎改动都可能被下一次 `git checkout`/`git restore` 冲掉——**先把对齐结果提交再改功能**。

**跨仓回滚**：若引擎侧改动导致 `compileJava` 失败，恢复方式是"把 `libs/` 下的旧 jar 放回 + 回滚 YSMU 侧对应的宿主编译依赖"。因此**在替换 `libs/*.jar` 之前，先由用户把旧 jar 备份**（例如 `libs/geckolib-5.09.52.417-dev.jar.bak`，并在收尾时删掉这个备份以免被打包/提交）。

**环境保持干净**：本任务的临时产物放在 `tmp/audit-dev/`；探针/快照目录在任务结束前删除；确认 `git status --porcelain` 只列出本阶段允许改动的路径；不要往 `build/` 写入任何工作区文件。

## Artifacts and Notes

**引擎基线记录（本阶段起点，必须原样保留）**：

    # tmp/Geckolib —— 既不是符号链接、也不是引擎仓，只是一份不完整拷贝（缺 Converter/GeoCube/AnimationProcessor）
    # 它在引擎仓未被跟踪且被 .gitignore:11 的 /tmp/ 忽略，不要把它写进任何 inScope
    git -C tmp/Geckolib rev-parse HEAD             -> 43cc628891263ebece5a44c4971caa8dc4d3cd7a
    git -C tmp/Geckolib status --porcelain         -> ?? .agent-teams/
    git -C tmp/Geckolib rev-parse --show-toplevel  -> E:/IDEA/YesSteveModel-Unofficial   # 即本仓 dev

    # 真正的引擎仓库（引擎侧改动的基线）—— captain 已核实并可构建
    路径            E:\IDEA\Geckolib   (git rev-parse --show-toplevel = E:/IDEA/Geckolib)
    分支/HEAD       master / 1154d090f337c5960a787c21571575681d5594bb
    git status      只有 " M .gitignore"  → 可作固定基线
    构建            .\gradlew.bat build --offline -> BUILD SUCCESSFUL in 38s
    产物            build/libs/geckolib-5.09.52.417-dev.jar
                    2,339,441 B, SHA256 142DF706C8B117A63FB97C546D9D34C7EE2CD3A6CE8AB24E018C3AAFCC04AB56
    与本仓 libs/    逐字节相同（同尺寸同 SHA256）→ 当前无版本漂移；旧 jar 备份在 tmp/backup-libs/
    过时前提        t27 报告里的 "18 个暂存删除 / 源树无法自洽编译" 属分支 clean-job 的半成品，
                    随分支切换已解除；以本条为准

**本仓基线记录**：

    git rev-parse --abbrev-ref HEAD -> dev
    git rev-parse HEAD              -> 43cc628891263ebece5a44c4971caa8dc4d3cd7a
    src/main/java 下 .java 数        -> 180（com/fox/ysmu 134 + rip/ysm 46）；+ src/test 4 = 184
    libs/geckolib-5.09.52.417-dev.jar -> 存在；dependencies.gradle:21-23 引用三次

**六份核验报告的关键统计（供收尾对照）**：`core-ui.md` 21 条中 20 条仍存在 + 2 条新增；`network.md` 12 条仍存在 + `N-09` 收窄 + 4 条新增；`model.md` 29/29 仍存在 + 5 条新增；`render.md` 1 条已修 + 1 条 NPC 侧已修 + 9 条仍存在 + 5 条新增；`animation.md` 15 条仍存在 + 4 条转移到引擎 + 2 条作废 + 6 条新增；`engine-side.md` 桶(a) 11 项作废 / 桶(b) 7 项引擎现状 / 桶(c) 8 项本仓现状 + 引擎待办 9 项。

**可以复用的静态检索（不需要 Gradle）**：

    # 语言串占位符（CU-01/CU-02）
    Select-String -Path src/main/resources/assets/ysmu/lang/*.lang -Encoding UTF8 -Pattern '%d|%\.2f'
    # 顺序不变量的实际发送点（N-01）
    Select-String -Path src/main/java/com/fox/ysmu/network/message/SyncModelFiles.java -Pattern 'THREAD_POOL|sendToClientPlayer'
    # 纹理释放是否真的出现（R-01）
    Select-String -Path (Get-ChildItem src/main/java -Recurse -File -Filter *.java).FullName -Pattern 'deleteTexture'
    # 空手条件名字面量（A-01）
    Select-String -Path (Get-ChildItem src/main/java -Recurse -File -Filter *.java).FullName -Pattern 'hold_mainhand:empty|hold_offhand:empty|swing_offhand'
    # 线程池构造（M-06）
    Get-Content src/main/java/com/fox/ysmu/util/ThreadTools.java -Encoding UTF8

## Interfaces and Dependencies

**三条来自新 `AGENTS.md` 的硬约束（本阶段不得违反）**：

1. **GeckoLib、YSMU、Touhou Little Maid 是同一所有者的一方项目，三者都可以随时修改，接口可以开放。** 一个功能不得因为最干净的实现位于另一个仓库就被阻塞、简化、做半截或重复实现。本阶段的具体落点：引擎格式容忍与 guard 落在 `E:\IDEA\Geckolib`（`t-fix-engine`）；宿主契约的安装落在 YSMU（`ClientProxy.init`）；`sound_effects` 出声需要两仓各做一半（`E-10` + `M-22`）。
2. **必须守住的运行时性质：各 mod 在没有彼此的情况下仍能加载运行。** `geckolib` 是例外——它是 YSMU 的引擎，YSMU 声明 `required-after:geckolib`，因此引擎侧改动不需要为"没装 geckolib"做降级。YSMU 与 Touhou Little Maid 之间保持可选：TLM 相关的入口（`EntityAnimationApi`/`EntityModelApi`/`ModelGuiApi` 的非玩家路径）必须继续在 TLM 缺失时干净退化（现状是 mod-id 探测 + 反射桥；`EntityAnimationApi.java:35-36` 的"common-side safe"承诺属于这条性质，`D-A6` 修的是它的文档）。
3. **同一份表/规则/清单不得在两个仓库各存一份，发现即记为缺陷。** 本阶段命中三处，都必须合并成一份：`E-08`（"哪些版本可构建"：引擎 `FormatVersion.isSupportedLayout()` vs 宿主 `ClientModelManager.java:177-180`）、`D-A2`（"动画 id 解析"：`CustomPlayerEntity.java:117-124` vs `EntityAnimationApi.java:119-122,153-170`）、`D-A3`（"物品 → 条件名"：`AnimationManager.java:389-400` vs `ConditionalHold.java:111-115`）。合并方向由"更干净的那一侧"决定，并写进各自的验证证据。

**跨仓工作流（本阶段的执行骨架，顺序不可颠倒）**：

    引擎侧源码改动（t-fix-engine，改 E:\IDEA\Geckolib）
      -> 引擎仓库提交（固定基线）
      -> 【用户】在 E:\IDEA\Geckolib 跑 .\gradlew.bat build，产出新的 geckolib-*-dev.jar
      -> 【用户】把新 jar 覆盖回 libs/geckolib-5.09.52.417-dev.jar（先备份旧 jar）
      -> 宿主侧配合改动（E-08 的宿主半边、E-09 的安装点、M-22 的数据交付）
      -> 【集成/验证任务】在本仓跑一次 .\gradlew.bat compileJava
      -> 【用户】.\gradlew.bat build 与 runClient，按 12 个场景验收

哪些步骤必须由用户执行：**引擎仓库的 `gradlew build`、jar 替换、以及本仓的最终 `build`/`runClient`**（新 `AGENTS.md`：构建独占工作树，只能在全部改动结束、没有其它人在工作时跑；且引擎仓库在工作区之外，代理人不应在任务中途驱动它）。哪些不需要：如果本阶段只改 YSMU 侧（例如 `E-03` 未完成、引擎侧全部推迟），则**不需要换 jar**，宿主侧的编译依赖仍是现有 `libs/geckolib-5.09.52.417-dev.jar`。

**必须存在或保持稳定的类型与签名（本阶段结束时）**：

- 引擎：`software.bernie.geckolib3.geo.raw.pojo.FormatVersion` 保持四个常量 + `forValue` 各自映射 + 一个**单一**的"本移植可构建"判定（`E-08` 决定它叫什么；若沿用 `isSupportedLayout()`，必须把语义写清"1.14.0 需要另一套布局解析"）。
- 引擎：`Converter.fromJsonString(String)` 继续忽略未建模字段；`RawGeometryTree.parseHierarchy(RawGeoModel)` 以可读 `IOException` 报告"没有 `minecraft:geometry` 数组"；`GeoCube` 对空 uv / 缺 description 降级而不是 NPE。
- 宿主：`com.fox.ysmu.client.entity.CustomPlayerEntity` 继续 `implements IMolangPhysicsScope` 并保持三个键方法的语义（引擎 `MolangPhysicsRuntime.begin` 依赖它）。
- 宿主：`com.fox.ysmu.client.animation.AnimationManager` 提供 `public static void clearPlayerState()`（`A-11`，供 `CU-10` 调用）。
- 宿主：`com.fox.ysmu.client.ClientModelManager.registerAll(...)` 的幂等去重契约要写清"什么算同一模型"（`N-02`），且 `registerTexture` 负责释放旧纹理（`R-01`）。
- 宿主：`network/NetworkHandler.java` 的 packet id 不得重编号（新增必 bump `NETWORK_PROTOCOL`，`N-11`/`NF-01` 若加包必须遵守）。
- 依赖：不新增第三方库；不改 `dependencies.gradle`（**唯一例外**：`libs/` 下 jar 文件名变化时同步 `:21-23` 三处）；`libs/geckolib-5.09.52.417-dev.jar` 由用户替换。
- 约束：仍面向 JVM 8（不得用 Java 9+ 库 API；Jabel 语法允许）；新增用户可见文本同步两份 lang；`ModelIdUtil` 的 helper 优先于手拼 id。

## 修订记录

- 2026-09-29 / synthesizer：创建本 ExecPlan（phase7）。它把 `tmp/audit-dev/delta.md` 的本仓去重条目 91 条与【已转移到 Geckolib 引擎侧】10 条（`E-01`..`E-10`）作为唯一修复清单，并按 dev 的实际文件所有权重新划分归属（phase6 归 `[engine]` 的 `MON-07`/`MON-09`/`MON-10`/`MON-11`/`MON-12` 回到本仓的 `[render]`/`[network]`/`[model]`，引擎格式容忍改由引擎仓库的 `t-fix-engine` 承接）。记录了三处前提修正（`tmp/Geckolib` 不是符号链接；引擎仓库是 `E:\IDEA\Geckolib`；引擎源树与 jar 不同步），并把 `E-09`/`E-10` 从 phase6 的"非目标"升级为本次修复（理由见 Decision Log）。后续任何修改都必须在本节追加说明与理由。
- 2026-09-29 / synthesizer（据 captain 两条更正）：(1) **引擎侧目标收窄为 `E-05`（`GeoCube` 的 `:104`/`:105` 裸解引用）**——captain 已核实 `E:\IDEA\Geckolib` 在 `master` @ `1154d09` 上可构建（`build --offline` → `BUILD SUCCESSFUL in 38s`），产物 `build/libs/geckolib-5.09.52.417-dev.jar`（2,339,441 B、SHA256 `142DF706…AB56`）与本仓 `libs/` 逐字节相同，故 `E-11` 勾选为已完成、**删除了"先让引擎自洽编译"的里程碑**，`E-02`/`E-04` 降级为"已核实满足，仅记录"，`E-08`（版本判定单一来源）降为可选并给出降级出口，新增 `E-12`（`HostBindingContributor`）为后续项。`t27`/`t26` 里"18 个暂存删除、源树编不过"被标注为过时前提（属分支 `clean-job`）。(2) **`MON-01` 改为【本次修复】：`ClientModelManager` 的版本闸门接受四版本（含 `1.14.0`）**，用户裁定 dev 现有的"故意拒绝 1.14.0"是错的（引擎 `FormatVersion.isSupportedLayout()` 对 1.14.0 返回 true、几何构建器不读版本），归属 `fix-engine`、落点本仓 `src/main/java/com/fox/ysmu/client/ClientModelManager.java`。
