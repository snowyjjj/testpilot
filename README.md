# TestPilot

面向 Java Maven 项目的 AI 单元测试助手：**上传项目 → 解析源码 → LLM 生成单元测试 → 真实 Maven 编译执行 → 基于执行反馈自动优化 → JaCoCo 覆盖率 → 测试质量评分**。

> 当前形态：**单体后端 Demo，暂无前端**。全部能力通过 HTTP API 暴露，演示入口是 Postman 集合 [`postman/TestPilot-Demo.postman_collection.json`](postman/TestPilot-Demo.postman_collection.json)。

---

## 1. 项目简介

TestPilot 接收一个 Maven 项目的 ZIP 包，在服务端解压后完成以下闭环：

| # | 能力 | 实现要点 |
|---|------|----------|
| 1 | 项目上传 | 校验 ZIP 魔数 / 条目数 / 解压体积 / 根目录 pom.xml；解压到独立目录，先写临时目录再原子移交 |
| 2 | Java 源码解析 | JavaParser AST 解析包名、类、方法签名与参数类型，解析失败的文件单独记录 |
| 3 | 单元测试生成 | 由 pom.xml 判定 JUnit 4/5，构建 Prompt 调用 LLM，提取代码块并做静态校验后原子写盘 |
| 4 | 测试编译与执行 | 可控子进程执行 `mvn -B test`：输出重定向到临时文件避免管道死锁、绝对截止时间、超时杀进程树、单流输出上限 10 MiB |
| 5 | 反馈驱动优化 | 解析 Surefire 报告与编译错误，定位「测试源码错误」，让 LLM 修复后重跑；修复后校验 `@Test` 与断言数量不减少、不得出现 `@Disabled`/`@Ignore`，失败则回滚 |
| 6 | 覆盖率分析 | 以命令行方式调用 JaCoCo 插件（不改用户 pom），执行 `prepare-agent test` + `report`，解析 `jacoco.xml` |
| 7 | 质量评分 | 测试执行 + 覆盖率 + AST 静态统计三路数据合成 4 个维度（有效性 / 覆盖率 / 充分性 / 规范性）加权评分与改进建议 |

设计上刻意区分「不可评」与「0 分」：覆盖率不可用时 `percentage=null`、维度 `available=false`、综合分 `null` 且等级 `N/A`。

> 项目自身回归测试基线：最近一次完整 `mvn test` 为 **410 个用例，0 失败，0 错误，10 跳过**。

---

## 2. 技术栈

| 分类 | 选型 |
|------|------|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.3.5（Web / Validation / Actuator） |
| 源码分析 | JavaParser 3.26.2 |
| 覆盖率 | JaCoCo 0.8.11（命令行调用，**不修改**被测项目 pom.xml） |
| 大模型 | 阿里云百炼 DashScope OpenAI 兼容接口，默认模型 `qwen-plus` |
| 构建 | Maven（对被测项目执行本机 `mvn` 子进程） |
| 存储 | 本地文件系统（无数据库） |
| 测试 | JUnit 5 + Mockito + `MockRestServiceServer`（LLM 调用全部 mock，测试不联网） |

---

## 3. 核心业务流程

```
上传 ZIP ──► 解析源码 ──► 生成测试 ──► 执行测试 ──► 优化(条件) ──► 覆盖率 ──► 质量评分
   │            │            │            │             │            │          │
   │            │            │            │             │            │          └ QualityScorer
   │            │            │            │             │            │            纯函数计算
   │            │            │            │             │            └ 解析 jacoco.xml
   │            │            │            └ 解析 Surefire 报告 └ 反馈提取 + LLM 修复重跑
   └ storage/projects/{id}.zip          storage/extracted/{projectId}/（被测项目工作目录）
```

