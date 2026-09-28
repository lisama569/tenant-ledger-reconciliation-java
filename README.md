# 多租户资金账本与对账重放（本地可运行）

一个基于 Spring Boot 3 / Java 21 的本地服务，用 **内存态 + append-only 操作日志文件** 模拟
账户、流水与操作记录，目标是让以下四类场景的账务结论保持一致且可解释：

1. 并发请求 / 重复提交同一笔业务不得重复入账；
2. 服务重启后重放历史记录不得改变最终余额；
3. 重复、乱序、中断（写坏）的日志记录必须被明确识别或安全处理；
4. 异常流水的对账与补偿受权限约束、只能生效一次，补偿后账务保持守恒。

金额一律使用 `long`（最小货币单位，如“分”），杜绝浮点误差。

---

## 1. 快速开始

```bash
# 构建并运行测试（推荐先验证）
mvn test

# 启动服务（随机端口，日志目录 ledger-data/）
mvn spring-boot:run

# 指定日志目录
mvn spring-boot:run -Dspring-boot.run.arguments="--ledger.data-dir=/tmp/my-ledger"
```

启动时会自动重放 `operations.log`，日志中可见：

```
ledger started dataDir=... applied=N rejected=M anomalies=true/false
startup replay warning: ...（重复键 / 乱序 / 缺口 / 损坏 / 中断尾部 的明细）
```

---

## 2. HTTP 接口

所有写接口请求体均需携带：`tenantId`、`role`、`requestId`、`idempotencyKey`。

| 方法 | 路径 | 说明 | 最低角色 |
|---|---|---|---|
| POST | `/api/accounts` | 开户（`initialBalance` 可选） | USER |
| POST | `/api/entries` | 资金流水（`entryType=DEBIT/CREDIT`，`amount>0`） | USER |
| POST | `/api/exceptions` | 把一笔流水标记为异常（隔离，**不动余额**） | AUDITOR |
| POST | `/api/compensations` | 补偿（单边余额调整） | **ADMIN** |
| GET  | `/api/tenants/{t}/accounts/{a}` | 查账户 | - |
| GET  | `/api/tenants/{t}/accounts/{a}/entries` | 查流水 | - |
| GET  | `/api/tenants/{t}/reconciliation` | 对账报告 | - |
| GET  | `/api/replay-report` | 本次启动重放报告 | - |

命令类接口的业务失败返回 **HTTP 200**，响应体中以 `result=REJECTED` + 可区分的
`failureCode` 表达（这样“首次被拒绝”与其后的幂等重放拥有完全相同的结论）；
查询类失败返回 4xx。

### 示例

```bash
curl -s -X POST localhost:$PORT/api/accounts -H 'Content-Type: application/json' -d '{
  "tenantId":"t1","role":"USER","requestId":"req-1","idempotencyKey":"biz-1",
  "accountId":"a1","initialBalance":1000}'

curl -s -X POST localhost:$PORT/api/entries -H 'Content-Type: application/json' -d '{
  "tenantId":"t1","role":"USER","requestId":"req-2","idempotencyKey":"biz-2",
  "accountId":"a1","entryId":"e1","entryType":"DEBIT","amount":300}'
# => balance 700；重复提交 biz-2（即使并发、即使换 requestId）永远返回首次结论
```

### 失败码 `failureCode`

| 失败码 | 含义 |
|---|---|
| `INVALID_REQUEST` | 参数缺失/非法：金额非正、流水类型错误、补偿方向不匹配等 |
| `TENANT_MISMATCH` | 账户/流水存在，但属于**其他租户**（跨租户访问被拒） |
| `ACCOUNT_NOT_FOUND` / `ENTRY_NOT_FOUND` | 本租户内不存在 |
| `ACCOUNT_ALREADY_EXISTS` | 本租户内账户已存在 |
| `INSUFFICIENT_FUNDS` | 借方/补偿会导致余额为负 |
| `ENTRY_ALREADY_EXCEPTIONAL` | 流水已被隔离，不能重复标记 |
| `PERMISSION_DENIED` | 角色无权执行该操作 / 角色无法识别 |
| `COMPENSATION_ALREADY_APPLIED` | 该异常/漂移事件已经补偿过一次 |

