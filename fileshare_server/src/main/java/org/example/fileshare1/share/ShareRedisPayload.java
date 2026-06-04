package org.example.fileshare1.share;

// 分享载荷：创建分享时序列化成 JSON 存进 Redis 的“完整描述”。
// 字段故意冗余保存 fileName/contentType/ownerUserId：
// 访客下载时若节点已改名/换归属，仍能靠这些字段做一致性校验。

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShareRedisPayload {
    private long fileId;
    private long ownerUserId;
    private String fileName;
    private String contentType;
    /** BCrypt 哈希；null 表示无需 PIN */
    private String pinHash;
}
