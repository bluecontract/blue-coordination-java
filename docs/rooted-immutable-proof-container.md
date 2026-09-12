# Retained source proof-container reuse candidate

## Problem and scope

This isolated candidate starts from the combined Coordination baseline
`be068f3ce3f943a3db772e4fd44c45ab770fe11d`, using its unchanged library bindings.
During the original MyOS ring, a single writer-thread sample at 369.71 seconds
showed `CyclicSetProof.declaredPlaceholderSet` under
`ManagedEpochSourceEvidenceVerifier.copyCyclicSetProof`, the private evidence
accessor, and `ManagedEpochInvocationCapturer.capture`:

`/Users/kamil/Documents/Projects/Blue/legal-detached-retarget-evidence.fKnxrU/myos-proof-fingerprint-ring-01-sample-01.txt`

This is a hotspot observation, not measured cumulative clone time, proof of the
ring's cause, a speedup estimate, or a necessity claim. Performance qualification
and the unchanged original ring acceptance remain pending.

## Narrow change

The source verifier redundantly rebuilt the same `CyclicSetProof` container on
provider-result handoff, evidence-record construction, and accessor return.
Each rebuild extracted defensive member copies and copied them again into a new
container. The candidate removes only these three transfers and their helper.
The package-private evidence record now retains and returns the same container.

Language documents `CyclicSetProof` as an immutable container: it is final, owns
copies of its constructor inputs, and returns detached mutable members in an
unmodifiable list. Member resolution also clones before rewriting. Sharing the
container follows the existing `CyclicSetProofResult` and `ExactValue` usage;
this is not a mutable-node cache or a new proof-validity assertion.

The provider is still called and validates the complete cyclic body/proof on
every verification. Receipt, predecessor, event, gas and durable application
checks are unchanged. Language cause admission still authenticates the supplied
proof. All actual member extraction remains defensive. No provider/store cache,
authority fence, public API, semantic operation, failure mapping or gas rule is
changed. The private record's Coordination callers do not compare proof object
identity, and the record is not used as an equality-sensitive key. Language's
existing verifier may reuse mathematical validation for the same immutable
proof instance; no public defensive-wrapper contract is changed.

## Controls and qualification status

`ManagedEpochIndirectComponentRebindTest` gains a real retained cyclic-proof
control: the record shares its supplied container, constructor-input and nested
extraction mutations leave the complete proof unchanged, the proof still
authenticates the historical member, and recapture preserves exact invocation,
cause, input and durable state. The existing missing, unavailable and invalid
proof controls now first acquire evidence successfully, then check that the same
verifier observes the new failure. Their original no-PROCESS, cursor, receipt,
rollback and restart assertions remain in place.

No builds or tests have been run on this candidate at preparation time. The
parent-owned focused gate should select the complete
`blue.coordination.internal.ManagedEpochIndirectComponentRebindTest` owner,
then `ManagedEpochApplicationAtomicRollbackTest` and
`ManagedEpochApplicationResponseLossTest` against the same immutable library
tuple. Any performance comparison must record the exact candidate artifact and
unchanged original ring inputs/deadlines; this source patch alone establishes
neither a timing gain nor full baseline qualification.