> 注意区分两种“重复”：
> - **同一 `idempotencyKey` 重放**：返回首次结论（成功或失败），不新增任何影响；
> - **不同 key 但 `entryId` 相同**：属于业务方编号冲突，明确 `INVALID_REQUEST` 拒绝，
>   而不是悄悄复用。

---

## 3. 账务一致性规则（守恒不变式）

对任意租户的任意账户，恒有：

```
account.balance == Σ entry.signedDelta  （仅 state = NORMAL 的流水，含开户 OPENING 流水）
```

- 开户初始余额以一笔系统贷方流水 `OPENING:<accountId>` 入账，使守恒式自开户起即成立；
- `DEBIT` 为负、`CREDIT` 为正；
- 流水被标记为 `EXCEPTIONAL` 后 **只从求和中隔离，不直接改余额**，
  因此隔离后对账会立即暴露出 `difference = 被隔离流水的 signedDelta`（差异可解释，不静默）；
- 对账报告逐账户给出 `balance / normalEntrySum / difference / matched`，并附带
  `exceptionalEntryIds`（标注 `open` 或 `compensated:<event>`）与 `compensatedEventRefs`。

### 执行管道（在线请求）

```
全局串行化 → 幂等重放判定 → 权限校验 → 业务校验 → 操作日志先落盘 → 内存态变更 → 幂等登记
```

- 所有写命令在单把操作锁内串行：并发提交同一 `idempotencyKey` 时，只有首个请求真正校验/落盘/生效，
  其余请求拿到首条操作记录（相同 `seq`、相同结果），**绝不重复扣款/入账**；
- **日志先落盘，后改内存**：若进程在落盘之后、内存提交之前崩溃，重启重放会补齐该笔，且只补一次；
- `REJECTED` 操作同样落盘（带失败码），既参与幂等去重，也让“为什么没入账”可追溯；
- 所有查询都以 `tenantId` 限定；若资源存在于其他租户，返回 `TENANT_MISMATCH` 而非“不存在”。

---

## 4. 重放语义（重启 / 乱序 / 重复 / 中断）

操作日志为 append-only，每条逻辑记录占两行：`OperationRecord JSON` + 该行的 `CRC32`。
`seq` 在落盘时于日志锁内连续分配。重放规则：

| 现象 | 处理 |
|---|---|
| `APPLIED` 记录 | 按 `type` 重放账务效果（开户/入账/隔离/补偿） |
| `REJECTED` 记录 | 只登记幂等结论，**不产生任何账务影响** |
| `idempotencyKey` 重复 | 安全跳过，在报告 `duplicateKeys` 中列出 |
| `seq` 倒退/重复（乱序） | 安全跳过，列入 `outOfOrderSeqs`，不得按乱序生效 |
| `seq` 缺口 | 继续重放但列入 `gapWarnings`（可能意味着日志丢失，需人工介入） |
| JSON 不可解析 / CRC 不匹配 | 跳过该记录并列入 `corruptLines`，不让脏记录入账 |
| 末尾缺 CRC 行（写入中断/截断） | 安全丢弃尾部并置 `truncatedTail=true` |

因此重放**不会**静默漏记、重复扣款或跨租户串账：所有异常都在 `ReplayReport`
（`GET /api/replay-report` 或启动日志）中显式列出；正常情况下
`balance == Σ NORMAL 流水` 与重放前完全一致。

---

## 5. 异常对账与补偿语义

分两步，职责分离：

1. **`MARK_EXCEPTION`（AUDITOR/ADMIN）**：把错误流水隔离为 `EXCEPTIONAL`，记录 `eventRef`，
   不改余额。此时对账不守恒，差异即被隔离流水的金额。
