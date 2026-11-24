package com.example.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示用数据处理类。
 * <p>
 * 与 {@link Calculator} 相比，这里包含空值判断、循环、多分支与集合过滤，
 * 用于在 JaCoCo 报告中展示「行覆盖高、分支覆盖低」的典型差异。
 * <p>
 * 演示时通常只对 {@code Calculator} 生成测试，因此 {@code DataProcessor}
 * 会保持较低覆盖率——这正是覆盖率分析要展示的「测试盲区」。
 */
public class DataProcessor {

    /**
     * 求数组元素之和；null 或空数组返回 0。
     */
    public int sum(int[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    /**
     * 求平均值；null 或空数组返回 0.0（不抛异常，避免除零）。
     */
    public double average(int[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        return (double) sum(values) / values.length;
    }

    /**
     * 按分数区间返回等级。
     *
     * @param score 分数，必须在 0~100 之间
     * @return A（>=90）/ B（>=75）/ C（>=60）/ D（其余）
     * @throws IllegalArgumentException 分数越界时抛出
     */
    public String classify(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("分数必须在 0~100 之间");
        }
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        return "D";
    }

    /**
     * 过滤掉 null 与空白字符串，保持原有顺序；入参为 null 时返回空列表。
     */
    public List<String> filterNonBlank(List<String> inputs) {
        List<String> result = new ArrayList<>();
        if (inputs == null) {
            return result;
        }
        for (String input : inputs) {
            if (input != null && !input.isBlank()) {
                result.add(input);
            }
        }
        return result;
    }
}
