# Coordination behavior-fixture control language

The behavior package contains 55 authored YAML fixtures. Four fixtures expand
to multiple representation variants, producing 65 behavior execution cases.
Every file is decoded by `CoordinationBehaviorFixtureHarness`; fixture IDs are
not mapped to pre-existing JUnit methods.

The closed top-level fields are `schema`, `id`, `vectors`, `category`,
`description`, `operation`, `input`, and `expected`. The only operations are:

- `channel-classify`
- `process`
- `gas-integration`
- `mandate-eligibility`
- `provider-eligibility`
- `split`
- `timeline-order`

The executor configures `BlueRepository.latest()` from the local
`../blue-repository-java` composite build, registers the real Coordination
processors, consumes the fixed Repository's exact manifest BlueIds directly
from every repository-backed fixture `type`, preprocesses those canonical
nodes for ordinary Blue value typing, and dispatches the corresponding
production API. The executor does not depend on a test-only Repository type
alias shim. PROCESS cases use the generic processor's verified Root
delivery-plan boundary. Split cases use
`CoordinationDocumentSplitter`. Timeline cases use
`TimelineProviderSupport.evaluateCompletenessWindow`. Mandate cases use the
production eligibility helpers.

The assertion operators are `absent`, `contains`, `equals`,
`equalsProjection`, `greaterThan`, `notContains`, `present`,
`sameAcrossVariants`, and `sequenceEquals`. Unknown operations, controls,
variants, projections, or operators fail before a case can be recorded.

Cross-Timeline order is never reconstructed from timestamps or Timeline
identity. The `entries` list is already the platform's verified order.
Coordination validates strict timestamp increase only among entries belonging
to the same exact Timeline and preserves the supplied order.

No control may name a Java callback, mark a case passed, skip an assertion,
authorize provider evidence, mutate Root outside PROCESS, synthesize document
content, or use elapsed time as an oracle. For non-Mandate Root/Event inputs,
every non-inline representation requires exact declared provider evidence.
Inline, reference, fragmented, cold-cache, and warm-cache forms execute through
the real provider boundary. `partial` means exactly one verified Root-fragment
fetch by BlueId, leaving every downstream fragment reference unresolved.
`batched` is a transport-neutral, lazy provider prefetch: candidate BlueIds are
sorted, divided into windows of at most 16, and the one window containing an
actual demand is fetched through repeated public `NodeProvider` lookups and
cached. A strict splitter never includes an unadmitted executable-body BlueId
in a prefetch window. This does not invent a batch method or portable work.
Mandate document inline/reference coverage is exercised through the production
Mandate path.

This package remains a candidate. The current local fixed Repository contains
provider bodies that do not calculate to the BlueIds declared by its manifest,
so strict Language verification fails closed. The harness validates exact
feeder revision pairs, source keys, Mandate target evidence, and splitter
catalog selections, then invokes the verified-evidence PROCESS overload. Every
authored PROCESS fixture supplies the exact managed/indexed revision pair and
eligible source occurrence sequence; empty or partial feeder evidence fails
closed.

`trace.forbiddenDemands` filters the observed semantic-demand order against
forbidden executable-body BlueIds independently derived from splitter metadata.
`splitter.fragmentMetadata` projects the production split graph as
`kind|scopePath|pointer`, so inherited executable bodies and embedded scopes
are asserted without inventing runtime semantic demands.
`trace.processingEventBlueIdStable` compares the actual Language frozen Event
identity with every hosted BEX exact Event identity and remains `null` when
there was no observation. The independent `splitter.selectedBytes` expectation
sums canonical UTF-8 fragment bytes for structural fragments, declared allowed
body fragments, and Event fragments; it never reads the measured total.
Named-ledger merge, opaque gas, recursive counter presence, BEX child merge
count, and workflow-step order are projected from production traces. The
129-member aggregate executes and retains all 516 ordered trace entries. The
Language portable value of 256 bounds distinct counter kinds in one child
catalog; it is not a repeated trace-entry cap. The audit executes or fails
every case without skips and never writes a conformance receipt while the
fixed Repository evidence boundary remains invalid. The Gradle release graph
writes a receipt only after binding all 86 behavior, portable-gas, and
host-quota case identities to successful same-run JUnit executions.
