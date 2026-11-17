package com.testpilot.utils;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 项目级共享执行锁。按 projectId 互斥，同线程可重入。
 * <p>
 * 用于统一管理普通测试执行、覆盖率执行、测试优化流程之间的并发互斥：
 * <ul>
 *   <li>不同 projectId 可以并行执行；</li>
 *   <li>同一 projectId 同时只能有一个线程持有锁；</li>
 *   <li>同线程可重入（ReentrantLock），支持优化流程全程持锁期间内部调用 executeTests。</li>
 * </ul>
 * <p>
 * 实现说明：
 * <ul>
 *   <li>使用 {@link ConcurrentHashMap} 存储 projectId → {@link ReentrantLock} 映射；</li>
 *   <li>通过 {@code computeIfAbsent} 保证同一 projectId 始终对应同一把锁实例；</li>
 *   <li>不自动从 Map 中移除锁对象，避免锁释放与其他线程获取之间产生竞争，
 *       导致同一 projectId 出现两把不同的锁实例；</li>
 *   <li>通过 {@code tryLock()} 非阻塞获取，失败立即返回 false（调用方抛 409）。</li>
 * </ul>
 */
@Component
public class ProjectExecutionLock {

    /** projectId → ReentrantLock 映射。创建后常驻，不自动移除。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 非阻塞尝试获取项目锁。
     * <ul>
     *   <li>同一 projectId 首次调用成功返回 true；</li>
     *   <li>其他线程已持有该项目锁时返回 false；</li>
     *   <li>同线程已持有该项目锁时返回 true（重入，hold count 递增）。</li>
     * </ul>
     *
     * @param projectId 项目 ID，不允许 null 或 blank
     * @return true 表示获取成功；false 表示被其他线程持有
     * @throws IllegalArgumentException projectId 为 null 或 blank 时抛出
     */
    public boolean tryLock(String projectId) {
        validateProjectId(projectId);
        ReentrantLock lock = locks.computeIfAbsent(projectId, k -> new ReentrantLock());
        return lock.tryLock();
    }

    /**
     * 释放当前线程持有的项目锁。
     * <ul>
     *   <li>仅当当前线程持有该项目锁时才会递减 hold count；</li>
     *   <li>非持有线程调用无副作用（不会抛 {@link IllegalMonitorStateException}）；</li>
     *   <li>重入场景下需要对应次数的 unlock 才能真正释放。</li>
     * </ul>
     *
     * @param projectId 项目 ID，不允许 null 或 blank
     * @throws IllegalArgumentException projectId 为 null 或 blank 时抛出
     */
    public void unlock(String projectId) {
        validateProjectId(projectId);
        ReentrantLock lock = locks.get(projectId);
        if (lock == null) {
            return;
        }
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static void validateProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
    }
}
