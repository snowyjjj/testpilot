package com.testpilot.dto;

/**
 * 被测项目使用的 JUnit 版本。
 */
public enum JUnitVersion {

    JUNIT4("JUnit 4"),

    JUNIT5("JUnit 5"),

    UNKNOWN("未知");

    private final String displayName;

    JUnitVersion(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
