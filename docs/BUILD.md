# 构建源码

第一方源码使用仓库根目录的 MIT 许可证。第三方依赖及模拟器核心保留各自许可证；源码和二进制来源见 [核心清单](../third-party/cores.json)、[工作进程依赖](../third-party/worker-dependencies.json)及[第三方源码清单](../third-party/source_manifest.json)。不需要游戏 ROM 或原厂 BIOS 来编译源码。

## 独立重建 Java 工作进程

`tools/build_worker.py`只使用 Python 标准库、Java21 JDK 和固定版本的 Maven Central 依赖，不导入 Minecraft、NeoForge 或私有工作区构建工具。源码位于 `common/src/main/java/net/muxigame/console/runtime/`，当前36个文件，包含普通、GB/GBA、NDS和3DS工作进程；实际class清单以本次构建记录为准。

独立克隆后，用 Python3.11+ 执行：

```text
python -B tools/build_worker.py --name worker-local-01 --java-home /path/to/jdk-21 --cache /path/to/dependency-cache
```

也可以设置 `JAVA_HOME` 后省略 `--java-home`。必须使用完整JDK，不能只安装JRE。Windows路径可加引号，如 `--java-home "C:\Java\jdk-21"`。

首次构建从官方 `https://repo.maven.apache.org/maven2/` 获取以下七个JAR，每个文件验证精确大小和SHA256后才使用：

| 组件 | 版本与内容 | 固定输入 |
|---|---|---|
| JNA | 5.14.0，`jna-5.14.0.jar` | 脚本中的官方URL、1878533字节、SHA256 `34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6` |
| LWJGL | 3.3.3，lwjgl/glfw/opengl三个模块及各自Windows native JAR | `third-party/worker-dependencies.json`六项精确输入 |

这些依赖不包含模拟器核心。构建不会运行工作进程、启动Minecraft、下载游戏或重编第三方C/C++核心。已有缓存若不匹配会拒绝使用并保留原文件。全部依赖已经缓存后，可加 `--offline` 禁止联网。

在本项目总工作区内，缓存固定为 `test-environment/cache/muxi-console/dependencies/`，脚本自动选择；显式指定其他缓存会被拒绝。独立克隆使用自己的 `--cache`，不需要总工作区目录。

每次使用新的 `--name`。输出位于：

```text
build/<name>/runtime.jar       工作进程类，无外部依赖打包
build/<name>/classes/          独立编译结果
build/<name>/compile.args      实际javac参数
build/<name>/compile.log       实际编译输出
build/<name>/build.json        源码/依赖哈希、工具链与可选比较结果
```

编译固定 `--release 21 -encoding UTF-8 -proc:none -implicit:none`，最大堆512MiB、单CPU，并拒绝继承额外Java启动选项。ZIP类条目排序、日期 `2026-01-01 00:00:00` 和原Windows ZIP元数据固定，避免文件时间或构建主机路径进入结果。

可与一个已构建产品JAR核对：

```text
python -B tools/build_worker.py --name worker-compare-01 --java-home /path/to/jdk-21 --cache /path/to/dependency-cache --compare /path/to/muxi-console-runtime.jar
```

`--compare`复核运行库的提取清单、七个嵌入依赖的大小/SHA256、每个worker class，以及整个 `worker/runtime.jar` 原始字节；不相同则保留结果并返回失败。旧版单JAR也可作为比较输入。Java编译器和ZIP压缩器版本也会记录，其他工具链若生成不同字节可从逐class差异定位。

`machine-saves-04`的基准worker为51301字节，SHA256 `99012c1f6043fa9520cda82f14f6d297405cfd1c293446956f7c0eb6fb17ae3a`。对应工具链为Java21.0.2+13-58、Python3.12.7、zlib1.2.13；这只是该基准的精确重建条件，不代表其他Java21发行版必然产生不同结果。

本轮用独立入口实际生成的 `build/public-worker-01/` 已与该基准核对：12源、34class、七个官方依赖和整个worker JAR原字节一致。比较只读取既有候选，不依赖其中的class或依赖进行编译。

0.2.0候选16另由同一独立入口离线重建：36源、116class、217519字节，worker SHA256 `3c7c90baf32bb5498f1f83d809896af2d514ee0d6b5c0e92bc61d721ed023170`。七依赖、逐class和整个worker JAR均与实际Minecraft候选16原字节一致；记录位于本地`build/public-worker-16/build.json`。这不替代实际模拟器运行与关闭验收。

## 完整 Minecraft 模组

完整模组目前沿用 `tools/build.py` 的 muxigame 工作区SDK入口；单独克隆本仓不会自动取得Minecraft映射开发环境。该入口只读取已安装SDK，包含MC集成、领域代码、资源、工作进程及固定第三方核心。没有另外维护一套Gradle工程。

在已登记的真实 `muxigame/muxi-console/` 子仓中执行：

```powershell
python -B tools/generate_assets.py --check
python -B tools/fetch_cores.py --offline
python -B tools/build.py --name mc-local-01 --tests
```

