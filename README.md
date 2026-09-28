# 多租户资金账本与对账重放（本地可运行）

一个基于 Spring Boot 的本地单进程模拟服务：用**追加式操作日志 + 本地 JSON 文件**作为唯一事实来源，
账户与流水为重放派生状态。目标是在**并发请求、重复提交、服务重启重放、异常补偿**四类场景下，
账务结论保持一致、可解释、可审计。

金额单位统一为「分」（`long`），避免浮点误差。

---

## 1. 数据模型与事实来源

| 对象 | 说明 | 是否持久化 |
| --- | --- | --- |
| `OperationRecord` | 追加式操作日志：一次业务请求一条记录，含 `seq`、`requestId`、类型、状态、角色、载荷、`prevHash/hash` | **是（唯一事实）** |
| `Account` | 账户：租户、初始余额、当前余额、是否关闭 | 否，启动重放重建 |
| `LedgerEntry` | 流水：方向、金额、幂等键、异常/已补偿/补偿标记、关联流水 | 否，启动重放重建 |

操作类型：`OPEN_ACCOUNT / ENTRY / MARK_ANOMALY / COMPENSATE`；状态：`APPLIED / REJECTED`。
**被拒绝的请求同样写日志**（`REJECTED + failureReason`），所以"为什么没入账"也可审计。

存储文件由 `ledger.storage-file` 指定（默认 `build/ledger-state.json`），
写入采用「临时文件 + 原子 move」，进程中断不会留下半截文件。

---

## 2. 账务一致性规则

1. **单写锁原子临界区**：所有变更在 `LedgerRepository.mutate` 的同一把写锁内完成
   「校验 → 追加日志（分配 seq 与哈希链）→ 改派生余额/流水 → 同步落盘」，
   成功与业务异常都会落盘（异常路径保证拒绝审计记录不丢）。
2. **幂等键 `X-Request-Id`**：同一 `requestId` 的成功操作全局只生效一次。
   并发同键请求只有一个线程入账，其余以 `DUPLICATE_REQUEST` 拒绝；
   补偿类请求同键重试直接返回原补偿流水（不二次冲销）。
3. **租户隔离**：`TENANT_OPERATOR` 的身份租户必须与请求体租户一致，且账户必须属于该租户，
   否则 `TENANT_MISMATCH`；查询接口同样按租户过滤。
4. **余额约束**：`DEBIT` 不得透支（`INSUFFICIENT_FUNDS`）；金额必须为正（`INVALID_AMOUNT`）；
   账户不存在 / 已关闭分别为 `UNKNOWN_ACCOUNT / ACCOUNT_CLOSED`。
5. **可区分失败原因**（HTTP 422，body 中 `reason`）：
   `INVALID_OPERATION, INVALID_AMOUNT, UNKNOWN_ACCOUNT, ACCOUNT_CLOSED, TENANT_MISMATCH,
   INSUFFICIENT_FUNDS, DUPLICATE_REQUEST, PERMISSION_DENIED, ANOMALY_TARGET_MISSING,
   ENTRY_NOT_ANOMALY, ENTRY_ALREADY_COMPENSATED, LEDGER_UNHEALTHY`。
6. **哈希链**：`hash_n = SHA-256(prevHash | seq | requestId | type | status | role | 载荷)`。
   仓库统一分配 seq 与 hash，业务层无法伪造链路。

---

## 3. 重放语义（重启）

服务启动时 `ReplayService` 自动从日志重建账户与流水（`POST /api/ledger/replay` 可手动触发），
实时入账与重放走**同一套派生效果代码**，因此最终余额必然一致。检测结论输出为 `ReplayReport`：

| 情形 | 识别与处理 |
| --- | --- |
| 正常连续日志 | 全部 `APPLIED` 记录重放，`healthy=true`，最终余额不变 |
| 物理重复（同 seq 同 hash 的副本） | 在链校验前剔除并计数 `skippedDuplicates`，安全跳过，不二次扣款 |
| 同 requestId 重复（不同 seq） | 只生效第一条，其余 `DUPLICATE_SKIPPED` |
| 乱序（物理顺序与 seq 不一致） | 按 seq 升序排序后重放，报告 `outOfOrder`，结论不变 |
| 缺号 / 中断（seq 不连续，且非物理重复） | `gapDetected=true`，**账本只读保护**，拒绝一切变更（`LEDGER_UNHEALTHY`） |
| 篡改（金额等字段被改，hash 对不上） | `tampered=true` + 定位 `tamperedSeq`，同样只读保护 |

只读保护是**安全默认**：无法确定被删/被改记录的资金影响时，绝不猜测入账。

---

## 4. 对账与补偿语义

- 对账（`GET /api/ledger/reconcile`）按账户比较：
  `账面余额` 与 `应有干净余额 = 初始余额 + Σ干净流水（异常流水与补偿流水都不计入）`。
  - 异常**已标记未补偿** → 账面仍含异常资金 → `matched=false`，并列出待补偿异常；
  - 补偿完成 → 反向冲销把账面拉回干净余额 → `matched=true`。
