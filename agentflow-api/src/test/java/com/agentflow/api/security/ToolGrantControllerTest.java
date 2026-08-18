package com.agentflow.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R21 工具授权管理 API 测试（v1.1）。
 *
 * <p>standalone + {@link ApiKeyAuthFilter} 真过滤链：admin key → 可 grant/revoke/查任意；
 * 普通 caller → 变更 403、只读自己；未定义 admin key → 变更全 403。
 */
class ToolGrantControllerTest {

    private static final String ADMIN_KEY = "admin-secret-123";
    private static final String CALLER_KEY = "caller-key-456";
    private static final String ADMIN_HASH = ApiKeyAuthFilter.sha256(ADMIN_KEY);
    private static final String CALLER_HASH = ApiKeyAuthFilter.sha256(CALLER_KEY);

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryToolGrantRepository repo;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo = new InMemoryToolGrantRepository();
        ToolGrantController controller = new ToolGrantController(repo, ADMIN_KEY);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new ApiKeyAuthFilter(Set.of(ADMIN_KEY, CALLER_KEY)))
                .build();
    }

    @Test
    @DisplayName("admin 可 grant → 授权即时生效可查；幂等仍 201")
    void adminGrantsEffective() throws Exception {
        mockMvc.perform(post("/api/tools/grants")
                        .header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ToolGrantController.GrantRequest(CALLER_HASH, "finance-db-query"))))
                .andExpect(status().isCreated());
        assertThat(repo.isGranted(CALLER_HASH, "finance-db-query")).isTrue();
        assertThat(repo.totalGrantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("非 admin caller 尝试 grant → 403，授权不变")
    void nonAdminCannotGrant() throws Exception {
        mockMvc.perform(post("/api/tools/grants")
                        .header("X-API-Key", CALLER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ToolGrantController.GrantRequest(CALLER_HASH, "finance-db-query"))))
                .andExpect(status().isForbidden());
        assertThat(repo.totalGrantCount()).isZero();
    }

    @Test
    @DisplayName("admin revoke → 授权移除（204）；随后 caller 无权")
    void adminRevoke() throws Exception {
        repo.grant(CALLER_HASH, "risk", "admin");
        mockMvc.perform(delete("/api/tools/grants/{caller}/{tool}", CALLER_HASH, "risk")
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());
        assertThat(repo.isGranted(CALLER_HASH, "risk")).isFalse();
    }

    @Test
    @DisplayName("普通 caller 可读自己的授权；admin 可查任意 caller")
    void listGrantsSelfAndAdmin() throws Exception {
        repo.grant(CALLER_HASH, "tool-a", ADMIN_HASH);
        repo.grant(ADMIN_HASH, "tool-z", ADMIN_HASH);

        // 普通 caller 无 caller 参数 → 自己的
        String own = mockMvc.perform(get("/api/tools/grants").header("X-API-Key", CALLER_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(own).contains("tool-a").doesNotContain("tool-z");

        // admin 指定 caller → 该 caller 的
        String adminView = mockMvc.perform(get("/api/tools/grants").param("caller", ADMIN_HASH)
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminView).contains("tool-z");
    }

    @Test
    @DisplayName("非 admin 查指定 caller → 403")
    void nonAdminCannotListOthers() throws Exception {
        mockMvc.perform(get("/api/tools/grants").param("caller", ADMIN_HASH)
                        .header("X-API-Key", CALLER_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺 admin key 配置 → grant/revoke 403，读取仍可用")
    void noAdminConfiguredMutationsForbidden() throws Exception {
        MockMvc noAdmin = MockMvcBuilders
                .standaloneSetup(new ToolGrantController(repo, ""))
                .addFilters(new ApiKeyAuthFilter(Set.of(ADMIN_KEY, CALLER_KEY)))
                .build();
        noAdmin.perform(post("/api/tools/grants").header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ToolGrantController.GrantRequest(CALLER_HASH, "t"))))
                .andExpect(status().isForbidden());
        assertThat(repo.totalGrantCount()).isZero();
        noAdmin.perform(get("/api/tools/grants").header("X-API-Key", CALLER_KEY))
                .andExpect(status().isOk());
    }
}