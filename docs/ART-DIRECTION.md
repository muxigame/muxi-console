# 木犀模块台：原创外观与资源接口

本仓模型、像素纹理和界面文字由本项目从空白编写。生成输入仅为 `tools/generate_assets.py` 中的几何、色值与像素字形，不读取旧分叉、参考模组、厂商模型或任何外部图像。平台名称只表示游戏兼容范围，不把平台外观当作产品形态。

## 形态与颜色

共同语言是墨青底壳、暖白面板、低饱和金属边、琥珀灯条。正面以偏置的小色条作识别，通风口与接点用独立方块几何表达，不依赖绘制的假轮廓。

- 桌面游戏机：低矮的左右分体顶盖，右侧升高的模块槽，前侧状态灯和通风格。
- 掌机：横向屏幕框与两侧握持区，顶部两个独立色块，正面十字键与错位双键。
- 卡带：窄长模块、右上阶梯缺角、底部裸露的接点桥；空白卡带只用木犀标记。
- 刻录机：低台面，偏左的顶置接入槽、右侧实体确认块、正面窄状态窗。
- 屏幕：4:3 面板和偏置阶梯支座，右下一个状态灯；无厂商图案。
- 控制器：开放的双短握柄、中置单功能键、错位双动作键。

19 平台使用同一原创形态，只改变色条、模块标签和两位编号。它们不是现实名机或 Cartridge 模型的变体。标签色同时搭配字母与编号，避免仅靠颜色区分。

## 注册和渲染约定

命名空间 `muxi_console`。方块 `desk_console`、`screen`、`cartridge_writer`；物品包含同名方块物品和 `handheld`、`cartridge`、`blank_cartridge`、`controller`。所有方块默认朝 north，blockstate 使用 `facing=north/east/south/west` 四朝向。

`desk_console`、`handheld`、`cartridge` 物品使用 MC 1.21.1 的 `custom_model_data` 选择平台模型。1..19 顺序固定为：nes、snes、gb、gbc、gba、md、n64、sms、gg、sg1000、pce、a2600、a7800、lynx、ngp、ngpc、ws、wsc、vb。无属性或 0 使用木犀默认外观。方块本体为通用形态，物品属性不会自动改变世界内方块纹理。

屏幕默认 north 时，活动面为模型坐标 x=1.5..14.5、y=3.0..12.75，z=6.94。动态画面应画在 z=6.90；13×9.75 单位严格为 4:3，再跟随方块水平朝向旋转。屏幕自身保留原创待机纹理，未运行时无空洞。

## 界面文字和像素资源

中英文键集一致。平台键 `platform.muxi_console.<id>`；常规界面键 `ui.muxi_console.*`。上传页用一个默认未选的声明框：“我确认有权使用此游戏文件”；选择新文件后需要重新确认。游戏内不放投诉或客服表单。常用按钮短写为“选择文件”“上传游戏”“刻录卡带”“开始游戏”。

客户端可以直接绘制面板，也可用 `textures/gui/panel.png` 的 4px 九宫格。`textures/gui/checkbox.png` 是 32×16 原创像素图，左 16×16 为未选，右 16×16 为已选。GUI 面板与世界模型共用墨青、暖白与琥珀色。

## 再生成与验收

```powershell
python -B tools/generate_assets.py
python -B tools/generate_assets.py --check
python -B tools/generate_assets.py --check --preview
```

生成器仅使用 Python 标准库。`--check` 检查资源逐字节再现、模型继承与纹理引用、边界、blockstate、配方输出及中英平台键闭包；逐张校验 PNG chunk CRC、RGBA8 头、zlib 流并重建像素，检查模型纹理能进入 MC 1.21.1 的 blocks atlas。模型使用的公共色纹理必须在 `textures/block/common/`，不能放在未登记 atlas source 的 `textures/common/`。依据为 MC 1.21.1 官方客户端资源 `assets/minecraft/atlases/blocks.json` 的 `block/`、`item/` directory sources。

native-01 已发现旧 `common/` 路径未入 atlas，主机、刻录机、屏幕外壳和卡带因此显示亮紫黑。该轮原图和日志保留，原闭包检查只证明文件存在，遗漏了 atlas 可达性。已改生成器并迁移 28 张原始色纹理；原 PNG 解码和本地/原 JAR 一致性均通过，错误原因是 atlas 路由。修正的 Minecraft 画面须重新验收，不能用离线检查代替。

合成图 `build/assets-preview.png` 仅用于形态检查，不能代替 Minecraft 截图。资源包加载、实际光照、各朝向、GUI/手持/物品展示和动态屏幕仍须在根统一测试环境中验收。

配方使用原版材料；已刻录的游戏卡带没有合成配方，只能通过服务端刻录流程获得。此文件不授予任何生产发布权限。
