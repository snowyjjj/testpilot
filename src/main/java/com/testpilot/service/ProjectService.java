package com.testpilot.service;

import com.testpilot.dto.MethodContext;
import com.testpilot.dto.ProjectParseResponse;
import com.testpilot.dto.ProjectUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

public interface ProjectService {

    ProjectUploadResponse uploadProject(MultipartFile file);

    ProjectParseResponse parseProject(String projectId);

    /**
     * 定位被测方法的完整上下文（包名、类名、类源码、方法信息、源文件相对路径）。
     * 支持方法重载：通过 paramTypes 精确匹配。
     *
     * @param projectId  项目 ID
     * @param className  类名
     * @param methodName 方法名
     * @param paramTypes 参数类型列表（顺序敏感），可为空表示无参方法
     */
    MethodContext getMethodContext(String projectId, String className, String methodName, List<String> paramTypes);

    /**
     * 返回项目解压根目录的绝对路径。仅当项目已解压时返回。
     */
    Path getProjectDir(String projectId);

    /**
     * 返回 Maven 项目根目录（包含 pom.xml 的目录）。
     * 兼容 ZIP 内包含单层子目录的情况：先查解压根目录，再向下搜索一层。
     * 找不到 pom.xml 时抛出业务异常。
     */
    Path getMavenProjectDir(String projectId);
}
