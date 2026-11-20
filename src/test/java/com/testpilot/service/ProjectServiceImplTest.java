package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.ClassInfo;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.ParsedFile;
import com.testpilot.dto.ProjectParseResponse;
import com.testpilot.dto.ProjectUploadResponse;
import com.testpilot.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceImplTest {

    private ProjectServiceImpl projectService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl();
        ReflectionTestUtils.setField(projectService, "projectStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(projectService, "extractedStoragePath",
                tempDir.resolve("extracted").toString());
    }

    private byte[] createZip(String... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.write(("content of " + entry).getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private byte[] createZipWithContents(java.util.Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (java.util.Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private String uploadAndGetId(byte[] zipBytes, String fileName) throws IOException {
        MultipartFile file = new MockMultipartFile("file", fileName,
                "application/zip", zipBytes);
        return projectService.uploadProject(file).getProjectId();
    }

    @Test
    void uploadProject_nullFile_throwsException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(null));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    void uploadProject_emptyFile_throwsException() {
        MultipartFile file = new MockMultipartFile("file", "test.zip",
                "application/zip", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    @Test
    void uploadProject_fileTooLarge_throwsException() {
        MockMultipartFile file = new MockMultipartFile("file", "big.zip",
                "application/zip", new byte[]{1, 2, 3}) {
            @Override
            public long getSize() {
                return 51L * 1024 * 1024;
            }
        };
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("50MB"));
    }

    @Test
    void uploadProject_invalidZip_throwsException() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "test.zip",
                "application/zip", "not a zip content".getBytes());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertTrue(ex.getMessage().contains("ZIP"));
    }

    @Test
    void uploadProject_zipWithoutPom_throwsException() throws IOException {
        byte[] zipBytes = createZip("README.md", "src/Main.java");
        MultipartFile file = new MockMultipartFile("file", "test.zip",
                "application/zip", zipBytes);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertTrue(ex.getMessage().contains("pom.xml"));
    }

    @Test
    void uploadProject_zipWithNestedPomOnly_throwsException() throws IOException {
        byte[] zipBytes = createZip("module/sub/pom.xml", "README.md");
        MultipartFile file = new MockMultipartFile("file", "test.zip",
                "application/zip", zipBytes);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertTrue(ex.getMessage().contains("pom.xml"));
    }

    @Test
    void uploadProject_zipWithRootPom_success() throws IOException {
        byte[] zipBytes = createZip("pom.xml", "src/Main.java");
        MultipartFile file = new MockMultipartFile("file", "myproject.zip",
                "application/zip", zipBytes);

        ProjectUploadResponse response = projectService.uploadProject(file);

        assertNotNull(response.getProjectId());
        assertFalse(response.getProjectId().isBlank());
        assertEquals("myproject.zip", response.getFileName());
        assertEquals("UPLOADED", response.getStatus());

        Path savedFile = tempDir.resolve(response.getProjectId() + ".zip");
        assertTrue(Files.exists(savedFile));
    }

    @Test
    void uploadProject_zipWithSubfolderPom_success() throws IOException {
        byte[] zipBytes = createZip("myproject/pom.xml", "myproject/src/Main.java");
        MultipartFile file = new MockMultipartFile("file", "myproject.zip",
                "application/zip", zipBytes);

        ProjectUploadResponse response = projectService.uploadProject(file);

        assertNotNull(response.getProjectId());
        assertEquals("UPLOADED", response.getStatus());

        Path savedFile = tempDir.resolve(response.getProjectId() + ".zip");
        assertTrue(Files.exists(savedFile));
    }

    @Test
    void uploadProject_storedFileNameIsUuid_notOriginalName() throws IOException {
        byte[] zipBytes = createZip("pom.xml");
        MultipartFile file = new MockMultipartFile("file", "../../evil.zip",
                "application/zip", zipBytes);

        ProjectUploadResponse response = projectService.uploadProject(file);

        Path savedFile = tempDir.resolve(response.getProjectId() + ".zip");
        assertTrue(Files.exists(savedFile));
        assertFalse(Files.exists(tempDir.resolve("evil.zip")));
    }

    @Test
    void uploadProject_zipTooManyEntries_throwsException() throws IOException {
        ReflectionTestUtils.setField(projectService, "maxZipEntries", 3);
        String[] entries = new String[]{"a.txt", "b.txt", "c.txt", "d.txt", "pom.xml"};
        byte[] zipBytes = createZip(entries);
        MultipartFile file = new MockMultipartFile("file", "many.zip",
                "application/zip", zipBytes);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("条目数量"));
    }

    @Test
    void uploadProject_zipDecompressedSizeExceeded_throwsException() throws IOException {
        ReflectionTestUtils.setField(projectService, "maxZipDecompressedSize", 10L);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write(new byte[1024]);
            zos.closeEntry();
        }
        byte[] zipBytes = baos.toByteArray();
        MultipartFile file = new MockMultipartFile("file", "big.zip",
                "application/zip", zipBytes);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.uploadProject(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("解压数据量"));
    }

    @Test
    void parseProject_normalClass_extractsMethodInfo() throws IOException {
        String javaCode = "package com.example;\n\n"
                + "public class Calculator {\n"
                + "    public int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n"
                + "}\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/Calculator.java", javaCode);
        String projectId = uploadAndGetId(createZipWithContents(entries), "calc.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        assertEquals(projectId, response.getProjectId());
        assertEquals(1, response.getFiles().size());
        assertTrue(response.getErrors().isEmpty());

        ParsedFile file = response.getFiles().get(0);
        assertEquals("src/Calculator.java", file.getFilePath());
        assertEquals("com.example", file.getPackageName());
        assertEquals(1, file.getClasses().size());

        ClassInfo clazz = file.getClasses().get(0);
        assertEquals("Calculator", clazz.getClassName());
        assertEquals("class", clazz.getClassType());
        assertEquals(1, clazz.getMethods().size());

        MethodInfo method = clazz.getMethods().get(0);
        assertEquals("add", method.getName());
        assertEquals("int", method.getReturnType());
        assertEquals("public", method.getAccessModifier());
        assertFalse(method.isStatic());
        assertEquals(2, method.getParameters().size());
        assertEquals("a", method.getParameters().get(0).getName());
        assertEquals("int", method.getParameters().get(0).getType());
        assertEquals("b", method.getParameters().get(1).getName());
        assertTrue(method.getSource().contains("return a + b;"));
    }

    @Test
    void parseProject_classWithMultipleMethods_extractsAll() throws IOException {
        String javaCode = "package com.example;\n\n"
                + "public class Service {\n"
                + "    public void doWork() {}\n"
                + "    private String helper() { return \"x\"; }\n"
                + "    protected int count() { return 0; }\n"
                + "}\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/Service.java", javaCode);
        String projectId = uploadAndGetId(createZipWithContents(entries), "svc.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        ClassInfo clazz = response.getFiles().get(0).getClasses().get(0);
        assertEquals(3, clazz.getMethods().size());
        assertEquals("doWork", clazz.getMethods().get(0).getName());
        assertEquals("helper", clazz.getMethods().get(1).getName());
        assertEquals("count", clazz.getMethods().get(2).getName());
    }

    @Test
    void parseProject_staticMethod_extractsStaticFlag() throws IOException {
        String javaCode = "package com.example;\n\n"
                + "public class MathUtil {\n"
                + "    public static int square(int x) {\n"
                + "        return x * x;\n"
                + "    }\n"
                + "}\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/MathUtil.java", javaCode);
        String projectId = uploadAndGetId(createZipWithContents(entries), "math.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        MethodInfo method = response.getFiles().get(0).getClasses().get(0).getMethods().get(0);
        assertEquals("square", method.getName());
        assertTrue(method.isStatic());
        assertEquals("int", method.getReturnType());
    }

    @Test
    void parseProject_interfaceAndEnum_extractsCorrectTypes() throws IOException {
        String interfaceCode = "package com.example;\n\n"
                + "public interface Greeter {\n"
                + "    String greet(String name);\n"
                + "}\n";
        String enumCode = "package com.example;\n\n"
                + "public enum Color {\n"
                + "    RED, GREEN, BLUE;\n"
                + "    public String lowerName() {\n"
                + "        return name().toLowerCase();\n"
                + "    }\n"
                + "}\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/Greeter.java", interfaceCode);
        entries.put("src/Color.java", enumCode);
        String projectId = uploadAndGetId(createZipWithContents(entries), "types.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        assertEquals(2, response.getFiles().size());
        assertTrue(response.getErrors().isEmpty());

        ClassInfo greeter = findClassByName(response, "Greeter");
        assertEquals("interface", greeter.getClassType());
        assertEquals(1, greeter.getMethods().size());
        assertEquals("greet", greeter.getMethods().get(0).getName());

        ClassInfo color = findClassByName(response, "Color");
        assertEquals("enum", color.getClassType());
        assertEquals(1, color.getMethods().size());
        assertEquals("lowerName", color.getMethods().get(0).getName());
    }

    private ClassInfo findClassByName(ProjectParseResponse response, String name) {
        return response.getFiles().stream()
                .flatMap(f -> f.getClasses().stream())
                .filter(c -> name.equals(c.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + name));
    }

    @Test
    void parseProject_emptyAndInvalidFiles_recordsErrorsWithoutStopping() throws IOException {
        String validCode = "package com.example;\n\n"
                + "public class Valid {\n"
                + "    public void run() {}\n"
                + "}\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/Empty.java", "");
        entries.put("src/Invalid.java", "this is not valid java {{{");
        entries.put("src/Valid.java", validCode);
        String projectId = uploadAndGetId(createZipWithContents(entries), "mixed.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        assertEquals(2, response.getFiles().size());
        assertEquals(1, response.getErrors().size());

        ParsedFile emptyFile = response.getFiles().stream()
                .filter(f -> "src/Empty.java".equals(f.getFilePath()))
                .findFirst().orElseThrow();
        assertTrue(emptyFile.getClasses().isEmpty());

        assertEquals("src/Valid.java", response.getFiles().stream()
                .filter(f -> f.getFilePath().equals("src/Valid.java"))
                .findFirst().orElseThrow().getFilePath());
        assertEquals("Valid", findClassByName(response, "Valid").getClassName());

        assertEquals("src/Invalid.java", response.getErrors().get(0).getFilePath());
    }

    @Test
    void parseProject_multipleJavaFiles_parsesAll() throws IOException {
        String code1 = "package com.example;\n\npublic class A { public void a() {} }\n";
        String code2 = "package com.example;\n\npublic class B { public void b() {} }\n";
        String code3 = "package com.example;\n\npublic class C { public void c() {} }\n";
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("pom.xml", "<project/>");
        entries.put("src/A.java", code1);
        entries.put("src/B.java", code2);
        entries.put("src/C.java", code3);
        String projectId = uploadAndGetId(createZipWithContents(entries), "multi.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);

        assertEquals(3, response.getFiles().size());
        assertTrue(response.getErrors().isEmpty());
        assertEquals("A", response.getFiles().get(0).getClasses().get(0).getClassName());
        assertEquals("B", response.getFiles().get(1).getClasses().get(0).getClassName());
        assertEquals("C", response.getFiles().get(2).getClasses().get(0).getClassName());
    }

    @Test
    void parseProject_notFound_throwsException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.parseProject("nonexistent"));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void parseProject_normalDirectoryEntries_trailingSlash_work() throws IOException {
        // 标准 ZIP：目录条目以 / 结尾且 isDirectory()=true
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry e1 = new ZipEntry("src/main/");
            zos.putNextEntry(e1);
            zos.closeEntry();

            ZipEntry e2 = new ZipEntry("src/main/java/");
            zos.putNextEntry(e2);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            ZipEntry e3 = new ZipEntry("src/main/java/X.java");
            zos.putNextEntry(e3);
            zos.write("public class X {}".getBytes());
            zos.closeEntry();
        }
        String projectId = uploadAndGetId(baos.toByteArray(), "normal.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);
        assertEquals(1, response.getFiles().size());
        assertEquals("src/main/java/X.java", response.getFiles().get(0).getFilePath());
    }

    @Test
    void parseProject_windowsBackslashEntries_work() throws IOException {
        // Windows 风格：反斜杠分隔的条目，不以 / 结尾，isDirectory()=false
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // 这些条目的 name 是 "src\main\"（注意：Java ZipEntry 仍会用正斜杠，但这里模拟特殊工具的输出）
            // 我们直接写 "src/main" 且不以 / 结尾，模拟 isDirectory() 为 false 的目录
            zos.putNextEntry(new ZipEntry("src/main"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("src/main/java"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("src/main/java/com"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            ZipEntry e = new ZipEntry("src/main/java/com/X.java");
            zos.putNextEntry(e);
            zos.write("package com; public class X {}".getBytes());
            zos.closeEntry();
        }
        String projectId = uploadAndGetId(baos.toByteArray(), "win-style.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);
        assertEquals(1, response.getFiles().size());
        assertEquals("src/main/java/com/X.java", response.getFiles().get(0).getFilePath());
        assertEquals("com", response.getFiles().get(0).getPackageName());
        assertTrue(response.getErrors().isEmpty());
    }

    @Test
    void parseProject_missingDirectoryMarkers_butWithChildren_work() throws IOException {
        // 目录条目完全缺失（既没有显式目录条目，也没有尾斜杠标记），只有文件条目
        // 之前代码会因为父目录不存在而失败，现在应能自动推断父目录
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            // 只有文件，没有任何目录条目
            ZipEntry e = new ZipEntry("src/main/java/com/example/App.java");
            zos.putNextEntry(e);
            zos.write("public class App {}".getBytes());
            zos.closeEntry();
        }
        String projectId = uploadAndGetId(baos.toByteArray(), "no-dir-entries.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);
        assertEquals(1, response.getFiles().size());
        assertEquals("src/main/java/com/example/App.java", response.getFiles().get(0).getFilePath());
        assertTrue(response.getErrors().isEmpty());

        // 确认目录确实创建出来了
        Path extracted = tempDir.resolve("extracted").resolve(projectId);
        assertTrue(Files.isDirectory(extracted.resolve("src/main/java/com/example")));
        assertTrue(Files.isRegularFile(extracted.resolve("src/main/java/com/example/App.java")));
    }

    @Test
    void parseProject_makefileNoExtension_notTreatedAsDirectory() throws IOException {
        // 无扩展名的普通文件 Makefile 不应被误判为目录
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            ZipEntry makefile = new ZipEntry("Makefile");
            zos.putNextEntry(makefile);
            zos.write("# build target\nall:\n\t@echo ok".getBytes());
            zos.closeEntry();
        }
        String projectId = uploadAndGetId(baos.toByteArray(), "makefile.zip");

        ProjectParseResponse response = projectService.parseProject(projectId);
        // 没有 .java 文件，files 为空
        assertTrue(response.getFiles().isEmpty());

        // 但 Makefile 必须真实存在为文件，而不是目录
        Path extracted = tempDir.resolve("extracted").resolve(projectId);
        assertTrue(Files.isRegularFile(extracted.resolve("Makefile")),
                "Makefile should be a regular file, not a directory");
        assertFalse(Files.isDirectory(extracted.resolve("Makefile")));

        // 确认文件内容正确
        assertEquals("# build target",
                Files.readAllLines(extracted.resolve("Makefile")).get(0));
    }

    @Test
    void parseProject_pathTraversal_stillRejected() throws IOException {
        // ZIP 路径穿越攻击：条目名包含 ../
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("../../evil.txt"));
            zos.write("hacked!".getBytes());
            zos.closeEntry();
        }
        String projectId = uploadAndGetId(baos.toByteArray(), "traversal.zip");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.parseProject(projectId));
        assertEquals(500, ex.getCode());
    }

    // ============ 解压目录复用测试 ============

    @Test
    void parseProject_existingDirIsReused_notReExtracted() throws IOException {
        // 首次解压
        String projectId = uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/com/example/App.java",
                        "package com.example;\npublic class App {}")), "reuse.zip");
        projectService.parseProject(projectId);

        Path projectDir = tempDir.resolve("extracted").resolve(projectId);
        // 在解压目录中放一个标记文件
        Path marker = projectDir.resolve("marker.txt");
        Files.writeString(marker, "keep-me");

        // 再次 parseProject，不应删除已有目录
        projectService.parseProject(projectId);

        assertTrue(Files.exists(marker), "已存在的标记文件应保留，不应被重新解压删除");
    }

    @Test
    void parseProject_testFileSurvivesRepeatedParse() throws IOException {
        String projectId = uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/com/example/Calc.java",
                        "package com.example;\npublic class Calc { public int add(int a,int b){return a+b;} }")),
                "survive.zip");
        projectService.parseProject(projectId);

        Path projectDir = tempDir.resolve("extracted").resolve(projectId);
        // 模拟已生成的测试文件
        Path testFile = projectDir.resolve("src/test/java/com/example/CalcTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example;\nclass CalcTest {}");

        // 再次 parseProject
        projectService.parseProject(projectId);

        assertTrue(Files.exists(testFile), "已生成的测试文件不应被 parseProject 删除");
    }

    // ============ 方法定位测试 ============

    private String uploadCalculatorProject() throws IOException {
        String src = "package com.example;\n"
                + "public class Calculator {\n"
                + "  public int add(int a, int b) { return a + b; }\n"
                + "  public double add(double a, double b) { return a + b; }\n"
                + "  private int secret() { return 42; }\n"
                + "  protected void doProtected() {}\n"
                + "}";
        return uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/com/example/Calculator.java", src)), "calc.zip");
    }

    @Test
    void getMethodContext_overloadedMethod_distinguishedByParamTypes() throws IOException {
        String projectId = uploadCalculatorProject();

        var ctx1 = projectService.getMethodContext(projectId, "Calculator", "add",
                java.util.List.of("int", "int"));
        assertEquals("add", ctx1.getMethodInfo().getName());
        assertEquals("int", ctx1.getMethodInfo().getReturnType());
        assertEquals(2, ctx1.getMethodInfo().getParameters().size());

        var ctx2 = projectService.getMethodContext(projectId, "Calculator", "add",
                java.util.List.of("double", "double"));
        assertEquals("double", ctx2.getMethodInfo().getReturnType());
    }

    @Test
    void getMethodContext_methodNotFound_throwsException() throws IOException {
        String projectId = uploadCalculatorProject();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getMethodContext(projectId, "Calculator", "nonexist",
                        java.util.List.of()));
        assertEquals(404, ex.getCode());
    }

    @Test
    void getMethodContext_privateMethod_rejected() throws IOException {
        String projectId = uploadCalculatorProject();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getMethodContext(projectId, "Calculator", "secret",
                        java.util.List.of()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("private"));
    }

    @Test
    void getMethodContext_protectedMethod_rejected() throws IOException {
        String projectId = uploadCalculatorProject();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getMethodContext(projectId, "Calculator", "doProtected",
                        java.util.List.of()));
        assertEquals(400, ex.getCode());
    }

    @Test
    void getMavenProjectDir_pomAtRoot_returnsExtractedDir() throws IOException {
        String projectId = uploadCalculatorProject();
        Path projectDir = projectService.getMavenProjectDir(projectId);
        assertNotNull(projectDir);
        assertTrue(Files.isRegularFile(projectDir.resolve("pom.xml")));
    }

    @Test
    void getMavenProjectDir_pomInSingleSubdir_returnsSubdir() throws IOException {
        // ZIP 内含单层子目录，pom.xml 位于子目录中
        String projectId = uploadAndGetId(createZipWithContents(
                java.util.Map.of("myapp/pom.xml", "<project/>",
                        "myapp/src/main/java/App.java", "class App {}")), "sub.zip");
        Path projectDir = projectService.getMavenProjectDir(projectId);
        assertNotNull(projectDir);
        assertEquals("myapp", projectDir.getFileName().toString());
        assertTrue(Files.isRegularFile(projectDir.resolve("pom.xml")));
    }

    @Test
    void getMavenProjectDir_noPom_throws404() throws IOException {
        String projectId = uploadCalculatorProject();
        // 触发解压后删除 pom.xml，模拟无 pom 的情况
        Path extracted = projectService.getProjectDir(projectId);
        Files.delete(extracted.resolve("pom.xml"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getMavenProjectDir(projectId));
        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("pom.xml"));
    }

    @Test
    void getMavenProjectDir_blankId_throws400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getMavenProjectDir("  "));
        assertEquals(400, ex.getCode());
    }

    // ============ 解压并发安全测试 ============

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ReentrantLock> extractionLocks() {
        return (ConcurrentHashMap<String, ReentrantLock>)
                ReflectionTestUtils.getField(projectService, "extractionLocks");
    }

    private Path extractedRoot() {
        return tempDir.resolve("extracted");
    }

    private long countExtractTempDirs() throws IOException {
        Path root = extractedRoot();
        if (!Files.exists(root)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.contains(".extract-"))
                    .count();
        }
    }

    /** 构造条目较多的 ZIP：放大首次解压的竞态窗口，pom.xml 放在前面保证根目录可定位。 */
    private byte[] createManyEntriesZip(int fileCount) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();
            for (int i = 0; i < fileCount; i++) {
                zos.putNextEntry(new ZipEntry("src/main/resources/data_" + i + ".txt"));
                zos.write(("content-" + i).getBytes());
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("src/main/java/com/example/App.java"));
            zos.write("package com.example;\npublic class App {}".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createTraversalZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("../../evil.txt"));
            zos.write("hacked!".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /** 用 CountDownLatch 同时释放 N 个线程执行 action，返回首个捕获到的异常。 */
    private Throwable runConcurrent(int threadCount, java.util.function.Consumer<Integer> action)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.accept(idx);
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            }, "worker-" + i);
            threads.add(t);
            t.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未能在超时内就绪");
        start.countDown();
        for (Thread t : threads) {
            t.join(15_000);
            assertFalse(t.isAlive(), "线程未在超时内结束: " + t.getName());
        }
        return error.get();
    }

    @Test
    void ensureExtracted_concurrentFirstAccess_allSucceed() throws Exception {
        String projectId = uploadAndGetId(createManyEntriesZip(100), "concurrent.zip");
        Throwable error = runConcurrent(8, idx -> {
            ProjectParseResponse resp = projectService.parseProject(projectId);
            if (resp.getFiles().isEmpty()) {
                throw new AssertionError("解析到空文件集，可能读取到不完整目录");
            }
        });
        assertNull(error, "同一 projectId 多线程首次访问应全部成功: " + error);

        Path projectDir = extractedRoot().resolve(projectId);
        assertTrue(Files.isRegularFile(projectDir.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(projectDir.resolve("src/main/java/com/example/App.java")));
        assertEquals(0, countExtractTempDirs(), "解压完成后不应残留临时目录");
    }

    @Test
    void ensureExtracted_concurrentFirstAccess_noIncompleteMavenDir() throws Exception {
        // 多个读线程并发首次访问：若读到不完整目录会因找不到 pom.xml 抛 404
        String projectId = uploadAndGetId(createManyEntriesZip(200), "race.zip");
        Throwable error = runConcurrent(12, idx -> {
            Path mavenDir = projectService.getMavenProjectDir(projectId);
            if (!Files.isRegularFile(mavenDir.resolve("pom.xml"))) {
                throw new AssertionError("读到不完整目录，缺少 pom.xml");
            }
        });
        assertNull(error, "并发首次访问期间不应读到不完整目录: " + error);
        assertEquals(0, countExtractTempDirs());
    }

    @Test
    void ensureExtracted_failure_noProjectDirAndNoTempDirLeft() throws IOException {
        // 路径穿越 ZIP：上传校验不查穿越，但解压阶段会拒绝，触发真实解压失败
        String projectId = uploadAndGetId(createTraversalZip(), "traversal-fail.zip");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.parseProject(projectId));
        assertEquals(500, ex.getCode());

        // 失败后正式目录不应存在，否则会被误认为已解压完成
        assertFalse(Files.exists(extractedRoot().resolve(projectId)),
                "解压失败后不应留下正式目录");
        // 临时目录必须清理干净
        assertEquals(0, countExtractTempDirs(), "解压失败后不应残留临时目录");
    }

    @Test
    void ensureExtracted_failureThenRetry_succeeds() throws IOException {
        String projectId = uploadAndGetId(createTraversalZip(), "retry-bad.zip");

        // 首次解压失败，且不留下正式目录
        assertThrows(BusinessException.class, () -> projectService.parseProject(projectId));
        assertFalse(Files.exists(extractedRoot().resolve(projectId)));
        assertEquals(0, countExtractTempDirs());

        // 用同一 projectId 覆盖为合法 ZIP，验证失败后可安全重试
        Path zipFile = tempDir.resolve(projectId + ".zip");
        byte[] goodZip = createZipWithContents(java.util.Map.of(
                "pom.xml", "<project/>",
                "src/main/java/com/example/App.java",
                "package com.example;\npublic class App {}"));
        Files.write(zipFile, goodZip);

        ProjectParseResponse resp = projectService.parseProject(projectId);
        assertFalse(resp.getFiles().isEmpty());
        Path projectDir = extractedRoot().resolve(projectId);
        assertTrue(Files.isRegularFile(projectDir.resolve("pom.xml")));
        assertEquals(0, countExtractTempDirs());
    }

    @Test
    void ensureExtracted_existingDirReused_noReExtractionAndTestFilePreserved() throws IOException {
        String projectId = uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/com/example/Calc.java",
                        "package com.example;\npublic class Calc {}")), "reuse2.zip");
        projectService.parseProject(projectId);

        Path projectDir = extractedRoot().resolve(projectId);
        Path testFile = projectDir.resolve("src/test/java/com/example/CalcTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example;\nclass CalcTest {}");

        // 二次访问：快速路径直接复用，不创建临时目录，不删除/覆盖测试文件
        projectService.parseProject(projectId);
        projectService.getMavenProjectDir(projectId);

        assertTrue(Files.exists(testFile), "已生成的测试文件不应被重新解压覆盖");
        assertEquals(0, countExtractTempDirs(), "复用已有目录时不应创建解压临时目录");
    }

    @Test
    void ensureExtracted_differentProjectIds_useDistinctLocksAndExtractInParallel() throws Exception {
        String idA = uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/A.java", "class A {}")), "a.zip");
        String idB = uploadAndGetId(createZipWithContents(
                java.util.Map.of("pom.xml", "<project/>",
                        "src/main/java/B.java", "class B {}")), "b.zip");

        Throwable error = runConcurrent(2, idx -> {
            String pid = (idx == 0) ? idA : idB;
            projectService.parseProject(pid);
        });
        assertNull(error, "不同 projectId 应能独立解压成功: " + error);

        // 结构验证：两个 projectId 映射到不同的锁实例，证明不是同一把全局锁
        ConcurrentHashMap<String, ReentrantLock> map = extractionLocks();
        ReentrantLock lockA = map.get(idA);
        ReentrantLock lockB = map.get(idB);
        assertNotNull(lockA);
        assertNotNull(lockB);
        assertNotSame(lockA, lockB, "不同 projectId 应使用各自独立的锁实例");
    }
}
