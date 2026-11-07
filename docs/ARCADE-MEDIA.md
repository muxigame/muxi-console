# 街机媒体、FDS 与完整平台启动

街机运行当前登记的 MAME 0.289，保留其全部 driver 类别；没有换成只覆盖早期机台的核心，没有下载或附带游戏 ROM、父集或原厂 BIOS。MAME 要求媒体集版本与核心相容，具体游戏所需父集/BIOS由用户自行合法提供，文件名必须保留 MAME 原驱动名。

## 刻录与格式

刻录界面选择 `driver.zip` 可自动识别为街机，或手选街机平台。按需点击“添加街机父集 / BIOS”逐个加入原名 ZIP，也可清空依赖。换主文件、添加/清空依赖或改变平台都会撤销界面中唯一的本次授权勾选；重新确认后才允许准备与上传。已有 `.mxarc` 可直接选择，不能再外加依赖。

客户端不直接把 `driver.zip` 改成内容哈希文件名交给 MAME。它先生成我们独立定义的 ZIP 媒体容器 `.mxarc`，上传服务器校验并刻录。容器只有 `manifest.properties` 和 `roms/<set>.zip`，清单是严格 ASCII `key=value` 行，没有 Properties 转义、任意命令、路径或附加字段。例如：

```text
format=mxarc-1
driver=pong
main=pong.zip
sets=pong.zip
bytes.pong.zip=22
sha256.pong.zip=<该ZIP实际SHA256>
```

`driver` 必须匹配 `[a-z0-9_]{1,32}`，`main` 必须等于 `driver + ".zip"`。`sets` 最多64项，必须包含 main；每项都必须有精确 bytes / SHA256。文件名只接受对应的原名 ZIP，大小写在创建容器时统一为小写，重复主集/依赖被拒绝。界面准备包在完成刻录或服务器拒绝后删除，用户原始文件不修改。

启动时再次验证 `.mxarc`，只把清单列出的原始 ZIP 安全提取到当前客户端自己的 `muxi-console/media/<bundle-sha256>/roms/`。MAME 实际收到的 content 是其中的 `driver.zip`，父集与 BIOS ZIP 位于同目录，因此驱动名称与依赖查找语义完整保留。没有用户 `.cmd` 导入入口，没有用户提供的命令行参数。

