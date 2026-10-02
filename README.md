# 临床数据接入与患者主索引服务（本地版）

纯本地运行的 Spring Boot 服务：接收多来源病历消息，完成归一、身份归并（EMPI），
并对外提供去标识化查询、基于令牌的权限控制与审计。**不依赖任何外部服务或数据库**，
所有状态保存在内存中，`mvn test` 即可完整验证。

## 1. 支持的消息格式与版本

| 格式 | 形态 | 支持范围 | 识别方式 |
|---|---|---|---|
| HL7 v2 | 管道分隔 ER7 文本 | 版本 2.x（如 2.5.1），事件 A01/A04/A08/A31 | 首段为 `MSH|` 或 content-type 含 `hl7` |
| FHIR | JSON `Patient` 资源 | R4 结构（R5 的 Patient 基础身份字段兼容） | JSON 且 `resourceType=Patient` |

接口：

- `POST /api/patients/ingest`（`text/plain` 放 HL7，`application/json` 放 FHIR）：正式接入。
- `POST /api/patients/preview`：**接入预检**，消息格式、凭证要求与正式接入完全一致，
  但完全只读（见第 8 节）。

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
  - `REVIEW_QUEUE_FULL`（409）：检测到冲突，但人工复核待办队列已满（见第 9 节，
    与普通冲突明确区分，绝不静默丢弃）
  - `REVIEW_TASK_NOT_FOUND`（404）/`REVIEW_ALREADY_DECIDED`（409）/
    `INVALID_DECISION`（400）：复核裁决相关错误（见第 9 节）

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
| `patient:ingest` | `POST /api/patients/preview`（预检复用接入凭证） |
| `patient:query` | `GET /api/patients`, `GET /api/patients/{mpiId}` |
| `patient:review` | `GET /api/reviews`、`POST /api/reviews/{id}/decision` |
| `audit:read` | `GET /api/audit/events` |

失败可区分：未携带凭证 → 401 `MISSING_TOKEN`；令牌无效 → 401 `INVALID_TOKEN`；
已认证但缺 scope → 403 `FORBIDDEN_SCOPE`。

内置本地验证令牌：

| 令牌 | 调用方 | scope |
|---|---|---|
| `ingest-token-a` / `ingest-token-b` | source-a / source-b | ingest |
| `query-token` | analyst | query |
| `reviewer-token` | reviewer | review |
| `admin-token` | admin | query + audit:read + review |

可用 `mpi.tokens`（环境变量 `MPI_TOKENS`）追加，格式 `token=user,scope1,scope2;...`。
复核权限即 scope `patient:review`：可追加给任意自定义调用方，例如
`MPI_TOKENS='my-reviewer=zhang,patient:review'`。

## 5. 审计

每次接入/查询（含拒绝与未命中）均写审计：时间、调用方、动作、目标 MPI、替代标识、
**标识符 SHA-256 截断哈希**（非明文）、结果、判定依据。审计记录不含姓名、证件号等明文
敏感字段；merge basis 中的标识符在写入前也会替换为哈希；日志中的输入摘要只保留
姓名首字符与出生**年份**。仅 `audit:read` 可读取。预检与复核相关动作同样留痕
（`PREVIEW` / `INGEST_CONFLICT` / `REVIEW_LIST` / `REVIEW_DECIDE`，
含未带凭证、凭证无效、权限不足三类鉴权失败，匿名 actor 记为 `anonymous`），
全部不含明文敏感字段。

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

## 7. 接入预检（dry-run）

`POST /api/patients/preview`，请求体、Content-Type、Bearer 凭证与 `/ingest` 完全相同
（需要 `patient:ingest` scope；未带凭证/凭证无效/权限不足分别返回
`MISSING_TOKEN` / `INVALID_TOKEN` / `FORBIDDEN_SCOPE`，失败也留痕）。

预检与正式接入共用同一套 parser、归一规则与冲突护栏：

- 会被拒绝的消息得到**完全相同的原因码与 HTTP 状态**（含 `CONTRADICTORY_DATA`、
  队列满时的 `REVIEW_QUEUE_FULL`），只是不真正提交。
- 可接受时返回归一结果（性别、出生**年份**、标识符哈希、normalization notes）、
  逐字段效果（`KEPT` 已有值保留 / `FILLED` 空值补全，值来源标明为
  `existing` / `incoming` / `merged-cluster`）、已脱敏的 merge basis、
  `wouldCreate` 以及 `targetPseudonym`：
  - 命中已有患者时，`targetPseudonym` 就是该患者稳定的替代标识，
    响应中不出现 MPI id、姓名或原始标识符；
  - 会新建患者时 `wouldCreate=true`，`targetPseudonym` 为模拟中新患者将获得的
    替代标识（密钥不变时与真正接入后一致）。
- **完全只读**：在主索引的深拷贝快照上模拟，主索引记录数、标识归属、
  已有人口学数据均不变；即使消息会被拒绝或会触发冲突，也不创建主索引、
  不挂标识、**不登记复核待办**。同一条消息重复预检结果一致。

## 8. 冲突人工复核

