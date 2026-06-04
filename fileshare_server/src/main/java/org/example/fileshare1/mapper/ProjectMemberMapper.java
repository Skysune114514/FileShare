package org.example.fileshare1.mapper;

// 项目成员 Mapper：空壳，全靠 BaseMapper 的通用方法。

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.fileshare1.entity.ProjectMember;

/**
 * 项目成员表 {@code project_member} 数据访问：项目根 id + 用户 id + 角色（member/project_admin）。
 */
@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
}
