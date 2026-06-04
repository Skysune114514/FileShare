package org.example.fileshare1.mapper;

// 历史快照 Mapper：只做通用 CRUD，所以是空壳。

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.fileshare1.entity.FsNodeRevision;

/**
 * 文件历史快照表 {@code fs_node_revision} 数据访问：替换文件时写入，每文件最多保留 20 条。
 */
@Mapper
public interface FsNodeRevisionMapper extends BaseMapper<FsNodeRevision> {
}
