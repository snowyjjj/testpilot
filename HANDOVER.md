# TestPilot 项目交接文件

> 交接日期：2026-11-22（阶段 8 已完成并归档）
> 仓库根目录：`D:\Program Files\JetBrains\TestPilot`
> 远端仓库：GitHub `https://github.com/snowyjjj/testpilot.git`
> 当前分支：`main`（领先 `origin/main` 2 个提交，尚未 push）
> 当前 HEAD：`62461b8 test(phase8): 增加并发锁冲突 HTTP 集成测试`
> 全量测试：`329 run / 0 failures / 0 errors / 10 skipped / BUILD SUCCESS`

---

## 一、项目概述

TestPilot 是一个基于大语言模型（阿里云百炼 / DashScope，qwen-plus）的 **Java 单元测试生成与优化平台**（本科毕业设计）。接收用户上传的 Java Maven 项目 ZIP，自动完成：源码解析 → LLM 生成测试 → Maven 编译执行 → 失败反馈 LLM 修复 → JaCoCo 覆盖率分析 → 测试质量评分与改进建议。

- 后端单体应用，无前端、无认证、无 CORS 配置（本地演示用）。
- 纯文件系统存储，无数据库。
- 对外仅 REST API，统一响应体 `Result<T>`（`code` / `message` / `data`）。

---

## 二、技术栈与环境要求

| 项 | 版本 / 说明 |
|----|------------|
| Java | 17 |
| Spring Boot | 3.3.5 |
| 构建工具 | Maven（Windows 下用 `mvn.cmd`） |
| 核心依赖 | spring-boot-starter-web / validation / actuator、Lombok、javaparser-core 3.26.2、spring-boot-starter-test（test scope） |
| LLM | 阿里云百炼（兼容 OpenAI 接口），模型 `qwen-plus` |
| 覆盖率 | JaCoCo 0.8.11（命令行调用，**不修改用户上传的 pom.xml**） |
| 测试报告 | Surefire（解析 `.txt` 汇总与 `TEST-*.xml`） |

**运行必需环境变量**：`DASHSCOPE_API_KEY`（百炼 API Key）。无此变量时集成测试仍可运行（LLM 被 Mock）。

---

## 三、构建与运行

```powershell
# 编译
mvn clean compile

# 全量测试（注意：不要用 -q，会抑制 surefire 汇总行）
mvn clean test

# 单独跑某个测试类
mvn.cmd -Dtest=SystemFlowIntegrationTest test

# 启动应用（需先设置 DASHSCOPE_API_KEY）
$env:DASHSCOPE_API_KEY="你的key"
mvn spring-boot:run
```

默认端口 `8080`，健康检查 `GET /actuator/health`。

---

## 四、功能阶段总览

| 阶段 | 功能 | 核心类 |
|------|------|--------|
| 1 | 项目上传与源码解析 | `ProjectServiceImpl`、`ProjectController` |
| 2 | LLM 生成单元测试 | `TestGenerationServiceImpl`、`BailianLLMServiceImpl`、`PromptBuilder` |
| 3 | Maven 自动编译与执行 | `TestExecutionServiceImpl`、`DefaultMavenProcessRunner`、`SurefireReportParser` |
| 4 | 执行反馈优化测试 | `TestOptimizationServiceImpl`、`ExecutionFeedbackExtractor`、`TestFileManager` |
| 5 | JaCoCo 覆盖率分析 | `CoverageServiceImpl`、`JacocoReportParser`、`CoverageController` |
| 6.1 | 测试代码静态质量分析 | `TestQualityAnalyzer` |
| 6.2 | 执行结果 + 覆盖率整合 | `TestQualityServiceImpl`、`TestQualityResult` |
| 6.3 | 质量评分与改进建议 | `QualityScorer`、`QualityReport`、`QualityDimension`、`QualityIssue` |
| 7 | 系统级验收前置审查 | （只读审查，无代码产物） |
| 8 | 系统级集成测试（✅ 已完成） | `SystemFlowIntegrationTest`（用例 A + B + C + D）、`IntegrationTestFixtures` |

