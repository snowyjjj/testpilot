package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassInfo implements Serializable {

    private String className;

    private String classType;

    private List<MethodInfo> methods;
}
