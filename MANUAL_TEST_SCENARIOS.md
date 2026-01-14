# RikkaHub vNext 智能召回系统 - 手动测试场景

## 测试环境准备

1. 安装 APK 到测试设备
2. 启用 Debug 日志（DebugLogger 设置为 INFO 级别）
3. 准备测试会话：至少包含 10 轮对话，涵盖诗歌、代码、讨论等内容
4. 确保设置中启用了"逐字召回"功能

---

## 测试场景 1：互斥性验证

**目标**：单轮对话最多生成一个 [RECALL_EVIDENCE] 块

**测试步骤**：
1. 创建新会话
2. 输入："请背诵《静夜思》"
3. 等待 AI 回复
4. 输入："再背一遍《望庐山瀑布》"
5. 等待 AI 回复
6. 检查 system prompt（通过日志或调试界面）

**预期结果**：
- 每轮的 system prompt 中最多包含一个 `[RECALL_EVIDENCE]...[/RECALL_EVIDENCE]` 块
- 不应该出现多个 `[RECALL_EVIDENCE]` 标记

**验收证据**：
- 日志截图显示单轮最多一个 `[RECALL_EVIDENCE]` 块

---

## 测试场景 2：NeedGate 验证

**目标**：needScore < 0.55 且非显式时不触发任何 DB 检索

**测试步骤**：
1. 创建新会话
2. 输入："你好"
3. 观察日志输出

**预期结果**：
- 日志显示：`NeedGate blocked (needScore=0.00 < T_NEED=0.55)`
- 日志中**不应该**出现任何数据库查询（`getByConversationIdAndIndices`, `getByConversationIdAndTitle` 等）
- 返回 null，不生成任何注入块

**验收证据**：
- 日志显示 "NeedGate blocked"
- 日志计数器显示 DB 查询次数 = 0

---

## 测试场景 3：冷却机制验证

**目标**：连续两轮相同查询，第二轮因冷却返回 NONE

**测试步骤**：
1. 创建新会话
2. 输入："请背诵《静夜思》"
3. 等待 AI 回复后，立即输入："再背一遍刚才那首诗"
4. 观察日志和回复

**预期结果**：
- 第 1 轮：生成 `[RECALL_EVIDENCE]` 块（如果找到诗歌内容）
- 第 2 轮：日志显示 "All candidates in cooldown" 或 "redundancyPenalty=1.0"
- 第 2 轮：不生成任何注入块（返回 null）

**验收证据**：
- 日志显示冷却生效（`cooldownUntilTurn` > 当前轮次）
- 第 2 轮无注入块

---

## 测试场景 4：显式逐字召回

**目标**：含"原文/全文/逐字/《...》"关键词时能从 P 源输出 FULL 或 SNIPPET

**测试步骤**：
1. 创建新会话
2. 输入："请背诵《静夜思》原文"
3. 等待 AI 回复
4. 检查注入块内容

**预期结果**：
- 日志显示：`explicit=true`
- 生成 `[RECALL_EVIDENCE]` 块
- `type=FULL` 或 `type=SNIPPET`
- `source=P_TEXT`
- 内容包含《静夜思》全文（或片段）

**验收证据**：
- 日志显示 `explicit=true`
- 注入块包含诗歌内容

---

## 测试场景 5：P 源候选生成（Title 匹配）

**目标**：Title 匹配策略能正确生成候选

**测试步骤**：
1. 在会话中先创建一个《静夜思》的逐字归档
2. 输入："刚才那首《静夜思》再解释一下"
3. 观察日志和注入块

**预期结果**：
- 日志显示：`Title matching` 或 `Generated P source candidates`
- 生成 `[RECALL_EVIDENCE]` 块
- 候选 ID 格式：`P:${conversationId}:SNIPPET:${nodeIndices}`

**验收证据**：
- 日志显示 Title 匹配成功
- 注入块内容来自正确的 node_indices

---

## 测试场景 6：P 源候选生成（FTS4 兜底）

**目标**：无 Title 时使用 FTS4 兜底检索

**测试步骤**：
1. 在会话中讨论"异步编程"话题
2. 输入："那个异步方案怎么实现？"
3. 观察日志

**预期结果**：
- 日志显示：`FTS4 fallback`
- 生成候选（如果找到匹配内容）
- 候选数 ≤ 3（MAX_PER_SOURCE）

**验收证据**：
- 日志显示 FTS4 检索执行
- 候选数量符合预算限制

---

## 测试场景 7：预算护栏验证

**目标**：SNIPPET ≤ 800 字符，FULL ≤ 6000 字符

**测试步骤**：
1. 在会话中创建一个长文本（> 1000 字符）的逐字归档
2. 输入："请给出原文"
3. 检查注入块内容长度