| 步骤 | 接口 | 关键实现 |
|------|------|----------|
| 上传 | `POST /api/projects/upload` | `ProjectServiceImpl#uploadProject` |
| 解析 | `POST /api/projects/{projectId}/parse` | `ProjectServiceImpl#parseProject` |
| 生成 | `POST /api/projects/{projectId}/tests/generate` | `TestGenerationServiceImpl` + `PromptBuilder` + `JUnitDetector` |
| 执行 | `POST /api/projects/{projectId}/tests/execute` | `TestExecutionServiceImpl` + `DefaultMavenProcessRunner` |
| 优化 | `POST /api/projects/{projectId}/tests/optimize` | `TestOptimizationServiceImpl` + `ExecutionFeedbackExtractor` |
| 覆盖率 | `POST /api/projects/{projectId}/coverage` | `CoverageServiceImpl` + `JacocoReportParser` |
| 质量 | `POST /api/projects/{projectId}/quality` | `TestQualityServiceImpl` + `TestQualityAnalyzer` + `QualityScorer` |

**并发控制**：同一 `projectId` 上的生成 / 执行 / 优化 / 覆盖率 / 质量评估共享一把 `ReentrantLock`，冲突时返回 409（消息按入口区分）。因此演示时同一项目不要并发点击多个接口。

**统一响应体**：`{ "code": 200, "message": "success", "data": ... }`。

---

## 4. 环境要求

| 依赖 | 版本 / 说明 |
|------|-------------|
| JDK | 17 及以上（编译目标 `release 17`） |
| Maven | 3.8+（建议 3.10），且 `mvn` 必须在 `PATH` 中——TestPilot 会以子进程方式调用它 |
| PowerShell | 5.1 及以上（仅用于示例项目打包脚本） |
| Postman | 任意较新版本（导入 v2.1 集合） |
| 百炼 API Key | 环境变量 `DASHSCOPE_API_KEY`，仅「生成」与「优化」两步需要 |

被测项目自身的约束：

- 必须是 Maven 项目，ZIP 根目录（或唯一一层子目录）含 `pom.xml`；
- 必须在 pom 中声明 `junit:junit`（JUnit 4）或 `org.junit.jupiter:*` / `spring-boot-starter-test ≥ 2.2`（JUnit 5），否则生成接口返回 400；
- 单个 ZIP ≤ 50 MB，条目 ≤ 10000，解压后 ≤ 100 MB。

---

## 5. 配置 API Key

本项目**不保存**任何密钥，只从环境变量读取：

```powershell
# PowerShell（当前会话）
$env:DASHSCOPE_API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxxxx"

# 永久写入用户环境变量
[Environment]::SetEnvironmentVariable("DASHSCOPE_API_KEY", "sk-xxxx", "User")
```

`src/main/resources/application.yml` 中的相关默认值：

```yaml
testpilot:
  llm:
    base-url: https://ws-ksj13y5j5qjkb8jg.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
    model: qwen-plus
    api-key: ${DASHSCOPE_API_KEY:}
    connect-timeout: 10   # 秒
    read-timeout: 60      # 秒
```

注意：

1. 默认 `base-url` 是**某个百炼业务空间专属的兼容模式域名**（`ws-xxxx.cn-beijing.maas.aliyuncs.com`）。换用其他人的 Key 时，需要改成自己业务空间的兼容地址（百炼控制台 → API-KEY / 兼容 OpenAI 接口 处可查），或使用公共地址 `https://dashscope.aliyuncs.com/compatible-mode/v1`（能否使用取决于 Key 所属空间）。
2. `base-url` 只配到 `/compatible-mode/v1`，程序会自行追加 `/chat/completions`。
3. 不想改配置文件时，用环境变量覆盖即可（无需重新打包）：

```powershell
$env:TESTPILOT_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:TESTPILOT_LLM_MODEL    = "qwen-plus"      # 可选
```

4. Key 未配置时，「生成」接口会返回 500，消息包含「API Key 未配置」；上传 / 解析 / 执行 / 覆盖率不依赖 Key，可先行演示。

其它可选覆盖项：`TESTPILOT_MAVEN_TIMEOUT_SECONDS`（默认 120）、`TESTPILOT_STORAGE_PROJECT_PATH`（默认 `storage/projects`）、`TESTPILOT_STORAGE_EXTRACTED_PATH`（默认 `storage/extracted`）。

## 6. 启动

```powershell
# 方式一：开发模式直接启动
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/testpilot-0.0.1-SNAPSHOT.jar
```

启动后自检（默认端口 8080，见 `server.port`）：

