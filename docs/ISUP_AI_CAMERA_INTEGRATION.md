# ISUP 协议模块接入海康 AI 摄像头 —— 任务计划与实施说明

> 状态：**未实施**（方案参考）。曾按本方案实现，2026-11-25 已全部撤回，第 6 节所列文件当前不存在于仓库中。
> 来源实现：`D:\code\dongfeng-sanitation-operations-system\device-ai-camera`（只读引用、复制移植，**不修改**该仓库任何文件）。

## 1. 现状分析

| 项 | 现状 |
| --- | --- |
| VLStream「视频汇聚 → 设备管理 → ISUP协议」 | 页面 `VLStream-ui/src/views/isup/lsupDevice/index.vue`，接口经 `wvpRequest` 调用独立 WVP 后端 `/isup/lsupDevice/**`；VLS 后端不含 ISUP SDK |
| WVP ISUP 端口（默认） | CMS 7660、SMS 7665、SS 7661、AMS 7663，密钥 `isup.isupKey` |
| 东风 `device-ai-camera` | Spring Boot 3.3 / Java 17 独立进程；ISUP5.0 CMS 注册 + AMS 报警（MQTT）+ SS 图片 + SMS 取流（PS→RTP over TCP→ZLM）；ISAPI/城管 JSON/人脸/基本报警 → 统一 AI 事件；有界队列 + 重试 + 熔断 + spool；默认端口 7660/7663/7662/6011/7665，HTTP 8095 |
| VLS 后端 | Spring Boot 2.7 / **JDK 8**、Sa-Token、SpringBlade `R`；ISUP 菜单权限 `vls:menu:isup:isupDevice` |
| 文档测试摄像头 | `GL7186749`（城管事件）、`GM4815205`（枪机，萤石/ISUP 二选一）；当前经 natfrp（`frp-shy.com:12197` 等）注册在东风测试网关 192.168.0.222 |

结论：两套 ISUP 实现默认端口冲突，且原生 SDK 回调为进程级全局状态，**不能**塞进 WVP 或 VLS 现有进程，也不能复用同一组端口。

## 2. 目标与约束

1. 在 VLStream 现有「ISUP协议」模块中新增“AI 摄像头（ISUP 网关）”能力：设备在线列表、接收 AI 事件（含抓拍图、目标框、置信度）、播放实时视频。
2. 能用文档中的测试摄像头完成 AI 事件接收与视频播放验证。
3. **不影响现有系统**：WVP ISUP 设备的注册、播放、报警接收不变；VLS 原有设备管理/视频广场/AI 事件链路不变；东风系统的摄像头播放与事件接收不变（见第 7 节测试方式）。
4. **不修改** `dongfeng-sanitation-operations-system` 的代码。

## 3. 总体架构

```
测试摄像头 ──ISUP5.0──▶ vls-isup-ai-gateway（新增独立进程，Java17，复制移植自 device-ai-camera）
   CMS 17660 / AMS 17663(+UDP 17662) / SS 16011 / SMS 17665 / HTTP 8096
        │  HikAlarmConverter → AiEventDispatcher（队列/重试/熔断/spool）
        ├─▶ StoreAiEventSink → LocalEventStore（data/events/*.jsonl + images/，按天保留）
        │                    └─(可选) HttpAiEventSink 转发外部系统
        └─▶ HikIsupLive → PsRtpPusher ──RTP/TCP──▶ ZLMediaKit（独立实例，或 openRtpServer 专用端口）
                                                        │ HTTP-FLV
VLStream-ui「ISUP协议」页签「AI 摄像头」──▶ VLS 后端 /vls/isup-ai/**（Sa-Token + vls:menu:isup:isupDevice）
                                       └──X-AI-Gateway-Token──▶ 网关 /ai-camera/**
浏览器 ──FLV──▶ ZLM（网关返回的播放地址）
```

### 3.1 隔离设计（“不影响现有系统”的落实点）

| 维度 | 措施 |
| --- | --- |
| 进程 | 新网关独立部署、独立 JVM；SDK 崩溃不影响 VLS/WVP |
| 端口 | 默认 17660/17663/17662/16011/17665/8096，与 WVP（7660/7661/7663/7665）、东风网关（7660/7663/7662/6011/7665/8095）全部错开；启动前端口占用即熔断 DOWN，不会抢占 |
| 流媒体 | `zlm-rtp-mode`：`SINGLE_PORT`（推荐配合**独立 ZLM** 实例）或 `OPEN_RTP_SERVER`（共享 WVP 的 ZLM 时，经 `openRtpServer` 为每台设备分配专用端口，流 ID 带前缀 `isupai_`，不与 WVP 国标单端口 SSRC 流冲突） |
| 数据 | 事件与图片存放在网关自身数据目录，不写 VLS / WVP 数据库，不新增 Flyway 脚本 |
| VLS 后端 | 仅新增 3 个类与一段默认关闭的配置（`VLSTREAM_ISUP_AI_ENABLED=false`），不改原有类 |
| 前端 | 原 ISUP 页面组件不改；新增外层页签容器，默认仍显示原页面，AI 页签懒加载 |
| 权限 | 复用 ISUP 菜单权限，不新增菜单；网关管理接口需令牌，未配置令牌时只允许本机访问 |
| 停流 | 关闭播放窗口不停流（避免影响其他观看者），由网关空闲回收（默认 300s）或手动“停止推流” |

