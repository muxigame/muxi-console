# 双全屏、完整按钮与电脑显示器

2026-11-08，本轮从 `dev@3cbfca0b6693e78e22136cb036a6f3c22c8a3eba` 继续独立实现。当前冻结候选为 [monitor-polish-04](../build/monitor-polish-04/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，121516375字节，SHA256 `d8bc6f5fed1963f4b518c2f9fbb389a7e1255e744a9a13aa15be9ffc126f4cd2`。本轮尚未发布；精确构建输入在 [build.json](../build/monitor-polish-04/build.json)。

## 用户要求与实现

| 要求 | 当前行为 |
| --- | --- |
| 整颗按钮变蓝 | 300个实际钮帽体积区域对应原始baked quad，按下时替换整个钮帽的颜色与白色采样材质，包含正面、边缘和侧面；松开恢复原模型。 |
| 两种全屏 | 右键首先放大实际掌机机身及内屏，再右键切换纯画面；继续右键往返，两者共用同一room和worker。 |
| 画面比例与边框 | 纯画面保留原始宽高比，最大化到可用区域的一边，留两像素细边框；不拉伸或裁切。 |
| 短提示 | 选中掌机时在物品栏上方显示“右键切换全屏模式，Esc退出全屏”，约3秒后淡出。全屏没有设置按钮、按键描述或状态条。 |
| 退出与键位 | Esc只退出全屏、清空输入并返回普通持有，同局继续；F/F8可作普通游戏绑定。MC原生“选项→控制→按键绑定”管理键鼠，options.txt保存；手柄选项也在原生控制列表。右键/Esc保留作界面操作。 |
| 电脑显示器 | 真实四片、宽2格×高2格×深1格占用范围，薄窄框16:9面板、细颈、低底座、背部散热与接口。 |
| 旧屏兼容 | 完整六片旧屏保留身份、原点和连接迁移为四片；单格旧屏仅在周围安全可用时扩展，受阻保留并重试。事务失败恢复本次仍拥有的位置。 |
| 机体细节 | 22款平台机体均继续改几何，增加接缝、螺丝、刻字、后盖或连接细节；保留既有模型编号和控制布局。 |

原生键位取代三个自建绑定界面；旧control_surfaces仅保留兼容接口。整颗按钮高光的只读Audit来自实际提交绘制的quad，不能用它代替可见面的原图，也不能称为按钮真实下沉。Esc退出不是保存离开；收起掌机等待正常保存后再装入底座或取卡，见[指南](USER-GUIDE.md)。

## 构建与验证边界

72个产品Java源、15组2616项检查通过，380个生成资源及28773项闭合检查通过。[逐条JAR比较](../build/monitor-polish-04/compared-to-heldfeel04.json)相对held-feel-04为83项改变、36项新增、3项删除；97项领域、运行时、worker、核心及其打包元数据原字节保持。03到04仅关闭五个新屏模型的分块环境遮蔽，不改变其他几何或方向面光照；不将旧21平台长矩阵记为本轮重跑。

首次统一run `client-2/runs/muxi-console-monitor-polish-01` 的首张屏幕图发现暗线，初步怀疑分块阴影，原图和false阶段结果保留；随后三MC JVM正常exit0、native审计空。04候选使用新QA05及新统一run重新验收。其首图暗线只围住准星选中的一个tile，后续同一正面镜头通过普通隐藏HUD补拍，确认面板本身连续、暗线是原生方块选择轮廓。不编辑原图、不覆盖初次结果，关闭分块AO本身不作为暗线消失的证据。

同候选04的新run已完成四向放拆/阻挡、六片迁移及单格受阻/清障升级16项检查和9张屏幕原图，独立普通隐藏HUD补图3项、1张；22机体与10款便携形态共32张当前原图逐张复核通过。游玩阶段已通过短提示/消失、原生控制与F/F8绑定保存重读、同worker首次机身全屏共7项，首5张原图完整；但阶段在人工看图的input()因未分配交互终端而EOF，结果false保留，finally已正常保存并关闭GB worker。后续严格从该原卡实际存档续办，不把该测试脚本错误改写为产品失败或整段通过。

续办已完成38项实际检查及13张新原图。它先核原GB卡带和实际保存点，从同卡恢复后验证完整A钮帽实际12个quad、六方向各2面全部蓝色顶点/白纹理；GB与GBA按下/松开、两全屏原比例最大化、右键往返同worker、Esc保持同局并归零、世界操作隔离、正常保存及底座整物往返均通过。实际GLFW实体设备不计入这些普通前端回调结果。

原13张中的两张底座取景被旧屏自动升级后的面板遮挡，仅作为活动监视器流的记录，不能计为低位机体展示通过。独立窄补图首次因QA漏传pressed参数失败，false/2项既有记录/0照片保留；finally正常保存。随后从该原底座、原卡实际checkpoint恢复，独立补验5项和2张清晰背侧原图完成，展示整台GBA与低底座且无遮挡；两图角度接近，不冒称拍到正面活动LCD或大角度变化。

当前统一run为 `client-2/runs/muxi-console-monitor-polish-02`，runId `cacc86c5c469489a85be6c2a0c9752db`，QA05绑定同一04产品。[当前图库](../build/monitor-polish-review/index.html)逐文件核62次原始采图，其中60张取景合格，另两张遮挡图明确保留并标记。22世界机体与10持有图已独立逐张查看，root核短提示、全屏与按钮、Esc回持有及底座背侧；两张原生Controls/KeyBinds由独立审查确认，原生选项重读另有实际文件证据。原false不能改成全段通过，联合范围为主流程65项和独立补图5项，首次窄补图失败前的2项不另充作完成数。

| 原始记录 | 实际范围 |
| --- | --- |
| [screen](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-screen-result.json) | true，16项、9原图：四向放拆/阻挡与旧屏升级 |
| [model](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-model-result.json) | true，1项完整清单、32原图 |
| [clean monitor](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-monitor-clean-result.json) | true，3项、1张普通隐藏HUD原图 |
| [original play](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-play-result.json) | false/EOF保留，7项实际通过、5原图 |
| [continued play](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-play-continuation-result.json) | true，38项、13原图；精确引用原false及同卡实际保存点 |
| [first dock supplement](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-dock-visual-result.json) | false/QA漏参保留，2项已记录、0照片 |
| [resumed dock supplement](../../test-environment/client-2/runs/muxi-console-monitor-polish-02/console-qa/polish-dock-visual-resumed-result.json) | true，5项、2清晰背侧原图，引用前次实际保存点 |

最终GBA状态SHA256 `abbb413cd5b4ddaead967541a4ef30f22df5a69963c82ae3b9132064b20e329e`，客户端与vault一致，原卡/型号5/自定义tag唯一取回slot2。host累计7个worker自然退出，强停/失败均0，guest未启动worker，双端worker为空。root随后正常关闭三MC JVM，全部exit0，native审计complete且remaining/unknown/errors/actions为空；统一入口退出0并自动清理944107018字节运行payload。随后现有prepare_profile退役入口按精确SHA/已关闭run/PID代际/释放lease/全文件集合核对，退役唯一04准备及526730637字节副本，保留本仓构建、原图与原始运行证据，未触碰后续槽位租约。

普通阻挡和正常旧屏迁移不等价于第三方回调抛出异常的故障注入；异常回滚仅做源码审查，不声称已在MC中故障注入。QA回执队列19项确定性检查通过，本轮实际写回执竞争也有保留ack后恢复的记录，仍不声称消灭全部Windows文件竞争。

实体手柄按键/热插拔、音响听感、Linux、光影、完整整合包及各平台代表性合法游戏仍需专项验收。第一方发行许可及第三方精确对应源码交付沿用[当前状态](STATUS.md)的限制，`releaseReady=false`。未改Portal、Minecraft干净源或生产。
