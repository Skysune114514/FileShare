package org.example.fileshare1.dto;

// 广场首页卡片数据：一个公开项目对应一条记录。

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 广场网格卡片：一个「项目」对应用户空间根目录下已公开的文件夹，含所有者与上传者信息。
 */
@Data
@Builder
public class GalleryProjectVO {
    /** 项目根节点 id（进入目录浏览时作 parentId） */
    private Long id;
    private String name;
    private Long ownerUserId;
    /** 账号侧展示名 */
    private String ownerName;
    /** 创建项目时的署名快照 */
    private String uploadedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
