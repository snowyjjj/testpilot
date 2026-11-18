package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.TestOptimizationRequest;
import com.testpilot.service.impl.TestExecutionServiceImpl;
import com.testpilot.service.impl.TestOptimizationServiceImpl;
import com.testpilot.utils.MavenProcessRunner;
import com.testpilot.utils.ProcessResult;
import com.testpilot.utils.ProjectExecutionLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨服务锁共享验证：TestExecutionServiceImpl 与 TestOptimizationServiceImpl
 * 注入同一个 {@link ProjectExecutionLock} 实例时，同一 projectId 的操作互相阻塞。
 * <p>
 * 使用 {@link CountDownLatch} 作为主要同步手段，避免依赖 {@code Thread.sleep}。
 */
class CrossServiceLockSharingTest {

    @TempDir
    Path tempDir;

    private ProjectExecutionLock sharedLock;
    private TestExecutionServiceImpl testService;
    private TestOptimizationServiceImpl optimizationService;
    private ProjectService projectService;
    private MavenProcessRunner processRunner;
    private TestExecutionService testExecutionService;
    private LLMService llmService;

    @BeforeEach
    void setUp() {
        sharedLock = new ProjectExecutionLock();
        projectService = mock(ProjectService.class);
        processRunner = mock(MavenProcessRunner.class);
        testExecutionService = mock(TestExecutionService.class);
        llmService = mock(LLMService.class);

        testService = new TestExecutionServiceImpl(projectService, processRunner, sharedLock);
        optimizationService = new TestOptimizationServiceImpl(
                projectService, testExecutionService, llmService, sharedLock);
    }

    private TestOptimizationRequest buildRequest() {
        TestOptimizationRequest req = new TestOptimizationRequest();
        req.setClassName("com.example.Calculator");
        req.setMethodName("add");
        req.setParamTypes(List.of("int", "int"));
        req.setMaxRetries(0);
        return req;
    }

    /**
     * 普通测试执行持锁期间，优化服务应被拒绝（409）。
     */
    @Test
    void testExecutionHoldingLock_blocksOptimization() throws Exception {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);

        CountDownLatch enteredRun = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenAnswer(inv -> {
                    enteredRun.countDown();
                    releaseRun.await(5, TimeUnit.SECONDS);
                    return new ProcessResult(0, "ok", "", false, 100L);
                });

        Thread t1 = new Thread(() -> testService.executeTests("p1"));
        t1.start();
        assertTrue(enteredRun.await(5, TimeUnit.SECONDS), "测试执行线程应已进入 Maven 调用并持锁");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> optimizationService.optimize("p1", buildRequest()));
        assertEquals(409, ex.getCode());

        releaseRun.countDown();
        t1.join(5000);
    }

    /**
     * 优化服务持锁期间，普通测试执行应被拒绝（409）。
     * 优化服务在 doOptimize 第一步 getMethodContext 处阻塞，锁仍被持有。
     */
    @Test
    void optimizationHoldingLock_blocksTestExecution() throws Exception {
        CountDownLatch enteredGetContext = new CountDownLatch(1);
        CountDownLatch releaseGetContext = new CountDownLatch(1);
        when(projectService.getMethodContext(eq("p1"), anyString(), anyString(), anyList()))
                .thenAnswer(inv -> {
                    enteredGetContext.countDown();
                    releaseGetContext.await(5, TimeUnit.SECONDS);
                    throw new RuntimeException("模拟优化流程内部异常以触发 finally 释放锁");
                });

        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                optimizationService.optimize("p1", buildRequest());
            } catch (Throwable e) {
                t1Error.set(e);
            }
        });
        t1.start();
        assertTrue(enteredGetContext.await(5, TimeUnit.SECONDS),
                "优化线程应已进入 doOptimize 并持锁");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> testService.executeTests("p1"));
        assertEquals(409, ex.getCode());

        releaseGetContext.countDown();
        t1.join(5000);

        // 验证异常后锁已释放：另一个线程应能成功获取同一 projectId 的锁
        // 注意：ReentrantLock 同一线程可重入，必须用独立线程验证
        verifyLockReleasedByAnotherThread("p1");
    }

    /**
     * 普通测试执行抛出异常时，finally 块必须释放锁。
     * 用独立线程验证，避免 ReentrantLock 同线程可重入造成的误判。
     */
    @Test
    void testExecutionException_releasesLock() throws Exception {
        when(projectService.getMavenProjectDir("p1")).thenReturn(tempDir);
        when(processRunner.run(eq(tempDir), any(String[].class), any(), anyInt()))
                .thenThrow(new RuntimeException("模拟 Maven 执行异常"));

        assertThrows(RuntimeException.class, () -> testService.executeTests("p1"));

        verifyLockReleasedByAnotherThread("p1");
    }

    /**
     * 优化服务抛出异常时，finally 块必须释放锁。
     * 用独立线程验证，避免 ReentrantLock 同线程可重入造成的误判。
     */
    @Test
    void optimizationException_releasesLock() throws Exception {
        when(projectService.getMethodContext(eq("p1"), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("模拟 getMethodContext 异常"));

        assertThrows(RuntimeException.class,
                () -> optimizationService.optimize("p1", buildRequest()));

        verifyLockReleasedByAnotherThread("p1");
    }

    /**
     * 启动另一个线程尝试获取指定 projectId 的锁，设置超时避免永久阻塞。
     * 成功获取后立即释放，由主线程通过断言确认结果。
     * <p>
     * 并发安全要点：
     * <ul>
     *   <li>checker 线程内部锁获取后 unlock 必须在 finally 中，防止锁泄露。</li>
     *   <li>主线程断言失败时，done.countDown() 必须执行以确保 checker 线程能退出。</li>
     *   <li>checker 线程异常通过 error AtomicReference 传递，主线程检测后抛出 AssertionError。</li>
     * </ul>
     */
    private void verifyLockReleasedByAnotherThread(String projectId) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread checker = new Thread(() -> {
            boolean locked = false;
            try {
                boolean ok = sharedLock.tryLock(projectId);
                result.set(ok);
                if (ok) {
                    locked = true;
                    acquired.countDown();
                    // 主线程确认后再释放；若被中断则恢复标志
                    try {
                        done.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (locked) {
                    sharedLock.unlock(projectId);
                }
            }
        });
        checker.start();

        boolean acquiredOk = acquired.await(5, TimeUnit.SECONDS);
        try {
            assertTrue(acquiredOk,
                    "另一个线程应在 5s 内成功获取锁（否则说明锁未被释放）");
            assertEquals(Boolean.TRUE, result.get(), "另一个线程 tryLock 应返回 true");
            if (error.get() != null) {
                throw new AssertionError("检查线程发生异常", error.get());
            }
        } finally {
            // 必须确保 checker 线程能退出，无论断言是否失败
            done.countDown();
            checker.join(5000);
        }
    }
}
