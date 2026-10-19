package com.zkjs.aio.swap;

import com.zkjs.aio.mybatisplus.migration.core.DatabaseMigrationRunner;
import com.zkjs.aio.mybatisplus.migration.enums.DbProductType;
import com.zkjs.aio.mybatisplus.migration.enums.MigrationDialect;
import com.zkjs.aio.mybatisplus.migration.model.MigrationScript;
import com.zkjs.aio.mybatisplus.migration.model.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AIO Framework 数据库脚本初始化与升级核心逻辑功能与Bug验证测试集
 *
 * @author wuyw
 */
public class DatabaseMigrationFrameworkTest {

    // =========================================================================
    // 1. SQL 语句分词解析器 (splitSqlStatements) 验证
    // =========================================================================
    @Nested
    @DisplayName("1. SQL 语句分词解析器测试")
    class SqlStatementSplitterTests {

        @Test
        @DisplayName("测试常规单语句与多语句分词")
        void testNormalStatements() {
            String sql = "CREATE TABLE t1 (id INT); INSERT INTO t1 VALUES (1);";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertEquals("CREATE TABLE t1 (id INT)", stmts.get(0));
            assertEquals("INSERT INTO t1 VALUES (1)", stmts.get(1));
        }

        @Test
        @DisplayName("测试末尾无分号的遗留语句处理")
        void testStatementWithoutTrailingSemicolon() {
            String sql = "CREATE TABLE t1 (id INT);\nINSERT INTO t1 VALUES (1)";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertEquals("CREATE TABLE t1 (id INT)", stmts.get(0));
            assertEquals("INSERT INTO t1 VALUES (1)", stmts.get(1));
        }

        @Test
        @DisplayName("测试字符串字面量中包含分号不被误切分")
        void testSemicolonInsideQuotes() {
            String sql = "INSERT INTO t1 (msg) VALUES ('Hello; World; This is a test');";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(1, stmts.size());
            assertEquals("INSERT INTO t1 (msg) VALUES ('Hello; World; This is a test')", stmts.get(0));
        }

        @Test
        @DisplayName("测试单引号转义字符 (反斜杠 \\' 与 双单引号 '')")
        void testEscapedQuotesInString() {
            String sql1 = "INSERT INTO t1 (msg) VALUES ('It\\'s; a test');";
            List<String> stmts1 = DatabaseMigrationRunner.splitSqlStatements(sql1);
            assertEquals(1, stmts1.size(), "反斜杠转义单引号不应导致提前闭合字符串");

            String sql2 = "INSERT INTO t1 (msg) VALUES ('It''s; a test');";
            List<String> stmts2 = DatabaseMigrationRunner.splitSqlStatements(sql2);
            assertEquals(1, stmts2.size(), "双单引号转义不应导致提前闭合字符串");
        }

        @Test
        @DisplayName("测试包含单行注释 (--) 与块级注释 (/* */) 中带分号")
        void testCommentsWithSemicolons() {
            String sql = """
                    -- 这是单行注释; 不应被切分
                    CREATE TABLE t1 (
                        id INT, -- 字段注释; 不切分
                        name VARCHAR(32) /* 块注释; 不切分 */
                    );
                    /*
                     * 多行块级注释;
                     * 分号不应切分;
                     */
                    INSERT INTO t1 (id, name) VALUES (1, 'ZKJS');
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertTrue(stmts.get(0).startsWith("CREATE TABLE t1"));
            assertTrue(stmts.get(1).startsWith("INSERT INTO t1"));
        }

        @Test
        @DisplayName("测试 MySQL / ClickHouse 特有单行注释符 (#)")
        void testHashComment() {
            String sql = """
                    # 这是 MySQL 特有的 # 注释; 不应切分
                    CREATE TABLE t2 (id INT);
                    # 另一条注释;
                    SELECT * FROM t2;
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertEquals("CREATE TABLE t2 (id INT)", stmts.get(0));
            assertEquals("SELECT * FROM t2", stmts.get(1));
        }

