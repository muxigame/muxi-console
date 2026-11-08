# 木犀游戏机独立原生 QA

本目录从空白编写，只读取本仓新产品 API 和统一 runner 文档。两个 QA JAR 分侧安装，Java 包为 `net.muxigame.consoleqa`，不会包含任何产品 class，也不发布。

## 当前：机器存档与卡带收纳盒

本轮产品把存档归属改为真实机器 UUID 与精确 ROM SHA；卡带 UUID 只作实体媒体和权限身份。`MachineActions`、`MachineScene`只读 `MachineData.read`、当前 `saveOwner()`、实际机器存档元数据和磁盘文件，不创建机器身份、不注入存档。服务器 fixture 只提供普通空掌机、桌机、底座、工具和空盒；机器身份由产品正常使用/放置分配。历史以卡带 UUID 查存档的脚本和结果保留为旧候选证据，不能直接用来验当前机器存档。

使用 `qa/build.py --name machine-saves-01 --product build/machine-saves-01/muxi-console-0.1.0-dev.1+mc1.21.1.jar --cartridge-review` 编译，新增四个源文件 `CaseActions`、`CaseScene`、`MachineActions`、`MachineScene` 均纳入分侧源哈希。`--cartridge-review`仍仅嵌入本仓已验证原创诊断；本轮只正常上传声明并刻录小GBA两张、GB一张、NES一张，不重跑完整核心矩阵、不把ROM加入产品。

统一 runner 正常启动并核 `qa/drive.py --check` 后，唯一驱动所有者依次执行 `python -B qa/case_review.py --run <规范绝对run路径> --build <QA报告>` 与 `python -B qa/machine_review.py --run <同run> --build <同QA报告>`。两个阶段均自然结束，不使用输入暂停、不并发命令。case阶段以原生27格容器验证便携/摆放、卡专用限制、空白堆叠、数字键/shift/来源槽锁、关闭持久化和整盒拆除取回；最后四张实际卡全部取回host，`case-result.json`固定记录 `gba_a/gba_b/gb/nes`、原UUID/SHA/完整NBT与真实槽。machine阶段严格引用同run/同候选该结果及SHA，复用这四张原卡，不重烧替代。

机器阶段用正常菜单装取卡和真实前端输入：A按A键的红画面保存后，同SHA另一UUID卡在A恢复；将该真实卡正常丢弃、按实际实体坐标拾取交给B，B从蓝画面独立开始并按Start变绿；A换GB建立独立游戏存档，换回GBA恢复A红画面。再验证同掌机安装到底座保留机器归属、NES桌机正常保存取卡后生存拆除、拾取同机物品重新摆放与恢复。所有活动截图前后必须同卡、room、epoch、worker，ready且已有实际画面；截图仍需人工查看，不能以audit代替视觉验收。

每次正常保存核客户端checkpoint SHA、vault最新元数据、实际磁盘SHA/大小及路径 `server/<实际世界>/muxi-console/states/machines/<机器>/<ROM SHA>/cards/<机器>/<代际>.state`。最终核四个机器/游戏命名空间、四张原卡各只有一个实际位置、完整NBT保持，所有worker自然关闭。实际计数和未完成范围以新run不可覆盖的结果JSON为准；首次执行中断须保留false并用明确同源独立续办，不能重写为零失败。

`muxi-console-machine-saves-01` 的case阶段真实35项/4图完成。原machine阶段同SHA异卡在A恢复已完成19项/2图，随后guest仍是初始创造模式，原生InventoryScreen被CreativeModeInventoryScreen自动替换，正常背包callback按边界拒绝；原false SHA `31b47132f6b5cfec225012f2751f3c1f348a41f4957e2ffe93e34e5b8afc3e99`、19条pass及2原图均保留。`qa/machine_review.py --continue-machine --source-sha256 <该SHA>`只接这一精确错误/计数及同run/产品、实际双worker空、A最新checkpoint磁盘哈希与同四原卡状态；B原卡已被guest实际拾取，不重丢、不重烧、不重跑A前置。后续仅用普通指定副手卡装入、正常保存后的Shift取卡完成guest路径，继续换游戏/底座/桌机，另写独立结果和5新图。

续办实际完成57/57项、5新图，SHA `462f821e8bfbacc276d0ee53d288bc0e2560bb4f0f819a7fb6259d1f3628d29b`，与原19项联合验证存储功能；case35项另计，不能把重复身份/存活断言说成独立功能数。最终host/guest分别7/1个worker自然退出，强制/失败均0、双worker空、观察超时0；四个machine+game存档和三份去重ROM在实际世界的磁盘SHA/大小均核对。run01已正常停止，三JVM exit0/native remaining/unknown/errors/actions为空；统一清理实际释放953530564字节，原世界、JSON和PNG保留。

