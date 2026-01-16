package me.rerere.rikkahub.service.recall

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.recall.decision.RecallDecisionEngine
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.planner.HeuristicRecallPlanner
import me.rerere.rikkahub.service.recall.planner.LlmRecallPlanner
import me.rerere.rikkahub.service.recall.planner.PlannerResult
import me.rerere.rikkahub.service.recall.scorer.EvidenceScorer
import me.rerere.rikkahub.service.recall.source.ArchiveSourceCandidateGenerator
import me.rerere.rikkahub.service.recall.source.TextSourceCandidateGenerator
import kotlin.system.measureTimeMillis

/**
 * 智能召回系统协调器（Phase C 完整实现）
 *
 * Phase B: 完整实现 NeedGate + P源候选 + 评分 + 决策 + 注入
 * Phase C: 集成 A源候选
 * Phase L1: 集成 RecallPlanner（支持 LLM 规划 + 多查询生成）
 * Phase L2: 规划器触发逻辑（灰色区域触发，覆盖 NeedGate）
 * Phase L3: 多查询检索（P/A 源支持多查询合并去重）
 */
class RecallCoordinator(
    private val context: Context,
    private val conversationDao: ConversationDAO,
    private val messageNodeTextDao: MessageNodeTextDao,
    private val verbatimArtifactDao: VerbatimArtifactDao,
    private val archiveSummaryDao: ArchiveSummaryDao,
    private val vectorIndexDao: VectorIndexDao,
    private val providerManager: ProviderManager,
    // Phase L1: 可选的 LLM 规划器（未配置时仅使用 HeuristicPlanner）
    private val llmPlanner: LlmRecallPlanner? = null
) {
    companion object {
        private const val TAG = "RecallCoordinator"

        // Phase L2: 灰色区域阈值
        private const val GRAY_ZONE_MIN_SCORE = 0.35f  // NeedGate 分数下限
        private const val GRAY_ZONE_MAX_SCORE = 0.65f  // NeedGate 分数上限
        private const val SHORT_TEXT_THRESHOLD = 12    // 短文本阈值（字符数）
    }

    /** Phase B 组件 */
    private val textSourceCandidateGenerator by lazy {
        TextSourceCandidateGenerator(
            context = context,
            messageNodeTextDao = messageNodeTextDao,
            verbatimArtifactDao = verbatimArtifactDao
        )
    }

    /** Phase C 组件 */
    private val archiveSourceCandidateGenerator by lazy {
        ArchiveSourceCandidateGenerator(
            context = context,
            archiveSummaryDao = archiveSummaryDao,
            vectorIndexDao = vectorIndexDao,
            messageNodeTextDao = messageNodeTextDao,
            providerManager = providerManager
        )
    }

    /** Phase L1: 兜底规划器（始终可用） */
    private val heuristicPlanner by lazy { HeuristicRecallPlanner() }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Phase L2: 判断是否应该使用 LLM 规划器
     *
     * 触发条件：
     * 1. 配置了 LLM 规划器
     * 2. 不是显式请求（explicit=false）
     * 3. needScore 在灰色区域 (0.35~0.65) 或文本很短 (<=12 字符)
     */
    /**
     * Phase L2: 判断是否应该使用 LLM 规划器
     *
     * 触发条件：
     * 1. 配置了 LLM 规划器
     * 2. 不是显式请求（explicit=false）
     * 3. needScore 在灰色区域 (0.35~0.65) 或文本很短 (<=12 字符)
     */
    private fun shouldUseLlmPlanner(
        needScore: Float,
        isExplicit: Boolean,
        lastUserText: String
    ): Boolean {
        if (llmPlanner == null) return false
        if (isExplicit) return false  // 显式请求跳过规划器

        val inGrayZone = needScore in GRAY_ZONE_MIN_SCORE..GRAY_ZONE_MAX_SCORE
        val isShortText = lastUserText.length <= SHORT_TEXT_THRESHOLD
        return inGrayZone || isShortText
    }

    /**
     * 协调召回流程（Phase C 完整实现 + 性能监控）
     *
     * @param queryContext 查询上下文
     * @param onRecallLedgerUpdate 账本更新回调（返回更新后的 JSON 字符串）
     * @param settings 设置（用于 A源 embedding 调用）
     * @return 召回注入块字符串，如果不需要召回则返回 null
     */
    suspend fun coordinateRecall(
        queryContext: QueryContext,
        onRecallLedgerUpdate: ((String) -> Unit)?,
        settings: me.rerere.rikkahub.data.datastore.Settings? = null  // 可选，用于 A源
    ): String? {
        val debugLogger = DebugLogger.getInstance(context)

        // 总体性能监控
        val totalStartTime = System.currentTimeMillis()

        Log.i(TAG, "=== RecallCoordinator.coordinateRecall STARTED ===")
        Log.i(TAG, "conversationId: ${queryContext.conversationId}")
        Log.i(TAG, "lastUserText: ${queryContext.lastUserText.take(100)}")
        Log.i(TAG, "explicitSignal.explicit: ${queryContext.explicitSignal.explicit}")
        Log.i(TAG, "enableVerbatimRecall: ${queryContext.settingsSnapshot.enableVerbatimRecall}")
        Log.i(TAG, "enableArchiveRecall: ${queryContext.settingsSnapshot.enableArchiveRecall}")

        debugLogger.log(
            LogLevel.INFO,
            TAG,
            "Recall coordination started",
            mapOf(
                "conversationId" to queryContext.conversationId,
                "lastUserText" to queryContext.lastUserText.take(100),
                "explicit" to queryContext.explicitSignal.explicit
            )
        )

        // 阶段1：评估上一轮试探结果（Phase F：ProbeControl 纯内存计算，优先于 NeedGate）
        // 目的：即使 NeedGate blocked，也要更新 outcome/strikes，避免短回复（"对/继续"）无法判定
        val evaluateProbeStartTime = System.currentTimeMillis()
        val updatedLedger = me.rerere.rikkahub.service.recall.probe.ProbeControl.evaluateAndUpdate(
            ledger = queryContext.ledger,
            lastUserText = queryContext.lastUserText,
            nowTurnIndex = queryContext.nowTurnIndex,
            context = context
        )
        val evaluateProbeTime = System.currentTimeMillis() - evaluateProbeStartTime

        debugLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Probe evaluation completed",
            mapOf(
                "time" to "${evaluateProbeTime}ms",
                "outcome" to (updatedLedger.lastProbeObservation?.outcome?.name ?: "null"),
                "strikes" to updatedLedger.globalProbeStrikes
            )
        )

        // 更新 QueryContext.ledger
        val updatedQueryContext = queryContext.copy(ledger = updatedLedger)

        // P0-1: 统一计算 needScore（用于 NeedGate、A源、EvidenceScorer、RecallDecisionEngine）
        val needScore = NeedGate.computeNeedScoreHeuristic(updatedQueryContext)

        // Phase L2: 阶段2a - 规划器决策（三层智能策略）
        val plannerResult: PlannerResult
        val plannerTime = measureTimeMillis {
            val useLlmPlanner = shouldUseLlmPlanner(
                needScore = needScore,
                isExplicit = updatedQueryContext.explicitSignal.explicit,
                lastUserText = updatedQueryContext.lastUserText
            )

            plannerResult = if (useLlmPlanner) {
                Log.i(TAG, "=== Using LLM Planner ===")
                debugLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "LLM planner triggered",
                    mapOf(
                        "needScore" to needScore,
                        "grayZone" to "${GRAY_ZONE_MIN_SCORE}..$GRAY_ZONE_MAX_SCORE",
                        "textLength" to updatedQueryContext.lastUserText.length
                    )
                )
                llmPlanner!!.plan(updatedQueryContext)
            } else {
                // 使用兜底规划器（NeedGate 逻辑）
                Log.i(TAG, "=== Using Heuristic Planner ===")
                heuristicPlanner.plan(updatedQueryContext)
            }

            Log.i(TAG, "=== Planner Result ===")
            Log.i(TAG, "shouldRecall: ${plannerResult.shouldRecall}")
            Log.i(TAG, "pQueries: ${plannerResult.pQueries}")
            Log.i(TAG, "aQueries: ${plannerResult.aQueries}")
            Log.i(TAG, "reason: ${plannerResult.reason}")
            Log.i(TAG, "confidence: ${plannerResult.confidence}")

            debugLogger.log(
                LogLevel.INFO,
                TAG,
                "Planner result",
                mapOf(
                    "shouldRecall" to plannerResult.shouldRecall,
                    "pQueries" to plannerResult.pQueries.size,
                    "aQueries" to plannerResult.aQueries.size,
                    "confidence" to plannerResult.confidence,
                    "reason" to plannerResult.reason
                )
            )
        }

        // 阶段2b：NeedGate 检查（可能被规划器覆盖）
        val needGateTime = measureTimeMillis {
            val needGatePass = NeedGate.shouldProceed(updatedQueryContext)

            Log.i(TAG, "=== NeedGate CHECK ===")
            Log.i(TAG, "needScore: $needScore")
            Log.i(TAG, "threshold: ${NeedGate.getThreshold()}")
            Log.i(TAG, "explicit: ${updatedQueryContext.explicitSignal.explicit}")
            Log.i(TAG, "needGatePass: $needGatePass")
            Log.i(TAG, "planner.shouldRecall: ${plannerResult.shouldRecall}")

            // Phase L2: 规划器的 shouldRecall 可以覆盖 NeedGate 的 block
            val shouldProceed = needGatePass || plannerResult.shouldRecall

            if (!shouldProceed) {
                Log.w(TAG, "=== Recall BLOCKED (NeedGate blocked, planner override=false) ===")
                debugLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Recall blocked",
                    mapOf(
                        "needScore" to needScore,
                        "threshold" to NeedGate.getThreshold(),
                        "plannerOverride" to plannerResult.shouldRecall,
                        "reason" to plannerResult.reason
                    )
                )
                // Phase F: 即使 blocked，也要回调更新后的 ledger（outcome/strikes 已更新）
                val updatedLedgerJson = json.encodeToString(updatedLedger)
                onRecallLedgerUpdate?.invoke(updatedLedgerJson)
                return null  // 未通过，不调用任何 DAO
            }
        }

        Log.i(TAG, "=== Recall PASSED in ${needGateTime}ms (planner: ${plannerTime}ms) ===")
        debugLogger.log(LogLevel.DEBUG, TAG, "Recall passed in ${needGateTime}ms")

        // 阶段3：检查静默窗口（Phase E：快速停止）
        if (!updatedQueryContext.explicitSignal.explicit &&
            me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
                ledger = updatedLedger,
                nowTurnIndex = updatedQueryContext.nowTurnIndex
            )
        ) {
            debugLogger.log(
                LogLevel.INFO,
                TAG,
                "Silent window active, blocking non-explicit recall",
                mapOf(
                    "silentUntilTurn" to updatedLedger.silentUntilTurn,
                    "nowTurnIndex" to updatedQueryContext.nowTurnIndex
                )
            )
            // Phase F: 即使 blocked，也要回调更新后的 ledger
            val updatedLedgerJson = json.encodeToString(updatedLedger)
            onRecallLedgerUpdate?.invoke(updatedLedgerJson)
            return null  // 静默窗口内，non-explicit 一律 NONE
        }

        // 阶段4：P源候选生成（Phase L3: 支持多查询）
        val candidates = mutableListOf<Candidate>()
        val pSourceTime = measureTimeMillis {
            Log.i(TAG, "=== P Source Candidate Generation START ===")
            Log.i(TAG, "=== P Source queries: ${plannerResult.pQueries} ===")

            val pSourceCandidates = textSourceCandidateGenerator.generate(
                queryContext = updatedQueryContext,
                queries = plannerResult.pQueries  // Phase L3: 传入多查询
            )
            candidates.addAll(pSourceCandidates)

            Log.i(TAG, "=== P Source generated ${pSourceCandidates.size} candidates ===")
            debugLogger.log(
                LogLevel.INFO,
                TAG,
                "P source candidates generated",
                mapOf(
                    "count" to pSourceCandidates.size,
                    "queries" to plannerResult.pQueries.size
                )
            )
        }
        debugLogger.log(LogLevel.DEBUG, TAG, "P source generation took ${pSourceTime}ms")

        // 阶段5：A源候选生成（Phase C + Phase L3: 支持多查询）
        // P0-1: 传入 needScore（统一计算，不得使用 ledger.recent.size 派生）
        val aSourceTime = measureTimeMillis {
            Log.i(TAG, "=== A Source queries: ${plannerResult.aQueries} ===")

            val aSourceCandidates = archiveSourceCandidateGenerator.generate(
                queryContext = updatedQueryContext,
                pSourceCandidateCount = candidates.size,
                needScore = needScore,  // P0-1: 传入统一计算的 needScore
                settings = settings,
                queries = plannerResult.aQueries  // Phase L3: 传入多查询
            )
            candidates.addAll(aSourceCandidates)

            debugLogger.log(
                LogLevel.INFO,
                TAG,
                "A source candidates generated",
                mapOf(
                    "count" to aSourceCandidates.size,
                    "queries" to plannerResult.aQueries.size
                )
            )
        }
        debugLogger.log(LogLevel.DEBUG, TAG, "A source generation took ${aSourceTime}ms")

        if (candidates.isEmpty()) {
            debugLogger.log(LogLevel.INFO, TAG, "No candidates generated from any source")
            return null
        }

        debugLogger.log(
            LogLevel.INFO,
            TAG,
            "Total candidates generated",
            mapOf("count" to candidates.size)
        )

        // 阶段6：获取最大 node_index（用于 recency 归一化）
        val maxNodeIndex = messageNodeTextDao.getMaxNodeIndex(updatedQueryContext.conversationId) ?: 0

        // 阶段7：评分
        // P0-1: 使用统一计算的 needScore（不再重复计算）
        // 显式请求时，needScore 强制为 1.0（仅用于评分/决策一致性，不影响 shouldProceed）
        val scoringNeedScore = if (updatedQueryContext.explicitSignal.explicit) {
            1.0f
        } else {
            needScore.coerceIn(0f, 1f)
        }

        val scoredCandidates = candidates.map { candidate ->
            candidate to EvidenceScorer.score(
                candidate = candidate,
                queryContext = updatedQueryContext,
                needScore = scoringNeedScore,
                maxNodeIndex = maxNodeIndex
            )
        }
        val scoringTime = 0L  // 已在上面计算
        debugLogger.log(LogLevel.DEBUG, TAG, "Scoring completed")

        // 阶段8：决策
        // Phase J0: 传入统一计算的 needScore（与 EvidenceScorer 保持一致）
        val decisionResult = RecallDecisionEngine.decide(
            scoredCandidates = scoredCandidates,
            queryContext = updatedQueryContext,
            needScore = scoringNeedScore
        )
        val decisionTime = 0L  // 已在上面计算

        // Phase H2: 结构化日志（冻结字段，便于调试）
        val bestCandidate = decisionResult.selectedCandidate
        val bestScores = scoredCandidates.firstOrNull { it.first.id == bestCandidate?.id }?.second
        val secondBest = scoredCandidates
            .filter { it.first.id != bestCandidate?.id }
            .maxByOrNull { it.second.finalScore }
        val margin = if (secondBest != null && bestScores != null) {
            bestScores.finalScore - secondBest.second.finalScore
        } else {
            null
        }

        debugLogger.log(
            LogLevel.INFO,
            TAG,
            "Recall decision summary (Phase H)",
            mapOf(
                "needScore" to scoringNeedScore,  // Phase J0: 使用统一计算的 needScore
                "explicit" to updatedQueryContext.explicitSignal.explicit,
                "silent" to me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
                    ledger = updatedLedger,
                    nowTurnIndex = updatedQueryContext.nowTurnIndex
                ),
                "bestScore" to (bestScores?.finalScore ?: 0f),
                "secondScore" to (secondBest?.second?.finalScore ?: 0f),
                "margin" to (margin ?: "null"),
                "precision" to (bestScores?.precision ?: 0f),
                "risk" to (bestScores?.risk ?: 0f),
                "action" to decisionResult.action.name,
                "vetoReason" to (decisionResult.vetoReason ?: "null")
            )
        )

        // 阶段9：账本更新 + 冷却（Phase E：记录本轮试探）
        val finalLedger = if (decisionResult.action != RecallAction.NONE && decisionResult.selectedCandidate != null) {
            // 6.1 记录本轮试探（用于下一轮判定）
            val ledgerWithProbe = me.rerere.rikkahub.service.recall.probe.ProbeControl.recordProbe(
                ledger = updatedLedger,
                action = decisionResult.action,
                candidate = decisionResult.selectedCandidate,
                nowTurnIndex = updatedQueryContext.nowTurnIndex
            )

            // 6.2 创建账本条目
            val ledgerEntry = RecallDecisionEngine.createLedgerEntry(
                candidate = decisionResult.selectedCandidate,
                action = decisionResult.action,
                queryContext = updatedQueryContext
            )

            // 6.3 添加条目到账本
            val ledgerWithEntry = ledgerWithProbe.addEntry(ledgerEntry)
            val updatedLedgerJson = json.encodeToString(ledgerWithEntry)

            debugLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Ledger updated",
                mapOf(
                    "candidateId" to ledgerEntry.candidateId,
                    "action" to ledgerEntry.action.name,
                    "cooldownUntilTurn" to ledgerEntry.cooldownUntilTurn,
                    "globalProbeStrikes" to ledgerWithEntry.globalProbeStrikes
                )
            )

            // 回调更新账本
            onRecallLedgerUpdate?.invoke(updatedLedgerJson)

            ledgerWithEntry
        } else {
            // 无动作，清空上一轮试探观察
            val clearedLedger = updatedLedger.clearLastObservation()
            if (clearedLedger != updatedLedger) {
                val updatedLedgerJson = json.encodeToString(clearedLedger)
                onRecallLedgerUpdate?.invoke(updatedLedgerJson)
            }
            clearedLedger
        }

        // 阶段7：构建注入块
        val injectionTime = 0L  // 简化：不单独测量

        Log.i(TAG, "=== Building injection block ===")
        Log.i(TAG, "decisionResult.action: ${decisionResult.action}")
        Log.i(TAG, "decisionResult.selectedCandidate: ${decisionResult.selectedCandidate?.id}")
        Log.i(TAG, "decisionResult.vetoReason: ${decisionResult.vetoReason}")

        if (decisionResult.action != RecallAction.NONE && decisionResult.selectedCandidate != null) {
            Log.i(TAG, "=== Building injection block (action != NONE) ===")

            try {
                val injectionBlock = buildInjectionBlock(
                    candidate = decisionResult.selectedCandidate,
                    scores = scoredCandidates.firstOrNull { it.first == decisionResult.selectedCandidate }?.second,
                    action = decisionResult.action
                )

                Log.i(TAG, "=== Injection block built successfully, length: ${injectionBlock.length} ===")
                Log.i(TAG, "=== COMPLETE INJECTION BLOCK START ===")
                Log.i(TAG, injectionBlock)
                Log.i(TAG, "=== COMPLETE INJECTION BLOCK END ===")

                debugLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Recall injection block created",
                    mapOf(
                        "type" to decisionResult.action.name,
                        "charCount" to injectionBlock.length
                    )
                )

                // 记录总性能
                val totalTime = System.currentTimeMillis() - totalStartTime
                logPerformanceMetrics(
                    needGateTime = needGateTime,
                    pSourceTime = pSourceTime,
                    aSourceTime = aSourceTime,
                    scoringTime = scoringTime,
                    decisionTime = decisionTime,
                    injectionTime = injectionTime,
                    totalTime = totalTime
                )

                Log.i(TAG, "=== Returning injection block ===")
                return injectionBlock
            } catch (e: Exception) {
                Log.e(TAG, "=== ERROR building injection block: ${e.message} ===", e)
                throw e
            }
        }

        // 记录总性能（未召回的情况）
        val totalTime = System.currentTimeMillis() - totalStartTime
        Log.i(TAG, "=== Recall returning NULL (no recall) in ${totalTime}ms ===")
        logPerformanceMetrics(
            needGateTime = needGateTime,
            pSourceTime = pSourceTime,
            aSourceTime = aSourceTime,
            scoringTime = scoringTime,
            decisionTime = decisionTime,
            injectionTime = injectionTime,
            totalTime = totalTime
        )

        return null
    }

    /**
     * 记录性能指标
     */
    private fun logPerformanceMetrics(
        needGateTime: Long,
        pSourceTime: Long,
        aSourceTime: Long,
        scoringTime: Long,
        decisionTime: Long,
        injectionTime: Long,
        totalTime: Long
    ) {
        val debugLogger = DebugLogger.getInstance(context)

        val target = 600L
        val withinTarget = totalTime <= target

        val level = if (withinTarget) LogLevel.INFO else LogLevel.WARN

        debugLogger.log(
            level,
            TAG,
            "Recall performance summary",
            mapOf(
                "total" to "${totalTime}ms",
                "target" to "${target}ms",
                "withinTarget" to withinTarget,
                "needGate" to "${needGateTime}ms",
                "pSource" to "${pSourceTime}ms",
                "aSource" to "${aSourceTime}ms",
                "scoring" to "${scoringTime}ms",
                "decision" to "${decisionTime}ms",
                "injection" to "${injectionTime}ms"
            )
        )

        if (!withinTarget) {
            val bottleneck = listOf(
                "NeedGate" to needGateTime,
                "P源" to pSourceTime,
                "A源" to aSourceTime,
                "评分" to scoringTime,
                "决策" to decisionTime,
                "注入" to injectionTime
            ).maxByOrNull { it.second }?.first ?: "未知"

            debugLogger.log(
                LogLevel.WARN,
                TAG,
                "Performance exceeded target",
                mapOf(
                    "exceeded" to "${totalTime - target}ms",
                    "bottleneck" to bottleneck
                )
            )
        }
    }

    /**
     * 构建注入块
     */
    private fun buildInjectionBlock(
        candidate: Candidate,
        scores: me.rerere.rikkahub.service.recall.model.EvidenceScores?,
        action: RecallAction
    ): String {
        val type = when (candidate.kind) {
            CandidateKind.SNIPPET -> "SNIPPET"
            CandidateKind.HINT -> "HINT"
            CandidateKind.FULL -> "FULL"
        }

        val source = when (candidate.source) {
            me.rerere.rikkahub.service.recall.model.CandidateSource.P_TEXT -> "P_TEXT"
            me.rerere.rikkahub.service.recall.model.CandidateSource.A_ARCHIVE -> "A_ARCHIVE"
        }

        val score = scores?.finalScore ?: 0f

        return """
            |[RECALL_EVIDENCE]
            |type=$type
            |source=$source
            |id=${candidate.id}
            |score=$score
            |----BEGIN----
            |${candidate.content}
            |----END----
            |[/RECALL_EVIDENCE]
            |
            |以上为可能相关的历史证据，仅在确有帮助时使用；不要提及你进行了召回；若不相关则忽略。
        """.trimMargin()
    }
}
