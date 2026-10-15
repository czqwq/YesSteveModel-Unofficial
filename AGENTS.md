### AGENTS.md
项目概览

YSMU 是一个 Minecraft Forge 1.7.10 模组，将 Yes Steve Model/OpenYSM 风格的玩家模型移植回 1.7.10。模组 id 为 ysmu，根包为 com.fox.ysmu，Forge 入口点为 src/main/java/com/fox/ysmu/ysmu.java。

构建通过 settings.gradle.kts 和 build.gradle.kts 使用 GTNH Gradle 约定插件。gradle.properties 目标为 Minecraft 1.7.10、Forge 10.13.4.1614、MCP stable 12，启用 Mixins，并通过 JVM Downgrader 启用现代 Java（enableModernJavaSyntax = jvmDowngrader，downgradeTargetVersion = 8），因此源码可使用 Java 9+ 语法和标准库，而 jar 仍携带 JVM 8 类文件。运行时/开发依赖在 dependencies.gradle 中声明。GeckoLib 3 动画引擎已内置于本仓库，位于 src/main/java/{software,com/eliotlash,net/geckominecraft}，作为一次性源码快照；它不是外部模组，也不在 libs/ 中。参见 PROVENANCE.md。

当前工作重点是移植 OpenYSM。应仔细分析 OpenYSM 代码，并严格遵循其实现。

上游保真
当上游已实现某功能时，按上游方式实现。不要为上游已解决的问题自行发明机制。

参考为 tmp/YesSteveModel-dev-1.20。更改行为前先阅读，并在提交、ExecPlan 条目或代码注释中引用所依据的上游文件与行号。仅以“看起来合理”为理由的更改不算完成。

先找到上游机制。 对于行为更改，定位上游对同一问题的处理——通常是一个类加少量几行——并移植其形态，包括数据缺失时的回退方案。

上游没有的就不要发明。 如果上游没有对应实现（例如 YSMU 面向宿主的 EntityModelRenderApi，上游没有），不要发明新谓词或新语义。将差异记录为发现，交由所有者裁决；本地发明的机制即使能工作也是缺陷。

移植特有的新增需要移植特有的理由——1.7.10 限制，或移植自身的架构（世界、HUD 叠加层和 GUI 共用一个渲染器）。说明是哪种，并尽量保持新增最小。

绝不添加上游不保存的状态来绕开时序差异。如果值尚不可用，上游形态的答案是：通过拥有它的结构解析（上游在已加载的模型内解析纹理名称），或作为显式覆盖传递，或等待。用侧边集合跟踪“我已经做了什么”并据此门控，已在此处产生过一次已发布的回归：一个纹理 id 集合拒绝了玩家自己的 Minecraft 皮肤用于内置 steve/alex 模型，而上游通过将皮肤作为渲染事件覆盖交给渲染器来绘制。该恢复当时只做了一半，且已按上游补齐：覆盖确实走事件，但判定按 id 末段比 `steve`/`alex`，而内置模型的 id 是相对路径的 hex 编码（`misc/2_steve` → `ysmu:_name_6d6973632f325f7374657665`），因此恒不命中，两个模型一直画包内皮肤；现在按上游 `VanillaPlayerRenderEvent.java:19-22,44-46` 比解码后的整个模型路径（另保留旧安装的裸名 `steve`/`alex`），且监听器只做那一个覆盖赋值。见 .agent/phase43-builtin-skin-and-default-texture.md。

不要复制上游的 bug。 引用行为，而非笔误——例如上游在发布渲染事件之前读取纹理覆盖，因此其自身字段从未看到监听器设置的内容；移植在之后读取。同一规则适用于参考分支（tmp/YesSteveModel-Unofficial-perf-previewUI），所有者长期指令要求逐字复制：它是完成的移植，而非正确的移植，且带有上游没有的缺陷。其中一个已实测——其 com/eliotlash/mclib/math/functions/utility/DieRoll.java:26-28（及 DieRollInteger.java）从不递增掷骰计数器，且乘以 getArg(2) - getArg(2)，即零，因此调用 math.die_roll 的包会挂起客户端线程，而上游自己的 .../geckolib3/core/molang/builtin/math/DieRoll.java:14-30 是正确的。逐字复制会引入该挂起。所以可自由复制参考的结构和注释，但在保留前，对照上游检查每个循环、每个除数和每个提前返回，并记录你拒绝复制的内容。

已知与上游的差异
此处移植对同一问题的回答与 tmp/YesSteveModel-dev-1.20 不同的每一处。每处要么已解决（说明方式），要么等待所有者。列在此处不是发明第三种答案的许可：选择上游形态，或记录为何 1.7.10 或移植自身架构无法做到。

Molang 变量名不区分大小写。 上游通过 MolangNames.identifier（api/molang/MolangNames.java:29-35，toLowerCase(Locale.ROOT)）规范化每个标识符。移植现在在一条规则 MolangParser.canonicalVariableName 中做同样的事，由解析器自身的注册表、RemoteAnimationVariables.normalize（网络路径）以及物理作用域的变量表使用。此前，包的 v.roaming.C 按原样存储但查找时小写，因此读作 0，每个 v.roaming.C==0?... 都走第一分支：包自身的设置开关静默失效。已解决——见 .agent/phase18-glow-and-roaming-case.md。

逐骨骼发光、透明度和颜色。 上游将其保存在每骨骼 14 个浮点的属性数组中（geckolib3/model/AnimatedGeoBone.java:9-16，打包于 :201-211），并在其原生渲染器中消费（natives/render/NativeModelState.java:57-66）。移植在 GeoBone 上以相同打包方式携带相同值，并在 MolangParser 中注册相同的三个名称（bone_color、bone_transparency、bone_glow），但用固定功能管线绘制：发光等级 >= 0 时骨骼无光照渲染，因为 1.7.10 没有逐骨骼光照图。等级保留在骨骼上，以便日后可对照上游而非猜测添加亮度映射。因 1.7.10 限制而属移植特有。

客户端的纹理阶梯没有模型自身声明默认值的层级。 上游在已加载的模型内解析纹理——请求名称，然后 settings.defaultTexture（RawModelAssembler:189-196 在包未声明时用第一个纹理填充），然后第一个条目（format/schema/model/ModelManifestLookup.java:10-29）。移植解析并同步同一字段（YSMFolderDeserializer:170 和 :1031、RawYsmModel:210、YSMBinarySerializer:483 / YSMBinaryDeserializer:611），但客户端阶梯在该层放了全局 Config.DEFAULT_MODEL_TEXTURE（CustomPlayerModel.textureFor），因此仅对声明了 default_texture 的包有差异。已解决：引擎的 ModelProperties 新增 defaultTexture（ysm_default_texture），RawYsmModelAdapter 将其注入 ysm_render_layers_first 旁，ClientModelManager.DECLARED_DEFAULT_TEXTURES 以与 RENDER_LAYERS_FIRST 相同的卫生方式携带它，CustomPlayerModel.resolveTexture 在选中项与常量之间使用它，并像上游的 containsTexture 一样对照模型自身纹理列表验证。Config.DEFAULT_MODEL_TEXTURE 保持其确切角色。ResolveTextureLadderTest 守护它。

内置兜底模型（default）的贴图不随连接级模型列表释放。 上游的兜底不属连接目录：ClientModelRenderTargetManager 的 defaultInitialization / requireAvailable（:620-632）把内置 default 的资源初始化一次并要求整会话可用，目录级重置只换目录，它从不被释放。移植的模型列表清理（ClientModelManager.clearRuntimeModelCaches → ModelTextureRegistry.clear）却把内置 default 也一并交还上游的延迟释放，而 CustomPlayerModel.textureFor 对每个尚未发布的模型按常量交出的正是这张贴图（渲染器此时画的也正是内置 default 的几何）：一旦没有第二次同步把它注册回来，10 s 后该 id 就从平台纹理表里消失，下一次绑定由 TextureManager.bindTexture 新建 SimpleTexture 去资源包查（模型纹理永远查不到），打一条 Failed to load texture: ysmu:default/default.png 并把该 id 永久指向全局缺失贴图。已解决：ModelTextureRegistry.clear() 跳过 isDefaultModelId 的条目（同一判定已用于资产回收对 default 几何/动画的钉住，AssetManager:184-202），并且显存计数改为按存活条目重建，常驻字节不会从预算里消失。ModelTextureRegistryTest 固定两半。见 .agent/phase43-builtin-skin-and-default-texture.md。

