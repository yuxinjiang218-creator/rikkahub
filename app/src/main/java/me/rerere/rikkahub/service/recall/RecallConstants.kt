package me.rerere.rikkahub.service.recall

/**
 * 召回系统常量集中（Phase H：便于调参冻结）
 *
 * 设计原则：
 * - 所有写死阈值集中于此，不改行为，只便于未来调参
 * - 每个阈值加注释说明"为何存在、调大/调小的方向影响"
 * - 禁止顺手重构；不新增 UI；失败倾向 NONE
 */
object RecallConstants {

    // ========================================
    // NeedGate（需求门控）
    // ========================================

    /**
     * NeedGate 阈值（启发式评分 >= 此值则进入候选生成）
     *
     * 为何存在：避免低需求时浪费资源做召回
     * - 调大：更保守，减少召回频率
     * - 调小：更激进，增加召回频率
     */
    const val T_NEED = 0.55f

    // ========================================
    // RecallDecisionEngine（决策引擎）
    // ========================================

    /**
     * 试探阈值（finalScore >= 此值且 < T_FILL => PROBE）
     *
     * 为何存在：中等置信度时使用试探（小片段注入）
     * - 调大：减少试探，提高召回门槛
     * - 调小：增加试探，降低召回门槛
     */
    const val T_PROBE = 0.75f

    /**
     * 填充阈值（finalScore >= 此值 => FACT_HINT 或 PROBE）
     *
     * 为何存在：高置信度时直接注入（无需试探）
     * - 调大：减少填充，提高召回门槛
     * - 调小：增加填充，降低召回门槛
     */
    const val T_FILL = 0.88f

    /**
     * 风险阻断阈值（risk > 此值且 non-explicit => NONE）
     *
     * 为何存在：避免高风险误召回（如低相关性、低 precision）
     * - 调大：允许更高风险的召回
     * - 调小：更保守，仅允许低风险召回
     */
    const val RISK_BLOCK = 0.60f

    /**
     * 显式召回的最小相关性阈值（FULL）
     *
     * 为何存在：显式请求 FULL 时需要高相关性支持
     * - 调大：减少 FULL 召回
     * - 调小：增加 FULL 召回
     */
    const val EXPLICIT_FULL_MIN_RELEVANCE = 0.75f

    /**
     * 显式召回的最小相关性阈值（SNIPPET）
     *
     * 为何存在：显式请求 SNIPPET 时需要中等相关性支持
     * - 调大：减少 SNIPPET 召回
     * - 调小：增加 SNIPPET 召回
     */
    const val EXPLICIT_SNIPPET_MIN_RELEVANCE = 0.55f

    /**
     * Margin veto 阈值（best - secondBest < 此值 => 可能 veto）
     *
     * 为何存在：避免模糊候选误召回（灰区保守策略）
     * - 调大：减少 veto，允许更接近的候选
     * - 调小：增加 veto，要求候选之间差距更大
     *
     * Phase I (Balanced v1): 0.05 → 0.04（略微放松，允许更接近的候选）
     */
    const val MARGIN_VETO_THRESHOLD = 0.04f

    /**
     * Margin veto precision 阈值（precision < 此值时才触发 veto）
     *
     * 为何存在：仅当 precision 不高时才 veto（若 precision 高则允许模糊候选）
     * - 调大：减少 veto，允许更低 precision 的候选
     * - 调小：增加 veto，要求更高 precision
     */
    const val MARGIN_VETO_PRECISION_THRESHOLD = 0.60f

    /**
     * Margin veto 最大分值限制（best.final >= 此值时不触发 margin veto）
     *
     * 为何存在：高置信度召回不应被"分差小"一刀切否决
     * - 调大：允许更高置信度的召回不受 margin veto 影响
     * - 调小：降低高置信度门槛，更多情况受 margin veto 约束
     *
     * Phase I (Balanced v1): 新增常量（保护高置信度召回，>=0.88 不会被 margin veto 误伤）
     */
    const val MARGIN_VETO_MAX_SCORE = 0.88f

    // ========================================
    // ProbeLedgerState（探针账本）
    // ========================================

    /**
     * 最大 strikes 数（>= 此值时进入静默窗口）
     *
     * 为何存在：连续多次 IGNORE/REJECT 时需要静默避免打扰用户
     * - 调大：允许更多次失败才静默
     * - 调小：更快进入静默
     *
     * Phase I (Balanced v1): 2 → 3（更耐心，连续两次 IGNORE 不触发静默）
     */
    const val MAX_STRIKES = 3

    /**
     * 静默窗口持续轮数（strikes >= MAX_STRIKES 时静默此轮数）
     *
     * 为何存在：静默期间禁止 non-explicit 召回
     * - 调大：静默时间更长
     * - 调小：静默时间更短
     *
     * Phase I (Balanced v1): 10 → 6（静默时间更短，防止"过度安静"）
     */
    const val SILENT_WINDOW_TURNS = 6

