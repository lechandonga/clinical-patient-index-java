# 临床数据接入与患者主索引服务（本地版）

纯本地运行的 Spring Boot 服务：接收多来源病历消息，完成归一、身份归并（EMPI），
并对外提供去标识化查询、基于令牌的权限控制与审计。**不依赖任何外部服务或数据库**，
所有状态保存在内存中，`mvn test` 即可完整验证。

## 1. 支持的消息格式与版本

| 格式 | 形态 | 支持范围 | 识别方式 |
|---|---|---|---|
| HL7 v2 | 管道分隔 ER7 文本 | 版本 2.x（如 2.5.1），事件 A01/A04/A08/A31 | 首段为 `MSH|` 或 content-type 含 `hl7` |
| FHIR | JSON `Patient` 资源 | R4 结构（R5 的 Patient 基础身份字段兼容） | JSON 且 `resourceType=Patient` |

接口：`POST /api/patients/ingest`（`text/plain` 放 HL7，`application/json` 放 FHIR）。

**接入预检（新增）**：`POST /api/patients/precheck` 使用与正式接入完全相同的消息体、
凭证（`patient:ingest`）、解析与归并判定，但**完全只读**——不新建/合并主索引、
不改标识归属与人口学数据，同一条消息重复预检结果一致（即使该消息正式提交会被拒绝
或触发冲突）。返回是否可接受（`acceptable`）、归一后字段（`normalizedFields`）、
结果类型（`CREATE_NEW` / `UPDATE_EXISTING` / `MERGE_INTO_EXISTING` / `CONFLICT_REVIEW`
/ `REJECTED`）、命中的已有患者稳定替代标识（`targetPseudonym`，不会出现 MPI ID 或
原始标识符）、各字段取舍（`fieldDecisions`：`KEEP_EXISTING` 保留 /
`FILL_FROM_INCOMING` 补全）以及标识符级动作（只给哈希）。拒绝时返回的
`rejectionReason` 与正式接入是同一套原因码。预检未授权同样按
`MISSING_TOKEN`/`INVALID_TOKEN`/`FORBIDDEN_SCOPE` 区分并留痕；预检本身写审计，
审计里只有替代标识、标识符哈希与输入摘要，不含姓名、原始标识符、出生日期明文。

**容错与拒绝规则（不静默丢数据）**

- 段乱序容忍：MSH 必须是首个非空行且仅出现一次，其余段（PID 等）允许乱序、重复。
- 字段缺失：姓名（HL7 PID-5 / FHIR `name`）、至少一个标识符（PID-3 / `identifier`）、
  出生日期（PID-7 / `birthDate`）为必填，缺失即拒绝。
- 时间异常：支持 HL7 `yyyyMMdd` 与 FHIR `yyyy-MM-dd`；部分日期按可解释规则补全
  （`yyyy` → `yyyy-01-01`，`yyyy-MM` → `yyyy-MM-01`，并在响应 `normalizationNotes` 中说明）；
  非法日历日（如 `19901340`）拒绝。
- 无法识别的性别归一为 `UNKNOWN` 并在 notes 中说明，不丢弃记录。
- 失败原因通过响应 `reason` 明确区分，HTTP 状态码对应：
  - `MALFORMED_MESSAGE`（400）：段结构/JSON 语法/UTF-8 非法
  - `UNSUPPORTED_FORMAT`（415）：非 2.x、不支持的事件、非 Patient 资源
  - `MISSING_REQUIRED_FIELD`（422）：必填缺失
  - `ILLEGAL_IDENTIFIER`（422）：标识符为空或含非法字符
  - `INVALID_DATE`（400）：日期无法解析或补全
  - `CONTRADICTORY_DATA`（409）：同一标识符解析到人口学冲突的不同簇
  - `REVIEW_QUEUE_FULL`（409）：本应转人工复核的冲突撞上待办上限（见第 8 节）

**冲突提交的对外兼容性**：触发冲突的正式提交，HTTP 状态码与响应体仍与以前一致
（409 + `CONTRADICTORY_DATA`），老接入方无需任何改动；唯一区别是服务端会额外生成
一条人工复核待办（待办满时原因码变为明确的 `REVIEW_QUEUE_FULL`，不静默丢弃）。
冲突被拒绝期间两个主索引始终各自独立，标识归属与人口学数据零变化。

## 2. 标识符归一与身份归并规则

**统一标识符表示（canonical key）**：`系统|值`，其中

- 系统：去空白、转大写（`hospital_a` = `HOSPITAL_A`；FHIR 取 system URI 最后一段）；
- 值：去首尾空白、去所有内部空白、转大写，并剥离常见前缀
  （大小写不敏感的 `MRN-`/`MRN:`/`PAT#`/`ID-` 等）。

因此 `HOSPITAL_A / " mrn1001 "` 与 `hospital_a / "MRN-1001"` 指向同一标识符。

**归并（union-find 语义）**

