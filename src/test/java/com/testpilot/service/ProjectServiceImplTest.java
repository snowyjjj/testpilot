package com.testpilot.service;

import com.testpilot.common.exception.BusinessException;
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
}