## 4. 任务分解

| ID | 任务 | 产出 | 验收标准 |
| --- | --- | --- | --- |
| T1 | 现状调研：VLS ISUP 前端、WVP ISUP、东风网关、测试摄像头文档 | 本文第 1 节 | 端口/协议/权限/部署边界明确 |
| T2 | 新建独立模块 `vls-isup-ai-gateway`（复制移植东风网关全部源码与单测，包名 `com.ruoyi.vlstream.isupai`） | pom、源码、单测 | `mvn -B -ntp package` 通过，原有单测全部保留 |
| T3 | 端口与配置隔离（`VLS_ISUPAI_*` 环境变量，默认错开端口） | `application.yml` | 与 WVP/东风同机启动无端口冲突 |
| T4 | 本地事件存储：`LocalEventStore` + `StoreAiEventSink`（按 eventId 去重、图片落盘、按天保留、可选转发） | 2 个类 + 单测 | 重复事件不重复入库；图片可按 eventId 读取；重启后最近事件可查询 |
| T5 | 网关接口安全：`GatewayTokenFilter`（令牌或仅本机） | 1 个类 + 单测 | 无令牌外部请求 401 |
| T6 | 事件查询与停流接口：`GET /ai-camera/events`、`/events/{id}`、`/events/{id}/image`、`POST /devices/{id}/live/stop` | 控制器 | 分页/按设备/按类型/按来源过滤可用 |
| T7 | 取流隔离：`ZlmRtpServerClient`，`SINGLE_PORT` / `OPEN_RTP_SERVER` 两种模式；返回 FLV/WS-FLV/HLS 地址 | HikIsupLive 改造 | 共享 ZLM 时流 ID 为 `isupai_<设备ID>`，停流时关闭 RTP 端口 |
| T8 | VLS 后端代理：`IsupAiGatewayProperties`、`IsupAiGatewayClient`、`IsupAiCameraController`（`/vls/isup-ai/**`） | 3 个类 + 配置 | 未启用返回明确提示；登录 + ISUP 菜单权限校验 |
| T9 | 前端：`views/isup/index.vue`（页签容器）、`views/isup/aiCamera/index.vue`、`api/isup/aiCamera.js`、路由切换 | 3 个文件 + 路由 1 行 | 原 ISUP 页功能不变；AI 页签可看状态、设备、播放、事件与抓拍图 |
| T10 | 部署与联调文档 | 本文第 5、7 节 + 网关 README | 按文档可完成部署、测试、回滚 |
| T11 | 构建验证：网关 `mvn package`、VLS `mvn -pl ruoyi-admin -am -DskipTests package`、前端 `npm run build` | 构建结果 | 三端构建通过（需在开发机执行） |
| T12 | 实机联调：测试摄像头注册 → 触发事件 → 列表出现事件与抓拍图 → 播放实时视频 | 联调记录 | 见第 7 节验收清单 |
| T13 | 回归：WVP ISUP 设备列表/播放/云台、VLS 视频广场、东风驾驶舱播放与 AI 工单 | 回归记录 | 均无变化 |

## 5. 部署

### 5.1 网关

```powershell
cd D:\code\VLStream-Cloud\VLStream-Cloud-Backend-Server\vls-isup-ai-gateway
mvn -B -ntp package
# SDK 目录不能含中文；可复制 HCISUPSDKV2.5.1.35 Win64 / Linux64 的 lib 目录（含 HCAapSDKCom）
$env:VLS_ISUPAI_LIB_DIR="D:\isup-lib"
$env:VLS_ISUPAI_KEY="<与摄像头 ISUP 登录密钥一致>"
$env:VLS_ISUPAI_PUBLIC_HOST="<摄像头可达的地址/穿透域名>"
$env:VLS_ISUPAI_GATEWAY_TOKEN="<随机长令牌，与 VLS 配置一致>"
$env:VLS_ISUPAI_ZLM_RTP_HOST="<ZLM 地址>"; $env:VLS_ISUPAI_ZLM_PLAY_BASE="http://<浏览器可访问的 ZLM>:<http端口>"
java -jar target\vls-isup-ai-gateway.jar
```