原01图审还发现产品渲染器两处仍按旧卡UUID房间匹配，使世界LCD暗、MODEL回退成纯屏；原图不能记显示通过。root将按正常关闭世界复制到修复候选的新统一run，仅补同原机器/原卡恢复后的世界LCD与MODEL两图及安全保存。存储功能继承范围、二进制差异与新视觉证据分别说明，不把01失败或暗图覆盖。

新候选视觉使用 `qa/machine_visual.py --run <新run> --build <新QA报告> --source-run <已关闭run01>`。先核原run正常退出及原case35/4、machine false19/2、续办57/5精确SHA引用和同卡集，再核统一runner声明复制世界、实际四checkpoint的SHA/代际不变、原A整机及完整卡。不会再次prepare、清空背包或造机器身份。正常恢复后新采世界LCD与实际完整MODEL（modelRendered/model5）两图，并安全保存核新世界磁盘SHA。用户随后要求压薄盒体和全物品图标审，由新候选原图单独验，不借原厚盒图宣称薄盒完成。

随后依次运行 `qa/case_visual.py --run <同新run> --build <新QA报告>` 和 `qa/icon_review.py --run <同新run> --build <新QA报告>`。case_existing只读adopt原唯一sentinel盒；独立case_icon_fixtures仅在已验证空槽供应普通无身份writer/dock各1，给物槽及完整NBT公开记录，不宣称这两个空件来自旧01背包，也不取走已安装底座。原生Inventory、薄盒放置、原生27格菜单、整盒拆回库存各采新图，前后核原23份blank、outerNBT、4原卡、3机器与所有committed checkpoint未变。

IconReviewScreen仅是私有QA展示页，按真实 `GuiGraphics.renderItem` 的16×16源格放大到32px绘制，并画槽边界、不裁剪越界。hardware28项包含22机体正确物品类型、两generic与writer/dock/screen/case；media23项包含21种无发行身份的CMD卡样式及blank/controller，共51项各一次。预览只在客户端临时对象中使用CMD，没有CardData、MachineData或CUSTOM_DATA，不进玩家物品栏、不产生卡UUID/ROM/存档。两页原图须核实际renderSequence、实际调用数与目录种类完整；另外用真实原生Inventory图佐证case/dock/writer位置，不能把QA展示页冒称玩家已拥有全部机型。

中间产品03在独立`muxi-console-machine-saves-02`仅跑icons，原结果数据12/12项、51个预览、2原图，SHA `2e858af2bdc1faa7bb0b0557bdf15b1a18cfb139ab09334c63de2ff57443c45e`。图审发现原版Tutorial遮住部分hardware，且部分空掌机联合envelope中心仍偏低，故不整体放行视觉；保留原JSON的true数据范围与两原图，不把它改false或冒称无视觉问题。产品04继续最小调整空机中心/保守缩放，新的独立run才补最终视觉。三个视觉driver开场走现有正常dock_view/Tutorial NONE并等10tick，不修改产品隐藏UI。中间run已正常关闭并由统一清理释放939076523字节。

最终产品04（`dfc0a88f5ecc360ff4f96ef13714687c9b2cfd3c84212c48a757d0ebc6159953`）绑定QA04，在`muxi-console-machine-saves-03`从已正常关闭的原01世界独立复制恢复。QA报告SHA `4ea2b4ad7837782dd95efd3eb95979e9635e4c1135b6a85549dfb4eb984c7f27`，产品/双QA JAR与20个Java源哈希最终全部复核相符。机器视觉真实10/10项、2新原图，SHA `71ce8480b7f22b2229bb2c96e03ec3a7f70dc5778f9d51097bde2fab6ffdcf3a`；世界手持LCD与完整MODEL被实际查看通过，红色原进度、完整机身和真实3:2内屏恢复，旧01显示缺陷已修复。

最终薄盒12/12项、4新图（SHA `dd9ac6f8f186a88828f314439a4e7900be988703b5f0d221aa86d9bc76e4360e`），最终图标12/12项、51个实际预览、2新图（SHA `2efb80baa3d2d106b4279ca0b42e31610aeba33cb396e8bf1cbc2d54740eb4b7`）；最终8图已实际逐张查看通过。全部7份原JSON共有157条通过的检查记录，其中含重复身份/存活断言，不是157个独立功能，也不代表原machine无中断或中间icons视觉通过。原false、原显示缺陷图和中间图标否决均保持原样。