**预期结果**：
- 如果是 SNIPPET：内容长度 ≤ 800 字符
- 如果是 FULL：内容长度 ≤ 6000 字符
- 日志显示注入块的 `charCount`

**验收证据**：
- 日志中的 `charCount` 符合预算限制
- 内容长度验证

---

## 测试场景 8：失败退化验证

**目标**：数据库查询失败时返回 NONE，不崩溃

**测试步骤**：
1. 模拟数据库故障（例如关闭数据库连接或触发超时）
2. 输入："那个方案怎么样？"
3. 观察应用行为

**预期结果**：
- 应用**不崩溃**
- 返回 null（不生成注入块）
- 日志显示错误信息（WARN 或 ERROR 级别）
- AI 回复正常进行（只是没有召回证据）

**验收证据**：
- 应用稳定运行
- 日志显示异常处理

---

## 测试场景 9：评分可复现性验证

**目标**：相同输入产生相同评分

**测试步骤**：
1. 创建固定内容的测试会话
2. 输入相同查询两次（在冷却期外）
3. 对比两次日志中的评分

**预期结果**：
- `relevance`, `precision`, `novelty`, `recency`, `risk`, `finalScore` 完全相同
- 评分精度到小数点后 6 位一致

**验收证据**：
- 两次日志评分对比截图

---

## 测试场景 10：账本持久化验证

**目标**：ledger 正确序列化/反序列化

**测试步骤**：
1. 创建新会话
2. 触发一次召回（输入含回指词）
3. 检查数据库 `conversationentity` 表的 `recall_ledger_json` 字段
4. 重启应用
5. 再次触发召回

**预期结果**：
- 数据库中 `recall_ledger_json` 不为 `"{}"`
- JSON 格式正确（可通过 JSON 验证器）
- 重启后仍能正确反序列化
- 冷却机制跨重启生效

**验收证据**：
- 数据库查询结果截图
- JSON 内容示例

---

## 通用验收标准

### 1. 互斥性
- ✅ 单轮 system prompt 最多一个 `[RECALL_EVIDENCE]` 块

### 2. 隔离性
- ✅ 召回内容只注入 system prompt，不写入 messageNodes
- ✅ `updateRunningSummary()` 输入不含注入块
- ✅ `generateArchiveSummary()` 输入不含注入块

### 3. NeedGate
- ✅ needScore < 0.55 且非显式时不触发 DB 检索
- ✅ 日志提供 "NeedGate blocked" 证据

### 4. 冷却机制
- ✅ 连续两轮相同 candidateId，第二轮返回 NONE
- ✅ 冷却期 10 轮（写死）

### 5. 显式逐字
- ✅ 含"原文/全文/逐字/《...》"时能从 P 源输出

### 6. 预算护栏
- ✅ P 源 ≤ 3 个候选
- ✅ SNIPPET ≤ 800 字符
- ✅ FULL ≤ 6000 字符

### 7. 失败安全
- ✅ 任何异常 => NONE，不崩溃
- ✅ 日志记录错误

---

## 测试报告模板

```markdown
## 测试执行报告

**测试日期**：YYYY-MM-DD
**测试人员**：XXX
**APK 版本**：vX.X.X
**设备型号**：XXX

### 测试结果汇总

| 场景 | 结果 | 备注 |
|------|------|------|
| 场景1：互斥性 | ✅ / ❌ | |
| 场景2：NeedGate | ✅ / ❌ | |
| 场景3：冷却机制 | ✅ / ❌ | |
| 场景4：显式逐字 | ✅ / ❌ | |
| 场景5：Title 匹配 | ✅ / ❌ | |
| 场景6：FTS4 兜底 | ✅ / ❌ | |
| 场景7：预算护栏 | ✅ / ❌ | |
| 场景8：失败退化 | ✅ / ❌ | |
| 场景9：评分可复现 | ✅ / ❌ | |
| 场景10：账本持久化 | ✅ / ❌ | |

### 通过标准
- ✅ 全部 10 个场景通过
- ✅ 所有通用验收标准满足
- ✅ 无崩溃或异常行为
- ✅ 日志证据完整

### 缺陷记录
（记录发现的问题和复现步骤）
```

---

## 快速验证命令（用于自动化）

```bash
# 1. 检查 NeedGate 日志
adb logcat | grep "NeedGate blocked"

# 2. 检查注入块生成
adb logcat | grep "RECALL_EVIDENCE"

# 3. 检查候选生成
adb logcat | grep "Candidates generated"

# 4. 检查冷却机制
adb logcat | grep "cooldownUntilTurn"

# 5. 检查决策结果
adb logcat | grep "Decision made"

# 6. 查询数据库账本
adb shell "run-as me.rerere.rikkahub databases/rikkahub.db \"SELECT recall_ledger_json FROM conversationentity LIMIT 1;\""
```
