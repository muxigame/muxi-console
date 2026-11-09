# Wii、NDS、3DS 与真实多机连接的实施方案

实施更新（2026-11-09）：下面的“尚未下载/编译”描述是研究开始时的快照。三个核心现已进入开发候选，原创诊断及原生状态/文件冷恢复已有独立进程证据；GB/GBC串口、GBA有线多机也已有实际交换与存档互导。MC整合、来源最终封包、NDS无线及3DS房间仍在做，完整当前范围见[升级工作单](PLAYSTATION-UPGRADE.md)。研究记录中的建议与旧状态不代替当前pin或发布验收。

3DS补充（2026-11-09，尚未完成MC复验）：候选10的真实MC长目录在Azahar初始化中出现原生访问错误；仅改变存档目录长度的离线对照也能重现。当前worker在Windows只给N3DS使用经过`Files.isSameFile`校验的同目录8.3名称，Java仍读写原机器UUID目录，不搬移或复制存档。长目录启动、原生文件保存及同目录快照冷恢复已有独立进程证据；禁用8.3名称且路径过长的卷会提前报错，这是明确限制，不能宣称所有长路径均已支持。

另已实测发现官方Azahar快照中的部分文件系统挂载点会保留旧绝对路径，跨目录直接恢复可能写回旧目录。当前仅N3DS的新`MXS2`外包记录目录及原生路径拼写的SHA256身份；不同目录、变化的短名称或旧未绑定快照会在原生反序列化前被拒绝，MC明确改用当前机器的游戏内存档启动。原快照不会被当作成功恢复，原生文件进度保留；同目录继续支持快照恢复。其余平台仍用原`MXS1`。这项保护尚不是核心文件路径重定位，真正跨目录即时续玩须等待源码修复和专项验证。相关原失败及后续独立进程结果分别保留在`azahar-long-profile-04`、`azahar-bound-profile-06`；后者46项检查、三worker正常退出，不代替MC验收。

研究日期：2026-11-09（Asia/Shanghai）。官方元数据观察时间：2026-11-08T17:13:29Z 附近。项目基线为 `dev@185f4f4c8966f1288b9c286efa269abde7bb256e`；研究开始时工作区干净，随后其他所有者正在修改上传、绑定和原生刻录菜单，本文件不评审或覆盖这些并行修改。

这是已核官方源码与发行的实施设计，尚未下载、编译或执行这三个新平台。下述发行 SHA 是 GitHub 官方 API 的 `digest` 字段，未冒称本机下载后的实测哈希。没有读取旧 Cartridge / muxi-arcade 实现，没有操作 Minecraft 或生产环境。

## 1. 选择与目标

推荐用 **melonDS DS 接 NDS、Azahar 接 3DS、Dolphin libretro 接 Wii**。保留现有独立 worker 边界，Minecraft 进程不直接装载新核心。真实联机同时推进独立端点、视频和持久化协议；不要只增加平台枚举和 DLL。

| 需求 | 实施路线 | 已找到的接口 | 首个可交付验收 |
| --- | --- | --- | --- |
| NDS 单机与触摸双屏 | melonDS DS libretro | 两屏布局、pointer、microphone、软件/OpenGL 视频 | 原创 `.nds` 同时显示上下不同图案；下屏四角触摸返回正确坐标 |
| 3DS 单机与触摸双屏 | Azahar 官方 libretro | OpenGL 4.3 / Vulkan / 软件视频、pointer、sensor、microphone | 原创 `.3dsx` 的 400×240 上屏和 320×240 下屏各自显示；触摸及运动输入可读 |
| Wii 单机与四控制席 | Dolphin libretro | Wiimote、Nunchuk、Classic、MotionPlus，pointer/mouse/sensor | 原创 `.dol` 显示四端口按键、IR、加速度、角速度；退出 worker 正常回收 |
| GB/GBC 真实线缆双机 | 当前 SameBoy 的双 ROM subsystem | `gb_link_2p`、串口 bit callbacks、周期交错执行 | 两张独立卡各读自己的画面，交换随机挑战字节而非共享按键 |
| GBA 真实线缆 2–4 机 | 当前 mGBA 源码的 native 多机 adapter | `GBASIOLockstepCoordinator`、独立 `mCore` 和 SIO driver | 2/4 台原创 ROM 收到各自端点编号与对方挑战字；断线可恢复单机 |
| GBA 无线适配器 | gpSP 的 netpacket，作为明确标注的 RFU 模式 | `RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE` | RFU 专用诊断双向交换；不能用该结果代替有线串口验收 |
| NDS 本地无线 | melonDS DS 的 netpacket | timestamp/AID/帧类型 + `Platform::MP_*` | 两台 DS 独立 ROM 通过仿真无线收发，不依赖同画面控制席 |
| 3DS 本地无线经互联网 | Azahar 自建 frontend/native adapter | `Network::Room`、`RoomMember`、UDS `WifiPacket` | 私有房间中两台独立 3DS 用原创 UDS 程序建网、加入、交换数据 |
| Wii / NDS / 3DS 原游戏互联网协议 | 独立网络设置和用户自有系统资料 | 各自网络设备/服务实现，按服务验证 | 自建测试服务的 TCP/UDP 回显；第三方实际服务另验，不混称房间联机 |

