package org.example.fileshare1.dto;

// 访客查看分享元信息时的响应：文件名/是否要 PIN/剩余秒数。

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShareMetaResponse {
    private String fileName;
    private String contentType;
    private boolean requiresPin;
    /** Redis 剩余 TTL（秒），-1 表示无 TTL（不应出现） */
    private long ttlSecondsRemaining;
}
