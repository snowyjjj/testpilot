package com.testpilot.service.impl;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.ClassInfo;
import com.testpilot.dto.MethodContext;
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.ParseError;
import com.testpilot.dto.ParsedFile;
import com.testpilot.dto.ProjectParseResponse;
import com.testpilot.dto.ProjectUploadResponse;
import com.testpilot.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    @Value("${testpilot.zip.max-entries:10000}")
    private int maxZipEntries = 10000;

    @Value("${testpilot.zip.max-decompressed-size:104857600}")
    private long maxZipDecompressedSize = 100L * 1024 * 1024;

    @Value("${testpilot.storage.project-path:storage/projects}")
    private String projectStoragePath;

    @Value("${testpilot.storage.extracted-path:storage/extracted}")
    private String extractedStoragePath;

    @Override
    public ProjectUploadResponse uploadProject(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件大小不能超过 50MB");
        }

        validateZipAndPom(file);

        String projectId = UUID.randomUUID().toString().replace("-", "");
        String storedFileName = projectId + ".zip";

        Path storageDir = Paths.get(projectStoragePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageDir);
            Path targetFile = storageDir.resolve(storedFileName);
            file.transferTo(targetFile.toFile());
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败");
        }

        return new ProjectUploadResponse(projectId, file.getOriginalFilename(), "UPLOADED");
    }

    private void validateZipAndPom(MultipartFile file) {
        boolean pomFound = false;
        int entryCount = 0;
        long decompressedSize = 0;
        byte[] buffer = new byte[8192];
        try (InputStream is = file.getInputStream();
             PushbackInputStream pis = new PushbackInputStream(is, 4)) {
            byte[] signature = new byte[4];
            int read = pis.read(signature);
            if (read < 4 || !isZipSignature(signature)) {
                throw new BusinessException(400, "无效的 ZIP 文件");
            }
            pis.unread(signature, 0, read);
            try (ZipInputStream zis = new ZipInputStream(pis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > maxZipEntries) {
                        throw new BusinessException(400, "ZIP 条目数量超限");
                    }
                    int n;
                    while ((n = zis.read(buffer)) > 0) {
                        decompressedSize += n;
                        if (decompressedSize > maxZipDecompressedSize) {
                            throw new BusinessException(400, "ZIP 解压数据量超限");
                        }
                    }
                    if (isRootPom(entry.getName())) {
                        pomFound = true;
                    }
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new BusinessException(400, "无效的 ZIP 文件");
        }
        if (!pomFound) {
            throw new BusinessException(400, "ZIP 根目录下未找到 pom.xml");
        }
    }

    private boolean isZipSignature(byte[] sig) {
        return sig[0] == 0x50 && sig[1] == 0x4B
                && ((sig[2] == 0x03 && sig[3] == 0x04)
                || (sig[2] == 0x05 && sig[3] == 0x06));
    }

    private boolean isRootPom(String entryName) {
        if (entryName == null) {
            return false;
        }
        String normalized = entryName.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if ("pom.xml".equals(normalized)) {
            return true;
        }
        int firstSlash = normalized.indexOf('/');
        if (firstSlash > 0 && normalized.indexOf('/', firstSlash + 1) < 0
                && normalized.endsWith("/pom.xml")) {
            return true;
        }
        return false;
    }

    @Override
    public ProjectParseResponse parseProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }

        Path projectDir = ensureExtracted(projectId);

        List<ParsedFile> files = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectDir)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path javaFile : javaFiles) {
                String relativePath = projectDir.relativize(javaFile).toString()
                        .replace("\\", "/");
                try {
                    ParsedFile parsed = parseJavaFile(javaFile, relativePath);
                    files.add(parsed);
                } catch (Exception e) {
                    errors.add(new ParseError(relativePath, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(500, "遍历项目源码失败");
        }

        return new ProjectParseResponse(projectId, files, errors);
    }

    @Override
    public MethodContext getMethodContext(String projectId, String className, String methodName,
                                           List<String> paramTypes) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }
        if (className == null || className.isBlank()) {
            throw new BusinessException(400, "类名不能为空");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new BusinessException(400, "方法名不能为空");
        }

        Path projectDir = ensureExtracted(projectId);

        // 遍历所有 .java 文件，定位目标类
        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(projectDir)) {
            javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new BusinessException(500, "遍历项目源码失败");
        }

        // 按文件名匹配类名（Java 规范要求 public class 与文件名一致，这里放宽为包含目标类即可）
        for (Path javaFile : javaFiles) {
            String relativePath = projectDir.relativize(javaFile).toString().replace("\\", "/");
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(javaFile);
            } catch (Exception e) {
                continue;
            }
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!className.equals(type.getNameAsString())) {
                    continue;
                }
                // 找到目标类，在其方法中匹配
                List<MethodDeclaration> candidates = type.getMethods().stream()
                        .filter(m -> methodName.equals(m.getNameAsString()))
                        .filter(m -> matchParameters(m, paramTypes))
                        .collect(Collectors.toList());

                if (candidates.isEmpty()) {
                    continue;
                }
                if (candidates.size() > 1) {
                    throw new BusinessException(400, "方法匹配不唯一，存在多个重载，请提供更精确的参数类型");
                }

                MethodDeclaration method = candidates.get(0);

                // 访问修饰符校验：仅允许 public 和 default（包级可见）
                String access = method.getAccessSpecifier().asString();
                if ("private".equals(access) || "protected".equals(access)) {
                    throw new BusinessException(400, "暂不支持为 private/protected 方法生成测试");
                }

                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");
                String classSource;
                try {
                    classSource = Files.readString(javaFile);
                } catch (IOException e) {
                    classSource = cu.toString();
                }

                MethodInfo methodInfo = toMethodInfo(method);
                return new MethodContext(packageName, className, relativePath, classSource, methodInfo);
            }
        }

        throw new BusinessException(404, "未找到方法: " + className + "." + methodName);
    }

    @Override
    public Path getProjectDir(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(400, "项目 ID 不能为空");
        }
        return ensureExtracted(projectId);
    }

    /**
     * 确保项目已解压。若解压目录已存在则直接复用，不再重新解压。
     * 解压失败时清理本次产生的不完整目录，保留原始 ZIP。
     */
    private Path ensureExtracted(String projectId) {
        Path zipFile = Paths.get(projectStoragePath).toAbsolutePath().normalize()
                .resolve(projectId + ".zip");
        if (!Files.exists(zipFile)) {
            throw new BusinessException(404, "项目不存在");
        }

        Path projectDir = Paths.get(extractedStoragePath).toAbsolutePath().normalize()
                .resolve(projectId);
        if (Files.exists(projectDir)) {
            return projectDir;
        }

        try {
            Files.createDirectories(projectDir);
            extractZip(zipFile, projectDir);
        } catch (IOException e) {
            log.error("项目解压失败, projectId={}", projectId, e);
            // 清理本次产生的不完整目录，保留原始 ZIP
            try {
                deleteDirectory(projectDir);
            } catch (IOException cleanupEx) {
                log.warn("清理不完整解压目录失败, projectId={}", projectId, cleanupEx);
            }
            throw new BusinessException(500, "项目解压失败");
        }
        return projectDir;
    }

    /**
     * 匹配方法参数。比较参数数量和参数类型字符串（去除空白后比较）。
     */
    private boolean matchParameters(MethodDeclaration method, List<String> expectedTypes) {
        List<String> actualTypes = method.getParameters().stream()
                .map(p -> normalizeType(p.getType().asString()))
                .collect(Collectors.toList());
        List<String> expected = (expectedTypes == null ? List.<String>of() : expectedTypes).stream()
                .map(this::normalizeType)
                .collect(Collectors.toList());
        return actualTypes.equals(expected);
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        // 去除多余空白，保留泛型尖括号内的内容
        return type.replaceAll("\\s+", "").trim();
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        // Phase 1: 预扫描所有条目，规范化路径并推断哪些是目录
        // ZIP 规范要求目录条目以 '/' 结尾且 isDirectory()=true，但 Windows 工具常不遵守
        // 推断规则：如果某条目不显式标记为目录，但其自身 isDirectory()=true
        //           或以 '/' 结尾，或后续存在子条目引用它作为父路径，则视为目录
        Set<String> inferredDirs = new HashSet<>();
        List<String> allNames = new ArrayList<>();
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String normalized = entry.getName().replace("\\", "/");
                allNames.add(normalized);
                if (entry.isDirectory() || normalized.endsWith("/")) {
                    // 显式目录或尾斜杠目录 → 去掉末尾斜杠加入推断集合
                    String dirKey = normalized.endsWith("/")
                            ? normalized.substring(0, normalized.length() - 1)
                            : normalized;
                    if (!dirKey.isEmpty()) {
                        inferredDirs.add(dirKey);
                    }
                }
                zis.closeEntry();
            }
        }

        // 根据所有条目名构建完整目录集合：任何作为其他条目父路径的名称都是目录
        for (String name : allNames) {
            // 对每个条目的所有父路径，都加入推断目录集合
            String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            int slashIdx = trimmed.indexOf('/');
            while (slashIdx >= 0) {
                inferredDirs.add(trimmed.substring(0, slashIdx));
                slashIdx = trimmed.indexOf('/', slashIdx + 1);
            }
        }

        // Phase 2: 正式解压
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");

                // 路径穿越校验
                Path resolved = targetDir.resolve(name).normalize();
                if (!resolved.startsWith(targetDir.normalize())) {
                    throw new IOException("非法的 ZIP 条目路径: " + entry.getName());
                }

                // 综合判断是否目录：显式标记 / 尾斜杠 / 推断目录集合
                String dirKey = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                boolean isDir = entry.isDirectory()
                        || name.endsWith("/")
                        || inferredDirs.contains(dirKey);

                try {
                    if (isDir) {
                        Files.createDirectories(resolved);
                    } else {
                        if (resolved.getParent() != null) {
                            Files.createDirectories(resolved.getParent());
                        }
                        Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.error("ZIP 条目解压失败, entry={}", entry.getName(), e);
                    throw e;
                }
                zis.closeEntry();
            }
        }
    }

    private ParsedFile parseJavaFile(Path javaFile, String relativePath) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        List<ClassInfo> classes = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            classes.add(toClassInfo(type));
        }
        return new ParsedFile(relativePath, packageName, classes);
    }

    private ClassInfo toClassInfo(TypeDeclaration<?> type) {
        String className = type.getNameAsString();
        String classType = resolveClassType(type);
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            methods.add(toMethodInfo(method));
        }
        return new ClassInfo(className, classType, methods);
    }

    private String resolveClassType(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration) {
            return ((ClassOrInterfaceDeclaration) type).isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        return "class";
    }

    private MethodInfo toMethodInfo(MethodDeclaration method) {
        String name = method.getNameAsString();
        String returnType = method.getType().asString();
        String accessModifier = method.getAccessSpecifier().asString();
        if (accessModifier.isEmpty()) {
            accessModifier = "default";
        }
        boolean isStatic = method.isStatic();
        List<MethodParameter> parameters = method.getParameters().stream()
                .map(p -> new MethodParameter(p.getNameAsString(), p.getType().asString()))
                .collect(Collectors.toList());
        String source = method.toString();
        return new MethodInfo(name, returnType, accessModifier, isStatic, parameters, source);
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> sorted = paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .collect(Collectors.toList());
            for (Path p : sorted) {
                Files.deleteIfExists(p);
            }
        }
    }
}
