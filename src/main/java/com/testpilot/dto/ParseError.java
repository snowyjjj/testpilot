package com.testpilot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseError implements Serializable {

    private String filePath;

    private String errorMessage;
}