- 任一 canonical key 命中已有主索引即视为同一患者；一次提交携带多个标识符会互相链接。
- 归并**幂等**：重复提交返回同一 MPI ID，不产生重复索引、不覆盖已有数据。
- **安全护栏**：两个簇经共享标识符链接时，若出生日期冲突（或生日相同但姓氏冲突），
  返回 `CONTRADICTORY_DATA` 拒绝合并，避免不同患者被误并。
- 人口学采用“已有值优先、空值补全”策略，冲突的出生日期不会覆盖已有值（basis 中说明）。
- 归并全程由单一可重入锁保护：并发接入/重复提交不会出现重复 MPI、更新丢失或半合并状态。

## 3. 去标识化规则

查询接口只返回去标识化视图：

```json
{
  "pseudonym": "PSEU-90ffa8ec86ee",
  "gender": "FEMALE",
  "birthYear": 1988,
  "linkedIdentifierCount": 2,
  "linkedSourceSystemCount": 2
}
```

- 不返回姓名、证件号、联系方式、原始标识符值；生日仅降维到出生年份。
- 替代标识 = `HMAC-SHA256(密钥, mpiId)` 截断十六进制（`PSEU-` 前缀）。
  同一患者在任意次、任意来源查询中的替代标识**稳定一致**；无密钥无法反推。
- 密钥通过 `mpi.pseudonym-secret` / 环境变量 `MPI_PSEUDONYM_SECRET` 配置。

## 4. 权限模型

HTTP `Authorization: Bearer <token>`，三种 scope：

| scope | 接口 |
|---|---|
| `patient:ingest` | `POST /api/patients/ingest` |
| `patient:ingest` | `POST /api/patients/precheck`（预检，同样只读鉴权） |
| `patient:query` | `GET /api/patients`, `GET /api/patients/{mpiId}` |
| `patient:review` | `GET /api/reviews/tickets`，`POST /api/reviews/tickets/{id}/decision` |
| `audit:read` | `GET /api/audit/events` |

失败可区分：未携带凭证 → 401 `MISSING_TOKEN`；令牌无效 → 401 `INVALID_TOKEN`；
已认证但缺 scope → 403 `FORBIDDEN_SCOPE`。

内置本地验证令牌：

| 令牌 | 调用方 | scope |
|---|---|---|
| `ingest-token-a` / `ingest-token-b` | source-a / source-b | ingest |
| `query-token` | analyst | query |
| `admin-token` | admin | query + audit:read |
| `reviewer-token` | reviewer | patient:review（查看待办、做裁决） |

可用 `mpi.tokens`（环境变量 `MPI_TOKENS`）追加，格式 `token=user,scope1,scope2;...`。

## 5. 审计

每次接入/查询（含拒绝与未命中）均写审计：时间、调用方、动作、目标 MPI、替代标识、
**标识符 SHA-256 截断哈希**（非明文）、结果、判定依据。审计记录不含姓名、证件号等明文
敏感字段；merge basis 中的标识符在写入前也会替换为哈希。仅 `audit:read` 可读取。

## 6. 本地验证方法

前置：JDK 21+、Maven。

```bash
# 运行全部单测/集成测（畸形输入、重复与并发合并、去标识化一致性、越权访问）
mvn test

# 启动服务（随机端口见日志，或设 SERVER_PORT=8080）
mvn spring-boot:run
```

手动联调：

```bash
# HL7 v2 接入
curl -s -X POST http://localhost:8080/api/patients/ingest \
  -H "Authorization: Bearer ingest-token-a" -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG100|P|2.5.1\nPID|1||MRN-7001^^^HOSPITAL_A^MR||Zhao^Min||19880320|F'

# FHIR 接入（同一患者的另一来源标识）
curl -s -X POST http://localhost:8080/api/patients/ingest \
  -H "Authorization: Bearer ingest-token-b" -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","identifier":[{"system":"HOSPITAL_A","value":"MRN7001"},{"system":"HOSPITAL_B","value":"7002"}],"name":[{"family":"Zhao","given":["Min"]}],"gender":"female","birthDate":"1988-03-20"}'

# 去标识化查询（两次查询的 pseudonym 一致）
curl -s "http://localhost:8080/api/patients?system=hospital_a&identifier=mrn-7001" \
  -H "Authorization: Bearer query-token"

# 审计（仅 admin）
curl -s http://localhost:8080/api/audit/events -H "Authorization: Bearer admin-token"
```

## 7. 测试覆盖

- `Hl7V2MessageParserTest`：合法消息归一、段乱序、版本/事件不支持、必填缺失、
  非法标识符、非法日期、性别补全（原因逐一区分，日志打印输入摘要与判定依据）。
- `FhirJsonMessageParserTest`：部分日期补全、JSON 畸形、资源类型错误、
  标识符缺失/非法、日期非法。
- `MasterPatientIndexTest`：大小写/前缀/空白差异幂等归并、不同患者不误并、
  人口学冲突拒绝、32 线程并发重复提交仅产生 1 个 MPI。
- `EndToEndApiTest`：HL7+FHIR 跨源合并、去标识化一致性（无姓名/原始标识）、
  稳定替代标识、MISSING_TOKEN/INVALID_TOKEN/FORBIDDEN_SCOPE、审计无明文。

