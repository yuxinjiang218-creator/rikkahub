# 召回系统参数UI收敛分析

> 本轮只做分析，不做任何代码修改。

---

## 一、当前已有的UI控制项

### 1.1 全局设置（Settings）

| 参数 | 类型 | 说明 | 已实现 |
|------|------|------|--------|
| `embeddingModelId` | Uuid? | Embedding 模型选择（用于 A 源语义召回） | ✅ |
| `enableWebSearch` | Boolean | 启用网络搜索（用于搜索增强） | ✅ |

### 1.2 助手设置（Assistant）

| 参数 | 类型 | 说明 | 已实现 |
|------|------|------|--------|
| `enableArchiveRecall` | Boolean | **启用归档自动回填**（A 源召回开关） | ✅ |
| `enableVerbatimRecall` | Boolean | 启用逐字召回（P 源召回开关） | ❓（需要确认）|
| `contextMessageSize` | Int | 上下文消息数量 | ✅ |
| `enableCompression` | Boolean | 启用上下文压缩 | ✅ |
| `enableMemory` | Boolean | 启用记忆系统（未来） | ✅ |

---

## 二、RecallConstants 参数分类（36+ 常量）

### 2.1 **用户需要UI控制的**（影响体验的关键开关）

#### 🔴 高优先级（必须暴露）

| 常量 | 当前值 | 说明 | UI 控制建议 |
|------|--------|------|-------------|
| **T_NEED** | 0.55 | NeedGate 阈值（低于此值不触发召回） | **滑块 0.3-0.8**，标记为"召回灵敏度" |
| **T_PROBE** | 0.75 | 试探阈值（中等置信度） | **高级设置**，与 T_FILL 联动 |
| **T_FILL** | 0.88 | 填充阈值（高置信度） | **高级设置**，与 T_PROBE 联动 |
| **MAX_STRIKES** | 3 (Balanced v1) | 最大失败次数进入静默 | **滑块 1-5**，标记为"静默触发次数" |
| **SILENT_WINDOW_TURNS** | 6 (Balanced v1) | 静默持续轮数 | **滑块 3-15**，标记为"静默持续时间" |

**理由**：
- `T_NEED` 直接影响召回频率，用户可能想要"更激进"或"更保守"
- `T_PROBE/T_FILL` 控制召回质量，高级用户可能想调整
- `MAX_STRIKES/SILENT_WINDOW_TURNS` 影响"被打扰"的程度，用户需要控制

#### 🟡 中优先级（可选暴露）

| 常量 | 当前值 | 说明 | UI 控制建议 |
|------|--------|------|-------------|
| **ENABLE_VERBATIM_RECALL** | (开关) | P 源召回开关（如果未在 Assistant 中） | **全局开关**，默认 ON |
| **ENABLE_ARCHIVE_RECALL** | (开关) | A 源召回开关（如果未在 Assistant 中） | **全局开关**，默认 ON |
| **REJECT_COOLDOWN_TURNS** | 24 | REJECT 后冷却轮数 | **高级设置**，滑块 10-60 |
| **IGNORE_COOLDOWN_TURNS** | 12 | IGNORE 后冷却轮数 | **高级设置**，滑块 5-30 |

**理由**：
- 全局召回开关方便用户快速关闭（替代逐个 Assistant 设置）
- 冷却轮数影响"重复打扰"的程度，高级用户可能想调整

---

### 2.2 **高级用户/开发者调参**（不暴露给普通用户）

#### 🟢 低优先级（仅开发者模式）

