package com.testpilot.utils;

import com.testpilot.dto.FailureFeedback;
import com.testpilot.dto.FailureFeedback.CompileErrorSource;
import com.testpilot.dto.TestExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ExecutionFeedbackExtractor 单元测试。
 * 使用真实 Maven 日志格式验证编译错误来源分类。
 */
class ExecutionFeedbackExtractorTest {

    private static final String TEST_FILE_NAME = "CalculatorTest.java";

    private TestExecutionResult compileResult(String output) {
        return new TestExecutionResult("pid", false, "COMPILE_ERROR", 1,
                0, 0, 0, 0, 1000L, output, "编译失败");
    }

    /**
     * 真实 Maven 日志：maven-compiler-plugin 编译 TestPilot 生成的测试文件失败。
     * 应识别为 TEST_SOURCE。
     */
    @Test
    void extract_compilerPluginTestSource_classifiedAsTestSource() {
        String output = """
                [INFO] --- maven-compiler-plugin:3.11.0:testCompile (default-testCompile) @ demo ---
                [INFO] Changes detected - recompiling the module!
                [INFO] Compiling 1 source file to /home/user/project/target/test-classes
                [INFO] -------------------------------------------------------------
                [ERROR] COMPILATION ERROR :
                [INFO] -------------------------------------------------------------
                [ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol
                [ERROR]   symbol:   class Foo
                [ERROR]   location: class com.example.CalculatorTest
                [INFO] 1 error
                [INFO] -------------------------------------------------------------
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:testCompile (default-testCompile) on project demo: Compilation failure
                [ERROR]
                [ERROR] -> [Help 1]
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(FailureFeedback.FailureType.COMPILE, fb.getFailureType());
        assertEquals(CompileErrorSource.TEST_SOURCE, fb.getCompileErrorSource());
    }

    /**
     * 真实 Maven 日志：maven-compiler-plugin 编译业务源码（src/main/java）失败。
     * 应识别为 MAIN_SOURCE，不允许 LLM 修复。
     */
    @Test
    void extract_compilerPluginMainSource_classifiedAsMainSource() {
        String output = """
                [INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ demo ---
                [INFO] Changes detected - recompiling the module!
                [INFO] Compiling 2 source files to /home/user/project/target/classes
                [INFO] -------------------------------------------------------------
                [ERROR] COMPILATION ERROR :
                [INFO] -------------------------------------------------------------
                [ERROR] /home/user/project/src/main/java/com/example/Calculator.java:[5,8] ';' expected
                [ERROR]   symbol:   class Calculator
                [ERROR]   location: package com.example
                [INFO] 1 error
                [INFO] -------------------------------------------------------------
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project demo: Compilation failure
                [ERROR]
                [ERROR] -> [Help 1]
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(CompileErrorSource.MAIN_SOURCE, fb.getCompileErrorSource());
    }

    /**
     * 真实 Maven 日志：maven-surefire-plugin 执行失败（非 compiler 插件）。
     * 应识别为 PLUGIN，不允许 LLM 修复。
     */
    @Test
    void extract_surefirePluginFailure_classifiedAsPlugin() {
        String output = """
                [INFO] --- maven-surefire-plugin:3.0.0:test (default-test) @ demo ---
                [INFO]
                [INFO] -------------------------------------------------------
                [INFO]  T E S T S
                [INFO] -------------------------------------------------------
                [INFO]
                [INFO] Results:
                [INFO]
                [INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [INFO]
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.0.0:test (default-test) on project demo: There are test failures.
                [ERROR]
                [ERROR] -> [Help 1]
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(CompileErrorSource.PLUGIN, fb.getCompileErrorSource());
    }

    /**
     * 真实 Maven 日志：依赖解析失败。
     * 应识别为 DEPENDENCY，不允许 LLM 修复。
     */
    @Test
    void extract_dependencyResolutionFailure_classifiedAsDependency() {
        String output = """
                [INFO] Scanning for projects...
                [INFO]
                [INFO] ---------------------< com.example:demo >---------------------
                [INFO] Building demo 1.0
                [INFO] --------------------------------[ jar ]---------------------------------
                [INFO]
                [INFO] --- maven-resources-plugin:3.3.1:resources (default-resources) @ demo ---
                [INFO] Copying 0 resource
                [INFO]
                [INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ demo ---
                [INFO] Changes detected - recompiling the module!
                [WARNING] The POM for com.example:missing-lib:jar:1.0 is missing, no dependency information available
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] Failed to execute goal on project demo: Could not resolve dependencies for project com.example:demo:jar:1.0: Could not find artifact com.example:missing-lib:jar:1.0
                [ERROR]
                [ERROR] -> [Help 1]
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(CompileErrorSource.DEPENDENCY, fb.getCompileErrorSource());
    }

    /**
     * 真实 Maven 日志：同时存在测试源码和业务源码编译错误。
     * 应识别为 MAIN_SOURCE（业务源码错误优先，不允许 LLM 修复）。
     */
    @Test
    void extract_mixedTestAndMainSourceErrors_classifiedAsMainSource() {
        String output = """
                [INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ demo ---
                [INFO] Compiling 2 source files
                [ERROR] /home/user/project/src/main/java/com/example/Calculator.java:[5,8] ';' expected
                [INFO] --- maven-compiler-plugin:3.11.0:testCompile (default-testCompile) @ demo ---
                [INFO] Compiling 1 source file
                [ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:testCompile (default-testCompile) on project demo: Compilation failure
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        // 业务源码错误优先，不允许修复
        assertEquals(CompileErrorSource.MAIN_SOURCE, fb.getCompileErrorSource());
    }

    /**
     * 无法识别的编译错误（没有明确的文件路径和行号）。
     * 应识别为 UNKNOWN，不允许 LLM 修复。
     */
    @Test
    void extract_unrecognizedCompileError_classifiedAsUnknown() {
        String output = """
                [INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ demo ---
                [INFO] Compiling 2 source files
                [ERROR] Some unexpected compilation error without file location
                [ERROR] ANOTHER ERROR LINE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project demo: Compilation failure
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(CompileErrorSource.UNKNOWN, fb.getCompileErrorSource());
    }

    /**
     * maven-compiler-plugin 错误不应被误判为 PLUGIN。
     * 即使有 "Failed to execute goal ... maven-compiler-plugin" 行，
     * 只要错误来自测试文件，就应识别为 TEST_SOURCE。
     */
    @Test
    void extract_compilerPluginError_notMisclassifiedAsPlugin() {
        String output = """
                [ERROR] /home/user/project/src/test/java/com/example/CalculatorTest.java:[10,13] cannot find symbol
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:testCompile (default-testCompile) on project demo: Compilation failure
                """;

        FailureFeedback fb = ExecutionFeedbackExtractor.extract(
                compileResult(output), Path.of("target/surefire-reports"), TEST_FILE_NAME);

        assertNotNull(fb);
        assertEquals(CompileErrorSource.TEST_SOURCE, fb.getCompileErrorSource());
    }
}
