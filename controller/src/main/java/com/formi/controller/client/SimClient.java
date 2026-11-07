package com.formi.controller.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.formi.controller.client.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Typed HTTP client for every simulator endpoint.
 *
 * Design decisions:
 *
 *  1. java.net.http.HttpClient — zero extra dependency; synchronous .send()
 *     is fine because our loop is intentionally single-threaded.
 *
 *  2. Retry policy: idempotent GETs retry up to MAX_RETRIES times with
 *     exponential backoff on IOException or 5xx. POSTs are NEVER retried —
 *     a duplicate POST /approvals with the same proposal_id returns 409, which
 *     is handled explicitly by the governance service (adopt the existing one).
 *
 *  3. Error mapping: each distinct HTTP error is mapped to its own checked
 *     exception subclass so callers are forced to handle them explicitly.
 *
 *  4. Snake-case JSON ↔ camelCase Java via Jackson's SNAKE_CASE naming strategy
 *     so the model classes use idiomatic Java names without @JsonProperty noise.
 */
public class SimClient {

    private static final Logger log = LoggerFactory.getLogger(SimClient.class);

    private static final int MAX_RETRIES   = 4;
    private static final long BACKOFF_MS   = 500;  // doubles each retry: 500, 1000, 2000, 4000

    private final String baseUrl;
    private final HttpClient http;
    final ObjectMapper mapper;    // package-private so tests can reach it

    public SimClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");  // strip trailing slash
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Health / State
    // ══════════════════════════════════════════════════════════════════════════

    /** GET /health — throws if the simulator is not reachable. */
    public void health() throws ApiException, IOException, InterruptedException {
        getJson("/health", new TypeReference<JsonNode>() {});
        log.info("Simulator health OK at {}", baseUrl);
    }

    /** GET /sim/state — returns current sim timestamp, finished flag, credits. */
    public SimState getSimState() throws ApiException, IOException, InterruptedException {
        return getJson("/sim/state", new TypeReference<>() {});
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Workflows
    // ══════════════════════════════════════════════════════════════════════════

    /** GET /workflows — list all workflows. */
    public List<WorkflowSummary> listWorkflows() throws ApiException, IOException, InterruptedException {
        return getJson("/workflows", new TypeReference<>() {});
    }

    /** GET /workflows/{id} — single workflow detail. */
    public WorkflowSummary getWorkflow(String workflowId) throws ApiException, IOException, InterruptedException {
        return getJson("/workflows/" + workflowId, new TypeReference<>() {});
    }

    /** GET /workflows/{id}/config — current config version. */
    public ConfigVersion getConfig(String workflowId) throws ApiException, IOException, InterruptedException {
        return getJson("/workflows/" + workflowId + "/config", new TypeReference<>() {});
    }

    /** GET /workflows/{id}/config/history — list of past config versions (array). */
    public List<JsonNode> getConfigHistory(String workflowId) throws ApiException, IOException, InterruptedException {
        return getJson("/workflows/" + workflowId + "/config/history", new TypeReference<>() {});
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Simulation control
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /sim/advance — advance simulated time by {@code minutes}.
     *
     * @throws FinishedException if the scenario has already ended.
     */
    public AdvanceResult advance(int minutes) throws ApiException, IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of("minutes", minutes));
        return postJson("/sim/advance", body, new TypeReference<>() {});
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Events / chunks
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET /events/chunks?kind=&after= — list chunk metadata.
     *
     * @param kind  "events", "history", or null for all
     * @param after return only chunks with number > after; use -1 for all
     */
    public List<ChunkMeta> listChunks(String kind, int after)
            throws ApiException, IOException, InterruptedException {
        String path = "/events/chunks?after=" + after;
        if (kind != null) path += "&kind=" + kind;
        return getJson(path, new TypeReference<>() {});
    }

    /**
     * GET /events/chunks/{n} — stream one chunk as NDJSON, calling {@code consumer}
     * for each parsed event line.
     *
     * We stream line-by-line rather than loading the whole chunk into memory because
     * the load scenario produces ~850k events/day across many chunks. Each line is
     * parsed independently so a bad line is logged and skipped without losing the rest.
     */
    public void streamChunk(int chunkNo, Consumer<JsonNode> consumer)
            throws ApiException, IOException, InterruptedException {

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/events/chunks/" + chunkNo))
                .GET()
                .timeout(Duration.ofSeconds(60))  // large chunks may take time
                .build();

        // Use InputStream so we can stream — don't buffer the whole response body
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() != 200) {
            String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw mapError(resp.statusCode(), errBody);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;    // NDJSON may have blank separators
                try {
                    consumer.accept(mapper.readTree(line));
                } catch (Exception e) {
                    log.warn("Chunk {} — skipping unparseable line: {} ({})", chunkNo, line, e.getMessage());
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Approvals
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * POST /approvals — submit a governance proposal.
     *
     * NOT retried. On 409 duplicate_proposal_id the caller (GovernanceService)
     * adopts the existing approval by looking it up with GET /approvals/{proposal_id}.
     *
     * @throws DuplicateProposalException if proposal_id already exists
     * @throws StaleBaseVersionException  if base_version is outdated
     */
    public ApprovalSubmitResult submitApproval(ApprovalRequest request)
            throws ApiException, IOException, InterruptedException {
        String body = mapper.writeValueAsString(request);
        return postJson("/approvals", body, new TypeReference<>() {});
    }

    /**
     * GET /approvals/{id} — poll one approval by its approval_id.
     * Retried (GET is idempotent).
     */
    public ApprovalStatus getApproval(String approvalId)
            throws ApiException, IOException, InterruptedException {
        return getJson("/approvals/" + approvalId, new TypeReference<>() {});
    }

    /**
     * GET /approvals?workflow_id=&status= — list approvals with optional filters.
     * Pass null for parameters you don't want to filter on.
     */
    public List<ApprovalStatus> listApprovals(String workflowId, String status)
            throws ApiException, IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/approvals?");
        if (workflowId != null) path.append("workflow_id=").append(workflowId).append("&");
        if (status     != null) path.append("status=").append(status).append("&");
        return getJson(path.toString(), new TypeReference<>() {});
    }

    /**
     * POST /workflows/{id}/apply — apply an approved proposal.
     *
     * NOT retried. Errors map to:
     *   409 stale_base_version → StaleBaseVersionException
     *   410                    → LapsedException
     *
     * @throws StaleBaseVersionException if config changed between approval and apply
     * @throws LapsedException           if the apply window expired
     */
    public void applyApproval(String workflowId, String approvalId)
            throws ApiException, IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of("approval_id", approvalId));
        postJson("/workflows/" + workflowId + "/apply", body, new TypeReference<JsonNode>() {});
    }

