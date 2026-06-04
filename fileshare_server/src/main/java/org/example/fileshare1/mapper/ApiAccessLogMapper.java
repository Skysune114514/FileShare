package org.example.fileshare1.mapper;

// 访问日志 Mapper：空壳，AOP/Filter/Admin 都通过它读写 api_access_log。

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.fileshare1.entity.ApiAccessLog;

/**
 * 接口访问日志表 {@code api_access_log} 数据访问：由 {@link org.example.fileshare1.aop.ApiAccessLogAspect} 写入。
 */
@Mapper
public interface ApiAccessLogMapper extends BaseMapper<ApiAccessLog> {
}
