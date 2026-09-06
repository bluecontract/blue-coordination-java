# Revision15.7 outcome: semantic equivalence before reuse shortcuts

> **Date:** 2026-09-06 · **Status:** DESIGN REVIEW; no implementation approval
> [Package](../README.md) · [Source evidence](revision-15-7-source-evidence.md) · [Current semantic authority](../21-semantic-equivalence-and-source-reuse.md)

## Outcome

The proposal keeps source-once/1000 independent Orders and lazy PostgreSQL-backed execution, but
withdraws several mechanisms previously presented as chosen. Their semantic equivalence was not
established. This revision changes documentation, the reference Java sketch, review schemas and
planned fixture coverage only. It does not implement library/MyOS behavior or declare G1 passed.

## Applied corrections

| Earlier assumption | Revision15.7 requirement |
|---|---|
| Managed versus ordinary processing can simply use different ancestor behavior | One logical Contracts semantics; explicitly map scopes/ownership and correct the conformance gap. Physical factorization cannot drop required ancestor reactions. |
| Initialized-source reuse is automatically equivalent to new authored initialization | Distinguish exact input stage and prior authorized admission from cache presence. Analyze current invocation, epoch, event-ID, gas and rollback differences; do not normalize them away. |
| Fixed source policy establishes an independent initialization tariff | Keep it as experimental candidate evidence only. Derive logical gas/authority; a receipt's allocated gas is not automatically a pure source-only cost. |
| Receipt final-after plus own emission list is sufficient | Retain authenticated observable update/view/admission evidence. EQ1–EQ4 distinguish correct replay from final-only/flat-batch shortcuts. |
| Stage all alias pins before handlers | Withdraw the selected phase rule. Derive grouping and observable update/admission order from independent logical traces. |
| Every new placement catches up only after creator commit | Preserve frozen original recipients, but include creator-local initialization/caused work where required. Later continuation needs a justified boundary. |
| Every ancestor delivery uses a committed immediate-Parent relay | Withdraw this transport prescription. Preserve original E, composed path/frozen recipients and queue order without forced re-emission or duplication. |
| Feedback can be modeled as fresh bounded invocations per hop | Genuine caused workflow feedback keeps one required atomic scope/shared gas. Separate operational pause only from genuinely separately authorized future operations. |
| Failed managed import leaves later repair permanently held | Preserve successful view/epoch, record terminal handled-input outcome, and define the next action against unchanged consumer state. A required positive repair needs an exact continuity contract. |

SOURCE history selection is not a free choice made by the first worker. Explicit FROM_NOW boundaries
can be legitimate different admissions; cache loss cannot create another birth. Prior independent
admission and creator-local new-child initialization have different ownership facts. Identical
authored BlueId alone is not the entire invocation input, and cannot authorize conflicting histories.

Source/consumer independence is an explicit logical ownership goal. It may change rollback/gas/IDs
relative to one legacy combined Root. That must be reviewed separately from materialization
invariance; it cannot excuse missing intermediate reads, update handlers or ancestor events.

## API and documentation impact

The concrete flat relay DTOs are replaced by the provisional `SemanticObservationEvidence` seam:
observable updates/intermediate views, admission/frozen-recipient information, provenance/completion
ownership and gas evidence. Its shape is an obligation inventory, not a finished canonical wire
format, constructor or verifier. Grouping and preparation/publication records remain candidates
subject to the logical proof; a compiling record does not settle the algorithm.

[21](../21-semantic-equivalence-and-source-reuse.md) has highest semantic precedence. Documents18–20
are corrected in place; architecture, invariants, host/API/storage guidance, delivery/validation plans
and review prompt are aligned. The map and README are shorter. The new
[tutorial13](../tutorial/13-equivalence-and-reuse.md) explains the changes through concrete examples;
101/202 and existing tutorial chapters no longer teach the withdrawn rules.

The 53 schedule families and nine named subcases remain. EQ1–EQ8 are required fixture variants in
existing families, not a new profile or catalog. The reviewed scenario artifact must bind exact
fixture/expectation/row coverage, and reports must join observations to it. JSON schemas validate
structure, not semantic coverage; implementing the future preflight/verifier remains planned work.

## What is not yet proved

Before implementing dependent behavior, close:

1. Ordinary/factored scope, observation and identity correspondence.
2. Minimum authenticated observation evidence and its replay/validation algorithm, including FIFO.
3. Exact grouping, freeze/retirement and creator initialization/catch-up boundaries.
4. Independent source ownership versus actual coupled workflow/feedback scope.
5. Source origin/admission, initialization/reuse gas and publication for fixed authorized history.
6. Terminal managed failure progress and positive next-input/repair continuity.

These are focused semantic obligations, not instructions to invent a generic origin-election,
version-compatibility or public VM-trace framework. Future approved tests must verify the independently
expected behavior in actual Coordination/Contracts before MyOS integration. Measurements may optimize
physical batching/evidence retention, not choose semantic results.

## Verification and evidence limits

Structural checks for this package:

- Java17 reference-sketch compilation with `--release 17 -Xlint:all`.
- JSON parsing and Draft202012 metaschema validation of all three schemas.
- Active Markdown UTF-8, fence, local-link/anchor and revision consistency checks.
- Mermaid11.12.1 parsing of all14 active diagram blocks.
- Protected historical evidence/test/build/ZIP hashes and unchanged tracked patch check.
- SHA-256 payload manifest and independently read-back ZIP member verification.

These checks do not execute processing. The retained r15.2 results remain3 passing attachment
controls and4 failing settled-history cases; their limitations and stored evidence are unchanged.
No runtime source, test/build implementation, PostgreSQL integration or benchmark was added or run.
The user still decides when the three-phase implementation begins.
