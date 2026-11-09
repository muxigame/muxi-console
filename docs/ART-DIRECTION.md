# 原创游戏机：模型与显示接口

本页几何数量及分轮图审保留原开发阶段的固定范围。当前发行0.2.1有24平台/25机型；Wii、NDS、3DS及后续配件姿态的验证见[升级工作单](PLAYSTATION-UPGRADE.md)。下表记录首批机型，当前完整操作以[玩家指南](USER-GUIDE.md)为准。

当前新增`tools/cartridge_case_assets.py`与`tools/gui_item_fit.py`：薄卡册实际14×1.25×9模型单位、原像素盖缝/小扣；全部76物品模型以本体投影居中，144种空机及兼容实卡状态留约1.5像素边距。GUI变换只影响物品栏，非卡册的世界/手持几何保持原样。390资源/30489检查与末轮8张原图、51项原生图标的实审见[机器存档与薄卡册](MACHINE-SAVES.md)。以下旧轮资源数量与图审按原候选冻结。

第一方几何、像素纹理、字形和界面由本仓独立编写。美术输入是 `tools/generate_assets.py` 与 `tools/pixel_hardware.py` 中可编辑的坐标、色值和像素字形；不读取旧分叉、Cartridge或厂商模型文件。公开实物图仅作轮廓和部件布局参考，不导入贴图、厂商logo或游戏封面。平台名称描述兼容范围。

设备采用真实年代设备的像素化造型：独立塑料壳色、上下壳接缝、阶梯圆角、卡槽、插口及实际控制布局，弱化旧统一青金配色、厚重叠层和大铭牌。22款机体均重做几何，不以换色代替外形。九款掌机按north前视local +X为视觉左排列方向区和动作键；游戏UV保持。前两轮按当时要求冻结大屏；本轮按新的电脑显示器要求改为2×2×1真实占格的薄面板、支架与底座，并保留旧存档读取和迁移。最初完整年代机型审查见[前轮审核](PIXEL-HARDWARE-REVIEW.md)，其通过项不自动扩展为本轮新显示器验收。

前轮手持改进将九掌机机壳深度压至原来的76%，连同收小按键后总深度减少约27–29%。AB保留小型像素圆角形，半径0.36–0.40模型单位；Start/Select/Option使用三段圆端胶囊，宽约0.9、高0.24–0.30。桌机仍保留各年代机体的真实形态，不统一压成薄片。通用0保持原bounds和内屏坐标，按钮细化但不破坏旧坐标契约。

本轮22款机体继续增加细小制造部件：掌机屏框下的原创蚀刻、背盖铭纹和背螺钉，桌机顶面螺钉、后置装配标与细散热缝，VB眼罩标记和背螺钉，单双街机侧轨紧固件。它们避开活动显示窗与实际按钮，保持原机型比例、CMD和媒体兼容关系；不导入厂商标志或纹理。

## 固定编号与形态

CMD1..19保持原顺序，FDS追加20，ARCADE追加21。22只表示ARCADE的双人宽机台，不新增平台枚举。后续NDS=23、3DS=24、Wii=25；0或缺省现在表示不能启动游戏的游戏机核心，须通过合成成为具体机型，原机器身份与存档保持。

