# 实体浏览器显示屏联动

实现位于 `common/src/main/java/net/muxigame/console/compat/`。这是独立编写的可选适配；没有读取或迁入 Cartridge / muxi-arcade 实现，没有修改 WebDisplays、MCEF、Portal、干净源或生产。功能须经过下述真实 Minecraft 验收后才能记为已验。

## 当前实际依赖

2026-11-08 只读核对 `minecraft/release/client/mods/`，并读取 JAR 的 `META-INF/neoforge.mods.toml`：

| 组件 | 实际版本 / modId | SHA256 |
| --- | --- | --- |
| `[内置网页浏览器]WebDisplays-REMASTER-2.1.0.jar` | `2.1.0` / `webdisplays` | `2c61926eb8049a098679c5d3710714d49e831152d8b017b822e99eb00849c85e` |
| `[嵌入式框架]mcef-neoforge-2.1.6-1.21.1.jar` | 元数据 `2.1.6-1.21.1-muxi.2-popup.1` / `mcef` | `c8d7c1edd4f3b1085d65240d50b03a768f9efb763b44caa358a17b29979dbe02` |

目标仍为 Minecraft 1.21.1 / NeoForge 21.1.250 / Java21。真实联动测试的两客户端及服务器均应按其正常依赖携带这两个 JAR；客户端 Chromium 原生资源使用统一环境已有的 `mcef-libraries/`。不要启动另外一套测试安装，也不要为此修改以上共享只读资源。没有这两个模组时，游戏机本身继续正常运行。