最终实际世界 `client-2/runs/muxi-console-machine-saves-03/server/qa-world/muxi-console` 的4份checkpoint与3份去重ROM全部再次直接读取并核SHA/大小，原4卡分别唯一位于host/1、host/2掌机内、host/15、guest/3，原3机器身份和完整卡NBT保持。case/icon没有修改已提交存档；最终A-GBA checkpoint为 `c2ae0b92a058b5d84ed223fcc1a661c6d7ffd8d3c55cd645cfe0fd6f4908c8fd`。三个run均三JVM exit0/native审计完整且remaining/unknown/errors/actions空，合计9个worker自然退出、强制/失败均0；所有驱动观察超时0。统一运行副本清理释放953530564、939076523、944052292字节，合计2836659379字节。

`qa/machine_gallery.py`以三run正常关闭为硬门，核7原JSON的精确SHA和续办/复制链、4卡3机4key及21份原PNG（960×540、PIL verify/decode、原SHA；本轮21份不同bytes）。输出 [机器存档与薄盒图审](../build/machine-save-review/index.html)，只主嵌最终8图，历史13图仅独立原图链接；33个anchor与8个img相对链接全部核验，不编辑或拷贝原图。图审人工结论与原生JSON数据范围分开，不修改其`visualAcceptance:false`或`physicalOSInput:false`边界。

## 历史：实体整卡槽与真实加载进度

`qa/build.py --cartridge-review`只在私有QA中加入大卡诊断：保持已验证`regression.gba`全部65536字节，末尾仅补FF到8388608字节；固定原ROM、原生成器、基线结果、QA派生生成器及派生文件哈希。不会修改或向产品加入ROM。另用本仓已验证原创NES、GB、小GBA和PCE，正常上传声明并刻录六张卡（GB额外一张用于旧格式）；不造卡UUID、ROM或存档。当前准备/编译不等价原生通过，实际结果以对应新run为准。

root给实际新run后先`qa/drive.py --check`，依次运行`qa/cartridge_review.py --run <实际run> --build <QA报告> --phase inventory|cold|hardware`。三阶段分别写不可覆盖的独立结果，阶段自然结束后看图，不使用`input()`暂停或并发命令通道。inventory使用原生InventoryScreen和正常PICKUP/SWAP回调验证双向装取、容量1、满槽/非卡/不兼容/满背包与忙卡保护，完整NBT和UUID必须保持。忙卡检查须实际执行保存阶段点击并收到真实服务器拒绝，不能靠status推断通过。legacy仅把一张已真实发行且未加sentinel的GB卡消耗后移入空掌机旧flat字段，明确是历史格式fixture，不产生新身份；正常取一次/再取无复制/重新装入新CONTAINER。明确另一手快插使用实际主副手和普通useItem，Shift防止该测试步骤自动开局。

cold真实丢弃拾取转交8MiB卡给guest，持卡本身不得下载；实际选中已装掌机才开始冷加载。世界LCD、MODEL、SCREEN三图均要求截图前后`0<bytes<total`，并联合真正LCD/全屏render audit，不写假进度、不人为限速。首画面后加载层消失，真实普通前端映射输入改变诊断，普通保存后再启动验证零新增ROM分块和实际存档恢复。hardware继续用guest的原大卡和真实缓存，采手持/原生物品栏/MODEL/底座露卡；图标很小，不夸大能看清标签。NES/PCE正常插原卡，实际管理面板“保存并离开游戏”后才取卡，不能以电源停止替代保存。真实卡用于电脑屏只允许连接显示来源，不能消费卡、建独立room或启动worker。最终核server实际world/muxi-console/content与states磁盘SHA、六卡唯一位置和全部worker自然关闭。所有图为新候选原始Minecraft帧，不沿用或编辑旧图；未验实体OS键鼠/手柄。

本轮`client-2/runs/muxi-console-cartridge-01`保留两份脚本原中止：inventory的GUI关闭重采键导致合法自动开局先触发忙卡保护，原false含16/17已过检查、1图；`--phase inventory --continue-inventory --source-sha256 e9323121930be66d8648de0c59846376d5728427d53c25ba386faf64e4fbb693`仅接该精确范围及同卡实际清理状态，独立续办8项/1新图，实际slot/full拒绝、明确另一手及saving忙拒绝/GB盘hash闭合。满包保护不以noRoom为必要条件；游戏合法自动启动后须正常保存。

cold原固定拾取位置未达到真实抛物落点，原false含1项已过、0图；未重丢或重烧，实际同UUID实体在观测坐标普通拾取，独立`cartridge-pickup-result.json`保留3项证据。`--phase cold --continue-cold`严格接原cold SHA、pickup SHA和原六卡集，22项/5图完成所有未执行要求。原primary流程已改用真实落点，旧失败不覆盖。hardware独立32项/8图，拍图前后必须同room/epoch/card/worker且ready/有首帧，转动期间自然等待不代替存活验证；管理面板正常保存和六卡唯一取回完成。联合共83条检查记录，其中82条通过、原合并断言1条false保留，不能写原轮零失败或将重复检查当独立测试。15张原图由`qa/cartridge_gallery.py`核同run/候选及六份JSON精确SHA引用，输出`build/cartridge-review/index.html`；原false、真实pickup和两个续办来源分别标明。

