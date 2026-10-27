# 可选客户端与区域管理界面

客户端安装 EDB 和 malilib 后，可用 `/edb gui` 或 `/edbclient` 打开界面。服务器仍只需 EDB 与原有 Fabric 依赖；未安装客户端的玩家继续使用原有命令。Litematica 可选，仅用于读取选区。

## 使用

区域列表中点击区域进入备份目录。创建备份是目录的主要操作；区域的编辑范围、高亮和删除放在“区域管理”菜单。备份列表同时显示时间与备注。宽窗口右侧显示所选备份详情，窄窗口进入独立详情页；恢复为主要操作，修改备注和删除放在“更多”菜单。

恢复、删除备份和删除区域先由服务器生成预览，再用 malilib 原生确认对话框确认。关闭窗口不执行操作；明确取消会撤销对应预览。表单收到服务端对应种类的成功结果后才关闭，错误时保留输入。服务器正在处理时只展示阶段进度，空闲时不常驻成功回显或操作说明。真实客户端聊天中的执行结果仍保留。

创建和编辑区域共用一个表单：

- 可填写 XYZ 两个角点，也可选择 XZ 全高度。
- “读取投影选区”读取 Litematica 当前选中的区域子选区；只有一个框时可直接读取。多个框没有明确选中时拒绝，不自动取它们的外围大框。
- 导入只填写坐标和维度，不直接创建服务器区域；后续编辑、预览和保存与手填一致。
- “对齐整区块”向外扩展 X/Z 到区块边界，并切换为全高度。负坐标使用向下取整；不裁掉原选区，也不自动提交。
- 高亮绘制十二条本地包围框边线，包含两端方块。全高度使用当前维度的实际高度。只保留一个高亮，断线清除，不读取／加载区块，不发送粒子。

客户端命令 `/edbclient highlight <区域>` 可请求并高亮服务器区域；`/edbclient highlight clear` 清除。服务器命令 `/edb region <区域> show` 仍提供范围信息。

为支持跨维度编辑，创建／更新命令的坐标前可加 `in <维度>`，例如：

```text
/edb region create factory in minecraft:overworld -32 0 31 63
/edb region factory update in minecraft:the_nether -16 20 -16 15 80 15
```

不写 `in` 时仍采用命令执行者当前维度。服务器会校验指定维度是否可用。区域定义和备份格式不变，不增加迁移。

## 统一执行路径

GUI 的所有写操作均转换为现有 `/edb` 命令，由普通原版命令包提交。服务器仍经同一 Brigadier 命令树、配置权限、目录执行器、预览校验和 `RegionBackupService`；没有另一套 GUI 备份／恢复实现。

确认预览绑定执行者、具体操作、目标、预期元数据、一次性 UUID 和 60 秒期限。GUI 使用 `/edb confirm <UUID>`，聊天中的确认／取消链接也携带 UUID；旧按钮或旧窗口不能确认、取消较新的操作。手输 `/edb confirm`、`/edb abort` 仍操作当前预览。下线和配置重载会清理相应预览。

TPC 的请求 UUID、确认与取消链接、返回上下文做法用于本次交互参考；没有将传送业务或额外的请求管理系统引入 EDB。

## 协议 v1

所有 channel 均使用 `extraordinarydynamicbackup` 命名空间，以 `_v1` 结尾。Fabric 通道能力决定是否支持界面，未支持时不发送请求。

| 方向／类型 | 内容 |
| --- | --- |
| C2S `management_request_v1` | 请求序号、是否读取目录、区域名、区域页、备份页；只读 |
| S2C `management_state_v1` | 请求序号及结构化 NBT：权限、忙闲、阶段、目录页、元数据、待确认操作 |
| S2C `management_feedback_v1` | 现有命令处理器的结果种类／错误；用于表单确认、刷新和错误展示，不解析聊天文本 |
| S2C `management_open_v1` | `/edb gui` 的打开请求 |

目录和备份每页 8 项；只传元数据，不传区块内容。区域名线上最多 64 字符。每个玩家同时最多一个目录查询，目录／状态分别限流；实际目录读取在现有串行 catalog worker 执行。打开、翻页和操作结果触发目录刷新，不逐帧扫描文件。界面开启时轮询轻量状态。

服务器在读取前和发送前检查同一 `BackupConfig.allowed` 权限。没有权限的客户端不会收到目录、备注或范围。维护中只放行本协议的只读请求，其他自定义负载仍受原有屏障限制。维护状态处理不依赖等待模拟恢复的普通任务队列。

请求序号隔离目录响应；返回目录与当前区域不符时不显示旧内容。区域删除后自动返回区域列表。高亮和所有目录数据在断线后清理。

## 文件职责和依赖

| 路径 | 职责 |
| --- | --- |
| `src/main/.../protocol/ManagementPayloads` | 有版本的负载及 Codec 注册 |
| `protocol/ManagementData` | 元数据与精确选区的线上表示 |
| `protocol/ManagementServer` | 权限、限流、异步目录投影、状态和命令结果同步 |
| `command/CommandConfirmations` | GUI／CLI 共用的一次性确认 |
| `src/client/.../client/ManagementClient` | 连接生命周期、请求／响应和当前界面状态 |
| `client/ClientCommands` | 表单到普通命令的转换 |
| `client/gui/ManagementScreen` | 区域导航、备份目录与宽屏详情分栏 |
| `client/gui/BackupDetailsScreen` | 只读详情和相关操作；也用于宽屏详情栏 |
| `client/gui/RegionEditorScreen` | 手填、导入、整区块对齐的共同表单 |
| `client/gui/CommandForm` | 等待统一命令处理器的结果，失败保留输入 |
| `client/gui/ConfirmationScreen` | 基于 `GuiConfirmAction` 的确认框 |
| `client/gui/ListScreen` | 原生 malilib 列表选择／滚动，数据 ID 与截断文字分离 |
| `client/gui/GuiLayout` | 窗口边距、内容宽度和分栏几何 |
| `client/adaptors/litematica/` | 可选桥接；未安装时不链接 Litematica 类型 |
| `client/render/RegionOutline` | 本地线框，不访问世界数据存储 |

控件使用 malilib 的按钮、输入框、列表、标签、下拉菜单和确认对话框。布局不在窄窗口硬挤两栏；宽屏内容宽度有上限。当前 malilib 没有隐藏无溢出滚动条的开关，列表外观适配只遮去无用的满高滑块，继续使用其原生滚动／输入实现。

没有提升原有 Minecraft、Fabric API、Loader 或 Loom 版本。malilib 固定 `4cb96026bf`、Litematica 编译接口固定 `101ac3a23649`，沿用本机 ChainVeinFabric 26.3 的已使用版本。库不打包进 EDB，不成为专用服务器依赖；Litematica 不成为客户端必需依赖。渲染 API 参考 ChainVeinFabric 的 outline，选区读取对照 Litematica 与 litematica-printer。
