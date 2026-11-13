package com.testpilot.controller;

import com.testpilot.common.Result;
import com.testpilot.dto.ProjectParseResponse;
import com.testpilot.dto.ProjectUploadResponse;
import com.testpilot.service.ProjectService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/upload")
    public Result<ProjectUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        ProjectUploadResponse response = projectService.uploadProject(file);
        return Result.success(response);
    }

    @PostMapping("/{projectId}/parse")
    public Result<ProjectParseResponse> parse(@PathVariable String projectId) {
        ProjectParseResponse response = projectService.parseProject(projectId);
        return Result.success(response);
    }
}
