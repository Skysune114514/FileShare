package org.example.fileshare1.mapper;

// 文件树 Mapper：通用 CRUD 继承自 BaseMapper，额外手写两条广场分页 SQL。

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.fileshare1.entity.FsNode;

import java.util.List;

/**
 * 文件树节点表 {@code fs_node} 数据访问：继承通用 CRUD，另含广场公开项目查询。
 */
@Mapper
public interface FsNodeMapper extends BaseMapper<FsNode> {

    /** 统计广场公开的一级项目（parent_id=0 且 is_public=1 的文件夹）总数。 */
    @Select("SELECT COUNT(*) FROM fs_node WHERE parent_id = 0 AND node_type = 'FOLDER' AND is_public = 1")
    long countPublicRootFolders();

    /** 分页查询广场公开项目，按 updated_at 降序。 */
    @Select("SELECT * FROM fs_node WHERE parent_id = 0 AND node_type = 'FOLDER' AND is_public = 1 ORDER BY updated_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<FsNode> selectPublicRootFolders(@Param("offset") long offset, @Param("limit") int limit);
}
