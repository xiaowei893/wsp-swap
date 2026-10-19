package com.zkjs.aio.swap;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.zkjs.aio.mybatisplus.config.AioDatasourceProperties;
import com.zkjs.aio.mybatisplus.migration.constant.DbSqlConstants;
import com.zkjs.aio.mybatisplus.migration.core.DatabaseMigrationRunner;
import com.zkjs.aio.mybatisplus.migration.enums.MigrationDialect;
import com.zkjs.aio.mybatisplus.migration.exception.MigrationChecksumMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 针对 DatabaseMigrationRunner 的深度模拟仿真与 Bug 探测测试
 * 覆盖表存在性检测、历史记录加载、V脚本不可变校验和防御、R脚本重新执行、事务回滚等核心逻辑
 *
 * @author wuyw
 */
public class DatabaseMigrationSimulationTest {

    private DynamicDataSourceProperties dynamicProperties;
    private AioDatasourceProperties aioProperties;
    private DatabaseMigrationRunner runner;

    @BeforeEach
    void setUp() {
        dynamicProperties = new DynamicDataSourceProperties();
        aioProperties = new AioDatasourceProperties();
        aioProperties.setAutoMigrate(true);
        aioProperties.setMigrationLocation("db-sql");
        runner = new DatabaseMigrationRunner(dynamicProperties, aioProperties);
    }

    // =========================================================================
    // 1. 复杂 SQL 分词边缘用例与异常格式容错性验证
    // =========================================================================
    @Nested
    @DisplayName("1. 复杂 SQL 分词与状态机边缘测试")
    class AdvancedSqlSplittingTests {

        @Test
        @DisplayName("测试双反斜杠转义后跟随单引号 (如路径 'C:\\\\Program Files\\\\MySQL\\')")
        void testEscapedBackslashesBeforeQuote() {
            // \\ 代表转义后的反斜杠，后面的 ' 是合法的闭合单引号
            String sql = "INSERT INTO sys_config VALUES ('C:\\\\path\\\\'); SELECT 1;";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(2, stmts.size());
            assertEquals("INSERT INTO sys_config VALUES ('C:\\\\path\\\\')", stmts.get(0));
            assertEquals("SELECT 1", stmts.get(1));
        }

        @Test
        @DisplayName("测试未闭合的单引号容错 (文件意外截断)")
        void testUnclosedQuoteGracefulHandling() {
            String sql = "INSERT INTO sys_config VALUES ('未闭合的字符串";
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(1, stmts.size());
            assertEquals("INSERT INTO sys_config VALUES ('未闭合的字符串", stmts.get(0));
        }

        @Test
        @DisplayName("测试带特定标签的 Dollar Quote (如 $procedure$ 和 $body$)")
        void testTaggedDollarQuotes() {
            String sql = """
                    CREATE FUNCTION test_tagged() RETURNS void AS $procedure$
                    DECLARE
                        v_name text;
                    BEGIN
                        SELECT name INTO v_name FROM users WHERE id = 1;
                        IF v_name IS NULL THEN
                            RAISE NOTICE 'User not found;';
                        END IF;
                    END;
                    $procedure$ LANGUAGE plpgsql;
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(1, stmts.size(), "$procedure$...$procedure$ 内部的分号绝对不能被拆开");
            assertTrue(stmts.get(0).contains("LANGUAGE plpgsql"));
        }

        @Test
        @DisplayName("测试多行注释中夹带单行注释符号与分号")
        void testNestedCommentMarkers() {
            String sql = """
                    /*
                     * 这是一个块级注释 -- 这里夹带单行注释标记;
                     * # 夹带井号注释;
                     */
                    SELECT 100;
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(sql);
            assertEquals(1, stmts.size());
            assertEquals("SELECT 100", stmts.get(0));
        }

