# transaction-fraud-controls-java

面向交易的实时欺诈识别与处置能力。整条链路在单进程内运行：交易经本地回环 HTTP
（或测试中的内存直调）进入判定引擎，结合账号、设备、收款方与历史行为信号给出
**放行（ALLOW）/ 复核（REVIEW）/ 拦截（BLOCK）** 三类判定，全部状态落在本地追加式
日志文件中，进程重启后重放恢复，不依赖任何外部服务。

## 运行与测试

```bash
mvn test          # 全部单元/集成测试（19 个）
mvn spring-boot:run
```

启动方式与原有工程一致（`Application` + `server.port=0` 随机端口），新增能力不破坏
既有启动与测试方式。

## 判定语义

一次判定的生命周期：

1. **受理（accept）**：校验操作人角色（`SUBMITTER`），绑定**当时生效的规则版本**，
   先把原始交易写入 `transactions.log`，再写入 `PENDING` 状态的判定记录
   （`decisions.log`）。此后任何崩溃都留有可恢复的受理痕迹。
2. **判定（decide）**：按固定顺序合成结论——
   - 命中**拦截名单**（账号/设备/收款方任一）→ `BLOCK`，不消耗额度；
   - **额度/频率预留**失败（账号日累计超限或设备窗口频率超限）→ `BLOCK`；
   - 评分 ≥ `blockThreshold` → `BLOCK`；
   - 命中**观察名单**或评分 ≥ `reviewThreshold` → `REVIEW`（保留额度占用等待处置）；
   - 否则 → `ALLOW`（额度正式计入）。
   判定完成写入 `COMPLETED` 记录并审计留痕。
3. **归并（dedup）**：同一 `transactionId` 的重复提交/网络重试经分条锁串行化，
   命中已完成的判定直接原样返回（`duplicate=true`），不重复累计额度、不重复
   触发拦截动作；并发重复提交下全集群只有一个提交真正驱动判定。

三类结果都带可解释依据：`DecisionRecord.reasons` 给出人类可读原因，
`DecisionRecord.hits` 给出逐条规则命中明细。

## 评分口径

- 评分器是纯函数：相同信号 + 相同规则版本 ⇒ 总分与逐项命中明细完全一致。
- 每条规则 `(signal, threshold, weight, missingSignalScore)`：观测值 ≥ 阈值时贡献
  `weight` 分，否则 0 分。
- **信号缺失（键不存在）≠ 信号为零**：缺失按 `missingSignalScore` 独立计分，
  为零是正常观测值参与阈值比较。`RuleHit.signalMissing` 与
  `RuleHit.observedValue=null` 可区分两种情形。
- 每次判定都可追溯：`ruleVersion` + 每条 `RuleHit`（规则 id、观测值、阈值、权重、
  得分贡献、说明）。
- 信号源（`SignalEnricher`）以 `fraud.signal-timeout-ms` 超时隔离调用；超时/失败的
  信号按缺失处理，不阻塞当前交易，也不拖垮其他交易的判定。

## 规则版本化

- 规则集不可变，按版本号发布（`POST /api/fraud/admin/rules`），当前版本原子切换。
- 交易在**受理时**绑定版本；规则调整不回溯改写已受理交易与历史判定。
- 新旧版本并存期间：已受理（含中断待恢复）的交易按绑定版本判定/重驱，新交易按
  当前版本判定；历史结论可按版本号查询（`GET /api/fraud/admin/rules/{version}`）。

## 名单与分级处置

- 名单条目 `(key, kind, source, priority, expiresAt)`：`BLOCK` 直接拦截，`WATCH`
  进入复核；同一键多条目按**来源优先级**（数值大者优先）裁决；过期条目自动失效。
- 名单是**不可变快照**：一次判定只读一个快照，变更整体构建新快照后原子替换，
  任一时刻对同一交易读到的名单状态不会半新半旧。

## 并发一致性

- 同一 `transactionId` 经分条锁串行；账号与设备额度用分条锁按“先账号后设备”
  固定顺序加锁，避免死锁。
- 额度预留按 `transactionId` 幂等：重复提交、并发争抢、中断重放都不会重复扣减；
  并发下同账号交易收敛到唯一结果，额度不穿透（测试：10 并发 × 20 万对 100 万
  日限额，恰好 5 笔放行，账上恰好 100 万）。

## 复核与申诉

