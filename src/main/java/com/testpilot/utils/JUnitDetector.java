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
 * 检测语义（以实际声明的 {@code <dependencies>} 为唯一依据）：
 * <ul>
 *   <li>{@code junit:junit} → JUNIT4</li>
 *   <li>{@code org.junit.jupiter} 组下任意依赖 → JUNIT5</li>
 *   <li>{@code org.springframework.boot:spring-boot-starter-test} 且版本 ≥ 2.2 → JUNIT5</li>
 *   <li>同时声明 JUnit 4 和 JUnit 5 → {@link JUnitVersion#CONFLICT}</li>
 * </ul>
 * <p>
 * 辅助信息（仅用于版本解析，不单独触发检测）：
 * <ul>
 *   <li>{@code <dependencyManagement>} 仅用于解析 {@code <dependencies>} 中未声明版本的依赖版本；
 *       其中出现的 junit-bom、junit-jupiter-bom 不代表项目实际使用了对应框架。</li>
 *   <li>{@code <parent>} 为 spring-boot-starter-parent 时，其版本仅用于解析
 *       实际声明的 {@code spring-boot-starter-test} 的版本；仅凭 Parent 不判定 JUnit 5。</li>
 * </ul>
 * 不解析远程父 POM，不处理嵌套多层 dependencyManagement import；
 * 版本完全由远程父 POM 管理且当前 pom 未声明时返回 UNKNOWN。
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
        // 禁用 XXE 相关功能，防止恶意 pom.xml 触发外部实体注入
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(pom.toFile());
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();

        // 解析 properties 用于 ${...} 版本替换
        Map<String, String> properties = parseProperties(root);
        properties.putIfAbsent("project.version", getDirectText(root, "version"));

        // dependencyManagement 仅用于版本解析，不参与框架判定
        Map<String, String> managedVersions = parseDependencyManagement(root, properties);

        // parent 仅用于解析 spring-boot-starter-test 的版本
        String springBootParentVersion = resolveSpringBootParentVersion(root, properties);

        // 仅扫描实际声明的 <dependencies>
        boolean foundJunit4 = false;
        boolean foundJunit5 = false;

        Element depsEl = getFirstChild(root, "dependencies");
        if (depsEl != null) {
            NodeList deps = depsEl.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String groupId = getDirectText(dep, "groupId");
                String artifactId = getDirectText(dep, "artifactId");
                if (groupId == null || artifactId == null) {
                    continue;
                }

                JUnitVersion matched = matchDependency(groupId, artifactId);
                if (matched == JUnitVersion.JUNIT4) {
                    foundJunit4 = true;
                } else if (matched == JUnitVersion.JUNIT5) {
                    foundJunit5 = true;
                }

                // spring-boot-starter-test 需结合版本判断
                if ("org.springframework.boot".equals(groupId)
                        && "spring-boot-starter-test".equals(artifactId)) {
                    String version = resolveDependencyVersion(dep, properties, managedVersions,
                            springBootParentVersion);
                    if (version != null && isSpringBoot22OrLater(version)) {
                        foundJunit5 = true;
                    }
                }
            }
        }

        if (foundJunit4 && foundJunit5) {
            log.warn("项目同时声明了 JUnit 4 和 JUnit 5 依赖，无法唯一确定测试框架: {}", pom);
            return JUnitVersion.CONFLICT;
        }
        if (foundJunit4) {
            return JUnitVersion.JUNIT4;
        }
        if (foundJunit5) {
            return JUnitVersion.JUNIT5;
        }
        return JUnitVersion.UNKNOWN;
    }

    /**
     * 解析 dependencyManagement 中的依赖版本，仅用作实际依赖的版本来源。
     */
    private static Map<String, String> parseDependencyManagement(Element root, Map<String, String> properties) {
        Map<String, String> managed = new HashMap<>();
        Element depMgmt = getFirstChild(root, "dependencyManagement");
        if (depMgmt == null) {
            return managed;
        }
        Element deps = getFirstChild(depMgmt, "dependencies");
        if (deps == null) {
            return managed;
        }
        NodeList list = deps.getElementsByTagName("dependency");
        for (int i = 0; i < list.getLength(); i++) {
            Element dep = (Element) list.item(i);
            String groupId = getDirectText(dep, "groupId");
            String artifactId = getDirectText(dep, "artifactId");
            String version = resolveVersion(getDirectText(dep, "version"), properties);
            if (groupId != null && artifactId != null && version != null) {
                managed.put(groupId + ":" + artifactId, version);
            }
        }
        return managed;
    }

    /**
     * 若 parent 为 spring-boot-starter-parent，返回其版本（用于解析无版本的 spring-boot-starter-test）。
     */
    private static String resolveSpringBootParentVersion(Element root, Map<String, String> properties) {
        Element parent = getFirstChild(root, "parent");
        if (parent == null) {
            return null;
        }
        String groupId = getDirectText(parent, "groupId");
        String artifactId = getDirectText(parent, "artifactId");
        String version = resolveVersion(getDirectText(parent, "version"), properties);
        if ("org.springframework.boot".equals(groupId)
                && "spring-boot-starter-parent".equals(artifactId)
                && version != null) {
            return version;
        }
        return null;
    }

    /**
     * 解析实际依赖的版本：显式 version → properties → dependencyManagement → spring-boot parent。
     */
    private static String resolveDependencyVersion(Element dep, Map<String, String> properties,
                                                   Map<String, String> managedVersions,
                                                   String springBootParentVersion) {
        String version = resolveVersion(getDirectText(dep, "version"), properties);
        if (version != null) {
            return version;
        }
        String groupId = getDirectText(dep, "groupId");
        String artifactId = getDirectText(dep, "artifactId");
        if (groupId != null && artifactId != null) {
            version = managedVersions.get(groupId + ":" + artifactId);
            if (version != null) {
                return version;
            }
        }
        // spring-boot-starter-test 继承自 spring-boot-starter-parent 的版本
        if ("org.springframework.boot".equals(groupId)
                && "spring-boot-starter-test".equals(artifactId)) {
            return springBootParentVersion;
        }
        return null;
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

    /**
     * 仅匹配实际会引入测试框架的依赖。junit-bom / junit-jupiter-bom 属于 BOM，
     * 不直接引入测试框架，故不匹配。
     */
    private static JUnitVersion matchDependency(String groupId, String artifactId) {
        if ("junit".equals(groupId) && "junit".equals(artifactId)) {
            return JUnitVersion.JUNIT4;
        }
        if (groupId.startsWith("org.junit.jupiter")) {
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