实际大卡冷下载342个分块，世界/MODEL/SCREEN截图前后分别2–7%、15–21%、29–34%，截图时显示4%/16%/31%；同卡缓存重启没有新增ROM分块。最终host/guest各4个worker自然退出，强制/失败均0，双方worker为空。加载中取消/坏缓存/坏块等异常另外由391项纯文件检查及源码审查覆盖，本轮不冒称原生故障注入或实体手柄验收。

## 当前：整颗按钮、两种全屏与电脑显示器

使用新产品精确SHA构建分侧QA，仍以 `qa/build.py --pixel-review` 嵌入本仓既有原创GB/GBA诊断；root统一runner给实际编号run后先 `qa/drive.py --check`，再按 `screens → models → play` 执行 `qa/polish_review.py --run <实际run> --build <QA报告> --phase <阶段> --pause-after-first`。先`screens`做2×2×1四向生存放拆/阻挡不耗/唯一掉落、旧3×2及受阻part0实际NBT加载迁移、前侧背原图；首显示器暂停供图审。随后`models`采新22机体和10款便携形态。屏阶段结束仅配置未装卡GBA槽2及其自定义tag；模型阶段后普通空机fixture还原GB槽7，不覆盖已发行卡带。`play`正常声明刻录自写GB/GBA，检查精确短提示及消失、原生Options→Controls→KeyBinds真实改键/保存/重读、MODEL/SCREEN同worker切换和原比例最大化、Esc回普通持有不中断、完整按钮六向实际baked quad着色/松开、输入隔离与底座/安全保存。首MODEL全屏图也先暂停核取景。展示前通过本run正常Tutorial选项步骤结束原生新手提示，FOV45，不改产品或修饰原图。

只使用正常UI回调和产品公开输入mapper；隐藏窗口授权source采 `NativeControls.sample`，不直接造反馈mask。F/F8可在原生KeyBinds绑定为游戏动作；Esc退出全屏，最终保存通过普通 `leave()`，不冒称Esc保存。旧屏fixture仅构造合法空屏历史数据，经正常NBT load和真实scheduled tick迁移，不造卡、ROM、状态或迁移结果。普通阻挡路径不等价于unchecked异常注入，范围分开记录。

本轮`monitor-polish-02`的原play在首MODEL暂停时因启动未配置交互输入而收到精确`EOF when reading a line`，原false保留7项已通过检查和5张原图，finally普通leave实际保存、host/guest worker均空。`qa/polish_review.py --run <同run> --build <同QA报告> --phase play --continue-play`仅接受这个精确中止范围，核同run/产品、原false SHA、7pass/5图及正常清理，从同一原GB实体卡实际checkpoint恢复；跳过已做的提示/原生设置与GB刻录，不重放原命令。续办单独写`polish-play-continuation-result.json`，13张新图使用`resumed-`前缀、不再暂停、不覆盖原图。该联合闭合不等于原play零失败。

`qa/polish_gallery.py`严格核上述原false及续办对它的SHA引用，同时收同run/同产品model32张、screen9张、原play5张、续办13张、独立clean1张与底座补图2张，共62张未经编辑的原始PNG。原续办两张底座照片被升级后的旧屏遮挡，只记显示器活动流、不能当完整底座图；另由`qa/polish_dock_visual.py`从同一原GBA实际存档正常恢复，以北侧合法近距补两张完整低位旋转机身/底座。首次补图因QA Esc参数漏传而在拍图前失败，保留false/2pass/0图，已正常保存；`--resume-failed`仅接受这个明确阶段，核原结果SHA/同卡/真实finally存档，从仍在dock的原完整GBA恢复，另写resumed true/5pass/2新图并再次安全保存、唯一整机取回。最终host自然worker退出7、forced/failed均0、guest从未启动worker，最终双worker为空；不能把联合结果改成原轮零失败。

clean另有自己的结果/SHA，普通F1/hideGui暂隐藏原生HUD和选中tile轮廓后恢复，不替换屏阶段原图；原图中的L形是Minecraft选中单片轮廓，不宣称AO改动消除了原生选择框。新模型不能复用历史图片验收。截图和实际render后snapshot须共同审核，不能用绘制计数替代肉眼效果，也不冒称实体OS键鼠或真实手柄输入。启动需要人工暂停的初次阶段必须提供可交互标准输入；play续办明确禁止暂停。

