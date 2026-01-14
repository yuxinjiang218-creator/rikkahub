# RikkaHub vNext 智能召回系统 - 手动测试执行指南

**测试日期**: 2026-01-14
**测试设备**: MuMu 模拟器 (localhost:7555)
**APK 版本**: v1.7.9 (debug)
**日志文件**: `recall_test_log.txt`

---

## 测试准备

### 1. 确认 APP 已启动
```bash
adb shell monkey -p me.rerere.rikkahub.debug 1
```

### 2. 启用 Debug 日志
在 APP 设置中启用详细日志（LogLevel.DEBUG）

### 3. 准备测试会话
创建一个新会话，包含以下测试内容：
- 诗歌《静夜思》、《望庐山瀑布》
- 代码片段（async/await 示例）
- 讨论内容（异步编程方案）

---

## 测试场景执行

### 场景 1：互斥性验证

**目标**：单轮对话最多生成一个 [RECALL_EVIDENCE] 块

**步骤**：
1. 在会话中输入：`请背诵《静夜思》`
2. 等待 AI 回复
3. 输入：`再背一遍《望庐山瀑布》`
4. 等待 AI 回复
5. 检查日志

**预期结果**：
```
日志应该显示：
- 最多一个 `[RECALL_EVIDENCE]...[/RECALL_EVIDENCE]` 块
- DecisionResult.action = PROBE 或 FILL
- 不应该有多个 RECALL_EVIDENCE 标记
```

**验证命令**：
```bash
grep "RECALL_EVIDENCE" recall_test_log.txt | tail -20
```

---

### 场景 2：NeedGate 验证

**目标**：needScore < 0.55 且非显式时不触发 DB 检索

**步骤**：
1. 创建新会话
2. 输入：`你好`
3. 检查日志

**预期结果**：
```
日志应该显示：
- "NeedGate blocked (needScore=0.00 < T_NEED=0.55)"
- 不应该有任何 DB 查询日志
- 不应该有 "Candidates generated"
```

**验证命令**：
```bash
grep "NeedGate" recall_test_log.txt | tail -5
```

---

### 场景 3：冷却机制验证

**目标**：连续两轮相同查询，第二轮因冷却返回 NONE

**步骤**：
1. 输入：`请背诵《静夜思》`
2. 等待回复后，立即输入：`再背一遍刚才那首诗`
3. 检查日志

**预期结果**：
```
日志应该显示：
- 第 1 轮：生成 `[RECALL_EVIDENCE]` 块
- 第 2 轮："All candidates in cooldown" 或 "redundancyPenalty=1.0"
- 第 2 轮：DecisionResult.action = NONE
```

**验证命令**：
```bash
grep -E "cooldown|redundancyPenalty" recall_test_log.txt | tail -10
```

---

### 场景 4：显式逐字召回

**目标**：含"原文/全文/逐字/《...》"关键词时能从 P 源输出

**步骤**：
1. 输入：`请背诵《静夜思》原文`
2. 检查日志和注入块

**预期结果**：
```
日志应该显示：
- explicit=true
- 生成 `[RECALL_EVIDENCE]` 块
- type=FULL 或 type=SNIPPET
- source=P_TEXT
- 内容包含《静夜思》全文
```

**验证命令**：
```bash
grep -E "explicit|type=|source=" recall_test_log.txt | tail -20
```

---

### 场景 5：P 源候选生成（Title 匹配）

**目标**：Title 匹配策略能正确生成候选

**步骤**：
1. 先在会话中创建《静夜思》的逐字归档
2. 输入：`刚才那首《静夜思》再解释一下`
3. 检查日志

**预期结果**：
```
日志应该显示：
- "Title matching" 或 "Generated P source candidates"
- 候选 ID 格式：P:${conversationId}:SNIPPET:${nodeIndices}
```

**验证命令**：
```bash
grep -E "Title matching|Generated.*candidates|candidateId=P:" recall_test_log.txt | tail -10
```

---

### 场景 6：P 源候选生成（FTS4 兜底）

**目标**：无 Title 时使用 FTS4 兜底检索

**步骤**：
1. 在会话中讨论"异步编程"
2. 输入：`那个异步方案怎么实现？`
3. 检查日志

**预期结果**：
```
日志应该显示：
- "FTS4 fallback"
- 生成候选（如果有匹配内容）
- 候选数 ≤ 3（MAX_PER_SOURCE）
```

