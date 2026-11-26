# 业务闭环开发任务计划

首轮实施范围为单租户本地工单。现场验收使用一台授权摄像机、一个已归档模型和一条本地工单规则，用一次真实检测串起告警、派单、办理和回写。端侧交付、多租户下游办结、样本回流和持续目标告警另行推进。[现有业务流程](../BUSINESS_PROCESSES.md)

## P0　告警到本地工单

- [ ] 业务方核定当前规则：仅带录像的事件由人工处理；带图事件按现有配置的优先级匹配，每条命中规则派一单。相关流程全部正常办结后关闭事件。退回到处理中节点、终止或取消均保留待处理；对已办结事件的重开策略待业务确认。
- [x] 新事件和每条自动派单意图同事务入库。后台按原请求号启动流程并记录规则与实例关联，失败退避重试；配置的发起人可从事件详情提前重试。人工建单在创建事务内回填本地事件，并可用原请求恢复历史缺失关联。升级前未保留派单意图的事件需要结合原流程记录人工核对。
- [x] 按可信租户、事件和实际工单实例对账已提交的 Flowable 历史终态，逐单记录办理人、时间和结果，支持中断后重试及重复扫描。
- [ ] **验收**　在事件入库后、工单创建后、办结回写前三处模拟中断。重启后，每条规则产生约定数量的工单；事件详情可追到流程实例、办理人、时间及最终结果。另测多级审批、退回和跨租户访问。

开发入口见[自动派单](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java)、[幂等建单](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-system/src/main/java/com/ruoyi/workflow/service/impl/WfProcessServiceImpl.java)和[审批回写](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-system/src/main/java/com/ruoyi/workflow/service/LocationTaskWorkflowCallbackService.java)。

## P0　端侧交付时核实设备模型

- [ ] 与固件方确认目标板型、固件版本及模型格式。按现行协议更新参考客户端，成对下载和校验模型与类别文件，加载成功后返回两个摘要，加载失败时恢复上一套可用文件。现有 Python 示例只处理模型文件。
- [ ] 定义回执遗失后的核实流程。沿用原请求核对设备身份、两个文件摘要和实际加载情况；设备证据不足时继续保留未知状态，并给出人工核实入口。明确终结旧任务、释放设备下发限制的条件。
- [ ] **验收**　在目标板上测试正常切换、类别文件损坏、加载失败和成功回执丢失。分别核对任务状态、设备运行库存及已知样例推理结果；恢复后可继续下发。

开发入口见[设备协议](VLS-Protocol.md)、[参考客户端](../sdk/examples/model_dispatch_mqtt_client.py)和[下发任务状态](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/service/ModelDispatchTaskService.java)。

## P0　多租户跨系统办结时对接下游

- [ ] 与 location 服务确认外部事件编号、幂等键、终态含义及回调或查询契约。复用现有外发队列，将送达结果和下游办理结果分别记录并展示。
- [ ] 提供最终投递失败的查询和授权重放，沿用原幂等键；为回调缺失提供按原事件对账的方式。
- [ ] **验收**　在两端核对同一事件。模拟响应丢失、重复投递、下游办结后回调中断和跨租户查询，验证只生成约定的一条下游记录，恢复后能看到真实处置结果。

开发入口见[外发队列处理](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/job/EventReportOutboxWorker.java)。

## P1　现场样本到下一版模型

- [ ] 在事件和复核详情增加“加入待标注样本”，保存原图、来源事件、设备、可核实的模型版本和判定理由；对漏报提供从授权录像选帧的入口。校验租户和媒体权限，支持重复提交去重及人工修订后确认标注。
- [ ] 固定一组独立评测样本，用相同类别和指标对比现用模型与候选模型；训练页接通完整评估结果，记录放行或驳回的依据。复用已有训练、模型归档和下发流程。
- [ ] **验收**　选取一条误报和一段漏报录像，追踪样本来源、人工标注、训练包版本、模型摘要与评测结果。选定候选模型在同一路摄像机验证，保留现场效果和本次版本决策。

开发入口见[数据导入](dataset-import/README.md)、[智能标注](dataset-import/SMART_ANNOTATION.md)和[训练页面](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue)。

## P2　持续目标告警

- [ ] 先采集真实设备报文，确认通道内轨迹标识和跟踪会话在重连、重启时的行为，再落实首次告警、证据更新和检测结束的协议。人工处置状态单独保存。
- [ ] **验收**　同一目标持续异常只触发一次首次派单；两个目标同时出现、异常解除后再次出现、设备重启后复用轨迹号，均能正确区分。