QA回执现由 `ReplyQueue` 保存已执行命令的不可变ack。写文件失败后只重试同ack，成功前不执行下一ID；不重放已执行mutation，不更改owner/lease/PID/哈希校验或通道。19项确定性文件故障检查见 `build/qa/replyqueue-check-02/result.json`。这项修复不抹除旧NTFS原失败，也不宣称Windows不存在文件竞争。

下述F/F8视角、独立按键页及Esc保存专项均为旧候选历史工具说明，不能直接用于当前交互验收；旧原结果和构建保留。

构建：`python -B qa/build.py --name <新名称> --product build/<候选>/muxi-console-0.1.0-dev.1+mc1.21.1.jar`。嵌入本仓原创 `build/runtime-smoke/two-port-battery.nes`、`build/platform-smoke/original.gb` 和生成的 `build/model-review-plan.json`，逐项固定哈希。产品内模型目录必须与当前资源原字节一致。编译固定 Java21、512MiB、单 CPU；构建不启动 Minecraft。

只能由总仓统一 `scripts/ops/test_environment.py run` 创建 `test-environment/client-N/runs/` 运行。当前模型/浏览器专项使用下述最小 `--release-profile`，不再混用单独模组输入。默认新世界；明确授权的后续回归可由统一 runner 复制同任务已正常关闭的世界，必须核对原owner/正常退出和复制声明，不复制正在写入的世界。测试客户端为真实网络玩家；服务端 fixture 只放置平台和三台设备、给空白卡带/控制器/掌机，不生成已刻录卡带。

运行中可用 `python -B qa/drive.py --run <实际编号run路径> --build build/qa/<名称>/qa-build.json --check` 核实 owner、runId、每个真实 PID/创建代际、租约和运行中的产品/QA 原始哈希；`--prepare` 准备服务器场景并读取两真实客户端。驱动不会启动或结束 Minecraft，也不会强杀 worker。

双端连接后使用 `--scenario` 运行完整最小场景：真实刻录界面的拖文件回调、默认拒绝与重新声明、两次刻录及内容去重、原生丢弃/拾取转交、强制回源下载权限、真实方块交互与两控制席、正常 InputSource 前端到服务端再到两个 native worker 的独立输入与画面 CRC，以及五张实际渲染截图。驱动先调用正常产品离开并等待 worker 关闭，最后仍由统一 runner 正常停止三个 Minecraft JVM。

本目录实现和编译通过不等于场景已通过。`scenarioComplete` 只根据本轮真实结果设置；截图仍须人工/图像查看验收，`visualAcceptance` 与 `physicalOSInput` 始终 false。文件拖入、Checkbox/Button、InputSource 均是正常前端回调测试，不冒称实体鼠标、实体键盘或真实手柄操作。

## 手臂、薄掌机与按键反馈专项

精确产品冻结后，仍使用 `qa/build.py --name <新名称> --product <本仓候选JAR> --pixel-review` 及正常最小profile。由root统一runner提供实际新run后，先 `qa/drive.py --check`，再 `qa/held_review.py --run <实际run> --build <QA报告> --phase models --pause-after-first`。只准备普通空机，拍九掌机与VB的十张本轮原图；首张双手图必须先人工核手臂、握持位置和取景，再继续。绘制分支计数不能代替手臂实际在画内的原图检查。

`--phase live --pause-after-first` 正常上传声明/刻录本仓GB及GBA诊断，保留原卡身份和完整外壳；采GB主右/副左后暂停，先人工检查单手握持再继续。逐项采双手、主右副左、左惯用手镜像，以及idle/真实按下/松开/组合键天蓝反馈；普通设置按钮更改A和操作视角键、保存后独立读回配置、核平台隔离及恢复默认。输入来自普通GUI回调和隐藏窗口授权前端：实际回调键集合经产品 `InputProfiles.sampleKeys` 映射，再走同一服务端控制席/核心和真实高光；不直接造视觉mask。普通世界KeyMapping前进实测位移同时游戏mask0，专注GUI的W/Q/数字/F/鼠标回调被消费且玩家/库存/选栏/方块不变；右键全屏/配置视角键返回保持同room/epoch/worker，正常松键归零；F8、设置切换清键，Esc完成真实保存退出。底座两角度原图及同卡恢复/正常保存/整物取回作几何接缝回归。不重跑未变桌机、大屏或21核心矩阵。

