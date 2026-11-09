# 历史0.1.0开发目标与验证范围

本页下文保留0.1.0发布前的冻结快照；其中“当前”“未发布”、21平台、联机线未实现及许可待定均描述当时状态。当前0.2.1已发布，支持24平台/25机型，原创仓库采用MIT，GB/GBC/GBA串行及NDS/3DS无线连接已有独立专项。最新发行、验证范围与未验限制请看[README](../README.md)、[升级工作单](PLAYSTATION-UPGRADE.md)和[0.2.1修复说明](RELEASE-NOTES-0.2.1.md)，勿将本页旧状态用于发布判断。

当前是独立开发候选，未发布。Cartridge仅参考公开功能和体验，不读取或迁入其实现；第一方代码、传输、UI、资产、诊断和QA在本仓自行实现，模拟器核心与依赖另行登记。精确候选及最终MC验收状态见 [STATUS](STATUS.md)，玩家操作见 [指南](USER-GUIDE.md)。

当前存档规则为每台机器、每个ROM内容独立进度，同内容换卡续玩，借卡不带进度；底座继续使用掌机身份。27格卡带收纳盒及本轮限定原生验证见[机器存档与收纳盒](MACHINE-SAVES.md)。下列21平台核心矩阵是既有冻结结果，不等于本轮重新逐平台执行。

当前cartridge-slots-02继续保留下面的21平台范围，新增完整实体卡槽、实际插卡显示和真实加载进度；前轮完整钮帽、原生键位、双全屏及2×2×1显示器保留。其97项领域/运行时/核心/worker及元数据保持原字节，旧平台长矩阵不重记为本轮执行；本轮GB/GBA/NES/PCE实际交互、8MiB转交及模型证据见[专项审核](CARTRIDGE-SLOTS.md)。

## 21平台

原公开范围为NES、FDS、SNES、GB、GBC、GBA、MD、N64及可选现代MAME；新增12类为SMS、GG、SG-1000、PCE、Atari2600/7800、Lynx、NGP/NGPC、WonderSwan/Color、Virtual Boy。原19个平台与模型编号保持，FDS=20、Arcade=21；双人机台为额外外观22，不另算平台。

| 平台 | 核心 | 已实际运行的独立诊断 |
| --- | --- | --- |
| NES | FCEUmm | 原创双口6502、P1/P2输入、SRAM及恢复 |
| FDS | FCEUmm | 原创诊断固件从双面磁盘读入RAM执行；弹出/换面/插入、恢复 |
| SNES | bsnes2014 Performance | 原创65816画面、主按钮、跨进程恢复 |
| GB / GBC | SameBoy | 原创程序、内核内置独立boot、主按钮、跨进程恢复 |
| GBA | mGBA | 原创ARM、A/Start、SRAM、坏组件回滚、跨进程恢复 |
| MD | BlastEm | 原创68000、主按钮、跨进程恢复 |
| N64 | Mupen64Plus-Next | 原创MIPS启动/VI/SI-PIF、真实OpenGL、P1/P2、恢复与正常关闭 |
| SMS / GG / SG-1000 | Gearsystem | 原创Z80、主按钮、跨进程恢复 |
| PCE | Beetle PCE Fast | 原创HuC6280画面、主按钮、跨进程恢复 |
| Atari 2600 | Stella2014 | 原创6502/TIA、fire输入、跨进程恢复 |
| Atari 7800 | ProSystem | 原创MARIA显示、跨进程恢复；未专项验按钮 |
| Lynx | Beetle Lynx | 原创BS93对象及诊断固件、跨进程恢复；普通LNX与按钮未由该诊断覆盖 |
| NGP / NGPC | Beetle NGP | 原创显示及跨进程恢复；未专项验按钮 |
| WS / WSC | Beetle WonderSwan | 原创8086画面、主按钮、跨进程恢复 |
| Virtual Boy | Beetle VB | 原创V810显示、跨进程恢复；未专项验按钮 |
| Arcade | MAME 0.289 | 官方无ROM Pong电路；新增16项P1/P2各自球拍输入验证 |

基础 [21平台矩阵](../build/platform-smoke/platform-matrix.json) 为3534项，绑定各诊断内容及核心哈希。后续N64关闭、显示比例、旧MXS1和Pong输入另有窄回归。10另有[21平台冷启动恢复](../build/cold-load-all-21-02/result.json)548项：新进程未step即恢复另一进程的正常状态、执行并正常关闭，另测首次保存及坏组件回滚，共63个worker。NES/GBA初始SRAM保持不变，旧05的NES/GBA MXS1也由当前fresh worker恢复通过。矩阵的“跨进程恢复”指状态API接受后继续实际执行，MAME逐像素续接不一致没有被改写成通过。所有结果均不代表商业游戏库、所有映射器、外设或联机线兼容；DLL导出探测不计游戏运行。