接口资料来自发布 JAR 的公共签名及上游公开源码：[ScreenBlockEntity](https://github.com/CinemaMod/webdisplays/blob/1.20/src/main/java/net/montoyo/wd/entity/ScreenBlockEntity.java)、[ScreenData](https://github.com/CinemaMod/webdisplays/blob/1.20/src/main/java/net/montoyo/wd/entity/ScreenData.java)、[ScreenRights](https://github.com/CinemaMod/webdisplays/blob/1.20/src/main/java/net/montoyo/wd/core/ScreenRights.java)。上游 1.20 分支只作为接口含义资料，实际 2.1.0 的签名另外用 `javap -public` 核对，不能把上游分支当成当前 JAR 源码等价证明。MCEF 的 `CefBrowser` 接口在已登记的 `mcef/common/java-cef/java/org/cef/browser/CefBrowser.java` 只读核对。

适配只调用公共接口：`Multiblock.findOrigin` / `Vector3i.toBlock`，`ScreenBlockEntity.getScreen(BlockSide)`，`ScreenData.rightsFor(UUID)`、公开 `url` / `browser` 字段，`CefBrowser.loadURL` / `getURL` / `executeJavaScript`。其中 `CHANGE_URL` 权位为 `1`。这些类型只在反射字符串中出现，不构成硬链接依赖；没有抽取、复制或重写浏览器实现。

## 玩家操作

1. 正常建立 WebDisplays 显示面，并正常进入木犀主机或掌机控制席。
2. 游戏画面中按 F8 返回世界，控制席保持占用。手持木犀控制器，潜行使用所选的实体网页显示面。只绑定这一个面，不自动占用附近其他人的显示牌。
3. 选择者必须仍有当前游戏控制权限，并有该 WebDisplays 显示面的更改网址权限。服务器重新计算多方块原点、显示面、操作距离与权限。
4. F8 回到游戏输入界面。绑定显示面连续显示真实模拟画面；同维度、距该显示面原点 32 格以内的其他玩家接收同一像素流。
5. 对同一显示面再次潜行使用控制器解除连接，恢复原网页。源会话结束、选择者离线或失权、显示面移除或其原网页被正常修改时自动解除。

控制器可以在副手，主手保留已装卡的掌机。浏览器页面仅负责显示，游戏输入仍由原有控制席处理；旁观者不会因接收画面而获得输入席位、卡带、ROM 或存档。

## 帧与协议

`ConsoleClient` 在方向旋转完成后，把与内置显示屏相同的 RGBA 传给 `BrowserLinkClient.INSTANCE.frame(long number,int width,int height,byte[] orientedRgba)`。只有服务器 `browser_publish` 指定的当前主控制席会编码；编码在单独、有界的后台任务中进行，已有编码未完成时丢弃新候选，不积压视频队列。

默认 20fps，最长宽度 512、最高 512，保持比例且不放大。客户端可在 `config/muxi-console-browser.json` 设置 `{"fps":20,"maxWidth":512}`；FPS 限制为 1–20，宽度为 64–512。原始帧被最近邻缩放后写为原创最小 RGBA PNG。高熵画面会继续等比缩小，直到整个 PNG 能放入一个 24576 字节的现有 Minecraft 消息；该限制可能降低复杂画面的清晰度。没有固定截图、网页游戏或备用模拟器冒充真实流。

服务器每房间上限 20fps、短时突发 2 帧；每帧验证当前 host、控制权限、epoch、已准备状态及模拟帧序号，再完整检查 PNG 签名、全部 CRC、唯一 IHDR/IDAT/IEND、最大尺寸、精确解压长度、行过滤方式和压缩结束位置。不能携带 PNG 文本、任意附属块、APNG 或其他可执行内容。每个会话最多 8 面，每个服务器最多 32 面。刷新请求按玩家合并，每秒最多执行一次，正常会话切换的晚到请求会在限期到达后补发。帧只转发给真实绑定显示面附近的玩家；跨维度、超范围或已撤销的接收者不再收帧。

| 方向 | 消息 | 数据 |
| --- | --- | --- |
| 客户端→服务器 | `browser_select` | 玩家实际选择的 block position / side；服务器重验持物、潜行、距离、控制席、显示面权限 |
| 客户端→服务器 | `browser_refresh` | 重新取得当前合法附近绑定；不会提供游戏权限 |
| 服务器→客户端 | `browser_bind` / `browser_unbind` | 维度、实体原点、面、房间、epoch |
| 服务器→客户端 | `browser_publish` | 当前 host 是否可提供对应 room / epoch 的像素 |
| 双向经服务器 | `browser_frame` | room、epoch、序号与 PNG；下行另有尺寸、来源主机位置和维度 |

此协议不发送 ROM hash、ROM 字节、存档或核心配置。服务器既有 `rom_read` 的持卡/控制权限规则保持有效。纯旁观客户端只解码 PNG、更新显示纹理和 CEF Canvas，不运行 libretro、不请求 ROM。

## 浏览器生命周期与隔离

每个客户端、每个实体绑定都生成独立随机标识的首方 `data:text/html;base64,...` 文档，仅包含 Canvas 与样式。CSP 禁止网络连接、外部素材、表单、子框架和 base URL；Java 用公共 CEF 接口推送已验证 PNG，通过真实 `Image` 解码后画入 Canvas。脚本同时验证唯一文档 URL、Canvas 标识及无损 BigInt 帧序号，异步解码不会用旧帧覆盖新帧。

没有 HTTP 服务、WebSocket、监听端口、本地文件页面、共享磁盘视频或浏览器输入入口，因此没有 loopback token、路由或生产网页协议。网页导航不会读到 ROM；页面自己没有访问游戏机的桥。数据 URL 只写客户端内存中的显示面字段，服务端不持久化它，不把机器本地地址传播给其他玩家。

客户端保存原网页；解除或离线时仅在该显示面仍指向自己生成的文档时恢复，避免覆盖玩家随后正常导航的页面。epoch 变化先撤销旧 Canvas，再建立新绑定；主控切换重新授权提供者。服务端还保存显示面对象身份，同位置拆除再放置或服务端 Chunk 重载后须重新选择，不自动接管新的物理显示面。模组不会关闭或销毁第三方拥有的浏览器。仅客户端 Chunk 卸载/浏览器重建后会对仍合法的绑定重新加载首方页面。

## 接线与验收范围

根实现只需要在 `ConsoleServer.receive` 和 `ConsoleClient.receive` 的普通 switch 之前各委托一次 `BrowserLinkServer.receive` / `BrowserLinkClient.INSTANCE.receive`；在旋转后帧更新处调用 `frame`；会话停止调用客户端 `stop()`。事件订阅负责 F8、真实选屏、客户端 tick/登出和服务器 tick/停服。

`BrowserLinkClient.INSTANCE.pictureFor(long consolePosition)` 提供旁观者的像素纹理，内置实体屏可在没有本地模拟器画面时使用。`snapshot()` 返回绑定、像素接收、JavaScript 提交与已到达首方页面的真实浏览器数量；提交次数不等于 GPU 显示验收。`nativeBrowser(long displayPosition,String side)` 返回真实 CEF 对象供原生 QA 检查 Canvas/纹理，不能构造假的浏览器或通过反射写状态冒充成功。

离线检查：`tests/compat/BrowserFrameChecks.java` 对真实 PNG 进行独立 ImageIO 互解、通道/透明度检查、随机画面带宽和比例检查、完整截断/CRC/非法块/解压上限拒绝，以及生成文档的隔离检查，最终 185 项通过。`BrowserInputMigrationChecks` 的 10 项检查覆盖旧 F8 绑定迁移与其他键/手柄设置保留；原持久化检查调整测试键避开保留 F8，125 项通过。Java21/512MiB/单CPU 接线编译通过，输出在 `build/browser-compat-03/`。此结果没有声称 WebDisplays 原生显示成功。

后续真实验收已由根统一runner完成：07/run02的双端内置屏与WebDisplays同局原图及真实CEF GPU纹理已核，双方纹理CRC `1eecb87d→c4038634`；纯观众收到186帧/185次JS提交，ROM下载授权、缓存及worker均0。实际无权限拒绝、正常交互绑定、解绑恢复、撤权清理、正常后续导航保留及源结束清理通过；09/run03再核白天/夜晚内置屏亮度与浏览器一致保持。分轮原图/JSON及原失败见[模型审核](MODEL-REVIEW.md)和[图库](../build/model-review/index.html)。操作由正常前端回调驱动，实体键鼠和完整整合包共存仍未验，CEF提交计数不代替原图审核。
