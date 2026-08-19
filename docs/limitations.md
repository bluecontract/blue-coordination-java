# Known limitations

- The rc.2 artifact is a local-only in-memory SDK freeze candidate. It is not
  remotely published and is not a production MyOS runtime.
- Managed-child admission from an operation result is deliberately unsupported.
  The SDK can create `ManagedDocumentDraft` values, but a call using
  `request.managed(...)` or `expectOccurrence(...)` fails before append with
  `UNSUPPORTED_MANAGED_DRAFT_ADMISSION`. There is no partial mutation and no
  fallback to legacy child/parent admission. A real Contracts host-invocation
  bridge remains required.
- The supported external-pilot profile is one JVM, in-memory, sequential drain,
  public-Root-scope closures, and bounded cyclic components. It has no
  fresh-process durable recovery, provider-completeness adapter, provider-backed
  Mandate resolver, parallel/distributed scheduling, or stable latency SLA.
- Production MyOS still requires durable stores, exact restart recovery,
  authorization and tenant isolation, provider completeness, outbox recovery,
  operational backpressure, and production observability. Those are separate
  adapter/profile phases and are not simulated by the SDK.
- Managed embedded-document epochs and historical synchronization are a
  next-version Coordination temporal profile. They are not claimed as frozen
  Contracts 1.0 semantics.
- The frozen Contracts API has no managed-child ownership-mask input.
  Coordination therefore uses an explicit ownership projection before frozen
  processing; exact semantic-parent fidelity across every child-owned
  subscription surface remains a frozen-API gap.
- Journal completeness is proven only for the current in-memory journal. There
  is no durable or distributed transaction protocol.
- The copy-on-write multi-document publication API is currently package
  internal and in-memory. It proves selected-head and managed-topology CAS plus
  one-swap rollback, but `SequentialDrainCoordinator` is not wired to it and no
  serialized adapter yet reloads its inventory, component state, outbox,
  checkpoint evidence, or publication receipts.
- The pinned generic Timeline Entry has no universal literal `documentId`
  field. This is an optional generalized targeting-profile gap, not a blocker
  for append-once/environment-derived routing: concrete Channel/message types
  may define exact target derivation, and Repository-native
  `OperationRequest.document` version targeting is supported. A universal
  Timeline-Entry target profile requires an upstream field or runtime hook.
- General provider-backed Mandate eligibility requires an exact Mandate-state
  resolver at the entry's source order. The in-memory engine does not invent
  that evidence; authority-bearing entries fail closed until a host adapter can
  supply it.
- `Process Embedded.collectionPaths` covers direct stable-key members. General
  list-position identity and arbitrary collection reshaping are not implied.
- Coordinator reconstruction inside the same live engine is supported while
  its typed in-memory document, journal, and scheduler state survives. A fresh
  engine instance is not reconstructible from a serialized store. External
  frontier import and cross-process recovery still fail closed unless exact
  cursor, epoch, entry-frame, commit-companion, and provider-completeness
  evidence is durably available.
- Drain is intentionally sequential. Parallel document processing, leasing,
  distributed scheduling, a second SCC planner, and caller-selected recipient
  sets are out of scope. The normal SDK may select an exact operation target;
  the environment still derives the resulting recipients.
- `DrainBudget` bounds selected entries and committed PROCESS transitions. It
  cannot preempt one frozen processor call, does not count epoch-zero
  INITIALIZE inside an atomic attachment, and is not a hard latency deadline.
- In large documents, steady drain latency is currently dominated by frozen
  Language/Contracts/BEX delivery-plan derivation and platform commit. The
  Coordination scheduler is measured separately and remains small; removing
  independent frozen verification or caching revision-bound delivery evidence
  would be an unacceptable semantic shortcut.
- The retained Round 13 campaign failed append p95 (18.680667 ms against a
  1.000000 ms hard limit) and Coordination-host p95 (872.356126 ms against a
  250.000000 ms hard limit); route and total passed hard, while all four metrics
  missed their preferred targets. The historical 3.0.0-rc.1 workflow policy
  permitted only `PASS_WITH_KNOWN_PERFORMANCE_LIMITATION`. It does not claim a
  latency pass and cannot be applied to rc.2 or a stable release.
- Immutable graph generations structurally share unchanged forward/reverse
  buckets and binding records, but a topology-changing publication still makes
  shallow copies of the three top-level in-memory directory maps. This RC does
  not claim persistent-map O(affected-key) allocation for those directories.
- Deterministic failed retries stabilize whole-object cache size for the same
  failure. Distinct failed results can leave unreachable immutable cache values;
  retention is an in-memory host policy.