**验证命令**：
```bash
grep "FTS4" recall_test_log.txt | tail -5
```

---

### 场景 7：预算护栏验证

**目标**：SNIPPET ≤ 800 字符，FULL ≤ 6000 字符

**步骤**：
1. 创建一个长文本（> 1000 字符）的逐字归档
2. 输入：`请给出原文`
3. 检查注入块内容

**预期结果**：
```
日志应该显示：
- charCount 符合预算限制
- SNIPPET: ≤ 800 字符
- FULL: ≤ 6000 字符
```

**验证命令**：
```bash
grep "charCount" recall_test_log.txt | tail -5
```

---

### 场景 8：失败退化验证

**目标**：数据库查询失败时返回 NONE，不崩溃

**步骤**：
1. 模拟数据库故障（或查看现有日志中的异常）
2. 输入：`那个方案怎么样？`
3. 检查应用行为

**预期结果**：
```
应用不应该崩溃
日志应该显示：
- 异常信息（WARN 或 ERROR 级别）
- 返回 null（不生成注入块）
```

**验证命令**：
```bash
grep -E "ERROR|WARN.*recall|Exception" recall_test_log.txt | tail -10
```

---

### 场景 9：评分可复现性验证

**目标**：相同输入产生相同评分

**步骤**：
1. 输入：`那段代码再详细解释一下`
2. 等待冷却期后（10轮），再次输入相同内容
3. 对比两次日志中的评分

**预期结果**：
```
两次日志评分应该完全相同：
- relevance
- precision
- novelty
- recency
- risk
- finalScore
```

**验证命令**：
```bash
grep -E "relevance=|precision=|novelty=|recency=|risk=|finalScore=" recall_test_log.txt | tail -20
```

---

### 场景 10：账本持久化验证

**目标**：ledger 正确序列化/反序列化

**步骤**：
1. 触发一次召回（输入含回指词）
2. 查询数据库 `recall_ledger_json` 字段
3. 重启 APP
4. 再次触发召回

**预期结果**：
```
数据库中 recall_ledger_json 不为 "{}"
JSON 格式正确
冷却机制跨重启生效
```

**验证命令**：
```bash
# 查询数据库
adb shell "run-as me.rerere.rikkahub.debug databases/rikkahub.db \"SELECT recall_ledger_json FROM conversationentity LIMIT 1;\""
```

---

## 日志分析脚本

### 提取关键指标

```bash
# 1. NeedGate 判断
grep "NeedGate" recall_test_log.txt

# 2. 候选生成
grep -E "Candidates generated|Title matching|FTS4" recall_test_log.txt

# 3. 评分结果
grep -E "relevance=|precision=|novelty=|finalScore=" recall_test_log.txt

# 4. 决策结果
grep "Decision made" recall_test_log.txt

# 5. 注入块生成
grep "RECALL_EVIDENCE" recall_test_log.txt

# 6. 性能指标
grep "Recall performance" recall_test_log.txt

# 7. 冷却机制
grep -E "cooldown|redundancyPenalty" recall_test_log.txt

# 8. 错误和异常
grep -E "ERROR|WARN|Exception|Failed" recall_test_log.txt
```

---

## 测试报告模板

```markdown
## 手动测试执行报告

**测试人员**: XXX
**测试时间**: YYYY-MM-DD HH:mm:ss
**设备信息**: MuMu 模拟器

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

## 快速验证命令

```bash
# 检查是否有召回发生
grep "RECALL_EVIDENCE" recall_test_log.txt | wc -l

# 检查 NeedGate 阻塞次数
grep "NeedGate blocked" recall_test_log.txt | wc -l

# 检查冷却生效次数
grep "cooldownUntilTurn" recall_test_log.txt | wc -l

# 检查性能是否达标
grep "withinTarget=true" recall_test_log.txt | wc -l

# 检查错误数量
grep -E "ERROR|Exception" recall_test_log.txt | wc -l
```

---

## 注意事项

1. **日志收集**：测试过程中保持 logcat 持续收集
2. **场景隔离**：每个场景后记录日志范围，便于分析
3. **截图保存**：关键步骤截图保存作为证据
4. **异常记录**：发现任何异常行为立即记录

---

**指南生成时间**: 2026-01-14 17:30:00
**文档版本**: v1.0
