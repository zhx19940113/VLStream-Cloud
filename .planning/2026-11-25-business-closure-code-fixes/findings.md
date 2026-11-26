# Findings & Decisions

## Requirements
- 实施 `docs/BUSINESS_CLOSURE_PLAN.md` 中「前后端调用链与代码修复清单」全部 14 项（4 P0 / 7 P1 / 3 P2）。
- 每项以代码 + 可执行测试证据支撑；无法本地验证的环境项（真实 Flowable 定义、目标板、双实例、慢下游）列为待环境验证。

## Research Findings

### 基线
- 审查基线 commit `cf946c17`。工作树仅 `docs/BUSINESS_CLOSURE_PLAN.md` 有修改（即本任务清单）。
- 后端：Java 8、Spring Boot 2.7、MyBatis-Plus、Flowable 6.8.0、Flyway（`ruoyi-admin/src/main/resources/db/migration/`，最新 `V1_2_0_083`）。
- 前端：Vue 3 + Element Plus + Avue，`<script setup>`。
- Java 测试须 `@Tag("dev")` 才被 Surefire 选中；前端测试为 `node:test` + `vue/compiler-sfc` + `esbuild`。
- 工具链：JDK 17（target 8）、Maven 3.9.11、Node 24.22.1。

### P0-1 云端告警工单无法按编号回写
- 云端事件写入 `LocationTaskCompatService.insertCameraEvent`，主键 `(id, no=0)`。
- 对外编号来自 `toEvent`：`no == 0 ? rawId : rawId + "-" + no`，云端为裸 `id`。
- 派单 `startWorkflow` 将该裸编号写入流程变量 `no`。
- 回写 `LocationTaskWorkflowCallbackService.handleApprovedTask` 仅按 `CONCAT(id,'-',no) = ?` 查，`id-0` 与裸 `id` 不等，查不到；事件停留在待处理。
- 回调查询无租户约束；审批意见仅精确等于「通过」时生效。

### P0-2 云端执行器释放租约失败长期占用名额
- `CloudAiWorker.reconcile` 的 `finally` 先 `UPDATE ... lease_owner=NULL`，后 `active.remove(id)`。
- 该 SQL 抛异常时 `active.remove` 不执行；`active.size() >= 4` 时 `scan` 直接返回，残留 4 项后彻底停止领取。
- `CloudDeviceWorker.reconcile` 同构（`vls_cloud_device_run`）。

### P0-3 单租户并发取号主键冲突
- `nextEventKey(userName)`：前缀 = 清洗用户名 + `-yyyyMMdd`；序号 = `SELECT COUNT(*) FROM oort_task_event WHERE id = prefix` + 1；随后 `INSERT (id,no)`。
- 主键 `PRIMARY KEY (id,no)`；`addEvent` 与 `insertCameraEvent` 共用。
- 两个并发插入可同时取到同一 `no`，主键冲突。
- 设备消息重传去重走 `findDuplicate(sourceMessageId, deviceId, deviceEventId)`，须继续复用原事件。

### P0-4 批量外发排队消耗租约
- `EventReportOutboxWorker.deliverPendingEvents`：`claimBatch` 一次领取 `batchSize`（上限 200，默认属性 50），再串行 `deliverOne`。
- 租约过期以 `locked_at <= staleBefore`（`staleLockMillis` 默认 60s）判定；队尾任务等待期间可能过期，被另一实例接管。
- `claimBatch` 未按实际发送容量分片；发送前不核实持有者与有效期。
- `markSuccess/markFailure` 已带 `workerId + status='PROCESSING'` 条件。
- 幂等键 `idempotency_key` 已持久，重试须复用。

### P1-5 长期保留录像超过 7 天不可回放
- `PlatformRecordingService.segments(id, date)` 下界为 `Math.max(dayStart, now - 7d)`，硬编码 7 天。
- `file(segmentId)` SQL 硬编码 `s.end_ms > now - 7d`。
- `cleanup()` 按每源 `retention_days`（7/30/90/0 永久）清理。
- `retention_days` 列由 `V1_2_0_071` 迁移新增。
- 修复：两个读入口按源策略计算截止；0 = 永久无下界；保留 `owned` 内的设备授权检查。

