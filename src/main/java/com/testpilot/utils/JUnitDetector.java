package com.testpilot.utils;

import com.testpilot.dto.JUnitVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从用户项目的 pom.xml 中检测 JUnit 版本。
 * <p>
 * 检测规则：
 * <ul>
 *   <li>直接依赖 junit:junit → JUNIT4</li>
 *   <li>依赖 org.junit.jupiter 组 → JUNIT5</li>
 *   <li>spring-boot-starter-test 且 Spring Boot ≥ 2.2 → JUNIT5</li>
 *   <li>父 POM 为 spring-boot-starter-parent 且版本 ≥ 2.2 → JUNIT5</li>
 *   <li>dependencyManagement 中含 junit-bom / junit-jupiter-bom → JUNIT5</li>
 * </ul>
 * 注意：本类不解析远程父 POM，也不处理嵌套的多层 dependencyManagement import。
 * 版本由父 POM 管理但未在当前 pom 中声明的情况可能无法识别，返回 UNKNOWN。
 */
public final class JUnitDetector {

    private static final Logger log = LoggerFactory.getLogger(JUnitDetector.class);

    private JUnitDetector() {
    }

    /**
     * 在项目根目录中查找 pom.xml 并检测 JUnit 版本。
     * 若根目录没有 pom.xml，则向下搜索一层（兼容 ZIP 内单层子目录结构）。
     */
    public static JUnitVersion detect(Path projectDir) {
        Path pom = findPom(projectDir);
        if (pom == null) {
            log.debug("未找到 pom.xml, projectDir={}", projectDir);
            return JUnitVersion.UNKNOWN;
        }
        try {
            return parsePom(pom);
        } catch (Exception e) {
            log.warn("解析 pom.xml 失败: {}", pom, e);
            return JUnitVersion.UNKNOWN;
        }
    }

    private static Path findPom(Path projectDir) {
        Path rootPom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            return rootPom;
        }
        // 向下搜索一层
        try (Stream<Path> stream = Files.list(projectDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static JUnitVersion parsePom(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(pom.toFile());
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();

        // 解析 properties 用于版本替换
        Map<String, String> properties = parseProperties(root);
        properties.putIfAbsent("project.version", getDirectText(root, "version"));

        JUnitVersion fromDeps = checkDependencies(root, properties, "dependencies/dependency");
        if (fromDeps != JUnitVersion.UNKNOWN) {
            return fromDeps;
        }

        JUnitVersion fromDepMgmt = checkDependencies(root, properties, "dependencyManagement/dependencies/dependency");
        if (fromDepMgmt != JUnitVersion.UNKNOWN) {
            return fromDepMgmt;
        }

        // 检查父 POM：spring-boot-starter-parent
        JUnitVersion fromParent = checkParent(root);
        if (fromParent != JUnitVersion.UNKNOWN) {
            return fromParent;
        }

        return JUnitVersion.UNKNOWN;
    }

    private static Map<String, String> parseProperties(Element root) {
        Map<String, String> props = new HashMap<>();
        Element propsEl = getFirstChild(root, "properties");
        if (propsEl != null) {
            NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    props.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        }
        return props;
    }

    private static JUnitVersion checkDependencies(Element root, Map<String, String> properties, String xpathLike) {
        String[] segments = xpathLike.split("/");
        Element container = root;
        for (int i = 0; i < segments.length - 1; i++) {
            container = getFirstChild(container, segments[i]);
            if (container == null) {
                return JUnitVersion.UNKNOWN;
            }
        }
        String depTag = segments[segments.length - 1];
        NodeList deps = container.getElementsByTagName(depTag);
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String groupId = getDirectText(dep, "groupId");
            String artifactId = getDirectText(dep, "artifactId");
            if (groupId == null || artifactId == null) {
                continue;
            }
            JUnitVersion v = matchDependency(groupId, artifactId);
            if (v != JUnitVersion.UNKNOWN) {
                return v;
            }
            // spring-boot-starter-test 需要结合版本判断
            if ("org.springframework.boot".equals(groupId) && "spring-boot-starter-test".equals(artifactId)) {
                String version = resolveVersion(getDirectText(dep, "version"), properties);
                if (version != null && isSpringBoot22OrLater(version)) {
                    return JUnitVersion.JUNIT5;
                }
            }
        }
        return JUnitVersion.UNKNOWN;
    }

    private static JUnitVersion matchDependency(String groupId, String artifactId) {
        if ("junit".equals(groupId) && "junit".equals(artifactId)) {
            return JUnitVersion.JUNIT4;
        }
        if (groupId.startsWith("org.junit.jupiter")) {
            return JUnitVersion.JUNIT5;
        }
        // JUnit BOM（用于 dependencyManagement）
        if ("org.junit".equals(groupId) && "junit-bom".equals(artifactId)) {
            return JUnitVersion.JUNIT5;
        }
        if ("org.junit.jupiter".equals(groupId) && "junit-jupiter-bom".equals(artifactId)) {
            return JUnitVersion.JUNIT5;
        }
        return JUnitVersion.UNKNOWN;
    }

    private static JUnitVersion checkParent(Element root) {
        Element parent = getFirstChild(root, "parent");
        if (parent == null) {
            return JUnitVersion.UNKNOWN;
        }
        String groupId = getDirectText(parent, "groupId");
        String artifactId = getDirectText(parent, "artifactId");
        String version = getDirectText(parent, "version");
        if ("org.springframework.boot".equals(groupId)
                && "spring-boot-starter-parent".equals(artifactId)
                && version != null && isSpringBoot22OrLater(version)) {
            return JUnitVersion.JUNIT5;
        }
        return JUnitVersion.UNKNOWN;
    }

    private static boolean isSpringBoot22OrLater(String version) {
        try {
            String clean = version.trim();
            // 去除 -SNAPSHOT 等后缀
            int dash = clean.indexOf('-');
            if (dash > 0) {
                clean = clean.substring(0, dash);
            }
            String[] parts = clean.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 2 || (major == 2 && minor >= 2);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String resolveVersion(String version, Map<String, String> properties) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String v = version.trim();
        if (v.startsWith("${") && v.endsWith("}")) {
            String key = v.substring(2, v.length() - 1);
            return properties.getOrDefault(key, null);
        }
        return v;
    }

    private static Element getFirstChild(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getParentNode() == parent) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String getDirectText(Element parent, String tagName) {
        Element child = getFirstChild(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }
}