## 2. 可取得的官方发行与完整源码

### NDS

候选为 [melonDS DS v1.4.0](https://github.com/JesseTG/melonds-ds/releases/tag/v1.4.0)，tag 已解析到 `f394adbacb5722ee97c1b37c8064da9a25818310`。Windows x64 Release 资产是 `melondsds_libretro-win32-x86_64-Release.zip`，2,732,973 B，官方 digest：

```text
f03af0318fee91a83880f438b369a1368fea6b5c1524a09dde849ca8fa68603f
```

核心 GPL-3.0-or-later；这不是整个模组改成 GPL 的声明。其 [README](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/README.md) 明确区分互联网 Wi-Fi 与本地多人，支持原生 libretro microphone，也有开源替代 BIOS/firmware；需要原机资料的功能由用户提供。DSi 的 BIOS/NAND 是另一套需求，不应把“增加 NDS”自动宣称成 DSi 已验收。

正式分发的源闭合不能只拿顶层 tag。该版本的 [FetchDependencies.cmake](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/cmake/FetchDependencies.cmake) 把 melonDS 依赖指定为 `JesseTG/melonDS` 的浮动 `jtg/fix-uninitialized-opengl`，本次读到其 commit 为 `16f127dbb587a73371827ce79689c5d237c4b59b`；另外还取 libretro-common、embed-binaries、glm、libslirp、pntr、fmt、yamc、span-lite、date、zlib 等。**当前分支值不证明旧 release 在构建时也取了这一值。**

可实施办法：优先从上述 core commit 自编，给全部 FetchContent 指定完整 commit/本地 source tree，并归档这些源码、原许可证、生成步骤及参数。或者取得正式 release 的 CI 实际 dependency lock，再分发相应官方 DLL。开发 probe 可以先用正式发行，但没有闭合来源前不能套用上一版 `releaseReady=true`。

[官方构建说明](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/CONTRIBUTING.md) 要求 CMake≥3.19、Git、C++17；明确不支持 MSVC，Windows 推荐 MSYS2 MinGW。已有 LLVM-MinGW 可做候选编译器，但尚未验证兼容该依赖集合；不以“机器已有 Clang”代替成功编译证据。Release CI 关闭 LTO 的设置也需保留。独立工具链仍放根 `test-environment/toolchains/`，构建缓存放 `test-environment/cache/muxi-console/`，单任务 `-j1`。

独立版 [melonDS 1.1](https://github.com/melonDS-emu/melonDS/releases/tag/1.1) 也有官方 Windows x86_64 ZIP（19,484,283 B，digest `9f3f8a244103be20b5b657af5b0ed1b2a66bb20a7181476a6d294c9a53d4f8c8`）。它可用于交叉诊断上游行为；不要自动认为独立版的 Qt UI / local multiplayer 功能已全部透过任意旧 libretro DLL 暴露。

### 3DS

采用 [Azahar 2126.2 正式发行](https://github.com/azahar-emu/azahar/releases/tag/2126.2)，tag commit 为 `219d743a1e698101fe1ab8bef43e6563126c3c27`，直接有：

| 官方资产 | 字节数 | 官方 SHA-256 digest |
| --- | ---: | --- |
| `azahar-libretro-windows-x86_64-2126.2.zip` | 12,097,452 | `d863711cff775ed697ba0cc5a963f2145b9672c4ad9a891367821d7f3cc1f888` |
| `azahar-unified-source-2126.2.tar.xz` | 43,224,348 | `85c8b56fac703df04720125044b8ff887997911858a6cde545dfb1107ba4cd26` |
| `azahar-windows-msvc-2126.2.zip`（独立版诊断） | 43,941,048 | `4412eac8d13123c8840f11890fc26e5b0e4c76d16aa28a608941377b948a9db0` |

顶层源码及接口头标注 GPLv2-or-later，仓库还有其他来源及许可证，不用 GitHub 的单一 license 标签代替文件级审计。[license.txt](https://github.com/azahar-emu/azahar/blob/2126.2/license.txt)、`misc/licenses/`、所有 submodule 和实际链接依赖一起交付；可以沿现有 source-bundle 机制分发。官方 unified source 是优于不含 submodule 的 GitHub 自动 source ZIP 的起点，实际打包前仍须检查它涵盖所选二进制全部依赖与许可证。

[构建入口](https://github.com/azahar-emu/azahar/wiki/Building-From-Source) 与 [2126.2 CMake](https://github.com/azahar-emu/azahar/blob/2126.2/CMakeLists.txt) 有 CMake≥3.25、C++20、recursive submodule；官方 Windows 流程有 MSVC 和 MSYS2 clang64。libretro 可关闭 Qt/SDL 等；默认 GPU 路线先选现有 worker 可接的 OpenGL 4.3，Vulkan 需新增 ABI，不能假装当前 `HardwareVideo` 已接受它。软件渲染器适合诊断基线，性能另测。

**真实无线联机不能仅开一个现成选项。** 该版本 CMake 明确把 `ENABLE_LIBRETRO` 与 `ENABLE_ROOM` / `ENABLE_ROOM_STANDALONE` 列为不兼容并强制关闭。正式 libretro DLL 未因独立版存在房间 UI 就获得 room API。可落地方案是制作单独 native frontend/worker：链接同一 commit 的 `citra_core`、`video_core`、`network`，实现自己的 EmuWindow、输入及音频出口，调用房间接口；也可在明确标记的第三方 patch 中扩充 libretro 桥，须把 room 初始化/关闭、UDS 接线和所有修改源码一并分发，不能仅删除 CMake 检查。

### Wii

libretro 候选 commit 为 `libretro/dolphin@32c61f9d0e933331bb7087ba57e96883241b4960`（本次观察的 master），并非一份已经验证的二进制 pin。[核心文档](https://docs.libretro.com/library/dolphin/) 给出了 OpenGL / Vulkan / D3D，以及所需 `Data/Sys`。Windows x64 buildbot 可取得 `dolphin_libretro.dll.zip`，但 rolling ZIP 与任意当前 commit 不具有天然对应关系；推荐从明确 commit 自编后锁 DLL、ZIP 和 source bundle，重用上一版两核心的可追溯交付做法。

该 commit 的 [CMake](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/CMakeLists.txt) 已用 C++23、CMake≥3.25；[Windows MSVC CI](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/.github/workflows/build-windows-msvc-libretro.yml) 是 Visual Studio 18 2026，另有官方 [MinGW 交叉构建流程](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/.github/workflows/build-windows-ming-libretro.yml)。此前核实的本机 VS2019 不足以代表这一基线可构建；可选择当前隔离 LLVM-MinGW 的能力预检，或固定官方 MinGW/现代 MSVC CI 工具链。不要为通过编译默默退回未知老 DLL。

[COPYING](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/COPYING) 说明大部分原始代码 GPLv2+，整体代码集合兼容 GPLv3，并要求按 SPDX 与 `LICENSES/` 保留许可。`Data/Sys` 的开源字体、数据库和 per-game 配置也要按其原许可附带，不能误认为它是可由任意论坛系统包替代的 BIOS。原 Wii NAND、密钥或商业软件不随包提供。

独立版 [Dolphin 官方下载](https://dolphin-emu.org/download/) 当前有 Windows x64 `2609b`，官方 tag 解析为 `3a5b3743b18da3e8f04cbc44f20dc50fbd3a9ef3`。独立版可交叉验证，不能把它的 EXE 当成带稳定嵌入 API 的 DLL。libretro 1×分辨率的 OpenGL 是当前 worker 最短接入路径；官方源码的内部 C++ API 只随选定 commit 固定，不对外宣称稳定 ABI。

## 3. 平台、设备与输入 contract

这些是拟实现约定，尚未注册平台。`nds` / `n3ds` / `wii` 是建议稳定 id，所有者实现时一次确认；旧 id、序列化和物品外观编号保留。

1. **编号分开。** 当前 `Platform.customModelData()=ordinal()+1`，已有 21 平台，而硬件模型 22 已是双人街机。新增平台不能直接占 CMD22。新设备使用显式稳定模型号（至少 23 起），不要挪动旧枚举；卡片模型编号也独立核碰撞。
2. **控制席不是虚拟机器。** NDS/3DS 每台仍是一个本地控制席；Wii 同台最多四个 Wii Remote 席。GB/GBA/NDS/3DS 联机使用多个 machineUUID，不把掌机 `controllerPorts` 改成 2/4 来替代多机。
3. **双屏作为语义面。** 视频契约新增 `Surface(id, sourceRect, nativeSize, touchable)`，仍可运输单张 RGBA atlas。NDS top/bottom 各 256×192；3DS top 400×240、bottom 320×240。每帧带实际布局/矩形及序号，换屏、比例、黑边后仍能反投影。左右眼 3D 是可选额外 surface，不能把两只眼当作上下双屏。Wii 为单面 4:3/16:9 实际显示比例。
4. **同一布局服务全部视图。** 手持机两个物理 LCD、MODEL 全屏、SCREEN、底座、世界大屏共用布局。世界模型不能把整张双屏 atlas 在两个 LCD 各重复一遍；GUI 也不能只裁掉下屏。大屏默认上下等比组合，允许放大一个屏但始终能切回另一屏。
5. **触摸不是方向键。** 扩展 worker 输入为 `Pointer(surfaceId,x,y,pressed,valid)`，平台 adapter 根据 sourceRect 转到核心实际 pointer 坐标，满足 libretro -32767..32767 约定。上屏与外部黑边点击不得触发下屏；拖拽、松键、失焦/退屏后的释放必须可测。保留既有右键切模式与 Esc 退出，游戏触摸用主点击/明确绑定，避免原生交互冲突。
6. **运动有单位与有效位。** 输入加 acceleration(g)、gyro(rad/s)、timestamp、valid；Azahar 自身把 gyro 转为 deg/s，前端不能重复转换。Dolphin 也读取 libretro sensor，支持每端口独立数据。普通手柄用可绑定虚拟倾斜、摇晃、回正与 IR 指针；真实传感器/DSU 是另一个可选数据源，不能把摇杆值谎称硬件陀螺仪。
7. **Wii 设备类型可选。** 每端口保存 upright / sideways / Nunchuk / Classic / MotionPlus 类型与绑定；按 [Dolphin 输入实现](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/Source/Core/DolphinLibretro/Input.cpp) 使用其 controller subclass。四玩家不共用一份 pointer 或 sensor。真实 Wiimote/Bluetooth passthrough 依赖宿主硬件，是额外验证，不作为模拟 Wiimote 的前置条件。
8. **麦克风有真实来源。** 接 libretro microphone API，可选择本地采样或明确的模拟吹气，默认无输入返回静音。样本率、样本数和断开状态都进 worker；有输入选项不等于当前普通按键协议已能传音频。

[Azahar 输入工厂](https://github.com/azahar-emu/azahar/blob/2126.2/src/citra_libretro/input/input_factory.cpp) 和 [libretro window](https://github.com/azahar-emu/azahar/blob/2126.2/src/citra_libretro/emu_window/libretro_window.cpp) 已给出 sensor 和双屏帧输出实现；[melonDS DS 触摸定义](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/src/libretro/config/definitions/screen.hpp) 有 pointer / joystick / auto。上述 contract 用来把现有功能接入我们自己的呈现和协议，不复制第三方界面或模型。

## 4. “联机”必须分成三类

| 对用户的名称 | 核心端真实行为 | 机器、内容与画面 |
| --- | --- | --- |
| 同机多人 / 远程控制席 | 一台虚拟主机的多个手柄；也可能做输入同步 netplay | 一台 machine、一份 ROM 执行状态，玩家通常共享画面 |
| 连线 / 本地无线 | 两台以上虚拟机器交换串口或无线设备数据 | 每台有自己的 machine、内容授权、存档与画面，可是兼容的不同 ROM |
| 游戏互联网服务 | 游戏通过仿真网络设备访问实际服务协议 | 每台自己的网络身份和账号；服务端不是我们的共享画面房间 |

Dolphin [Netplay Guide](https://dolphin-emu.org/docs/guides/netplay-guide/) 讲的是原本本地多人游戏的同步运行，要求相同版本和内容；它本身不证明 Wii 原游戏的互联网服务或 Wii↔DS 通信可用。现有 N64/ARCADE sharedRuntime 也只属于第一类。

真正连线推荐**同宿主多实例 + 各端点独立远程输入/视频**作为互联网首条实现：GB/GBA 的串口周期留在同宿主，NDS 的无线包也不必每次往返远程 MC 网络。宿主可为获授权玩家的 worker 组，不把 C/C++ 核心装进 Minecraft 专服。若以后部署专用模拟宿主，它是独立执行服务，需另行设计授权/资源限制；本方案不自动部署它。

这里多机仍各执行自己的 CPU、RAM、ROM 和总线，只是运输画面给相应玩家，所以是可实现的真实连接，不是换名字的一台 ROM 共享控制。宿主必须取得每个端点内容的真实权限；拿一张卡的授权不能自动扩散成任意玩家拥有多张 ROM。

## 5. 各平台连接实现

### GB / GBC

当前已分发 SameBoy commit `90e2899574b7b63c59dce8a7276783426d52a068` 的 [libretro.c](https://github.com/libretro/SameBoy/blob/90e2899574b7b63c59dce8a7276783426d52a068/libretro/libretro.c) 已包含两 ROM subsystem、两个 `GB_gameboy_t`、串口位连接和以 `GB_run` 交错推进的调度。先扩 worker `SET_SUBSYSTEM_INFO` / `retro_load_game_special`，加载各卡真实 ROM，拆分 top-down 或 left-right 两画面；不是为凑功能更换未知核心。

需要额外做两端 SRAM/RTC 路由及 save-state 规则。当前 worker 固定读 memory id0/1，不会自动保存 SameBoy subsystem 的每端私有 memory id。可用原生 [SameBoy C API](https://github.com/LIJI32/SameBoy/blob/v1.0.5/Core/gb.h) 提供独立电池存档/状态出口；若继续用组合 libretro 状态，明确存为整个 link session 检查点，不能把双机 blob 当某一台单机状态写回。该 API 有串口 bit callbacks 与 `GB_serial_get/set_data_bit`，也可用于更细的分机 adapter。

### GBA

当前 pin `libretro/mgba@7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6` 的 [SIO lockstep 头](https://github.com/libretro/mgba/blob/7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6/include/mgba/internal/gba/sio/lockstep.h) 已提供 `GBASIOLockstepCoordinatorInit/Attach/Detach` 和 `GBASIOLockstepDriverCreate`；[SIO 定义](https://github.com/libretro/mgba/blob/7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6/include/mgba/internal/gba/sio.h) 的 `MAX_GBAS=4`。但该 pin 的 libretro `retro_load_game_special` 直接返回 false，所以并排启动现有 DLL、每帧喂按键没有完成连线。

实施一个小型 C/C++ adapter，直接链接同 commit 的 mGBA core：2–4 个 `mCore`、各自 ROM/视频/SRAM，接官方 coordinator 与线程/等待回调；对 Java 暴露我们自己的版本化 C ABI。先封装和验证有线 8/32bit 与 multiplayer 模式，再加拔插/异常退出；实际时序交给官方 SIO 调度，不在 Minecraft tick 中模拟每一串口位。mGBA MPL-2.0 修改文件保留 MPL，原许可和完整对应源码随 adapter 分发。

无线 RFU 另可选 [gpSP](https://github.com/libretro/gpsp/blob/5819380c2ffb0900219d700a382ee68c464ebb99/libretro/libretro.c)（观察 commit `5819380c2ffb0900219d700a382ee68c464ebb99`），其 netpacket 有真实收发和连接人数限制；源码 README 将无线与未来扩展的 link 模式分开。选它会增加一个 GPL 核心和 source delivery，不作为“mGBA 已支持所有联网”的证据，也不因少数游戏专用串口模式就宣称通用有线支持。

### NDS

[melonDS DS MP 桥](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/src/libretro/libretro.cpp) 实际连接 `Platform::MP_SendPacket/RecvPacket/SendCmd/SendReply`；[MP 状态](https://github.com/JesseTG/melonds-ds/blob/v1.4.0/src/libretro/net/mp.cpp) 携带 timestamp、AID、cmd/reply 类型，通过 libretro netpacket 收发。这是可直接接的仿真无线接口。

worker 必须实现环境命令78的 `retro_netpacket_callback`、稳定 client id、broadcast、可靠/不可靠与 flush hint 语义。回调在 core 线程泵入收包队列，MC 网络线程不能直接重入 `retro_run` 或 JNA。尤其 `poll_receive` 可在一个 `retro_run` 内阻塞等包，只在“本帧结束后”处理网络会自锁。[libretro ABI](https://github.com/libretro/libretro-common/blob/master/include/libretro.h) 明确规定这类多人是不同主机，并要求连接时停用时间操作。

上游当前 MP 阻塞窗口为 25ms，且主动提示 VPN/tunnel 不适用；这不是任意 WAN 延迟可玩的保证。先把两个 worker 放同宿主低延迟网络，玩家跨互联网只传自己的输入和画面；再对端到端 netpacket 路线实测往返延迟/抖动界限。保留不同 endpoint 的 ROM/hash，不强迫所有连线都是同 SHA；但仅在已验配对/协议兼容时开放跨 ROM 连接。

DS 的互联网 Wi-Fi 经核心网络后端（例如 libslirp）是不同路径，要独立做网络开关、DNS/服务设置和实际回显。Slot-2 加载 GBA ROM 只表示 DS 卡槽互操作，不意味着 melonDS 能执行 GBA 或自动连入 GBA serial session。

### 3DS

同 commit 的 [RoomMember](https://github.com/azahar-emu/azahar/blob/2126.2/src/network/room_member.h) 有 `Join`、`SendWifiPacket`、`BindOnWifiPacketReceived`；`WifiPacket` 含帧类型、raw data、源/目标 MAC 与 channel。[Room](https://github.com/azahar-emu/azahar/blob/2126.2/src/network/room.h) 提供 Create，默认端口24872。这套协议能承载独立机器的本地无线，不能只把 `RoomMember::Join` 当作完成了触摸/GPU/UDS 前端接线。

用上文自建 worker/frontend 接 HLE UDS 与独立 private room。第一步同宿主两个 worker、loopback 私有房间，禁用公开列表和不需要的 telemetry；第二步可在固定协议版本及权限管理下，把这一真实无线房间放到互联网。不得把 console ID、MAC、NAND 用户数据在所有机器间复制成相同值；原生 RoomMember 也明确处理重复 console ID/MAC。

真实 3DS 互联网服务另有用户身份和系统模块。官方 [Pretendo 的 Azahar 安装说明](https://pretendo.network/docs/install/azahar) 要求用户自己从 3DS/2DS 取得资料及做 LLE/Nimbus 配置，且只为官方 Azahar 提供支持；我们的自建 adapter 不可承诺得到其官方技术支持。可提供显式的用户自有系统目录导入及网络配置，再对每个服务做兼容验收，绝不随模组分发用户证书/OTP/账号或拿一个测试身份给全服共用。

### Wii 与跨平台

同机四控制席可以沿用 shared-runtime，但新 Wiimote 输入需要完整承载。原游戏网络通过 Dolphin 的仿真 IOS socket/SSL 等网络实现访问服务；[官方 Wii Network Guide](https://dolphin-emu.org/docs/guides/wii-network-guide/) 说明部分所需资料不能由 Dolphin 分发、原 Nintendo WFC 已终止，自定义服务必须按各服务文档设置。可做用户自有 NAND/profile 导入和自建 TCP/UDP 测试服务；某游戏的第三方社区服务不等于所有 Wii 游戏通用联网。

Wii↔DS 不是现成 room/串口适配：[官方说明](https://dolphin-emu.org/blog/2021/02/11/dolphin-progress-report-december-2020-and-january-2021/) 区分 IOS 模块改进与真正 DS 连接。当前选定源码的 [WD Command](https://github.com/libretro/dolphin/blob/32c61f9d0e933331bb7087ba57e96883241b4960/Source/Core/Core/IOS/Network/WD/Command.cpp) 接收处理仍有 frame/notification 数据 TODO。可研发 WD scan/beacon/发送/接收与 melonDS MP 帧的桥，但需补真实协议和原创双端诊断；不是改一条 UDP 目的地即可完成。本阶段保留明确的跨平台 backend 扩展点，不能将 Wii↔DS 或 GBA↔DS、DS↔3DS 一概标为通用互联。

## 6. 持久化、权限、尺寸与退出

建议 session 结构：

```text
LinkSession(version, sessionUUID, transportKind, coreBuildHash, protocolVersion,
            endpoints[{machineUUID, cardUUID, romSHA, platform, endpointId}], epoch)
EndpointInput(sessionUUID, epoch, endpointId, sequence, pad, pointer, sensors)
EndpointVideo(sessionUUID, epoch, endpointId, sequence, surfaces, rgba, pcm)
```

这是建议 wire contract，不是现有类/API。MC server 核实卡/机器操作权限和席位，再发 session token；宿主也据 token 限定 endpoint。参与者退出、卡取出、机器卸载、断网、宿主崩溃都撤销席位并推进 epoch，旧包不能操作新会话。连接请求不是允许任意访问局域网地址的通用代理。

每台继续以 machineUUID + ROM SHA 保存其电池/文件系统内容，不能改成卡 UUID 或玩家名。连接中禁单端 rewind/快进/加载快照；需要加载时全组停在一致 barrier，验证 endpoint 集合和 coreBuildHash，再做 group checkpoint 或先拆连线回单机。SameBoy 组合状态和 mGBA 各端状态都不能被无条件当作旧单机状态兼容。Wii/3DS 的 NAND、文件系统和系统 profile 也需各机器独立目录、原子落盘与配额，不只保存 `RETRO_MEMORY_SAVE_RAM`。

现基线需修改的具体边界：

| 当前实现 | 接新平台的实际工作 |
| --- | --- |
| `RomInspector.MAX_ROM_BYTES=512MiB`；默认 upload reservation 总共1GiB | 保持流式 hash/分块上传，平台独立 admission；Wii/3DS 容纳大镜像的 long 长度、配额预留、恢复和真实进度统一设计，不能只改后缀。NDS 可先维持512MiB上界 |
| worker 内存内容加载上限128MiB | 新核心 `need_fullpath` 使用已核 SHA 的缓存文件；不把 Wii/3DS 全镜像放进 Java/JNA byte[] |
| `PortInput` 只有16按钮及双摇杆 | 添加 pointer/sensor/mic、设备 subclass、每 endpoint 输入和版本化 wire；保留旧调用兼容 |
| 当前 environment 无 netpacket/sensor/microphone，LaunchConfig 只一个 content | 补官方 ABI 协商、回调生命周期、subsystem 多内容与多端点持久化 |
| 当前 `HardwareVideo` 只接受桌面OpenGL，最大2048² | 首版选1×OpenGL、从实际AV negotiation取尺寸；3DS需要4.3。Vulkan/D3D另写适配，不能 fallback 假成功 |
| core/SRAM/RTC 每项上限24MiB，wire总64MiB | 对新核实测 serialize 状态大小、启动峰值与重载耗时；分平台设有界限制/流式状态，保留旧格式读取，不统一无界放大 |
| 单张 `AvFrame`、单机画面/输入流 | 增 surface 与 endpoint 路由；不同玩家屏幕必须能看见不同随机挑战及不同游戏进度 |

## 7. 原创诊断和验收顺序

不需要商业游戏就能完成核心接入与真正多机链路验收；商业兼容性只按用户自己的文件做另行记录。所有新诊断使用自绘像素/文本和自写逻辑，工具链及库的许可证单独保留。

1. **ABI / 退出。** 固定 DLL/源码/依赖和工具链哈希，查 PE x64、所需 retro exports / 自有 adapter ABI。独立 worker 先加载原创内容、出帧、输入、音频、停止，检查 native 退出与临时目录回收。真实 frame 与 measured state-size 结果决定下一阶段，不用 API1 代替可玩证明。
2. **双屏与输入。** devkitARM + libnds 编原创 `.nds`；devkitARM + libctru 编原创 `.3dsx`。不同屏幕显示独立计数器，四角/中心触摸、拖动、松手、缩放、换屏及退屏释放全部验证。3DS 运动读数用已知 g/rad/s 测试值核单位；麦克风回显电平。参考官方 [3DS touch example](https://github.com/devkitPro/3ds-examples/tree/be2001fee08cf8ec7d3366e095e83f6f75da8942/input/touch-screen)，不复制它的无明确许可资源。
3. **GB / GBA 连线。** GB 可沿现有自制 ROM 生成器写 SB/SC 交换，或用 [GBDK 串口 API 用例](https://github.com/gbdk-2020/gbdk-2020/blob/278b46e9a3a3cb74340c91cd1a85a1545a25d863/gbdk-lib/examples/cross-platform/comm/src/comm.c) 研究接口。GBA 用官方 [libgba SIO 寄存器定义](https://github.com/devkitPro/libgba/blob/f0e44ce9ed57e1d21f148443223408fddeacfb9d/include/gba_sio.h) 编原创 2/4端程序，验证非相同输入、双向随机 nonce、端点编号、SRAM归属、拆线和正常退出。
4. **NDS / 3DS 真无线。** NDS 用固定版本的 [dswifi raw frame API](https://github.com/devkitPro/dswifi/blob/v0.4.2/include/dswifi9.h) 或对应 ARM7 WLAN API 做原创发收程序，先证实 guest 总线到 transport 的双向链路；raw frame 通过不冒称所有 Nintendo 本地无线协议都通过。3DS 通过 [libctru UDS API/官方用例](https://github.com/devkitPro/3ds-examples/blob/be2001fee08cf8ec7d3366e095e83f6f75da8942/network/uds/source/uds.c) 自写建网/发现/加入/nonce交换，验证 RoomMember 真的收到另一台的帧。之后测低延迟和注入抖动的界限、超时清理、重连 epoch。
5. **Wii 控制与网络。** devkitPPC + libogc 自写 `.dol`，用 [WPAD API](https://github.com/devkitPro/libogc/blob/ef718e2d7df5e5b1750a9e1a67c17350bdd30d3e/gc/wiiuse/wpad.h) 显示四端按键、IR/运动与扩展类型，用自建服务测 socket 回显。不依赖原 Wii Menu、第三方 WAD 或系统证书让基本诊断成立。
6. **MC 真机呈现。** 最后由统一 test-environment 做手持/MODEL/SCREEN/底座/大屏双屏、独立两玩家和输入、慢下载、机器保存恢复。每张验收图匹配实际 endpoint/帧号/输入/nonce；不能用共享同一图像或源码函数存在替代原生运行结果。

这些步骤可以并行分工为 worker输入/多面视频、native多机adapter、媒体与资源、网络会话/存档四块。NDS/3DS/Wii 单机接入和 GB/GBA 真连线均有明确接口；3DS room桥、Wii网络profile、大文件与状态运输属于需要开发的工作项，保留在目标内，未验证部分不提前写成发布能力。