---

## 五、REST API 清单

统一前缀 `/api/projects`，响应体 `Result<T>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload` | 上传 ZIP（multipart，字段名 `file`） |
| POST | `/{projectId}/parse` | 解析源码（类/方法/参数） |
| POST | `/{projectId}/tests/generate` | LLM 生成测试（`TestGenerationRequest`：className/methodName/paramTypes） |
| POST | `/{projectId}/tests/execute` | 执行 Maven 测试，返回 `TestExecutionResult` |
| POST | `/{projectId}/tests/optimize` | 优化闭环（`TestOptimizationRequest`：+maxRetries≤2），返回 `TestOptimizationResult` |
| POST | `/{projectId}/coverage` | JaCoCo 覆盖率分析 |
| POST | `/{projectId}/quality` | 质量评估（静态统计 + 覆盖率 + 评分报告） |

业务异常由 `GlobalExceptionHandler` 统一处理（400/404/409/500）。

---

## 六、关键设计（接手前必读）

### 6.1 并发锁
- `ProjectExecutionLock`：Spring 单例，底层 `ReentrantLock`，按 projectId 隔离，**支持同线程重入**。测试执行 / 测试优化 / 覆盖率分析 / 质量评估共用同一把锁；锁被占用时第二个请求返回 **HTTP 409**。
- 锁冲突统一抛 `BusinessException(409, ...)`，由 `GlobalExceptionHandler` 映射为 HTTP 409；文案区分入口：执行 / 覆盖率「该项目正在执行测试，请稍后重试」、优化「该项目正在执行测试优化，请稍后重试」、质量评估「该项目正在执行其他操作，请稍后重试」。集成用例 D 对这些文案做**精确断言**，避免优化 / 质量评估的内层执行锁 409 冒充入口外层锁。
- `TestGenerationServiceImpl` 在**真正写文件前**才获取锁（不让 LLM 调用持锁）。
- `ProjectServiceImpl.ensureExtracted()` 首次解压：per-projectId 阻塞锁 + 双重检查 + 临时目录 `ATOMIC_MOVE` 原子移交，失败清理临时目录。

### 6.2 路径与文件安全
- `TestFileManager.assertPathWithinProject()`：写入前 + 原子移动后双重校验路径边界。
- 解压 Zip Slip 防护；符号链接路径链校验（Windows 下 `Files.createSymbolicLink()` 常静默失败，相关测试用 JUnit Assumptions 跳过）。
- 测试文件写入：临时文件（`.repair-`/`.restore-` 前缀）+ `ATOMIC_MOVE` 原子替换。
- `TestGenerationServiceImpl.saveTestFile()` 拒绝覆盖已存在测试文件（409）。

### 6.3 测试执行状态推导（`TestExecutionServiceImpl.buildTestResult`）
判定顺序：`TIMEOUT` → `MAVEN_ERROR`(exitCode=-1) → **`COMPILE_ERROR`(exitCode≠0 且无有效 surefire 报告)** → `FAILED` → `NO_REPORTS` → `PASSED`。
每次执行前 `cleanSurefireReports()` 清空旧报告，避免读到陈旧结果。

### 6.4 编译错误来源分类（`ExecutionFeedbackExtractor`）
优先级：DEPENDENCY（依赖关键词）→ PLUGIN（非 compiler 插件失败）→ 逐行匹配 `[ERROR] .../X.java:[line,col] msg`。
仅当错误路径含 `/src/test/java/` 且文件名 == 生成的测试文件名时判为 **TEST_SOURCE**，只有 TEST_SOURCE 才允许 LLM 自动修复；MAIN_SOURCE / DEPENDENCY / PLUGIN / UNKNOWN 均提前返回不修复。

