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
import com.testpilot.dto.MethodInfo;
import com.testpilot.dto.MethodParameter;
import com.testpilot.dto.ParseError;
import com.testpilot.dto.ParsedFile;
import com.testpilot.dto.ProjectParseResponse;
import com.testpilot.dto.ProjectUploadResponse;
import com.testpilot.service.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ProjectServiceImpl implements ProjectService {

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

        Path zipFile = Paths.get(projectStoragePath).toAbsolutePath().normalize()
                .resolve(projectId + ".zip");
        if (!Files.exists(zipFile)) {
            throw new BusinessException(404, "项目不存在");
        }

        Path projectDir = Paths.get(extractedStoragePath).toAbsolutePath().normalize()
                .resolve(projectId);
        try {
            if (Files.exists(projectDir)) {
                deleteDirectory(projectDir);
            }
            Files.createDirectories(projectDir);
            extractZip(zipFile, projectDir);
        } catch (IOException e) {
            throw new BusinessException(500, "项目解压失败");
        }

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

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");
                Path resolved = targetDir.resolve(name).normalize();
                if (!resolved.startsWith(targetDir.normalize())) {
                    throw new IOException("非法的 ZIP 条目路径: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
