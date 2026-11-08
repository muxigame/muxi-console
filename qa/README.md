# 木犀游戏机独立原生 QA

本目录从空白编写，只读取本仓新产品 API 和统一 runner 文档。两个 QA JAR 分侧安装，Java 包为 `net.muxigame.consoleqa`，不会包含任何产品 class，也不发布。

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
