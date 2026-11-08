# 原创游戏机：模型与显示接口

第一方几何、像素纹理、字形和界面由本仓独立编写。美术输入是 `tools/generate_assets.py` 与 `tools/pixel_hardware.py` 中可编辑的坐标、色值和像素字形；不读取旧分叉、Cartridge或厂商模型文件。公开实物图仅作轮廓和部件布局参考，不导入贴图、厂商logo或游戏封面。平台名称描述兼容范围。

本轮按用户要求进一步改为真实年代设备的像素化造型：独立塑料壳色、上下壳接缝、阶梯圆角、卡槽、插口及实际控制布局，弱化旧统一青金配色、厚重叠层和大铭牌。22款机体均重做几何，不以换色代替外形。九款掌机按north前视local +X为视觉左排列方向区和动作键；旧左右反向已修正，游戏UV保持。用户随后明确大屏不再修改，因此旧屏、新3×2屏及依赖的公共纹理全部冻结。范围与实图审查见[本轮审核](PIXEL-HARDWARE-REVIEW.md)。

## 固定编号与形态

CMD1..19保持原顺序，FDS追加20，ARCADE追加21。22只表示ARCADE的双人宽机台，不新增平台枚举。0或缺省仍是原木犀通用设备。

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

旧单格屏幕的待机面：x=1.5..14.5、y=3..12.75、z=6.94；动态画面z=6.90，13×9.75严格4:3。各形态内置屏及街机屏使用catalog的 `screen_plane`。正面法线north，随后与机身一起按facing旋转；UV保持前次修复后的方向。

新屏资源为 `block/screen_0..5`，真实宽3格高2格、背板z7..9；`ScreenBlock.PART=0`保留旧屏，1..6对应新片。anchor为正面左下，north右向WEST，整体local x=-32..16、y0..32；活面x=-31.25..15.25、y=.5..31.5，z6.94，动态z6.90，严格3:2外形，具体游戏继续按原比例letterbox。物品图标是独立完整薄屏模型，六片各自保持0..16，不用越界几何冒充占格。

`handheld_dock`底板和插槽最高3/16；客户端直接画实际完整掌机，scale=.7，最低点固定4/16，绕竖轴每tick .5度，没有上下bob。基础模型不内置假的掌机；放入的机型及活动屏幕分别来自真实物品与底座会话。[当前像素机体实图](../build/pixel-realistic-review/index.html)含两个低位旋转时刻；[前轮底座/薄屏实图](../build/dock-review/index.html)区分原出画与补验，不用软件预览替代。

掌机屏幕比例为GB/GBC/GG的10:9、GBA的3:2、Lynx的80:51、NGP/NGPC的20:19、WS/WSC的14:9。21单人街机屏为x4.4..11.6、y8.4..13.8、z4.70；22双人街机屏为x3.2..12.8、y7..14.2、z4.30，均4:3。

VB没有伪装成电视的中央屏。catalog的 `screen_planes` 给出两个眼框内的12:7平面。当前 `preview_mode=duplicated-mono` 表示将同一平面预览重复显示到两眼；不将它称为立体渲染验收。两个眼框各使用完整UV，控制席仍只有1个。

## 再生成与校验

```powershell
python -B tools/generate_assets.py
python -B tools/generate_assets.py --check
python -B tools/generate_assets.py --check --preview --gallery-plan
```

生成器只依赖Python标准库。当前生成353项资源；22464项检查包含逐字节再现、继承/纹理/配方/中英平台键闭包、PNG解码/CRC/zlib、MC图集可达、CMD稳定映射、21个平台机身与媒体的纯坐标差异、掌机屏幕比例、单双街机几何、六片覆盖/UV连续/四朝向及旧屏迁移。另检查49个实际硬件/媒体的物理接合、13个动态视口无遮挡及通用0硬编码坐标保持。168个新增小PNG为自行生成的设备材质，原屏幕公共PNG不变。所有模型纹理必须位于MC1.21.1默认blocks atlas可收录的 `textures/block/` 或 `textures/item/` 下。

`build/assets-preview.png` 是内部软件几何预览，不能作为Minecraft审核图。真实审核要求及分轮结论见 [MODEL-REVIEW.md](MODEL-REVIEW.md)。[当前像素设备图库](../build/pixel-realistic-review/index.html)收53张同候选原图，含22种机体、9掌机及VB完整主副手、媒体/配件与动态内屏；[前轮模型图库](../build/model-review/index.html)保留其原图及分轮来源，不替代本轮新几何验收。
