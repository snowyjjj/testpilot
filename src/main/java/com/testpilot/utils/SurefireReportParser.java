package com.testpilot.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 解析 Maven Surefire 测试报告（target/surefire-reports/*.txt 和 *.xml）。
 * <p>
 * - TXT 报告用于汇总统计（totalTests / passedTests / failedTests / skippedTests）。
 * - XML 报告用于提取失败用例详情（类名、方法名、异常类型、消息、堆栈）。
 */
public final class SurefireReportParser {

    private static final Logger log = LoggerFactory.getLogger(SurefireReportParser.class);

    // 匹配 "Tests run: X, Failures: Y, Errors: Z, Skipped: W"
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private SurefireReportParser() {
    }

    /**
     * 解析指定 Surefire 报告目录下所有 .txt 文件并汇总统计。
     *
     * @param reportsDir target/surefire-reports 目录
     * @return 汇总结果；若目录不存在或无报告文件，reportFound 为 false
     */
    public static SurefireSummary parse(Path reportsDir) {
        if (reportsDir == null || !Files.isDirectory(reportsDir)) {
            log.debug("Surefire 报告目录不存在: {}", reportsDir);
            return new SurefireSummary(false, 0, 0, 0, 0);
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean found = false;

        try (Stream<Path> files = Files.list(reportsDir)) {
            List<Path> txtFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .toList();
            for (Path txt : txtFiles) {
                String content = Files.readString(txt, StandardCharsets.UTF_8);
                Matcher m = SUMMARY_PATTERN.matcher(content);
                if (m.find()) {
                    found = true;
                    total += Integer.parseInt(m.group(1));
                    failures += Integer.parseInt(m.group(2));
                    errors += Integer.parseInt(m.group(3));
                    skipped += Integer.parseInt(m.group(4));
                }
            }
        } catch (IOException e) {
            log.warn("读取 Surefire 报告失败: {}", reportsDir, e);
            return new SurefireSummary(false, 0, 0, 0, 0);
        }

        // failedTests = failures + errors
        int failed = failures + errors;
        int passed = total - failed - skipped;
        return new SurefireSummary(found, total, Math.max(0, passed), failed, skipped);
    }

    /**
     * 解析 Surefire XML 报告（TEST-*.xml），提取失败用例详情。
     * 仅返回有 failure 或 error 子元素的 testcase。
     *
     * @param reportsDir target/surefire-reports 目录
     * @param maxStackLength 每条失败堆栈的最大字符数，超出截断
     * @return 失败用例列表；目录不存在或无失败时返回空列表
     */
    public static List<FailedCase> parseXmlFailures(Path reportsDir, int maxStackLength) {
        List<FailedCase> result = new ArrayList<>();
        if (reportsDir == null || !Files.isDirectory(reportsDir)) {
            return result;
        }

        try (Stream<Path> files = Files.list(reportsDir)) {
            List<Path> xmlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
            for (Path xml : xmlFiles) {
                result.addAll(parseXmlFile(xml, maxStackLength));
            }
        } catch (IOException e) {
            log.warn("读取 Surefire XML 报告失败: {}", reportsDir, e);
        }
        return result;
    }

    private static List<FailedCase> parseXmlFile(Path xmlFile, int maxStackLength) {
        List<FailedCase> cases = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 防护
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile.toFile());
            doc.getDocumentElement().normalize();

            NodeList testCases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Element tc = (Element) testCases.item(i);
                String className = tc.getAttribute("classname");
                String methodName = tc.getAttribute("name");

                Element failure = getFirstChild(tc, "failure");
                Element error = getFirstChild(tc, "error");
                Element detail = failure != null ? failure : error;
                if (detail == null) {
                    continue;
                }
                String type = detail.getAttribute("type");
                String message = detail.getAttribute("message");
                String stack = detail.getTextContent();
                if (stack != null && stack.length() > maxStackLength) {
                    stack = stack.substring(0, maxStackLength) + "\n... (stack truncated)";
                }
                cases.add(new FailedCase(className, methodName, type, message, stack));
            }
        } catch (Exception e) {
            log.warn("解析 Surefire XML 失败: {}", xmlFile, e);
        }
        return cases;
    }

    private static Element getFirstChild(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i).getParentNode() == parent) {
                return (Element) list.item(i);
            }
        }
        return null;
    }

    @Getter
    @AllArgsConstructor
    public static class FailedCase {
        private final String className;
        private final String methodName;
        /** 异常类型，如 java.lang.AssertionError */
        private final String exceptionType;
        private final String message;
        /** 堆栈信息（已截断） */
        private final String stackTrace;
    }

    @Getter
    @AllArgsConstructor
    public static class SurefireSummary {
        /** 是否找到 Surefire 报告 */
        private final boolean reportFound;
        private final int totalTests;
        private final int passedTests;
        private final int failedTests;
        private final int skippedTests;
    }
}
