# Rooted recovery integration status

Historical donor evidence below does not qualify the current d220-based port.
See [the current port and acceptance boundary](rooted-external-state-baseline-port.md).

The rooted release remains the behavioral reference. Storage changes do not
select different roots, epochs, publication owners, event order or logical gas.
Every component below remains development-only; no release is published here.

## Complete engine qualification — 11 September 2026

The complete factory at Coordination
`1fceadbd5d64e449481bcfb2a4ebf55644fc4a74` passed **39/39 tests plus Javadoc**
in 4 minutes 43 seconds, without source changes during the run. This is now an
actual new engine, not just independent codec/component tests:

- close the producer, open from bytes, process the next entry and compare exact
  session history, gas/checkpoints and control state to the resident reference;
- recover a suspended parent, execute its exact separately owned source action,
  reopen again, recognize the completed action and resume the original parent;
- fail immutable staging, prove the previous selection did not advance, retry
  staging and reopen the same completed result;
- open/process a selected root while unrelated session bodies are unavailable.

The gate also covers source-action association (including admission expansion),
feeder recovery, constructor controls, legacy API, and existing Main source
isolation, gas-boundary and local-history recovery. These **39 overlap earlier
component gates**; the counts must not be added as distinct scenarios. The seven
existing local-history controls took 229.7 seconds; the four full cold-engine
controls took 8.19 seconds. These test timings are not performance acceptance.

Exact dependencies: Language `8542285144a8969f157d73e73292398885105c46`, BEX
`ab72af14ee54c6123e6d80349956af373a887680`, Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`, with verified immutable downstream
bindings. Source archive:
`rooted-complete-engine-evidence.WA72HM/source-1fceadb.tar.gz`, SHA-256
`ede47145fd9442a6a1b3b741a5bc7bb280873c610d8bfe0d75c4109a2e6ac126`.
XML/HTML/Javadoc archive in the same directory: `complete-engine-final-pass.tar.gz`,
SHA-256 `bb641c8fedae7455bc26ddd7fdc48d0f6789e8be6a803a56fe57a1cb182a24b0`.

An initial run passed 19/20 and exposed construction of an unused legacy
coordinator on rooted open. Its legacy readiness recovery loaded all sessions.
Contracts now constructs only its own coordinators; legacy recovery remains on
the legacy path. The original failure is retained in `first-gate-19-of-20.tar.gz`.

See [the integration boundary](rooted-runtime-storage-factory.md),
[pending actions](rooted-engine-pending-storage.md), and
[feeder evidence](rooted-feeder-progress-storage.md) for problem/fix/rationale.

## Earlier combined component qualification

The earlier merged storage-component gate passed **118/118 plus Javadoc** in
68 seconds at source `7f7fde1626a599d4cc359b185f4f879b67cf5f94`, tree
`37549b7944a8eb74f86b6b92b0b36ff07efee64a`. It ran all sixteen `Storage`/`Stored`
test owners together, without source changes during execution. The exact bundle
was Language `8542285144a8969f157d73e73292398885105c46`, unchanged BEX
`ab72af14ee54c6123e6d80349956af373a887680`, and Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`, with coherent immutable downstream
bindings. The group includes pending reciprocal-join restoration, original
suspended demand authority, same-epoch representations and cold selected reads.
It does not include every unchanged test from each component's earlier focused
regression, nor does it claim complete SDK/app E2E.

Raw results are archived at
`rooted-combined-854-storage-evidence.PkciAv/storage-118-pass.tar.gz`, SHA-256
`b3869633b236df8b32df082836ee145119087d81f26deeaa0a73526beb33e708`.
The exact committed source archive in that directory is `source-7f7fde1.tar.gz`,
SHA-256 `5f8053f2609e172be341bccc4a7a08c21c2489cbe456185ae969d79c0f518995`.

### Earlier combined checkpoint

At exact Coordination source `0c3d5c5499df8e15dbc5d4e9642d3b00e4eeaa82`, the
combined focused storage gate passed **58/58 plus Javadoc** in 56 seconds:
whole objects 15, journal 13, document sessions 12, SDK metadata 7, engine
control 4, selected document indexes 5, physical provider outage 1, and the
existing persistent-map view control 1. No source edit occurred during execution. The tuple was Language
`bd09c281`, unchanged BEX `ab72af1` and Catalog `0b68744`, with exact immutable
downstream bindings to the event-evidence Language artifact. This is not a
complete library-suite or SDK-E2E result.

Raw XML/HTML is archived beside the worktrees at
`rooted-combined-point-recovery-evidence.s1zktP/coordination-0c3d5c5-bd09-58-pass.tar.gz`,
SHA-256 `75c50e09eaff54d1badbefbdd7b8e6761d3a05faddc720aae1b46af082ca7055`.

| Component | Implemented boundary | Still outside that claim |
| --- | --- | --- |
| Exact objects and Language evidence | Full representations/proofs, original execution authority and typed events; installed in complete cold engine | PostgreSQL application acceptance and measured cost |
| Timeline journal | Exact byte-only cold fixture and separately tested PostgreSQL row/index adapter | Atomic journal + SDK/state publication in the application |
| Document store | Complete 46-family indexed state, sessions/history, catch-up, topology, result/receipt evidence and bounded shared view scope | Host work partitioning and large-history cost |
| SDK metadata | Five actual owner-scoped map families with exact configuration and journal association | Full SDK factory gate, then real application wiring |
| Engine control and attempts | Source pending/submitted/completed actions, routes, feeder demands/rejections, fairness and terminal progress installed together | Host-atomic publication and application recovery |
| Persistent ordered map | Lazy authenticated immutable AVL paths; exact mutable scopes and selected bucket projections | End-to-end data-access and concurrency measurements |

The lazy map primitive's separate source `f15f29b` passed 21/21 plus Javadoc,
including fresh-JVM checks. The combined 58-case gate includes one existing map
view control, not that entire independent map suite.

## Next acceptance boundary

1. Qualify the complete new SDK owner, including owner-bound handles, repeated
   staging, source recovery and a reciprocal cycle. No producer handles survive.
2. Publish journal mutations and named state descriptors in one short fenced
   PostgreSQL transaction, with computation outside that transaction. Dropping
   a failed runtime while leaving its journal append committed is insufficient.
3. Keep one scoped SDK owner across command execution, projection/finalization
   and publication. Replace realm replay at external startup with exact restore;
   do not fall back to global in-memory handle/metadata caches.
4. Integrate host work selection and recovery. Independently named family roots
   do not themselves provide per-document concurrency: a partition's shared
   index roots can still conflict. A single mutable whole-realm runtime root is
   not the target concurrency model.
5. Qualify writer-JVM exit → PostgreSQL-only fresh reader → identical next work
   and complete observable result, including suspension, tight gas, same-epoch
   positions, lost acknowledgement and a second restart.

The MyOS physical adapters are separately tested. Component success does not
mean durable application mode is enabled, the complete paired scenario corpus
passes, or performance/scaling has been demonstrated.