详细协议提案与先决条件见[同一目标持续告警接入方案](CONTINUOUS_TARGET_ALARMS.md)。

## 前后端调用链与代码修复清单

本节记录基线 `cf946c17` 的问题。后续复核与执行记录见文末。P0 优先解决闭环中断，P1 处理结果错误、并发及高频路径开销，P2 改善指标展示和交互成本。

| 业务入口 | 页面动作如何落到后台 |
| --- | --- |
| 视频回放 | 选择设备、通道、日期 → `platformRecording` 请求片段索引 → 服务端核验设备归属和访问权 → 按片段 ID 读取 MP4 → 浏览器播放和截取封面。读路径与后台录像保留、清理共用片段索引。[前端 API](../VLStream-Web/VLStream-ui/src/api/platformRecording.js) · [录像服务](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/recording/PlatformRecordingService.java) |
| 数据管理与训练 | 数据管理页通过 `/vlsData/projects` 获取样本、抽检及版本差异；训练页通过 `/vlsAlgorithmTraining` 读取分页指标，再独立轮询日志和任务状态。云端任务进程状态由后台回写。[数据接口](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/controller/VlsDataManagementController.java) · [训练接口](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/controller/VlsAlgorithmTrainingController.java) |
| 端侧与云端执行 | 设备下发接口建立持久任务，HTTP 下载和 MQTT 回执都可能推进同一条记录。云端编排和常驻检测使用各自的调度器，以数据库租约领取任务，在进程内跟踪正在处理的任务。[下发接口](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/controller/VlsModelDispatchController.java) · [云端编排](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/cloudai/CloudAiWorker.java) |
| 事件与工单 | 单租户事件列表和个人事件列表分页读事件并补充执行人；本地 Flowable 审批回调按外部事件编号找回原事件。多租户外发通过持久队列分批领取并调用下游，投递结果单独回写。[本地事件服务](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java) · [外发任务](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/job/EventReportOutboxWorker.java) |

### 正确性与异常恢复

