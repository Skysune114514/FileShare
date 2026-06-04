package org.example.fileshare1.entity;

// 历史快照实体：每次“替换文件”前，把旧文件的元数据+旧磁盘路径存成一行。

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fs_node_revision")
public class FsNodeRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeId;
    private String storageKey;
    private String fileNameSnapshot;
    private Long sizeBytes;
    private String contentType;
    private Long createdByUserId;
    private String createdByName;
    private LocalDateTime createdAt;
}
