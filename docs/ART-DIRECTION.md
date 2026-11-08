# 原创游戏机：模型与显示接口

第一方几何、像素纹理、字形和界面由本仓独立编写。唯一美术输入是 `tools/generate_assets.py` 中可编辑的坐标、色值和像素字形；不读取旧分叉、Cartridge、厂商模型或外部图片。平台名称描述兼容范围，不使用真实厂商logo或游戏封面。

2026-11-08新增逐平台外形。旧版“所有平台共用一个模型换色”的方案已被替换。统一墨青底壳、暖白面板、金属边和琥珀灯保留为本项目语言；平台识别同时依靠轮廓、媒体、操作区与文字。颜色不能替代几何审核。

## 固定编号与形态

CMD1..19保持原顺序，FDS追加20，ARCADE追加21。22只表示ARCADE的双人宽机台，不新增平台枚举。0或缺省仍是原木犀通用设备。

| CMD | 平台 | 形态与可见几何 |
| --- | --- | --- |
| 1 | NES | 宽型前装桌机，两层后盖、前装挡板、前置接口 |
| 2 | SNES | 肩部阶梯桌机，中间拱形卡槽、两侧实体顶键 |
| 3 | GB | 纵向高身掌机，上方方屏、下方十字键与错位双键 |
| 4 | GBC | 窄纵向掌机，冠形顶缘、右下阶梯、短横扬声孔 |
| 5 | GBA | 横向桥形掌机，两侧突出握区和独立肩键 |
| 6 | MD | 八边轮廓桌机，中央多层环形卡槽台 |
| 7 | N64 | 三瓣底盘、后方卡槽梁、四个前置接口 |
| 8 | SMS | 低楔形桌机，长色轨、后置下沉卡槽 |
| 9 | GG | 厚横向掌机，高屏框、右侧竖向功能键 |
| 10 | SG1000 | 象牙色打字台轮廓，后横梁和右侧高卡槽塔 |
| 11 | PCE | 紧凑方形桌机，偏置薄卡入口和后散热鳍 |
| 12 | A2600 | 宽楔底座，木色前带、密集顶肋与六个实体拨杆 |
| 13 | A7800 | 纤薄阶梯桌机，梯形顶盖、靠前的中央卡槽 |
| 14 | Lynx | 长横向掌机，宽端盖与独立右侧功能键列 |
| 15 | NGP | 近方形横向掌机，阶梯圆盘式拇指操作区 |
| 16 | NGPC | 波肩掌机，抬高屏冠、双色下缘和侧裙 |
| 17 | WS | 非对称横向掌机，左侧上下两套方向键 |
| 18 | WSC | 纤薄横向掌机，竖向侧操作区、下方功能键与顶脊 |
| 19 | VB | 双目镜筒、双眼框和分叉三脚桌架，单人viewer |
| 20 | FDS | 下方磁盘驱动器与上层适配器，宽盘口及弹出块 |
| 21 | ARCADE | 窄立式单人机台，突出操纵台、单摇杆、顶部灯箱 |
| 22 | ARCADE 2P | 宽立式双人机台，两个独立摇杆及按键区 |

9种掌机为GB/GBC/GBA/GG/Lynx/NGP/NGPC/WS/WSC。VB单人双目设备单列；其桌架不表示多人电视主机。NES/FDS/SNES/MD/N64/SMS/SG1000/PCE/A2600/A7800为外接显示器的桌机。控制席能力由领域层限制，模型上的接口不单独决定游戏兼容性。

每个平台的媒体另有独立尺寸、缺口、沟槽或接点。FDS是带中心轮毂和读取窗的磁盘，PCE/Lynx是薄卡；其他媒体为不同尺寸的卡带。空白卡带保留木犀通用形态。街机媒体是本产品的程序模块，不表示真实街机游戏使用家用卡带。

## 可编辑资源与注册接口

