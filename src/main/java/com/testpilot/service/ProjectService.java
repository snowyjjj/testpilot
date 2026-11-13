package com.testpilot.service;

import com.testpilot.dto.ProjectUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ProjectService {

    ProjectUploadResponse uploadProject(MultipartFile file);
}