共享 WVP 的 ZLM 时追加：`VLS_ISUPAI_ZLM_RTP_MODE=OPEN_RTP_SERVER`、`VLS_ISUPAI_ZLM_API_BASE=http://<zlm>:<http端口>`、`VLS_ISUPAI_ZLM_SECRET=<ZLM api.secret>`，ZLM `rtp_proxy.port_range` 需对网关可达。

### 5.2 VLS 后端

```ini
VLSTREAM_ISUP_AI_ENABLED=true
VLSTREAM_ISUP_AI_INTERNAL_URL=http://<网关地址>:8096
VLSTREAM_ISUP_AI_TOKEN=<同 VLS_ISUPAI_GATEWAY_TOKEN>
```

### 5.3 前端

无需新增代理：接口走 VLS 原有基址（开发 `/vls` 已代理到后端）。播放地址由网关 `VLS_ISUPAI_ZLM_PLAY_BASE` 决定；HTTPS 页面需经同源 Nginx 代理 ZLM（如 `location /bus/isup-ai-zlm/ { proxy_pass http://<zlm>/; proxy_buffering off; }`，并把 PLAY_BASE 设为 `/bus/isup-ai-zlm`）。

## 6. 代码清单

| 位置 | 文件 |
| --- | --- |
| 新模块 | `VLStream-Cloud-Backend-Server/vls-isup-ai-gateway/**`（独立 Maven 工程，不加入 `vls-stream` 父工程，避免 JDK 版本冲突） |
| VLS 后端（新增） | `ruoyi-vlstream/src/main/java/com/ruoyi/vlstream/test/vlstream/isupai/{IsupAiGatewayProperties,IsupAiGatewayClient,IsupAiCameraController}.java` |
| VLS 后端（追加配置） | `ruoyi-admin/src/main/resources/application.yml` 的 `vlstream.isup-ai`；`ENVIRONMENT_VARIABLES.md` |
| 前端（新增） | `src/api/isup/aiCamera.js`、`src/views/isup/index.vue`、`src/views/isup/aiCamera/index.vue` |
| 前端（修改 1 行） | `src/router/index.js`：`/isup/isupDevice` 组件改为页签容器 |

## 7. 测试摄像头联调与“不影响东风系统”

`GL7186749`、`GM4815205` 目前注册在东风网关。海康设备通常只配置一个 ISUP 平台，改地址会让东风网关掉线。按影响从小到大三选一：

1. **双平台（零影响，优先）**：在 222 上经东风网关只读透传查询能力：
   `{"method":"GET","path":"/ISAPI/System/Network/Ehome/capabilities"}`（以及 `GET /ISAPI/System/Network/Ehome`）。若设备支持第二个 ISUP 中心，把第二中心指向 VLStream 网关（新端口、可用独立密钥），东风配置不动。
2. **时间窗切换**：先 `GET /ISAPI/System/Network/Ehome` 备份原配置 → `PUT` 指向 VLStream 网关 → 完成验收 → `PUT` 原配置恢复；窗口内东风侧该设备离线，需与业务方约定时间。
3. **另备一台**同型号摄像头专供 VLStream 测试。

若在 222 同机部署网关：新端口需单独建 natfrp 隧道（17660/17663/16011/17665），**不要**复用东风的 4 条隧道。

### 验收清单

- [ ] `GET /vls/isup-ai/status`：`HIK_ISUP.state=UP`，监听端口为新端口
- [ ] 设备注册后 AI 页签设备列表出现测试设备
- [ ] 现场触发城管/人车事件 → 10 秒内事件列表出现记录，抓拍图可预览，`targets[].bbox` 与置信度存在
- [ ] 点击“播放”出现实时画面（H.265 用 Jessibuca）；关闭窗口不影响其他观看者
- [ ] `VLS_ISUPAI_MOCK_ENABLED=true` 时“模拟事件”可走通存储链路（验收后关闭）
- [ ] 回归：WVP ISUP 设备列表/播放/云台正常；东风驾驶舱播放与 AI 工单正常（采用方式 1 或 3 时全程无影响）

## 8. 风险与回滚

| 风险 | 应对 |
| --- | --- |
| 共享 ZLM 时 WVP Hook 处理未知 rtp 流 | 优先独立 ZLM；或 `OPEN_RTP_SERVER` 模式并验证 WVP 对 `isupai_*` 流无副作用 |
| Linux SDK OpenSSL 崩溃风险（WVP 文档已提示） | 独立进程隔离；先在 Windows 验证，再按东风已验证的 2.5.1.35 Linux64 包部署 |
| ISUP 密钥/地址错误 | 网关 DOWN 并给出原因，`POST /ai-camera/adapters/HIK_ISUP/restart` 恢复 |
| 回滚 | 停网关进程；`VLSTREAM_ISUP_AI_ENABLED=false`；前端路由组件改回 `lsupDevice/index.vue`；无数据库变更 |
