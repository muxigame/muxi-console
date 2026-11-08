# 像素设备外观改进

本轮目标是让游戏机更像真实年代设备的像素化模型。用户随后明确大屏已经改好，不再要求修改；因此屏幕模型、尺寸、碰撞、显示面和依赖公共纹理均冻结。第一方几何、纹理与像素字形自行制作，公开实物图片仅作轮廓参考，不导入模型、贴图或厂商logo。

## 验收范围

| 要求 | 应检查的证据 |
| --- | --- |
| 22款机体均有可辨识的真实设备比例与部件布局 | 当前候选逐款Minecraft世界原图；与本仓生成器坐标和公开实物外观核对 |
| 掌机十字键、AB、功能键、扬声孔和肩键布局合理 | 九款掌机与VB的主副手完整原图；north前视local +X为视觉左 |
| 配色和细节具有年代设备感，减少统一厚夹层和大铭牌 | 各型号独立壳色/材质、上下壳分缝、卡槽、接口与像素阶梯圆角 |
| 卡带、控制器、刻录机和底座外观协调 | 媒体/配件实机台及底座原图，不以软件预览代替 |
| 动态画面与新屏框同变换且不遮挡 | GB及GBA原创诊断的实际手持画面、底座展示与正常保存退出 |
| CMD、兼容关系、控制席和状态逻辑保留 | catalog/源码及候选JAR逐条比较、既有相关检查 |
| 大屏不被间接修改 | 与dock-screen-01候选对比完整模型/纹理依赖闭包、display_layout及相关Java |

所有照片保留Minecraft原始PNG，不裁图、合成或修饰。新几何不能套用旧机型截图宣称通过；旧回归证据仅用于确实未变的运行逻辑。

## 公开外观参考

参考用于辨认比例与部件，不作为纹理或模型文件输入。GB采用原款灰壳竖向比例，可参看[Nintendo实物说明](https://www.nintendo.com/en-gb/Hardware/Nintendo-History/Game-Boy/Game-Boy-627031.html)；MD的低矮黑壳、上插卡槽与前置双接口可对照[SEGA官方外观页](https://www.sega.jp/mdmini/outline.html)。各平台存在区域和修订差异，模型选定一个一致的设备风格，不混用主机和手柄的部件。

少见掌机另对照馆藏：[NGPC圆盘式短摇杆](https://www.computinghistory.org.uk/det/35321/Neo-Geo-Pocket-Color-(Blue)/)、[WonderSwan两组四键方向区](https://www.computinghistory.org.uk/det/59615/WonderSwan/)、[Lynx II外壳](https://www.computinghistory.org.uk/det/62456/Atari-Lynx-II-(Warbirds-Box)/)。SNES主机保留电源、复位和退卡机构，四色ABXY属于手柄；N64主机的四前接口与上插卡槽不能画成三叉手柄。

## 最终核验

真实仓从 `dev@f844682` 起点完成[候选pixel-hardware-01](../build/pixel-hardware-01/muxi-console-0.1.0-dev.1+mc1.21.1.jar)，SHA256 `d46d73b4fff988f20ce85a7d71904e868939489279c429c0fbdbc5b6e3c6f697`。22款机体的几何和材质绑定均改变；21媒体、通用0、控制器、刻录机和底座协调重做。353项资源/22464项检查通过，包含49个实际硬件/媒体的连通和13个动态视口无遮挡检查；原14组2186项纯检查通过。

[新图库](../build/pixel-realistic-review/index.html)只收本轮候选的53张Minecraft原图。统一run为 `client-2/runs/muxi-console-pixel-hardware-01`，runId `851bb6c655e14edaa6046616f6695ce6`。22张世界图和20张主副手已逐张查看，另有总览、媒体/配件原图及正面补图。原媒体/配件台朝镜头的一面是背面，原图保留，不用其证明标签/按钮正面；独立补拍的两张前图闭合这部分外观审核。

| 核验项 | 实际结果 |
| --- | --- |
| 全22机体真实年代外形 | 全部逐张看图通过；NES/SNES/MD双前孔、N64四孔，机台宽度/操作区数量可辨 |
| 九掌机与VB主副手 | 20图完整；方向区/动作键左右正确，NGP短摇杆、WS双四键组和VB眼罩/支架可见 |
| 媒体及配件 | 21媒体正面标签/读取窗、控制器正面、刻录机/底座均有本轮原图 |
| GB/GBA活动内屏 | 两种自写诊断实际刻录、输入改变画面，主副手4图同变换对齐 |
| 底座活动展示 | GBA同卡恢复，两个时刻低位旋转完整可见，内屏未被壳挡，13项窄流程通过 |
| 保存及完整物品 | GB/GBA各按原UUID、原壳和自定义标签保留；3个worker均自然退出，双端worker为空 |
| 运行逻辑与大屏冻结 | [逐条JAR比较](../build/pixel-hardware-01/compared-to-dock-screen01.json)：所有class/worker/core原字节、0条目移除；25项实际屏幕依赖闭包及display_layout完全相同；另有43项屏幕/公共资源广义比对不变 |

`pixel-model-review-result.json`为45张静态原图；`pixel-media-front-result.json`、`pixel-accessories-front-result.json`各补1张；`pixel-live-review-result.json`含13项/6图。底座最终提交462928字节状态，generation `d2be1b39-5691-4cca-b87d-ab4b39fba282`，frame1824，client/vault SHA同为 `14c1fcc68ca93d3d8c4a485e91532932004cc3c9ccb540ed7895144845b89867`。三MC JVM正常exit0，native审计空，统一入口自动清理943872576字节payload；释放后编号槽位已由其他任务合法复用。

未把实体键鼠/手柄、听感、光影、完整整合包或商业游戏兼容当作本轮通过项。MC启动日志有OpenAL设备失败；客户端还保留一条此前通用lastError，来源操作未记录，不能归因为同一原因或宣称零历史错误。实际外观、输入、存档和自然退出断言通过；不为纯外观任务修改既有运行逻辑。未修改Portal、干净源或生产，未发布；第三方许可/对应源码交付状态沿用[当前状态](STATUS.md)。
