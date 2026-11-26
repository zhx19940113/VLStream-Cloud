# Progress Log

## Session: 2026-11-25

### Current Status
- **Phase:** 3 - Implementation
- **Started:** 2026-11-25

### Actions Taken
- 读取 docs/BUSINESS_CLOSURE_PLAN.md，识别新增「前后端调用链与代码修复清单」为本轮待实施范围（14 项）。
- 读取后端 AGENTS.md，确认构建命令、Java 8 基线、Flyway 迁移规则与测试需 `@Tag("dev")`。
- 浏览 LocationTaskCompatService、LocationTaskWorkflowCallbackService、CloudAiWorker、CloudDeviceWorker、
  PlatformRecordingService、EventReportOutboxWorker/Mapper、ModelDispatchTaskService、
  ModelDeployReplyMqttMessageHandler、CloudTrainingWorker、DataManagementService、RemoteTrainingService、
  AlgorithmTraining.vue、VideoPlayback.vue 的相关片段。
- 确认工具链：JDK 17、Maven 3.9.11、Node 可用，~/.m2 已有依赖缓存。

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|

### Errors
| Error | Resolution |
|-------|------------|

### Session 2026-11-26 - Implementation complete

| Item | Change | Evidence |
|------|--------|----------|
| P0-1 | `EventNumbers` (new) + callback resolves bare `id` and `id-no`, constrained to trusted workflow tenant | callback test 7/7 |
| P0-2 | `CloudAiWorker` / `CloudDeviceWorker` release local slot before lease cleanup; cleanup failure logged, not thrown | full compile |
| P0-3 | `oort_task_event_number_scope` + `V1_2_0_084` migration seeded from MAX(no); CAS allocate with retry | numbering test 4/4 (20 threads x 5, all distinct & continuous) |
| P0-4 | claim capacity derived from lease window / request timeout; pre-send ownership check; original idempotency key reused | outbox test 6/6 |
| P1-5 | `segments` / `file` use the source's `retention_days`; 0 = permanent; device auth still first | recording test 10/10 |
| P1-6 | reply re-reads and re-evaluates on lost conditional update; identity + dual digest checks kept | dispatch test 4/4 |
| P1-7 | terminal state = server code set incl. `stop`; localised label never decides | frontend 18/18 |
| P1-8 | log/status polls carry task + open ticket, one in-flight per endpoint | frontend 18/18 |
| P1-9 | bounded plain-text window + longest-overlap-suffix incremental append | tail test 8/8 |
| P1-10 | executors loaded once per page via IN query, empty arrays preserved | batch test 1/1 |
| P1-11 | sample detail queries only the selected image's annotations | compile |
| P1-12 | version compare groups signatures per image instead of rescanning | compile |
| P2-13 | row field and template read the same `mAP`; real 0 renders `0.0%` | frontend 18/18 |
| P2-14 | first clip skipped by warmer, cover from decoded frame, shared in-flight request | playback test 4/4 |

### Still needs a real environment
- P0-1: real deployed Flowable definition, multi-level approval, return/reopen, cross-tenant probe.
- P0-3: 20 truly parallel HTTP submissions (test covers allocator concurrency, not HTTP).
- P0-4: two worker instances and a slow downstream endpoint.
- P1-5: four retention policies against real segment files; shortening retention.
- P1-6: injected transaction interleavings on MySQL.
- P1-11/12: `DataManagementIntegrationTest` requires `VLS_DATA_TEST_JDBC` MySQL.
- P2-14: cold-cache byte accounting in a real browser.
- Frontend: no `node_modules`, so no `vite build` / `eslint` run; syntax checked with `node --check` and SFC tag pairing.

### Errors (this session)
| Error | Resolution |
|-------|------------|
| Random multi-script tokens injected into long `Write` payloads (e.g. `wanted`, `Scratch`, `podcast`, `iotics`) | Short writes, `node --check` / compile as the gate; removed the two temp checker scripts afterwards |
| A `Write` of a whole test file silently emptied `EventReportOutboxWorker.java` | Restored with `git checkout --`, then applied 3 small `Edit` calls |
| Inline `sed`/`python` heredoc with quotes echoed `unexpected EOF` | Dropped to `perl -pi -e` on plain ASCII anchors, or edited by line number |
| Test asserted `eq(0)`/`eq(7)` for a `long` arg but mocks returned `Integer` | Used `eq(0L)` / `eq(7L)` |
| `stillOwned` initially added to the state service, which has no mapper timeout config | Moved into the worker, which already owns `outboxMapper` and `staleLockMillis` |
| `fileRequests.clear()` was inside `clearMedia()`, defeating sharing on every `play()` | Moved to the three selection-change entry points |
| `warmPosters` test asserted a stale location | Updated to the three entry points plus a negative check that `play()` keeps the table |
