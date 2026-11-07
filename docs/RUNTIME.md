# 独立模拟器工作进程

本实现从公开 libretro ABI 自行编写；没有读取、导入、改写 Cartridge 或旧 muxi-arcade 的代码、协议、host、资源或测试。唯一第三方 Java 依赖是官方 JNA 5.14.0。MC 进程使用纯 Java API，只有新建的 Java21 工作进程加载 JNA 与核心 DLL。

公开依据：

- [libretro 开发文档](https://docs.libretro.com/development/cores/developing-cores/)
- [libretro-common 官方公共 ABI](https://raw.githubusercontent.com/libretro/libretro-common/master/include/libretro.h)，MIT；本仓没有复制该头文件正文。
- [JNA 官方 API](https://java-native-access.github.io/jna/5.14.0/javadoc/overview-summary.html)。JNA 自身的许可与模拟器核心许可须另行登记。

## 游戏端接口

包 `net.muxigame.console.runtime`：

```java
var config = new LaunchConfig(java21, List.of(productJar, jnaJar), coreDll,
    romPath, systemDirectory, saveDirectory, Map.of());
try (var runtime = ProcessConsoleRuntime.open(config)) {
    var frame = runtime.step(new InputFrame(List.of(new PortInput(1 << 8))));
    byte[] checkpoint = runtime.saveState();
    runtime.loadState(checkpoint);
}
```

`content` 为 null 只适用于核心明确声明的无内容模式。所有本地路径由调用方从可信缓存解析；不要把网络包中的任意路径传给启动接口。工作进程使用显式 Java 和 classpath、256MiB 堆、单CPU；构建使用 Java21、512MiB 堆、单CPU。

- `CoreInfo` 返回核心名、版本、初始几何、FPS、采样率。
- `InputFrame` 最多四个端口，缺席端口自动为零；`PortInput` 按公开 RetroPad 16位按钮顺序，含左右摇杆四个有符号16位轴。
- `AvFrame` 给出逐帧序号、RGBA8、PCM16立体声、采样率、逆时针90度旋转次数与核心退出请求。重复视频帧保留上一帧完整像素。
- `saveState` 是不透明的 **MXS1** 状态封装，含核心序列化、SRAM、RTC；先验证结构与组件上限，再恢复 core 的保存类型、严格验证实际内存长度并写回 SRAM/RTC，失败回滚原 core/内存。mGBA 初次开游戏的未定保存区可为128KiB，恢复状态后收敛32KiB，不能用未定大小提前否决合法存档。调用方同时绑定平台、内容哈希、核心版本并校验组包 SHA；封装不是跨核心/跨ROM格式。
- `readSram`/`writeSram` 允许单独持久化电池存储，写入长度必须与当前核心完全一致。核心不提供状态时明确失败。
- `workerPid`、`isAlive`、`exitCode`、`wasForcedShutdown` 可用于结束验收。正常成功要求 exit0 且 forced=false。

这些方法会阻塞等候工作进程，MC调用方须放在自己的有界后台执行器，不能放在渲染或服务器主线程。宿主按FPS调度 `step`；暂停时停止调用。一个工作进程只运行一个核心和一份游戏。重复会话须有独立save/system可写目录与工作进程。

## 私有传输与边界

本仓自定 **MXCW v1**：父进程绑定127.0.0.1临时端口，子进程用256位随机token握手；每帧是网络字节序的magic(4)、version(2)、opcode(2)、request-id(8)、length(4)、payload。响应opcode置0x8000，首字节0表示成功、1表示UTF8错误。命令为 OPEN、STEP、RESET、SAVE、LOAD、SRAM_READ、SRAM_WRITE、QUIT。未知命令、版本、请求编号、额外字节及越界长度会拒绝。

不使用stdout传输，因为核心可能自行打印。子进程stdout/stderr均丢弃，避免日志反压；用户错误通过私有响应返回。没有外网监听，没有复用其他产品协议。启动握手及每次应答最多等15秒。关闭先QUIT，完成unload/deinit再回复，等5秒正常退出；异常时仅终止自己的子进程，forced不能记成功。该进程隔离防止原生崩溃直接带走MC；它不是针对恶意DLL的操作系统权限沙箱，核心须来自固定可信来源。

支持三个公开软件格式：0RGB1555、XRGB8888、RGB565，并尊重行间距。最多2048×2048像素、单帧最多192000立体声采样、单块状态/电池内存最多24MiB、传输包最多64MiB。C `bool`按1字节绑定，`size_t`按指针位宽绑定；不能用Windows64的32位C long代替size_t。

环境接口支持软件视频、目录、旋转、几何/音频变化、默认与显式核心选项、四个RetroPad及模拟轴、无内容模式、输入bitmask。硬件渲染、异步音频、键盘/鼠标/触屏、VFS、磁盘更换等扩展尚未实现；未知环境调用返回false。需要OpenGL/Vulkan的核心会明确失败，不能据平台目录宣称全部兼容。

## 本轮离线原生验证

```powershell
python -B tools/runtime_smoke.py --core ../test-environment/cache/muxi-console/libretro/2048_libretro.dll
python -B tools/runtime_smoke.py --core ../test-environment/cache/muxi-console/libretro/fceumm_libretro.dll --diagnostic
python -B tools/fetch_cores.py --offline
python -B tools/runtime_smoke.py --probe-manifest
```

工具只从真实仓编译runtime及其独立测试，不启动Minecraft、不建立整包运行环境。核心二进制从 [libretro官方构建站](https://buildbot.libretro.com/nightly/windows/x86_64/latest/) 新取得；独立来源pin由集成owner维护，不从旧产品复制。结果在 `build/runtime-smoke/`。`--diagnostic` 全新生成24,592字节mapper0电池卡带：P1 A使左半蓝变红、P2 A使右半白变绿，分别写SRAM[0]=1、SRAM[1]=2，适合后续真实客户端上传与双端控制验收。

2048真实工作进程10项通过；FCEUmm诊断23项通过，包括独立两端输入、核心状态与SRAM恢复、错误长度拒绝、重置、正常退出和子进程释放。最初诊断ROM有调色板后nametable选择错误，已修ROM后重新实测，未放宽断言。另有全新 ARM7TDMI GBA 诊断3188项通过：240×160、A/Start、SRAM、同进程及跨进程恢复，错误组件拒绝与回滚。GBA 未含厂商logo/BIOS，使用 mGBA HLE；RTC区未暴露，不宣称硬件时钟通过。上述是独立工作进程测试；MC实际结果另见 STATUS，不代表真实键鼠/手柄、音频设备或全平台游戏兼容。未改干净源或发布。

工作进程的唯一 JNA 临时目录位于私有 core 父目录下，正常退出后按具体目录身份、文件边界清理；意外子目录会拒清并显式报错。Minecraft 提取路径是 `natives/muxi-console/`，统一测试入口结束即随 native payload 清理；玩家固件、ROM缓存和存档不放在临时native目录。

## 第三方核心登记

`third-party/cores.json` 固定14个新官方Windows x86_64二进制：13个软件模拟器覆盖18种平台，另一个2048仅供无内容诊断。N64因没有硬件渲染接口而明确不支持。每个条目记录官方archive/library大小及SHA256、官方license元数据URL及SHA、源码仓URL和来源证据。14个PE机器类型、双哈希和独立进程导出探测均已通过；后者只查询公共API版本和核心身份，不加载游戏。

首次登记或明确刷新使用 `python -B tools/fetch_cores.py --pin`；日常命令只接受已有pin，下载字节漂移会拒绝。`--offline`只读取本产品的新共享cache。新取的zip仅解出唯一预期DLL，拒绝其他路径与格式，不读取旧产品缓存。`--record-probes`可将当前哈希绑定的export报告写入manifest，并通过GitHub SHA-only接口解析核心自报短git；这个接口不返回源码或patch正文。

滚动构建包尚无完整可验证的对应源码/子模块/本地改动及可复现构建链。因此 `sourceRevision`仍为null、`sourceRevisionVerified`与`releaseReady`为false；`binaryReportedRevisionResolved`只表示核心自报的短hash解析到一个官方commit，不能代替发布前对应源码及许可交付。没有把当前source HEAD填成二进制revision。
