# 逐平台模型：Minecraft原图审核

## 掌机、底座与薄屏接续

当前候选 `dock-screen-01` 的[独立图库](../build/dock-review/index.html)包含9张当前原图、4张历史取景图和3份原始结果；产品SHA `b9d33b38d2c8cf0045b79d3ef9be38922a63f8fb373ae9030ac878e5212d71fb`，runId `ff9126cce10740e8b3fdaa6674adeb73`。无GUI手持、F透明手持操作、右键同worker全屏、3×2活动薄屏、低位旋转两个时刻及侧背图均已查看。机体最低点4/16、距槽顶1/16，没有高悬浮；侧面薄屏外壳完整，背板图明确为底边贴帧的近照，不冒称完整背面外壳图。原22机型几何未改，下文图库继续保留。

原主结果的05/06近照出画，07取景超出8格触发会话结束；其48项完成不作为该段最终主动保存证明。首次补验观察超时false保留；独立resumed补验19项要求每张图前同一底座room/ready/worker仍有效，最后真实正常leave提交新generation、client/vault同hash且worker自然关闭，原掌机完整取回。详见[当前状态](STATUS.md)与[操作说明](HANDHELD-DOCK.md)。所有PNG直接链接原run，不复制、不裁切合成；三MC JVM正常exit0，native审计无残留，运行payload已自动清理。

2026-11-08已在根统一测试环境完成22机型世界原图、20张完整主副手原图，以及GB动态掌机、NES浏览器和N64/MAME共享运行的实际采集与分项核验。原失败及未验范围见下文；原图完整不等于全部功能通过。不得以软件绘图、渲染器预览或旧通用模型截图替代对应机型。

## 固定审核物料

`python -B tools/generate_assets.py --check --gallery-plan` 输出 `build/model-review-plan.json`。其中22个station依照CMD排成5列，起点y64；根runner可整体平移到专用平台高度，各相机使用相对坐标。每台包含真实方块的model属性、对应CMD物品、媒体、外接屏位置和照片名。模型目录为 `common/src/main/resources/assets/muxi_console/model_catalog.json`。

每款至少一张普通Minecraft世界原图，能同时辨认轮廓和实体操作区。9掌机及VB另需真实玩家手持视角；桌机另需实际放置方块与物品形态一致。NES/FDS与SNES、GB与GBC、NGP与NGPC、WS与WSC须分别拍摄，不能只拍颜色总览。单人街机与双人街机均要让操纵台占用数可见。VB必须拍到双眼镜筒与桌架。

| 原图文件 | 机型 | 原图文件 | 机型 |
| --- | --- | --- | --- |
| `01-nes.png` | NES | `12-a2600.png` | A2600 |
| `02-snes.png` | SNES | `13-a7800.png` | A7800 |
| `03-gb.png` | GB | `14-lynx.png` | Lynx |
| `04-gbc.png` | GBC | `15-ngp.png` | NGP |
| `05-gba.png` | GBA | `16-ngpc.png` | NGPC |
| `06-md.png` | MD | `17-ws.png` | WS |
| `07-n64.png` | N64 | `18-wsc.png` | WSC |
| `08-sms.png` | SMS | `19-vb.png` | VB |
| `09-gg.png` | GG | `20-fds.png` | FDS |
| `10-sg1000.png` | SG1000 | `21-arcade.png` | 单人街机 |
| `11-pce.png` | PCE | `22-arcade-2p.png` | 双人街机 |

额外保留 `00-gallery-overview.png` 总览、`23-handhelds-first-person.png` 手持示例、`24-desktop-screen-and-browser.png` 桌机同时连接本模组屏幕及实际浏览器mod显示牌的画面。浏览器mod缺失或未连接应明确列为未验，不能用普通图片方块冒充。

## 拍摄与审核范围

使用根 `scripts/ops/test_environment.py` 分配的真实Minecraft客户端，当前源码构建的同一个JAR。新建平整审核台，普通光照、晴天正午、建议FOV45，隐藏仅用于准备的辅助界面。相机应在正面偏右、略高于机身的位置，看清前面、顶部及右侧；小掌机额外正面近照。原图保留客户端原始分辨率，不能通过抠图或合成隐藏缺失贴图与遮挡。

