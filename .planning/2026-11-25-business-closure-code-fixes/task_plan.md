# Task Plan: 业务闭环代码修复清单实施

## Goal
按 `docs/BUSINESS_CLOSURE_PLAN.md`「前后端调用链与代码修复清单」实施全部 14 项代码修复（4 P0 / 7 P1 / 3 P2），并以编译与测试证据收尾。

## Next Step
对前端 4 项改动补齐校验（无 node_modules 时用 Node --check + 标签配对 + 源码断言），并汇总全部验收证据与待环境验证项。

## Current Phase
Phase 3 - Implementation

## Constraints
- Java 8 语法、Spring Boot 2.7、MyBatis-Plus、Flowable。
- 新表/列变更必须新增 Flyway 迁移（`V1_2_0_NNN__*.sql`），禁止改已提交迁移。
- 保持 diff 局部化，不做无关格式化。
- 测试需 `@Tag("dev")` 才被 Surefire 选中。
- 本仓库默认时间区 Asia/Shanghai。
- 前端为 Vue 3 + Element Plus，测试脚本位于 `VLStream-ui/test/*.test.mjs`。

## Phases

### Phase 1: Discovery
- [x] 读取计划文档与 AGENTS.md
- [x] 定位 14 项修复涉及的前后端代码
- [x] 记录每项现状与缺口到 findings.md
- **Status:** complete

### Phase 2: Design
- [x] 逐项确定最小改动方案与兼容策略
- [x] 确认需要新增的 Flyway 迁移与测试
- **Status:** complete

### Phase 3: Implementation
- [x] P0-1 云端告警裸编号回写（回调服务 + 合规服务）
- [x] P0-2 云端执行器本地名额无条件释放（CloudAiWorker / CloudDeviceWorker）
- [x] P0-3 事件并发取号原子分配（addEvent / insertCameraEvent）
- [x] P0-4 外发按容量领取 + 发前持有者校验
- [x] P1-5 录像保留期读取边界（segments / file）
- [x] P1-6 设备成功回执条件更新丢失
- [x] P1-7 训练页 stopped 终态判断
- [x] P1-8 训练页旧响应隔离
- [x] P1-9 日志窗口重复追加
- [x] P1-10 事件列表执行人批量查询
- [x] P1-11 单样本详情只读所选标注
- [x] P1-12 版本对比按图片分组比较
- [x] P2-13 训练指标字段与空值
- [x] P2-14 回放首片重复下载
- **Status:** pending

### Phase 4: Verification
- [ ] Java 模块编译与相关单元测试
- [ ] 前端测试脚本
- **Status:** pending

### Phase 5: Delivery
- [ ] 汇总证据、剩余环境验证项
- **Status:** pending
