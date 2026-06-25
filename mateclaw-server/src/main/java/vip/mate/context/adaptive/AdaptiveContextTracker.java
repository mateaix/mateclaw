package vip.mate.context.adaptive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptive context window tracker — the probe layer core.
 *
 * <p>Maintains an independent window state machine for every
 * {@code (provider, modelName) pair. All signals come from production
 * LLM traffic at zero additional token cost.
 *
 * <h3>Tracking keys</h3>
 * <pre>
 *   primaryKey  = "provider:modelName"     — standard tracking
 *   gatewayKey  = "provider:modelName"     — same key, but in gateway-distribution mode
 *   actualKey   = "provider:actualModel"   — when the gateway exposes the real upstream model
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All state mutations go through {@code ConcurrentHashMap.compute atomic operations.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class AdaptiveContextTracker {

    private final AdaptiveContextProperties properties;
    private final JdbcTemplate jdbcTemplate;

    /** provider:modelName → window state (single-model tracking) */
    private final ConcurrentHashMap<String, ModelWindowState> states = new ConcurrentHashMap<>();

    /** provider:modelName → gateway distribution (multi-backend tracking) */
    private final ConcurrentHashMap<String, GatewayDistribution> gatewayDists = new ConcurrentHashMap<>();

    /** provider:actualModel → window state (gateway-exposed real model) */
    private final ConcurrentHashMap<String, ModelWindowState> actualModelStates = new ConcurrentHashMap<>();

    public AdaptiveContextTracker(AdaptiveContextProperties properties,
                                   JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    

    // ==================== Public API ====================

    /**
     * Record a successful LLM call.
     *
     * @param provider       provider ID
     * @param modelName      model name used in the request
     * @param actualModel    real upstream model from gateway response header (nullable)
     * @param promptTokens   actual prompt token count returned by the API
     */
    public void recordSuccess(String provider, String modelName,
                               String actualModel, int promptTokens) {
        if (!properties.isEnabled() || promptTokens <= 0) return;
        // Sanity: clamp absurdly large token counts (corrupted API response, etc.)
        if (promptTokens > ModelWindowState.GLOBAL_CEILING) promptTokens = ModelWindowState.GLOBAL_CEILING;

        // Gateway exposed the real model — track precisely
        if (actualModel != null && !actualModel.isBlank()
                && !actualModel.equals(modelName)) {
            recordSingleModelSuccess(key(provider, actualModel), promptTokens);
            return;
        

        // Check gateway mode
        String gwKey = key(provider, modelName);
        GatewayDistribution gwDist = gatewayDists.get(gwKey);
        if (gwDist != null && gwDist.isGatewayDetected()) {
            gwDist.recordSuccess(promptTokens);
            return;
        

        // Single-model tracking
        boolean changed = recordSingleModelSuccess(gwKey, promptTokens);

        // Feed the gateway detector in parallel
        if (gwDist == null && properties.isEnabled()) {
            gwDist = gatewayDists.computeIfAbsent(gwKey, k -> new GatewayDistribution());
        
        gwDist.recordSuccess(promptTokens);

        // Periodically check gateway pattern
        if (changed || gwDist.getObservationCount() % 10 == 0) {
            checkGatewayPattern(gwKey, gwDist);
        
    

    /**
     * Record a prompt-too-long overflow.
     *
     * @param provider         provider ID
     * @param modelName        model name used in the request
     * @param attemptedTokens  estimated prompt token count before the failed call
     */
    public void recordOverflow(String provider, String modelName, int attemptedTokens) {
        if (!properties.isEnabled() || attemptedTokens <= 0) return;
        // Sanity: clamp absurd overflow values (network error misreported as PTL, etc.)
        if (attemptedTokens > ModelWindowState.GLOBAL_CEILING * 2) attemptedTokens = ModelWindowState.GLOBAL_CEILING;

        String key = key(provider, modelName);
        GatewayDistribution gwDist = gatewayDists.get(key);
        

        
            
        

        ModelWindowState state = states.compute(key, (k, existing) -> {
            if (existing == null) existing = loadOrCreate(k);
            boolean overflowChanged = existing.recordOverflow(attemptedTokens);
            if (overflowChanged) persistToDb(key, existing);
            return existing;
        );
        if (state != null) {
            log.info("[AdaptiveContext] Overflow for {: window={, phase={",
                    key, state.getEffectiveWindow(), state.phase);
        
    

    /**
     * Get the current effective context window for budget allocation.
     *
     * @return effective window in tokens, or 0 when the tracker has no data
     *         (caller should fall back to yml / provider defaults)
     */
    public int getEffectiveWindow(String provider, String modelName) {
        if (!properties.isEnabled()) return 0;

        String key = key(provider, modelName);

        // Gateway mode: use the P10 safe window
        GatewayDistribution gwDist = gatewayDists.get(key);
        if (gwDist != null && gwDist.isGatewayDetected()) {
            return gwDist.computeSafeWindow();
        

        ModelWindowState state = states.get(key);
        if (state != null) {
            return state.getEffectiveWindow();
        

        // No tracking data — caller should use yml / provider defaults
        return 0;
    

    /**
     * Get the full window state snapshot for monitoring / logging.
     * Returns {@code null when the model has never been tracked.
     */
    public ModelWindowState getState(String provider, String modelName) {
        return states.get(key(provider, modelName));
    

    /**
     * Whether the given model is currently classified as gateway multi-backend.
     */
    public boolean isGatewayMode(String provider, String modelName) {
        if ("off".equalsIgnoreCase(properties.getGatewayMode())) return false;
        if ("on".equalsIgnoreCase(properties.getGatewayMode())) return true;
        GatewayDistribution d = gatewayDists.get(key(provider, modelName));
        return d != null && d.isGatewayDetected();
    

    // ==================== Internals ====================

    private String key(String provider, String modelName) {
        return provider + ":" + (modelName != null ? modelName : "");
    

    private boolean recordSingleModelSuccess(String key, int promptTokens) {
        ModelWindowState state = states.compute(key, (k, existing) -> {
            if (existing == null) existing = loadOrCreate(k);
            existing.recordSuccess(promptTokens);
            return existing;
        );
        if (state != null && state.phase != ModelWindowState.Phase.STABLE && state.successiveSuccess <= 1) {
            persistToDb(key, state);
        
        return state != null && state.successiveSuccess == 1;
    

    private ModelWindowState loadOrCreate(String key) {
        ModelWindowState fromDb = loadFromDb(key);
        if (fromDb != null) return fromDb;
        return ModelWindowState.coldStart(ModelWindowState.COLD_SEED_FALLBACK);
    

    private void checkGatewayPattern(String key, GatewayDistribution dist) {
        // Gateway detection is opt-in: gateways are transparent by design,
        // so auto-detection is unreliable. User must set gateway-mode: on explicitly.
        if ("off".equalsIgnoreCase(properties.getGatewayMode())) return;
        if ("on".equalsIgnoreCase(properties.getGatewayMode())) {
            ModelWindowState s = states.get(key);
            if (s != null) s.gatewayMode = true;
            return;
        

        if (dist.detectGatewayPattern()) {
            log.info("[AdaptiveContext] Gateway pattern detected for {", key);
            ModelWindowState s = states.get(key);
            if (s != null) {
                s.gatewayMode = true;
                int safeWindow = dist.computeSafeWindow();
                if (safeWindow < s.effectiveWindow) {
                    s.effectiveWindow = safeWindow;
                    s.confidenceUpper = dist.getMaxSuccessToken();
                
            
        
    

    // ==================== DB persistence ====================

    private ModelWindowState loadFromDb(String key) {
        if (!properties.isDbPersist() || jdbcTemplate == null) return null;
        try {
            String[] parts = key.split(":", 2);
            if (parts.length != 2) return null;
            return jdbcTemplate.query(
                    "SELECT effective_window, confidence_lower, confidence_upper, phase, " +
                    "is_gateway, peak_observed, successive_success, successive_overflow, " +
                    "last_success_at, last_overflow_at FROM mate_model_context_state " +
                    "WHERE provider_id = ? AND model_name = ?",
                    rs -> {
                        if (!rs.next()) return null;
                        ModelWindowState s = new ModelWindowState();
                        s.effectiveWindow = rs.getInt("effective_window");
                        s.confidenceLower = rs.getInt("confidence_lower");
                        s.confidenceUpper = rs.getInt("confidence_upper");
                        s.phase = ModelWindowState.Phase.valueOf(rs.getString("phase"));
                        s.gatewayMode = rs.getBoolean("is_gateway");
                        s.peakObserved = rs.getInt("peak_observed");
                        s.successiveSuccess = rs.getInt("successive_success");
                        s.successiveOverflow = rs.getInt("successive_overflow");
                        Timestamp ts = rs.getTimestamp("last_success_at");
                        if (ts != null) s.lastSuccessAt = ts.toInstant();
                        Timestamp to = rs.getTimestamp("last_overflow_at");
                        if (to != null) s.lastOverflowAt = to.toInstant();
                        s.lastUpdatedAt = Instant.now();
                        return s;
                    ,
                    parts[0], parts[1]);
         catch (DataAccessException e) {
            log.debug("[AdaptiveContext] DB load failed for {: {", key, e.getMessage());
            return null;
        
    

    private void persistToDb(String key, ModelWindowState state) {
        if (!properties.isDbPersist() || jdbcTemplate == null) return;
        try {
            String[] parts = key.split(":", 2);
            if (parts.length != 2) return;
            jdbcTemplate.update(
                    "INSERT INTO mate_model_context_state " +
                    "(provider_id, model_name, effective_window, confidence_lower, confidence_upper, " +
                    "phase, is_gateway, peak_observed, successive_success, successive_overflow, " +
                    "last_success_at, last_overflow_at, last_updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "effective_window = VALUES(effective_window), " +
                    "confidence_lower = VALUES(confidence_lower), " +
                    "confidence_upper = VALUES(confidence_upper), " +
                    "phase = VALUES(phase), " +
                    "is_gateway = VALUES(is_gateway), " +
                    "peak_observed = VALUES(peak_observed), " +
                    "successive_success = VALUES(successive_success), " +
                    "successive_overflow = VALUES(successive_overflow), " +
                    "last_success_at = VALUES(last_success_at), " +
                    "last_overflow_at = VALUES(last_overflow_at), " +
                    "last_updated_at = CURRENT_TIMESTAMP",
                    parts[0], parts[1],
                    state.effectiveWindow, state.confidenceLower, state.confidenceUpper,
                    state.phase.name(), state.gatewayMode,
                    state.peakObserved, state.successiveSuccess, state.successiveOverflow,
                    state.lastSuccessAt != null ? Timestamp.from(state.lastSuccessAt) : null,
                    state.lastOverflowAt != null ? Timestamp.from(state.lastOverflowAt) : null);
         catch (DataAccessException e) {
            // DB write failure must never affect the in-memory state.
            // Next startup will recover the last persisted safe value.
            log.debug("[AdaptiveContext] DB persist failed for {: {", key, e.getMessage());
        
    

