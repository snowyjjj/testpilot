package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedFile implements Serializable {

    private String filePath;

    private String packageName;

    private List<ClassInfo> classes;
}
