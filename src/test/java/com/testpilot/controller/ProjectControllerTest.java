package com.testpilot.controller;

import com.testpilot.common.exception.BusinessException;
import com.testpilot.dto.ProjectUploadResponse;
import com.testpilot.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;

    @Test
    void upload_success_returnsHttp200() throws Exception {
        when(projectService.uploadProject(any()))
                .thenReturn(new ProjectUploadResponse("pid-123", "test.zip", "UPLOADED"));

        MockMultipartFile file = new MockMultipartFile("file", "test.zip",
                "application/zip", "zip-bytes".getBytes());

        mockMvc.perform(multipart("/api/projects/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value("pid-123"))
                .andExpect(jsonPath("$.data.fileName").value("test.zip"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"));
    }

    @Test
    void upload_businessException_returnsMatchingHttpStatus() throws Exception {
        when(projectService.uploadProject(any()))
                .thenThrow(new BusinessException(400, "无效的 ZIP 文件"));

        MockMultipartFile file = new MockMultipartFile("file", "bad.zip",
                "application/zip", "not-zip".getBytes());

        mockMvc.perform(multipart("/api/projects/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("无效的 ZIP 文件"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void upload_missingFileParam_returnsHttp400() throws Exception {
        mockMvc.perform(multipart("/api/projects/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少必填的 file 参数"));
    }
}