### 6.5 质量评分模型（`QualityScorer`，纯计算、不依赖 Spring）
- 四维度：EFFECTIVENESS(0.30)、COVERAGE(0.25)、SUFFICIENCY(0.20)、CORRECTNESS(0.25)。
- 覆盖率权重：LINE 0.50 / BRANCH 0.30 / METHOD 0.20。
- 综合分条件：**≥2 个可评维度 且 ≥1 个运行时维度（有效性或覆盖率）可评**，否则 `overallScore=null`、`grade="N/A"`。
- 不可用数据 = `available=false, score=null`（不可评），与真实 0 分严格区分。
- 改进建议 10 类：EMPTY_TEST / NO_ASSERTION / SKIPPED_TEST / PARSE_FAILED / LOW_LINE(<60%) / LOW_BRANCH(<30%) / TEST_FAILED / COMPILE_ERROR / LOW_METHOD_DENSITY / LOW_ASSERTION_DENSITY。`analysisError != null` 时不生成静态建议。

### 6.6 存储
默认 `storage/projects`（ZIP）与 `storage/extracted`（解压目录），`storage/` 已被 `.gitignore` 忽略。集成测试通过 `@DynamicPropertySource` 重定向到独立临时目录，不污染默认存储。

---

## 七、测试情况

### 7.1 全量基线

**当前基线**：`mvn -o -B test` → **329 run / 0 failures / 0 errors / 10 skipped / BUILD SUCCESS**（在提交 `62461b8` 上实测）。

- 集成测试 `SystemFlowIntegrationTest`（`@SpringBootTest + @AutoConfigureMockMvc`，仅 Mock `LLMService` 与 `MavenProcessRunner`）：
  - 基础设施验证（通过反射确认 `ProjectServiceImpl` 注入的存储路径指向临时目录；实测默认 `storage/` 未被写入）。
  - **用例 A**：完整业务链路（上传→解析→生成→执行→覆盖率→质量评估），含确定性评分断言（EFFECTIVENESS 100 / COVERAGE 67.5 / SUFFICIENCY 30 / CORRECTNESS 100 / overallScore 77.875 / grade B）。
  - **用例 B**：编译错误修复闭环（生成含 `multiply` 错误调用 → 首次 Maven exitCode=1 不写报告 → 真实推导 COMPILE_ERROR → 反馈判 TEST_SOURCE → LLM 修复 → 二次执行 PASSED），含 LLM/Maven 各恰好 2 次调用、第二次 prompt 含首次编译错误、业务源码与 pom 未变等断言。
  - **用例 C**：MAIN_SOURCE 编译错误不得自动修复（真实执行 + 真实反馈分类 → `retryCount=0`、生成阶段之外零 LLM、测试文件 / 业务源码 / pom 字节不变、测试目录清单不变）。
  - **用例 D**：并发锁冲突 409 与零副作用（独立 holder 线程持锁 → execute / coverage / optimize / quality 四入口均返回 HTTP 409 且**精确文案**匹配、无 `data`；Maven / LLM 零调用、无 surefire / jacoco 报告、三文件字节与测试目录清单不变；释放锁后正向执行 200 / PASSED / success=true）。
- 10 个 skipped 均为 Windows 符号链接假设型单元测试（`TestFileManagerTest` 3、`SurefireReportParserTest` 2、`TestExecutionServiceImplTest` 2、`TestQualityAnalyzerTest` 2、`TestGenerationServiceImplTest` 1），与近期改动无关。

### 7.2 阶段 8 交付记录（2026-11-22，✅ 已完成）

阶段 8（系统级集成测试）已完成交付与只读评审（结论 **Approve with Suggestions**）；评审提出的唯一建议 M1（409 文案只做“包含”断言，无法证明 409 来自入口自身外层锁）已修复并复验。

| 用例 | 覆盖内容 | 提交 |
|------|----------|------|
| 基础设施 | 临时存储重定向；默认 `storage/` 未被创建或污染 | `2759def` |
| A | 完整业务链路：上传 → 解析 → 生成 → 执行 → 覆盖率 → 质量评估（确定性评分断言） | `2759def` |
| B | TEST_SOURCE 编译错误修复闭环：真实执行 → 真实反馈分类 → LLM 修复 → 二次执行 PASSED | `2759def` |
| C | MAIN_SOURCE 编译错误不得自动修复：`retryCount=0`、零 LLM、测试文件 / 业务源码 / pom 字节与目录清单不变 | `d5ba7e5` |
| D | 并发锁冲突与零副作用：四入口真实 HTTP 409（精确文案）、Maven / LLM 零调用、无报告产物、文件零改动；释放后正向执行 200 / PASSED | `62461b8` |

