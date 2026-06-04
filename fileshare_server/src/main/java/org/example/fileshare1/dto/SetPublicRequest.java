package org.example.fileshare1.dto;

// 设置公开/私有请求。JSON 字段叫 public（是 Java 关键字），
// 所以用 @JsonProperty("public") 映射到内部字段 value。

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SetPublicRequest {
    @JsonProperty("public")
    private Boolean value;
}