- 补偿（`POST /entries/{id}/compensate`）生成一条**反向等额冲销流水**（`CREDIT↔DEBIT`），
  关联原流水；原流水置 `compensated`。
- **守恒**：异常流水与其补偿流水成对地不参与干净余额计算，补偿后反复对账结论恒定；
  余额恒等式 `余额 = 初始 + Σ干净流水` 始终成立。

---

## 5. 权限边界

以请求头模拟鉴权：

- `X-Role: TENANT_OPERATOR`：开户、提交资金变动、查询**本租户**数据；必须携带 `X-Tenant-Id`。
- `X-Role: COMPENSATION_ADMIN`：标记异常、发起补偿、手动重放、全量对账；无租户归属限制。

越权操作返回 `PERMISSION_DENIED` 且同样写入 `REJECTED` 审计日志。
补偿必须先有「标记异常」，且每条异常**只能补偿一次**（`ENTRY_ALREADY_COMPENSATED`）。

---

## 6. 本地验证方法

### 6.1 运行测试（覆盖四类场景）

```bash
mvn test
```

| 测试类 | 覆盖点 |
| --- | --- |
| `ConcurrencyIdempotencyTests` | 20 线程同一 requestId 只入账一次；串行重复；余额不足/租户不匹配/未知账户/非法金额原因区分 |
| `RestartReplayTests` | 重启重放余额不变；物理重复 + 乱序安全处理；缺号与篡改识别并锁账本 |
| `PermissionReconciliationTests` | 租户操作员越权拒绝并留审计；对账不匹配→一次性补偿→守恒；补偿重复提交幂等 |

测试使用 JUnit 直接在临时文件上装配服务（不启动 Web 容器），
日志中会打印 `requestId`、处理结果（APPLIED/REJECTED(原因)）与关键判定依据。

### 6.2 本地启动并手工验证

```bash
mvn spring-boot:run          # 或 mvn package 后 java -jar target/*.jar
```

```bash
B=http://localhost:8080/api/ledger

# 开户 + 扣款
curl -s -X POST $B/accounts -H 'X-Role: TENANT_OPERATOR' -H 'X-Tenant-Id: t1' \
  -H 'X-Request-Id: open-1' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","tenantId":"t1","openingBalance":10000}'
curl -s -X POST $B/entries -H 'X-Role: TENANT_OPERATOR' -H 'X-Tenant-Id: t1' \
  -H 'X-Request-Id: pay-1' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","tenantId":"t1","direction":"DEBIT","amount":3000}'

# 重复提交（422 DUPLICATE_REQUEST）
curl -i -X POST $B/entries -H 'X-Role: TENANT_OPERATOR' -H 'X-Tenant-Id: t1' \
  -H 'X-Request-Id: pay-1' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","tenantId":"t1","direction":"DEBIT","amount":3000}'

# 管理员标记异常 → 对账不匹配 → 补偿 → 对账匹配
curl -s -X POST $B/entries/E-2/anomaly    -H 'X-Role: COMPENSATION_ADMIN' -H 'X-Request-Id: m1'
curl -s $B/reconcile                       -H 'X-Role: COMPENSATION_ADMIN'
curl -s -X POST $B/entries/E-2/compensate -H 'X-Role: COMPENSATION_ADMIN' -H 'X-Request-Id: c1'
curl -s $B/reconcile                       -H 'X-Role: COMPENSATION_ADMIN'

# 重启进程后再次查询：余额与对账结论不变（启动日志含重放报告）
curl -s $B/accounts/a1 -H 'X-Role: COMPENSATION_ADMIN'
curl -s $B/health       -H 'X-Role: COMPENSATION_ADMIN'
```

### 6.3 接口一览

| 方法 | 路径 | 角色 |
| --- | --- | --- |
| POST | `/api/ledger/accounts` | 租户操作员 |
| GET  | `/api/ledger/accounts/{id}` | 本租户操作员 / 管理员 |
| GET  | `/api/ledger/accounts/{id}/entries` | 本租户操作员 / 管理员 |
| POST | `/api/ledger/entries` | 租户操作员 |
| POST | `/api/ledger/entries/{id}/anomaly` | 管理员 |
| POST | `/api/ledger/entries/{id}/compensate` | 管理员 |
| GET  | `/api/ledger/reconcile` | 管理员 |
| POST | `/api/ledger/replay` | 管理员 |
| GET  | `/api/ledger/health` | 管理员 |

> 说明：这是教学/演示级单机模拟，写锁为单进程内锁，持久化为单个本地 JSON 文件；
> 多实例部署时应替换为分布式锁与共享日志存储，但幂等键、哈希链、重放与补偿的语义规则保持不变。