- [ ] **P0 · 单租户云端告警工单无法按编号回写。** 云端事件以 `(id, 0)` 存储，对外编号为裸 `id`。派单将裸编号写入流程变量，审批回调却按 `CONCAT(id,'-',no)` 查找，已关联的工单通过审批仍可能留下待处理事件。统一编号解析，按可信租户与复合主键查找。验收覆盖裸编号、传统带日序号的编号、重复回调和错误工单关联；正确关联的两类事件均只办结一次。[编号生成](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L1009) · [派单变量](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L1842) · [回写查询](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-system/src/main/java/com/ruoyi/workflow/service/LocationTaskWorkflowCallbackService.java#L88)
- [ ] **P0 · 云端执行器释放租约失败会长期占用调度名额。** 两个 worker 都在清理数据库租约之后才从本地 `active` 集合移除任务。若清理 SQL 抛异常，线程已退出，下轮扫描仍尝试续租；同一 worker 残留四项后停止领取新任务。保证无条件释放本地名额，并沿用持久租约确认远端执行状态。验收分别向两条路径注入清租约失败，数据库恢复后原任务可安全接管，其他排队任务也可继续，远端进程无重复启动。[编排任务](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/cloudai/CloudAiWorker.java#L162) · [常驻检测](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/cloudai/CloudDeviceWorker.java#L165)
- [ ] **P1 · 长期保留的录像超过七天无法回放。** 清理按通道的 7、30、90 天或永久设置运行，但片段列表和文件读取都固定过滤最近七天。让两个读入口与通道策略使用一致的时间边界，并保留设备授权检查。验收使用同一批第 1、10、40、100 天的片段，逐项检查四档策略的列表、播放和下载，再测试缩短保留期。[列表与文件读取](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/recording/PlatformRecordingService.java#L108) · [过期清理](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/recording/PlatformRecordingService.java#L266)
- [ ] **P1 · 单租户同前缀事件并发取号会发生主键冲突。** 普通拍传和未预留编号的设备事件均通过当天同前缀的 `COUNT(*) + 1` 取号，再插入 `(id,no)`。两笔不同事件可同时拿到同一号码。按已有编号作用域原子分配，兼容历史编号。验收并发提交 20 笔不同事件，应全部落库且编号互异，同时验证同一设备消息重传仍复用原事件。[取号逻辑](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L2549) · [事件表主键](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/resources/oortcloud_location_task_initialize.sql#L128)
- [ ] **P1 · 批量外发中排队等待也会消耗租约。** 默认一次领取 50 条，租约 60 秒，然后串行请求下游。队尾任务可能已被另一实例接管，原实例仍继续发送。按实际发送容量领取，发前核实持有者及有效期，复用原幂等键。验收用两个 worker 和慢响应下游记录发送次数及持有者，避免因批内等待过期而重复外发；另测下游受理后响应丢失的合法重试。[领取条件](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/mapper/EventReportOutboxMapper.java#L25) · [发送循环](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/job/EventReportOutboxWorker.java#L52)
- [ ] **P1 · 设备成功回执可在并发更新时丢失。** 回执先读取任务状态，再按该旧值作条件更新；若下载完成更新恰好先提交，合法的终态更新影响零行。处理器只记“不匹配”日志，原任务可能停在下载完成。条件更新失败后重新读取并按状态顺序判定，保留消息、设备与双摘要校验。验收控制两事务的先后次序，同时送达下载完成和有效成功回执；所有次序均收敛为同一终态，迟到进度不能回退。[条件更新](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/service/ModelDispatchTaskService.java#L119) · [回执处理](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/service/ModelDeployReplyMqttMessageHandler.java#L55)
- [ ] **P1 · 已停止的云端训练仍被页面轮询。** 后端将确认取消映射成 `stop`，日志与状态接口返回该值；前端终态判断接受 `stopped`，也未匹配展示值“停止”。统一使用服务端状态码判断终态。验收异步取消成功后两路轮询均结束，操作按钮复位且不触发模型转换。[后台终态映射](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/compute/CloudTrainingWorker.java#L416) · [页面终态判断](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2486)
- [ ] **P1 · 切换训练任务后旧响应可更新新弹窗。** 清除定时器不能取消已发出的请求，日志和状态回调返回后会写入共享终端或停止当前轮询。按任务及打开次数隔离请求，并限制每个接口同一时间只有一个请求。验收刻意延迟 A 的日志与终态响应，切换至 B 后再放行；B 不混入旧日志、状态和转换动作，关闭弹窗后也不再写入。[日志与状态轮询](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2661) · [终态处理](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2625)
- [ ] **P2 · 训练指标的字段与空值判断有误。** 行数据写入 `mAP`，模板读取 `mapValue`；三项百分比还将数值 0 处理为空。统一字段，分别处理 0、缺失和有效非零值。验收传入 0、0.65、null，分别显示 0.0%、65.0% 和缺失占位。[字段映射](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2388) · [表格](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L118)

### 性能与资源使用

- [ ] **P1 · 滚动日志窗口重复追加，浏览器持续累积内容。** 远端训练读取最近 200 行；页面只识别“新响应以整个旧响应开头”的增长。窗口移动一行就会再追加整段 HTML。改用有上限的纯文本窗口或带游标的增量协议。验收返回 1–200、2–201 两个连续快照，只显示一份重叠区间；长跑时可见行数和 DOM 大小有上限。[日志读取](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/service/RemoteTrainingService.java#L211) · [终端追加](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2518)
- [ ] **P1 · 事件列表每一行额外查询一次执行人。** 分页组装逐条调用 `eventUsers`，100 条事件会额外发出 100 次查询，空执行人也要查。按当前租户本页事件批量读取并分组，保持执行人顺序。验收分别取 1 和 100 条，执行人查询最多一次，列表、空数组与详情一致。[分页组装](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L2509) · [执行人读取](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L2444)
- [ ] **P1 · 打开一张样本也会读取全项目标注。** 样本详情加载项目全部实例后在内存过滤；抽检还会为每张入选图片重复过滤。按租户、项目与选中图片 ID 批量查询并分组。验收在大项目打开单图、抽检 10 张，统计数据库返回的标注行数仅来自所选图片，标签、计数、顺序与空图结果保持一致。[单图详情](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/data/DataManagementService.java#L224) · [随机抽检](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/data/DataManagementService.java#L423)
- [ ] **P1 · 数据集版本对比为每张共有图片重扫两版全部标注。** 对 S 张共有图片、两版合计 A 条标注，仅筛选就需约 S × A 次判断。先按图片分组或建立每图的标注签名，再比较。验收覆盖重复标注、标注仅换序、空图和内容变化；用 1 千与 1 万张各含一条标注的样本记录实例遍历次数、耗时及内存。[版本对比](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/data/DataManagementService.java#L539) · [标注比较](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/data/DataManagementService.java#L574)
- [ ] **P2 · 回放首片被封面与播放器各下载一遍。** 打开某天录像时，封面预取从第一片开始，播放器同时获取同一个完整 MP4，文件响应标为 `no-store`。首片封面复用播放器已经解码的帧或共享在途文件请求，切换日期后取消旧预取。验收在冷缓存下打开至少有三段录像的一天，首片只有一次完整文件请求；记录传输字节数，切换日期不会展示旧封面。[回放页](../VLStream-Web/VLStream-ui/src/views/VideoAggregation/VideoPlayback.vue#L177) · [文件响应](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/recording/PlatformRecordingController.java#L29)

**本轮核验范围。** 使用内存 SQLite 运行从录像服务提取的两个查询及其时间边界，30 天策略下第 1 天列表和文件查询均命中，第 10 天均返回零行。用 Node 执行训练页的字段映射，0.65 映射为 65.0% 但模板取值为空，数值 0 的三项指标均为空；执行现有日志追加函数，两份相邻的 200 行快照实际生成 400 行，其中仅 201 行不同。其余条目依据源码调用顺序、查询条件或循环结构定位。并发、故障恢复及性能改善仍需按各项验收条件在对应环境验证。

### 复核新增（2026-11-26）

复核对象为 `cf946c17` 加上工作区中尚未提交、针对上述 14 项的修复。下列问题不在原清单中，或由这批修复引入、或未被修复覆盖，均待实施。这批修复须先通过下面的 P0 门禁，才能视为可交付。

- [ ] **P0 · 训练页存在语法错误，整页无法构建。** `appendLogLines` 的 `split('…')` 引号内写入了一个真实换行，`vite build` 报 `Unterminated string constant`，训练页及依赖它的路由均无法打包。改为 `split('\n')`，并检查同批改动中有无类似的写入损坏。验收：`vite build` 成功；页面能打开训练弹窗并显示本地提示行。[日志追加](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2566)
- [ ] **P0 · 训练日志与状态轮询启动即抛异常，训练闭环在页面端中断。** `const ticket = ++logPollingTicket` 与 `++statusPollingTicket` 对 `const` 声明的 ref 自增。构建能通过，运行时会抛 `TypeError: Assignment to constant variable`，两路轮询都不会启动：弹窗没有日志，终态不会被识别，自动转换和发布也不会触发。改为对 `.value` 取号，并核对 `stopLogPolling` 与 `startPollingForTask` 各自递增后，票号比较是否仍成立。验收：真实或模拟训练任务从运行、完成到停止，两路轮询均能启动并在终态结束；切换任务后的旧响应被丢弃。[日志轮询](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2699) · [状态轮询](../VLStream-Web/VLStream-ui/src/views/AlgorithmTraining/AlgorithmTraining.vue#L2754)
- [ ] **P0 · 前端验收门禁需包含构建和行为测试。** 新增的 `training-*.test.mjs` 和 `video-playback-first-clip.test.mjs` 只断言源码字符串。在上面两个缺陷存在时 18/18 全部通过，无法证明修复有效。把 `vite build` 列为必过项；终态判断、日志增量合并和票号隔离改用导入函数或挂载组件后验证行为。验收：在当前缺陷代码上新测试失败，修复后通过。[测试目录](../VLStream-Web/VLStream-ui/test)
- [ ] **P0 · 批量设备拍传仍会并发取号冲突。** 新的 `allocateEventNumber` 依赖 `SELECT … FOR UPDATE` 持锁至事务结束。`reportCameraEvents` 没有事务，逐条调用 `insertCameraEvent` 时每条语句自动提交，行锁随即释放，“读取—加一—写回”的竞争依旧存在。现有并发测试在 H2 上无事务运行，不能代表 MySQL。改为单条原子语句分配（例如 `UPDATE … SET last_no = LAST_INSERT_ID(last_no + 1)`，并在同一连接上读取结果），或由分配方法自行开启并约束事务。验收：在 MySQL 上通过 HTTP 并发提交单条与批量设备事件各 20 笔，全部落库且编号互异；单条事件失败不影响同批其他成员。[批量入口](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L187) · [取号](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L2622)
- [ ] **P1 · 新增的后端测试不会进入版本库。** `.gitignore` 第 61 行忽略 `ruoyi-admin/src/test/**`，`LocationTaskEventNumberTest` 和 `LocationTaskEventUserBatchTest` 只存在于本地，P0-3 和 P1-10 的验证证据无法提交或复跑。须确认这条忽略规则是否有意为之，再强制添加这两个文件或调整规则。验收：干净检出后能运行这两个测试。[忽略规则](../.gitignore#L61)
- [ ] **P1 · 按执行人统计事件时漏掉所有带序号的事件。** `eventStatistics` 的执行人维度用 `te.id = teu.task_event_id` 关联，而执行人行保存的是对外编号：带序号的事件为 `id-no`，只有序号为 0 的云端事件是裸 `id`。结果是普通拍传和设备事件的总数、完成数及完成率全部记为 0。改用与分页列表相同的对外编号表达式，且仍按租户关联。验收：同一执行人分别名下有带序号事件与云端事件，统计总数与列表筛选结果一致。[统计关联](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L1494)
- [ ] **P1 · 单租户 MQTT 事件去重没有数据库约束兜底。** `findDuplicate` 用 JSON 字段查询 `mqtt_message_id` 或 `device_event_id`，`oort_task_event` 上没有相应的唯一约束（多租户表 `vls_event_management` 已有）。两条重投同时到达，或多个实例在无共享订阅时都收到同一消息，两次检查都会通过，事件入库两次并各自触发自动派单。先确认消费线程模型与部署实例数，再增加持久去重键（例如生成列加唯一索引，或独立去重表），冲突时返回原事件。验收：并发投递同一消息 10 次，只生成一条事件和约定数量的工单，设备收到的回执一致。[去重查询](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L984)
- [ ] **P2 · 编号作用域表的种子与说明需修正。** `V1_2_0_084` 按 `oort_task_event.id` 全量分组写入种子，序号为 0 的云端事件也会各占一行，表随云端事件数增长且这些行永远用不到；按日作用域的行也没有清理策略。种子只写入真正的按日作用域（`no > 0`），并明确旧作用域的保留期。另外，批量执行人查询的注释写着“`no` 为 0 时仍用 `id-no`”，与实现（裸 `id`）相反，应予更正。验收：在含云端与普通事件的备份库上执行迁移，作用域行数等于按日作用域数，后续取号接续原最大值。[迁移](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/resources/db/migration/V1_2_0_084__event_number_scope.sql) · [批量查询注释](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/main/java/com/ruoyi/web/controller/compat/LocationTaskCompatService.java#L2457)

**复核时的核验方式。** 在 Linux 本机副本上用 Temurin 17、Maven 3.9.9、Node 24 与 pnpm 10 构建（`/d/code` 是 sshfs 挂载，不支持 pnpm 符号链接，也不支持 Javadoc 保留插件在原地写入 `target`）。原样构建前端时报语法错误；仅修正该行后构建成功。另用 Node 复现了对 `const` 自增的 TypeError。其余条目依据源码定位。

## 执行顺序与验收记录

先走通本地派单并做中断恢复。若首期包含端侧部署或跨系统办结，对应 P0 任务可与本地派单并行；现场样本回流依赖可读取的事件证据，持续告警合并依赖设备报文验证。每项验收记录环境版本、事件或请求编号、预期与实际状态及失败恢复结果；硬件与下游相关任务使用目标设备和服务留证。

### 单租户本地工单实施记录（2026-11-26）

- 升级涉及 Flyway 084（事件取号）、085（设备事件去重）、086（逐规则派单记录）。派单和终态对账任务按接收实例的单租户配置运行。事件详情返回 `work_order_dispatches`；配置发起人可用原 `request_id` 重试失败记录。缺失或跨租户的规则配置保留失败原因，需人工核对。
- MySQL 8 回归覆盖单条与批量上报并发、设备重传、旧事件回执、手动建单关联补偿、反馈防覆盖。独立测试库中的 Flowable 两级审批使用生产完成监听器，中间审批保持待处理，最后一级完成后可从持久历史回写原事件。该用例用测试适配器替代应用目录、表单建单与媒体签名。参见[上报与补偿用例](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/test/java/com/ruoyi/web/controller/compat/LocationTaskIngestionMysqlTest.java)和[两级流程用例](../VLStream-Cloud-Backend-Server/vls-stream/ruoyi-admin/src/test/java/com/ruoyi/web/controller/compat/LocationTaskClosureFlowableMysqlTest.java)。
- 训练页已修正字符串语法和两处轮询票号自增，新增运行实际组件轮询函数的行为测试，覆盖切换任务、丢弃迟到响应及停止回收定时器。
- 待现场验收：从真实摄像机触发检测，使用部署中的流程定义、应用目录、表单与真实办理账号，记录事件号、工单号、各级办理结果和中断恢复证据。升级前没有派单记录的历史事件需单独核实。
