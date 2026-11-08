# 独立实现状态

## 0.1.0开源与发行（2026-11-08，已发布）

整个原创仓库已按MIT在[GitHub公开](https://github.com/muxigame/muxi-console)，并登记到总仓子模块。正式包为531648674字节、SHA256 `370f59645046e97386f5b89b3ba44d13085e37f29dccd44f4b7dec130ccc2f44`；附409431826字节完整源码ZIP（SHA256 `55a54a138171f7d21ab7dfc5b27f30707fc61d05fb606499b92030555e3bac44`），15核心/217份许可/12工作进程源码与独立构建入口均闭合，见[源码清单](../third-party/source_manifest.json)及[构建说明](BUILD.md)。13核心及全部198产品class、390原资源、8个worker条目与machine-saves-04原字节相同，未删除产品条目；仅BlastEm/PCE换为明确源码重建，各通过45项输入/画面/状态/跨进程零步冷恢复检查，无残留worker。18组3163项纯检查和30489项资源检查通过。

第一次整包双端已连入，掌机/卡册打开通过；测试脚本空槽数量与未加载场地两次中止保留false，三JVM正常退出、运行payload已清理。最终同哈希包在全新client-3/fullpack-02完成真实双端掌机、卡册、刻录机及单个/整组掉落的27项成功检查。原统计范围漏掉落到平台外、Y66的2颗钻石而保留false；随后只读宽范围原始NBT证实1+2颗真实存在，独立收尾8项通过，未重放库存或掉落动作。三JVM正常exit0、native审计无残留，自动清理6228371168字节运行副本。同哈希包已正式发布到客户端1.4.94（9476文件）与服务端1.4.94-server.1（2237文件），仅新增一个模组，无其他文件改动或删除，既有配置迁移和worldContent保持。008经完整公告与正常停服后，新JVM5368/新Done、端口与RCON均通过，实际安装SHA完全一致；玩家实际下载URL的全量531648674字节也通过SHA1/SHA256核验。原脚本false仍保留。实体手柄、听感、代表性商业游戏、Linux和掌机联机线仍不计入验收。

## 当前机器存档、薄卡册与图标候选（2026-11-08）

[候选machine-saves-04](../build/machine-saves-04/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121587267字节，SHA256 `dfc0a88f5ecc360ff4f96ef13714687c9b2cfd3c84212c48a757d0ebc6159953`。进度改为每台掌机/桌机每个ROM内容独立保存，借卡不带进度、同机换同内容卡续档，掌机/底座及桌机拆放保留机器身份；旧卡档保留且不自动导入。新增27格便携薄卡册，只收卡带，手持打开/潜行摆放/拆回保全；厚度为首版四分之一。76物品模型按本体居中，144空机及插卡状态留边，修复掌机画面旧会话匹配。

84源、18组3163项Java、390资源/30489项检查通过；61项runtime/worker/core原字节保持。源01背包/四原卡与机器存档111条成功记录，末轮复制关闭世界的原机LCD/MODEL、薄盒和51图标34项通过；原machine整体中止false与中间图标低偏/遮挡原图保留。21原文件中仅8张最终图主展示，两名审图者实际逐张查看。9worker自然退出、三轮各三JVM exit0/native审计空，运行及唯一准备副本均正常清理。精确构建、继承/续办边界见[本轮审核](MACHINE-SAVES.md)与[图库](../build/machine-save-review/index.html)。未改Portal、干净源或生产，未发布。

## 前一轮实体卡槽与加载进度候选（2026-11-08）

[候选cartridge-slots-02](../build/cartridge-slots-02/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121553497字节，SHA256 `7552f29a5225dd0987533c871fd7f736933744139fc3e1bd3e0323ce92545ccd`。掌机支持原生背包双向右键单卡槽，保留整卡/旧UUID与自定义数据，移除自动选背包第一张；实际卡在机身外槽显示，下载/校验/启动/恢复阶段在内屏和两全屏显示。运行或保存时保护插槽，设备管理页有正常保存离开入口。世界下的ROM/存档布局保持，电脑屏继续仅显示连接来源。

77产品Java源、17组3139项检查、380资源/29546项校验通过。[比较](../build/cartridge-slots-02/compared-to-monitor04.json)为42改/15增/0删，97项runtime/domain/worker/core及元数据原字节保持。同一run原生库存、6张原卡/5个ROM、8MiB真实转交/冷读/缓存恢复、可见插槽与正常保存联合完成；83条记录82成功，两个原QA误判/站位失败保留并严格续办，15张原图已逐张核对。8worker自然退出、三JVM exit0、native审计空，运行与唯一准备副本均正常清理。精确范围/原结果见[本轮审核](CARTRIDGE-SLOTS.md)与[图库](../build/cartridge-review/index.html)。未改Portal、干净源或生产，未发布；实际设备/整包和许可边界沿用下文。

## 全屏与电脑显示器候选（前一轮）

[候选monitor-polish-04](../build/monitor-polish-04/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121516375字节，SHA256 `d8bc6f5fed1963f4b518c2f9fbb389a7e1255e744a9a13aa15be9ffc126f4cd2`。右键在完整机身与原比例纯屏两种全屏间切换，Esc只退出并释放输入；选中掌机仅短暂显示指定提示。移除独立绑定页，使用MC原生控制/按键绑定，整颗钮帽含边缘侧面一起变蓝。新电脑屏实际占2×2×1，薄面板、细支架/低底座及背部接口，旧屏按安全条件升级；22款机体继续增加细节。

72产品Java源、15组2616项检查、380资源/28773项校验通过。[打包比较](../build/monitor-polish-04/compared-to-heldfeel04.json)为83改/36增/3删，97项runtime/domain/worker/core及其打包元数据原字节保持。当前同候选原图、实机结果、续办及验证边界统一见[本轮逐项审核](MONITOR-POLISH.md)和[图库](../build/monitor-polish-review/index.html)；旧轮结果仍按原候选保留，不作为本轮重跑。未改Portal、干净源或生产，未发布；实体设备、整包和许可边界沿用下文。

当前run02已联合完成显示器、32张机体/持有原图、短提示、原生改键、GB/GBA双全屏/完整钮帽/输入隔离/正常保存及底座整物往返；62次采图中60张取景合格，原2张遮挡和两份QA脚本中止false保留。最终7个worker自然退出、双端worker空、三MC JVM exit0且native审计空；运行944107018字节与唯一准备526730637字节副本均由现有正常入口清理，源码、构建和原始证据保留。

## 手持体验候选（前一轮）

[候选held-feel-04](../build/held-feel-04/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121470443字节，SHA256 `e817e5553039eb17ed07ec2656540aeb33dfd2384c75150967fdfc638f23540d`。从 `dev@7a24560` 完成实际皮肤手臂、主副手/惯用手与双手支撑、九掌机约27–29%薄壳、小圆角动作键和细胶囊功能键。游戏画面新增按键设置入口，按平台保存键盘/手柄映射，操作视角键可改；世界持有中立、专注游玩拦截世界操作，实际控制表面按同一输入采样显示天蓝高光。行为及逐项证据见[手持审核](HANDHELD-FEEL.md)。

73产品Java源、14组2425项纯检查、354生成资源/23534项校验通过。[逐条比较](../build/held-feel-04/compared-to-pixelhardware01.json)为36改/6增/0删，93项runtime/domain/worker/core原字节保持；25项大屏模型/依赖及display_layout不变。同产品在QA05统一run的[32次原始采图](../build/held-feel-review/index.html)已逐张核对；原77项实际通过、原回执占用失败false保留，独立续办16项从同卡真实存档补齐剩余操作/底座，联合完成。四worker自然关闭、三MC JVM exit0、native审计空，943943839字节payload自动清理；只读共享调整不声称消灭全部Windows竞争。完整证据与实体设备等边界见上面逐项审核。未改Portal、干净源或生产，未发布。

## 像素设备外观候选（前一轮）

[候选pixel-hardware-01](../build/pixel-hardware-01/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121450653字节，SHA256 `d46d73b4fff988f20ce85a7d71904e868939489279c429c0fbdbc5b6e3c6f697`。从 `dev@f844682` 起点重做22机体、21媒体、通用0和控制器/刻录机/底座；采用各年代壳色、上下壳接缝、阶梯圆角、插槽/接口及正确控制布局，保留原CMD/控制能力。大屏按用户随后补充不再修改。

353项资源/22464闭包检查、原14组2186项纯检查通过。[JAR比较](../build/pixel-hardware-01/compared-to-dock-screen01.json)仅50项资源改变、168个自制小PNG新增、无移除，全部class/worker/core与dock-screen-01逐字节相同；25项实际屏幕模型/纹理依赖闭包及display_layout原样，另43项广义屏幕/公共资源比较不变。

统一client-2/run `muxi-console-pixel-hardware-01`，runId `851bb6c655e14edaa6046616f6695ce6`，完成本候选53张MC原图：22机体、20主副手、总览、媒体/配件两原图与两正面补图、GB/GBA及底座6张活动图。已逐张核对外观；媒体/控制器初始镜头拍到背面，因此另拍前面，未覆盖旧原图。GB/GBA正常刻录、输入/内屏对齐、底座同卡恢复/低位旋转、保存与整物返回的13项窄流程通过，3worker自然关闭，双端worker为空。三MC JVM exit0、native审计空，自动清理943872576字节payload，编号槽位随后合法复用。

[当前图库](../build/pixel-realistic-review/index.html)与[逐项完成审核](PIXEL-HARDWARE-REVIEW.md)绑定同一SHA及原始JSON。MC启动有OpenAL设备失败且客户端保留一条来源操作未记录的通用lastError；不归因为同因，不宣称零历史错误，不计实体设备听感。该限制不代替已实际查看的模型和动态对齐证据。仍是Windows x86-64/MC1.21.1/NeoForge21.1.250/Java21未发布候选；许可与其他未验边界沿用下文。以下为旧候选的冻结分轮证据。

## 掌机、底座与薄大屏候选（前一轮）

按用户新增要求完成[候选dock-screen-01](../build/dock-screen-01/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121382805字节，SHA256 `b9d33b38d2c8cf0045b79d3ef9be38922a63f8fb373ae9030ac878e5212d71fb`。71个第一方Java源、14组2186项纯检查、185项原创资源/12861项资源检查通过。独立只读审查未发现阻止原生验收的实质问题；[逐条打包比较](../build/dock-screen-01/compared-to-platform10.json)确认58项运行时、核心及其清单字节与原候选10相同，没有移除JAR条目，不重记21平台运行测试为本轮重跑。

- 持有装卡掌机自动在世界中运行，不抢全屏；F进入透明手持操作，右键同一room/epoch/worker进入全屏。F8释放操作，Esc正常保存离开并抑制同卡立即重开。收进原玩家背包时只有15秒保存/离开宽限，停止游戏输入，不续期，不扩大控制授权。
- 独立底座保存唯一完整掌机，型号、卡带UUID、自定义数据和NBT往返保持；默认一席，通过设备面板连接屏幕及从原状态恢复。机体最低点4/16，距底座顶1/16，无上下漂浮，缓慢旋转。
- 新屏默认真实占3×2格、厚2/16，四方向、全足迹预检与失败回滚；拆任意片只掉落一件并移除其他片。旧单格屏保持，跨区块孤片在足迹加载完整后无掉落清理。不同游戏按自身比例留黑边。

实际run为根统一环境 `client-2/runs/muxi-console-dock-screen-01`，runId `ff9126cce10740e8b3fdaa6674adeb73`。正常刻录原创GB诊断后验证四向屏幕生存放置/阻挡/拆从片/拾取、无GUI输入、两条右键路径复用worker、Esc及收热栏保存、底座同卡恢复与整物取回。`dock-review-result.json`保存48项实际结果，但原05/06近照出画，07取景越过8格权限后会话结束，不能把其后空room的leave当正常保存。首次独立补验 `dock-visual-result.json`在host观察超时失败，原文件均保留不覆盖。

同候选的 `dock-visual-resumed-result.json`补齐19项，要求每张取景前同room/ready/worker仍有效；低位旋转及薄屏侧/背原图已查看。真实正常leave后新generation `83d0d9b3-7166-4e1e-b713-70ca7c13f390`、frame3607，客户端和vault状态SHA同为 `bc4a11f051ca6fa431c39234fffb7a35f099025f449a90274b6677929d445e3d`，正常worker退出数6→7，无强停或失败；取回仍是原实例 `de6a0229-24cc-43ce-b118-e1dd097b3e37`、CMD5及原自定义标签。原图/原JSON见[本轮图库](../build/dock-review/index.html)，操作见[掌机与底座](HANDHELD-DOCK.md)。

本轮三个Minecraft JVM均正常exit0，native审计remaining/unknown/errors/actions为空，统一入口自动清理940616523字节payload并正常返回。槽位随后被另一任务合法复用，不操作新任务租约。原生操作由普通前端回调和真实网络玩家驱动，不冒充实体键鼠或实体手柄；物理设备和整合包边界沿用下文。第一方公开发行许可及第三方对应源码交付仍未闭合，未修改干净源或生产，未发布。下一节及以下是原平台候选的分轮历史证据。

## 全平台开发候选10（前一轮）

本轮完整开发目标已完成实现与对应诊断/模型审核物料，未生产发布。[候选10](../build/platform-model-10/muxi-console-0.1.0-dev.1+mc1.21.1.jar)为121,355,552字节，SHA256 `08493a72b103454f1693658a6c4ce575e9dd58a2ad027254f65b47dbb14e5dde`；Windows x86-64 / Minecraft1.21.1 / NeoForge21.1.250 / Java21。66个第一方Java源、13组2081项检查、174项原创资源及12181项资源检查通过。精确输入在[构建记录](../build/platform-model-10/build.json)。

第一方MC集成、上传/卡带、协议、独立工作进程、UI、键位/手柄映射、模型、贴图及诊断自行实现；Cartridge只参考公开功能与体验，旧分叉不作输入。21类平台保留原范围并新增12类，22款独立机体包括单双人机台、掌机和VB双镜筒桌架。底层第三方核心及依赖分别登记，不声称模拟CPU核心为第一方。平台/核心/实际验证逐项见[完整范围](PLATFORM-COMPLETION.md)。

- 21平台[基础原生诊断](../build/platform-smoke/platform-matrix.json)3534项通过；[最终冷恢复](../build/cold-load-all-21-02/result.json)548项、63个独立worker正常退出，覆盖新进程直接恢复、首次保存及坏组件回滚。全部17个LibretroCore类与候选10逐字节相同；旧05 NES/GBA存档亦直接恢复通过。
- [实际产品准入](../build/admission-final-07/admission.json)覆盖21平台；[打包媒体执行](../build/media-final-07/result.json)124项覆盖N64 OpenGL、FDS换面、现代MAME和Lynx。MAME跨进程逐像素续接不一致保留，不作为严格连续性通过。
- 真实MC run02已验证NES普通双端、N64/MAME单主控共享、双方独立输入、同批PNG/PCM、客席ROM/state拒绝及主控结束后从P1正常恢复。MAME以Pong两球拍位置验证输入，未用球自动移动替代；该轮最终用设备关机结束，未把它写成Esc保存成功。
- 真实MC run03已补同步中一次退出排队保存、双方自动结束、普通NES双worker回归，以及内置屏与WebDisplays昼夜亮度。该轮GB旧存档首次恢复失败保留；候选10修复SameBoy首次未执行时拒绝序列化回滚点的问题。
- 真实MC run04（runId `372c19207595455caa521d9d7faba777`）使用候选10恢复原GB卡带：252698字节旧状态SHA `6a8add62c3dc7adf5efdad8e99db5127da67389c3790d6361f3b34b7427268c0`与客户端恢复身份相同；主副手昼夜四图已看。正常保存frame1612、状态SHA `4c1aab00e159e7b5b341074f4dd1641057ece8d36eb5af875b59f22a75c632ed`，worker自然退出，取出后CMD5 GBA外壳及原标签保持。没有重刻或替换旧卡带。

[模型审核图库](../build/model-review/index.html)链接原始Minecraft PNG及分轮JSON：22机型世界图、20张完整主副手图、GB活动掌机、实际内置屏/WebDisplays及N64/MAME机台画面。原图未裁切或合成；每张保留来源run/JAR/文件哈希，旧失败不覆盖。[逐轮模型结论](MODEL-REVIEW.md)区分05静态、07手持/共享、09显示与退出、10冷恢复结果。各轮三JVM均正常exit0；最后一轮的native子进程审计为空，统一入口负责租约及payload清理。

当前限制：实体手柄映射功能已实现，但两MC进程均未枚举到设备，按键/热插拔未验；实际音响听感、Linux、光影、完整整合包共存、各平台代表性合法游戏仍须单独验收。掌机联机线、不中断自动主控迁移未实现；共享流最高20fps/512×512。所有诊断与截图均不代表整个商业游戏库兼容。

第一方公开发行许可尚未指定，第三方滚动核心的精确对应源码与许可交付未闭合，manifest仍为 `releaseReady=false`。未捆绑商业ROM/原厂BIOS，未替换Minecraft干净源、修改Portal或生产发布。两份独立媒体测试提取缓存合计748720304字节因自动审批返回“blocked by policy”保留，未换通道删除；MC运行payload与其分开，由统一入口正常清理。

## 2026-11-07 历史快照（独立09）

以下是上一阶段的冻结记录；候选号、检查数和未实现列表只描述当时，不覆盖上述当前结果。

本仓从根提交 `512ed6c` 空白基线开始，未继承或迁移 Cartridge 分叉源码、协议、native host、资源或测试。第一方 MC 集成、服务端协议、领域状态机、Java/JNA 工作进程、界面、输入、模型、贴图和诊断均重新实现。公开 ABI 和第三方模拟器另行列明来源，不将其冒称第一方。

独立开发候选已完成构建与本轮实际验证，尚未生产发布。旧分叉的测试不计入本仓结果。源码在本真实仓 `dev`，最终功能提交由父仓 `projects.json` 登记；根提交仅有本仓初始说明，不是第三方祖先。

最终候选：[muxi-console-0.1.0-dev.1+mc1.21.1.jar](../build/independent-09/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，8,003,015 字节；SHA256 `c75c25b1058f37e88868f7928b688349307a1f022cb4eb0db29ad0f0d89e85e9`。适用 Windows x86-64 / Minecraft1.21.1 / NeoForge21.1.250 / Java21，第一方产品源码48个Java文件，源哈希与构建结果无漂移。

- 原创资源：136 个文件，19 平台标签/配色、桌机/掌机/卡带/刻录机/控制器/屏幕；7286 项可重现、PNG 解码及 MC 图集引用检查通过。
- 纯 Java：545 项领域、37 项旋转显示、124 项键位持久化、192 项状态存储检查通过，合计898项。
- 独立运行时：新官方核心包14项（13产品软件核心覆盖18类平台候选，1诊断核心）哈希及独立进程导出探测通过；新自写 NES 诊断23项和2048无ROM诊断10项通过。全新无厂商logo/BIOS的 GBA ARM 诊断3188项通过，覆盖240×160、A/Start、SRAM、跨进程恢复、坏组件拒绝并回滚。修复了 mGBA 未定128KiB保存区在恢复后才收敛32KiB的实际缺陷；RTC未暴露不记为硬件时钟验收。
- MC：独立03双客户端启动/联网通过；独立04的 native01 完成真实刻录/去重/转交权限/P1/P2输入，图片发现图集和镜像缺陷；修复后的独立06/native02 同流程、服务器 checkpoint 保存/双方恢复、重复加入幂等、正常退出计数均通过。普通原图已由根查看，额外掌机模型已查看。
- native02：guest 正常关闭工作进程1次，host 3次；强制/失败均0，worker全清空；随后三MC JVM均exit0。运行内模组已由统一入口清理，早期自有提取缓存也逐个核对打包哈希清理；新版本改为 `natives/muxi-console/` 随统一入口清理。
- 最终 native04/base09+QA05：桌面完整链、服务端状态保存与双方恢复、同epoch重复加入不换PID/不重置帧、P1/P2独立输入与双端CRC一致均通过；新增掌机真实潜行/use装备用卡，卡带instance保持、松散卡消耗，P1输入改变画面，正常离开保存到同卡UUID的服务器checkpoint。guest正常worker关闭2次、host3次，forced/failed均0，JNA临时目录清理通过，所有worker为空。
- 最终七张原图已经查看：刻录/控制设置、原创桌机/屏幕/掌机、实际全屏和世界屏幕画面；图集紫黑与电视镜像缺陷已修。最终三个MC JVM全部exit0，统一入口已删除各角色mods与natives，未遗留本轮worker。native03只做启动后为最终原子计数修正正常停止，不计为玩法验收。

最终原始结果在总仓 `test-environment/client-2/runs/muxi-console-native-20261107-04/`，runId `608b31266f164d48a66e27bbcdf34bf2`：`console-qa/scenario-result.json` 为产品流程，`run-result.json` 仅审计连接/退出，七图在两客户端screenshots。布景/物品准备有辅助，卡带由真实刻录生成，输入为正常前端回调，并非实体键鼠/手柄测试。native01 的原拾取定位失败保留并同run续办，不与最终无失败native04混淆。这里只保存必要结果，不构建私有回执体系。

未验证/未实现：N64硬件渲染、FDS/光盘类新媒体、MAME机台、各扩展平台的代表游戏和多人兼容、实体手柄输入及热插拔、Linux、光影、完整整合包共存、云平台投诉客服。默认只支持同核心二进制身份的控制会话；掌机联机线与纯旁观音画流尚未实现。

第三方滚动核心的精确对应源码及公开发行许可交付仍未闭合，manifest `releaseReady=false`；本任务没有进行公开发布或替换干净源。第一方公开发行许可证尚未指定。
