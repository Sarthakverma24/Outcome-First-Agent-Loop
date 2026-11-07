# DECISIONS.md

Design decisions for the Formi Outcome-First Controller (Day 1 & Day 2).

---

## D1 — Java 17, Zero Non-Essential Dependencies
- Standard `java.net.http.HttpClient` with retry backoff for GETs.
- Jackson for explicit JSON serialization (`@JsonProperty` for snake_case).
- `sqlite-jdbc` with WAL mode and `PRAGMA synchronous = NORMAL`.
- JUnit 5 for parameterised and replay tests.
- **No Statistics Library:** Two-proportion z-test, Holm-Bonferroni correction, and Chi-squared Wilson-Hilferty CDF approximations are implemented from first principles in `com.formi.controller.stats.*` to be completely explainable in review.

## D2 — Separation of KPIs (Hard Rule 3)
- Pickup rate (`connected / dials`) and engagement rate (`engaged / connected`) are kept strictly independent in database schema, SQL queries, and statistical tests.
- Objectives inspect `workflow.objective.primary` and monitor `guardrail` with a 95% one-sided confidence lower bound.

## D3 — Attempt-Stratified Aggregation
- Comparing arms across aggregate calls introduces survival bias because engaged leads leave the dialling pool.
- `ExposureStore` slices all exposure by `attempt_no`. The experiment engine evaluates strata independently and combines z-scores via Stouffer's method before Holm correction.

## D4 — Causal Realised Exposure
- Decisions are made exclusively from the delivered variant on each call (`calls.voice`, `calls.welcome_message`, etc.), not from the intended config at dial time. Mid-campaign config updates do not corrupt historical counts.

## D5 — Deterministic Identifiers & Crash Safety
- Proposal IDs: SHA-256 hash of `workflow_id:base_version:canonical_config_json`.
- Experiment IDs: SHA-256 hash of `workflow_id:cohort_id:dimension:start_sim_day`.
- In-flight decisions are persisted in the database before submitting network side effects. Replays evaluate to identical winners.

## D6 — Conservative Thresholds in BrainConfig
- `MIN_CONNECTED_PER_ARM = 300`: Provides 80% statistical power to detect 3pp lift at α=0.05.
- `LOOK_INTERVAL_SIM_HOURS = 24`: Enforces one look per simulated day to prevent continuous peeking bias.
- `CALLER_ID_CONSECUTIVE_FLAG_TICKS = 3`: Filters transient carrier drops before triggering caller ID rotation.
- `HALLUCINATION_RATE_MULTIPLIER = 2.0`: Requires a 2x surge over 7-day baseline to account for noisy QA labels.

## D7 — Supervisor Sovereignty & Contamination
- Supervisor edits (`actor=supervisor`) are treated as authoritative business overrides.
- Active experiments on the edited workflow are immediately flagged `contaminated` and discarded.

## D8 — Prompt Changes Require Human Approval & Validation
- Prompt changes are never auto-applied. Rollbacks require `validation_run_id` proof; if missing, state enters `awaiting_validation` without issuing unvalidated proposals.