对应测试方法：`contextStarts_withTempStorageRedirected_andNoDefaultStoragePollution`（基础设施）、`fullBusinessFlow_upload_parse_generate_execute_coverage_quality`（A）、`optimizationClosedLoop_compileErrorThenRepair_passes`（B）、`optimization_initialMainSourceCompileError_doesNotCallLlmOrTouchFiles`（C）、`concurrentLockConflict_returns409ForFourEndpoints_withoutSideEffects`（D）。

| 项 | 内容 |
|----|------|
| 交付文件 | `src/test/java/com/testpilot/integration/SystemFlowIntegrationTest.java`（1010 行）、`IntegrationTestFixtures.java`（290 行） |
| 提交（A/B） | `2759def test(phase8): 新增真实容器级系统链路集成测试`（2 files changed, +917） |
| 提交（C） | `d5ba7e5 test(phase8): 增加 MAIN_SOURCE 不触发自动修复集成测试`（2 files changed, +231） |
| 提交（D） | `62461b8 test(phase8): 增加并发锁冲突 HTTP 集成测试`（1 file changed, +184） |
| 推送 | `2759def` 已推送；`d5ba7e5`、`62461b8` 仍仅在本地（`origin/main` = `b601c93`），待 push |
| 全量验证 | `mvn -o -B test` → 329 run / 0 failures / 0 errors / 10 skipped，BUILD SUCCESS；集成测试类 5/5；用例 D 单独重复 5 次全绿 |
| Mock 边界 | 仅 `LLMService`、`MavenProcessRunner` 被 `@MockBean`；Controller / Service / `ProjectExecutionLock` / `SurefireReportParser` / `JacocoReportParser` / `TestQualityAnalyzer` / `QualityScorer` / `PromptBuilder` / `TestFileManager` / 文件系统全部真实 |
| 隔离实测 | `storage/projects` 运行前后均为 6 个文件；`storage/extracted` 无新增目录；`%TEMP%\testpilot-it-*` 运行后 0 个 |
| D 关键设计 | holder 由**独立线程**持锁（MockMvc 在测试线程同步执行，若测试线程自持 `ReentrantLock` 会重入导致假绿）；全程 `CountDownLatch` 同步、无 sleep；`finally` 中 `release.countDown()` + `join` + `holder.isAlive()` 断言，异常经 `AtomicReference` 传递 |
| D 精确文案 | execute / coverage：`该项目正在执行测试，请稍后重试`；optimize：`该项目正在执行测试优化，请稍后重试`；quality：`该项目正在执行其他操作，请稍后重试`（精确匹配，防止内层执行锁 409 冒充外层锁） |
| 提交范围 | 仅测试文件；`TestClassStat.java`（行尾假象）与 `HANDOVER.md` 均未混入提交 |

**阶段 8 遗留加强项（评审记录，未实施，不阻塞）**：

1. Maven 调用契约（`args` goals / `timeout` / `maxLogLength`）尚未断言，建议用 `ArgumentCaptor<String[]>` + `verify(times(n))` 固化。
2. 反向与异常路径仍有缺口：FAILED（断言失败）修复闭环、超时 / MAVEN_ERROR 分支的集成级验证。
3. 旧报告清理护栏未验证（未预置脏 surefire / jacoco 产物）。
4. 首轮生成 prompt 内容未断言（`ArgumentCaptor` 目前仅用于修复 prompt）。
5. `readJson` 建议显式 `getContentAsString(StandardCharsets.UTF_8)`，避免 Mock 响应默认按 ISO-8859-1 解码。
6. 跨 `projectId` 隔离（D7）、跨服务锁组合（P2）与部分单测中的 `Thread.sleep` → latch 重构（P3）尚未实施。