- 处置（`Disposition`）只**追加**不改写：原始 `DecisionRecord` 永久保留，
  当前生效结论 = 原始判定 + 最新处置的合成视图。
- 角色要求：`CONFIRM`/`ESCALATE_TO_BLOCK` 需 `REVIEWER`，`RELEASE_BLOCK` 需
  `DISPOSITION_ADMIN`。
- 误拦解除路径：`RELEASE_BLOCK` 将生效结论置为 ALLOW 并尝试补占额度，补占结果
  写入处置依据；重复解除/重复升级按锁串行后以状态冲突拒绝，结果确定。

## 权限与审计

- 角色：`SUBMITTER`（提交交易）、`REVIEWER`（复核）、`RULE_ADMIN`（规则/名单/
  授权）、`DISPOSITION_ADMIN`（解除拦截）。
- 越权抛出 `AccessDeniedException`，原因码可区分：`UNKNOWN_OPERATOR`（未注册
  主体）/ `MISSING_ROLE`（缺角色）；HTTP 层映射为 403。
- 每次判定、规则发布、名单变更、处置、授权都写审计事件（操作人、依据、时间、
  单调序列号），可按交易或账号追溯（`GET /api/fraud/audit/by-transaction/{id}`、
  `GET /api/fraud/audit/by-account/{id}`）。

## 持久化与恢复

- 全部状态变更先写本地追加日志（`fraud.data-dir` 下的
  `transactions/decisions/limits/lists/rules/dispositions/audit.log`）再改内存，
  崩溃产生的残行在重放时被忽略。
- 重启时重放恢复：已判定交易不丢失、拦截动作不重复执行（重复提交命中已完成
  判定直接归并）；`PENDING` 记录与已提交结果可区分，恢复时用受理日志中的原始
  交易按**受理时绑定的规则版本**重驱为 `COMPLETED`（审计事件
  `decision.recovered`）；额度预留幂等重放，计数不错乱。

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `fraud.data-dir` | `build/fraud-data` | 本地持久化目录 |
| `fraud.signal-timeout-ms` | `200` | 单信号源超时，超时按缺失处理 |
| `server.port` | `0` | 随机端口（沿用原有配置） |

内置初始规则版本 `v1`（`FraudConfiguration.bootstrapRuleSet()`）：4 条信号规则
（金额/均值比、设备关联账号数、近一小时频率、收款方风险分），复核线 50、拦截线
100、账号日限额 1,000,000 分、设备窗口 60s/5 笔。

## API 一览

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/fraud/transactions` | SUBMITTER | 受理并判定（幂等） |
| GET | `/api/fraud/transactions/{id}` | - | 查询判定记录 |
| GET | `/api/fraud/transactions/by-account/{accountId}` | - | 按账号查询 |
| POST | `/api/fraud/admin/rules` | RULE_ADMIN | 发布规则版本 |
| GET | `/api/fraud/admin/rules[/{version}]` | - | 版本查询 |
| POST | `/api/fraud/admin/lists` | RULE_ADMIN | 原子变更名单 |
| GET | `/api/fraud/admin/lists` | - | 名单快照 |
| POST | `/api/fraud/admin/grants` | RULE_ADMIN | 授权 |
| POST | `/api/fraud/review/{txId}` | REVIEWER / DISPOSITION_ADMIN | 复核/申诉处置 |
| GET | `/api/fraud/review/{txId}` | - | 原始判定 + 生效结论 + 处置记录 |
| GET | `/api/fraud/audit/by-transaction/{id}` | - | 审计追溯 |
| GET | `/api/fraud/audit/by-account/{id}` | - | 审计追溯 |

操作人通过 `X-Operator` 请求头标识。

## 测试覆盖

`src/test/java/.../tests/fraud/`：

- `ScoringDeterminismTest`：评分确定性与缺失/零区分
- `IdempotencyConcurrencyTest`：重复提交归并、并发归并、额度穿透防护
- `RuleVersionTest`：版本绑定、历史不改写、新旧并存
- `WatchlistTest`：分级处置、原子变更、来源优先级、过期
- `AppealAndAuthzTest`：越权拒绝原因码、误拦解除、复核不改写原判定
- `RecoveryTest`：重启后判定/名单/规则/处置恢复、中断重驱、额度不重扣
- `SignalIsolationTest`：慢信号源超时隔离
- `FraudApiTest`：本地回环 HTTP 端到端