| CMD | 平台 | 形态与可见几何 |
| --- | --- | --- |
| 1 | NES | 灰黑双色前装桌机、前装挡板、双前口与后散热槽 |
| 2 | SNES | 浅灰圆肩桌机、中间上插槽、电源/复位/退卡机构与双前口 |
| 3 | GB | 暖灰竖壳、深屏框/单色LCD、左十字与右酒红错位双键 |
| 4 | GBC | 紫色竖壳、阶梯圆角、黑控制件与右下扬声孔 |
| 5 | GBA | 靛蓝横向弧握区、浅灰肩键/动作键、独立背盖 |
| 6 | MD | 低矮黑壳、八边环形卡槽台、红键与双前口 |
| 7 | N64 | 深色圆角桌机、中央上插卡槽、四个前置接口 |
| 8 | SMS | 低楔形桌机，长色轨、后置下沉卡槽 |
| 9 | GG | 炭黑横向掌机、蓝Start键、错位双动作键与扬声孔 |
| 10 | SG1000 | 象牙色低桌机、红色轨、上插卡槽和后散热区 |
| 11 | PCE | 紧凑方形桌机，偏置薄卡入口和后散热鳍 |
| 12 | A2600 | 宽楔底座，木色前带、密集顶肋与六个实体拨杆 |
| 13 | A7800 | 纤薄阶梯桌机，梯形顶盖、靠前的中央卡槽 |
| 14 | Lynx | Lynx II风格黑色横向壳、四个重复AB键、Option带与背槽 |
| 15 | NGP | 灰色横向壳、圆盘短帽摇杆、AB与独立Option |
| 16 | NGPC | 蓝色横向壳、圆盘短帽摇杆、AB与独立Option |
| 17 | WS | 灰白横向壳、视觉左上下两组分离四键方向区与右AB |
| 18 | WSC | 银蓝横向壳、两组分离四键方向区、彩色屏框与顶脊 |
| 19 | VB | 宽红visor、连通黑眼罩、双眼框与灰黑分叉桌架 |
| 20 | FDS | 红乳白叠层驱动器/上层适配器、盘口及弹出机构 |
| 21 | ARCADE | 黑红窄机台、单摇杆/彩色按键、投币区和灯箱 |
| 22 | ARCADE 2P | 黑红宽机台、两个独立摇杆/按键区、低操纵台 |

9种掌机为GB/GBC/GBA/GG/Lynx/NGP/NGPC/WS/WSC。VB单人双目设备单列；其桌架不表示多人电视主机。NES/FDS/SNES/MD/N64/SMS/SG1000/PCE/A2600/A7800为外接显示器的桌机。控制席能力由领域层限制，模型上的接口不单独决定游戏兼容性。

每个平台的媒体另有独立尺寸、缺口、沟槽或接点。FDS是带中心轮毂和读取窗的磁盘，PCE/Lynx是薄卡；其他媒体为不同尺寸的卡带。空白卡带保留木犀通用形态。街机媒体是本产品的程序模块，不表示真实街机游戏使用家用卡带。

## 可编辑资源与注册接口

- `block/desk_console_<platform>` 是每个平台的独立几何模型，`block/arcade_cabinet_2p` 是双人机台。
- `item/desk_console_<platform>` 继承对应机身；`item/handheld_<platform>` 继承同一平台轮廓并设置物品展示姿态；`item/cartridge_<platform>` 包含对应媒体几何。
- `desk_console` 的blockstate使用 `facing=north/east/south/west,model=0..22`；屏幕通过facing/part选片，writer和dock通过facing选朝向。
- `assets/muxi_console/model_catalog.json` 提供CMD、形态、控制席、媒体、包围范围、屏幕平面及审核摆台。坐标单位为1/16方块，正面north。所有几何均在0..16边界内。
- 旧通用 `block/desk_console`、`item/template_handheld`、`item/template_cartridge` 保留兼容。桌机平台的旧handheld CMD只保留资产寻址兼容；合法掌机平台由产品逻辑校验。

模型均是可编辑JSON立方体和原始像素PNG。修改应回到生成器后再生成，不手改生成后的JSON来掩盖漂移。

## 动态屏幕接口

当前电脑显示器实际占2×2×1格。新物品放置`part=7..10`，对应`block/monitor_0..3`；anchor为7、位置仍是正面左下，`part-7=column+row*2`，north时第二列向WEST延伸。每片几何和碰撞限制在本格，四片拼成薄面板、连通支架与底脚，不用越界模型代替占格。整体坐标范围x=-16..16、y=0..32、z=2.8..13.2；面板主体z=6.2..8.4，底脚才延伸至完整深度。活动16:9画面为x=-14.6..14.6、y=13.15..29.575、z=6.14。不同游戏继续按自身比例留边，不拉伸画面。

四片作为一整块显示器处理光照，不按分块分别加环境遮蔽。首图暗线最初怀疑是AO，后续正常隐藏HUD的原图确认是MC选中单片的轮廓，面板本身连续；关闭AO不单独作为去缝证明。当前原图与验证见[本轮审核](MONITOR-POLISH.md)。

旧`part=1..6`仍按原3×2位置、旧模型`block/screen_0..5`及原画面x=-31.25..15.25、y=.5..31.5、z=6.90读取。完整加载后只保留旧索引0/1/3/4并改为新7..10，释放旧第三列2/5，不移动anchor；若待移除片包含卡带、整机或控制器数据，则保留旧结构。旧`part=0`使用同风格单格显示器，活动面为x=1.1..14.9、y=6.65..14.4125、z=6.14；其周围足迹、实体、边界均允许时才扩为四片，受阻保持原位，不挤掉相邻方块。