```powershell
curl.exe http://localhost:8080/api/health
# {"code":200,"message":"TestPilot is running","data":"OK"}

curl.exe http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

改端口：`java -jar target/testpilot-0.0.1-SNAPSHOT.jar --server.port=9090`，同时把 Postman 集合变量 `baseUrl` 改成 `http://localhost:9090`。

运行期目录（首次调用时自动创建，均在 `.gitignore` 中）：

| 路径 | 内容 |
|------|------|
| `storage/projects/{projectId}.zip` | 上传的原始 ZIP |
| `storage/extracted/{projectId}/` | 解压后的 Maven 工作目录（含生成的测试、`target/`、JaCoCo 报告） |

---

## 7. 打包示例项目

仓库自带一个最小可运行的 Maven 示例项目 `samples/demo-calculator/`（Java 17 + JUnit Jupiter 5.9.2，无其他依赖）：

```
samples/demo-calculator/
├── pom.xml
└── src/main/java/com/example/demo/
    ├── Calculator.java      # add / subtract / multiply / divide（除 0 抛 ArithmeticException）
    └── DataProcessor.java   # sum / average / classify / filterNonBlank（多分支，便于展示覆盖率差异）
```

生成可上传的 ZIP：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-sample.ps1
```

输出示例（每次执行都会覆盖旧文件）：

```
ZIP created:
  Path : D:\...\TestPilot\samples\demo-calculator.zip
  Size : 2968 bytes
  Files: 3

Entries:
  pom.xml
  src/main/java/com/example/demo/Calculator.java
  src/main/java/com/example/demo/DataProcessor.java
```

脚本行为：

- `pom.xml` 位于 ZIP 根目录 → 满足 `ProjectServiceImpl#isRootPom` 的校验规则；
- 只读取示例源码，不修改、不写入；
- 自动排除任何 `target/` 目录与 `*.zip`，因此先在示例项目里跑过 `mvn test` 也不会污染上传包；
- 生成后自检「根目录存在 pom.xml」，不满足直接报错退出；
- 生成物 `samples/demo-calculator.zip` 由 `.gitignore` 的 `*.zip` 规则排除，不会入库；
- 自定义位置：`-OutputPath D:\tmp\demo.zip`（相对路径按仓库根目录解析）。

可选：先独立验证示例项目本身可构建（**初始没有任何测试类是刻意设计**，用于让演示从 0 覆盖率开始）：

```powershell
mvn -f samples/demo-calculator/pom.xml test
# ... [INFO] No tests to run.
# [INFO] BUILD SUCCESS
```

---

## 8. Postman 演示

1. Postman → Import → 选择 `postman/TestPilot-Demo.postman_collection.json`。
2. 集合变量（已内置默认值）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `baseUrl` | `http://localhost:8080` | 服务地址 |
| `projectId` | 空 | **由 01 上传响应自动写入**，不要手工填历史 ID |
| `className` | `Calculator` | 被测类 |
| `methodName` | `divide` | 被测方法 |

3. 打开 `01 上传示例项目`，确认 form-data 的 `file` 字段指向刚生成的 `samples/demo-calculator.zip`（Postman 可能提示重新选择文件，手动选中即可）。

4. 按顺序执行（Collection Runner 可直接整套跑）：

| # | 请求 | 方法 + 路径 | 预期 |
|---|------|-------------|------|
| 00 | 健康检查 | `GET /api/health` | `code=200`, `data="OK"` |
| 01 | 上传示例项目 | `POST /api/projects/upload`（multipart `file`） | `data.status="UPLOADED"`，写入 `projectId` |
| 02 | 解析项目源码 | `POST /api/projects/{id}/parse` | `data.files` 非空、`data.errors` 为空 |
| 03 | 生成单元测试 | `POST /api/projects/{id}/tests/generate` | `data.status="GENERATED"`，`testFilePath` 形如 `src/test/java/com/example/demo/CalculatorTest.java` |
| 04 | 执行测试 | `POST /api/projects/{id}/tests/execute` | `data.status` ∈ PASSED/FAILED/COMPILE_ERROR/MAVEN_ERROR/TIMEOUT/NO_REPORTS |
| 05 | 自动优化（条件） | `POST /api/projects/{id}/tests/optimize` | 固定断言 `retryCount`、`repairRecords`、初始/最终结果结构 |
| 06 | 覆盖率分析 | `POST /api/projects/{id}/coverage` | `coverageStatus` ∈ COVERAGE_GENERATED/NO_COVERAGE/NO_REPORT |
| 07 | 质量评价 | `POST /api/projects/{id}/quality` | `qualityReport` 存在，`dimensions` 固定 4 项 |

