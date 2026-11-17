package com.testpilot.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectExecutionLock 单元测试。
 */
class ProjectExecutionLockTest {

    private ProjectExecutionLock lock;

    @BeforeEach
    void setUp() {
        lock = new ProjectExecutionLock();
    }

    @Test
    void tryLock_firstCall_succeeds() {
        assertTrue(lock.tryLock("p1"));
        lock.unlock("p1");
    }

    @Test
    void tryLock_concurrentDifferentThread_fails() throws Exception {
        AtomicBoolean secondSucceeded = new AtomicBoolean(true);
        CountDownLatch t1Acquired = new CountDownLatch(1);
        CountDownLatch t2Done = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            lock.tryLock("p1");
            t1Acquired.countDown();
            // 保持锁直到主线程确认
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            lock.unlock("p1");
        });
        t1.start();
        t1Acquired.await(1, TimeUnit.SECONDS);

        Thread t2 = new Thread(() -> {
            secondSucceeded.set(lock.tryLock("p1"));
            t2Done.countDown();
        });
        t2.start();
        t2Done.await(1, TimeUnit.SECONDS);
        t1.join();
        t2.join();

        assertFalse(secondSucceeded.get(), "第二个线程不应获取到已被持有的锁");
    }

    @Test
    void tryLock_reentrantSameThread_succeeds() {
        assertTrue(lock.tryLock("p1"));
        // 同线程重入，应成功
        assertTrue(lock.tryLock("p1"), "同线程应可重入");
        // 需要 unlock 两次才真正释放
        lock.unlock("p1");
        lock.unlock("p1");
    }

    @Test
    void tryLock_reentrant_holdCountTracked() throws Exception {
        // 获取两次
        assertTrue(lock.tryLock("p1"));
        assertTrue(lock.tryLock("p1"));

        AtomicBoolean otherThreadGotLock = new AtomicBoolean(true);
        CountDownLatch t1 = new CountDownLatch(1);
        CountDownLatch t2 = new CountDownLatch(1);

        // 先 unlock 一次，锁仍被持有（hold count = 1）
        lock.unlock("p1");

        Thread other = new Thread(() -> {
            otherThreadGotLock.set(lock.tryLock("p1"));
            t2.countDown();
        });
        other.start();
        t2.await(1, TimeUnit.SECONDS);
        other.join();

        assertFalse(otherThreadGotLock.get(), "hold count=1 时其他线程不应获取到锁");

        // 再 unlock 一次，hold count = 0，真正释放
        lock.unlock("p1");

        CountDownLatch t3 = new CountDownLatch(1);
        AtomicBoolean thirdSucceeded = new AtomicBoolean(false);
        Thread another = new Thread(() -> {
            thirdSucceeded.set(lock.tryLock("p1"));
            t3.countDown();
        });
        another.start();
        t3.await(1, TimeUnit.SECONDS);
        another.join();

        assertTrue(thirdSucceeded.get(), "hold count=0 后其他线程应能获取锁");
        lock.unlock("p1");
    }

    @Test
    void tryLock_differentProjects_bothSucceed() {
        assertTrue(lock.tryLock("p1"));
        assertTrue(lock.tryLock("p2"), "不同 projectId 应互不影响");
        lock.unlock("p1");
        lock.unlock("p2");
    }

    @Test
    void unlock_notHeldByCurrentThread_noException() throws Exception {
        // 线程 A 获取锁
        CountDownLatch a1 = new CountDownLatch(1);
        CountDownLatch a2 = new CountDownLatch(1);
        Thread a = new Thread(() -> {
            lock.tryLock("p1");
            a1.countDown();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            lock.unlock("p1");
            a2.countDown();
        });
        a.start();
        a1.await(1, TimeUnit.SECONDS);

        // 主线程尝试 unlock 不是自己持有的锁，不应抛异常
        assertDoesNotThrow(() -> lock.unlock("p1"));

        a.join();
        a2.await(1, TimeUnit.SECONDS);
    }

    @Test
    void unlock_nullOrBlankProjectId_throws() {
        assertThrows(IllegalArgumentException.class, () -> lock.tryLock(null));
        assertThrows(IllegalArgumentException.class, () -> lock.tryLock(""));
        assertThrows(IllegalArgumentException.class, () -> lock.unlock(null));
        assertThrows(IllegalArgumentException.class, () -> lock.unlock(""));
    }

    @Test
    void exceptionReleasesLock() {
        lock.tryLock("p1");
        // 模拟业务抛出异常，finally 中 unlock
        try {
            throw new RuntimeException("test");
        } catch (RuntimeException e) {
            // finally 模拟
        } finally {
            lock.unlock("p1");
        }
        // 释放后可再次获取
        assertTrue(lock.tryLock("p1"));
        lock.unlock("p1");
    }

    @Test
    void lockObjectNotAutoRemoved_fromMapAfterUnlock() throws Exception {
        // 获取并释放
        assertTrue(lock.tryLock("p1"));
        lock.unlock("p1");

        // 再次获取应使用同一把锁（computeIfAbsent 复用同一实例）
        assertTrue(lock.tryLock("p1"));

        // 验证不同 projectId 锁对象独立
        assertTrue(lock.tryLock("p2"));

        // 清理
        lock.unlock("p1");
        lock.unlock("p2");
    }

    /**
     * 同线程重入模拟 optimize 场景：外层持锁，内层 executeTests 重入。
     */
    @Test
    void reentrant_outerOptimizeInnerExecuteTests() throws Exception {
        // 外层：optimize 获取锁
        assertTrue(lock.tryLock("p1"), "optimize 外层应获取锁成功");

        // 内层：executeTests 同线程重入
        assertTrue(lock.tryLock("p1"), "同线程 executeTests 应重入成功");

        // 内层释放
        lock.unlock("p1");

        // 模拟中间业务执行
        // ... LLM.call(), updateTestFile() ...

        // 内层再次重入（重新 executeTests）
        assertTrue(lock.tryLock("p1"), "再次 executeTests 应重入成功");
        lock.unlock("p1");

        // 外层释放
        lock.unlock("p1");

        // 其他线程此时应能获取锁
        AtomicBoolean otherGot = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            otherGot.set(lock.tryLock("p1"));
            done.countDown();
        });
        other.start();
        done.await(1, TimeUnit.SECONDS);
        other.join();

        assertTrue(otherGot.get(), "外层释放后其他线程应能获取锁");
        lock.unlock("p1");
    }

    /**
     * 外层释放前，其他线程无法获取锁（即使内层已释放）。
     */
    @Test
    void outerHoldsLock_innerRelease_doesNotFree() throws Exception {
        // 外层获取
        assertTrue(lock.tryLock("p1"));
        // 内层重入
        assertTrue(lock.tryLock("p1"));
        // 内层释放（hold count = 1）
        lock.unlock("p1");

        AtomicBoolean otherGot = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            otherGot.set(lock.tryLock("p1"));
            done.countDown();
        });
        other.start();
        done.await(1, TimeUnit.SECONDS);
        other.join();

        assertFalse(otherGot.get(), "外层仍持有锁时，其他线程不应获取到");

        // 外层释放
        lock.unlock("p1");

        // 现在其他线程可以获取
        AtomicBoolean thirdGot = new AtomicBoolean(false);
        CountDownLatch done2 = new CountDownLatch(1);
        Thread third = new Thread(() -> {
            thirdGot.set(lock.tryLock("p1"));
            done2.countDown();
        });
        third.start();
        done2.await(1, TimeUnit.SECONDS);
        third.join();

        assertTrue(thirdGot.get());
        lock.unlock("p1");
    }
}
