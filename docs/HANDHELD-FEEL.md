# 掌机手持、薄壳与按键反馈

本轮针对用户提供的地图持握示例和GBA按键截图改进。手臂、姿态、几何与反馈均为本仓实现；使用Minecraft玩家皮肤及公开渲染接口，不引入暮色或Cartridge实现，不修改终端或Portal。

## 行为与验收要求

| 要求 | 实现及应检查的证据 |
| --- | --- |
| 主手右手、副手左手，主手且副手为空时双手拿 | 实际玩家皮肤手臂；主副手原图和两手接壳图，不能只看渲染分支计数 |
| 遵从Minecraft惯用左手 | 同一台GB用左惯用手的主左/副右及双手原图 |
| 按钮收小、功能键精细 | 九掌机小圆角AB及细胶囊Start/Select/Option；九款本轮原图 |
| 掌机适度削薄 | 两候选JAR目录实际bounds比较，加底座不同角度原图；不以正视图量化厚度 |
| 玩家可调整键位 | 正常设置按钮重绑、保存、独立读回；平台隔离、旧键失效、恢复默认 |
| 操作与移动隔离 | 世界前进时玩家移动且游戏中立；专注时W/Q/数字/F/鼠标回调被消费，位置、库存和选栏不变 |
| 按钮有按下反馈 | 同一真实本地采样驱动天蓝表面高光；idle/按下/松开、方向+B、功能胶囊及肩键原图 |
| 视角与存档保留 | 右键全屏/操作视角键切回不换room/epoch/worker；Esc正常保存，底座同卡恢复及整物返回 |

只有透明手持操作或全屏GameScreen采游戏输入；普通持有、设置页和释放状态只发送中立输入。默认WASD、A=J/B=K、X=I/Y=U、L=Q/R=E、Start=Enter/Select=右Shift；MD/N64另有平台默认。操作视角键默认F且可改，F8释放，Esc保存离开。具体入口见[玩家指南](USER-GUIDE.md#按键和手柄)。

高光读取和控制会话共用的本地采样，不另发按键。它覆盖224个已校验的实际控制表面，按设备位置、卡带身份和席位筛选；不声称模型真的下沉。`HandheldPresentation.snapshot`记录最后实际绘制的手臂分支和反馈数，须结合当前物品、递增sequence及原图使用，不能把历史快照当现时画面。

## 精确输入与冻结范围

[产品04](../build/held-feel-04/muxi-console-0.1.0-dev.1+mc1.21.1.jar)为121470443字节，SHA256 `e817e5553039eb17ed07ec2656540aeb33dfd2384c75150967fdfc638f23540d`。从 `dev@7a24560` 起点开发，73产品Java源，14组2425项纯检查通过；354项生成资源/23534项校验通过。

[逐条JAR比较](../build/held-feel-04/compared-to-pixelhardware01.json)为36项改变、6项新增、无移除。93项runtime/domain、核心与worker原字节不变；25项完整屏幕依赖闭包和display_layout保持，不重新声称跑过21核心矩阵。九掌机的总深度较前候选减少26.76–29.28%，数据见[两JAR几何测量](../build/held-feel-04/asset-delta.json)；桌机保留年代形态，通用0的旧bounds/屏面不变。

01/02首图分别因手与机身分离、手藏进热栏而否决；03细调后仍有下角窄缝。04握持原图通过，live被QA固定控制器回收槽假设阻断。原图、原false结果和正常保存/退出均保留；QA05仅修正常空槽SWAP，产品04未改。

## 最终联合验收

统一run `client-2/runs/muxi-console-held-feel-05`（`303f67499b244eb6b88a143fe082d982`）的[图库](../build/held-feel-review/index.html)含32次原始Minecraft采图：十款空机、GB十二张/GBA八张实际姿态/设置/按键图及两张底座图。十机图由独立代理逐张查看，二十二张live图由root逐张查看，手掌接壳、屏幕/按钮无遮挡、高光对应、松开恢复均通过。32个路径均完成原始SHA、960×540及全像素解码核验；其中GB idle与A-released图内容自然相同，渲染序号/时间不同，不声称32个不同SHA。

模型结果true；原live结果保留false、77项实际断言全true/20图，失败是host回执替换受Windows文件占用影响。只读共享句柄调整后，同run独立续办引用原结果SHA `7b8b42023c1fd4d2944f63b05a3c83094590331d9ff05ad43df46e3e8312a44a`，从原GBA实际cleanup checkpoint `77ebe221c13a665c344fe65a2d7153f27dc3be0c6bb0e0885c581b6f19a4a5fd`恢复；16项/2新图通过，补齐GBA视角/输入隔离/F8/设置/正常保存和底座。没有重放丢回执命令、改卡或覆盖原结果；最终结论依据77+16联合闭合，不将原失败改称无失败通过。

最终GBA仍是 `f02e0323-41b0-406b-bf9c-385acdcfd1fa`、CMD5和原自定义标签。底座正常leave提交462928字节，generation `22db3998-54ce-4ef3-8e20-fde2394ce6fb`、frame728，client/vault同SHA `f805013116046e492258f8e35d4322cd099dcebe28ace88abed65b1cfa99dc1d`，完整原物唯一取回。host四worker均自然退出、forced/failed为0，双端worker为空；三MC JVM正常exit0，原生审计complete且remaining/unknown/errors/actions空，统一入口自动清理943943839字节payload。

续办没有观察超时；独立保持读句柄的替换试验仍有AccessDenied，因此不声称只读共享调整消灭全部Windows竞争。该QA限制与原失败明确保留，不扩大为产品错误或内部测试框架全面修复。

未将正常前端回调等同实体OS键鼠或实体手柄。外部InputSource用于有授权的隐藏窗口前端，不能证明真实窗口失焦设备行为；听感、光影与完整整合包不计本轮。许可交付与发行范围沿用[当前状态](STATUS.md)，未发布。