    /**
     * REJECT 延长冷却轮数（REJECT 后 cooldownUntilTurn = now + 此值）
     *
     * 为何存在：用户明确拒绝后需要更长的冷却期
     * - 调大：REJECT 后冷却时间更长
     * - 调小：REJECT 后冷却时间更短
     *
     * Phase I (Balanced v1): 30 → 24（缩短冷却，更愿意再次尝试）
     */
    const val REJECT_COOLDOWN_TURNS = 24

    /**
     * IGNORE 延长冷却轮数（IGNORE 后 cooldownUntilTurn = now + 此值）
     *
     * 为何存在：用户忽略后需要适度的冷却期（比 REJECT 短）
     * - 调大：IGNORE 后冷却时间更长
     * - 调小：IGNORE 后冷却时间更短
     *
     * Phase I (Balanced v1): 15 → 12（缩短冷却，更愿意再次尝试）
     */
    const val IGNORE_COOLDOWN_TURNS = 12

    // ========================================
    // EvidenceScorer（证据评分）
    // ========================================

    /**
     * Relevance 权重（finalScore 加权公式中 relevance 的系数）
     *
     * 为何存在：relevance 是最重要的信号（用户查询与候选的相关性）
     * - 调大：更重视 relevance
     * - 调小：降低 relevance 重要性
     */
    const val WEIGHT_RELEVANCE = 0.40f

    /**
     * Precision 权重（finalScore 加权公式中 precision 的系数）
     *
     * 为何存在：precision 反映候选精确度（title 命中 > 显式短语 > 默认）
     * - 调大：更重视 precision
     * - 调小：降低 precision 重要性
     */
    const val WEIGHT_PRECISION = 0.20f

    /**
     * Novelty 权重（finalScore 加权公式中 novelty 的系数）
     *
     * 为何存在：novelty 避免重复内容（已存在的内容 novelty=0）
     * - 调大：更重视 novelty
     * - 调小：降低 novelty 重要性
     */
    const val WEIGHT_NOVELTY = 0.20f

    /**
     * NeedScore 权重（finalScore 加权公式中 needScore 的系数）
     *
     * 为何存在：needScore 反映用户需求强度
     * - 调大：更重视 needScore
     * - 调小：降低 needScore 重要性
     */
    const val WEIGHT_NEED_SCORE = 0.10f

    /**
     * Recency 权重（finalScore 加权公式中 recency 的系数）
     *
     * 为何存在：recency 反映候选的时间新鲜度
     * - 调大：更重视 recency
     * - 调小：降低 recency 重要性
     */
    const val WEIGHT_RECENCY = 0.10f

    /**
     * Title 命中 precision（title 命中时 precision = 此值）
     *
     * 为何存在：title 命中是高精度信号（用户明确指名）
     * - 调大：增加 title 命中的权重
     * - 调小：降低 title 命中的权重
     */
    const val PRECISION_TITLE_HIT = 1.0f

    /**
     * 显式短语 precision（命中显式逐字短语时 precision = 此值）
     *
     * 为何存在：显式短语是中等精度信号（用户提到"原文"等）
     * - 调大：增加显式短语的权重
     * - 调小：降低显式短语的权重
     */
    const val PRECISION_EXPLICIT_PHRASE = 0.7f

    /**
     * 默认 precision（未命中 title/显式短语时 precision = 此值）
     *
     * 为何存在：无明确信号时的默认 precision
     * - 调大：提高默认召回倾向
     * - 调小：降低默认召回倾向
     */
    const val PRECISION_DEFAULT = 0.3f

    // ========================================
    // TextSourceCandidateGenerator（P源候选生成）
    // ========================================

    /**
     * 每来源最多候选数（防止候选过多）
     *
     * 为何存在：限制候选数量，控制计算成本
     * - 调大：允许更多候选，增加计算成本
     * - 调小：减少候选，降低计算成本
     */
    const val MAX_PER_SOURCE = 3

    /**
     * SNIPPET 最大字符数（P源 SNIPPET 内容截断到此长度）
     *
     * 为何存在：控制注入块大小，避免超过上下文预算
     * - 调大：允许更长的 SNIPPET
     * - 调小：缩短 SNIPPET，减少注入内容
     */
    const val SNIPPET_MAX_CHARS = 800

    /**
     * FULL 最大字符数（P源 FULL 内容截断到此长度）
     *
     * 为何存在：控制注入块大小，避免超过上下文预算
     * - 调大：允许更长的 FULL
     * - 调小：缩短 FULL，减少注入内容
     */
    const val FULL_MAX_CHARS = 6000

    // ========================================
    // ArchiveSourceCandidateGenerator（A源候选生成）
    // ========================================

    /**
     * HINT 最大字符数（A源 HINT 内容截断到此长度）
     *
     * 为何存在：控制 HINT 候选大小（摘要通常较短）
     * - 调大：允许更长的 HINT
     * - 调小：缩短 HINT，减少注入内容
     */
    const val HINT_MAX_CHARS = 200

