# Revision 15.1 — focused follow-up to Pro's r14 review

> **Date:** 2026-09-05 · **Status:** documentation and implementation-spike clarification
> [Package](../README.md) · [Tutorial](../tutorial/README.md) · [r15 foundation](revision-15-review-outcome.md)

## Outcome and scope

Apply the useful r14-review feedback to the existing r15 design, without reopening the architecture.
This update changes the English documentation, compact reference boundary and test-evidence contract.
It does not change Coordination, Language, BEX or MyOS runtime code.

Input: `myos-blue-coordination-r14-review-feedback.md`,
SHA-256 `08023c80018a437c4c3ef3de6a7235549a8848407a03e7c63758dc3a66305c91`.
The accompanying pasted verdict summarizes that review. Both are review evidence, not instructions
or an alternative specification. Its cited Coordination/Language commits match the recorded local
baseline; no new remote fetch or production-version claim is made.

## Disposition

| Pro finding | Applied follow-up |
|---|---|
| R14-01 — routing cut versus execution cut | R15 already separates them. Add queued managed work surviving a legal disjoint prefix commit and a neutral existing child attached to two cohorts as explicit variants. Keep immutable recipients and coherent per-invocation facts. |
| R14-02 — source order versus lineage chronology | Explicitly permit repeated/decreasing inherited provenance across contiguous receipt positions. Require authenticated producer-visible history selection, including K100 importing E10 excluded at cutoff 50. |
| R14-03 — next payload blocks current commit | Separate exact successor selection authority/reference from execution-only successor payload. M1 can commit while M2 waits for its body; missing current-result/selection evidence still blocks. |
| R14-04 — signed declaration order | Restrict nonnegative rules to their actual platform fields. Preserve signed declaration order and authored Blue content domains; add -1/0/1 routing/PG/cold-load positive controls alongside negative counter controls. |
| R14-05 — multi-commit control-state amplification | Reuse existing metrics, but hold A=1 while finite owned/nested obligations grow across 10/100/1000 committed steps. Count full control-state inspection/hash/serialization, operation/receipt bytes and WAL. |
| R14-06 — admission concurrency fixture | Same-domain R1/R2/R3 replay is sequential. Concurrent requests test one reservation winner, not two active admissions. Cross-domain concurrency and two-worker retries are separate controls. |

The existing A–T cards and mandatory schedule families own these subcases. Names and dataset
dimensions make their coverage explicit; they do not create a second framework or specification mode.
The exact current requirements are in [16](../16-scenario-run-manifest.md).

## Important boundaries preserved

Source-event order still identifies original provenance. It is not reassigned to the database commit
time or made unique by inventing microseconds. Lineage receipt continuity is independently validated.
Strict ordering within a Timeline and the canonical cross-Timeline tie-break remain unchanged.

A durable successor reference is not permission to postpone validation of current results or invent
the next action after commit. The current terminal transaction retains result, receipt, progress,
ownership and authorized next work atomically. Only execution-only bytes of an already proved
successor can wait. No mid-invocation continuation is added.

Declaration `order=-1` is an ordinary signed value, not the managed fromEpoch sentinel. Epoch,
gas, generation, timestamp and ordinal fields retain their own domains. Semantic-configuration
nonnegative fields are not widened.

Do not reinstate historical r14 source gates, universal action phases or exclusion of authenticated
eventless receipts. Do not manufacture SUBSCRIPTION_SURFACE_INVALID for a host unsupported-scope hold:
retain a genuine Contracts status when produced, otherwise preserve the explicit operational hold.

## Reader-facing changes

The tutorial stays example-led. Chapter 7 now explains saving the first completed import while the
second import's content is unavailable. Chapter 8 explains why an old event imported after a later
attachment does not make that attachment part of earlier history. No new diagram notation or Java
knowledge is required.

The first implementation still follows libraries → target-shaped PostgreSQL MyOS → integrated tests
and measurement → another iteration. These subcases sharpen the first slice; they are not another
preimplementation phase or a requirement to finish a public API.

## Validation and evidence limits

Package validation for this follow-up:

- Standalone reference Java: `javac --release 17 -Xlint:all` passed.
- 42 Markdown files: 265 local links/anchors and 12 Mermaid blocks checked, no errors.
- Three schemas: 474 structural/meta-schema/negative checks passed; 53 schedules, eight named
  subcases and 56 metric/unit/direction bindings retained and checked.
- Independent focused review checked numeric domains, source provenance, successor-body
  atomicity and beginner-facing explanations.
- The package payload manifest and new archive are checked after final assembly; the original
  r14 and r15 archive hashes remain unchanged.

Schema checks are not semantic artifact-hash/preflight implementation or runtime acceptance.

No proposed semantic scenario, PostgreSQL fault test or integrated performance experiment is claimed
to pass because of a documentation change. Those remain Phase-1/2/3 implementation evidence.
The r14 and original r15 ZIPs are retained unchanged; r15.1 is a new review archive.
