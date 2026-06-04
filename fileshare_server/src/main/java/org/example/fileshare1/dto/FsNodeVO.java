package org.example.fileshare1.dto;

// 文件树节点“视图对象”：给前端看的文件/文件夹。
// 故意不返回实体的 storageKey（磁盘内部路径），避免泄露内部存储结构。

import lombok.Builder;
import lombok.Data;
import org.example.fileshare1.entity.FsNode;

import java.time.LocalDateTime;

/**
 * 返回给前端的节点视图，不包含 {@code storageKey} 等内部字段。
 */
@Data
@Builder
public class FsNodeVO {
    private Long id;
    private Long parentId;
    private String name;
    private String nodeType;
    private Long sizeBytes;
    private String contentType;
    private Long ownerUserId;
    /** 上传/创建时的署名快照 */
    private String uploadedByName;
    /** 上传/创建时的用户 id 快照（前端“能否删除本人上传”用） */
    private Long uploadedByUserId;
    /** 是否在广场对他人可见 */
    private Boolean isPublic;
    private LocalDateTime createdAt;
    /** 空间根下列项目时：成员姓名，英文逗号分隔（仅项目根文件夹有值） */
    private String projectMemberNames;

    /** 由实体转换为 VO */
    public static FsNodeVO from(FsNode n) {
        return FsNodeVO.builder()
                .id(n.getId())
                .parentId(n.getParentId())
                .name(n.getName())
                .nodeType(n.getNodeType())
                .sizeBytes(n.getSizeBytes())
                .contentType(n.getContentType())
                .ownerUserId(n.getOwnerUserId())
                .uploadedByName(n.getUploadedByName())
                .uploadedByUserId(n.getUploadedByUserId())
                .isPublic(n.getIsPublic())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
