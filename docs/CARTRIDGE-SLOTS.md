# 实体卡槽、世界存档与加载进度

本文冻结上一轮cartridge-slots-02的实现与原始验证。当前存档已改为按机器及ROM内容归属，新增卡带收纳盒；规则与本轮范围见[机器存档与收纳盒](MACHINE-SAVES.md)。下文原卡归属存档仅描述历史候选，不作为当前规则。

本轮从 `dev@15c1e4a` 继续独立实现，针对背包插卡、实际卡带展示与首次下载体验。实现和本轮联合验收已完成，仍是本地开发，未发布。

## 世界中的权威数据

服务器使用 `MinecraftServer.getWorldPath(LevelResource.ROOT)` 定位当前世界，在其中保存：

```text
<世界目录>/
  muxi-console/
    content/       已上传ROM原件及其内容元数据，按SHA256去重
    states/        按实体卡带UUID保存的已提交检查点及索引
    incoming/      尚未完成的ROM上传临时文件
  playerdata/      玩家物品及其中的实体卡带、掌机卡槽
  region/ ...     主机、底座、屏幕及其物品/连接等世界数据
```

专用服务器通常叫 `world/`，实际以世界配置为准，单机则位于所选世界目录。备份应覆盖整个世界，既保留ROM/检查点，也保留玩家与方块NBT中的卡带身份；仅复制 `states/` 不能还原实体卡的归属和位置。备份运行中的世界需沿用统一的保存/一致性流程；本轮没有增加自动备份服务。

客户端 `muxi-console/roms` 是已校验的可重建缓存，运行核心还会使用自己的临时/工作目录。权威已提交检查点在服务器世界内，包含核心状态、SRAM和RTC数据；客户端缓存不代替服务器存档。玩家自行提供的本地固件及个人设置仍由玩家管理。

## 一个掌机容纳一张实际卡带

每台掌机固定一个卡槽，使用Minecraft原生物品容器组件存储完整卡带ItemStack。插入、取出、装底座与转交保留原UUID、名称及自定义数据；卡槽不是另一份ROM，也不生成第二个实体身份。同游戏另刻一张卡仍有独立卡号和存档。

- 背包中鼠标拿起卡带，右键掌机装入；空鼠标右键已装卡掌机，原卡回到鼠标。
- 鼠标拿着掌机时，也可右键一个明确的兼容卡带槽装入，或右键空槽取出原卡。
- 世界快捷操作使用主手掌机、另一只手明确持有的卡带；不再扫描背包里第一张兼容卡。
- 容量已满、不是有效卡带或平台不兼容时，保留双方原物品；运行或保存中的卡不允许插拔。将运行掌机拿到鼠标上仍算玩家持有，以维持正常保存宽限，不能绕过忙状态。
- 潜行使用可取卡，但需先正常保存并结束会话；背包满时原卡保留在槽内。旧版仅保存卡带元数据的掌机按原UUID读取和迁移，不能凭空恢复旧版本没有保存过的其他属性。

桌面主机仍通过手持卡带右键机身插入。设备管理页的“保存并离开游戏”走正常检查点提交；提交完成后再取卡。Esc仍只退出全屏，电源停止不当作最新进度已提交的保证。

## 屏幕和机型

电脑显示器是独立显示设备，没有卡槽或模拟器运行核心，只连接主机或掌机底座显示画面，与WebDisplays显示面属于同一用途。之前称为“通用机型”的是主机/掌机的默认机身，和显示器无关。既有机型与平台兼容关系继续由服务端校验。

外部插卡显示使用真实装入卡的模型，按各机型的卡槽位置、旋转、尺度及埋入边界裁切；不是另放一个假卡带道具。可见部分与机身共同参与展示范围计算，避免机身全屏把露出的卡裁掉。

## 大ROM传递给另一名玩家

转交时移动实体卡及其元数据，不把整个ROM塞进物品NBT。接收方仅收到卡带不会下载；实际启动掌机或加入主机控制席时，先检查本地缓存。

缓存未命中时，客户端从服务器按24KiB分块接收并直接写临时文件，下载完验证大小与完整SHA256，再原子发布为可用缓存；完整ROM就绪后才启动核心。收起掌机、换卡或离开会话时取消后续请求并清理未完成文件，不能把半文件当完整缓存使用。Esc只退出全屏，仍拿着原掌机时继续加载。再次运行同内容时，缓存通过校验后复用；存档按卡号从服务器读取。

掌机内屏与两种全屏显示对应卡带的真实阶段：检查缓存、正在加载卡带及字节百分比、校验、启动、恢复存档和等待画面。无法量化的阶段只显示阶段名称，第一张真实游戏画面到达后提示消失。下载权限继续按实际持卡/当前控制席检查；N64/MAME共享客席只接收音画和输入，不因看到加载界面而取得ROM或存档。

## 本轮验证