### P1-6 设备成功回执并发丢失
- `ModelDispatchTaskService.applyHardwareReply`：先 `getByRequestId` 读旧状态，再 `eq(dispatchStatus, 旧值)` 条件更新。
- 若下载完成更新先提交，条件更新影响 0 行，返回 false。
- `ModelDeployReplyMqttMessageHandler` 对 false 只记忽略日志，任务可能停在 `DOWNLOADED`。
- 修复：条件更新失败后重新读取，按状态顺序判定；保留消息 ID、设备 ID、双摘要校验；迟到进度不得回退终态。

### P1-7 已停止云端训练仍被轮询
- `CloudTrainingWorker.finish`：`CANCELLED` → `trainingState = "stop"`。
- `CloudTrainingService.logs` 的状态取自 `training.getTrainStatus().getCode()`，即 `stop`。
- 前端 `getTrainStatusText` 映射 `stop → '停止'`；`isFinishedStatus` 名单为 `completed/failed/stopped/训练完成/训练失败/训练终止`，缺 `stop` 与 `停止`。
- 页面终态判断还接受 `displayStatus`（本地化文案参与逻辑）。
- 修复：以服务端状态码统一判断终态。

### P1-8 切换任务后旧响应污染新弹窗
- `startLogPolling` / `startStatusPolling` 仅 `clearInterval`，已发出的请求无法取消。
- 回调直接写 `terminalOutput`、调用 `handleTrainingFinished`，无任务或打开次数校验。
- `trainingLogRequestGeneration` 仅用于列表侧日志预览，弹窗未使用。
- 修复：引入「任务 ID + 打开次数」令牌；回调前校验；同一接口同一时间只保留一个在途请求。

### P1-9 滚动日志窗口重复追加
- `RemoteTrainingService.getTrainingLogs` 用 `tail -n <lines>` 返回最近 200 行，无游标或偏移。
- `appendLogLines` 只识别「新响应以整个旧响应开头」的增长；窗口右移一行（1–200 → 2–201）时前缀判断失败，整段再追加。
- 修复：终端改为有上限的纯文本行窗口；按与上一快照的最长重叠后缀做增量合并。

### P1-10 事件列表逐行查询执行人
- `pagedEvents` 对每行调用 `toEvent(row)`，`toEvent` 内调用 `eventUsers(externalId)`，每行一次 `SELECT ... WHERE task_event_id = ?`。
- 100 条事件额外 100 次查询，空执行人也照查。
- 修复：按本页外部编号批量读取并按 `task_event_id` 分组；顺序仍按 `created_at ASC`。

### P1-11 单样本详情读取全项目标注
- `detail(projectId, id)` 调 `instances.selectList(scope(projectId))` 读项目全部标注，再内存过滤。
- `review(projectId, count)` 同样读全量，并对每张入选图重复过滤。
- 修复：按 `image_id IN (...)` 批量查询；空集合直接返回空列表。

### P1-12 版本对比每图重扫两版全部标注
- `changedFields` 对每张共有图片调用 `semanticInstances(left,id)` 与 `semanticInstances(right,id)`，各自全量过滤后排序。
- S 张共有图片、两版合计 A 条标注 → 约 S × A 次判断。
- 修复：先按 `imageId` 分组建立每图签名，比较时取两次签名；语义（排序后相等）不变。

### P2-13 训练指标字段与空值判断有误
- 行映射写入 `mAP`，模板读取 `scope.row.mapValue`，模板取值恒空。
- 三处百分比使用 `item.xxx ? ... : null`，数值 `0` 被判为空。
- 修复：字段统一同名；0 显示 `0.0%`，null/undefined 显示缺失占位。

### P2-14 回放首片被封面与播放器各下载一遍
- `loadSegments` → 先 `warmPosters(rows, ticket)` 从第一片开始预取完整 MP4，随后 `play(rows[0], false)` 再取同一片段。
- 文件响应 `Cache-Control: no-store`，同 URL 无法复用。
- 修复：首片封面复用播放器已解码帧；切换日期后取消旧预取；预取与播放共享在途文件请求。

## Technical Decisions
见 task_plan.md 与 progress.md 的逐项记录。