`qa/held_gallery.py --run <实际run> --build <QA报告> --require-live` 生成 `build/held-feel-review/index.html`，仅链接同run同SHA的原结果及32张原PNG（含GB功能胶囊键/GBA肩键实际按下）。正常路径收model/live两份完成结果；05实际收model=true、原live=false和独立续办=true三份结果，按下述严格门联合核验。工具核路径、SHA、PNG解码与尺寸，不复制、裁切、修饰、拼图或导入旧模型照片。`HandheldPresentation.snapshot()`只读实际RenderHandEvent末尾记录，必须与原图结合审核；不设置 `visualAcceptance=true`，不冒称实体OS键鼠或真实手柄验收。

05原live在GBA功能组合松键回执时遇NTFS原子替换冲突，原false结果、77项已通过断言、20张live原图及boot.log具体原行保留。`qa/held_continue.py --run <同run> --build <同QA报告> --source-sha256 <原false完整SHA> --checkpoint-sha256 <实际cleanup存档SHA>`只接受这一精确阶段、同run/同产品/同卡及正常清理证据；从真实GBA已提交状态恢复，另写独立续办结果，仅补GBA剩余模式/隔离/F8/设置/正常保存和底座两图/取回，不重放丢回执命令，不覆盖任何原结果或照片。联合图库明确原false和16项续办的来源，不能把联合闭合改写成原轮零失败。

`drive.read_json`在Windows以只读共享READ/WRITE/DELETE句柄读取，并保持1MiB、owner、PID代际、租约及产物身份门；减少读者阻止替换的机会，不更改ACL。实际续办无观察超时，但独立持句柄/并发替换压力试验仍遇AccessDenied，不能宣称完全消除所有NTFS替换竞争。此观察与原失败另记短说明，不扩成内部QA平台重构。

## 前轮年代硬件像素模型专项

候选模型全部变化时，新建统一run和当前候选QA，不复用旧模型图：`python -B qa/build.py --name <新名称> --product <本仓候选JAR> --pixel-review`。该显式选项仅向分侧QA包加入已验证的本仓原创 `build/platform-smoke/regression.gba`（65536字节），绑定既有 `gba-result.json` 的内容哈希与exit0、原生成源及 `GbaSmoke.java` 哈希；不重新生成ROM或重跑核心长矩阵。GB仍使用本仓32768字节原创诊断。产品包不加入这些测试ROM。

root统一runner启动并提供实际路径后，先按既有 `--check` 核owner/PID代际/lease/产品及分侧QA哈希，再执行 `python -B qa/pixel_review.py --run <实际编号run> --build build/qa/<名称>/qa-build.json --phase models --pause-after-first`。入口先采NES世界与GB主手两张未经编辑的原始帧，暂停供人工检查轮廓、取景及左右操作区，再继续22款机体、9掌机/VB的20张主副手、总览及21媒体集体台/配件台，共45张。`pixel-model-review-result.json` 只记录当前候选结果，模型准备物品不含已发行卡带元数据；手持道具入口拒绝替换真实带卡掌机。

随后 `--phase live` 只做新几何必要的窄spotcheck：正常刻录GB并装入GB外壳，保留这台已装卡GB，再刻GBA并装入另一台GBA，分别采真实主副手画面、正常保存。GBA再正常装入底座恢复同卡状态，合法距离内拍两个低位旋转角度、正常保存与完整取回，共6张动态原图。不重做大屏四向放拆、浏览器或21核心矩阵。所有照片仍须人工审核屏幕开口、控制区左右和底座高度；实际键鼠或手柄未由前端回调代测。

媒体与控制器的普通固定展示实体面向南，station北侧镜头拍到的是背面。静态45张完成后、live前，分别运行 `qa/pixel_media_front.py --run <实际编号run> --build build/qa/<名称>/qa-build.json --group media` 及 `--group accessories`。它复用统一runner既有owned vanilla execute将真实玩家相机移到展示台南侧，仅补拍两张正面原图及独立JSON；不改模型、实体旋转或已有照片，不造卡带。入口拒绝在已经发卡或live完成后改变该静态相机。

`python -B qa/pixel_gallery.py --run <实际编号run> --build build/qa/<名称>/qa-build.json --require-live` 生成 `build/pixel-realistic-review/index.html`，仅收本run同候选的53张原生图和四份独立结果，不导入旧22机型原图或软件preview。图集核原始路径、SHA、分辨率及PNG解码，分组展示机体、主副手、21媒体/配件正反面和实际运行；不复制、裁切、合成图片，也不会自动将人工图审状态改为通过。

`--phase all` 按模型、两张正面补图、live顺序调用相同入口；实际分阶段审核仍建议显式分开执行。若最终快照保留历史错误提示，应另写简短 `pixel-review-observation.json`，记录原reply身份/哈希和原值，不改写完成结果、不清空历史；图库会链接该说明。模型/输入/保存断言不能扩展为音频或零历史错误结论。