逐款检查：平台轮廓对应；媒体对应；无紫黑图集错误；屏框不遮住动态画面；朝向、画面UV不镜像；放置方块形态与物品一致。四朝向可另用总览覆盖，但四朝向语法校验不等于实际画面检查。内置屏幕的输入/声音/保存、多玩家控制与外部浏览器显示属于交互验收，应附实际结果，单张模型图不能证明这些功能完成。

根runner统一启动与停止Minecraft，本资源任务不私建运行环境、不单独启动客户端。正常退出和payload清理由统一入口负责。

## 原图画廊

当前合并画廊位于 `build/model-review/index.html`，直接链接四个真实run的73张原图与13份结果。生成时核对run owner、runId、双侧产品输入SHA256、每张原图SHA256、PNG解码与至少640×360分辨率；不复制或编辑照片：

```powershell
python -B qa/gallery.py --world-run C:/Users/ranzh/workspace/dev/muxigame/test-environment/client-2/runs/muxi-console-model-browser-01 --current-run C:/Users/ranzh/workspace/dev/muxigame/test-environment/client-2/runs/muxi-console-model-browser-02 --visual-run C:/Users/ranzh/workspace/dev/muxigame/test-environment/client-3/runs/muxi-console-model-browser-03 --visual-run C:/Users/ranzh/workspace/dev/muxigame/test-environment/client-2/runs/muxi-console-model-browser-04
```

世界部分只收首轮22机型与总览，排除首轮裁切的持物图；完整20张主副手和最初动态部分收第二轮原图。第三轮保留昼夜同屏及GB冷恢复原失败，第四轮补原GB存档恢复与昼夜主副手。所有共享结果中的原图也保留，结果栏直接链接原JSON并标明未完成和后续结果。独立视觉轮可重复追加 `--visual-run <新run>`，读取该run的 `quick-review-result.json` / `held-live-review-result.json`，以及存在的退出回归结果。照片不因后续场景完成而覆盖旧结果。`tools/generate_assets.py --check --review-gallery <原图目录>` 仍可生成单目录模型清单；两入口均只证明文件完整，不能代替人工看图。

固定机型图是方块模型外观证据；并未把21平台的登记、核心装载或离线模型检查当作各平台所有游戏兼容。实体键鼠/手柄按键、热插拔、实际音响、光影与完整整合包共存仍须单独验证。

## 2026-11-08 原生审核入口

新独立 QA 已扩展 `qa/build.py`、`qa/drive.py` 和分侧 Java fixture；运行规则见 [QA说明](../qa/README.md)。根统一 runner 启动一个新双客户端 run 后，依次执行 `--model-review`（只补持物时用 `--held-review`）、`--hardware-scenario`、`--browser-scenario`。QA复用每个角色的 owner、PID创建代际、租约、实际产品/QA哈希检查；不会启动或停止Minecraft JVM。

当前模型入口生成43张原始 framebuffer PNG：22机型、总览、9掌机与VB各主副手。GB主手图名为 `23-handhelds-first-person.png`，其他为机型固定文件名追加 `-first-person` / `-offhand-first-person`。每张记录原分辨率和SHA256，原图不裁切、不合成、不由软件绘图补全。FOV45，拍机身时隐藏HUD，拍手持时显示真实玩家手臂和物品。

硬件入口另检验实际摆放/生存挖掘拾取保留CMD、错误机型拒绝、CMD5 GBA装GB卡再取出后外壳与其他自定义数据守恒。浏览器入口另出 `24-desktop-screen-and-browser.png`、`25-observer-screen-and-browser.png`，记录实际CEF GPU纹理前后CRC、持续像素接收与纯观众无ROM下载/缓存/worker；并验证权限、解绑恢复网址及源离开清理。编译、文件完整与GPU变化均不会自动把 `visualAcceptance` 改成true；实际看图结论以本页分项记录为准。

### 首轮原生结果（05候选，手持未通过）

`test-environment/client-2/runs/muxi-console-model-browser-01` / runId `b0e0ff42920a4b64a5fe947aca1dd815`，产品SHA256 `a373967d0d39bbee764fed88e4179b5492ae0267590fb7b19dd1c444668649cb`。22机型世界原图和总览逐张已看，差异、VB双镜筒/支架及单双人操纵台可见，无紫黑缺图；10张原手持图下缘严重出画，手持视觉明确未通过，原图保留。硬件流程通过型号摆放/掉落、错误媒体拒绝、GBA装GB/取出后的外壳与自定义数据守恒。

