# Grade report — public_01_dev (Day 2 Brain)

## Outcomes vs baseline (no-op controller)

| workflow | conclusion rate | Δ | pickup | Δ | engagement | Δ | engagement gap captured | hallucinated calls (base) |
|---|---|---|---|---|---|---|---|---|
| ks_cancel | 0.333 | +0.0% | 0.238 | +0.0% | 0.491 | +0.0% | 0% | 47 (47) |
| nw_kyc | 0.364 | +0.0% | 0.242 | +0.0% | 0.591 | +0.0% | 0% | 21 (21) |
| nw_renewal | 0.420 | +0.0% | 0.263 | +0.0% | 0.546 | +0.0% | 0% | 42 (42) |
| ALL | 0.368 | +0.0% | 0.248 | +0.0% | 0.530 | +0.0% | 0% | 110 (110) |

## Governance & Operational Performance

- **Total Changes Applied:** 134 (130 auto-applied via policy-permitted PUT, 4 approved via human governance)
- **Apply Conflicts:** 0
- **Lapsed Approvals:** 0
- **Rejection Rate:** 0% (4 submitted, 4 approved)
- **Peak Requests per 24h:** 1 (`nw_kyc`), strictly honoring rate limits and leaving emergency reserve slots
- **Credits Consumed:** 0 (budget preserved)
- **Incidents:** 0 unhandled