`ScreenBlockItem`放置前预检全部四格，失败不消耗物品；拆任意片只掉一件屏幕并清理同一结构其余片。迁移保存state、实际BE代际及完整NBT，每步写前重核，失败统一倒序恢复仍属于本次写入的位置，不抹掉邻居回调新增的外部方块或BE。四向放拆/阻挡、正常六片迁移及受阻单格清障升级已在本轮MC通过；异常路径仅作源码审核，未进行Minecraft故障注入。`DeviceEntity`的identity、连接源、供电/接口及附带物品字段沿用保存接口。

其他机体内屏及街机屏使用catalog的`screen_plane`/`screen_planes`，正面法线north，随后与机身按facing共同旋转；游戏UV方向保持。

`handheld_dock`底板和插槽最高3/16；客户端直接画实际完整掌机，scale=.7，最低点固定4/16，绕竖轴每tick .5度，没有上下bob。基础模型不内置假的掌机；放入的机型及活动屏幕分别来自真实物品与底座会话。[前轮像素机体实图](../build/pixel-realistic-review/index.html)含当时的两个低位旋转时刻；[更早底座/薄屏实图](../build/dock-review/index.html)区分原出画与补验，不用软件预览替代。

掌机屏幕比例为GB/GBC/GG的10:9、GBA的3:2、Lynx的80:51、NGP/NGPC的20:19、WS/WSC的14:9。21单人街机屏为x4.4..11.6、y8.4..13.8、z4.70；22双人街机屏为x3.2..12.8、y7..14.2、z4.30，均4:3。

VB没有伪装成电视的中央屏。catalog的 `screen_planes` 给出两个眼框内的12:7平面。当前 `preview_mode=duplicated-mono` 表示将同一平面预览重复显示到两眼；不将它称为立体渲染验收。两个眼框各使用完整UV，控制席仍只有1个。

## 手持与按键反馈

当前实际插卡接口由catalog的每机`media_slot`（position/rotation/scale及clip轴/保留半面）与每卡`media_bounds`提供，均用1/16方块坐标；通用掌机和通用桌机有不同slot。`InsertedMediaRenderer`读取`CardData.inserted`或`DeviceEntity.card`的实际卡Stack，裁切变换后的原baked quad及其UV/材质，不画另一个假卡；NES/PCE/FDS的前插姿态为[90,0,180]，接点朝机内、标签朝上。`HardwareCatalog.presentationBounds`合并原壳与可见卡边界，MODEL按合并范围适配，手臂仍以机壳下角定位。

`LcdLoadingRenderer`使用当前同UUID的`ProgressSnapshot`在真实LCD画临时进度；MODEL复用此路径，SCREEN才画二维进度，完成即由真实画面替换。当前380资源/29546检查、132项几何检查与[15张新原图](../build/cartridge-review/index.html)的逐项范围见[本轮审核](CARTRIDGE-SLOTS.md)；未把所有平台的纯几何检查冒称逐机运行。

`HandheldPresentation`在实际RenderHandEvent中画玩家皮肤手臂；主手掌机且副手空时双手支撑，其余按实际持手单手支撑，遵从Minecraft惯用手。它直接绘制原baked机体（包含钮帽重着色）与动态内屏，共用同一模型变换；不复制一套假的平面掌机。只读snapshot记录真实左右手绘制分支与高光quad数，仍须实图确认接触位置和遮挡。

当前反馈以catalog的`button_regions`为权威：每项包含公开RetroPad按钮位0..15、port及完整钮帽立方体的`from`/`to`边界。通用0与控制器分别使用root的`generic_button_regions`、`controller_button_regions`，连同22机体共300个完整钮帽区域。同一像素圆角、十字键或胶囊可由多个区域组成；Lynx重复AB和WS/WSC两组方向区保留其实际逻辑映射。桌机外壳与VB眼罩不虚构游戏AB键。