5. 关于「自动优化」这一步（唯一可能没有实质动作的环节）：

- 04 返回 `PASSED` 时，05 直接返回 `retryCount=0`、`optimizationSuccess=true`，**不会调用 LLM**——这是正确行为，不是失败；
- 想现场展示修复能力，可在 04 之前手动让测试失败：编辑 `storage/extracted/{projectId}/src/test/java/com/example/demo/CalculatorTest.java`，把某个断言改成错误值，再依次执行 04（FAILED）→ 05（LLM 修复 → 重跑 → `optimizationSuccess=true`）；
- 编译错误只有在**定位为测试源码错误**时才会进入修复；业务代码/依赖/插件导致的编译错误会直接返回，不消耗 LLM 调用。

6. 常见状态码：

| code | 含义 | 处理 |
|------|------|------|
| 400 | ZIP 非法 / 根目录无 pom.xml / 方法不存在 / JUnit 版本无法识别 | 按 `message` 修正后重试 |
| 404 | 项目不存在 / 未找到已生成的测试文件 | 重新执行 01 或先执行 03 |
| 409 | 该项目正在执行其他操作；或测试文件已存在拒绝覆盖 | 等待后重试；或重新上传获得新 `projectId` |
| 500 | LLM 调用失败（Key 未配置 / 401 / 429 / 内容为空）或生成代码校验失败 | 检查 `DASHSCOPE_API_KEY` 与 `base-url`，稍后重试 |

7. 耗时预期：03 取决于 LLM（通常数秒到数十秒）；04/06/07 各包含一次真实 Maven 测试（首次会下载插件依赖，可能数十秒），超时上限 120 秒/次，07 内部含完整覆盖率流程。

## 9. 查看覆盖率 HTML 报告

`POST /api/projects/{projectId}/coverage` 返回的 `metrics` 只是**项目级**汇总（INSTRUCTION / BRANCH / LINE / METHOD / CLASS / COMPLEXITY）。类、方法、行级别的明细在磁盘上的 HTML 报告里：

```
storage/extracted/{projectId}/target/site/jacoco/index.html
```

打开方式：

```powershell
# PowerShell（把 {projectId} 换成 01 上传返回的 32 位 ID）
$projectId = "把这里换成真实 projectId"
Start-Process "storage\extracted\$projectId\target\site\jacoco\index.html"
```

同目录下的 `jacoco.xml` 就是接口解析的原始数据源。若接口返回 `coverageStatus = NO_REPORT`，先确认该文件是否生成；常见原因是被测项目在 pom 中覆盖了 `argLine`（未保留 `${argLine}`）、把 surefire 配成 `forkCount=0`，或只跑了不产生测试的模块。

---

## 10. 已知限制

1. **单体后端 Demo，暂无前端**：没有页面，所有演示通过 HTTP / Postman 完成。
2. **文件系统存储，无数据库**：项目以 ZIP 与解压目录的形式落在 `storage/` 下；每次调用的结果只存在于响应体中，服务端不保存历史记录，也没有查询/列表接口。
3. **Maven 执行不是生产级沙箱**：被测项目的 `mvn test` 以当前操作系统用户身份在本机子进程运行，测试代码可以做该用户能做的任何事（读写文件、联网）。仅可在可信代码或隔离环境中使用。
4. **自动修复只针对测试源码错误**：能修的是「测试源码编译错误」和「测试断言失败」；业务源码错误、依赖缺失、插件配置错误一律不修。最多 2 轮，且每轮都要求 `@Test` 与断言数量不减少、不得出现 `@Disabled`/`@Ignore`，不满足则回滚。
5. **LLM 输出具有不确定性**：同一方法多次生成可能得到不同测试；偶尔会生成不可编译或断言错误的代码（这正是自动优化环节存在的意义）。`paramTypes` 必须与源码签名完全一致（含顺序），否则 400。
6. **测试文件不允许覆盖**：同一项目重复生成同一个测试类会返回 409；演示前请重新上传获得新的 `projectId`。
7. **覆盖率只解析项目级 counter**：多模块项目只在根目录执行一次，默认读取根模块的 `target/site/jacoco/jacoco.xml`，不聚合子模块。
8. **无鉴权、无限流**：仅面向本机/内网演示，不要直接暴露到公网。