## 当前候选与产品链

最新手持候选为 `held-feel-04`，73源/2425项纯检查、354资源/23534检查；32次新原始采图及77+16项联合验收见[当前状态](STATUS.md)和[手持审核](HANDHELD-FEEL.md)，原回执占用失败false保留。手臂、薄壳、可调键位/专注隔离和按钮高光更新，核心/worker/domain与前轮原字节相同，大屏保持；不将旧平台矩阵记为本轮重跑。下述候选号保留各阶段的实际证据。

10为66个第一方Java源文件，13组共2081项纯检查通过；精确JAR SHA256为 `08493a72b103454f1693658a6c4ce575e9dd58a2ad027254f65b47dbb14e5dde`。新平台已接刻录、服务器复检、机型兼容、输入与持久化。冷恢复测试的全部17个LibretroCore类文件与10内worker逐字节相同，见[绑定结果](../build/cold-load-all-21-02/candidate10-binding.json)；这项比对不代替10的真实MC验收。

- [07实际准入](../build/admission-final-07/checks.log)：21个平台，共89项，包含Lynx BS93与宿主对象拒绝、MAME保留驱动名的 `.mxarc`。
- [07打包媒体](../build/media-final-07/result.json)：N64/FDS/MAME/Lynx共124项，经过真实RuntimeFiles提取、身份校验、装载、保存、恢复、正常退出及native清理。N64/FDS/Lynx实测续接画面一致；MAME不声明逐像素一致。
- N64/MAME已采用单主控SharedPlay。07真实run02的N64已验证P1/P2红蓝/黄等独立输入、客席ROM和状态请求拒绝、同批次PNG；原主控离开保存结束后，原客席重新P1恢复同哈希状态，旧主控P2仅接收流。
- MAME离线Pong按两球拍固定列验证输入，不以自动移动的球或整帧CRC代替。07真实MC的SharedPlay也已实测通过。

07精确JAR SHA256为 `5d311c2db4c931ba4215617969246f7e150cc21f0d4e3e9c2bf8461243ac0ad8`。09真实run03已通过退出队列、NES回归和昼夜显示；其旧GB存档首次冷恢复失败已定位为SameBoy未执行前拒绝回滚点序列化，10按首次拒绝时最小初始化修复，保留MXS1和MXCWv2格式。10真实run04已恢复同一GB卡带的252698字节旧状态，客户端恢复哈希与服务器原状态一致；随后正常保存、离开及取出，GBA外壳和原标签保持。

SharedPlay视频最多20fps、512×512，复杂帧可能继续降采样；音频为48kHz PCM16立体声。非主控占席者仍可输入，但不运行第二份核心。网页纯旁观只接像素，权限与控制席分开。

## 模型与显示审核

已有21平台、22原创机体，包括单双人机台；机身型号独立于卡带，插拔保持外壳，服务端检查兼容媒体。run01世界模型原图和总览已查看；run02补齐9掌机及VB的主副手20图，并有动态GB、内置屏/真实浏览器与N64/MAME原图；run04再补当前候选的GB活动掌机主副手昼夜四图。原图和结果在[图库](../build/model-review/index.html)及[逐轮结论](MODEL-REVIEW.md)。原手持出画及夜间屏幕偏暗的失败留在原轮记录，不套用后续通过结论。

08候选SHA256为 `f72226e33a5cee5ff734e4e75d083d247cbd1040acdf33ff1fa2ad254f288245`，相对07仅 `DeviceRenderer.class` 改为真正不受光照影响的text shader，其他JAR条目（含worker、依赖和资源）逐项相同；09已在真实MC验证昼夜显示。10保留相同渲染类，并完成掌机昼夜实图，不能以字节一致替代显示验收。

## 仍待独立验收

实体手柄及热插拔、各平台代表性合法游戏、Linux、光影、完整整合包共存及音频设备听感仍须分别验收。掌机联机线尚未实现。模型原图证明外观，不能代替上述玩法或设备验收。

第一方公开发行许可未指定；第三方滚动核心的精确对应源码和许可交付未闭合，manifest仍为 `releaseReady=false`。本候选不作发行就绪声明，不修改Minecraft干净源、Portal或生产。
