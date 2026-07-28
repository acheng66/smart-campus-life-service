-- 店铺归属迁移：可重复执行。
-- owner_id 指向 tb_user.id；商家只能管理 owner_id 等于自己的店铺。

SET @schema_name = DATABASE();
SELECT COUNT(*) INTO @owner_column_exists
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'tb_shop'
  AND column_name = 'owner_id';

SET @migration_sql = IF(
    @owner_column_exists = 0,
    'ALTER TABLE tb_shop ADD COLUMN owner_id BIGINT UNSIGNED NULL COMMENT ''店主用户ID'' AFTER open_hours',
    'SELECT 1'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- 配置示例：先赋予用户商家角色，再将店铺归属给该用户。
-- UPDATE tb_user SET role = 2 WHERE id = 你的商家用户ID;
-- UPDATE tb_shop SET owner_id = 你的商家用户ID WHERE id = 你的店铺ID;
