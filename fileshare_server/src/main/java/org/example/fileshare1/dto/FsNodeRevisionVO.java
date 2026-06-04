package org.example.fileshare1.dto;

// 历史版本展示对象：表示某文件在替换前保存下来的“那一版”。

import lombok.Builder;
import lombok.Data;
import org.example.fileshare1.entity.FsNodeRevision;

import java.time.LocalDateTime;

@Data
@Builder
public class FsNodeRevisionVO {
    private Long id;
    private Long nodeId;
    /** 快照时的文件名 */
    private String fileName;
    private Long sizeBytes;
    private String contentType;
    private LocalDateTime createdAt;
    private String createdByName;

    public static FsNodeRevisionVO from(FsNodeRevision r) {
        return FsNodeRevisionVO.builder()
                .id(r.getId())
                .nodeId(r.getNodeId())
                .fileName(r.getFileNameSnapshot())
                .sizeBytes(r.getSizeBytes())
                .contentType(r.getContentType())
                .createdAt(r.getCreatedAt())
                .createdByName(r.getCreatedByName())
                .build();
    }
}
