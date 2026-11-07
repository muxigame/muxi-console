# 独立运行时补齐（2026-11-08，未发布）

本页保留运行时专项的分阶段记录。当前完整候选10、21平台矩阵/冷恢复和真实MC后续结果以[当前状态](../docs/STATUS.md)与[运行时](../docs/RUNTIME.md)为准；下文的“下一候选”或“没有启动Minecraft”仅描述当时专项，不覆盖最终游戏内验收。

第一方实现位于真实仓 `dev@c98cca5` 的当前工作区；未读旧分叉/Cartridge 实现。公共 ABI 按 libretro 官方 `libretro.h`，OpenGL 通过官方 LWJGL 3.3.3 与 GLFW，三模块六个 JAR 已逐字节比对 Maven Central，来源与 SHA 在 `worker-dependencies.json`。核心 pins 在 `cores.json`；对应源码与公开发行许可交付仍未闭合，`releaseReady=false`。

独立 Java21 编译512MiB/单CPU；父测试与每个 native worker256MiB/单CPU。没有启动 Minecraft、修改干净源或生产。

| 实际执行 | 验证结果 |
|---|---|
| Mupen64Plus-Next `3.0-Vulkan 15bada8`，实际 GLideN64 OpenGL | 18项：原创 MIPS IPL3/VI/SI-PIF ROM；640×480红上蓝下；P1/P2各自 A 使上绿/下黄；状态跨进程恢复；正常退出与 native 清理 |
| FCEUmm `(SVN) e0f3086`，FDS | 18项：原创8KiB诊断传输固件，通过磁盘寄存器将原创双面盘程序读入RAM执行；256×224上红下黑；R弹出、L换面、R插入后上绿；缺固件拒绝；跨进程状态恢复和正常退出 |
| MAME `0.289 (5f5a0774)` | 12项：核心自身的 Pong 离散电路驱动，无游戏 ROM；454×262真实球/球拍画面；状态跨进程恢复与正常退出 |
| 既有 NES / GBA 原创诊断回归 | 23 / 3188项通过；GBA含三个worker、SRAM保存类型收敛、坏组件拒绝和回滚 |

重跑入口：`python -B tools/platform_smoke.py --platform <n64|fds|arcade|nes|gba>`。只生成我们自己的诊断，不下载游戏/BIOS。每平台原始结果和帧图在 `build/platform-smoke/`，核心与内容哈希由结果记录；前三平台已在 manifest 绑定。FDS 测试固件只用于上述自有磁盘，不作为通用 BIOS 或产品资产分发；玩家仍须自行合法提供 `disksys.rom`。

`LaunchConfig`/`AvFrame`接口保持兼容。新增通用 `DiskState`、`diskState`、`selectDisk`、`setDiskEjected`，对应我们自己的 MXCW v1操作9/10/11。FCEUmm公开元数据明确 `disk_control=false`，因此其媒体操作使用已公开的 RetroPad L/R；不伪造其磁盘状态。硬件后端提供隐藏上下文、FBO、深度/模板、过程地址、reset/destroy及top-row RGBA回读，仅在worker装载。

首轮 N64 缺公开 logger导致核心空指针，及销毁 OpenGL 早于 core deinit 的失败已修复后重测；失败时自有native提取文件逐一与官方JAR内DLL哈希对照后清理。最后没有本轮 WorkerMain 或提取目录残留。

这些结果只证明上述诊断或电路实际执行，不代表商业游戏、其它扩展平台、MC内帧传输、实体手柄或完整整合包已验。

## N64 首帧前恢复与关闭修复

后续正式打包入口发现裸矩阵没有覆盖“打开后还没step就load/close”。已在原候选05复现：首帧前恢复时 Mupen 仍在 `initializing`，回滚快照被拒绝，未启动协程的关闭退出码为 `0xC0000005`；独立 `open-only` 也可稳定复现。当前源在硬件核心保存/恢复或未step关闭前完成一次 native 初始化，不递增公开帧号；将 `context_destroy` 通知与实际GL资源销毁拆开，GL上下文保留到 `retro_deinit` 完成。

`tools/platform_smoke.py --platform n64 --close-sequence <open-only|save-continue|fresh-load> --close-repeats N` 新增精确序列回归：打开即关闭5轮、保存后继续5帧再关闭5轮、首帧前跨进程恢复3轮（6个worker），共16个worker全部exit0、forced=false、native目录清理成功。原候选05与运行中的MC未修改；下一正式候选仍须通过实际 `MediaLaunchSmoke`，这段源回归不能代替最终打包入口验收。

公开依据：[libretro ABI](https://raw.githubusercontent.com/libretro/libretro-common/master/include/libretro.h)、[Mupen64Plus-Next](https://docs.libretro.com/library/mupen64plus/)、[FCEUmm元数据](https://raw.githubusercontent.com/libretro/libretro-core-info/master/fceumm_libretro.info)、[MAME元数据](https://raw.githubusercontent.com/libretro/libretro-core-info/master/mame_libretro.info)、[LWJGL配置](https://javadoc.lwjgl.org/org/lwjgl/system/Configuration.html)。

## 核心显示比例传输

`AvFrame` 与 `CoreInfo` 在末尾增加 `double displayAspect`，保留旧8参/6参构造；比例表示旋转前的物理显示宽高比，`rotation` 为奇数时由显示端取倒数。核心未声明比例时按像素宽高回退，非有限数拒绝。工作进程根据公开 AV/geometry 回调更新比例，MXCW内部协议升至v2；原始RGBA、像素尺寸和音频均不改动或裁切。

现代MAME0.289真实Pong240帧：初始454×262，第30帧起1552×240，公开 `retro_get_system_av_info.geometry.aspect_ratio` 与逐帧传输均为 `1.3333333730697632`（float4:3）。14项实际执行/跨进程恢复/正常退出通过，另11项构造兼容与协议检查通过。该值不能替换成1552/240；竖版与其他驱动继续采用各自核心比例。

MXS1结构不加显示元数据，恢复后重新查询核心当前AV；实际从冻结05包的v1 worker产生NES21966字节、GBA462928字节旧MXS1，再由新v2 worker读取，SRAM标记、运行画面与正常退出均通过，分别重新得到比例1.3061224222183228和1.5。原NES23/GBA3188回归与N64首帧前恢复关闭6个worker也通过；结果见 `build/platform-smoke/legacy-state-aspect-result.json` 及各平台result。
