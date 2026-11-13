package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MethodInfo implements Serializable {

    private String name;

    private String returnType;

    private String accessModifier;

    private boolean isStatic;

    private List<MethodParameter> parameters;

    private String source;
}