两个同步通道可投递同一文件夹模型。 上游同步一个通道。移植有 legacy 和 OpenYSM 两个通道，对文件夹模型它们序列化方式不同——烘焙侧重新发出美化打印的 JSON，包的浮点扩宽为 double，即 3.0 MB 几何对 wine_fox/05_magical 的 9.0 MB，六个纹理字节相同。重复检查对这些字节哈希，因此从未匹配：每个 wine_fox/* 模型每会话注册六次，而每个 .ysm 包三次，每次重新注册会停放几何、丢弃就绪标记和已安装动画，使模型约一秒内绘制内置默认——即闪烁。已解决：每个模型一个通道——SyncModelFiles 不再向 OpenYSM 索引携带的模型提供，且仅在 Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL 开启时，因此内置模型和 OpenYsmFormat 无法生成载荷的模型保持仅 legacy。CACHE_NAME_INFO 两种方式都保留条目——它也是阻止 legacy 文件夹扫描器重新扫描 OpenYSM 扫描器已注册目录的注册表。

渲染状态在绘制前作为类型选择，如同上游选择 RenderType。 上游有一个入口 IGeoRenderer#getRenderType(texture, visible, glowing, translucent)（geckolib3/geo/IGeoRenderer.java:33-39），当模型有半透明顶点时返回 CustomTranslucentRenderType——包装 RenderType.entityTranslucent，即混合与剔除——否则返回 RenderType.entityCutoutNoCull（alpha 测试、无混合、无剔除），并将该类型作为参数交给绘制（:18-26）。移植形态相同：YsmRenderType 是 1.7.10 中 RenderType 的替代（平台没有），IGeoRenderer#getRenderType 携带上游的名称、参数、默认值和两个分支，render(model, animatable, type, ...) 应用类型而非决定。移植不添加 getRenderType 覆盖，因为引擎的默认实现就是上游规则。hasTranslucentVertices 是上游在 GeoModelState:73-74 的名称；上游读取原生顶点数，移植用宿主对即将绑定纹理的回答替代——与上游烘焙相同的输入——并在 javadoc 中说明。第一人称手臂不经过 render，因此 FirstPersonHandRenderer 向 getRenderType 问与世界路径相同的问题——可见、不发光、手臂自身 animatable 的 hasTranslucentVertices——并在 prepareCustomArmState 中应用该类型状态，遵循 render 自身规则（IGeoRenderer.java:77-95）。角色在镜像手臂矩阵周围应用该状态，之后重置 glFrontFace(GL11.GL_CCW)，因为镜像使模型自身正面成为背面（CustomPlayerItemInHandLayer.java:107 对镜像手持物品是相同修正）。
值得保留的历史：手臂曾硬编码上游的手臂答案 CustomTranslucentRenderType——上游 CustomFirstPersonArmRenderer:39 确实无条件使用，但在 1.20 中通过移交的类型自行应用，而在 1.7.10 中必须手动应用。提交 67f8044 将该状态写为 disableAlpha() + enableCull()：对不透明模型，这与世界路径绘制相同几何所用的 entityCutoutNoCull 相反，因此 alpha-0 纹素未被丢弃（远面未光照内侧的黑色斑块），剔除又去掉了平面或单层片的一个面（透明孔洞）。wine_fox/05_magical 平面贴花的灰色单纹素背面曾为剔除而加，现由夹紧纹理上传处理（见下方纹理环绕条目），而非手臂剔除状态。
记录两个缺口而非发明：1.7.10 无轮廓通道，因此发光分支不绘制（getRenderType 返回 null）；引擎在零厚度立方体上保留移植特有的 0.001 膨胀，上游没有（GeoBuilder.java:100-116），因为移除会使平面贴花两面在 cutout-no-cull 分支上完全共面并 z-fight。all_cutout 是上游的 forceCulling（format/parser/pojo/manifest/settings/ModelProperties.java:35-36）；移植已解析并同步它（RawYsmModel:214、YSMFolderDeserializer:174、YSMBinarySerializer:491 / YSMBinaryDeserializer:620），现将其作为 ysm_all_cutout 带到客户端至引擎的 ModelProperties.forceCulling。现由 MaterialDrawSession 中纹理拥有的逐面 alpha 分区和裁剪平面/绕序 CPU 朝向消费，包括直接手臂入口。连续绘制段联合排序其半透明面；层、任意回调和嵌套绘制是排空边界。无 CPU 像素的外部纹理保留旧渲染器回退。**1.7.10 侧已实测的第一处偏差（phase45）：镜像绘制不做 CPU 剔除。** 移植在两处镜像模型——第一人称手臂（FirstPersonHandRenderer#alignGeckoArmToVanillaBipedArm，glScalef(1,-1,1)）与 HUD 纸娃娃/开界面时的世界绘制（RenderUtil#renderPlayerEntity，glScalef(-scale,scale,scale)，与原版 GuiInventory.func_147046_a:94 同写法）——而原生渲染器的方向符号是**逐骨骼**累积的（pose_stack_impl.inc:53-60 翻转 tangent_orientation，render_state_impl.inc:55-62 乘进 facing 系数），它的外层矩阵永不镜像（上游第一人称手臂用偶镜像 scale(-1,-1,1)），所以原生规则在被镜像的链上必然判反：cofactor 的值随 clip 行列式变号，于是模型靠近玩家的面被剔除、只剩内壁。移植的镜像夹在链条中间（平移→镜像→旋转），段内无法撤销，因此改由 MaterialFacing#culled 在这类绘制上**不剔除**（对闭合模型与正确剔除同一画面：远侧面被深度测试挡在后面，且该段本来就用 drain() 关掉 GL 剔除），非镜像绘制的行为一字未变。触发面只有六面全不透明（CUTOUT）或 all_cutout 的立方体，这解释了"只有手臂有洞、身体正常"。见 .agent/phase45-mirrored-draw-face-culling.md。可用原生源（tmp/YesSteveModel-Native-dev/modules/gfx/src/bake/baked_model.cc:685-734）分类每个面的 UV 像素并为六面立方体选择剔除分区；半透明面保持半透明。绘制时朝向使用变换后的面平面和绕序。无条件 GL 剔除开关不是该机制。见 .agent/task28-material-consumers.md。
值得保留的历史：早前尝试在 GeoCube 内丢弃退化面，作用于所有模型共享的几何，破坏了正确渲染的模型；已回退。不要让此类谓词作用于共享几何，也不要添加上游没有的方法——临时 shouldCullBackFaces 因此被删除。
两个映射通过阅读而非猜测确定。上游的 RenderFirstPlayerBackground 是第一人称手臂的背景通道，不是 HUD 叠加层；此处对应 FirstPersonHandRenderer，它应用该通道状态。HUD 额外玩家叠加层（ClientEventHandler#renderSelfGuiPlayer）是移植特有——上游无对应——因此走 doRender 并取与世界相同的逐模型答案。上游第三个 geo 渲染器 GeoProjectilesRenderer 在此引擎无对应，因此没有第三处需要询问类型。

模型纹理以夹紧方式上传，而非重复。 上游依赖平台默认：其纹理经 1.20 的 TextureManager，采样器为 CLAMP_TO_EDGE。移植的 OuterFileTexture 传入 clamp = false，即 GL_REPEAT。这很重要，因为包用零 uv_size 标记不想要的面——wine_fox/05_magical 的 ysmGlowdamofazhen3 声明 south 为 uv [256, 0], uv_size [0, 0]——上游仍构建这些面（GeoBuilder:186-193 仅跳过 uv 数据缺失的面），该面四个 uv 全归一化为 u = 1.0。夹紧时是行的最后一个纹素，在该包每个皮肤中透明，因此面不可见；重复时环绕到第一个纹素，在 magic.png 和 water.png 中是不透明灰色，面变成模型艺术上 60×60 的不透明板。实测：magic.png 在 (0,0) 为 A255 RGB(57,51,67)，在 (255,0) 为 A0。OuterFileTextureWrapTest 固定两半。已解决——注意此修复在移植中，因此需重建 YSMU jar，而不仅是 Geckolib。

EntityModelRenderApi.isModelLoaded 是移植特有查询。 上游面向宿主的表面是事件（RenderModelEvent、RenderLayerEvent、RegisterRenderStateModifierEvent、RegisterModelLocatorEvent），无就绪查询。移植对停放模型返回 true，而渲染器正在绘制内置默认。开放：所有者决策，记录而非解决。

第一人称手臂由其自身 animatable、控制器集和条件表摆姿势。 上游每帧 tick CustomFirstPersonArmEntity（client/renderer/CustomFirstPersonArmRenderer.java:27-28），以 fp.arm.misc / fp.arm.parallel_<i> / fp.arm.armor_<slot>（client/controller/collections/FPArmControllerCollection.java:22-32）驱动，基于第二动画存储和第二条件管理器（ModelRenderTargetAssembler.java:53-60），并与身体共享一个控制器映射。移植现同样（CustomFirstPersonArmEntity、FPArmConditionManager、AnimationDomain#controllerSetId），因为手臂骨骼此前从未进入 AnimationProcessor，绘制为绑定姿势。**手臂的对齐变换只允许有与模型无关的项**：上游是 `translate(∓0.25, 1.8, 0)` + `scale(-1,-1,1)`（CustomFirstPersonArmRenderer.java:43-47），移植额外需要一次绕 Y 的半转，因为 1.7.10 的第一人称相机看向 -Z 而 1.20 看向 +Z；那一转必须**绕原点**。曾经它是绕"手臂立方体包围盒中心"转的（FirstPersonHandRenderer 的 findArmRollPivot），等于在正确结果上再加一个 `2·(包围盒中心 x,z)` 的平移，这个量由每个包自己的几何决定：内置 default 的 arm.json 是 0.228 格，LaPluma2.5.3 的手臂几何在子骨骼里（x 跨度 -2.6…+4.2 px）所以更大——就是用户报的"错位"。旧常数 -0.5 恰好是上游的 -0.25 减去内置模型包围盒中心的两倍，是按内置模型调出来的；去掉枢轴后常数回到上游值，内置模型位置只动 0.02 格。见 .agent/phase46-first-person-arm-alignment.md。一处刻意差异：上游在构建模型资源集时决定存在哪些 fp.arm.* 和 parallel_<i> 控制器，并跳过无骨骼通道的动画（ParallelControllerDiscovery.java:64-71），而移植为每个 animatable 注册固定集并在运行时路由。该差异并非无害，且已作为缺陷发布：固定池向引擎请求当前模型未声明的名称，AnimationController#setAnimation 在填充 animationQueue 和更新 currentAnimationBuilder 前返回，控制器保留旧 builder，下一帧重新评估为已更改，每帧打印 Could not load animation: parallelN. Is it missing?，控制器无回退——实测为 parallel0/parallel5/parallel7 在实机重复，以及此前的 LaPluma 行走回归。移植对上游发现时过滤的对应是交接时的运行时存在检查：AnimationManager#playParallelIfPresent 询问 hasAnimation（先安装模型动画，与 definesAnimation 完全一样），名称缺失时返回 PlayState.STOP——SOURCE 自身使用并记录的结局（client/animation/AnimationManager.java:852-855，原因在 :822-823）。ParallelAnimationExistenceGuardTest 为身体和手臂固定，包括手臂仅从 fp_arm 域询问，身体仅从 main。已解决——见 .agent/phase23-first-person-arm-pose.md。
同一分裂的一个后果记录而非修补：默认动画回退按域（ModelRenderTargetLoader.java:375-379 与 DefaultAnimationKey.java:36-38，其中 fp_arm 单独映射到 player/first-person），因此legacy 三集默认载荷（extra/main/arm，即在 animations/fp.arm.animation.json 存在前烘焙）永远无法填充第一人称回退，该域保持空，直到模型自身包声明 fp_arm 或内置默认被重新复制。上游对未声明 fp_arm 集的载荷有同样缺口，因此这是格式行为，不是缺失的移植映射。

vehicle.origin 无可移植。 上游的 VehicleOriginController 不是“按实体偏航旋转”；它是只从两个提供者取四元数的兼容钩子，SimplePlaneCompat.getRotation（Simple Planes 的 PlaneEntity）和 ImmersiveAircraftCompat.getRotation（Immersive Aircraft 的 AircraftEntity）（VehicleOriginController.java:57-69）。两者都是 1.20 模组，无 1.7.10 对应，移植也没有任一兼容包装。两者都未安装时，上游控制器 rotation 为 null 且不访问骨骼队列——即渲染器自身偏航决定朝向，正是移植所做。记录而非发明“实体偏航原点控制器”，上游没有。先前相邻的时间线和查询绑定缺口已在 ccb8acd 解决：车辆时间线分派使用粒子子系统的配对实体/变换/骨骼作用域，EntityQueryBindings 向玩家、车辆和箭路径提供相同的实体参数化绑定表。不要把这些旧缺口算作仍缺失。

按上游“记忆已就绪模型”：实体继续画它已有的模型，而不是掉到内置 default。 上游只在新 target **加载完成**时装进实体绑定（EntityModelBinding#synchronize:145-167，AcquireResult.Ready 才 installReady），兜底只在 if (resourceHolder == null) 时装（:165-167），CustomEntity#applyBoundResource:133-145 在同一个 isLoaded() 门下切换 currentModelRenderTarget；所以新选中的模型还在 Pending 或已 Failed 时，实体继续画它已有的那个 target（failure-and-recovery.md:36：“a consumer may keep the old ready lease while the new authoritative target is Pending or Failed; the humanoid uses the intrinsic default only when it has no old lease”）。移植此前只有一个共享 animatable 且每帧按实体重新播种，“这个实体现在被画成什么”无处存放，于是 Pending 期间掉内置 default、Failed 后永久掉内置 default。已解决：新增 com.fox.ysmu.client.render.AppliedModelRegistry（以 tracked entity id 为键，同 NPCData 的 1.7.10 理由），渲染器在几何真正解析后写入（install，对应 installReady），下一帧读回；CustomPlayerRenderer.selectDrawn 是唯一判定（REQUESTED/APPLIED/FALLBACK/NONE），绘制路径 resolveDrawnModel 与“能否取消原版渲染”的 canDrawFor 共用它，APPLIED 时把 applied 的几何与它当时的贴图一起写回 animatable（上游从不把 A 的几何配 B 的资源），连接级清理与断线各清一次（上游：目录里没有 applied 模型的内容就释放它，EntityModelBinding:297-302）。第一人称手臂跟随身体的 applied 模型，这是上游自己的接线（CustomFirstPersonArmEntity.java:22,59-66,74-76）；HUD 纸娃娃（本仓特有）把“被画的模型变了”计入离散变化。见 .agent/phase44-model-loading-parity.md。

内置兜底不可用时交回原版渲染，而不是画成“什么都没有”。 上游把内置 default 当启动期要求（ClientModelService.start 等 defaultInitialization 完成才发布单例；requireAvailable 在此之前抛 IllegalStateException），产品决策也写明 humanoid 回退 default 失败是例外、不要求继续恢复宿主（model-fallback.md:29-39,49-53）。移植不能失败客户端启动，而且它取消了原版玩家渲染（ClientEventHandler.onRender 先 setCanceled(true)），所以旧行为是：兜底不可用仍取消事件，随后 geoModel == null 直接返回——实体既没被 YSM 画也没被原版画。已解决：onRender/onRenderLiving 先问 CustomPlayerRenderer#canDrawFor（纯查询，走同一个 selectDrawn，并检查所选模型的几何真在引擎缓存里），只有真能画出东西才取消原版事件，否则保持原版渲染——与本仓对 DISABLE_SELF_MODEL、非玩家实体无模型时的降级同一形态；ClientModelManager.isFallbackAvailable() 承载“兜底自己的资源仍可用”（内置 default 真正发布时置真、其自身被拒时置假、连接级清理不清它，对应 model-fallback.md:41-43）；RenderUtil.drawPreviewModel 增加 canDrawPreview，因为 GUI 预览直连 AnimatedGeoModel#getModel，兜底不可用时它会抛 GeoModelException 且一路到客户端主循环无人 catch。报错走 ModelFailureNotifier.reportFallbackUnavailable（一条 ERROR + 一条聊天，每会话一次）。见 .agent/phase44-model-loading-parity.md。

模型失败对玩家可见。 上游 ModelFailureNotificationAggregator 把失败延迟 1500 ms 聚合成一条聊天：同一模型同一结果只报一次（EmissionKey(identity, outcome)，:32-34），同一模型的不同结果按严重度合并（:35-36），一批用最坏结果加批大小发一条（:63-69），模型后来加载成功则清掉待发与已发标记（recovered，:42-46）；结果是 PARTIAL/FALLBACK/STOPPED，键 message.yes_steve_model.model.failure.{partial,fallback,stopped}。已解决：com.fox.ysmu.client.render.ModelFailureNotifier 保留全部语义，改由客户端 tick 排空（上游用 ScheduledExecutorService 再跳渲染线程；1.7.10 的聊天本来就必须在客户端线程），计数按 %s 传字符串。PARTIAL = 某张贴图解码失败（模型仍渲染）；FALLBACK = 模型被确定性拒绝（移植在模型级报告，上游按实体绑定报告，同一次失败对 projectile/vehicle 是 STOPPED）；STOPPED = 箭/载具失去替换并恢复宿主渲染（对应 ClientModelRenderTargetManager:246-250 的 fallbackRenderTargetId 为 null 那一支），且只在模型真的已发布或被拒时报告（还在构建的那一两帧是准备阶段）。另加一条本仓特有的 default_unavailable。见 .agent/phase44-model-loading-parity.md。

瞬时（环境）失败与确定性（内容）失败分开。 上游把这件事带进失败种类：AssetLoadException.Reason.CONTENT → ResourceFailure.Kind.DETERMINISTIC，其余 → TRANSIENT（ModelRenderTargetLoader.java:107-131）；只有确定性失败按内容版本进负缓存，且任何昂贵失败都只有显式 retry/reload 或内容/权限/连接/宿主能力变化才获得新的尝试资格（failure-and-recovery.md:13,28）。移植此前一律 markGeometryFailed，一次 OutOfMemoryError 就把模型在本连接判死。已解决：ClientModelManager.isTransientBuildFailure 把 Error（OOM、LinkageError、栈溢出）判为环境失败——载荷保持停放且请求标记保留（逐帧不再重提交，等价于“不给新的尝试资格”），isModelAvailable 仍为真（实体留在 YSM 渲染器上继续画 applied 模型，等价于上游保留 holder + terminalFailure），新载荷或重连后才重新可试；Exception（载荷畸形、格式不支持）照旧隔离。发布路径（纹理注册/上传）不改，因为上游明确把发布失败当确定性失败并丢弃候选（ModelRenderTargetCache:559-588）。见 .agent/phase44-model-loading-parity.md。

同名模型/同名包根的裁决显式化并按上游排序键裁决。 上游按 (rootKind 优先级, 相对路径) 升序选记录、先者胜、后者记 `ModelScanError` 丢弃（`model/catalog/ReloadableModelCatalog.selectRecords:602-642`，排序键在 `:607-610`），且非内置候选命中内置保留 id 时直接拒绝（`CatalogReconciler:401-424`）。移植把四处无条件 `put` 收敛到 `ServerModelManager.registerModel`/`registerPack`，排序键与上游一致：内置身份 = 扫描根是 `config/ysmu/builtin` 且被 `assets/ysmu/builtin-index.json` 契约声明过（契约不可读时退回只看扫描根并 warn 一次，保证"内置赢"永不因契约缺失反转），优先级沿用上游那张表（`BUILTIN 0 / AUTH 1 / CUSTOM 2`，**三档现在都有扫描根**：`config/ysmu/builtin`、`config/ysmu/auth`、`config/ysmu/custom` 加内置根里未被契约声明的目录，AUTH 的 1 档不再空缺），同优先级按扫描根内相对路径升序取先者（`custom/example/` 因此赢 `custom/example.ysm`，与登记顺序无关）。冲突一律 `ysmu.LOG.warn` 一条带两个来源路径与两个优先级的日志（上游消息原文 `Catalog source conflicts with a selected model`），`ServerModelManager.modelConflictCount()`/`packConflictCount()` 暴露本轮条数（`/ysm` 诊断接线未做）。残余差异只有一处：排序键完全相等（同优先级 + 同相对路径）时保留先登记者——上游一个 rootKind 下不会有两条同路径记录，本仓的 CUSTOM 档同时覆盖 `custom` 与内置根里未被契约声明的目录，所以这种情况只在本仓存在，按上游"先者占位、后者丢弃"的形态处理。`ServerModelRegistrationTest` 守护。

模型授权（Auth）按来源根判定，没有全局开关。 上游的选择判定是无条件的 `PUBLIC || granted`（`model/session/server/ServerModelSession.java:317-322`），受限集合来自 `auth` 目录本身（`model/catalog/source/CatalogRootKind.java:5-7` 的 `AUTH("auth", AccessPolicy.SESSION_AUTHORIZED)`），所以空目录 = 谁都不受限；`ServerConfig.RestrictedAuth`（默认 false）是**资产**门，不是选择门（`ServerModelSession.canRequest:205-221`）。移植此前自创了 `Config.ENABLE_MODEL_AUTHORIZATION`（默认关，"打开后每个模型都要授权"）——上游没有这个机制——已删除。现在多出第三个扫描根 `config/ysmu/auth`（`ServerModelManager.AUTH`，目录名与上游 `AssetPaths.authModelsRoot:100-107` 一致，落点沿用本仓的 `config/ysmu` 前缀）：该根的模型 `accessOf` 返回 `AUTHORIZED`，只有被 `/ysm auth` 授权过的玩家能选用；`BUILTIN` 与 `CUSTOM` 一律 `PUBLIC`，未知 id 也是 `PUBLIC`（客户端本地 `/ysmclient load` 模型照旧可选）。三条重置形状逐条照抄：`remove` 按 id 相等（`command/sub/AuthCommand.java:60-64`）、`clear` 只重置 `SESSION_AUTHORIZED` 的当前模型（`:90-94`）、授权重估（`ServerModelSession.setGrants:137-142`）由 `/ysm reload` 与登录触发（`SessionProtocolHandler.java:403-435`，移植在 `CommonEventHandler.onEntityJoinWorld`/`onPlayerLoggedIn`）；三者都被 `ignoresGrantsFor`（上游 `ModelInfoCapability:56-58`，由 `/ysm model set ... true` 写入的 `ignore_grants`，存玩家 NBT）豁免——上游的 `mandatory` 标志**没有**移植，它只为"把选择推给没有模组通道的客户端"存在，本仓同步没有那道门，带上就是死状态。客户端拿到受限集合与授权集合（新包 `S2CModelGrants`，客户端绑定 id 37，`NETWORK_PROTOCOL` 7→8）：受限且未授权的图块画暗、不响应点击、来源行写"授权模型"，`Category.AUTH` 分类只列出受限且已授权的模型。记录三处不移植（不是缺陷）：没有"只给展示信息"这一级（本仓同步单元是整个模型负载，`RestrictedAuth` 打开时该模型根本不出现，而不是出现但锁住；因此**授权后要等下一次同步**——relog 或 `/ysm reload`——被扣下的资产才会到达，这与产品契约"已接纳的传输保留接纳时的授权结果"一致）；没有 `SelectionStatus` 枚举与空授权集合的显式发布（拒绝以聊天行表达）；上游四个 `need_auth`/`auth_model.*.info` 语言键在 1.20 树里零引用，是旧版本死字符串，未复制。**native 层不需要移植**：`tmp/YesSteveModel-Native-dev` 的 `modules/` 与 `docs/` 里 `auth(?!or)` 零命中，49 处 `auth` 全是 "author" 署名，授权面只存在于 Java/线协议（见 `.agent/phase53-model-authorization.md`）。

ExecPlans
编写复杂功能或重大重构时，从设计到实现使用 ExecPlan（见 .agent/PLANS.md）。

仓库布局
src/main/java/com/fox/ysmu：项目特定模组代码。

src/main/java/com/fox/ysmu/client：仅客户端渲染、GUI、按键绑定、动画谓词、纹理/模型注册和上传状态。

src/main/java/com/fox/ysmu/model：服务端模型发现、内置模型复制、缓存生成和文件夹/.ysm 模型格式处理。

src/main/java/com/fox/ysmu/network：Forge SimpleNetworkWrapper 设置和数据包类。

src/main/java/com/fox/ysmu/eep：1.7.10 IExtendedEntityProperties 状态，用于所选模型/纹理、活动动画、收藏模型及每个玩家的 roaming 变量。

src/main/java/com/fox/ysmu/event：GTNHLib 事件订阅者，用于公共玩家同步和客户端渲染事件。

src/main/java/com/fox/ysmu/compat：可选模组兼容包装。将 Backhand 等直接调用置于这些包装后。

src/main/java/com/fox/ysmu/mixin：仅 Mixins。gradle.properties 将 Mixins 限制在包 com.fox.ysmu.mixin。

src/main/java/software、src/main/java/com/eliotlash、src/main/java/net/geckominecraft：内置 GeckoLib 3 引擎（327 文件：275/48/4）。本仓库拥有的第三方兼容代码——保持引擎编辑窄小并记录每次偏离。除 software/bernie/geckolib3/GeckoLib.java（@Mod 和 preInit 已移除）外，树与快照字节相同。见 PROVENANCE.md。

src/main/resources/assets/ysmu/builtin：内置模型资产（`default` 与 `misc/*`），在随包契约变化时整树解压到 `config/ysmu/builtin`。运行时副本按需重建（`ServerModelManager.extractBuiltinModels`）：`builtin-index.json` 的 sha256 加模组版本写进 `config/ysmu/builtin/.ysmu-builtin-stamp`，指纹一致**且**契约声明的每个模型路径都还在时跳过"清空 + 整树解压"；不一致、契约不可读（指纹为 null）或有契约路径缺失时才重写。因此**运行时副本里用户改动/添加的文件在契约未变时会保留**（旧行为是每轮清空重写，这是一处有意的语义变化），而用户自己的模型仍然应该放 `config/ysmu/custom`。用户把同名模型放进 `config/ysmu/custom/<同名>` **不会**替换内置那份：同名冲突由 `ServerModelManager.registerModel`/`registerPack` 按**显式来源优先级**裁决（内置 0 < 用户域 2，沿用上游 `ReloadableModelCatalog.rootPriority:636-642` 那张表），内置赢，且与扫描顺序无关；每次冲突都打一条 `ysmu.LOG.warn`，带两个来源路径与两个优先级，本轮条数见 `ServerModelManager.modelConflictCount()`/`packConflictCount()`。内置身份 = 扫描根是 `config/ysmu/builtin` **且**该模型被 `assets/ysmu/builtin-index.json` 契约声明过，所以用户手放进内置目录、契约没声明的目录不算内置模型。包根机制（`ysm-pack.json`）本身来自上游，同名包根走同一套优先级。**注意：旧文档所述的"用户可用 OpenYSM 包替换同名内置目录"（`ServerModelManager.builtInModelTarget`）在当前代码里已不存在**——该函数 grep 0 命中。每轮创建却从不被读取的 `config/ysmu/blacklist.txt` 也已删除（不再生成；已装机器上那份旧文件不主动删）。包根持有模型目录，其 ysm-pack.png 是封面从不是模型纹理，其 ysm-pack.json（名称/描述/语言）随 OpenYSM 同步索引到达客户端，使模型选择 GUI 能用包自身名称和封面标记文件夹。

src/main/resources/assets/ysmu/lang：en_US.lang 和 zh_CN.lang。保持新翻译键同步。

src/main/resources/mixins.ysmu.json：Mixin 配置。在 mixins 中列出 MixinEntityArrow，在 client 中列出 MixinItemRenderer、MixinEffectRenderer、client.MixinRenderArrow。添加前阅读文件而非此行——此行在其他三个已落地后仍说“仅 MixinItemRenderer”数日。

src/main/resources/META-INF/ysmu_at.cfg：YSMU 触及的 Minecraft 内部访问转换器（ItemRenderer、EntityRenderer、Minecraft.timer、RenderHelper）。引擎自身两个访问已在此文件，因为引擎随本 jar 发布。

tools/convert_new_ysm.py 和 tools/convert.md：较新 OpenYSM 风格模型目录的转换工具与文档。

Minecraft 和 Forge 源
RetroFuturaGradle 已将修补后的游戏源反编译到工作区。用文件工具就地阅读，而非打开 jar：

build/rfg/minecraft-src/java：反编译的 Minecraft + Forge 1.7.10 源（stable-12 名称，已应用 Forge 补丁）。

build/rfg/minecraft-src/resources：匹配资源。

build/rfg/launcher-src：捆绑启动器源。

不要提取或反编译 build/rfg 中的 jar（mcp_patched_minecraft-sources.jar、srg_patched_minecraft-sources.jar、recompiled_minecraft-1.7.10.jar、srg_merged_minecraft.jar）。它们与 minecraft-src 相同，解包只浪费时间并弄乱工作区。先在 build/rfg/minecraft-src/java 下检查原版/Forge 行为——维度旅行、重生、IExtendedEntityProperties 克隆、事件触发顺序、渲染和纹理重载钩子——仅当该目录缺失时才回退到 jar。build/ 是生成输出：绝不提交，绝不向其中添加工作区文件。

运行时流程
启动始于 ysmu.java。CommonProxy.preInit 加载 Config，调用 ServerModelManager.reloadPacks()，记录版本。CommonProxy.init 通过 NetworkHandler.init() 注册网络数据包。ClientProxy.init 额外注册动画状态/Molang 变量、自定义玩家渲染器和按键绑定。

ServerModelManager.reloadPacks() 创建 config/ysmu、custom、builtin、auth、export、cache 目录，按契约指纹把内置模型整树解压到 config/ysmu/builtin（ServerModelManager.BUILT；指纹一致且契约路径齐全时跳过清空与解压），初始化 cache/server/PASSWORD，并按 custom → auth → builtin 三个扫描根重建加密服务器缓存文件（每个根跑 packs/OpenYSM/legacy 三个扫描器，见"模型授权"条）。

模型同步始于服务器发送 RequestSyncModel。客户端通过 SyncModelFiles 回复缓存 MD5 名称。服务器发送加密密码（SendModelPassword），要求客户端加载缓存命中（RequestLoadModel），并发送缺失缓存文件（SendModelFile）。客户端解密并通过 ClientModelManager.registerAll() 注册模型。

客户端渲染在 ClientEventHandler 中取消原版 RenderPlayerEvent.Pre，委托给 CustomPlayerRenderer。CustomPlayerRenderer 从 ExtendedModelInfo 或 NPC 覆盖选择模型/纹理状态，发布 SpecialPlayerRenderEvent，然后通过 GeckoLib 替换渲染器渲染。第一人称手部渲染分为 RenderHandEvent 和 Angelica 特定的 MixinItemRenderer 路径。

HUD“额外玩家”叠加层（ClientEventHandler.renderSelfGuiPlayer，预览渲染）通过同一渲染器和同一 AnimationData 绘制同一本地玩家，因此一帧对一个实体在一个 seekTime 到达 AnimationController.process 两次。引擎必须对此幂等：第二次处理不得丢弃过渡正移向的动画（AnimationController.process 仅在 animationQueue 非空时轮询）。曾如此时，控制器从不离开 AnimationState.Transitioning，模型永远渲染过渡第一帧——表现为“行走有动画但冲刺没有，仅第三人称”，因为第一人称中 HUD 叠加层是唯一通道。见 .agent/phase7-geckolib-dependency.md；AnimationControllerTransitionTest 守护它。

引擎是内置的，不是必需的独立模组，因此 ysmu.java 不再声明 dependencies = "required-after:geckolib"。引擎需要宿主知识时通过接口，YSMU 实现它：CustomPlayerEntity 实现 IMolangPhysicsScope，使 software.bernie.geckolib3.core.molang.MolangPhysicsRuntime 能键控其逐帧 MoLang 作用域，ClientEventHandler 将远程动画变量推入引擎的 core.molang.RemoteAnimationVariables。YSMU 不再自带 Molang 物理运行时。

模型和资源规则
文件夹模型位于 config/ysmu/custom/<模型名> 下，必须包含 main.json、arm.json 和至少一个 .png。可选动画文件为 main.animation.json、arm.animation.json 和 extra.animation.json；缺失动画文件回退到内置默认动画。

config/ysmu/custom 中的 .ysm 文件也会被扫描。仅包含 main.json、arm.json 和至少一个 .png 的文件被缓存。

config/ysmu/auth 是第三个扫描根（上游的 auth 根，见"模型授权"条）：放进去的模型/包与 custom 完全同构（同样的目录结构与 .ysm 文件规则），只是它的模型需要 `/ysm auth` 授权才能被玩家选用；同名冲突时 auth 胜过 custom（优先级 1 < 2）。这个目录默认是空的——**空目录 = 没有任何模型受限**，所以升级后的服务器行为与之前一致。

模型图像在解析时规范化为 PNG。ModelImageConverter 用 libs/ImageStream--SNAPSHOT.jar 中内置的解码器（上游 OpenYSM 声明为 com.github.OpenYSM:ImageStream 的库）解码 BMP/JPEG/WebP/AVIF 并重新编码为 PNG，镜像上游的 YSMClientMapper；PNG 原样通过，无法解码的图像保持原样并每格式警告一次。因此客户端只接收 PNG，ClientModelMetadataRegistry 为旧缓存保留自己的格式守卫。**ImageStream 现在按源码 vendor 在 `src/main/java/rip/ysm/imagestream/**`（238 个文件）与 `src/main/resources/META-INF/services/javax.imageio.spi.*`**，与 GeckoLib 引擎同形。原先它是 `libs/` 里的一个 jar：由 `zipTree` 块嵌进产物、并挂上 dev 启动类路径，而那份 jar 是 Java 17 字节码（major 61）——1.7.10 的 FML 会用读不了它的 ASM 扫描每个类路径 jar，报 `Zip file … failed to read properly, it will be ignored` 并丢掉整个容器，三个解码器随即 `NoClassDefFoundError`（owner 的 runServer 日志实测）。源码化后由本仓自己编译、随其余代码一起降级到 JVM 8，产物不再依赖任何 `libs/` jar，也不会与代码漂移。见 .agent/phase54-imagestream-packaging.md。

模型选择和纹理屏幕通过 ModelPreviewRegistry（同步载荷中的模型 preview_animation，未声明时为 idle）加 ModelPreviewAnimationState（上游 CatalogModelPreviewAnimationState 的移植）动画化每个图块。模型只需定义名为 hover、hover_fadeout 和 focus 的动画即可选择额外通道；淡出窗口是该动画长度。RenderUtil.renderEntityInInventory(..., hovered, focused) 驱动状态机并在预览 animatable 上设置 AnimationData.shouldPlayWhilePaused，因为打开 GUI 会暂停单人游戏，引擎否则拒绝推进时钟——无该标志图块渲染为冻结姿势。

模型的逐玩家设置是 roaming 变量：包读取为 v.roaming.<name> 的命名浮点，由模型的 模型设置 面板（在 ysm.json 中声明为 extra_animation_buttons[].config_forms）写入。面板、本地副本和服务器权威是 ModelConfigScreen、ClientRoamingStore 和 ExtendedRoamingVariables；ModelConfigRegistry 记住哪个模型声明哪些表单，ClientRoamingKeys 记住每个模型的 32 位命名空间键，由 RawProperties.sha256 经 ModelRoamingKey 派生，使客户端和服务器无需额外载荷字段即可一致。服务器是权威：客户端通过 C2SRoamingChanges（服务器绑定 id 99）报告增量并采纳服务器在 S2CRoamingState（客户端绑定 id 20）中回显的映射，该映射也到达跟踪该玩家的每个客户端。边界仅在 ModelRoamingLimits。分离的 GUI 预览通过 IMolangPhysicsScope.getMolangVariables 读取本地玩家为所绘制模型的值，因为它没有实体供引擎查找 roaming 变量——这阻止预览图块在世界读取存储值时读零。

包假设 roaming 变量活过单帧：如 v.roaming.player_size=v.roaming.player_size?v.roaming.player_size:1 的 timeline 语句每次动画重启运行一次，之后每帧读取其赋值，parallel1 由此缩放整个模型（"MRoot":{"scale":"v.roaming.player_size"}）。仅当引擎 MoLang 作用域稳定时有效，因此 MolangPhysicsRuntime.ScopeKey 按值比较所属实体与模型（**不含动画**，见上一条"变量表按 (实体, 模型) 共享"）——绝不按对象标识。GUI 图块每渲染帧重建 setMainModel(ModelIdUtil.getMainId(modelId))，因此该键中的标识组件会给图块每帧新的空作用域，变量读 0 且模型闪烁消失；世界不受影响，因为键是玩家 UUID。MolangScopeKeyStabilityTest 守护此。

ModelIdUtil 为 ResourceLocation 规范化模型名。安全 id 匹配 [a-z0-9._-]+；不安全名编码为 _name_ 加 UTF-8 十六进制。使用 ModelIdUtil 助手而非手工构建模型、主、手臂或纹理 id。

jar 内 `src/main/resources/assets/ysmu/builtin` 的整棵树只在随包契约变化时（`assets/ysmu/builtin-index.json` 的 sha256 + 模组版本，记在 `config/ysmu/builtin/.ysmu-builtin-stamp`）才清空重解压到 `config/ysmu/builtin`；指纹一致且契约声明的路径都在时跳过，**所以运行时副本里用户改动/添加的文件会保留**（这是有意的语义变化；用户自己的模型仍应放 `config/ysmu/custom`）。用户把同名模型放进 `config/ysmu/custom/<同名>` **不会**替换内置那份：同名冲突由 `ServerModelManager.registerModel`/`registerPack` 按**显式来源优先级**裁决（内置 0 < 用户域 2，沿用上游 `ReloadableModelCatalog.rootPriority:636-642` 那张表），内置赢，且与扫描顺序无关；每次冲突都打一条 `ysmu.LOG.warn`，带两个来源路径与两个优先级，本轮条数见 `ServerModelManager.modelConflictCount()`/`packConflictCount()`。内置身份 = 扫描根是 `config/ysmu/builtin` **且**该模型被 `assets/ysmu/builtin-index.json` 契约声明过，所以用户手放进内置目录、契约没声明的目录不算内置模型。包根机制（`ysm-pack.json`）本身来自上游，同名包根走同一套优先级。**注意：旧文档所述的"用户可用 OpenYSM 包替换同名内置目录"（`ServerModelManager.builtInModelTarget`）在当前代码里已不存在**——该函数 grep 0 命中。每轮创建却从不被读取的 `config/ysmu/blacklist.txt` 也已删除（不再生成；已装机器上那份旧文件不主动删）。包根持有模型目录，其 ysm-pack.png 是封面从不是模型纹理，其 ysm-pack.json（名称/描述/语言）随 OpenYSM 同步索引到达客户端，使模型选择 GUI 能用包自身名称和封面标记文件夹。

网络和线程规则
NetworkHandler 中的数据包 id 是线协议的一部分。需要时添加新 id；不要重编号现有 id。保持数据包侧注册显式并与处理器行为一致。

不要在数据包处理器路径上执行重型文件 IO、加密/解密或模型解析。现有代码用 ThreadTools.THREAD_POOL 做后台工作，用 Minecraft.func_152344_a(...) 在客户端模型或纹理注册必须返回客户端线程时。

模型密码必须在缓存模型文件可解密前可用。保持 SendModelPassword 在 RequestLoadModel 之前的关系及 RequestLoadModel 中的重试行为。

OpenYSM 同步缓存由模型自身 sha256 加 ModelCacheWriter.OPEN_YSM_BAKE_VERSION 命名，绝不由载荷字节命名，其签名检查只证明文件属于该哈希。当烘焙载荷含义变化时提升该常量——序列化器、RawYsmModelAdapter 或反序列化器烘焙模型的哪些部分——否则旧构建写入的载荷（例如无几何，在立方体烘焙为面之前）会继续提供给缓存哈希同样匹配的客户端。这就是十个模型跨重载和重启保持不可见，而新烘焙和每个本地测试看起来正确的原因。

兼容性说明
运行时先决条件是 UniMixins 和 GTNHLib。动画引擎是本模组的一部分，因此不得同时安装 geckolib-*.jar。开发/运行时额外项包括 NotEnoughItems、Nashorn、Angelica、Backhand 和 JUnit，如 dependencies.gradle 所声明。

按现有模式对事件订阅者使用 GTNHLib 的 @EventBusSubscriber。仅客户端订阅者应指定 side = Side.CLIENT。

使用 BackhandCompat 和 AngelicaCompat，而非将可选模组 API 调用散落在核心逻辑中。可选模组缺失时保持兼容检查弹性。

编码约定
遵循 .editorconfig：UTF-8、LF 行尾、Java 4 空格缩进、Markdown/JSON/YAML 2 空格缩进、末尾换行，除 .lang 文件外无尾随空白。

在 Windows PowerShell 上，用 -Encoding UTF8 读取 UTF-8 文件；否则中文 README、注释和 lang 文件可能显示为乱码。

现代 Java 由 JVM Downgrader 启用，而非 Jabel：gradle.properties 设置 enableModernJavaSyntax = jvmDowngrader 和 downgradeTargetVersion = 8，因此源码可使用现代语法和 Java 9+ 标准库 API（例如上游 CustomTextureManager 使用的 java.lang.ref.Cleaner），而生成的 jar 仍携带 JVM 8 类文件。这可行是因为 downgrader 将这些 API 调用重写到自己的桩，桩从 xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api 解析；jvmDowngraderStubsProvider 故意留空（原因见 gradle.properties 注释，若报告真实 Java 8 启动器失败则重新考虑）。代码已使用模式变量。使用 Java 9+ API 时在代码中说明运行时为何需要——模组的正常运行时是现代 JVM，此类 API 原生，降级路径是保持旧启动器可用的原因。

保留现有公共名称和遗留大小写，包括小写 ysmu 模组类。GeckoLib 动画引擎已内置于本仓库（src/main/java/software、src/main/java/com/eliotlash、src/main/java/net/geckominecraft）。这些树是本仓库拥有的第三方兼容代码，因此引擎修复在此进行——保持编辑与缺陷一样窄，不要围绕它重构或重新设计内置代码，并在 .agent/ 和 PROVENANCE.md 中记录每次偏离快照。

添加面向用户文本时，更新 en_US.lang 和 zh_CN.lang。添加配置字段时，更新 Config、相关 GUI 屏幕（如适用）和翻译键。

ChatComponentTranslation 消费的翻译字符串只能使用 %s。 1.7.10 的 ChatComponentTranslation#initializeFromFormat 对每个转换字符不是 s 的说明符抛出 ChatComponentTranslationFormatException("Unsupported format: ...")（build/rfg/minecraft-src/java/net/minecraft/util/ChatComponentTranslation.java:121-124；%% 是唯一其他接受形式），且是惰性的，从 getFormattedText/getUnformattedTextForChat（:202、:212）——即绘制聊天时。因此聊天键中的 %d 不会在调用点响亮失败：消息只是从不渲染。参考分支是 1.20 移植，其中 TranslatableComponent 接受 %d，因此逐字复制其聊天字符串会将 1.20 规则带入 1.7.10——这正是 commands.yes_steve_model.loadlocal.complete 带三个 %d 和三个 int 参数发布且 /ysmclient load 完成行从未出现的原因。先格式化数字（String.format(Locale.ROOT, "%.2f", (double) ms) 或 String.valueOf(n)）并在两个 lang 文件中保留 %s。I18n.format 不受影响——它经 String.format，因此 GUI 自己的 %d 键没问题。

添加模型动画状态时，通过 AnimationRegister/AnimationManager 注册名称和优先级，并确保 ConditionManager.addTest 能分类条件动画名称。

动画文件表达式由 legacy software.bernie.geckolib3.core.molang.MolangParser（com.eliotlash.mclib.math 中的 MathBuilder 子类）求值，而非引擎较新的 MoLang VM，因此 Operation 中缺失的 Bedrock 操作符会破坏整个通道而不引发可见错误："scale": "v.player_size??1" 无法分词，通道求值为 0，十个模型包渲染为空白。将面向包的操作符添加到 com.eliotlash.mclib.math.Operation（查找是表驱动的）并在 MolangParserCompatibilityTest 中覆盖。

三元必须在 MathBuilder 之前解析，日志必须带表达式原文。 三元的优先级最低（上游 MolangParserImpl 的 PRECEDENCE_QUES），所以它要在其他运算符**和一元负号之外**先解；MathBuilder.parseSymbols 却先跑它的一元负号修复，那个修复会先切分号表，于是 `a?(b):-(c)` 切到还带 `:` 的片段并抛 "There is no such operator ':'"——实机就是这条（LaPluma2.5.3 的 `(query.vertical_speed>0)?(math.clamp(…)):-(math.clamp(…))`）。移植在 MolangParser.parseSymbols 里先调 tryTernary 再交给父类，非三元列表行为不变；这一处修好后该包 1389 条表达式全数可解析。同时 MolangParser#reportParseFailure 现在会带上出错表达式原文（同一原因仍只报一次，行数不变），MolangException 补了带 cause 的构造器并让三处 catch 链上 cause——在此之前失败只报 "Couldn't parse '<整条表达式>' expression!"，真正的理由（未知运算符/未知函数/参数表问题）被丢掉，定位只能靠猜。回归见 MolangParserCompatibilityTest#ternaryBranchesWithSpacedStringArgumentsParse。见 .agent/phase47-pack-timeline-never-dispatched.md。

前置一元负号只否定紧跟它的那个操作数。 `breakdownChars` 只在负号后面紧跟 `(` 时才留下独立的 `-` 记号，于是符号表是 `[-, [A], +, [B?-50:-80]]`；`MathBuilder.parseLeadingUnaryMinus` 过去把负号右侧**整条剩余表达式**取反（`Negative(parseSymbols(subList(i+1, size)))`），`-(A)+(B?-50:-80)` 因此被读成 `-((A)+(B?-50:-80))`，每个 `+`/`-` 项都反号。上游是 Pratt 解析，一元负号只包住操作数（`MolangParserImpl.java:156-158` 的 `UnaryExpression(ARITHMETICAL_NEGATION, parseCompoundExpression(…, 2800))`，而 2800 高于 `MUL/DIV`(2600)、`ADD/SUB`(2400)，见 `BinaryExpression.java:116-132`），所以 `-(A)+(B)` 就是 `(-A)+(B)`。现在改成"否定紧跟的那一个操作数、把结果作为单个 primary 拼回符号表，其余交给原优先级机制"。**它看起来像三元 bug 但不是**：phase47 修的是顶层三元，这里顶层 `?` 在括号里，吃掉三元项的是 `MathBuilder.parseSymbols` 自己的顺序（先 `parseLeadingUnaryMinus` 再它自己的 `tryTernary`）。`*`/`/` 与 `+ 0` 会掩盖它（结合律/不变号），只有非零加减项暴露——wine_fox 全系跳跃时双臂后拐就是这个形状（`-(vs/|vs|)*(1-exp(-0.1|vs|))*30 + (ysm.has_offhand?-50:-80)` 被算成 `+88°` 而非 `-72°`，再经 X 轴翻转成日志里的 `-88°`）。回归见 `MolangParserCompatibilityTest#unaryMinusBeforeAGroupParses` 与 `#wineFoxJumpClipNegationBindsToItsGroup`。见 .agent/phase51-molang-leading-minus-precedence.md。

运算符优先级与"取值边界"照上游，不要用 mclib 原表。 `Operation` 的整数值按上游 `BinaryExpression.Op` 的相对顺序（`BinaryExpression.java:116-132`）：`ASSIGN 0 < NULL_COALESCE 1 < CONDITIONAL 2 < OR 3 < AND 4 < EQ·NEQ 5 < LT·LTE·GT·GTE 6 < ADD·SUB 7 < MUL·DIV·MOD 8 < POW 9 < ARROW 10`（越大越紧；POW/MOD 是 mclib 有、上游没有的）。mclib 原表把比较/相等/逻辑挤在同一个值 5 且**比算术更紧**，于是 `0.8+ysm.food_level<=6?-0.9:0`（随包 `wine_fox/05_magical/animations/main.animation.json:2035`）被读成 `0.8+(food<=6)`——恒真，该模型 Z 位移永远偏；参考分支用的就是上游顺序。取值边界同理：二元操作数、一元负号、函数实参过 `Operation.asDouble`（NaN→0），条件与 `!`/`&&`/`||` 过 `asBoolean`（NaN→false）；`x/0` 得 **0**（上游 `ExpressionEvaluatorImpl.java:50-57`）、`==` **精确**（`:112-141`）、`&&`/`||`/`cond?value`/`??` 一律**惰性**（上游收 Supplier：`:154-159`、`:105-111`、`:70-77`；移植侧在 `MathBuilder#binary` 的 `LazyCondition` 与 `MolangParser` 的 `??`），未取的一侧不执行副作用。刻意保留：本仓不窄化成 float（`IValue` 是 double），`??` 的"未定义"用 0 并由 `ExplicitVariableLookup` 区分"显式 0"（`MolangParser:685-702`）。回归见 `MolangParserCompatibilityTest#operatorsFollowUpstreamPrecedence`、`#conversionAndLazyOperatorRulesMatchUpstream`；见 .agent/phase52-mathbuilder-vs-upstream-audit.md。

转移条件里的裸 `=` 是**赋值**，而且**赋值是表达式（带副作用）**——这是上游语义，照抄。 依据（dev-1.20 逐行读过）：转移检查是对状态的 `transitions` **从头逐条**求值、命中即跳转（`BedrockAnimationController.java:244-257`）；`IValue#evalAsBoolean` = `ValueConversions.asBoolean(eval(...))`；赋值的求值**写入目标并返回赋入值**（`ExpressionEvaluatorImpl.java:78-103` 的 `var.assign(evaluator, val); return val;`）；`&&` 是 `bool((a, b) -> a.eval() && b.eval())`，即 Java 短路——左侧为真才求值右侧。`asBoolean`：null→false、Number→`!NaN && !=0`、其余→true。

LaPluma2.5.3 整条剑链就建立在这个副作用上：`player.parallel_5` 的 `Default` 依次写 `v.Swing_Sword&&(v.attack=0)`、`…(v.attack=1)`、`…(v.attack=2)`——第一条把 `v.attack` 写成 0 并返回 0（假），第二条把它写成 1 并返回 1（真）→ **立刻跳转**；每次左键重新求值一轮就推进一格，于是"点一下出一刀、一直点一直出"（所有者在 1.20.1 实测确认；包本体表与 YSMParser 的 dump 我都用端口的 `YSMBinaryDeserializer` 核对过，逐字一致）。**移植此前缺的正是这一层**：条件表达式阶梯只有 `or→and→equality→comparison→…→primary`，`=` 只在**语句**路径（`findAssignmentOperator`）里实现，于是 `(v.attack=1)` 被读成变量的旧值、三条守卫全假、`swd1/2/3` 永不进入——实机症状是"LaPluma 没有挥动"。现在在 `OpenYsmControllerExpressionEvaluator` 的表达式阶梯底部加了 `parseAssignmentCompiled`（优先级低于三元、高于 `||`，右结合；只认 `v.` 目标；写入经 `ConditionScope#setVariable` → `RuntimeState#setVariable`，与 timeline／`on_entry`／`on_exit` 同一张表），`OpenYsmConditionEvaluationTest#assignmentInAConditionWritesTheTargetAndYieldsTheAssignedValue` 与 `#thePacksThreeGuardsSelectOneStatePerEvaluation` 钉住（后者断言首刀落在 `swd2`，因为第一条守卫已把 `v.attack` 写成 0）。

**此前两次走错方向，均已撤销**：(a) 把条件里的 `=` 放宽成 `==`——副作用消失，变成"挥一下停一下"（所有者实测）；(b) "新挥动时对播 `swing*` 的状态清选择字段强制重播"——它的前提是"包里没有可被点击走通的边"，被本结论推翻，已整块删除。另外：native 仓库（tmp/YesSteveModel-Native-dev）里**没有** Molang/控制器求值代码（只有容器解码与 `projector.cc` 里 `SourceProgram(...)` 的原样投影），条件语义只能以 dev-1.20 为准。
`v.qh` / `v.qh2` / `v.random` 由模型 timeline 自己写，不是引擎变量（已实测）。 内置 `default`/`wine_fox/05_magical` 等模型的 arm 片段在 `"0.0"` 打了 `["v.jump=q.is_jumping;", "v.qh=!v.jump?v.qh2+1+(v.qh2>=2?-v.qh2);", "v.random=math.random(-30,30);"]`，在 `"1.5417"` 打 `["v.qh=0;", "v.qh2=0;"]`（以 `arm.animation.json:1792/1794` 为例）——即"每次挥动在 1/2/3 之间循环的变体计数 + 每次挥动一个随机数"。两条求值路径的 `Random` 都已支持双参 `(min,max)`，无需补函数。实机 `[YSMU-SWING-VARS]`（挥动沿，`AnimationManager`）读数为 `qh=0→2→0`、`random` 每次挥动都变 —— 证明**每次新挥动的 t=0 timeline 指令确实重放**（`AnimationManager` 每次新挥动都 `shouldResetTick=true + markNeedsReload() + adjustTick(0)`），所以 `perf` 那套约 200 行的 `configureTimeline`/`timelineOwnsDispatch` 自有调度器**不需要搬**。`query.any_animation_finished` / `query.all_animations_finished` 都已实现（`AnimationRegister:866` 映射到 keyframe 路径，控制器路径走 `OpenYsmControllerExpressionEvaluator:1463` -> `finishedAnimations()` -> `AnimationController#concurrentAnimationFinished(index)`）。此前把名字写成复数 `any_animations_finished` 去搜得 0 命中，是搜索用词的错误，不是缺口。真正的待查点是"什么时候算完成"——例如某状态点名的片段被过滤成空表时（`OpenYsmPlayerControllerRuntime:579` 就记着 `empty` 的这类交互），`all_animations_finished` 会不会在进入该状态的第一帧就为真。

内置 `4_default_controllers` 必须携带上游那份控制器集——挥砍动作就来自它。 上游把"只提供控制器"的模型放在 `builtin/misc/4_default_controllers`，其 `controller/main_controllers.json` 的 `player.post_swing` 是一个**由 `v.swing_sword` 标志驱动**的连段状态机（`swing:sword` 片段的时间线写这个标志，转移在片段播完后推进，每个状态 `on_exit` 清标志；1.21.1 正式版 jar 内同一份设计仍在，可直接读——该 jar 的 Java 侧没有控制器解析，状态机在原生库，键名全被加密）。本仓曾只有上一代的扁平资产（无 `ysm.json`、无 `controller/`），连 `attack_empty`、`sword_attack_01..03` 都缺，因此"拿武器看不到挥砍"；现在整套上游目录已在位（`ysm.json` + `models/` + `animations/` + `controller/` + `textures/` + `avatar/` + `lang/`），且 `BuiltinDefaultControllersTest` 与 `BuiltinAssetsTest#theDefaultControllersModelStillBridgesItsControllerSet` 分别钉住"控制器集含该状态机"与"控制器真的进了加载链"。注意 Bedrock 的 `transitions` 是数组写法 `[{"目标": "条件"}]`，状态里的动画条目可为字符串或 `{"片段": "条件"}`。见 .agent/phase48-builtin-default-controllers.md。

变量表按 (实体, 模型) 共享，不按动画分家。 `MolangPhysicsRuntime.ScopeKey` 曾含 `animationId`，于是 **timeline 写的变量与控制器读的变量落在两张表里**，控制器静默读 0：`4_default_controllers/animations/arm.animation.json` 的 `swing:sword` timeline（arm 域）写 `v.jump=q.is_jumping&&(q.vertical_speed<0)`，而同一个模型的 `player.post_swing`（body 域）读 `v.swing_sword&&!v.jump&&…` 来决定走 `attack1` 还是 `attack_jump`。移植原先只给 `swing_sword` 打了单点补丁，其它跨域变量一律读 0（本仓自己的审计已记在 `tmp/audit/animation.md:66-68`）。上游是**每实体一张变量表**；修法是把 `ScopeKey` 收回 (entityId, modelId)。实机验证见 `.agent/phase49-cross-domain-variables.md`（日志里 `jump=1.0` 只在修复后出现，状态机随之走通 `attack1→2→3→attack_jump→default`）。**新增任何"某个变量读不到就补一条写"之前，先检查这里**——单点补丁会把同一缺陷留在其它变量上。

状态点名的片段解析不到时，**停在 IDLE 且算作已播完**——这是上游语义，不要改。 依据在引擎自己的代码里：`AnimationController:126` 的并发路径对每个条目 `setAnimation` 后判 `currentAnimation == null && animationQueue.isEmpty()` 则 `currentAnimationFinished = true`，注释直接引上游 `AnimationPlayer:75,124-142`。配上 `RuntimeState#finishedAnimations` 的 `count == 0` 保持 `all` 初值 true，一个点名了不存在片段的状态（LaPluma2.5.3 的 `Default` 点名 `Contrl_Idle`，全包 0 命中，本机用例已断言）会在**进入的第一帧**判定全部播完，于是按包的 `-> Default if [q.all_animations_finished]` 立刻放行——这正是包作者要的效果（空状态立刻走人），不是移植缺陷。整段无条目时引擎先 `releaseAnimationResources()` 再清空并发播放器：不重播任何东西、姿态停在上一段最后，也不会回绑定姿势。此前把这条列为嫌疑，已撤销。
Gradle 与验证
Gradle 可直接运行，但运行中的构建占用项目：守护进程持有构建输出和源码树，因此运行时编辑可能冲突或丢失工作。仅作为最终确认启动构建，在所有更改完成且无其他代理或子代理仍在此工作区工作时。绝不在任务中途启动构建。

贡献者的“完成”不是验证，仅解析检查不是编译。 当子代理被要求不运行 Gradle 时，它只能诚实报告阅读了自己的工作——因此其交回的构建必须在更改被相信前运行。此处一批实测：八个文件报告“完成，已语法检查”产生主代码三个编译错误（记录组件在调用一侧重命名，以及通过参数化外部类型选择静态嵌套类型）和测试代码五个（发送者从未实现的接口方法，以及静态调用的实例方法），另有十七个测试运行时失败——因为解析检查看不到这些。运行套件，并阅读失败断言而非报告说通过。

只跑与改动对应的测试，不要全量跑 test。 默认用 `--tests` 过滤到受影响的那几个类（例如
`.\gradlew.bat test --tests "software.bernie.geckolib3.geo.material.*" --tests "com.fox.ysmu.client.ClientModelManager*"`），
全量 `test` 是近千个用例、每次都要一分钟以上并占住整个源码树，不值得在迭代中重复。只有在交付前的最终确认、
或改动横跨多处、无法判断影响面时才跑全量；跑全量时也只读失败断言与 `build/test-results/test/TEST-*.xml`，
并注意原先这里记的"`OpenYsmControllerNameAliasesTest` 两条失败是既有的"已不成立：那两条断言的是被刻意去掉的 grouped `player.parallel_0` 回退，已删除，全量套件现在应当全绿。

基于本机文件的测试不入库，只在本机跑。 有些测试必须直接读本机才有的东西——`run/` 的运行目录、`tmp/` 下的模型包——它们在克隆和 CI 上只会 `assumeTrue` 跳过，留着等于让仓库带一堆永不执行的用例，所以它们已被 `git rm --cached` 移出索引并列进 `.gitignore`（当前十项：LaPlumaSwordSwingReproTest、RunPoseDiagnosticTest、ChargeCircleDecalRendersTranslucentTest、ClientModelManagerOnDemandBuildTest、ClientModelManagerRegistrationCostTest、TranslucentTexelSamplingTest、YsmSyncChannelParityTest、ModelImageConverterTest、OpenYsmBakeRoundTripTest、LaPlumaToggleTimelineReproTest），本地照常运行。**不要为这类测试新造入库版本**：若测试真正需要的只是"能构建的字节"，用 `com.fox.ysmu.client.GeometryFixtures` 的写法——有真包就用真包，没有就用内联几何，永远都会跑（`GeometryFixtures` 及其五个使用者就是照这条留在库里的）。新增同类测试时把路径补进 `.gitignore` 那份清单。

从仓库根运行回答问题的最窄任务：

.\gradlew.bat compileJava - 最快的“仍能编译”检查

.\gradlew.bat test - 单元测试

.\gradlew.bat build - 仅最终确认，所有工作完成且无其他占用树时

.\gradlew.bat runClient

.\gradlew.bat runServer

不要通过扫描 Gradle 缓存组装类路径来类型检查更改。它慢且产生误导错误，因为同一 jar 的多个版本会进入该类路径。改为运行 Gradle 任务。

src/test 持有覆盖资源格式、同步数据包、安全助手和 Molang 物理的 JUnit 5 源。因为 Gradle 9 不再注入测试框架实现依赖，testRuntimeClasspath 必须保留显式 junit-platform-launcher 条目，且 fastutil（模组代码使用，仅传递拉入用于编译）必须为测试保留声明。CI 将构建/测试和标记发布委托给 .github/workflows 中的可重用 GTNH 工作流。
