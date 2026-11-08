# 存档存储定向检查

从真实仓运行，使用独立构建输出，不启动 Minecraft 或模拟器：

```powershell
../test-environment/toolchains/java-21/bin/javac.exe -J-Xmx128m -J-XX:ActiveProcessorCount=1 --release 21 -encoding UTF-8 -proc:none -d build/state-vault-checks/classes domain/src/main/java/net/muxigame/console/domain/DomainException.java domain/src/main/java/net/muxigame/console/domain/Hashing.java domain/src/main/java/net/muxigame/console/domain/StateVault.java tests/save/StateVaultTest.java
../test-environment/toolchains/java-21/bin/java.exe -Xmx128m -XX:ActiveProcessorCount=1 -cp build/state-vault-checks/classes net.muxigame.console.domain.StateVaultTest build/state-vault-checks
```

2026-11-07 本机192项通过：actor/id绑定、每actor单上传、分块顺序与大小、完整SHA、主动abort、无进展超时、总配额预留与释放、两代保护、旧代下载与重开宽限、100代持续回收、原子head失败、未发布孤儿恢复、同长度数据损坏拒绝、元数据拒删后重试、OS写锁和32MiB边界。所有测试配置/状态在自有临时目录，结束完整删除。首次测试编译的lambda捕获错误与短时钟测试边界失败均修夹具后重验，未弱化功能检查。

`StateVault(Path)` 默认1GiB预算、32MiB单状态、24576字节分块、60秒无进展超时、旧代60秒保留。每代payload另预留1KiB元数据预算。上传内容完全不透明，不走ROM检测；组件只验存储/身份/完整性，MC适配器每次调用仍必须验证当前host、控制权和卡带。

`head` 原子指向current与previous，其他代从退出这两槽开始计60秒，read活动继续延长内存宽限；重开从恢复完成再给60秒。`now`为毫秒时间。下载适配器须按Snapshot.hash校验完整组包；组件在commit及恢复时复核磁盘SHA。这里保证原子可见性与JVM崩溃恢复，不声明主机突然掉电下的目录项持久化或硬件磁盘验收。

## 机器与游戏联合归属

2026-11-08新增`MachineStateVaultTest`，24项纯Java检查通过；与原192项合计216项。构建入口`tools/build.py --tests`会自动发现本目录的新suite，无需单独维护编译列表。

`MachineSaveKey`使用独立机器UUID和完整ROM SHA256，卡带UUID不进入新存档键。`MachineStateVault`复用`StateVault`，但对所有子库共用一个配额，并对同一上传者、同一机器/游戏限制在途上传。新代际元数据V2绑定完整命名空间；默认旧StateVault构造和V1旧档保持不变。

新增检查覆盖：

- 同机器/同内容复用原进度，跨机器及同机器不同内容分别保存。
- 实际字节和代际头重开后保持，其他机器/游戏不能读取原代际UUID。
- 同一联合键跨上传者并发被拒绝，同一上传者不能同时向不同键上传。
- 未完成提交不替换原头；在途配额计入全局预算，超时释放预留；不同子库不能各自放大总配额。
- 新机器不默认导入旧卡存档，打开和保存机器库不改旧卡头文件。
- 将完整状态库复制到另一游戏目录时，V2命名空间校验拒绝；原库仍能重新打开。

单独运行此suite时，在已有的纯域编译输出中补入`MachineSaveKey.java`、`MachineStateVault.java`及`MachineStateVaultTest.java`，再以相同Java21/128MiB/单CPU限制运行`net.muxigame.console.domain.MachineStateVaultTest`。测试使用自有临时目录并在结束删除，不创建世界、ROM、机器物品或MC进程。最近轻测输出在`build/machine-save-unit-01/`；普通桌机掉落/摆放、掌机/底座和借卡换卡行为仍须原生MC验收。