同run浏览器初始建面时序和一次NTFS回执读冲突原失败均保留。经正常区块重载及严格独立续办，`console-qa/browser-source-resumed-result.json` 完成真实双端内置屏/浏览器联动、权限和网址恢复检查；实际CEF纹理CRC两端均 `256039b3→56865cdf`，观众最终收196帧而ROM下载/缓存/worker均0。原生同屏图在host/guest各自 `screenshots/source-resumed-24…png` / `source-resumed-25…png`，已逐图查看，夜间内置屏偏暗另交产品修正。三Minecraft JVM正常exit0，native审计无残留/未知/清理动作，统一payload已清。07候选的主副手摆位、自发光和新共享运行须以新run结果补验。

### 第二轮原生结果（07候选）

`test-environment/client-2/runs/muxi-console-model-browser-02` / runId `43ad1bf49a3945d8ba9595380fa40d07`，产品SHA256 `5d311c2db4c931ba4215617969246f7e150cc21f0d4e3e9c2bf8461243ac0ad8`。20张9掌机/VB主副手图逐张已看，机体完整、屏幕与操作区可见；首轮下缘出画问题已修正。22世界模型继续引用首轮原图，块资源复用由根协调者逐字节核对，画廊逐张保留原产品哈希。

`hardware-scenario-result.json` 完成真实CMD2摆放/挖掘掉落、错误机型拒绝、CMD5 GBA装原创GB并取出后的外壳和 `qa_preserve` 守恒。原生输入CRC `efe393a6→74a33688`，`26-gba-shell-gb-game.png`、`27-gba-shell-gb-live-main.png` 与 `28-gba-shell-gb-live-offhand.png` 已查看；主副手确实显示同一活动GB画面。

`browser-scenario-result.json` 本轮直接完成，未用续办。真实WebDisplays建面分阶段等待六块到达，双端CEF纹理CRC均 `1eecb87d→c4038634`，纯观众最终收186帧/185次提交，ROM下载授权、缓存和worker均0；实际ROM请求被拒。控制权限、URL权限、解绑恢复、撤权清理、后续导航保留和源离开清理通过。同屏两张原图已查看，NES实际机体、内置屏与WebDisplays播放同局。07内置屏仍受方向光影响而比浏览器偏暗，昼夜修正不能据此记通过。

N64使用本仓原创无AI音源诊断。`shared-n64-resumed-result.json` 记录真实P2输入令下半区蓝→黄、两端同frame PNG CRC匹配、客席实际ROM/state请求拒绝、无新ROM下载或worker，以及model7/外接屏/WebDisplays同局原图；该文件随后因QA观察超时仍为未完成，保留原样。独立 `shared-n64-handover-result.json` 完成主控正常结束后原客席从同一物理机P1入口恢复存档，恢复SHA与原主控最终提交SHA相同；旧主控P2无worker且无新下载，随后双方正常离开。两端同frame129 PNG CRC为 `43fcbd98`。源和客席音频计数均0符合该诊断内容，明确不计N64音频验收，不承诺不中断自动迁移。

MAME/Pong使用22字节空ZIP经产品正常刻录为mxarc，model22双人操纵台实际播放。P1投币后，P2的真实轴输入令右球拍中心在原始Minecraft PNG中从 `0.4249→0.0706→0.9496`，左球拍保持 `0.4249`；host/guest一致，未用全图CRC或球自行运动代替控制证明。客席实际ROM/state请求拒绝且无额外worker；同frame1588 PNG CRC `d89d9598`、同frame1594非空PCM-S16LE CRC `6a86b275` 匹配。`shared-arcade-result.json` 后段遇NTFS命令替换冲突，失败和同步中退出问题保留。

`shared-arcade-finalized-result.json` 独立完成实际反向主客席审计和PNG/PCM批次匹配，再由正常设备电源按钮关闭唯一核心与远端会话；无强停或失败worker。该结果明确 `finalEscSaveVerified=false`，不能当作同步中Esc保存退出通过；MAME跨进程画面严格连续性与实体音响亦未声称通过。09候选将单独补自然pendingJoin期间一次正常退出、普通NES双端回归、昼夜显示及实际手柄枚举，本页此时未记该轮完成。

本轮三个Minecraft JVM最终均正常exit0，native审计complete且remaining/unknown/errors/actions为空，统一payload已清理。以上只覆盖这些实际原创诊断与场景，未生产发布。

### 第三轮后续回归（09候选）

