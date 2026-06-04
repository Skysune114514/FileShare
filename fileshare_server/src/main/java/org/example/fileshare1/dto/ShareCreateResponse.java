package org.example.fileshare1.dto;

// 创建分享成功的响应：code 是 10 位分享码，path 是前端短链路径。

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShareCreateResponse {
    private String code;
    /** 相对路径，如 /s/XXXX */
    private String path;
    private int ttlSeconds;
}
