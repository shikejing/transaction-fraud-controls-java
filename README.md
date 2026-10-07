# Transaction Fraud Controls

面向交易的实时欺诈识别与处置能力，整条链路只在本进程内运行：输入通过本地回环
REST（或测试中的内存直调）进入，不依赖任何外部服务，判定结论可在本地复现与验证。
仓库原有的启动（`mvn spring-boot:run`）与测试（`mvn test`）方式不变。

## 架构

```
TransactionController / AdminController   (本地回环 REST 入口)
        |
FraudDecisionEngine                       (编排：去重 -> 名单 -> 评分 -> 额度 -> 落账)
        |
  +-- IdempotencyStore   重复提交/重试归并
  +-- ListRegistry       拦截/观察名单（原子快照、来源优先级、过期时间）
  +-- RuleRegistry       版本化规则集（不可变历史版本）
  +-- RiskScorer         确定性加权评分
  +-- VelocityLimiter    账号/设备额度与频率（分条锁，原子 tryAcquire）
  +-- ReviewService      复核与申诉（追加式，不改写原判定）
  +-- AuditLog           审计轨迹（按交易/账号追溯）
  +-- JournalStore       本地文件日志（重启恢复）
```

## 判定语义

每笔交易依次经过：

1. **归并**：同一 `transactionId` 的重复提交与网络重试归并为同一次判定。
   并发首提交中只有一个线程真正执行判定，其余等待并拿到同一结果；
   重复提交不会重复累计额度，也不会重复触发拦截动作。
2. **名单**：命中拦截名单（BLOCK）直接拦截；命中观察名单（WATCH）进入复核。
   名单按 `keyType + keyValue` 匹配账号/设备/收款方；同一键多条目时
   **来源优先级高者胜**；`expiresAtEpochMs` 过期即失效。名单更新通过
   copy-on-write 原子快照生效，任一单次读取看到的都是同一代名单。
3. **评分**：命中分数 `score >= blockThreshold` 拦截；`>= reviewThreshold` 复核。
4. **额度**：仅当判定为放行时，对账号与设备两个维度做滑动窗口
   额度/频率检查；超限则转为复核。检查与记账是同一临界区内的原子操作，
   并发下不会额度穿透、不会重复扣减。

三类结果都带可解释依据（`reasons`）：命中名单的来源与优先级、或分数与
当时阈值/规则版本的对比、或超限的维度。

## 评分口径

- 评分 = 各命中规则权重之和，规则形式为 `signal >= threshold -> +weight`。
- **确定性**：相同信号 + 相同规则版本必得逐项一致的结果（`RiskScorer` 为纯函数）。
- **可追溯**：每个 `RuleHit` 记录规则 id、规则版本、观测值、阈值、权重、
  是否命中与分数贡献。
- **缺失 vs 零**：信号键不存在 = 缺失（`signalPresent=false`，规则不触发，
  观测值记为 null）；信号值为 0.0 = 观测到零，正常参与比较。两者在
  判定轨迹中明确区分。
- 交易金额始终以内置信号 `amountCents` 暴露给规则。

## 规则版本化

- 交易在**受理时**绑定当时生效的规则版本，版本号记入判定结果。
- 已发布版本不可变；同版本号重复发布会被拒绝。
- 规则调整只影响之后受理的交易，历史判定可按当时版本通过
  `GET /api/admin/rules/{version}` 与判定记录中的 `ruleVersion` 追溯。
- 新旧版本并存期间：在途交易沿用受理时版本，新交易用新版本，互不干扰。

## 复核与申诉

- 只有 `REVIEWER`（或 `ADMIN`）角色能复核判定或解除拦截，越权返回
  403 及可区分原因（`ACCESS_DENIED` + 缺失角色）。
- 复核/申诉结果以 `ReviewRecord` **追加**，原始 `DecisionRecord` 永不改写；
  交易当前有效处置 = 原始判定 + 最后一次复核/申诉结果。
- 误拦解除路径：申诉（`POST /api/admin/appeals`）-> 复核人核实 ->
  `POST /api/admin/appeals/resolve`（release=true）-> 有效处置变为 ALLOW，
  全程留痕。同一交易的复核/申诉/新判定在 per-transaction 锁下串行，
  并发结果确定。

## 权限与审计

- 角色：`ADMIN`（隐含全部）、`RULE_MANAGER`（发规则）、`LIST_MANAGER`
  （管名单）、`REVIEWER`（复核/解除）。
- 每次判定、规则发布、名单变更、复核、申诉、解除都记录
  `AuditEvent{类型, 操作人, 交易, 账号, 依据, 时间}`，可按交易或账号查询。

## 重启恢复

- 判定、复核、额度记账、审计事件以 JSON Lines 追加到本地日志
  （`fraud.journal-dir`），启动时由 `RecoveryRunner` 重放。
- 判定先落日志再对外可见：崩溃时未落日志的判定不会与已提交结果混淆，
  重提交时重新判定；已落日志的判定重启后归并不再执行，拦截动作不重复。
- 额度记账先于判定落日志，恢复后额度/频率计数只会偏多不会穿透。

## API 一览（本地回环）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/transactions` | 提交交易并返回判定（重复提交归并，`duplicate=true`） |
| GET | `/api/transactions/{id}` | 查询判定结果 |
| POST | `/api/admin/rules` | 发布新规则版本（需 `RULE_MANAGER`） |
| GET | `/api/admin/rules/current`、`/api/admin/rules/{version}` | 查询规则版本 |
| POST | `/api/admin/lists`、`/api/admin/lists/remove` | 名单增删（需 `LIST_MANAGER`） |
| GET | `/api/admin/lists` | 名单快照 |
| POST | `/api/admin/reviews` | 复核复核态交易（需 `REVIEWER`） |
| POST | `/api/admin/appeals`、`/api/admin/appeals/resolve` | 申诉与解除（解除需 `REVIEWER`） |
| GET | `/api/admin/audit/transactions/{id}`、`/api/admin/audit/accounts/{id}` | 审计追溯 |

管理接口通过请求头 `X-Operator-Id` 与 `X-Operator-Roles`（逗号分隔）标识操作人。

## 配置项（`fraud.*`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `fraud.journal-dir` | 空 | 恢复日志目录；为空则纯内存模式（不持久化） |
| `fraud.account-max-amount-cents-per-window` | 1000000 | 账号窗口额度（分） |
| `fraud.account-max-count-per-window` | 20 | 账号窗口最大笔数 |
| `fraud.account-window-millis` | 60000 | 账号滑动窗口 |
| `fraud.device-max-amount-cents-per-window` | 2000000 | 设备窗口额度（分） |
| `fraud.device-max-count-per-window` | 40 | 设备窗口最大笔数 |
| `fraud.device-window-millis` | 60000 | 设备滑动窗口 |
| `fraud.duplicate-wait-millis` | 5000 | 等待在途重复判定的超时 |

## 测试

`mvn test` 覆盖：重复与并发交易归并、并发下额度穿透防护、评分确定性与
缺失/零信号区分、规则版本切换与历史追溯、名单优先级/过期/原子变更、
误拦申诉解除与越权拒绝、重启恢复。