`test-environment/client-3/runs/muxi-console-model-browser-03` / runId `d4c67491ffb1490bb73ebe6e2a183fd5`，产品SHA256 `e2327323c9533c1d65c0810edabf8a1c6a6b2e3f0c5d690670cf2715a0c6a383`。统一runner在确认第二轮正常关闭且世界锁已释放后复制其世界，QA只登记原真实设备，不替换卡带、存档或玩家物品。原N64/NES/GB/MAME实例与内容保留。

`leave-during-join-result.json` 通过真实同步中退出：N64在frame348有guest pendingJoin，host只正常leave一次，服务端真实 `requestedLeaves` 于tick4999接受host；最后提交checkpoint仍为frame348，客户端保存hash与vault相同，双方自动收到结束，无room/worker，原生正常exit且无强停。没有使用设备关机替代退出。`nes-regression-result.json` 通过普通NES双方各一原生worker、同epoch/CRC/恢复hash，guest正常离开后host继续，host最后正常保存离开。

`09-display-day.png` / `09-display-night.png` 已人工查看，内置屏与WebDisplays显示同一红白画面。只读原图正确画面位置：两时段内置白均(252,252,252)、红(216,39,0)，浏览器白(255,255,255)、红(219,40,0)；环境与机壳夜间正常变暗，屏幕维持亮度。实际两个MC进程的 `InputProfiles.availableGamepads()` 均为空，实体手柄按键/热插拔未验。

该轮 `quick-review-result.json` 整体仍为失败并保留原样：随后尝试在新进程恢复旧GB存档时出现 `Core refused to save state`，没有完成计划的GB动态主副手昼夜图。真实worker正常退出，未伪造恢复或画面。已交产品修复冷启动恢复前保存回滚状态的路径；本段不把该GB补验记通过。画廊此时共有69张原图和12份结果，三轮各自的产品哈希与失败状态均可追溯。

### 第四轮GB冷恢复与昼夜掌机（10候选，通过）

`test-environment/client-2/runs/muxi-console-model-browser-04` / runId `372c19207595455caa521d9d7faba777`，产品SHA256 `08493a72b103454f1693658a6c4ce575e9dd58a2ad027254f65b47dbb14e5dde`。仅复制已正常关闭的第三轮世界，沿用原GB实例 `f729c4e8-aa53-41b4-a8fd-0f57243cbcaf`、ROM SHA `18babbc1c1836a6c9221984059b66d4b199e4c288173d5ecde4871a3ab20901a`，未重新刻录或删除原失败/存档。

`held-live-review-result.json` 通过fresh原生worker恢复原252698字节vault：旧generation `42db3eb6-947b-4e6f-a295-3df19f520766`，原frame1024，预期及实际恢复SHA均 `6a8add62c3dc7adf5efdad8e99db5127da67389c3790d6361f3b34b7427268c0`。`gb-cold-restored-day-main.png`、`gb-cold-restored-day-off.png`、`gb-cold-restored-night-main.png`、`gb-cold-restored-night-off.png` 四张原图逐张已看，GBA机体、屏幕、按钮完整；活动GB内屏在夜间保持亮度，机壳与环境正常变暗，照片记录真实原生frame400/734/1057/1379。四张原图正确屏幕位置的只读灰/白像素均为(168,168,168)/(252,252,252)。

随后一次正常退出提交新252698字节存档frame1612，generation `1c1c5a88-5239-4173-90c0-935d66b3bad8`，SHA `4c1aab00e159e7b5b341074f4dd1641057ece8d36eb5af875b59f22a75c632ed` 与客户端保存结果一致；唯一worker自然关闭，无强停、失败或观察超时。正常取出同一GB卡后，GBA外壳CMD5和 `qa_preserve=original-gba-shell` 保持。两客户端实际手柄枚举仍为空，实体按键/热插拔范围不变。

10与09的模型/渲染/服务端/共享链/资源及核心库由根协调者逐字节核对一致，本次只补修复后的冷恢复及掌机照片，不重演已核的模型、浏览器和共享场景。第四轮三个Minecraft JVM已由根统一正常关闭，均exit0，native审计complete且remaining/unknown/errors/actions为空。原09失败保持；本次补验不覆盖MAME跨进程像素严格连续性、实体手柄或实体音响等未验项。最终画廊73张原图均直接链接各run原文件，无拷贝、裁图、合成或修饰。