**对外兼容性**：会触发冲突的提交，响应与旧行为完全一致——仍是
`409 CONTRADICTORY_DATA`，老接入方无需改动；唯一变化是服务端在同一把归并锁内
额外生成一条待复核记录。冲突检测发生在任何索引写入之前，因此拒绝对数据零副作用：
事后按原标识查询仍各自落在原来的主索引上，归属与人口学不变；未决期间查询看到的
始终是独立主索引，绝不提前合并。

待办记录（仅 `patient:review` scope 可访问）：

- 涉及的已有患者只以**替代标识**（`pseudonyms`）表示，内部 MPI id 不出现在
  响应 JSON 中；另含冲突类别（`BIRTH_DATE` / `FAMILY_NAME`）、触发标识与
  提交标识的 SHA-256 哈希、提交人、提交时间，以及**确定且可解释的字段取舍方案**
  （`fieldMergePlan`，只写规则与来源，不含值）。
- **去重**：同一对患者 + 同一冲突类别在未决期间只保留一条待办，反复提交只累加
  `submissionCount`。
- **上限**：`mpi.review.queue-limit`（环境变量 `MPI_REVIEW_QUEUE_LIMIT`，默认 100）。
  待办满后新冲突仍照常拒绝，但原因明确为 `REVIEW_QUEUE_FULL`（409），
  不登记待办、不静默丢弃。

| 方法与路径 | 说明 |
|---|---|
| `GET /api/reviews` | 列出未决待办 |
| `POST /api/reviews/{id}/decision` | body：`{"decision":"APPROVE"}` 或 `{"decision":"REJECT"}` |

裁决语义：

- **批准（APPROVE）**：在同一把归并锁内原子完成——多个独立簇以 **MPI id
  字典序最小者为幸存者**；幸存者非空字段保留，空字段按确定顺序由被并簇补全，
  再用原提交补仍为空的字段；冲突字段以幸存者/现有值为准；新标识挂接到幸存者。
  之后两边任意标识查询都落到同一主索引，**幸存者替代标识不变**。
- **驳回（REJECT）**：不触碰任何索引数据，标识归属与人口学原样保留。
- **幂等**：同一待办重复/并发同样裁决只生效一次，后续调用返回相同结果
  （`applied=false`）；对已决待办提交相反裁决返回 `409 REVIEW_ALREADY_DECIDED`。
- 裁决与新接入、重复提交在同一把归并锁下串行，不会出现重复主索引、覆盖或半合并。
- 原始归一消息仅存于进程内暂存区（不出现在响应/待办/审计），裁决后立即删除；
  本地内存版重启会丢失暂存，此时批准会安全转为驳回并留痕。

## 9. 测试覆盖

- `Hl7V2MessageParserTest`：合法消息归一、段乱序、版本/事件不支持、必填缺失、
  非法标识符、非法日期、性别补全（原因逐一区分，日志打印输入摘要与判定依据）。
- `FhirJsonMessageParserTest`：部分日期补全、JSON 畸形、资源类型错误、
  标识符缺失/非法、日期非法。
- `MasterPatientIndexTest`：大小写/前缀/空白差异幂等归并、不同患者不误并、
  人口学冲突拒绝、32 线程并发重复提交仅产生 1 个 MPI。
- `EndToEndApiTest`：HL7+FHIR 跨源合并、去标识化一致性（无姓名/原始标识）、
  稳定替代标识、MISSING_TOKEN/INVALID_TOKEN/FORBIDDEN_SCOPE、审计无明文。
- `PreviewServiceTest`：预检对新建/归并/冲突/队列满四类消息的判定与正式接入同源，
  重复预检结果一致，索引与标识归属前后完全一致（含会被拒绝和会冲突的消息），
  预检不登记待办。
- `ReviewWorkflowTest`：待办生成与内容、未决去重（submissionCount）、待办上限打满
  返回 REVIEW_QUEUE_FULL 且无副作用、16 线程并发批准只生效一次且返回一致、
  24 线程批准与重复冲突接入交叉时无重复/半合并、驳回后归属与人口学不变、
  单簇冲突批准只补空值不覆盖、确定的幸存者选择与字段取舍。
- `ReviewEndToEndApiTest`：预检/待办/裁决全链路 HTTP 行为，冲突对外仍 409
  CONTRADICTORY_DATA，越权三态区分，批准后两边标识归一，审计覆盖新流程且无明文。
- `AuditPrivacyTest`：穷举新流程各审计路径，扫描全部事件不含姓名、原始标识符
  与出生日期明文。

手动联调新增片段：

```bash
# 预检（与 ingest 同样的消息、同样的凭证）
curl -s -X POST http://localhost:8080/api/patients/preview \
  -H "Authorization: Bearer ingest-token-a" -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|HIS_A|HOSPITAL_A|MPI|MPI|20261001120000||ADT^A04^ADT_A01|MSG200|P|2.5.1\nPID|1||MRN-7001^^^HOSPITAL_A^MR||Zhao^Min||19880320|F'

# 复核员查看待办 / 批准 / 驳回
curl -s http://localhost:8080/api/reviews -H "Authorization: Bearer reviewer-token"
curl -s -X POST http://localhost:8080/api/reviews/REV-0000000001/decision \
  -H "Authorization: Bearer reviewer-token" -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE"}'

# 配置待办上限后重启验证 REVIEW_QUEUE_FULL
MPI_REVIEW_QUEUE_LIMIT=1 mvn spring-boot:run
```
