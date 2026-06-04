package org.example.fileshare1.entity;

// 文件树节点实体（核心中的核心）：文件夹和文件共用一张表。
// parent_id=0 是虚拟“空间根”，一级文件夹代表一个项目；
// owner_user_id 决定这棵树属于谁，storageKey 是文件二进制在磁盘的相对路径。

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件系统树节点实体，对应表 {@code fs_node}。
 * 文件夹不占用 {@code storageKey}；文件在磁盘上的相对路径见 {@link #storageKey}。
 */
@Data
@TableName("fs_node")
public class FsNode {

    /** 文件夹节点类型值 */
    public static final String TYPE_FOLDER = "FOLDER";
    /** 文件节点类型值 */
    public static final String TYPE_FILE = "FILE";

    /** 虚拟根：{@code parent_id = 0} 表示挂在根下，表中无 id=0 的行 */
    public static final long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 父节点 id；{@link #ROOT_PARENT_ID} 表示空间根，其下的一级文件夹即「项目」根 */
    private Long parentId;
    /** 展示名（同父下唯一） */
    private String name;
    /** {@link #TYPE_FOLDER} 或 {@link #TYPE_FILE} */
    private String nodeType;
    /** 字节数；文件夹可为 0 */
    private Long sizeBytes;
    /** 相对 {@code file.storage.root} 的存储路径，仅文件有值 */
    private String storageKey;
    /** MIME，可选 */
    private String contentType;
    /** 归属用户 id，列表与写操作均按此隔离 */
    private Long ownerUserId;
    /** 创建时的用户展示名快照 */
    private String uploadedByName;
    /** 创建/上传时的用户 id 快照；老数据可能为空，删除鉴权优先按此判断 */
    private Long uploadedByUserId;
    /** 是否对广场访客公开（含进入子目录与下载公开文件） */
    private Boolean isPublic;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
