# 独立模拟器工作进程

实现依据公开 libretro ABI，自行编写 Java/JNA 适配、工作进程与传输。没有读取或迁入 Cartridge、旧 muxi-arcade 的源码、host、协议、资产或测试。Minecraft 进程不加载模拟器核心；每个实际执行者创建独立 Java21 worker。

当前构建目标为 Windows x86-64 / MC1.21.1 / NeoForge21.1.250。官方 JNA 5.14.0 用于核心 ABI；N64 的隐藏 OpenGL 上下文另使用官方 LWJGL 3.3.3 的 lwjgl、glfw、opengl 三模块及 Windows native JAR，共六个额外依赖。依赖逐字节核对与来源见 [worker-dependencies.json](../third-party/worker-dependencies.json)，核心见 [cores.json](../third-party/cores.json)。构建堆512MiB/单CPU，独立测试和 worker 堆256MiB/单CPU。

## Java接口与显示

```java
var config = new LaunchConfig(java21, workerClasspath, coreDll,
    romPath, systemDirectory, saveDirectory, Map.of());
try (var runtime = ProcessConsoleRuntime.open(config)) {
    AvFrame frame = runtime.step(new InputFrame(List.of(new PortInput(1 << 8))));
    byte[] checkpoint = runtime.saveState();
    runtime.loadState(checkpoint);
}
```

路径由可信缓存与打包清单解析，不接受网络传来的任意宿主路径。`content=null` 只用于核心明确支持的无内容模式。上述调用会阻塞，游戏端使用有界后台执行器。

- `CoreInfo`：核心名、版本、初始尺寸、FPS、采样率、`displayAspect()`。
- `InputFrame`：最多四端口；缺席端口归零。`PortInput` 使用标准RetroPad 16位按钮顺序和左右摇杆四个有符号16位轴。
- `AvFrame`：帧号、原始RGBA8、PCM16双声道、采样率、逆时针90度旋转次数、退出请求、`displayAspect()`。空视频回调沿用上一完整帧。
- 显示比例来自核心公开AV/geometry，未声明时回退像素宽高比。它表示旋转前比例；奇数次旋转后显示端取倒数。保留旧 `AvFrame` 八参及 `CoreInfo` 六参构造。
- 像素与显示形状分开处理。真实MAME Pong会从454×262变为1552×240，但核心报告显示比例为 `1.3333333730697632`。游戏显示、共享画面和浏览器按核心比例归一化，未通过裁掉图像修正比例；其他驱动不固定为4:3。

## ABI、媒体和生命周期

软件视频支持0RGB1555、XRGB8888、RGB565及行间距。N64使用GLideN64：worker提供隐藏GLFW上下文、OpenGL/core profile、FBO及深度/模板附件、函数地址与framebuffer回调、context reset/destroy、RGBA回读和方向修正。Minecraft图形上下文不进入核心。

公开环境接口还包括目录、旋转、几何/音频变化、默认及显式选项、四端口数字/模拟输入、输入bitmask、日志接收、无内容模式；SameBoy协商48kHz目标采样率。通用 `diskState` / `selectDisk` / `setDiskEjected` 仅对核心实际提供的disk-control接口有效。FCEUmm明确不提供该接口，FDS使用其公开RetroPad L换面、R弹出/插入操作。

FDS的 `disksys.rom` 必须为8192字节，Lynx的 `lynxboot.img` 必须为512字节，均由玩家合法自备到游戏目录的 `muxi-console/firmware/`，不随产品分发。Lynx `.o` 仅接收有合法头、入口和长度的BS93家用程序对象，不接收宿主机对象文件。MAME使用保留驱动名的原ZIP与可选parent/BIOS依赖，通过第一方 `.mxarc` 容器校验、传输和展开，详见 [媒体说明](ARCADE-MEDIA.md)。

单帧最多2048×2048像素、192000立体声采样；单块状态/电池内存最多24MiB；worker传输包最多64MiB。C `bool`按一字节、`size_t`按指针位宽绑定。异步音频、Vulkan、键鼠/触屏、VFS、联机线等扩展未统一实现；未知环境调用返回false。

N64首帧前可能仍处于核心协程初始化阶段。保存、恢复及未step关闭前会完成必要的native初始化，公开首帧编号仍为0。关闭依次执行unload_game、context_destroy通知、retro_deinit，最后销毁前端FBO/GLFW上下文，确保核心清理时GL仍然有效。打开即关闭、保存后继续五帧关闭、首帧前跨进程恢复均已专项回归。

## 存档与私有传输

`saveState` 返回不透明 **MXS1**：核心序列化、SRAM、RTC三个有界组件。恢复前验证封装，恢复核心保存类型后检查实际内存长度再写回；失败回滚原核心、内存和显示元数据。mGBA初始128KiB保存区可在识别后收敛为32KiB，不能提前以初始大小否决合法状态。