这个流程依据官方公共接口：libretro MAME [核心信息](https://github.com/libretro/libretro-core-info/blob/master/mame_libretro.info) 声明支持 ZIP / CMD / 7z，官方 [MAME 使用说明](https://docs.libretro.com/guides/softwarelist-getting-started/) 解释 ROM 集版本和父集/BIOS依赖；当前本模组的媒体容器只接收 ZIP 集，不宣称支持所有 CHD/磁盘或软件列表组合。

## 边界

街机容器与全局单文件上传上限均为512MiB，待传事务总预留上限1GiB；其他平台各自上限保持不变。外层 ZIP 每个成员都必须列入清单，路径穿越、目录成员、重复成员、额外字段、尺寸/hash/CRC不符全部拒绝。

每个内层 ROM ZIP 都使用固定32KiB缓冲区读取验证，最多65536成员，单成员解压后最多512MiB，全部集合计解压上限2GiB；超过16MiB且压缩比大于1000的单成员被拒绝。内层名称禁止绝对路径、驱动器、反斜杠、`..`、空段、控制字符、尾点/尾空格和过深路径，大小写重复也拒绝。内层成员不由本模组提取或执行。验证中的临时集逐个检查、逐个删除；缓存中未列出的额外依赖或被修改的集不会静默使用。

## RuntimeFiles 平台接线

21个平台全部有明确核心映射。NES/FDS 使用 FCEUmm；N64 使用 Mupen64Plus-Next，独立工作进程带清单固定的六份 LWJGL/OpenGL/GLFW JAR，固定 `gliden64` / `hle` / `pure_interpreter`；街机使用当前 MAME。其余已登记平台保留原核心映射。

每个运行时资源必须同时存在于嵌入的 `extract-hashes.json` 与 `extract-sizes.json`，两清单键集合必须一致。复制严格限于精确 bytes，最后核对 SHA256；资源硬上限512MiB足以涵盖372431360字节的当前 MAME DLL，不使用64MiB旧假设。六份 GL 依赖还与 `worker-dependencies.json` 逐项核对。缓存路径禁止符号链接/重解析到外部位置。

FDS 用户自行提供合法的 `muxi-console/firmware/disksys.rom`，必须为8192字节；固件不打入候选 JAR。FDS 支持16字节 `FDS\x1a` 头、侧数与65500字节盘面精确相符，或无头的完整65500字节盘面；逐面校验信息标识、文件数、文件头/数据块及长度。FDS 会话身份包括固件SHA256，N64包括固定选项和六份渲染依赖身份，以免不同执行条件静默共用状态。Lynx继续由用户提供 `lynxboot.img`。

## 验证

`ArcadeBundleTests` 的44项检查通过：真实 pack/inspect/extract、主集/父集/BIOS字节保留、稳定封装、已存在输出保护、路径/命令/重复/额外字段拒绝、压缩炸弹、缓存篡改、FDS 头/盘块/长度，以及配额。`build/arcade-media-02/` 的全部 common/domain Java21 编译通过，未启动 Minecraft。

`tools/media_smoke.py --jar <最终候选> --sha256 <固定SHA> --name <新名称>` 独立测试最终候选的真实 `RuntimeFiles.select → ProcessConsoleRuntime.open`，使用空 `pong.zip` 的 MAME 自带电路，以及本仓自写 N64/FDS 诊断与自写诊断固件；不读取第三方游戏或 BIOS，不启动 Minecraft。该测试报告启动、实际图像、存档/load API、像素续接是否相同、退出和临时目录清理，像素相同与否单独保留，不能把 load 调用成功当作确定性恢复证明。成功后清理本轮提取的大型运行时副本，保留必要图片与结果。

对候选 `build/platform-model-05/`（SHA256 `a373967d0d39bbee764fed88e4179b5492ae0267590fb7b19dd1c444668649cb`）的真实媒体链检查：

- `media-launch-03`：严格 `.mxarc` 经 `RuntimeFiles.select` 提取372431360B MAME DLL，实际 content 为 `pong.zip`；已查看真实球拍/球/中线原图，存档与新进程 load 调用成功、两个 worker 正常退出。但恢复后第5帧与原进程续跑 CRC 不同，**不能宣称 MAME 跨进程像素确定性或多人锁步通过**。
- `media-launch-04`：修正自写诊断标题长度后，N64 admission、固定选项、8项 classpath 及资源 bytes/SHA核对成功，实际红蓝帧生成；在 save 后续跑5帧再关闭时，原生 worker 以 `0xC0000005` 退出，本轮失败。打包 worker 与裸测对应类字节完全相同，关闭序列问题交运行时 owner 排查，未静默改候选。
- `media-launch-05`：FDS 最终包真实链23项通过，含固件缺失拒绝、8192B自写诊断固件、精确提取、256×224画面、save/load与跨进程像素续接相同。两个 worker exit0、无强杀、原生临时目录清空，本轮提取副本已按正常测试结束流程清理。

早期 `media-launch-02/03/game/natives` 清理命令被工具策略拒绝，未换通道或重试删除；两目录暂留，合计748720304B。`media-launch-01` 只因本机有两个 Gson 版本而在编译前退出；04的诊断生成器此前20字节标题槽误写21字节的问题已由作者修正，不放宽产品 N64 对齐检查。

这些结果不等同于所有 MAME 驱动、所有游戏、多玩家确定性或完整整合包验收。05用于模型及 NES 浏览器显示审核，MAME/N64 上述边界须后续实修实测。
