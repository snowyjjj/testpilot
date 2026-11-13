package com.testpilot.service.impl;

import com.testpilot.common.exception.BusinessException;
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
import java.util.UUID;
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
}
