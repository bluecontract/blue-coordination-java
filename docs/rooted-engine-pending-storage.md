# Retained engine attempts and source actions

The real engine owns five insertion-ordered mutable families: operation drafts,
frozen selections, pending source discoveries, submitted source actions and
completed source responses. `EnginePendingStorage` restores those same maps,
including original object identities needed by conditional removal/retry guards.
It does not select or execute an action while decoding one.

For example, a suspended Order can already have selected an Agreement admission.
After recovery, the same selected admission must be used; the Agreement's newer
physical head must not silently replace that action. A completed response must
also belong to that original action, not merely be a valid response to some
other action.

Selected rows validate the exact key, kind, input/work and terminal-publication
relationship. Completed admissions are checked against the independently keyed
admission ledger. This is important because the original admission can correctly
expand from one member to two: requiring its final input/member set to equal the
pre-expansion input would reject a legitimate Main operation. The complete
published receipt is compared; only the observation `PUBLISHED` versus
`ALREADY_PUBLISHED` is interchangeable, not its gas, result, input or history.

Pending maps share the owning document view scope and retain bounded selected
values without evicting identity-pinned rows. Immutable prewrites do not publish
the map roots. The host must pin/publish these families coherently with document,
publication, feeder and control state. A physical failure during processing
requires discarding the owning attempt, not staging partially mutated state.

## Verification

The final focused Java 17/full-854 tuple gate passed **16 tests and Javadoc** on
2026-09-11. It covers cold admission/live actions, all four source action kinds,
actual automatic admission expansion, mismatched response/ledger evidence,
ordered maps, original draft identity and failed writes. Source was unchanged
during the gate.

Receipt: `rooted-source-result-association.20260911-99883-1e1jssh/expanded-admission-01`
(SHA-256 `20818d5da9f23a571883053c5b6886d7fe7621622ad40b8147181e6a8b63dcd6`).
These are component controls, not PostgreSQL application E2E.
