# ASSUMPTIONS.md

Assumptions made where SIMULATOR.md is ambiguous. Each has a code comment
at the point of impact. Document updates here as new questions arise.

---

## A1 — Java 17 instead of Java 21
**Requested:** Java 21.
**Reality:** Maven on this machine runs JDK 17.0.17 (Eclipse Adoptium). JDK 25 is also
installed but not configured for Maven. All language features used (records, text blocks,
switch expressions, `instanceof` patterns) are Java 17 compatible. To use JDK 21/25:
set `JAVA_HOME` to the 21/25 install and bump `<maven.compiler.source/target>` in pom.xml.

## A2 — Approval response shape
SIMULATOR.md shows the POST body but not the full GET /approvals/{id} response.
Assumed fields:
  - `approval_id` (string)
  - `proposal_id` (string)
  - `workflow_id` (string)
  - `status` (string: pending | approved | rejected | expired | applied | lapsed)
  - `reasons` (array of string, may be empty)
  - `apply_by` (ISO-8601 string — the deadline before the approval lapses)
  - `decided_at` (ISO-8601 string, null while pending)
If the actual schema differs, only `ApprovalStatus.java` needs updating.

## A3 — `after` parameter on GET /events/chunks
Assumed: `after=N` returns chunks with chunk number **strictly greater than** N,
matching common cursor-pagination convention. First fetch uses `after=-1`.

## A4 — `approver_hours` semantics
Assumed: `[start, end)` in IST 24-hour clock, matching the dialling window convention.
We will not *submit* a new proposal outside this window (the approver won't be there to
read it). Polling for existing decisions happens regardless of hour.

## A5 — `max_requests_per_24h` counted in simulated time
The rolling 24-hour window is measured in simulated timestamps (`ts` from advance
responses), not wall-clock time. The governance service queries the approvals table for
proposals submitted within the last 24 simulated hours.

## A6 — History chunks before D0
`GET /events/chunks?kind=history` returns all pre-D0 chunks. We process them exactly
once on first start (guarded by the `history_bootstrapped` key in `kv_state`).

## A7 — Proposal ID format
The simulator accepts any non-empty string as `proposal_id`. We use
`"ctrl-" + first-32-hex-chars-of-SHA256(workflow_id + ":" + base_version + ":" + canonicalConfigJson)`
so the same intent always produces the same ID, enabling crash-safe retries.

## A8 — DEMO_PROPOSAL target
The demo proposal adds an explicit `schedule.hour_weights.default` table that mirrors
the implicit default (all hours in the dialling window, weight 1). This is semantically
identical to having no table. The governance approver may approve or reject it; we handle
both outcomes. We submit it via POST /approvals (not auto-apply), specifically to exercise
the full approval lifecycle including polling and applying.

## A9 — `mid_flight` handling
`next_campaign_only` means agent-facing dims (cohorts, allocations) are locked to the
config version at campaign creation. `prompt_version` and `schedule` always apply
immediately. The controller stores `mid_flight` from the workflow object and will use it
in Day 2 when deciding whether to propose mid-campaign changes.

## A10 — Credits are shared across workflows per scenario
`GET /sim/state → credits_remaining` reflects the scenario-wide budget. We track this
after every advance and log a warning when < 20 credits remain. We make no LLM or
validation calls on Day 1, so credits are not consumed.

## A11 — Statistical Aggregations on Realised Variant Values
Realised variants delivered on call events (`variant.*` in raw NDJSON) are stored directly
in flat columns in `calls` (`voice`, `welcome_message`, `caller_id`, `prompt_version`).
All statistical tests query these columns rather than looking up config version allocations.

## A12 — Stouffer Combining for Attempt Strata
When combining z-scores across attempt numbers, strata with fewer than 10 dials are skipped.
Valid strata z-scores are combined with Stouffer's method ($z_{combined} = \sum z_k / \sqrt{K}$)
under the assumption of independent stratum trials.

## A13 — Approvals Rate Limit Buffer
1 proposal slot is always held in reserve for emergency incidents (e.g. caller ID breakdown or
hallucination rollback), limiting routine experiments to at most `max_requests_per_24h - 1`.