| 常量 | 当前值 | 说明 | 原因 |
|------|--------|------|------|
| **RISK_BLOCK** | 0.60 | 风险阻断阈值 | 实现细节，用户不理解"风险"含义 |
| **EXPLICIT_FULL_MIN_RELEVANCE** | 0.75 | 显式召回 FULL 最小相关性 | 高级语义，用户不理解 |
| **EXPLICIT_SNIPPET_MIN_RELEVANCE** | 0.55 | 显式召回 SNIPPET 最小相关性 | 高级语义，用户不理解 |
| **MARGIN_VETO_THRESHOLD** | 0.04 (Balanced v1) | Margin veto 阈值 | 内部优化参数 |
| **MARGIN_VETO_PRECISION_THRESHOLD** | 0.60 | Margin veto precision 阈值 | 内部优化参数 |
| **MARGIN_VETO_MAX_SCORE** | 0.88 | Margin veto 最大分值限制 | 内部优化参数 |
| **EDGE_SIMILARITY_MIN** | 0.30 | A 源边缘相似度下限 | 质量护栏，内部实现 |
| **EDGE_SIMILARITY_MAX** | 0.34 (Balanced v1) | A 源边缘相似度上限 | 质量护栏，内部实现 |
| **MIN_SNIPPET_LENGTH** | 80 | SNIPPET 最小长度 | 质量护栏，内部实现 |
| **WEIGHT_RELEVANCE** | 0.40 | Relevance 权重 | 评分公式内部参数 |
| **WEIGHT_PRECISION** | 0.20 | Precision 权重 | 评分公式内部参数 |
| **WEIGHT_NOVELTY** | 0.20 | Novelty 权重 | 评分公式内部参数 |
| **WEIGHT_NEED_SCORE** | 0.10 | NeedScore 权重 | 评分公式内部参数 |
| **WEIGHT_RECENCY** | 0.10 | Recency 权重 | 评分公式内部参数 |
| **PRECISION_TITLE_HIT** | 1.0 | Title 命中 precision | 评分逻辑内部参数 |
| **PRECISION_EXPLICIT_PHRASE** | 0.7 | 显式短语 precision | 评分逻辑内部参数 |
| **PRECISION_DEFAULT** | 0.3 | 默认 precision | 评分逻辑内部参数 |

**理由**：
- 这些都是内部算法参数，用户难以理解其影响
- 调整可能导致意外行为（如误召回、召回失败）
- 应该保留为"预设配置"（如 Conservative/Balanced/Aggressive）

---

### 2.3 **系统内部固定**（绝不暴露）

| 常量 | 当前值 | 说明 | 原因 |
|------|--------|------|------|
| **MAX_PER_SOURCE** | 3 | 每来源最多候选数 | 性能护栏，内部实现 |
| **SNIPPET_MAX_CHARS** | 800 | SNIPPET 最大字符数 | 预算护栏，内部实现 |
| **FULL_MAX_CHARS** | 6000 | FULL 最大字符数 | 预算护栏，内部实现 |
| **HINT_MAX_CHARS** | 200 | HINT 最大字符数 | 预算护栏，内部实现 |
| **MIN_COS_SIM** | 0.3 | 最小余弦相似度 | 质量护栏，内部实现 |
| **EMBEDDING_MAX_CALLS** | 3 | Embedding 最大调用次数 | 成本护栏，内部实现 |
| **MULTI_QUERY_NEED_SCORE_THRESHOLD** | 0.75 | MultiQuery 触发阈值 | 成本护栏，内部实现 |
| **MAX_NODE_TEXT_ROWS** | 50 | DAO 拉取条数硬上限 | 成本护栏，内部实现 |
| **MAX_WINDOW_SIZE_FOR_FULL** | 200 | 全量拉取 window 大小上限 | 成本护栏，内部实现 |
| **MAX_ANCHORS** | 10 | 最大 anchors 数量 | 内部实现细节 |
| **MAX_ANCHOR_LENGTH** | 40 | Anchor 最大字符数 | 内部实现细节 |
| **MAX_KEYWORD_LENGTH** | 8 | 显式关键词最大长度 | 内部实现细节 |
| **ACCEPT_OVERLAP_RATIO_THRESHOLD** | 0.20 | 接住判定词重叠率阈值 | 内部实现细节 |
| **ACCEPT_MIN_TEXT_LENGTH** | 4 | 最小文本长度（短文本抑噪） | 内部实现细节 |