`HandheldFeedback.body`按原baked quad的四个顶点是否完整落在真实钮帽区域中选择几何，将选中quad全部顶点着天蓝并映到自制白色反馈sprite；面、圆端、侧边及肩键一起改变，其余机壳quad继续原材质与光照。静态街机区对选中钮帽区域作六面覆盖，按port区分单双人席位。反馈与发送到当前会话的输入共用同一份本地采样，松键、设置、退出全屏或实际离开时归零；常规键盘/手柄路径在窗口失焦时也归零。授权外部前端的InputSource仍受全屏捕获门约束，其隐藏窗口采样不计实体失焦验收。反馈不产生游戏输入或工作进程调用，也不声称真实机械下沉。

旧`control_surfaces`、`generic_control_surfaces`、`controller_control_surfaces`共224片零厚表面仍保留几何核验信息，已不用于只覆盖一层按钮顶面的手持反馈。WS/WSC第二方向组使用核心对应L/R/L2/R2位，不将旋屏快捷键虚构为Select实体键；[公开核心输入说明](https://docs.libretro.com/library/beetle_cygne/)可用于核对。

## 全屏与原生键位

右键进入同一掌机会话的MODEL全屏：原机体、活动内屏和真实按钮重着色使用同一GUI变换；再次右键在MODEL和SCREEN纯画面全屏之间循环。SCREEN按游戏显示比例适配窗口并留细边；全屏不画设置按钮、状态栏或常驻按键说明。Esc只清输入并返回普通持有，不调用保存离开；正常存档与结束会话仍走原有独立流程。普通持有不采游戏按键，移动与世界操作保持Minecraft行为。

`NativeControls`按24个平台各注册16个逻辑按钮和8个模拟方向，类别为`key.categories.muxi_console.<id>`，键名为`key.muxi_console.<id>.button.<0..15>`及`.axis.<0..7>`。玩家从Minecraft选项→控制→游戏机键位绑定进入，按设备分组选择平台，在键盘和手柄页之间切换；MC普通按键页隐藏游戏机分组。键盘/鼠标/扫描码及修饰键仍由原生KeyMapping保存到`options.txt`，已有绑定保持；旧JSON只对尚不存在的原生项作首次导入，已保存的原生项优先。F/F8不再是模式入口，可作为普通游戏绑定；右键与Esc保留给全屏交互，不接受为游戏动作绑定。

手柄页提供设备选择、启用/自动和物理按钮/扳机映射，保留原GUID与映射配置。实际GLFW按钮和模拟轴与原生键位组成同一输入，再供会话和外观反馈使用。选中掌机后仅在物品栏上方短暂显示“右键切换全屏模式，Esc退出全屏”，最多60个客户端tick，随后消失；全屏不重复该提示。

## 再生成与校验

```powershell
python -B tools/generate_assets.py
python -B tools/generate_assets.py --check
python -B tools/generate_assets.py --check --preview --gallery-plan
```

生成器只依赖Python标准库。本轮输出380项资源、29546项检查通过；检查覆盖逐字节再现、继承/纹理/配方/中英平台及原生按键语言闭包、PNG解码/CRC/zlib、MC图集可达、CMD稳定映射、机体与媒体差异、掌机比例、单双街机几何，以及新四片覆盖/UV连续/四朝向、旧六片和单片兼容信息。另检查49个实际硬件/媒体的物理接合、13个动态视口无遮挡、通用0坐标契约，以及224控制表面、300完整钮帽区域与实际几何的对应关系。这些资源检查不等于Minecraft图审或迁移实测通过，原生结果另列。

设备材质、细部蚀刻及白色反馈PNG由本仓生成。所有模型纹理必须位于MC1.21.1默认blocks atlas可收录的`textures/block/`或`textures/item/`下。`build/assets-preview.png`是内部软件几何预览，不能作为Minecraft审核图。

历史审核要求与分轮结论见[MODEL-REVIEW.md](MODEL-REVIEW.md)。[前轮手持图库](../build/held-feel-review/index.html)收32次当时的手臂、薄掌机、小按钮、单面反馈与底座采图；[前轮像素设备图库](../build/pixel-realistic-review/index.html)的53图及[更早模型图库](../build/model-review/index.html)保留来源和历史边界。[前轮62次原始采图](../build/monitor-polish-review/index.html)包含60张合格取景与2张保留遮挡图，完整钮帽、原生键位、双全屏及电脑显示器均按本轮证据核对；底座补图只证明完整背侧/低位展示，不冒称正面LCD。实际续办和未验边界见[本轮审核](MONITOR-POLISH.md)。