正式发行还需运行 `tools/release_sources.py --prepare` 和 `--verify`，核对18个实际打包核心、4个联机附属库、Dolphin Sys、依赖及工作进程的对应源码，然后使用 `tools/build.py --name release-local-01 --tests --release`。诊断专用2048不进入发行包。该模式拒绝未完成源码/许可交付或尚未批准发行的核心，离线复核源码包与当前二进制pin，并输出三份独立文件：

| 文件 | 内容与安装位置 |
|---|---|
| `muxi-console-<主版本>+mc1.21.1.jar` | 领域与Minecraft集成、模型/UI、服务器所需的小型核心身份清单。客户端与服务端均安装。 |
| `muxi-console-runtime-1.0.0+mc1.21.1.jar` | 18个普通核心、4个联机附属库、独立worker/JNA/LWJGL、Dolphin Sys、提取清单、许可证和源码附件指纹。仅客户端放入`mods/`。 |
| `muxi-console-runtime-1.0.0-sources.zip` | 完整对应源码、固定子模块、补丁、构建说明与原始许可。与运行库放在同一个公开发行页，不放入Minecraft整合包、`mods/`或类路径。 |

`build.json`保留主包的`artifact`、`bytes`、`sha256`、`version`，新增`runtimeArtifact`（`path/bytes/sha256/version`）和正式发行的`sourceAttachment`（`path/bytes/sha256`）。开发构建也输出主包和运行库，但`sourceAttachment=null`，运行库内明确写为尚未验证完整源码交付，不能作为正式发行。

运行库是NeoForge官方支持的[lowcodefml资源模组](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/)，modId为`muxi_console_runtime`，没有`@Mod`入口类。主包只在客户端声明必需依赖，独立服务端无需运行库。客户端通过该mod文件的`findResource`路径读取固定资源，避免把跨模组资源可见性依赖于Java模块的默认类加载行为。每次提取仍校验原条目的大小和SHA256；拆包不改变核心、worker、系统资源的身份或存档格式。

`tools/package_runtime.py`中的运行库版本与源码附件发行地址独立于主包版本。文件排序、ZIP日期和metadata固定，主包版本、构建时间、本机路径不会写入运行库。只修改主包代码或metadata时，保持已验证的源码包与运行库输入不变，运行库便保持相同SHA256；发布套件可按独立文件增量更新。修改worker、核心、依赖、系统资源或运行库内许可/源码内容时，应正常更新运行库版本和主包兼容范围，并重新执行对应验证，不以旧版本名发布不同字节。源码准备工具会验证实际输入；仅主包变化而源码交付输入未变时无需重复`--prepare`，`--verify`仍必需。

源码附件首发与运行库1.0.0同在`v0.2.2`发行页；其文件名、字节数、SHA256及完整源码清单写入运行库的`META-INF/muxi/SOURCE-DELIVERY.txt`和`source_manifest.json`。后续主包可以继续引用这份未变的运行库和对应源码附件，不需要玩家每次重新下载约605MB源码。源码仍为完整现成下载，不能以只给上游链接或按需索取承诺替代。正式发布须在发行页实际上传该附件。

构建读取客户端干净源中已安装的`jei-1.21.1-neoforge-19.32.0.359.jar`作为仅编译依赖，固定1616910字节、SHA256 `0169e38d4771b1a0a6bd9fc92734297105fff2f6ad8a4ed73ad38139b824ea54`；JEI不会嵌入主包、运行库或worker，未安装JEI的客户端仍可使用模组其余功能。

BlastEm与PCE的源码构建入口为 `tools/rebuild_cores.py`；新增核心和联机桥的入口为 `tools/build_dolphin.py`、`tools/build_ds_link_adapter.py`、`tools/build_3ds_link_adapter.py`、`tools/build_link_adapter.py`、`tools/build_gba_link_adapter.py`。使用固定官方提交、校验过的LLVM-MinGW/GNU Make工具链和单CPU编译；具体输入、完整固定子模块、补丁、生成步骤及源码与二进制哈希随源码包交付。GPL派生NWM补丁及原文件保留GPL标记，不纳入第一方MIT授权。其他核心保留官方发行的精确二进制及其自报提交所对应的完整源码，不声称重建出了相同二进制。MinGW运行库、winpthreads和LLVM libunwind的原始许可一并交付。

需要总仓的 `better-mc-remake/scripts/local_mc_runtime.py`、统一 `test-environment/resources/client/game/`、`test-environment/toolchains/java-21/` 和 `minecraft/release/server/` 所供MC1.21.1 / NeoForge21.1.250 SDK。核心档案必须先按 `third-party/cores.json` 的精确哈希准备；`--offline`不会替换缺失输入。该工作区入口继续受根 `AGENTS.md` 和统一测试环境约定约束。

产物与构建检查位于 `build/<name>/`。编译、纯检查与资源校验不等于完整整合包实机验收；实际Minecraft仅通过总仓统一测试入口运行。测试QA JAR和诊断ROM不进入公开产品包。