        @Test
        @DisplayName("【架构局限性排查】MySQL 复杂存储过程/触发器 BEGIN...END 内部未采用 Dollar-Quote 时的分词行为")
        void testMysqlStoredProcedureLimitation() {
            // MySQL 存储过程常规使用 DELIMITER // ... // 语法
            // 但 JDBC 并不支持 DELIMITER 命令，且 splitSqlStatements 遇到内部分号时会无条件切分
            String mysqlProcedureBody = """
                    CREATE PROCEDURE update_status(IN p_id INT)
                    BEGIN
                        UPDATE orders SET status = 2 WHERE id = p_id;
                        INSERT INTO logs (action) VALUES ('UPDATED');
                    END;
                    """;
            List<String> stmts = DatabaseMigrationRunner.splitSqlStatements(mysqlProcedureBody);
            // 排查发现：当前解析器主要针对 PostgreSQL 优化了 $tag$ 语法，对于 MySQL BEGIN...END 内部的多分号会拆解为多条，
            // 因此下游在 MySQL 编写存储过程迁移脚本时需特别注意不能直接带内部多分号
            System.out.println("【框架排查】MySQL BEGIN...END 被切分为了 " + stmts.size() + " 条语句：");
            for (int i = 0; i < stmts.size(); i++) {
                System.out.println("  语句 " + (i + 1) + ": " + stmts.get(i).replace("\n", " "));
            }
            assertTrue(stmts.size() > 1, "检测到 MySQL 内部 BEGIN...END 分号被切分（属于当前版本分词器架构特性）");
        }
    }

    // =========================================================================
    // 2. 数据库迁移流程仿真测试 (基于 Mockito Connection 模拟)
    // =========================================================================
    @Nested
    @DisplayName("2. 数据库迁移生命周期与异常阻断测试")
    class MigrationLifecycleSimulationTests {

        private Connection mockConn;
        private Statement mockStmt;
        private PreparedStatement mockPs;
        private ResultSet mockRs;
        private DatabaseMetaData mockMetaData;

        private org.mockito.MockedStatic<DriverManager> mockedDriverManager;

        @BeforeEach
        void initMocks() throws SQLException {
            mockConn = mock(Connection.class);
            mockStmt = mock(Statement.class);
            mockPs = mock(PreparedStatement.class);
            mockRs = mock(ResultSet.class);
            mockMetaData = mock(DatabaseMetaData.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
            when(mockConn.getMetaData()).thenReturn(mockMetaData);
            when(mockConn.getCatalog()).thenReturn("wsp_swap");
            when(mockConn.getAutoCommit()).thenReturn(true);
            when(mockPs.executeQuery()).thenReturn(mockRs);
            when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);

            mockedDriverManager = mockStatic(DriverManager.class);
            mockedDriverManager.when(() -> DriverManager.getConnection(anyString(), any(), any()))
                    .thenReturn(mockConn);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            if (mockedDriverManager != null) {
                mockedDriverManager.close();
            }
        }

        @Test
        @DisplayName("测试首次迁移: sys_schema_history 表不存在时自动触发 DDL 建表")
        void testHistoryTableCreationWhenNotExists() throws Exception {
            DataSourceProperty prop = new DataSourceProperty();
            prop.setUrl("jdbc:mysql://127.0.0.1:3306/wsp_swap");
            prop.setDriverClassName("com.mysql.cj.jdbc.Driver");
            prop.setUsername("root");
            prop.setPassword("pass");

            // 模拟查询 sys_schema_history 第一次返回 0 (表不存在)
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getInt(1)).thenReturn(0);

            // 触发单个数据源迁移
            runner.migrateSingleDatasource("master", prop);

            // 验证是否调用了 CREATE TABLE sys_schema_history
            verify(mockStmt, atLeastOnce()).execute(contains(DbSqlConstants.SCHEMA_HISTORY_TABLE_NAME));
            verify(mockStmt, atLeastOnce()).execute(contains("CREATE TABLE"));
        }

        @Test
        @DisplayName("测试二次执行 (幂等性): 已执行过的脚本不再重复执行")
        void testIdempotencyAlreadyExecutedScripts() throws Exception {
            DataSourceProperty prop = new DataSourceProperty();
            prop.setUrl("jdbc:mysql://127.0.0.1:3306/wsp_swap");
            prop.setDriverClassName("com.mysql.cj.jdbc.Driver");
            prop.setUsername("root");
            prop.setPassword("pass");

            // 1. 模拟历史表已存在 (COUNT = 1)
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getInt(1)).thenReturn(1);