掌机、底座与薄大屏的新世界专项：根统一runner准备精确候选后，先执行上述 `--check`，再执行 `python -B qa/dock_review.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json`。工具复用同一Driver的owner/PID代际/租约/双侧JAR哈希检查，不启动或停止Minecraft。普通fixture只提供空GBA外壳、屏/底座/工具，GB卡带仍由真实刻录界面确认授权后上传生成；不直接构造卡带或存档。

该专项按正常生存放置/挖掘/拾取检查屏幕受阻不消耗、四朝向的真实六格占位和拆从片单件掉落；再检查拿起自动世界播放、前端输入、物品右键同worker全屏、F透明操作、Esc保存抑制重开、收热栏保存、底座安装唯一性/完整NBT往返、同卡恢复和正常取回。`dock-review-result.json` 保留分项状态与原始PNG链接；8张照片覆盖实际手持/全屏、底座两时刻低位旋转，以及3×2屏幕前/侧/背。JSON完成和文件存在仍不能代替人工看图，也不证明实体键鼠、物理手柄或完整整合包共存。

首次原结果中的取景失败不改写：补拍入口 `qa/dock_visual.py` 只接受同run、同候选及原实体卡带；首次补验在拍图前失败时，可用 `--name resumed` 写独立输出，必须引用未覆盖的首次失败及原主结果。每张补图前检查同一活动room/worker，最终leave前再检查ready、worker和身份，之后比对新generation、client/vault哈希和自然退出增量。`--pause-after-first` 可先人工查看第一张PNG再继续；不会重开或停止Minecraft。

使用 `python -B qa/dock_gallery.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json` 生成 `build/dock-review/index.html`。图库只链接原始照片及JSON，核owner/双侧产品SHA、原图路径/哈希/尺寸/PNG解码；原出画和越界照片列历史证据，原主complete=true/48项的scope缺口及独立补验false结果明确保留。图审与实体外设验收不会由图库生成自动提升。

2026-11-08 新模型与浏览器专项使用一个新双客户端 run，按以下顺序执行，保留同一世界和每项原失败；不要与旧 `--scenario` 混合。各入口拒绝覆盖已有结果或同名原图。

```powershell
python -B qa/prepare_profile.py --name <新的专项名称> --build build/qa/<QA名称>/qa-build.json
# 由根统一 runner 使用上一步 validation-profile.json 启动，不能再混用 --mod 等单独输入。
python -B qa/drive.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json --check
python -B qa/drive.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json --model-review
python -B qa/drive.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json --hardware-scenario
python -B qa/drive.py --run <实际编号run> --build build/qa/<QA名称>/qa-build.json --browser-scenario
```

`prepare_profile.py` 只在空的统一 `test-environment/release-test/` 中建立一份最小专项：产品、分侧 QA、已登记 WebDisplays/MCEF 两个原包、runner 固定的完整72个公开 MCEF native 和139字节公开配置。每份独立复制，逐文件验证 size/SHA1/SHA256，调用正常 `release_environment.load_profile/runtime_preflight`。不写 `current.json`，不启动 Minecraft，不改干净源。额外私有 WebDisplays seed 使用其公开的 `miniserv_port=0`，关闭本测试不需要的文件上传服务。该 payload 含 QA，禁止发布。

`--model-review` 建立22个真实方块、对应物品和无卡带数据的媒体展示，按生成计划平移(+32,+26,0)到 y90。使用普通 Minecraft 第一人称相机和原生 `Screenshot.grab`，固定 FOV45、晴天正午并停日夜循环。输出22张固定机型图、总览及9掌机/VB各主副手，共43张；GB主手使用 `23-handhelds-first-person.png`，其余为对应机型名加 `-first-person.png` 或 `-offhand-first-person.png`。新run可用 `--held-review` 只补20张主副手，不能与同run已经建立的模型台重复准备。原始结果只证明采集完整，不能代替看图结论。

`--hardware-scenario` 通过实际生存模式方块摆放、持续挖掘与拾取验证 CMD2 保留；真实刻录 NES 插入 SNES 机型必须拒绝且不消耗。再真实刻录 GB，正常潜行使用装入 CMD5 GBA 外壳，运行 GB 核心、实际输入改变画面，并通过普通交换主副手动作拍摄两张动态持物图；正常保存退出并取出后核对 CMD5 与独立 `qa_preserve` 自定义数据保留。QA不直接生成已刻录卡带。

`--browser-scenario` 只让 host 刻录、接 P1。guest 始终无卡、无会话、下载授权计数0、ROM缓存0、无 worker，并实际请求一次 ROM 验证拒绝。真实 WebDisplays 面通过公共 `addScreen` 建立；绑定走普通潜行持控制器 `useItemOn`。验证无控制席拒绝、控制席无URL权限拒绝、双端持续收帧、实际 CEF 纹理随原生P1输入变化、再次点击恢复原URL、撤权清理、保留正常后续导航和源正常离开清理。

