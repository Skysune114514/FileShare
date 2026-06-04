-- 演示管理员账号（幂等：email 有唯一索引，重复启动不会重复插入）
-- 登录账号：admin@fileshare.local
-- 密  码：admin123
-- role=admin，可直接访问 /api/admin/** 管理接口
INSERT IGNORE INTO `user` (name, email, password_hash, role)
VALUES ('admin', 'admin@fileshare.local', '$2b$10$9eMqwWnyzFSecHBcZaJs4OzV/8h7dJmK5ZhRFqaU9kYWstIbqmbF.', 'admin');
