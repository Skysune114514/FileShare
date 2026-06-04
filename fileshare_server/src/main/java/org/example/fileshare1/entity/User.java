package org.example.fileshare1.entity;

// 平台用户实体。注意表名是 user（SQL 保留字），所以 @TableName 里加了反引号。

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("`user`")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String email;
    /** BCrypt 密文，注册后写入 */
    private String passwordHash;
    /** user | admin */
    private String role;
}
