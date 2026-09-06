# Revision15.6 source evidence

> **Revision:** 15.6 · **Inspected:** 2026-09-06 · source inspection, not test execution
> [Current outcome](revision-15-6-review-outcome.md) · [Decision traces](../20-decision-traces.md)

## Scope and confidence

The local Language/Contracts checkout is `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`, the same baseline
recorded in [provenance](../00-conventions-and-provenance.md). No remote refresh is claimed.
The excerpts below are from current source, not older Pro assumptions. They explain why the proposed
group barrier and relay companion are owning-library changes, not capabilities already supplied by
current receipt import. They do not prove the proposed algorithm correct.

The unchanged [15.5 source appendix](revision-15-5-source-evidence.md) retains evidence for activating-
invocation-dependent initialization, FROM_NOW birth semantics, warm/cold gas controls, external loop
recovery versus failed managed import, and same-epoch representation-only finalization. Its recorded
test assertions are inspected source, not newly executed results.

## 1. Singleton import: update work before incoming events

File: `blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java`  
SHA-256: `47f3db7a75a9a0f93936803e72b560ead5110c8c13b5186cd0bc8e3765b5ec5e`

At362–416, `executeManagedRevisionCause` constructs one containing-reference update, charges source
after-document admission, enqueues the update and calls `drainCausalWork()` before
`deliverManagedRevisionEvents(cause)`. At419–450, that method captures one target, appends each
retained source event with that captured target, charges enqueue work, and only then drains.

**Fact:** reference-update reactions can precede incoming source-event admission. E1/E2 are admitted
before handling E1; an F emitted by that handler does not jump E2. **Proposed change:** select all
same-position placements, stage all their after-pins before handlers, preserve each normal charge,
then perform the reviewed update/batch/drain phases. The current singleton code alone does not
specify or implement that barrier. A trace that globally sorts original relays before own emissions
would lose an earlier G emitted by reference-update work.

## 2. Existing containing-occurrence order

The same file's `activeContainingOccurrences` uses the following comparator (3605–3641):

```java
3622:                         int order = left.sourceDocumentId().compareTo(
3623:                                 right.sourceDocumentId());
3624:                         if (order != 0) {
3625:                             return order;
3626:                         }
3627:                         order = ClosureValueSupport.comparePortableText(
3628:                                 left.sourcePath(), right.sourcePath());
3629:                         if (order != 0) {
3630:                             return order;
3631:                         }
3632:                         order = Long.compare(
3633:                                 left.activationGeneration(),
3634:                                 right.activationGeneration());
3635:                         return order != 0 ? order
3636:                                 : ClosureValueSupport.comparePortableText(
3637:                                         left.occurrenceIdentity(),
3638:                                         right.occurrenceIdentity());
```

**Fact:** containing DocumentId, portable-text sourcePath, activation generation, then portable-text
occurrence identity. **Proposal:** reuse this order for the group placement phases in20A. Within
one consumer, containing DocumentId is constant. Do not substitute SQL collation or a binding-row
hash comparator. This does not settle the distinct comparator between competing source groups.

## 3. Receipts group actual emitters, not all imported ancestry

File: `blue-contracts-core/src/main/java/blue/language/processor/closure/ManagedTransitionReceiptAssembler.java`  
SHA-256: `0bbdc21bf43c02956340360fec2e51bd76c3c28f38f8b146372484981d0871d1`

At28–57, the assembler selects each resulting document's events from `eventsByDocument`, then
builds its receipt from that document's before/after value and its own list. At75–95, grouping uses
`ManagedRootEventOccurrence.sourceDocumentId()` and validates contiguous emitter receipt ordinals.
In `ClosureExecutionSession:1584–1597`, new emitted occurrences explicitly name the actual emitter.

**Fact:** imported Source E is not automatically a new Parent emission. **Proposal:** Parent's complete
result authenticates an ordered delivery-batch companion retaining original E and own F separately.
Root consumes that one immediate Parent result/batch, not a forged Parent E or an additional direct
Source cursor step. Parent may relay through the supported eventless-result evidence path. Exact
constructor, route, gas and cyclic-scope vectors are still required before dependent implementation.

## 4. Evidence boundaries that remain decisions

- Current FROM_NOW birth-at-attachment semantics mean equal labels atT10/T20 can choose different
  histories around E15. Fixed source gas policy and a unique row do not solve **BIRTH**.
- Existing external loop failure → detach → new input does not prove eligibility of D@T30 after
  failed managed r1@T10 and already-due r2@T20. No inspected rule proves the positive **REPAIR** path.
- Current representation rebind creates/replaces no managed epoch receipt; retain that exception in
  the triggering result/delta instead of adding synthetic business fanout to every re-encoding.
- Candidate initialization and X-owned initial-stream authority within a successful parent's bounded
  creation companion are proposed API semantics, not claims of existing source implementation.
