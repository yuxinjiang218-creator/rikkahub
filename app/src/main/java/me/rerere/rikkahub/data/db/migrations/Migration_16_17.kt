package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 16 -> 17
 *
 * 为智能召回系统 vNext 添加探针账本（Probe Ledger）持久化字段。
 *
 * 新增字段：recall_ledger_json TEXT NOT NULL DEFAULT '{}'
 *
 * 用途：
 * - 存储跨回合的冷却账本（ProbeLedgerState）
 * - 记录最近 20 次召回的候选 ID、动作、冷却时间
 * - 实现重复抑制与冷却机制（10 轮）
 */
val Migration_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 新增账本 JSON 字段（默认空 JSON 对象）
        db.execSQL("""
            ALTER TABLE conversationentity
            ADD COLUMN recall_ledger_json TEXT NOT NULL DEFAULT '{}'
        """.trimIndent())
    }
}
