# N64 / 街机共享执行

N64 的 GPU 执行和 MAME 离散电路跨进程状态并未获得逐像素一致性保证。05候选的实际 MAME Pong save/load 进程续接 CRC 不同，不能用放宽 CRC 掩盖多工作进程锁步的不确定性。共享执行把这两类平台改为一台主控制席运行核心，其余占用控制席通过服务器收取主机真实画面和音频，继续提交各自的控制输入。

这项实现与 WebDisplays 可选适配独立。`BrowserFrameCodec` 是本模组的纯 PNG 编解码器，没有浏览器模组时仍可运行；SharedPlay 不创建网页、MCEF实例、HTTP 服务或额外监听端口。

## 权限与生命周期

`ConsoleClient.sharedLease()` 仅为当前 N64 / ARCADE 会话提供 `Lease(room,epoch,port,leader)`。非主控客户端收到服务器原有 `session_start` 后发送 `shared_attach`；服务器重新验证实际房间、epoch、席位占用、平台、距离/设备/卡带条件。真实占席成功后记为 `ready="shared"` 并回复 `shared_ready`。客席不下载 ROM、存档或核心，不启动 native worker。

客席仍是原会话内的玩家控制席。它接收服务器输入时钟、通过原输入协议提交本席位按键；主控制席继续收到所有控制器输入并执行唯一的模拟器。SharedPlay 不给纯旁观者音画、ROM 或输入权限；纯旁观的实体网页显示仍使用独立 BrowserLink 权限与像素路由。

音画提供者必须同时是当前 host、真实控制席、有效参与者、已完成核心 ready，且房间已有核心 fingerprint。服务器只向仍占用本房间控制席、已 `shared_attach`、仍符合实际参与条件的其他玩家转发。接收方 port 由服务器逐人写入，客户端再次核对当前 room / epoch / port / 非leader条件，不能用包中自报的 owner 绕过。

epoch 变化由既有会话同步启动新租约；编码任务携带 generation，stop 后已排队任务的回调不会向新会话发送旧数据。服务端 gate 使用 `WeakHashMap<Room,Gate>`，不在 value 中持有房间对象，并按 epoch 更换序号/速率状态。主控制席仍走现有 checkpoint/正常退出链；新的主控如需创建核心，仍走当前卡带/控制席的 ROM 权限与存档恢复流程。SharedPlay 没有证明 MAME 原生存档恢复已逐像素精确，也不代替 N64 原生生命周期问题的修复。

## 画面

主机把与内置屏相同的已旋转 RGBA 送入 `SharedPlayClient.frame`。单独编码器默认最多20fps，最长宽度512、最高512，保持比例、不放大；完整 PNG 必须能放入24576字节消息，复杂画面继续等比缩小。

服务器检查 source frame 不超过当前模拟进度，序号严格增加，速率20fps、短时突发2帧。PNG 使用与 BrowserLink 相同的严格子集检查：签名、CRC、唯一 IHDR/IDAT/IEND、尺寸、精确有界解压和行过滤，不允许可执行内容或附加元数据。接收方再次验证 PNG 及声明尺寸，调用 Root 的 `sharedPicture` 更新真实游戏纹理；它不再自行模拟同一游戏。

## 音频

Root 每个服务器20Hz输入批次把该批所有 native PCM 合并，向 `audio(frame,pcm,rate)` 调用一次。主机使用现有 `StereoResampler` 连续重采样为48kHz、双声道、有符号16位小端 PCM，保留跨批次分数相位。

正常50ms批次约9600字节。单条 `shared_audio` 最多24576字节，较大合法批次最多4片、合计98304字节；每个20Hz批次的 frame 唯一，片序从0开始且必须连续，除最后一片外都必须完整。服务器限制为192000B/s，突发容量98304B；片段超出总大小、错序、重复、过期或元数据变化均不转发。片数据始终完整包含 stereo sample pair。

客席只保留一个有界未完成批次，超时1秒不播放，新的批次可以替换未完成的旧批次；凑齐后按原 channel 顺序还原，调用 Root 的 `sharedAudio(...,short[]48k)` 一次。Root 原有有界音频播放队列负责输出设备。两端编码队列每种媒体最多一个排队任务，拥堵时丢新候选，不堆积无限音视频。

| 方向 | 消息 | 字段 |
| --- | --- | --- |
| 客席→服务器 | `shared_attach` | room / epoch / port |
| 服务器→客席 | `shared_ready` | 服务器确认的 room / epoch / port |
| 主控→服务器→客席 | `shared_frame` | room / epoch / port / frame；下行附 width / height；PNG data |
| 主控→服务器→客席 | `shared_audio` | room / epoch / port / frame / part / parts / totalBytes / rate=48000；PCM data |

SharedPlay 使用根版本化 Minecraft 传输，`mx-console-2`。包中没有 ROM、BIOS、存档内容或任意核心命令。不同平台的其他软件核心继续走既有机制。

## 接线与验证

Root 提供 `sharedLease`、`sharedReady`、`sharedPicture`、`sharedAudio`，在 `ConsoleClient.receive/start/stop/方向处理后帧/每批PCM` 和 `ConsoleServer.receive` 接入 helper；共享客席收到普通输入时钟只更新 tick，host 执行唯一核心，只有 host 参加核心 fingerprint ready，已有 CRC 锁步只用于其余软件平台。

`tests/stream/SharedStreamChecks.java` 57项纯 Java 检查通过，覆盖完整 signed PCM16/小端/channel互解、4片准确重组、20fps和音频字节预算、重放/乱序/超时、单批缓存及 lease 边界。全仓 common/domain 与 Root 接线编译通过。它们没有伪造 Minecraft 玩家、权限或浏览器，也不算真实多客户端通过。

07/run02真实双端已经验证N64/MAME唯一host worker、客席ROM/state拒绝、无新缓存/worker，以及双方独立输入和同批次PNG。MAME使用实际Pong两球拍位置和非空PCM验证；N64原创VI诊断没有AI音源，音频计数0不记为音频验收。原主控正常结束后，原客席由普通P1入口恢复同一已保存状态，旧主控可作为P2只接收流；不承诺不中断迁移。

07的MAME同步中Esc退出遇到真实排队缺陷，最后以设备电源正常结束，`finalEscSaveVerified=false`保留。09/run03通过真实N64 pendingJoin期间一次leave排队、最终checkpoint/客户端状态一致、双方自动结束及自然worker退出；普通NES双worker回归亦通过。原失败、各候选边界和原始结果见[模型审核](MODEL-REVIEW.md)。MAME跨进程逐像素续接、实体音响听感、实体手柄及完整整合包共存仍分别验收。
