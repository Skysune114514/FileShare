package org.example.fileshare1.mapper;

// 用户表 Mapper：继承 MyBatis-Plus BaseMapper 后，selectById/selectOne/insert 等通用方法都有了。

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.fileshare1.entity.User;

/**
 * 用户表 {@code user} 数据访问：继承 MyBatis-Plus 通用 CRUD（按 id/email 查询等）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