---

## 附录 A 接口一览

| 方法 | 路径 | 请求体 | 说明 |
|------|------|--------|------|
| GET | `/api/health` | - | 服务存活 |
| POST | `/api/projects/upload` | multipart `file` | 上传 ZIP，返回 `projectId` |
| POST | `/api/projects/{projectId}/parse` | - | 解析 Java 源码 |
| POST | `/api/projects/{projectId}/tests/generate` | `{className, methodName, paramTypes}` | 生成单元测试 |
| POST | `/api/projects/{projectId}/tests/execute` | - | 执行 `mvn test` |
| POST | `/api/projects/{projectId}/tests/optimize` | `{className, methodName, paramTypes, maxRetries≤2}` | 执行→修复→重跑 |
| POST | `/api/projects/{projectId}/coverage` | - | JaCoCo 覆盖率 |
| POST | `/api/projects/{projectId}/quality` | - | 质量评分 |
| POST | `/api/llm/test` | `{prompt}` | 直连 LLM 的连通性自测（会消耗额度） |
| GET | `/actuator/health`、`/actuator/info` | - | Spring Boot Actuator |

## 附录 B 目录结构

```
TestPilot/
├── pom.xml
├── README.md
├── .gitignore
├── postman/
│   └── TestPilot-Demo.postman_collection.json   # 演示集合（入库）
├── samples/
│   └── demo-calculator/                         # 示例 Maven 项目（入库，无测试类）
├── scripts/
│   └── package-sample.ps1                       # 生成可上传 ZIP（入库）
├── src/main/java/com/testpilot/
│   ├── controller/     # 7 个业务控制器 + HealthController
│   ├── service/impl/   # 上传解析 / 生成 / 执行 / 优化 / 覆盖率 / 质量 / LLM
│   ├── utils/          # Maven 执行器、Prompt、JUnit 检测、报告解析、评分、项目锁
│   ├── dto/ config/ common/
│   └── resources/application.yml
├── src/test/java/com/testpilot/                 # 410 个回归用例（LLM 全部 mock）
└── storage/                                     # 运行期数据（不入库）
```

## 附录 C 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| 03 返回 409「测试文件已存在，拒绝覆盖」 | 同一项目已生成过该类测试，重新执行 01 上传获得新 `projectId` |
| 03 返回 500「API Key 未配置」 | 未设置 `DASHSCOPE_API_KEY` 环境变量（设置后需重启进程） |
| 03 返回 500 且 message 含 401/403 | Key 与 `base-url` 不匹配：改用自己业务空间的兼容地址 |
| 04 返回 `COMPILE_ERROR` 且 `errorMessage` 指向业务源码 | 被测项目本身编译不过，先在本地 `mvn -f samples/demo-calculator/pom.xml test` 验证 |
| 06 返回 `NO_REPORT` | 见第 9 节；重点检查 `argLine`、`forkCount`、是否真的产生了测试报告 |
| 07 返回 `grade="N/A"` | 尚未生成有效测试 → 没有可评的运行时维度，属预期三态语义 |
| 打包脚本被系统拦截 | `powershell -ExecutionPolicy Bypass -File scripts/package-sample.ps1` |
| Postman 上传报「file 未选择」 | 在 01 请求的 form-data 中手动选择 `samples/demo-calculator.zip` |

---

## 后续可做（未实现）

- 结果持久化 + 历史查询接口（`GET /api/projects`、`GET /api/projects/{id}/report`）；
- 前端页面（上传进度、生成代码对比、覆盖率可视化）；
- LLM 健康检查接口与启动自检；
- 多模块项目覆盖率聚合；
- 任务队列与限流（当前是同项目互斥、跨项目并发）。