冻结候选 [cartridge-slots-02](../build/cartridge-slots-02/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121553497字节，SHA256 `7552f29a5225dd0987533c871fd7f736933744139fc3e1bd3e0323ce92545ccd`。77个产品Java源、17组3139项检查与380资源/29546项闭合检查通过。与前轮monitor-polish-04的[逐项比较](../build/cartridge-slots-02/compared-to-monitor04.json)为42改/15增/0删，97项领域、运行时、worker、核心及其打包元数据原字节保持；不重记旧21平台矩阵为本轮重跑。初构建01到02只修正三种前插卡的姿态元数据，接点朝机内、标签朝上，没有使用未修正方向的候选做实机验收。

同一02产品、QA02、统一 `client-2/runs/muxi-console-cartridge-01`（runId `59d60c9d9dd14113a22f9a6c4be8776e`）完成真实网络双端联合验收。[15张本轮原图及完整来源](../build/cartridge-review/index.html)直接链接原host/guest截图，不复制、裁切或合成，root已逐张查看；独立模型owner另审库存、加载和硬件13张。原生回调/库存网络点击不是实体OS键鼠或实体手柄验收。

- 实际刻录6张卡，包含一张由已验证64KiB第一方ARM诊断末尾补FF到8MiB的派生GBA诊断；基底、生成源与派生SHA全部固定，未捆入产品。单槽、满槽、非卡/不兼容、正反向背包操作、明确另一手快插及满背包保留原卡通过。完整卡名称/自定义数据/UUID往返不变。
- 旧flat格式测试只将一张真实已发行卡移动成其原UUID的旧掌机表示，不发行新身份或造存档；一次取出、重复取不复制、重新安装成CONTAINER通过。运行掌机拿到鼠标后，真实保存期间的插拔收到了服务器忙拒绝，提交完成才可取出。
- 接收方正常拾取地上的同一张8MiB原卡，仅持卡时下载0；实际装入并选中才按需冷下载342块，最后一块不足24KiB。三图采集前后真实字节进度范围为2–7%、15–21%、29–34%，实际原图显示4%、16%、31%；世界LCD和两种全屏文字正向、单层且在屏内。第一真实画面后加载层消失，原生J输入改变自写诊断画面。
- 同一卡带再次启动没有新增ROM chunk，实际缓存SHA/大小吻合，并恢复已提交checkpoint。5个唯一ROM对象及5张已运行卡的latest state磁盘SHA逐个匹配；未运行的legacy卡不冒称有第6份存档。权威路径实查位于服务器世界下。关服后另查两份playerdata压缩NBT：host五卡、guest一张内置卡的原UUID字节各唯一出现，guest原生CONTAINER和卡自定义标记均已持久化；服务器日志确认玩家和全部维度保存完成。
- 手持、原生Inventory图标、MODEL、底座和NES/PCE不同前插槽实图通过；硬件/游戏图取景前后都核同card/room/epoch/worker仍ready并有真实首帧。GBA背槽卡较短，背照证明厚度/接触，不能据此声称读到完整卡标签。真实卡右键电脑屏仅建立显示连接，不消耗卡、不建独立room或worker。
- 底座、NES与PCE均通过设备面板实际“保存并离开游戏”后才完整取卡/整机，6张实体卡最终各唯一。最终大卡ROM SHA `9ee9c3ee53e43070835f3c2710526ce4196e4f40132be8c9aec0afeae0419339`，底座最后checkpoint SHA `f99f532ad0a72887db78c814cf6848d7e55ce76fe7524fc0bb8e2fec3e6a3785`，关服后重哈希相同。

| 原始记录 | 实际范围 |
| --- | --- |
| [inventory original](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-inventory-result.json) | false保留；17条中前16条通过、1原图。满包断言错误地要求无自动开局，实际正确触发忙保护，原卡未变。 |
| [inventory continuation](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-inventory-continuation-result.json) | true，8项、1新图，按原SHA/实际原卡/正常保存状态继续；精确slot/full拒绝、快插及保存忙门闭合。 |
| [cold original](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-cold-result.json) | false保留；1项通过、0图。真实抛出距离超出固定拾取站位，卡仍在地上，不能把等待超时当成卡消失。 |
| [normal pickup](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-pickup-result.json) | true，3项、0图，移动接收方到实际落点正常拾取同卡，原UUID/完整NBT不变、下载仍0。 |
| [cold continuation](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-cold-continuation-result.json) | true，22项、5图，严格引用原false/拾取结果；补齐真实冷读、输入、缓存与恢复。 |
| [hardware](../../test-environment/client-2/runs/muxi-console-cartridge-01/console-qa/cartridge-hardware-result.json) | true，32项、8图，实际插卡外观、纯显示器与正常保存/唯一取回。 |

共83条记录、82条成功，不称为82个互不重叠测试；两份原false及原失败断言均保留，后续分别按精确SHA引用，不能改写为原阶段零失败。没有重烧替换原卡、重放旧mutation或伪造进度来补齐结果。

host/guest各4个worker自然退出，强停/失败均0，双方worker为空；三MC JVM最终正常exit0，native审计complete且remaining/unknown/errors/actions为空，统一launcher退出0。运行副本957583232字节由统一入口自动清理；唯一准备经现有retire入口核精确profile SHA、全部文件、已关闭run/PID代际及释放lease后移除526840212字节，构建与原始证据保留。

未做实体设备、真实断网/重连、任意第三方私有容器、光影/Linux或完整整合包共存验收；没有新增自动备份服务或断点续传承诺。第一方发行许可及第三方对应源码交付沿用[状态](STATUS.md)的限制。未改Portal、干净源或生产，未发布。