GPU 结果来自 `BrowserLinkClient.nativeBrowser` 返回的真实 `MCEFBrowser.getRenderer().getTextureID()`，在渲染线程 `glGetTexImage` 读回；记录纹理尺寸/CRC/非黑与彩色像素、保存恢复GL打包状态，并保留之前的GL错误。没有构造浏览器或渲染器，没有替换视频帧。`24-desktop-screen-and-browser.png` 与 `25-observer-screen-and-browser.png` 是两个真实客户端同场景原图；脚本提交和GPU纹理变化仍需结合原图确认实际世界显示。

显示牌按“服务端放6块→两个真实客户端确认6块已到→公共addScreen正常广播”分阶段建立，避免WebDisplays建面消息先于Minecraft块更新。首轮05候选的原始同步失败和一次NTFS共享读冲突均保留；两个严格续办门只接受该同run相应实际状态，另写独立结果，不能把新结果覆盖为原轮无失败。

`--shared-scenario n64` / `--shared-scenario arcade` 真实刻录本仓自写N64诊断或22字节空Pong ZIP（由产品正常包装为mxarc），通过物理两控制器和普通设备面板加入P1/P2。第二控制席必须不增加ROM下载/缓存、不启动worker，实际请求当前ROM与真实存档generation均被拒绝。每tick只读公共SharedPlay快照的实际PNG/PCM批次编号和CRC，保留至多128条以便两端同批次对比；输入检查使用N64下半区蓝→黄及Pong右球拍位置，不用球自行运动的全图CRC替代P2有效。最后正常主控离开，原P2由普通P1入口成为唯一新host并恢复已提交状态，旧host再加入P2只收流。MAME跨进程像素严格连续性与实体音频输出另验，不根据状态hash相同声称通过。

下一最小准备可显式带 `--retire-profile <旧profile绝对路径> --retire-profile-sha256 <完整SHA256>`。工具只在旧owner/完整文件集合、所有引用run正常退出及native审计、实际PID代际与已释放lease全部匹配后删除那一份旧准备，再创建唯一新准备；不删除原run、截图、构建或其他缓存。

09后续使用 `--leave-during-join`、`--nes-regression`、`--quick-review`。只对统一runner明确复制的本任务原世界调用 `adopt_scene`，验证原实体后登记QA场景；不改卡带、已有设备、存档或玩家物品。另正常补给普通控制器。退出测试先观察真实N64 pendingJoin，再仅调用一次正常leave，读取公共Room.requestedLeaves历史，等待双方自动结束、最终checkpoint.frame不低于冻结room.frame、保存hash一致及worker自然关闭；不以设备关机替代。NES回归检查双方各自原生worker与同一恢复状态、客席正常离开后主控继续、最后主控正常保存退出。

`--quick-review` 使用已有NES与GB卡拍摄内置/浏览器同局昼夜原图和GBA动态主副手昼夜原图，另读取当前MC进程的 `InputProfiles.availableGamepads()`；GUID只记SHA256。实际枚举不等于实体按键或热插拔已测。所有新结果/同名原图均拒绝覆盖；PNG采样只读原图且须人工确认采样位置，不生成裁图或修饰图。

独立 `--held-live-review` 可在后续新的复制世界run只补原GB冷恢复：核对唯一既有GB卡及原vault SHA、fresh worker确实恢复该SHA，采集四张昼夜主副手原图，正常保存后核客户端/vault一致、worker自然关闭，取出同卡后检查CMD5/qa_preserve守恒。识别卡已在掌机时直接正常使用，不重复装卡动作；不新烧卡或删除原失败存档。

2026-11-08真实04补验通过：候选10 SHA `08493a72b103454f1693658a6c4ce575e9dd58a2ad027254f65b47dbb14e5dde`，QA07，runId `372c19207595455caa521d9d7faba777`。同一GB实例从原252698字节vault SHA `6a8add62c3dc7adf5efdad8e99db5127da67389c3790d6361f3b34b7427268c0` 在fresh worker恢复成功，四张昼夜主副手原图已逐张看；最终frame1612保存SHA `4c1aab00e159e7b5b341074f4dd1641057ece8d36eb5af875b59f22a75c632ed` 与服务端一致，唯一worker正常关闭，正常取卡后CMD5和qa_preserve保持。03原失败保留。汇总画廊与分项范围见 [模型审核](../docs/MODEL-REVIEW.md)，结果含真实枚举 `host=[]/guest=[]`，未冒称实体按键或热插拔已测。