        @Test
        @DisplayName("测试 PostgreSQL / 信创数据库 Dollar Quote ($$ 或 $tag$) 内部分号不被误切分")
        void testDollarQuotePlpgsql() {
            String plpgsql = """
                    CREATE OR REPLACE FUNCTION update_user_status() RETURNS void AS $$
                    BEGIN
                        UPDATE sys_user SET status = 1 WHERE active = true;
                        INSERT INTO sys_log (action) VALUES ('STATUS_UPDATED');
                    END;
                    $$ LANGUAGE plpgsql;
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(plpgsql);
            assertEquals(1, stmts.size(), "PostgreSQL 函数体内部的分号不应被拆分成独立语句");
            assertTrue(stmts.get(0).contains("LANGUAGE plpgsql"));
        }

        @Test
        @DisplayName("测试多个连续空分号不产生空语句")
        void testConsecutiveEmptySemicolons() {
            String sql = "SELECT 1;;; SELECT 2;;";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertEquals("SELECT 1", stmts.get(0));
            assertEquals("SELECT 2", stmts.get(1));
        }

        @Test
        @DisplayName("测试空串与纯注释脚本")
        void testEmptyOrOnlyComments() {
            assertTrue(DatabaseMigrationRunner.splitSqlStatements("").isEmpty());
            assertTrue(DatabaseMigrationRunner.splitSqlStatements("   \n\t  ").isEmpty());
            assertTrue(DatabaseMigrationRunner.splitSqlStatements("-- 仅有注释\n/* 块注释 */").isEmpty());
        }

        @Test
        @DisplayName("测试 MySQL 复杂存储过程与 DELIMITER 自定义定界符分词支持（验证 1.0.9 修复）")
        void testSplitSqlStatementsWithDelimiter() {
            String script = """
                    -- 准备基础表
                    CREATE TABLE sys_config (
                        config_id INT PRIMARY KEY,
                        config_name VARCHAR(64)
                    );
                    
                    -- MySQL 复杂存储过程 (使用 DELIMITER //，内含多行普通分号与单行注释)
                    DELIMITER //
                    CREATE PROCEDURE update_config_value(IN p_id INT, IN p_name VARCHAR(64))
                    BEGIN
                        DECLARE v_count INT DEFAULT 0;
                        SELECT COUNT(*) INTO v_count FROM sys_config WHERE config_id = p_id;
                        IF v_count > 0 THEN
                            UPDATE sys_config SET config_name = p_name WHERE config_id = p_id;
                        ELSE
                            INSERT INTO sys_config (config_id, config_name) VALUES (p_id, p_name);
                        END IF;
                    END //
                    DELIMITER ;
                    
                    -- MySQL 触发器 (使用 DELIMITER $$)
                    DELIMITER $$
                    CREATE TRIGGER trg_config_audit BEFORE INSERT ON sys_config
                    FOR EACH ROW
                    BEGIN
                        SET NEW.config_name = UPPER(NEW.config_name);
                    END $$
                    DELIMITER ;
                    
                    -- 常规插入语句
                    INSERT INTO sys_config VALUES (1, 'demo');
                    """;

            List<String> statements = DatabaseMigrationRunner.splitSqlStatements(script);
            assertEquals(4, statements.size(), "必须精确切分为 4 条独立 SQL 语句，DELIMITER 指令需剥离且代码块内部分号不被截断");
            assertTrue(statements.get(0).startsWith("CREATE TABLE sys_config"));
            assertTrue(statements.get(1).startsWith("CREATE PROCEDURE update_config_value"));
            assertTrue(statements.get(1).contains("SELECT COUNT(*) INTO v_count FROM sys_config WHERE config_id = p_id;"));
            assertTrue(statements.get(1).endsWith("END"));
            assertTrue(statements.get(2).startsWith("CREATE TRIGGER trg_config_audit"));
            assertTrue(statements.get(2).endsWith("END"));
            assertTrue(statements.get(3).startsWith("INSERT INTO sys_config"));
        }
    }

    // =========================================================================
    // 2. 跨平台 Checksum 换行符归一化与防篡改验证 (calculateNormalizedChecksum)
    // =========================================================================
    @Nested
    @DisplayName("2. Checksum 计算与跨平台防漂移测试")
    class ChecksumNormalizationTests {

        @Test
        @DisplayName("验证 Windows CRLF 与 Linux LF 计算出的 Checksum 绝对一致 (防跨平台漂移)")
        void testCrlfVsLfConsistency() {
            String linuxLf = "CREATE TABLE sys_user (\n    id BIGINT PRIMARY KEY,\n    username VARCHAR(64)\n);\n";
            String windowsCrlf = "CREATE TABLE sys_user (\r\n    id BIGINT PRIMARY KEY,\r\n    username VARCHAR(64)\r\n);\r\n";
            String macClassicCr = "CREATE TABLE sys_user (\r    id BIGINT PRIMARY KEY,\r    username VARCHAR(64)\r);\r";

            int checksumLf = DatabaseMigrationRunner.calculateNormalizedChecksum(linuxLf);
            int checksumCrlf = DatabaseMigrationRunner.calculateNormalizedChecksum(windowsCrlf);
            int checksumCr = DatabaseMigrationRunner.calculateNormalizedChecksum(macClassicCr);

            assertEquals(checksumLf, checksumCrlf, "Windows CRLF 与 Linux LF 的 Checksum 必须完全一致！");
            assertEquals(checksumLf, checksumCr, "Mac Classic CR 与 Linux LF 的 Checksum 必须完全一致！");
        }

        @Test
        @DisplayName("验证文件内容一旦被篡改，Checksum 立即改变")
        void testChecksumAlteredDetection() {
            String originalSql = "CREATE TABLE t (id INT);";
            String alteredSql = "CREATE TABLE t (id BIGINT);";

            int originalChecksum = DatabaseMigrationRunner.calculateNormalizedChecksum(originalSql);
            int alteredChecksum = DatabaseMigrationRunner.calculateNormalizedChecksum(alteredSql);

            assertNotEquals(originalChecksum, alteredChecksum, "修改 SQL 内容后 Checksum 必须发生改变");
        }

        @Test
        @DisplayName("验证首尾空白字符自动 trim 不影响 Checksum")
        void testTrimSurroundingWhitespace() {
            String rawSql = "CREATE TABLE t (id INT);";
            String sqlWithSpaces = "\n\n   CREATE TABLE t (id INT);   \n\t";

            assertEquals(
                    DatabaseMigrationRunner.calculateNormalizedChecksum(rawSql),
                    DatabaseMigrationRunner.calculateNormalizedChecksum(sqlWithSpaces)
            );
        }
    }

    // =========================================================================
    // 3. 语义化版本解析与排序规则验证 (MigrationVersion & MigrationScript)
    // =========================================================================
    @Nested
    @DisplayName("3. 语义化版本比对与排序规则测试")
    class SemanticVersioningTests {

        @Test
        @DisplayName("验证语义化版本顺序: V1.2.0 必须先于 V1.10.0 执行 (严禁字符串 ASCII 排序)")
        void testSemanticVersionVsAsciiSort() {
            MigrationVersion v1_2 = new MigrationVersion("1.2.0");
            MigrationVersion v1_10 = new MigrationVersion("1.10.0");
            MigrationVersion v1_2_1 = new MigrationVersion("1.2.1");
            MigrationVersion v2_0 = new MigrationVersion("2.0.0");

            assertTrue(v1_2.compareTo(v1_10) < 0, "1.2.0 应当小于 1.10.0");
            assertTrue(v1_2.compareTo(v1_2_1) < 0, "1.2.0 应当小于 1.2.1");
            assertTrue(v1_10.compareTo(v2_0) < 0, "1.10.0 应当小于 2.0.0");
        }

        @Test
        @DisplayName("验证不同长度段的版本比对: 1.0 与 1.0.0 视为等价")
        void testVersionDifferentLengths() {
            MigrationVersion v1 = new MigrationVersion("1.0");
            MigrationVersion v2 = new MigrationVersion("1.0.0");
            MigrationVersion v3 = new MigrationVersion("1.0.0.1");

            assertEquals(0, v1.compareTo(v2), "1.0 与 1.0.0 应当等价");
            assertTrue(v2.compareTo(v3) < 0, "1.0.0 应当小于 1.0.0.1");
        }

        @Test
        @DisplayName("验证日期时间格式版本比对: 20261012.01 < 20261012.02 < 20261015.01")
        void testTimestampVersionSorting() {
            MigrationVersion d1 = new MigrationVersion("20261012.01");
            MigrationVersion d2 = new MigrationVersion("20261012.02");
            MigrationVersion d3 = new MigrationVersion("20261015.01");

            assertTrue(d1.compareTo(d2) < 0);
            assertTrue(d2.compareTo(d3) < 0);
        }

        private org.springframework.core.io.Resource createMockResource(String filename) {
            return new ByteArrayResource("".getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
        }

        @Test
        @DisplayName("验证脚本文件名正则解析 (SCRIPT_PATTERN)")
        void testScriptFilenameParsing() {
            MigrationScript s1 = MigrationScript.parse(createMockResource("V1.0.0__init_schema.sql"));
            assertNotNull(s1, "V1.0.0__init_schema.sql 应当被成功解析");
            assertEquals("V", s1.getScriptType());
            assertEquals("1.0.0", s1.getVersion().getRawVersion());
            assertEquals("init_schema", s1.getDescription());

            MigrationScript s2 = MigrationScript.parse(createMockResource("R__create_views.sql"));
            assertNotNull(s2, "R__create_views.sql 应当被成功解析");
            assertEquals("R", s2.getScriptType());
            assertEquals("create_views", s2.getDescription());

            // 非法脚本命名应当被过滤解析为 null
            MigrationScript invalid1 = MigrationScript.parse(createMockResource("init_schema.sql"));
            assertNull(invalid1, "缺少 V/R 前缀的脚本应当返回 null");

            MigrationScript invalid2 = MigrationScript.parse(createMockResource("V1.0.0_missing_double_underscore.sql"));
            assertNull(invalid2, "缺少双下划线 __ 的脚本应当返回 null");
        }

        @Test
        @DisplayName("验证执行顺序规范: 所有 V 版本脚本必须优先于 R 可重复脚本执行")
        void testVPrecedesR() {
            MigrationScript v2 = MigrationScript.parse(createMockResource("V2.0.0__upgrade.sql"));
            MigrationScript v1 = MigrationScript.parse(createMockResource("V1.0.0__init.sql"));
            MigrationScript r1 = MigrationScript.parse(createMockResource("R__user_view.sql"));
            MigrationScript r2 = MigrationScript.parse(createMockResource("R__log_view.sql"));

            List<MigrationScript> list = new ArrayList<>(List.of(r2, v2, r1, v1));
            Collections.sort(list);

            assertEquals("V1.0.0__init.sql", list.get(0).getFileName());
            assertEquals("V2.0.0__upgrade.sql", list.get(1).getFileName());
            assertEquals("R__log_view.sql", list.get(2).getFileName());
            assertEquals("R__user_view.sql", list.get(3).getFileName());
        }
    }

    // =========================================================================
    // 4. 多数据库方言识别与路由边界测试 (MigrationDialect & DbProductType)
    // =========================================================================
    @Nested
    @DisplayName("4. 数据库方言识别与建库合规测试")
    class DialectRoutingTests {

        @Test
        @DisplayName("验证全部 6 种预置数据库方言根据 URL 与 Driver 精准识别")
        void testResolveAllPredefinedDialects() {
            assertEquals(MigrationDialect.MYSQL,
                    MigrationDialect.resolve("jdbc:mysql://localhost:3306/db", "com.mysql.cj.jdbc.Driver", null));
            assertEquals(MigrationDialect.CLICKHOUSE,
                    MigrationDialect.resolve("jdbc:clickhouse://localhost:8123/default", "com.clickhouse.jdbc.ClickHouseDriver", null));
            assertEquals(MigrationDialect.POSTGRESQL,
                    MigrationDialect.resolve("jdbc:postgresql://localhost:5432/db", "org.postgresql.Driver", null));
            assertEquals(MigrationDialect.DM,
                    MigrationDialect.resolve("jdbc:dm://localhost:5236/db", "dm.jdbc.driver.DmDriver", null));
            assertEquals(MigrationDialect.HIGHGO,
                    MigrationDialect.resolve("jdbc:highgo://localhost:5866/db", "com.highgo.jdbc.Driver", null));
            assertEquals(MigrationDialect.KINGBASE,
                    MigrationDialect.resolve("jdbc:kingbase8://localhost:54321/db", "com.kingbase8.Driver", null));
        }

        @Test
        @DisplayName("验证信创国产数据库 (HighGo / KingbaseES) 不会被误判为 PostgreSQL")
        void testHighGoAndKingbaseNotConfusedWithPostgres() {
            // 即使底层内核兼容 PG，也必须识别为各自专有信创方言
            MigrationDialect hg = MigrationDialect.resolve("jdbc:highgo://10.0.0.1:5866/db", "com.highgo.jdbc.Driver", null);
            assertEquals(MigrationDialect.HIGHGO, hg);
            assertEquals("rdbms/highgo", hg.getSubDirectory());

            MigrationDialect kb = MigrationDialect.resolve("jdbc:kingbase8://10.0.0.1:54321/db", "com.kingbase8.Driver", null);
            assertEquals(MigrationDialect.KINGBASE, kb);
            assertEquals("rdbms/kingbase", kb.getSubDirectory());
        }

        @Test
        @DisplayName("验证信创合规约束: 达梦 (DM)、瀚高 (HighGo)、人大金仓 (Kingbase) 严格禁止应用层自动建库")
        void testAutoCreateComplianceRules() {
            // 允许自动建库的数据库
            assertTrue(DbProductType.MYSQL.isAutoCreateSupported());
            assertTrue(DbProductType.POSTGRESQL.isAutoCreateSupported());
            assertTrue(DbProductType.CLICKHOUSE.isAutoCreateSupported());

            // 信创合规严格禁止应用自动建库的数据库
            assertFalse(DbProductType.DM.isAutoCreateSupported(), "信创规范: 达梦数据库必须由运维/DBA提前初始化！");
            assertFalse(DbProductType.HIGHGO.isAutoCreateSupported(), "信创规范: 瀚高数据库必须由运维/DBA提前初始化！");
            assertFalse(DbProductType.KINGBASE.isAutoCreateSupported(), "信创规范: 人大金仓必须由运维/DBA提前初始化！");
        }

        @Test
        @DisplayName("【框架缺陷复测】验证 MariaDB 驱动连接串识别情况（验证 1.0.9 BUG-MIGRATION-002 已修复）")
        void testMariaDbDialectResolution() {
            // 1. 测试纯 MariaDB 连接串 (jdbc:mariadb:)
            MigrationDialect dialect1 = MigrationDialect.resolve("jdbc:mariadb://localhost:3306/db", "org.mariadb.jdbc.Driver", null);
            assertEquals(MigrationDialect.MYSQL, dialect1, "jdbc:mariadb 协议在 1.0.9 中必须精准解析为 MYSQL 方言");

            // 2. 测试仅根据 Driver 类名识别
            MigrationDialect dialect2 = MigrationDialect.resolve("jdbc:custom://localhost:3306/db", "org.mariadb.jdbc.Driver", null);
            assertEquals(MigrationDialect.MYSQL, dialect2, "org.mariadb.jdbc.Driver 在 1.0.9 中必须精准解析为 MYSQL 方言");

            // 3. 测试 MetaData productName 为 MariaDB
            java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
            java.sql.DatabaseMetaData meta = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
            try {
                org.mockito.Mockito.when(conn.getMetaData()).thenReturn(meta);
                org.mockito.Mockito.when(meta.getDatabaseProductName()).thenReturn("MariaDB");
                MigrationDialect dialect3 = MigrationDialect.resolve("jdbc:custom://localhost:3306/db", null, conn);
                assertEquals(MigrationDialect.MYSQL, dialect3, "连接元数据为 MariaDB 时必须精准解析为 MYSQL 方言");
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================================
    // 5. 达梦数据库多 Schema 动态解析测试 (验证 1.0.9 BUG-MIGRATION-003 已修复)
    // =========================================================================
    @Nested
    @DisplayName("5. 达梦数据库多 Schema 动态解析测试 (验证 1.0.9 BUG-MIGRATION-003 已修复)")
    class DmSchemaResolutionTests {

        @Test
        @DisplayName("验证达梦优先从当前连接获取 Schema")
        void testResolveFromConnectionSchema() throws Exception {
            java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
            org.mockito.Mockito.when(conn.getSchema()).thenReturn("CUSTOM_SCHEMA");
            com.baomidou.dynamic.datasource.creator.DataSourceProperty prop = new com.baomidou.dynamic.datasource.creator.DataSourceProperty();
            prop.setUsername("SYSDBA");
            prop.setUrl("jdbc:dm://localhost:5236");

            String schema = DatabaseMigrationRunner.resolveDmSchema(conn, prop);
            assertEquals("CUSTOM_SCHEMA", schema);
        }

        @Test
        @DisplayName("验证达梦从 JDBC URL 的 schema 参数中解析")
        void testResolveFromUrlSchema() {
            com.baomidou.dynamic.datasource.creator.DataSourceProperty prop = new com.baomidou.dynamic.datasource.creator.DataSourceProperty();
            prop.setUrl("jdbc:dm://localhost:5236?schema=TENANT_SCHEMA&appName=aio");
            prop.setUsername("SYSDBA");

            String schema = DatabaseMigrationRunner.resolveDmSchema(null, prop);
            assertEquals("TENANT_SCHEMA", schema);
        }

        @Test
        @DisplayName("验证达梦从 JDBC URL 的 currentSchema 参数中解析")
        void testResolveFromUrlCurrentSchema() {
            com.baomidou.dynamic.datasource.creator.DataSourceProperty prop = new com.baomidou.dynamic.datasource.creator.DataSourceProperty();
            prop.setUrl("jdbc:dm://localhost:5236?currentSchema=TENANT_01");
            prop.setUsername("SYSDBA");

            String schema = DatabaseMigrationRunner.resolveDmSchema(null, prop);
            assertEquals("TENANT_01", schema);
        }

        @Test
        @DisplayName("验证达梦无配置时回退到用户名")
        void testResolveFallbackToUsername() {
            com.baomidou.dynamic.datasource.creator.DataSourceProperty prop = new com.baomidou.dynamic.datasource.creator.DataSourceProperty();
            prop.setUrl("jdbc:dm://localhost:5236");
            prop.setUsername("SYSDBA");

            String schema = DatabaseMigrationRunner.resolveDmSchema(null, prop);
            assertEquals("SYSDBA", schema);
        }
    }

    // =========================================================================
    // 6. RedisStarter 自动装配注解测试 (验证 1.0.9 BUG-CACHE-001 已修复)
    // =========================================================================
    @Nested
    @DisplayName("6. RedisStarter 自动装配注解测试 (验证 1.0.9 BUG-CACHE-001 已修复)")
    class RedisStarterAutoConfigureBeforeTests {

        @Test
        @DisplayName("验证 RedisConfig 标注 @AutoConfigureBefore 且涵盖 Spring Boot 4 DataRedisAutoConfiguration")
        void testRedisConfigAutoConfigureBefore() {
            org.springframework.boot.autoconfigure.AutoConfigureBefore annotation =
                    com.zkjs.aio.cache.config.RedisConfig.class.getAnnotation(org.springframework.boot.autoconfigure.AutoConfigureBefore.class);
            assertNotNull(annotation, "RedisConfig 必须标注 @AutoConfigureBefore");
            List<String> names = List.of(annotation.name());
            assertTrue(names.contains("org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"),
                    "必须优先于 Spring Boot 4 DataRedisAutoConfiguration");
            assertTrue(names.contains("org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"),
                    "必须平滑兼容 Spring Boot 3 RedisAutoConfiguration");
        }
    }
}
