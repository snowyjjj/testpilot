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
}