    /**
     * PUT /workflows/{id}/config — auto-apply without approval.
     *
     * Only call when the workflow's governance.auto_apply.enabled is true and
     * the changed dimensions are within allowed_dims and within max_shift.
     *
     * @throws AutoApplyNotPermittedException if the policy forbids it
     * @throws StaleBaseVersionException      if base_version is stale
     */
    public void autoApplyConfig(String workflowId, Map<String, Object> body)
            throws ApiException, IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        putJson("/workflows/" + workflowId + "/config", json);
    }

    /**
     * PUT /workflows/{id}/config — typed overload accepting a full proposed config
     * JSON node with the base_version and an optional diff for auditing.
     * Builds the standard body: {base_version, config, diff}.
     *
     * @throws AutoApplyNotPermittedException if the policy forbids it
     * @throws StaleBaseVersionException      if base_version is stale
     */
    public void putConfig(String workflowId, int baseVersion,
                          com.fasterxml.jackson.databind.JsonNode proposedConfig,
                          String summary, Map<String, Object> diff)
            throws ApiException, IOException, InterruptedException {
        String proposalId = "auto-" + Math.abs(proposedConfig.toString().hashCode()) + "-" + baseVersion;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("proposal_id", proposalId);
        body.put("base_version", baseVersion);
        body.put("proposed_config", mapper.convertValue(proposedConfig, new TypeReference<Map<String,Object>>(){}));
        body.put("rationale", summary);
        body.put("diff", diff);
        String json = mapper.writeValueAsString(body);
        putJson("/workflows/" + workflowId + "/config", json);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HTTP helpers — GET with retry, POST/PUT without retry
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GET with exponential backoff retry on IOException or 5xx.
     * We retry GETs because they are idempotent; we never retry POSTs.
     */
    private <T> T getJson(String path, TypeReference<T> type)
            throws ApiException, IOException, InterruptedException {

        IOException lastIo = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long sleepMs = BACKOFF_MS * (1L << (attempt - 1));
                log.debug("GET {} — retry {}/{} after {}ms", path, attempt, MAX_RETRIES - 1, sleepMs);
                Thread.sleep(sleepMs);
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() >= 500) {
                    log.warn("GET {} → {} (will retry)", path, resp.statusCode());
                    lastIo = new IOException("HTTP " + resp.statusCode());
                    continue;
                }
                if (resp.statusCode() != 200) {
                    throw mapError(resp.statusCode(), resp.body());
                }
                return mapper.readValue(resp.body(), type);
            } catch (IOException e) {
                lastIo = e;
                log.warn("GET {} — IO error: {} (will retry)", path, e.getMessage());
            }
        }
        throw new IOException("GET " + path + " failed after " + MAX_RETRIES + " attempts", lastIo);
    }

    /** POST — no retry. Maps HTTP errors to typed exceptions. */
    private <T> T postJson(String path, String json, TypeReference<T> type)
            throws ApiException, IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            // Some endpoints return an empty body on success (e.g. /apply)
            if (resp.body() == null || resp.body().isBlank()) return null;
            return mapper.readValue(resp.body(), type);
        }
        throw mapError(resp.statusCode(), resp.body());
    }

    /** PUT — no retry. */
    private void putJson(String path, String json)
            throws ApiException, IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 204) {
            throw mapError(resp.statusCode(), resp.body());
        }
    }

    /**
     * Map an HTTP status + body to the most specific ApiException subclass.
     *
     * We parse the body for the simulator's machine-readable error code.
     * If parsing fails we fall back to the generic ApiException.
     */
    private ApiException mapError(int status, String body) {
        String code = extractErrorCode(body);

        return switch (status) {
            case 402 -> new InsufficientCreditsException(body);
            case 403 -> new AutoApplyNotPermittedException(body);
            case 409 -> {
                if ("finished".equalsIgnoreCase(code) || (body != null && body.contains("\"finished\""))) {
                    yield new FinishedException(body);
                }
                if ("stale_base_version".equalsIgnoreCase(code) || (body != null && body.contains("stale_base_version"))) {
                    yield new StaleBaseVersionException(body);
                }
                if ("duplicate_proposal_id".equalsIgnoreCase(code) || (body != null && body.contains("duplicate_proposal_id"))) {
                    yield new DuplicateProposalException(body);
                }
                yield new ApiException(409, code, body);
            }
            case 410 -> new LapsedException(body);
            default  -> new ApiException(status, code, body);
        };
    }

    /** Best-effort extraction of the simulator's error code from a JSON body. */
    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) return "unknown";
        try {
            JsonNode node = mapper.readTree(body);
            // Check error / code first, then detail / message
            for (String field : new String[]{"error", "code", "detail", "message"}) {
                if (node.has(field)) return node.get(field).asText();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