    /**
     * 最小余弦相似度（cosineSimilarity < 此值被过滤）
     *
     * 为何存在：过滤低相似度归档，避免误召回
     * - 调大：允许更低相似度的归档
     * - 调小：提高相似度门槛，减少误召回
     */
    const val MIN_COS_SIM = 0.3f

    /**
     * Embedding 最大调用次数（MultiQuery 调度策略）
     *
     * 为何存在：限制 embedding 调用次数，控制成本
     * - 调大：允许更多次 embedding（Q0+Q1+Q2）
     * - 调小：减少 embedding 调用（仅 Q0）
     */
    const val EMBEDDING_MAX_CALLS = 3

    /**
     * MultiQuery 触发阈值（needScore >= 此值且 P源无候选时执行 3 次 embedding）
     *
     * 为何存在：高需求且 P源无候选时，增加 embedding 调用提高召回率
     * - 调大：减少 MultiQuery 触发
     * - 调小：增加 MultiQuery 触发
     */
    const val MULTI_QUERY_NEED_SCORE_THRESHOLD = 0.75f

    /**
     * DAO 拉取条数硬上限（Phase G3.1 成本护栏）
     *
     * 为何存在：防止 window 过大时拉取过多数据，控制 IO 成本
     * - 调大：允许拉取更多数据，增加 IO 成本
     * - 调小：减少数据拉取，降低 IO 成本
     */
    const val MAX_NODE_TEXT_ROWS = 50

    /**
     * 全量拉取的 window 大小上限（Phase G3.1 成本护栏）
     *
     * 为何存在：window > 此值时只取前 MAX_NODE_TEXT_ROWS 条，不得全量拉取
     * - 调大：允许更大的 window 全量拉取
     * - 调小：window 更容易触发稀疏采样
     */
    const val MAX_WINDOW_SIZE_FOR_FULL = 200

    /**
     * 边缘相似度下限（Phase G3.2 质量护栏）
     *
     * 为何存在：相似度在 [EDGE_SIMILARITY_MIN, EDGE_SIMILARITY_MAX) 时只生成 HINT，降低误召回
     * - 调大：扩大边缘区间，更多情况下只生成 HINT
     * - 调小：缩小边缘区间，允许更多 SNIPPET
     */
    const val EDGE_SIMILARITY_MIN = 0.30f

    /**
     * 边缘相似度上限（Phase G3.2 质量护栏）
     *
     * 为何存在：相似度在 [EDGE_SIMILARITY_MIN, EDGE_SIMILARITY_MAX) 时只生成 HINT，降低误召回
     * - 调大：扩大边缘区间，更多情况下只生成 HINT
     * - 调小：缩小边缘区间，允许更多 SNIPPET
     *
     * Phase I (Balanced v1): 0.35 → 0.34（略微收窄边缘区间，更愿意给 SNIPPET）
     */
    const val EDGE_SIMILARITY_MAX = 0.34f

    /**
     * SNIPPET 最小长度（Phase G3.2 质量护栏）
     *
     * 为何存在：SNIPPET 清理后长度 < 此值时退化为 HINT，避免空洞 snippet
     * - 调大：允许更短的 SNIPPET
     * - 调小：提高 SNIPPET 长度门槛，更多情况退 HINT
     */
    const val MIN_SNIPPET_LENGTH = 80

    // ========================================
    // AnchorGenerator（anchors 生成）
    // ========================================

    /**
     * 最大 anchors 数量（Phase G1 anchors 收敛）
     *
     * 为何存在：限制 anchors 数量，避免候选过大
     * - 调大：允许更多 anchors
     * - 调小：减少 anchors
     */
    const val MAX_ANCHORS = 10

    /**
     * Anchor 最大字符数（Phase G1 anchors 收敛）
     *
     * 为何存在：限制单个 anchor 长度，避免候选过大
     * - 调大：允许更长的 anchor
     * - 调小：缩短 anchor
     */
    const val MAX_ANCHOR_LENGTH = 40

    /**
     * 显式关键词最大长度（Phase G1 anchors 收敛）
     *
     * 为何存在：限制显式关键词长度，避免过长的关键词被视为 anchor
     * - 调大：允许更长的关键词
     * - 调小：缩短关键词长度
     */
    const val MAX_KEYWORD_LENGTH = 8

    // ========================================
    // ProbeAcceptanceJudge（接住判定）
    // ========================================

    /**
     * 词重叠率阈值（overlap >= 此值时触发 ACCEPT）
     *
     * 为何存在：用户输入与候选内容的词重叠率 >= 此值时认为接住
     * - 调大：提高接住门槛
     * - 调小：降低接住门槛，更容易判定 ACCEPT
     */
    const val ACCEPT_OVERLAP_RATIO_THRESHOLD = 0.20f

    /**
     * 最小文本长度（Phase F 修复：短文本抑噪）
     *
     * 为何存在：cleaned.length < 此值时不因 overlap 判定 ACCEPT，避免短文本噪声
     * - 调大：允许更短的文本通过 overlap 判定
     * - 调小：提高短文本门槛，减少噪声误判
     */
    const val ACCEPT_MIN_TEXT_LENGTH = 4
}
