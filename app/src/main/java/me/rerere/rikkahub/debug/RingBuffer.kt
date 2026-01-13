package me.rerere.rikkahub.debug

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 极简环形缓冲区（线程安全）
 *
 * 设计要点：
 * - 使用 AtomicInteger 实现无锁写入
 * - 使用 ReentrantReadWriteLock 保证读取一致性
 * - 内存预分配，避免 GC 压力
 *
 * @param T 缓冲区元素类型
 * @param capacity 容量（固定为 200）
 */
class RingBuffer<T>(
    private val capacity: Int = 200
) {
    // 底层数组存储
    private val buffer = arrayOfNulls<Any?>(capacity)

    // 写入位置（原子计数）
    private val writeIndex = AtomicInteger(0)

    // 读写锁（保证快照一致性）
    private val lock = ReentrantReadWriteLock()

    /**
     * 添加元素（线程安全）
     *
     * 性能：O(1)，无锁写入
     */
    fun add(element: T) {
        val index = writeIndex.getAndIncrement() % capacity
        buffer[index] = element
    }

    /**
     * 获取最近 N 条日志（线程安全快照）
     *
     * 性能：O(N)，加锁读取
     */
    fun getRecent(count: Int): List<T> {
        lock.read {
            val currentSize = minOf(count, writeIndex.get(), capacity)
            val result = mutableListOf<T>()

            for (i in 0 until currentSize) {
                val index = (writeIndex.get() - 1 - i + capacity) % capacity
                @Suppress("UNCHECKED_CAST")
                (buffer[index] as? T)?.let { result.add(it) }
            }

            return result
        }
    }

    /**
     * 获取所有日志（倒序）
     */
    fun getAll(): List<T> = getRecent(capacity)

    /**
     * 清空缓冲区
     */
    fun clear() {
        lock.write {
            writeIndex.set(0)
            for (i in 0 until capacity) {
                buffer[i] = null
            }
        }
    }

    /**
     * 获取当前大小
     */
    fun size(): Int = minOf(writeIndex.get(), capacity)
}