**理由**：
- 这些是系统实现的核心护栏，暴露会破坏系统稳定性
- 预算限制、性能护栏必须硬编码
- 用户修改可能导致：
  - 内存溢出
  - API 成本爆炸
  - 性能崩溃

---

## 三、UI 控制项收敛建议

### 3.1 用户可见的召回设置（Assistant 设置页）

#### 基础模式（默认显示）

```kotlin
data class AssistantRecallSettings(
    // ✅ 已有
    val enableArchiveRecall: Boolean = true,
    val enableVerbatimRecall: Boolean = true,  // 需要确认是否存在

    // 🆕 新增（用户可调）
    val recallSensitivity: RecallSensitivity = RecallSensitivity.BALANCED,
    val silentMode: SilentMode = SilentMode.BALANCED
)

enum class RecallSensitivity {
    CONSERVATIVE,  // T_NEED = 0.65（更保守）
    BALANCED,      // T_NEED = 0.55（默认）
    AGGRESSIVE     // T_NEED = 0.45（更激进）
}

enum class SilentMode {
    QUIET,      // MAX_STRIKES = 2, SILENT_WINDOW_TURNS = 10
    BALANCED,   // MAX_STRIKES = 3, SILENT_WINDOW_TURNS = 6
    PATIENT     // MAX_STRIKES = 4, SILENT_WINDOW_TURNS = 3
}
```

#### 高级模式（开发者模式）

```kotlin
data class AdvancedRecallSettings(
    // 召回门槛
    val needGateThreshold: Float = 0.55f,         // 0.3 - 0.8
    val probeThreshold: Float = 0.75f,             // 0.6 - 0.9
    val fillThreshold: Float = 0.88f,              // 0.7 - 0.95

    // 疲劳控制
    val maxStrikes: Int = 3,                       // 1 - 5
    val silentWindowTurns: Int = 6,                // 3 - 15
    val rejectCooldownTurns: Int = 24,             // 10 - 60
    val ignoreCooldownTurns: Int = 12,             // 5 - 30

    // 预算控制（仅开发者）
    val maxSnippetLength: Int = 800,               // 400 - 1200
    val maxCandidates: Int = 8,                    // 4 - 12
)
```

### 3.2 全局召回设置（Settings 页面）

```kotlin
data class GlobalRecallSettings(
    // ✅ 已有
    val embeddingModelId: Uuid? = null,

    // 🆕 新增（全局开关）
    val enableGlobalRecall: Boolean = true,         // 全局召回总开关
    val defaultRecallMode: RecallMode = RecallMode.BALANCED
)
```

---

## 四、不暴露的实现细节（必须固定）

### 4.1 预算护栏（硬编码）

```kotlin
// 性能与成本护栏 - 绝不暴露
MAX_PER_SOURCE = 3              // 每来源最多 3 个候选
MAX_CANDIDATES = 8              // 总数最多 8 个候选
SNIPPET_MAX_CHARS = 800         // SNIPPET 最多 800 字符
FULL_MAX_CHARS = 6000           // FULL 最多 6000 字符
HINT_MAX_CHARS = 200            // HINT 最多 200 字符
```

**原因**：
- 这些限制保护系统稳定性（内存、性能、API 成本）
- 用户调整可能导致：
  - OOM（内存溢出）
  - API 配额耗尽
  - 响应时间过长

### 4.2 质量护栏（硬编码）

```kotlin
// 质量护栏 - 绝不暴露
MIN_COS_SIM = 0.3                      // 相似度下限
EDGE_SIMILARITY_MIN = 0.30             // 边缘区间下限
EDGE_SIMILARITY_MAX = 0.34             // 边缘区间上限
MIN_SNIPPET_LENGTH = 80                // SNIPPET 最小长度
```

**原因**：
- 这些是内部质量保证机制
- 用户调整可能导致：
  - 误召回（低质量内容）
  - 空洞召回（无用内容）

