# 独立领域层检查

`DomainTests.java` 是无 Minecraft、无 JUnit 的 Java 21 检查。夹具全部在测试中用合成字节生成，临时文件位于 `build/domain-tests/` 并在退出时清理，不包含游戏或 BIOS。测试类为 `net.muxigame.console.tests.domain.DomainTests`。

编译输入：`domain/src/main/java/**/*.java` 和本目录 Java；输出：`build/domain-tests/classes/`。编译使用 `javac -J-Xmx512m -J-XX:ActiveProcessorCount=1`；执行使用 `java -Xmx128m -XX:ActiveProcessorCount=1 -cp build/domain-tests/classes net.muxigame.console.tests.domain.DomainTests`。这只检查纯领域规则，不代表 Minecraft、真实模拟器、网络或游戏兼容验收。

覆盖平台登记、合成格式头与尺寸、SHA-256、CAS 去重/原子可见性/配额、上传声明与偏移/分块哈希/全文件哈希/超时/清理、转交持卡权限与控制席、2/3/4 端口乱序到达一致性、epoch 与序号重放拒绝、缺失输入中性化，以及可选有界检查点的版本/权限/哈希边界。