---

## 八、当前 Git 状态

```
## main...origin/main                     （ahead 2 / behind 0，尚未 push）
 M src/main/java/com/testpilot/dto/TestClassStat.java    ← 纯 CRLF/LF 行尾假象，无实际内容差异，勿提交
 M HANDOVER.md                                          ← 本次文档更新，需单独提交
```

- 阶段 8 已完成提交：`2759def`（已推送）、`d5ba7e5`、`62461b8`（本地未推送）；`origin/main` 仍为 `b601c93 docs: 更新阶段 8 交接文档`。
- 工作区唯一的源码"修改"是 `TestClassStat.java` 的行尾假象：`git hash-object` 与 `git rev-parse HEAD:src/main/java/com/testpilot/dto/TestClassStat.java` 均为 `d52b300c92f6d948901cb50b72bb2f1e8b0b93cd`，`git diff` 无内容差异，**不要提交，也不要对其做 EOL 归一化**。
- `HANDOVER.md` 已纳入版本控制（`b601c93` 引入）；本次更新请单独 `git add HANDOVER.md` 并提交，勿与源码改动混在一起。
- 未切换分支、未改写历史；如需同步远端，执行 `git push origin main`（将包含 `d5ba7e5`、`62461b8`）。

---

## 九、已知限制与风险

1. 无认证 / 无鉴权，接口对局域网开放，仅适合本地演示。
2. 在用户机器上真实执行 Maven（运行不可信项目有安全风险，生产需容器隔离）。
3. 静态统计依赖 JavaParser，不解析符号（无 SymbolSolver），AssertJ 等断言风格统计有限。
4. SUFFICIENCY 密度分为启发式，不代表对业务代码的绝对充分性判断。
5. LLM 输出质量取决于模型与 prompt，修复不保证一次成功（maxRetries 上限 2）。
6. `storage/`、`target/`、`app.log`/`app2.log` 为运行产物，不入库。
7. 集成测试中 `MavenProcessRunner` 与 `LLMService` 均为 Mock，不覆盖真实 `mvn.cmd` 调用、真实编译/覆盖率产物格式；真实 Maven 端到端仍需人工验收。

---

## 十、建议后续工作

1. 推送阶段 8 剩余提交：`git push origin main`（本地领先 2 个提交：`d5ba7e5`、`62461b8`）。
2. 补强阶段 8 集成测试断言：Maven 调用契约（`args` / `timeout` / `maxLogLength`）、首轮生成 prompt 内容、脏报告预置断言、`issues` / `reliabilityWarning` 断言。
3. 补充仍缺失的反向与异常路径：FAILED（断言失败）修复闭环、超时 / MAVEN_ERROR、跨 projectId 并发隔离（D7）。
4. 用 Postman 对阶段 8 用例 A/B/C/D 做端到端人工验收（仓库含 `.postman` / `postman` 目录）。
5. 如需部署，补充认证与执行沙箱。

---

## 十一、关键文件索引

| 类别 | 文件 |
|------|------|
| 入口 | `src/main/java/com/testpilot/TestPilotApplication.java` |
| 配置 | `src/main/resources/application.yml`、`pom.xml` |
| 锁 | `src/main/java/com/testpilot/utils/ProjectExecutionLock.java` |
| 优化闭环 | `service/impl/TestOptimizationServiceImpl.java`、`utils/ExecutionFeedbackExtractor.java`、`utils/PromptBuilder.java`、`utils/TestFileManager.java` |
| 执行 | `service/impl/TestExecutionServiceImpl.java`、`utils/SurefireReportParser.java`、`utils/MavenProcessRunner.java` |
| 覆盖率 | `service/impl/CoverageServiceImpl.java`、`utils/JacocoReportParser.java` |
| 质量 | `utils/TestQualityAnalyzer.java`、`utils/QualityScorer.java`、`service/impl/TestQualityServiceImpl.java` |
| 集成测试 | `src/test/java/com/testpilot/integration/SystemFlowIntegrationTest.java`、`IntegrationTestFixtures.java` |
