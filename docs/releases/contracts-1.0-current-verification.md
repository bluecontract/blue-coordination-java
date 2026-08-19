# Contracts 1.0 current verification boundary

This source tree implements the Contracts 1.0 coordination profile against the
adjacent Language, BEX, and Repository source checkouts. The ordinary build is
therefore `local-composite`; `published-artifact` is a separate, explicit
dependency-isolation lane.

`verifyCurrentContractsDocumentation` checks the required Contracts entry
points and the five semantic invariants, then writes the exact current source
manifest and source/test counts to
`build/reports/contracts10/current-source-integrity.json`. The report is derived
from the worktree on every changed-source run; no historical counts are copied
forward.

The retained 3.0.0-rc.1 Round 13 report and JSON describe only their bound
candidate commit. They remain historical audit evidence and are never compared
with, or presented as evidence for, the current Contracts 1.0 source tree.

```text
CONTRACTS10_CURRENT_PERFORMANCE_CLAIM: NONE
```

The build also enforces current maintainability guardrails of at most 140
production source files, 40,000 production source lines, and 24 public API
source types. These rounded caps leave deliberate implementation headroom. They
are engineering constraints only: they are neither measured performance
evidence nor a relabeling of the retained Round 13 source inventory.

No Contracts 1.0 latency or throughput claim is made. Correctness, API,
Javadoc, artifact, source-provenance, and dependency-graph gates are independent
of the historical Round 13 performance receipts.