            // 2. 模拟加载历史记录：脚本 V1.0.0__init_schema.sql 已经执行，且 Checksum 一致
            // 计算当前 classpath 下 V1.0.0__init_schema.sql 真实的 Checksum
            String currentContent = new String(
                    getClass().getClassLoader().getResourceAsStream("db-sql/rdbms/mysql/V1.0.0__init_schema.sql").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
            int currentChecksum = DatabaseMigrationRunner.calculateNormalizedChecksum(currentContent);

            ResultSet historyRs = mock(ResultSet.class);
            when(mockStmt.executeQuery(contains("SELECT sql_file_name"))).thenReturn(historyRs);
            when(historyRs.next()).thenReturn(true, false);
            when(historyRs.getString("sql_file_name")).thenReturn("V1.0.0__init_schema.sql");
            when(historyRs.getInt("checksum")).thenReturn(currentChecksum);
            when(historyRs.getTimestamp("execute_time")).thenReturn(Timestamp.valueOf(LocalDateTime.now()));
            when(historyRs.getInt("success")).thenReturn(1);

            // 执行升级
            runner.migrateSingleDatasource("master", prop);

            // 验证：由于 Checksum 一致，严禁再次执行任何建表语句！
            verify(mockStmt, never()).execute(contains("CREATE TABLE IF NOT EXISTS `swap_task`"));
        }

        @Test
        @DisplayName("【重大安全特性验证】V 类脚本若被非法篡改，必须立即阻断并抛出 MigrationChecksumMismatchException")
        void testChecksumMismatchThrowsException() throws Exception {
            DataSourceProperty prop = new DataSourceProperty();
            prop.setUrl("jdbc:mysql://127.0.0.1:3306/wsp_swap");
            prop.setDriverClassName("com.mysql.cj.jdbc.Driver");
            prop.setUsername("root");
            prop.setPassword("pass");

            // 1. 模拟历史表已存在
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getInt(1)).thenReturn(1);

            // 2. 模拟加载历史记录中的 Checksum 与本地代码不一致（例如历史是 99999999）
            ResultSet historyRs = mock(ResultSet.class);
            when(mockStmt.executeQuery(contains("SELECT sql_file_name"))).thenReturn(historyRs);
            when(historyRs.next()).thenReturn(true, false);
            when(historyRs.getString("sql_file_name")).thenReturn("V1.0.0__init_schema.sql");
            when(historyRs.getInt("checksum")).thenReturn(99999999); // 错误的校验和
            when(historyRs.getTimestamp("execute_time")).thenReturn(Timestamp.valueOf(LocalDateTime.now()));
            when(historyRs.getInt("success")).thenReturn(1);

            // 验证必须抛出 MigrationChecksumMismatchException
            MigrationChecksumMismatchException exception = assertThrows(
                    MigrationChecksumMismatchException.class,
                    () -> runner.migrateSingleDatasource("master", prop)
            );

            System.out.println("【安全看板输出验证】检测到异常输出格式：\n" + exception.getMessage());
            assertTrue(exception.getMessage().contains("【AIO-FRAMEWORK 数据库版本校验失败】"));
            assertTrue(exception.getMessage().contains("V1.0.0__init_schema.sql"));
            assertTrue(exception.getMessage().contains("99999999"));
        }

        @Test
        @DisplayName("测试事务原子性保障: 关系型数据库脚本执行中途报错，必须自动触发 connection.rollback()")
        void testScriptFailureTriggersRollback() throws Exception {
            DataSourceProperty prop = new DataSourceProperty();
            prop.setUrl("jdbc:mysql://127.0.0.1:3306/wsp_swap");
            prop.setDriverClassName("com.mysql.cj.jdbc.Driver");
            prop.setUsername("root");
            prop.setPassword("pass");

            // 历史表已存在
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getInt(1)).thenReturn(1);

            // 模拟没有任何已执行记录（需要执行 V1.0.0__init_schema.sql）
            ResultSet historyRs = mock(ResultSet.class);
            when(mockStmt.executeQuery(contains("SELECT sql_file_name"))).thenReturn(historyRs);
            when(historyRs.next()).thenReturn(false);

            // 模拟执行 SQL 语句时抛出数据库异常 (如语法错误或磁盘满)
            doThrow(new SQLException("Table space full / syntax error"))
                    .when(mockStmt).execute(anyString());

            assertThrows(Exception.class, () -> runner.migrateSingleDatasource("master", prop));

            // 验证：必须执行 rollback 事务回滚！
            verify(mockConn, atLeastOnce()).rollback();
            // 验证：绝不能将失败脚本记录为成功！
            verify(mockPs, never()).executeUpdate();
        }
    }
}
