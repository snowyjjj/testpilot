package com.testpilot.utils;

import com.testpilot.dto.CoverageMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析 JaCoCo XML 覆盖率报告（{@code target/site/jacoco/jacoco.xml}）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>只读取根 {@code <report>} 元素直接子级的 {@code <counter>}，
 *       不累计 package/class/method 层级的 counter，避免重复计数。</li>
 *   <li>对 XML 文件使用 NOFOLLOW_LINKS 检查，拒绝符号链接。</li>
 *   <li>XXE 防护：禁用外部实体、外部 DTD、XInclude，并关闭实体引用展开；
 *       不使用 disallow-doctype-decl，因为 JaCoCo XML 含合法 DOCTYPE 声明。</li>
 *   <li>三态 percentage：missed+covered=0 时 percentage=null（无统计意义）；
 *       available=false 表示 XML 无该 counter 类型。</li>
 * </ul>
 */
public final class JacocoReportParser {

    private static final Logger log = LoggerFactory.getLogger(JacocoReportParser.class);

    /** JaCoCo XML 中可能出现的 counter type 集合（按固定顺序输出） */
    private static final String[] COUNTER_TYPES = {
            "INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS"
    };

    private JacocoReportParser() {
    }

    /**
     * 解析指定 JaCoCo XML 文件，返回各项覆盖率指标。
     * <p>
     * 文件不存在、是符号链接、或 XML 损坏时返回空列表。
     * 列表中包含所有已知的 counter type，available=false 表示 XML 未报告该类型。
     *
     * @param jacocoXml jacoco.xml 文件路径
     * @return 覆盖率指标列表；失败时返回空列表
     */
    public static List<CoverageMetric> parse(Path jacocoXml) {
        List<CoverageMetric> result = new ArrayList<>();

        // 1. 基础检查
        if (jacocoXml == null || !Files.exists(jacocoXml, LinkOption.NOFOLLOW_LINKS)) {
            log.debug("jacoco.xml 不存在或为符号链接: {}", jacocoXml);
            buildEmptyMetrics(result);
            return result;
        }
        if (Files.isSymbolicLink(jacocoXml)) {
            log.debug("jacoco.xml 是符号链接，拒绝读取: {}", jacocoXml);
            buildEmptyMetrics(result);
            return result;
        }
        if (!Files.isRegularFile(jacocoXml, LinkOption.NOFOLLOW_LINKS)) {
            log.debug("jacoco.xml 不是普通文件: {}", jacocoXml);
            buildEmptyMetrics(result);
            return result;
        }

        // 2. 解析 XML
        //    注意：JaCoCo 生成的 jacoco.xml 包含合法 DOCTYPE 声明
        //    （<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">），
        //    因此不能设置 disallow-doctype-decl=true，否则解析直接抛 SAXException。
        //    通过禁用外部实体、外部 DTD、XInclude 并关闭实体引用来防御 XXE。
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            // 安全特性无法设置时必须显式失败，不能静默降级为不安全配置
            throw new IllegalStateException("无法配置 JaCoCo XML 解析的 XXE 安全特性", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document doc;
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(jacocoXml.toFile());
        } catch (Exception e) {
            log.warn("解析 JaCoCo XML 失败: {}", jacocoXml, e);
            buildEmptyMetrics(result);
            return result;
        }

        // 3. 提取根 <report> 直接子级的 <counter>
        //    Jacoco XML 结构：<report name="..."> 作为根元素
        Element root = doc.getDocumentElement();
        // 根元素可能是 <report> 或其他异常情况
        if (!"report".equals(root.getTagName())) {
            log.warn("JaCoCo XML 根元素不是 <report>，而是 <{}>", root.getTagName());
            buildEmptyMetrics(result);
            return result;
        }

        NodeList children = root.getChildNodes();
        java.util.Map<String, long[]> counters = new java.util.HashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            // 只取直接子级 counter，跳过 package/class/method 等其他子元素
            if (!"counter".equals(child.getNodeName())) {
                continue;
            }
            Element counterElem = (Element) child;
            String type = counterElem.getAttribute("type");
            // 父节点必须是 report（防御）
            if (!(counterElem.getParentNode() instanceof Element)
                    || !"report".equals(counterElem.getParentNode().getNodeName())) {
                continue;
            }
            // 防御 package/class/method 等层级的 counter 被误提取：
            // 已通过只遍历 report 的直接子节点排除
            try {
                int missed = Integer.parseInt(counterElem.getAttribute("missed"));
                int covered = Integer.parseInt(counterElem.getAttribute("covered"));
                counters.put(type, new long[]{missed, covered});
            } catch (NumberFormatException ignored) {
                // 跳过无效 counter
            }
        }

        // 4. 构建结果（所有已知 type 都输出，缺失的 available=false）
        for (String type : COUNTER_TYPES) {
            long[] mc = counters.get(type);
            if (mc != null) {
                int missed = (int) mc[0];
                int covered = (int) mc[1];
                int total = missed + covered;
                // COMPLEXITY 不计算百分比，仅保留 missed/covered 原始计数；
                // 其他指标在 total>0 时计算覆盖率百分比。
                Double percentage = "COMPLEXITY".equals(type)
                        ? null
                        : (total > 0 ? covered * 100.0 / total : null);
                result.add(new CoverageMetric(type, true, missed, covered, percentage));
            } else {
                result.add(new CoverageMetric(type, false, 0, 0, null));
            }
        }

        return result;
    }

    /**
     * 判断 metrics 中是否至少有一项 available=true。
     * 解析成功但所有 counter 都不存在（不应该发生，正常 jacoco.xml 至少有 INSTRUCTION）
     * 也视为无有效数据。
     */
    public static boolean hasValidCounters(List<CoverageMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return false;
        }
        for (CoverageMetric m : metrics) {
            if (m.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    private static void buildEmptyMetrics(List<CoverageMetric> result) {
        for (String type : COUNTER_TYPES) {
            result.add(new CoverageMetric(type, false, 0, 0, null));
        }
    }
}