SameBoy首次运行前会报告非零状态大小，却拒绝序列化；恢复前捕获回滚点因此曾在09的GB冷启动入口失败。当前仅在从未执行的核心第一次拒绝序列化时，执行一帧空输入初始化，再重新查询大小并重试；公开首帧编号仍为0。不统一预跑所有软件核心，已可序列化的NES/GBA保持初始SRAM语义。依据为当前pin的[官方SameBoy实现](https://github.com/libretro/SameBoy/blob/90e2899574b7b63c59dce8a7276783426d52a068/libretro/libretro.c)。

MXS1未加入显示比例字段，旧封装结构不变；恢复后重新查询核心当前AV。已用冻结05的v1 worker产生NES/GBA旧MXS1，再由当前v2 worker恢复SRAM标记并运行。调用方仍绑定平台、内容哈希、核心身份、固件身份及组包SHA；不承诺跨核心、跨ROM通用。

内部 **MXCW v2** 仅监听127.0.0.1临时端口，以256位随机token握手。网络字节序帧包含magic、version、opcode、request-id、length和payload；响应关联请求，拒绝错误版本、额外字节、乱序和超限数据。命令为OPEN、STEP、RESET、SAVE、LOAD、SRAM_READ/WRITE、QUIT、DISK_STATE/SELECT/EJECT。v2为AV信息添加显示比例，与同一候选包内固定哈希的worker成对使用。

stdout不承载协议。默认丢弃核心输出；显式离线诊断可继承输出。启动和响应超时15秒；QUIT后等待正常退出，`exit0 && forced=false` 才记成功。JNA/LWJGL提取文件位于本次worker自有临时目录，退出后按目录身份和边界清理；游戏内提取根为 `natives/muxi-console/`。玩家固件、ROM和存档不放在临时native目录。进程隔离不等于允许装载不可信DLL，核心须匹配固定来源与哈希。

## N64与MAME的共享执行

两类平台由当前主控制席运行唯一核心；其他已占用控制席提交各自输入，接收真实像素和PCM，不请求ROM/存档或启动第二个worker。MAME离散电路和N64 GPU状态不以跨进程逐像素一致为前提。其他软件平台保留原有同核心身份的同步机制。

SharedPlay按当前room/epoch/席位/权限路由，主控提供画面和声音。视频上限20fps、尺寸最多512×512、保持核心显示比例；复杂PNG会继续等比缩小以满足单消息24576字节。声音连续重采样为48kHz、PCM16立体声，单批最多4片、合计98304字节，片序、帧号与速率均受限。有界队列拥堵时丢弃新候选，不无限积压。

主控正常离开保存并结束本轮；下一位重新以P1启动恢复已保存状态。已在07真实双端N64验证P1/P2独立画面变化、客席ROM/状态请求拒绝、同批次PNG，以及原主控离开后由原客席P1恢复、旧主控P2只收流。07的MAME游戏内共享也已实测通过；离线Pong两球拍独立输入16项通过。纯旁观网页显示采用独立BrowserLink，只接收像素，不授予控制席。详见 [共享执行](SHARED-PLAY.md) 与 [浏览器联动](BROWSER-LINK.md)。

## 验证范围与复现

21平台的原创诊断/官方无ROM电路基础矩阵共3534项，见 [矩阵](../build/platform-smoke/platform-matrix.json)。这是平台执行证据，不是商业游戏库兼容证明。07精确打包入口N64/FDS/MAME/Lynx媒体链124项通过，21平台实际准入89项通过；原始记录见 [媒体结果](../build/media-final-07/result.json) 和 [准入结果](../build/admission-final-07/checks.log)。MAME的恢复调用可执行，但逐像素续接不一致仍保留为边界。

10的[冷启动恢复矩阵](../build/cold-load-all-21-02/result.json)为21平台、548项、63个独立worker，均正常退出并清理native。每个平台先由另一进程生成正常状态，目标新进程的第一个状态操作直接恢复，恢复前没有step、save或测试侧预热；另测首次保存、坏组件回滚及实际画面。NES/GBA初始SRAM保持不变，20个平台的诊断颜色区域续接一致；MAME仅确认实际恢复执行。旧05 NES/GBA的MXS1亦在当前fresh worker恢复通过。测试用全部17个LibretroCore类文件与10内worker逐字节相同，见[产物绑定](../build/cold-load-all-21-02/candidate10-binding.json)。10真实MC run04亦恢复原252698字节GB状态并正常保存退出，恢复身份、四张主副手昼夜原图和机型守恒结果见[模型审核](MODEL-REVIEW.md)。

```powershell
python -B tools/fetch_cores.py --offline
python -B tools/platform_smoke.py --platform <平台id>
python -B tools/platform_smoke.py --platform n64 --close-sequence fresh-load --close-repeats 3
python -B tools/pong_input_smoke.py
python -B tools/newcoldload_smoke.py --name cold-load-verification
```

这些入口只启动受限独立worker，不启动Minecraft。核心来自 [libretro官方构建站](https://buildbot.libretro.com/nightly/windows/x86_64/latest/)；15个模拟核心覆盖21平台，另有2048作无内容诊断。导出探测仅证明API/二进制身份，不能替代运行。

第一方代码、协议和构建工具已按MIT在GitHub公开。0.1.0发行包内直接附上15个实际打包核心的完整源码、固定子模块、构建资料和原始许可证，以及JNA/LWJGL源码与工作进程的完整源码和独立构建入口。13个官方核心按二进制自报提交定位并保留原字节，这不代表逐字节重建证明；缺少可用提交信息的BlastEm/PCE改为从明确固定源码自行构建。相关pin、依据及交付哈希见[源码清单](../third-party/source_manifest.json)。正式打包的`--release`门只在这些材料完整且与实际二进制一致时通过。

本轮新BlastEm/PCE各通过19项输入/画面/状态诊断及26项零步冷恢复检查，四阶段正常退出，未遗留工作进程。其他13核心的既有诊断范围保留，不把本轮记作全部游戏兼容或所有核心重测。源码交付完成与线上部署是不同事项，当前部署状态见[开发状态](STATUS.md)。

公开依据：[libretro ABI](https://raw.githubusercontent.com/libretro/libretro-common/master/include/libretro.h)、[开发文档](https://docs.libretro.com/development/cores/developing-cores/)、[JNA](https://java-native-access.github.io/jna/5.14.0/javadoc/overview-summary.html)、[Mupen64Plus-Next](https://docs.libretro.com/library/mupen64plus/)。
