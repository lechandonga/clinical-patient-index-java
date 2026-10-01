# 临床数据接入与患者主索引服务（本地 EMPI）

一个完全本地运行、无外部服务依赖的 Spring Boot 服务：接收多来源病历数据，
在进程内完成**解析归一 → 身份归并 → 去标识化查询**，并带 API Key 权限模型与审计。

- Java 21（源码级别）/ Spring Boot 3.4.x，数据保存在进程内存，重启即清空。
- 不依赖数据库、消息队列或任何远程服务。

## 1. 支持的消息格式与版本

| 格式 | 范围 | 说明 |
| --- | --- | --- |
| HL7 v2（管道分隔） | 版本 2.1–2.8；仅 ADT^A01/A04/A08/A28/A31/A40 | 编码字符取 MSH-2；支持 PID-3 重复标识符（`~`）、组件分隔符 `^`；分段允许乱序（MSH 必须为首段），按段名而非位置定位；MSH/PID 重复出现直接拒绝 |
| FHIR 风格 JSON | FHIR R4（`resourceType=Patient`） | 读取 `identifier(system/value/type.coding.code)`、`name(family/given)`、`birthDate`、`gender`、`telecom(system=phone)` |

### 1.1 异常处理：绝不静默丢弃

任何无法处理的输入都会得到**结构化错误响应**（`errorCode` 可区分），并写一条 `DENIED` 审计：

| 错误码 | 含义 |
| --- | --- |
| `FORMAT_ERROR` | 语法/结构无法解析（坏 JSON、非 MSH 开头、段重复、未知 ADT 事件、资源类型不是 Patient、性别代码无法解释等） |
| `UNSUPPORTED_VERSION` | HL7 版本不在 2.1–2.8，或 FHIR 声明版本不是 R4 |
| `MISSING_REQUIRED_FIELD` | 缺少必填：至少一个有效标识符、姓名（姓或名）、出生日期；FHIR 标识符必须带 `system` |
| `IDENTIFIER_INVALID` | 标识符/来源系统归一后为空、超长或结构非法 |
| `TIME_FORMAT_ERROR` | 日期无法解释或为非法历法日期（如 1984-02-30）、出生日期晚于今天 |
| `PATIENT_NOT_FOUND` | 查询未命中 |
| `UNAUTHENTICATED` / `FORBIDDEN_ROLE` / `FORBIDDEN_SCOPE` | 见权限模型 |

### 1.2 显式补全规则

- 时间：接受 HL7 紧凑格式 `yyyyMMdd[HHmmss][.ffff][±ZZZZ]` 与 ISO `yyyy-MM-dd[...]`，统一补全为 `yyyy-MM-dd`；不猜测二义日期。
- 性别：HL7 `M/F/U` 与 FHIR `male/female/unknown/other` 归一为 `M/F/U/UNK`；缺失补 `UNK`，无法解释则拒绝。
- 标识符系统缺失时（HL7 PID-3 无 assigning authority）回退到 MSH-3 发送应用，再回退 `UNKNOWN`。

## 2. 标识符归一与身份归并规则

### 2.1 标识符归一（`IdentifierNormalizer`）

1. 去首尾空白、UTF-8 BOM，转大写；
2. **系统名**：空白压缩为 `_`、大写，再按 `cpi.identifier.system-aliases` 做别名归并（默认 `EMR→HIS`、`HOSPITAL_A→HIS`、`LAB→LIS`）；
3. **标识符值**：按配置剥离前缀（默认 `MRN`、`MR`）/后缀，删除所有非字母数字分隔符（`-`、空格、`_` 等），去除前导零（全零保留 `0`）；
4. 结果为空或超过长度上限（默认 64）按 `IDENTIFIER_INVALID` 拒绝；
5. 同一记录内归一后重复的标识符自动去重。

因此 `HIS / MRN-00012345`、`his / mrn 00012345`、`EMR / MRN00012345` 都落到同一统一键 `HIS|12345`。

### 2.2 主索引归并（`MasterPatientIndex`）

- **唯一合并依据**：两条记录共享至少一个「规范化系统 + 规范化标识符」键。姓名、生日等人口学信息**只用于补全与冲突告警，绝不单独触发合并**——同名同生日但标识符不同的两个人不会被误合并。
- 传递归并：`A~共享键` 与 `B~共享键` 会收敛为同一主索引；被吸收记录的标识符全部重指到存活记录。
- **幂等**：重复提交（无论大小写/前后缀变体）返回同一 `masterId`，不新建索引。
- **并发安全**：全部「查找-归并-提交」在同一把 `ReentrantLock` 内原子完成；并发重复提交或并发桥接不会出现重复主索引、覆盖丢失或半合并状态。
- 字段补全只填充空值，不覆盖已有非空值；人口学冲突（如姓氏、生日不一致）写入 `warnings` 可解释返回。

## 3. 去标识化规则

