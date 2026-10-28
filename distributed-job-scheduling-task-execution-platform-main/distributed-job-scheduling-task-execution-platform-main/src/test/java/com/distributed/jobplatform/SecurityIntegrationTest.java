package com.distributed.jobplatform;

import com.distributed.jobplatform.config.TestConfig;
import com.distributed.jobplatform.dto.CreateJobRequest;
import com.distributed.jobplatform.entity.User;
import com.distributed.jobplatform.enums.JobType;
import com.distributed.jobplatform.repository.JobRepository;
import com.distributed.jobplatform.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String userAToken;
    private String userBToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(new User("userA", passwordEncoder.encode("passwordA"), "USER"));
        userRepository.save(new User("userB", passwordEncoder.encode("passwordB"), "USER"));
        userRepository.save(new User("admin", passwordEncoder.encode("admin123"), "ADMIN"));

        userAToken = loginAndGetToken("userA", "passwordA");
        userBToken = loginAndGetToken("userB", "passwordB");
        adminToken = loginAndGetToken("admin", "admin123");
    }

    // ===========================
    // Authentication Tests
    // ===========================

    @Test
    void accessProtectedEndpoint_withoutToken_returns401() throws Exception {
        // REST semantics: unauthenticated requests get 401, not 403
        mockMvc.perform(get("/api/jobs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withValidCredentials_returnsToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"userA\",\"password\":\"passwordA\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.username").value("userA"));
    }

    @Test
    void login_withInvalidCredentials_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"userA\",\"password\":\"wrongpassword\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getJobs_withInvalidToken_returns401() throws Exception {
        // Invalid token = unauthenticated = 401
        mockMvc.perform(get("/api/jobs")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }

    // ===========================
    // Registration Security (CRITICAL)
    // ===========================

    @Test
    void register_normalUser_createsUser() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.username").value("newuser"))
            // Registration must report/produce USER role
            .andExpect(jsonPath("$.role").value("USER"));

        // Verify in database
        User created = userRepository.findByUsername("newuser").orElseThrow();
        assertEquals("USER", created.getRole(), "Public registration must always create USER");
    }

    @Test
    void register_withRoleAdmin_doesNotEscalateToAdmin() throws Exception {
        // CRITICAL: role=ADMIN in the registration payload must NEVER create an ADMIN
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"attacker\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("USER"))
            .andReturn().getResponse().getContentAsString();

        User created = userRepository.findByUsername("attacker").orElseThrow();
        assertEquals("USER", created.getRole(),
            "Privilege escalation via registration role field must be blocked");
        assertFalse("ADMIN".equals(created.getRole()));

        // The "attacker" must not be able to access ADMIN endpoints
        String token = objectMapper.readTree(response).get("token").asText();
        mockMvc.perform(get("/api/jobs/admin/jobs")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"userA\",\"password\":\"password123\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void register_invalidInput_returns400() throws Exception {
        // Username too short + password too short
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"a\",\"password\":\"123\"}"))
            .andExpect(status().isBadRequest());
    }

    // ===========================
    // ADMIN Endpoint Authorization
    // ===========================

    @Test
    void adminEndpoint_asUser_returns403() throws Exception {
        mockMvc.perform(get("/api/jobs/admin/jobs")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoint_asAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/jobs/admin/jobs")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void adminEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/jobs/admin/jobs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void recoverEndpoint_asUser_returns403() throws Exception {
        UUID jobId = createJob(userAToken, "UserA Dead Job");
        mockMvc.perform(post("/api/jobs/" + jobId + "/recover")
                .header("Authorization", "Bearer " + userBToken))
            .andExpect(status().isForbidden());
    }

    // ===========================
    // Cross-User Ownership (CRITICAL)
    // ===========================

    @Test
    void userA_cannotAccessUserB_job_returns404() throws Exception {
        UUID jobId = createJob(userBToken, "UserB Private Job");

        mockMvc.perform(get("/api/jobs/" + jobId)
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void userA_cannotAccessUserB_attempts_returns404() throws Exception {
        UUID jobId = createJob(userBToken, "UserB Attempts Job");

        mockMvc.perform(get("/api/jobs/" + jobId + "/attempts")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void userA_cannotAccessUserB_result_returns404() throws Exception {
        UUID jobId = createJob(userBToken, "UserB Result Job");

        mockMvc.perform(get("/api/jobs/" + jobId + "/result")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void userA_cannotCancelUserB_job_returns404() throws Exception {
        UUID jobId = createJob(userBToken, "UserB Cancel Job");

        mockMvc.perform(post("/api/jobs/" + jobId + "/cancel")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void userA_cannotRetryUserB_job_returns404() throws Exception {
        UUID jobId = createJob(userBToken, "UserB Retry Job");

        mockMvc.perform(post("/api/jobs/" + jobId + "/retry")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void owner_canAccessOwn_jobAttemptsAndResult() throws Exception {
        UUID jobId = createJob(userAToken, "UserA Own Job");

        mockMvc.perform(get("/api/jobs/" + jobId)
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(jobId.toString()));

        mockMvc.perform(get("/api/jobs/" + jobId + "/attempts")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/jobs/" + jobId + "/result")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(result -> assertTrue(
                result.getResponse().getStatus() == 200 || result.getResponse().getStatus() == 204,
                "Owner accessing own job result must get 200 (result present) or 204 (no result yet)"));
    }

    // ===========================
    // Input Validation
    // ===========================

    @Test
    void createJob_unknownHandlerType_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad Job\",\"type\":\"NOT_A_TYPE\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createJob_oversizedPayload_returns400() throws Exception {
        // Payload > 10000 chars must be rejected
        String hugePayload = "{\"data\":\"" + "x".repeat(11000) + "\"}";
        mockMvc.perform(post("/api/jobs")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Big Payload\",\"type\":\"DEMO_TASK\",\"payload\":\"" + hugePayload.replace("\"", "") + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createJob_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs")
                .header("Authorization", "Bearer " + userAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DEMO_TASK\"}"))
            .andExpect(status().isBadRequest());
    }

    // ===========================
    // Health Check
    // ===========================

    @Test
    void healthEndpoint_accessible_withoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    // ===========================
    // Helper Methods
    // ===========================

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID createJob(String token, String name) throws Exception {
        CreateJobRequest request = new CreateJobRequest();
        request.setName(name);
        request.setType(JobType.DEMO_TASK);

        MvcResult result = mockMvc.perform(post("/api/jobs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
