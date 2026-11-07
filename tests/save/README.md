# StateVault 定向检查

从真实仓运行，使用独立构建输出，不启动 Minecraft 或模拟器：

```powershell
../test-environment/toolchains/java-21/bin/javac.exe -J-Xmx128m -J-XX:ActiveProcessorCount=1 --release 21 -encoding UTF-8 -proc:none -d build/state-vault-checks/classes domain/src/main/java/net/muxigame/console/domain/DomainException.java domain/src/main/java/net/muxigame/console/domain/Hashing.java domain/src/main/java/net/muxigame/console/domain/StateVault.java tests/save/StateVaultTest.java
../test-environment/toolchains/java-21/bin/java.exe -Xmx128m -XX:ActiveProcessorCount=1 -cp build/state-vault-checks/classes net.muxigame.console.domain.StateVaultTest build/state-vault-checks
```

2026-11-07 本机192项通过：actor/id绑定、每actor单上传、分块顺序与大小、完整SHA、主动abort、无进展超时、总配额预留与释放、两代保护、旧代下载与重开宽限、100代持续回收、原子head失败、未发布孤儿恢复、同长度数据损坏拒绝、元数据拒删后重试、OS写锁和32MiB边界。所有测试配置/状态在自有临时目录，结束完整删除。首次测试编译的lambda捕获错误与短时钟测试边界失败均修夹具后重验，未弱化功能检查。

`StateVault(Path)` 默认1GiB预算、32MiB单状态、24576字节分块、60秒无进展超时、旧代60秒保留。每代payload另预留1KiB元数据预算。上传内容完全不透明，不走ROM检测；组件只验存储/身份/完整性，MC适配器每次调用仍必须验证当前host、控制权和卡带。

`head` 原子指向current与previous，其他代从退出这两槽开始计60秒，read活动继续延长内存宽限；重开从恢复完成再给60秒。`now`为毫秒时间。下载适配器须按Snapshot.hash校验完整组包；组件在commit及恢复时复核磁盘SHA。这里保证原子可见性与JVM崩溃恢复，不声明主机突然掉电下的目录项持久化或硬件磁盘验收。