- `masterId`/伪名 = `HMAC-SHA256(secret, 规范化标识符键)` 的前 32 位十六进制；**同一患者在任意输出中稳定一致**，密钥更换后整体轮换，且无法由伪名反推明文。
- 查询接口 `GET /api/patients/find` 只返回：患者伪名、**出生年份**（完整生日泛化）、归一性别、各标识符的伪名引用、来源系统列表。
- 姓名、证件号、联系方式、原始标识符永不出现在查询响应或审计记录中。
- 日志/审计中的输入关联使用 SHA-256 截断摘要（前 16 位），为非可逆指纹。
- 密钥通过 `cpi.deidentification.secret`（环境变量 `CPI_PSEUDONYM_SECRET`）配置。

## 4. 权限模型

所有 `/api/**` 接口都要求请求头 `X-Api-Key`。

| 角色 | 能力 |
| --- | --- |
| `INGEST` | 提交病历（`POST /api/ingest/**`） |
| `QUERY` | 去标识化查询（`GET /api/patients/find`） |
| `AUDIT_READ` | 读取审计（`GET /api/audit/events`） |

- 无 Key / Key 无效 → 401 `UNAUTHENTICATED`；
- 有身份但缺角色 → 403 `FORBIDDEN_ROLE`；
- 每个 Key 可配置**来源系统白名单**：提交或查询不在授权范围内的系统 → 403 `FORBIDDEN_SCOPE`。

本地默认 Key（`application.properties`，仅限开发）：

| Key | 角色 | 系统范围 |
| --- | --- | --- |
| `ingest-his-key` | INGEST | HIS |
| `ingest-lab-key` | INGEST | LIS |
| `query-key` | QUERY | 不限 |
| `audit-key` | AUDIT_READ | 不限 |
| `admin-key` | 全部 | 不限 |

审计事件含：时间、调用方、动作、路径、**患者伪名**、ALLOWED/DENIED、判定原因、输入摘要；
拒绝访问（未认证/越权）同样留痕。审计通道为内存环形缓冲（默认 10000 条）+ SLF4J 日志双通道。

## 5. 本地验证方法

需要 JDK 17+（仓库在 JDK 21/25 上验证通过）与 Maven。

```bash
mvn test                      # 17 个测试：畸形输入/重复与并发合并/去标识化一致性/越权/审计无明文
mvn spring-boot:run           # 启动在 http://localhost:8080
```

手工冒烟：

```bash
# HL7 v2 接入
curl -X POST http://localhost:8080/api/ingest/hl7 \
  -H "X-Api-Key: ingest-his-key" -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|HIS_APP|HIS|||20261001120000||ADT^A04|m|P|2.5.1\rPID|1||MRN-00012345^^^HIS^MR||张^三||19840307|M||||||13800000000'

# FHIR R4 接入（EMR 是 HIS 的别名，会归并到同一主索引）
curl -X POST http://localhost:8080/api/ingest/fhir \
  -H "X-Api-Key: admin-key" -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","identifier":[{"system":"EMR","value":"mrn00012345"}],"name":[{"family":"张","given":["三"]}],"gender":"male","birthDate":"1984-03-07"}'

# 去标识化查询（大小写、前缀、分隔符变体都命中同一患者）
curl "http://localhost:8080/api/patients/find?system=his&identifier=MR%2000012345" -H "X-Api-Key: query-key"

# 审计
curl "http://localhost:8080/api/audit/events" -H "X-Api-Key: audit-key"
```

测试清单（`src/test/.../tests`，测试日志会打印输入摘要与判定依据）：

- `NormalizerTest`：标识符/时间归一规则与可区分失败原因；
- `ParserTest`：HL7/FHIR 等价输入归一、乱序段、坏 JSON、缺字段、非法日期、版本/事件不支持；
- `MasterPatientIndexTest`：幂等合并、防误合并、32 线程并发重复提交、并发传递链归并；
- `ApiEndToEndTest`：五类错误码、跨格式同一主索引、伪名稳定与直接标识零泄漏、401/403 场景、审计无敏感明文。

## 6. 主要代码结构

```
ingest/        解析编排、错误码、标识符/时间归一
ingest/hl7     HL7 v2 ADT 解析器
ingest/fhir    FHIR R4 Patient 解析器
mpi/           并发安全主索引（归并/传递吸收/幂等）
deid/          HMAC 伪名与 SHA-256 摘要
security/      API Key 认证、角色、系统范围、拦截器
audit/         审计事件模型与双通道记录
query/         去标识化查询服务
web/           REST 控制器与全局错误处理
config/        可配置项与 MVC 拦截器注册
```

## 7. 局限与生产化提示

当前为**单进程内存实现**，适合本地验证与教学演示。生产化需要替换：持久化存储（带唯一约束的标识符映射表）、
分布式锁/事务或串行化写入、密钥放入 KMS/HSM、审计落不可变存储，并显式复核归并建议（人审）。
