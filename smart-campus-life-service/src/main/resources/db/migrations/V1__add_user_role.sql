-- 用户角色迁移：可重复执行。
-- 0 = 学生；1 = 管理员。
-- 执行后，请将下面的 1001 改成实际管理员用户 ID，再执行 UPDATE。

SET @schema_name = DATABASE();
SELECT COUNT(*) INTO @role_column_exists
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'tb_user'
  AND column_name = 'role';

SET @migration_sql = IF(
    @role_column_exists = 0,
    'ALTER TABLE tb_user ADD COLUMN role TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''用户角色：0学生，1管理员'' AFTER icon',
    'SELECT 1'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- 示例：将指定账号授予管理员。请先把 1001 改为自己的 tb_user.id。
-- UPDATE tb_user SET role = 1 WHERE id = 1001;
