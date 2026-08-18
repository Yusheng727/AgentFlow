package com.agentflow.api.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolGrantRepository} 语义测试（v1.1 R21）。
 *
 * <p>InMemory 全行为；Jdbc 用 H2 建兼容 {@code caller_tool_grants} 表跑生产 SQL 常量
 * （单一真相源，不复制 SQL——对齐 PostgresCheckpointManagerTest 先例）。
 * 覆盖：grant/revoke、isGranted（含 * 通配）、findGrantedTools、totalGrantCount、幂等。
 */
class ToolGrantRepositoryTest {

    @Nested
    @DisplayName("InMemoryToolGrantRepository")
    class InMemoryTests {

        private ToolGrantRepository repo;

        @BeforeEach
        void setUp() {
            repo = new InMemoryToolGrantRepository();
        }

        @Test
        @DisplayName("grant → isGranted/findGrantedTools；revoke → 移除；幂等")
        void grantRevokeIdempotent() {
            repo.grant("caller-a", "finance-db-query", "admin");
            repo.grant("caller-a", "finance-db-query", "admin"); // 幂等

            assertThat(repo.isGranted("caller-a", "finance-db-query")).isTrue();
            assertThat(repo.isGranted("caller-a", "risk-calculator")).isFalse();
            assertThat(repo.findGrantedTools("caller-a")).containsExactly("finance-db-query");
            assertThat(repo.totalGrantCount()).isEqualTo(1);

            repo.revoke("caller-a", "finance-db-query");
            assertThat(repo.isGranted("caller-a", "finance-db-query")).isFalse();
            assertThat(repo.totalGrantCount()).isZero();
        }

        @Test
        @DisplayName("* 通配 → 任意 tool 授权")
        void wildcardGrantsAll() {
            repo.grant("caller-b", "*", "admin");
            assertThat(repo.isGranted("caller-b", "anything")).isTrue();
            assertThat(repo.isGranted("caller-c", "anything")).isFalse();
        }

        @Test
        @DisplayName("空/未知 caller → false")
        void unknownOrNullCallerDenied() {
            assertThat(repo.isGranted("nobody", "x")).isFalse();
            assertThat(repo.isGranted(null, "x")).isFalse();
            assertThat(repo.findGrantedTools("nobody")).isEmpty();
        }
    }

    @Nested
    @DisplayName("JdbcToolGrantRepository（H2 兼容表）")
    class JdbcTests {

        private JdbcTemplate jdbc;
        private ToolGrantRepository repo;

        @BeforeEach
        void setUp() throws Exception {
            DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:toolgrants;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            if (repo == null) {
                try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS caller_tool_grants");
                    st.execute("""
                            CREATE TABLE caller_tool_grants (
                                caller_id   VARCHAR(64)  NOT NULL,
                                tool_name   VARCHAR(128) NOT NULL,
                                granted_by  VARCHAR(64),
                                created_at  TIMESTAMP NOT NULL DEFAULT now(),
                                PRIMARY KEY (caller_id, tool_name)
                            )""");
                }
                repo = new JdbcToolGrantRepository(jdbc);
            }
        }

        @Test
        @DisplayName("SQL 语义：grant/isGranted/find/revoke/total/通配（跑生产代码常量）")
        void jdbcSemantics() {
            repo.grant("caller-a", "finance-db-query", "admin");
            repo.grant("caller-a", "tool-b", "admin");
            repo.grant("caller-a", "tool-b", "admin"); // 幂等（ON CONFLICT DO NOTHING）

            assertThat(repo.totalGrantCount()).isEqualTo(2);
            assertThat(repo.isGranted("caller-a", "tool-b")).isTrue();
            assertThat(repo.isGranted("caller-a", "other")).isFalse();
            assertThat(repo.findGrantedTools("caller-a")).containsExactly("finance-db-query", "tool-b");

            repo.revoke("caller-a", "tool-b");
            assertThat(repo.isGranted("caller-a", "tool-b")).isFalse();
            assertThat(repo.findGrantedTools("caller-a")).containsExactly("finance-db-query");
        }

        @Test
        @DisplayName("Jdbc 通配 * 行 → 任意 tool 授权")
        void jdbcWildcard() {
            repo.grant("op", "*", "admin");
            assertThat(repo.isGranted("op", "any-tool")).isTrue();
            assertThat(repo.isGranted("other", "any-tool")).isFalse();
        }
    }
}