### 4.3 评分权重（硬编码）

```kotlin
// 评分公式内部参数 - 绝不暴露
WEIGHT_RELEVANCE = 0.40
WEIGHT_PRECISION = 0.20
WEIGHT_NOVELTY = 0.20
WEIGHT_NEED_SCORE = 0.10
WEIGHT_RECENCY = 0.10
```

**原因**：
- 这些是算法核心参数
- 调整需要深度理解算法原理
- 应该通过"预设配置"（Conservative/Balanced/Aggressive）来组合调整

---

## 五、实施建议（本轮不实施，仅分析）

### 5.1 短期（Phase J 可选）

1. **确认缺失的开关**：
   - 检查 `enableVerbatimRecall` 是否已存在于 Assistant 设置
   - 如果不存在，添加该字段

2. **添加预设配置**：
   - 实现 `RecallPreset` enum（CONSERVATIVE/BALANCED/AGGRESSIVE）
   - 在 Assistant 设置中添加"召回模式"下拉框

### 5.2 中期（Phase K 可选）

1. **高级设置面板**（开发者模式）：
   - 暴露 `T_NEED/T_PROBE/T_FILL` 滑块
   - 暴露 `MAX_STRIKES/SILENT_WINDOW_TURNS` 滑块
   - 添加"恢复默认值"按钮

2. **全局召回开关**：
   - 在 Settings 页面添加"启用智能召回"总开关
   - 允许用户快速关闭所有召回功能

### 5.3 长期（Phase L 可选）

1. **召回可视化**：
   - 显示每轮召回的候选评分（DEBUG 日志已有）
   - 显示静默窗口状态
   - 显示冷却状态

2. **召回统计**：
   - 召回次数统计
   - ACCEPT/IGNORE/REJECT 比例
   - 召回来源分布（P 源 vs A 源）

---

## 六、总结：需要暴露的最小集合

### 用户必须看到的（基础模式）

✅ **全局开关**（Settings 页面）：
- `embeddingModelId`（已有）
- `enableGlobalRecall`（新增，全局召回总开关）

✅ **助手开关**（Assistant 设置页）：
- `enableArchiveRecall`（已有）
- `enableVerbatimRecall`（确认/添加）
- `recallSensitivity`（新增，Conservative/Balanced/Aggressive 预设）
- `silentMode`（新增，Quiet/Balanced/Patient 预设）

### 高级用户可见（开发者模式）

🔧 **高级参数**（Assistant 高级设置）：
- `needGateThreshold`（滑块 0.3-0.8）
- `maxStrikes`（滑块 1-5）
- `silentWindowTurns`（滑块 3-15）
- `rejectCooldownTurns`（滑块 10-60）
- `ignoreCooldownTurns`（滑块 5-30）

### 绝不暴露（内部实现）

❌ **预算护栏**：
- MAX_PER_SOURCE, MAX_CANDIDATES
- SNIPPET_MAX_CHARS, FULL_MAX_CHARS, HINT_MAX_CHARS

❌ **质量护栏**：
- MIN_COS_SIM, EDGE_SIMILARITY_MIN/MAX
- MIN_SNIPPET_LENGTH

❌ **评分权重**：
- WEIGHT_RELEVANCE, WEIGHT_PRECISION, WEIGHT_NOVELTY, etc.

❌ **成本护栏**：
- EMBEDDING_MAX_CALLS, MAX_NODE_TEXT_ROWS, etc.

---

## 七、收敛原则

1. **用户体验优先**：暴露直接影响"被打扰程度"的参数
2. **安全优先**：预算/性能护栏必须硬编码
3. **简洁优先**：使用预设配置（Conservative/Balanced/Aggressive）替代复杂滑块
4. **分层优先**：基础模式（预设）→ 高级模式（滑块）→ 开发者模式（原始值）

---

**本轮结论**：只做分析，不做任何代码修改。等待用户确认后再决定是否实施 Phase J（添加缺失开关）或 Phase K（高级设置面板）。