- `block/desk_console_<platform>` 是每个平台的独立几何模型，`block/arcade_cabinet_2p` 是双人机台。
- `item/desk_console_<platform>` 继承对应机身；`item/handheld_<platform>` 继承同一平台轮廓并设置物品展示姿态；`item/cartridge_<platform>` 包含对应媒体几何。
- `desk_console` 的blockstate使用 `facing=north/east/south/west,model=0..22`；screen和writer仍只指定facing。
- `assets/muxi_console/model_catalog.json` 提供CMD、形态、控制席、媒体、包围范围、屏幕平面及审核摆台。坐标单位为1/16方块，正面north。所有几何均在0..16边界内。
- 旧通用 `block/desk_console`、`item/template_handheld`、`item/template_cartridge` 保留兼容。桌机平台的旧handheld CMD只保留资产寻址兼容；合法掌机平台由产品逻辑校验。

模型均是可编辑JSON立方体和原始像素PNG。修改应回到生成器后再生成，不手改生成后的JSON来掩盖漂移。

## 动态屏幕接口

旧单格屏幕的待机面：x=1.5..14.5、y=3..12.75、z=6.94；动态画面z=6.90，13×9.75严格4:3。各形态内置屏及街机屏使用catalog的 `screen_plane`。正面法线north，随后与机身一起按facing旋转；UV保持前次修复后的方向。

新屏资源为 `block/screen_0..5`，真实宽3格高2格、背板z7..9；`ScreenBlock.PART=0`保留旧屏，1..6对应新片。anchor为正面左下，north右向WEST，整体local x=-32..16、y0..32；活面x=-31.25..15.25、y=.5..31.5，z6.94，动态z6.90，严格3:2外形，具体游戏继续按原比例letterbox。物品图标是独立完整薄屏模型，六片各自保持0..16，不用越界几何冒充占格。

`handheld_dock`底板和插槽最高3/16；客户端直接画实际完整掌机，scale=.7，最低点固定4/16，绕竖轴每tick .5度，没有上下bob。基础模型不内置假的掌机；放入的机型及活动屏幕分别来自真实物品与底座会话。[本轮实图](../build/dock-review/index.html)区分原出画与补验，不用软件预览替代。

掌机屏幕比例为GB/GBC/GG的10:9、GBA的3:2、Lynx的80:51、NGP/NGPC的20:19、WS/WSC的14:9。21单人街机屏为x4.4..11.6、y8.4..13.8、z4.70；22双人街机屏为x3.2..12.8、y7..14.2、z4.30，均4:3。

VB没有伪装成电视的中央屏。catalog的 `screen_planes` 给出两个眼框内的12:7平面。当前 `preview_mode=duplicated-mono` 表示将同一平面预览重复显示到两眼；不将它称为立体渲染验收。两个眼框各使用完整UV，控制席仍只有1个。

## 再生成与校验

```powershell
python -B tools/generate_assets.py
python -B tools/generate_assets.py --check
python -B tools/generate_assets.py --check --preview --gallery-plan
```

生成器只依赖Python标准库。当前生成185项资源；12861项检查包含逐字节再现、继承/纹理/配方/中英平台键闭包、PNG解码/CRC/zlib、MC图集可达、CMD稳定映射、21个平台机身与媒体的纯坐标差异、掌机屏幕比例、单双街机几何、六片覆盖/UV连续/四朝向及旧屏迁移。所有模型纹理必须位于MC1.21.1默认blocks atlas可收录的 `textures/block/` 或 `textures/item/` 下。

`build/assets-preview.png` 是内部软件几何预览，不能作为Minecraft审核图。真实审核要求及分轮结论见 [MODEL-REVIEW.md](MODEL-REVIEW.md)。22种新形态已有逐款Minecraft世界原图，9掌机及VB另有完整主副手图；[图库](../build/model-review/index.html)保留原图、来源和实际动态屏幕结果。
