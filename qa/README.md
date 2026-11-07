# 木犀游戏机独立原生 QA

本目录从空白编写，只读取本仓新产品 API 和统一 runner 文档。两个 QA JAR 分侧安装，Java 包为 `net.muxigame.consoleqa`，不会包含任何产品 class，也不发布。

构建：`python -B qa/build.py --name <新名称> --product build/<候选>/muxi-console-0.1.0-dev.1+mc1.21.1.jar`。原创新 ROM 从 `build/runtime-smoke/two-port-battery.nes` 嵌入，仅为本轮诊断。编译固定 Java21、512MiB、单 CPU；构建不启动 Minecraft。

只能由总仓统一 `scripts/ops/test_environment.py run` 创建 `test-environment/client-N/runs/` 运行。将产品作为 `--mod`、服务端 QA 作为 `--server-mod`、客户端 QA 作为 `--client-mod`。不传旧世界，不复用其他产品测试目录。测试客户端为真实网络玩家；服务端 fixture 只放置平台和三台设备、给空白卡带/控制器/掌机，不生成已刻录卡带。

运行中可用 `python -B qa/drive.py --run <实际编号run路径> --build build/qa/<名称>/qa-build.json --check` 核实 owner、runId、每个真实 PID/创建代际、租约和运行中的产品/QA 原始哈希；`--prepare` 准备服务器场景并读取两真实客户端。驱动不会启动或结束 Minecraft，也不会强杀 worker。

双端连接后使用 `--scenario` 运行完整最小场景：真实刻录界面的拖文件回调、默认拒绝与重新声明、两次刻录及内容去重、原生丢弃/拾取转交、强制回源下载权限、真实方块交互与两控制席、正常 InputSource 前端到服务端再到两个 native worker 的独立输入与画面 CRC，以及五张实际渲染截图。驱动先调用正常产品离开并等待 worker 关闭，最后仍由统一 runner 正常停止三个 Minecraft JVM。

本目录实现和编译通过不等于场景已通过。`scenarioComplete` 只根据本轮真实结果设置；截图仍须人工/图像查看验收，`visualAcceptance` 与 `physicalOSInput` 始终 false。文件拖入、Checkbox/Button、InputSource 均是正常前端回调测试，不冒称实体鼠标、实体键盘或真实手柄操作。