2. **`COMPENSATE`（仅 ADMIN）**：按 `eventRef` 做一次**单边余额调整**，修复差异。
   - 关联异常流水时，强制 `compensationDelta == -entry.signedDelta`，
     修复后重新满足守恒式；流水被标记 `compensatedBy=<eventRef>`，差异闭环；
   - 不关联流水时（如故障注入/外部漂移导致的余额侧差异），允许只修余额，但不得造成负余额；
   - 补偿**不产生业务流水**：业务流水总和不变，避免“为了平账再造一笔假流水”。

**只能生效一次的双重保证**：

- 事件级：同一 `eventRef` 在 `compensatedEvents` 中只登记一次，
  即使换一个 `idempotencyKey` 重试，也返回 `COMPENSATION_ALREADY_APPLIED`；
- 请求级：同一 `idempotencyKey` 的重复提交直接返回首次结论。

重复对账、重复补偿处理都不会改变任何余额、流水与操作记录结论。

### 权限边界

| 角色 | 开户/流水 | 标记异常 | 补偿 |
|---|---|---|---|
| USER | ✅ | ❌ | ❌ |
| AUDITOR | ✅ | ✅ | ❌ |
| ADMIN | ✅ | ✅ | ✅ |

未识别/缺失角色一律 `PERMISSION_DENIED`。

---

## 6. 本地验证方法（测试）

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `LedgerConcurrencyTest` | 32 线程同一幂等键并发提交只入账一次；成功/失败结论的顺序重试幂等；余额不足、租户不匹配、非法操作、越权的可区分失败码 |
| `LedgerReplayTest` | 重启重放前后余额/流水一致（含 REJECTED）；“落盘后崩溃”重放补齐且不重复、随后重复提交仍幂等；重复键、乱序、缺口、CRC 损坏、中断尾部的识别与安全跳过 |
| `CompensationReconciliationTest` | 异常隔离 → 对账不匹配 → ADMIN 补偿恢复守恒；USER/AUDITOR 越权拒绝；错误补偿金额拒绝；事件级与请求级重复补偿均不二次生效；补偿后重启结论稳定；故障注入的单边漂移检测与一次性修复 |
| `WebApiTests` | 全链路 HTTP：幂等重放标记、权限拒绝、`TENANT_MISMATCH`、对账差异 `-200`、补偿后 `matched=true`、重复补偿余额不变 |

测试与服务日志（SLF4J）会打印**请求标识（requestId）、幂等键、处理结果（APPLIED/REJECTED）、
失败码与关键判定依据**，例如：

```
request handled requestId=r7 tenant=t1 role=ADMIN key=k-comp type=COMPENSATE seq=7 result=APPLIED ...
[补偿生效] requestId=r7 event=EVT-1 delta=+400 最终余额=1050 matched=true 判定依据=单边余额修复+异常事件闭环
request handled requestId=r8 ... result=REJECTED code=COMPENSATION_ALREADY_APPLIED ...
```

---

## 7. 代码结构

```
domain/      枚举、账户、流水、操作记录、对账报告、异常、角色、上下文
storage/     LocalLedgerStore：内存态 + append-only 日志 + CRC + 重放；ReplayReport；CompensationRecord
service/     LedgerService（命令管道：校验/权限/幂等/落盘）、ReconciliationService（只读对账）
config/      LedgerProperties（ledger.data-dir）、LedgerConfig（建库 + 启动重放）
web/         LedgerController、ApiExceptionHandler
```

### 已知边界（模拟实现）

- 单进程内用一把操作锁串行化所有写命令；`LocalLedgerStore` 本身也提供每账户锁原语，
  真实多实例部署需换用数据库唯一约束 / 事务。
- 日志文件不做压缩与归档；缺口（缺失序号）只告警不自动修复，需要人工核对外部事实。
