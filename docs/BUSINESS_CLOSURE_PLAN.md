# 业务闭环开发任务计划

所有任务均待开发和验收。首轮建议选择单租户、一台授权摄像机、一个已归档模型和一条本地工单规则。用一次真实检测串起告警、派单、办理和回写，再验证服务中断后仍能得到同一处理结果。[现有业务流程](../BUSINESS_PROCESSES.md)

## P0　告警到本地工单

- [ ] 和业务方确定多条规则同时命中时应派几张工单，以及哪些流程终态能办结、退回或重开原事件。当前自动路径仅处理带图事件，可启动多个匹配流程，事件记录保存首个成功关联。为仅带录像的告警明确派单或人工处理规则。
- [ ] 将事件提交后的自动派单意图持久化，复用已有请求编号和幂等建单服务。保存每条规则的工单关联、处理状态和失败原因，提供重试与核对入口。人工建单成功、随后保存关联失败时也应能恢复原关联。
- [ ] 对照实际 Flowable 定义接入办结回写。当前监听器依赖指定回调和审批意见“通过”，需核对人工与自动路径传入的事件编号、工单标识，并处理重复回调及中间审批节点。
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

## 执行顺序与验收记录

先走通本地派单并做中断恢复。若首期包含端侧部署或跨系统办结，对应 P0 任务可与本地派单并行；现场样本回流依赖可读取的事件证据，持续告警合并依赖设备报文验证。每项验收记录环境版本、事件或请求编号、预期与实际状态及失败恢复结果；硬件与下游相关任务使用目标设备和服务留证。