## 8. 冲突人工复核与裁决（新增）

触发归并护栏的正式提交对外仍返回 409 `CONTRADICTORY_DATA`（老接入方零改动），
同时服务端登记一条待办；未裁决前两个主索引始终各自独立，查询各自落回各自索引，
归属与人口学不发生任何变化。

**去重与上限**

- 同一对已有患者（顺序无关）+ 同一冲突类别（`BIRTH_DATE` / `FAMILY_NAME`）在
  PENDING 期间反复提交只保留一条待办，`occurrenceCount` 累加，不重复堆积。
- 待办数量上限可配置：`mpi.review.max-pending`（环境变量 `MPI_REVIEW_MAX_PENDING`，
  默认 100）。满后新冲突仍照常拒绝，但原因码明确为 `REVIEW_QUEUE_FULL`（仍为 409），
  绝不静默丢弃；裁决出空槽后恢复。

**待办内容（全部去标识化）**：`ticketId`、两个涉及患者的稳定替代标识、冲突类别、
首次提交者、提交时间、状态、重复提交次数；无姓名、无原始标识符、无出生日期明文。

**裁决**（仅 `patient:review` scope；未带/无效/权限不足凭证分别返回
`MISSING_TOKEN`(401)/`INVALID_TOKEN`(401)/`FORBIDDEN_SCOPE`(403)，且失败也留痕、不含明文）

- `GET /api/reviews/tickets`：列出 PENDING 待办。
- `POST /api/reviews/tickets/{ticketId}/decision`，body `{"decision":"APPROVE"}`
  或 `{"decision":"REJECT"}`。
- **批准合并**：在主索引同一把归并锁内一次性原子完成；重复或并发的相同裁决只生效
  一次，后续调用返回同一结果。合并后两边的标识符查询都落到同一主索引，替代标识保持
  稳定（沿用 survivor 的 pseudonym）。字段取舍规则确定且记录在待办的 `fieldDecisions`
  中：**survivor 已有值一律优先保留（KEEP_EXISTING），absorbed 一侧仅用于补全 survivor
  原本为空的字段（FILL_FROM_INCOMING）**，逐字段标注字段名/动作/取值来源/依据。
- **驳回**：不触碰主索引，两个标识仍各自属于原来的主索引，人口学原样不变。
- 裁决与新接入/重复提交交叉时共用归并锁，不会出现重复主索引、覆盖或半合并状态。

## 9. 新增配置项汇总

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `mpi.review.max-pending` | `MPI_REVIEW_MAX_PENDING` | `100` | PENDING 复核待办上限 |
| `mpi.tokens` | `MPI_TOKENS` | 内置令牌 | 追加令牌，格式 `token=user,scope1,scope2;...`，复核 scope 为 `patient:review` |

## 10. 新流程本地验证

```bash
# 全部自动化测试（含预检只读、待办去重/上限、裁决幂等与并发、驳回归属不变、
# 裁决×接入交叉一致性、越权区分、新流程留痕无明文）
mvn test

# 以很小的待办上限启动，便于手工验证 REVIEW_QUEUE_FULL
MPI_REVIEW_MAX_PENDING=2 mvn spring-boot:run
```

```bash
# 1) 预检（不写入）：可接受 / 拒绝 / 冲突三种消息各试一次，重复预检结果一致
curl -s -X POST http://localhost:8080/api/patients/precheck \
  -H "Authorization: Bearer ingest-token-a" -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG100|P|2.5.1\nPID|1||MRN-7001^^^HOSPITAL_A^MR||Zhao^Min||19880320|F'

# 2) 正式接入两条不同患者，再提交同时引用两边标识符的冲突消息 -> 409 但生成待办
# 3) 复核员查看待办 / 批准 / 驳回（重复调用返回相同结果）
curl -s http://localhost:8080/api/reviews/tickets -H "Authorization: Bearer reviewer-token"
curl -s -X POST http://localhost:8080/api/reviews/tickets/REV-0000000001/decision \
  -H "Authorization: Bearer reviewer-token" -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE"}'

# 4) admin 查看审计：预检、冲突、裁决与越权失败均留痕且无明文
curl -s http://localhost:8080/api/audit/events -H "Authorization: Bearer admin-token"
```

新增测试：

- `PlanAndReviewIndexTest`：预检对新建/更新/会拒绝消息的纯只读性与可重复性、
  强制合并的字段取舍规则、裁决与并发接入交叉时无重复主索引/半合并状态。
- `ReviewQueueTest`：同对患者同类别去重、待办满返回 `REVIEW_QUEUE_FULL`、
  并发裁决仅生效一次且结果一致。
- `PrecheckAndReviewApiTest`：HL7/FHIR 预检只读与同套拒绝原因、冲突提交对外表现
  不变但生成去重待办、未决期间两索引独立、批准后标识汇聚且 pseudonym 稳定、
  驳回后归属不变、待办满原因可区分、预检/复核三类越权可区分、新流程审计无明文。
