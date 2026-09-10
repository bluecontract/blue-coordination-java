# Declared births in rooted processing

An operation's declared child and an independently admitted source can have the
same authored content without having the same managed lineage or ownership.
The rooted adapter preserves that distinction when it resolves new occurrences.

If an operation supplies different exact content at a declared child's expected
path, the processor can suspend with an authenticated managed-occurrence demand.
The feeder compares that demand with the immutable operation declaration and
retains a terminal `REJECTED_MANAGED_DECLARATION` decision. The SDK reports
`REJECTED` with `MANAGED_OCCURRENCE_BINDING_MISSING`. It does not report a resource
wait that an upload could resolve, fabricate a completed processor result, or
publish the child. The actual suspended attempt has no portable gas result.

The terminal record retains the actual emitted demand, selected and executed
inputs, the exact registered declaration, owner context, cause and terminal key.
Contracts' existing prospective-birth authenticator verifies the demand's
processor-issued authority; a record reconstructed from public hash operands
cannot substitute for that authority. This validation constructs no committed
birth and performs no retry. Publication checks the captured owner heads and
graph generations. Repeated processing returns the retained decision.

If the processor completes but no expected occurrence was installed, the
existing completed-result rejection path retains the actual charged work and
its captured declaration. It publishes no document, occurrence, component or
event changes. Legacy-profile checks still require their original virtual-input
members and absent fences.

For an operation that installs both a declared child and a bare occurrence of
the same authored content, the bare source requires its own history admission.
After that prerequisite, retrying the same entry creates only the declared
child as an owned birth. The calculation input and complete commit companion
include the independent source as evidence. The publication projection, head
fences and graph mutations contain exactly the derived owners and new births.
The independent source's history and public events are preserved.

The graph-generation transaction checks distinct present and absent fences,
exact processor-derived ownership, full input/companion identity, current owner
generations and each new child's authenticated epoch-zero initialization. It
does not install root-local dependency generations as authoritative source data.

Maintained regressions:

- `RootedManagedDraftRejectionTest`: exact mismatch in both submission modes,
  completed missing-path rejections, real gas accounting, duplicate handling,
  store restart and a later valid birth initialized once.
- `RootedDeclaredBirthRejectionTest`: recomputed-demand forgery, duplicate
  evidence, wrong owner/cause/key, stale fences, substituted declarations,
  forbidden semantic staging and response loss after retaining the decision.
- `RootedMixedDraftExpansionTest`: both original extra-occurrence operations,
  independent admission, same-entry retry, source preservation and store restart.
- `RootedBirthPublicationTest`: missing, extra, duplicate and overlapping graph
  fences remain rejected.

The in-memory SDK's store restart is distinct from MyOS fresh-process command
replay. Packaged application acceptance is required for the latter. These
regressions and this description do not establish final RC readiness.
