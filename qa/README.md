# 木犀游戏机独立原生 QA

本目录从空白编写，只读取本仓新产品 API 和统一 runner 文档。两个 QA JAR 分侧安装，Java 包为 `net.muxigame.consoleqa`，不会包含任何产品 class，也不发布。

构建：`python -B qa/build.py --name <新名称> --product build/<候选>/muxi-console-0.1.0-dev.1+mc1.21.1.jar`。嵌入本仓原创 `build/runtime-smoke/two-port-battery.nes`、`build/platform-smoke/original.gb` 和生成的 `build/model-review-plan.json`，逐项固定哈希。产品内模型目录必须与当前资源原字节一致。编译固定 Java21、512MiB、单 CPU；构建不启动 Minecraft。

只能由总仓统一 `scripts/ops/test_environment.py run` 创建 `test-environment/client-N/runs/` 运行。当前模型/浏览器专项使用下述最小 `--release-profile`，不再混用单独模组输入。默认新世界；明确授权的后续回归可由统一 runner 复制同任务已正常关闭的世界，必须核对原owner/正常退出和复制声明，不复制正在写入的世界。测试客户端为真实网络玩家；服务端 fixture 只放置平台和三台设备、给空白卡带/控制器/掌机，不生成已刻录卡带。

运行中可用 `python -B qa/drive.py --run <实际编号run路径> --build build/qa/<名称>/qa-build.json --check` 核实 owner、runId、每个真实 PID/创建代际、租约和运行中的产品/QA 原始哈希；`--prepare` 准备服务器场景并读取两真实客户端。驱动不会启动或结束 Minecraft，也不会强杀 worker。

双端连接后使用 `--scenario` 运行完整最小场景：真实刻录界面的拖文件回调、默认拒绝与重新声明、两次刻录及内容去重、原生丢弃/拾取转交、强制回源下载权限、真实方块交互与两控制席、正常 InputSource 前端到服务端再到两个 native worker 的独立输入与画面 CRC，以及五张实际渲染截图。驱动先调用正常产品离开并等待 worker 关闭，最后仍由统一 runner 正常停止三个 Minecraft JVM。

本目录实现和编译通过不等于场景已通过。`scenarioComplete` 只根据本轮真实结果设置；截图仍须人工/图像查看验收，`visualAcceptance` 与 `physicalOSInput` 始终 false。文件拖入、Checkbox/Button、InputSource 均是正常前端回调测试，不冒称实体鼠标、实体键盘或真实手柄操作。

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
