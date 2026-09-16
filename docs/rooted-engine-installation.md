# Cold engine and SDK installation boundary

## Problem

An external journal or decoded session alone cannot reopen the actual runtime:
the old constructors always created empty document/route/source indexes, feeder
controls and pending-plan maps. Rebuilding those pieces by replaying submitted
commands would perform work again and could select different source positions.

## Change

The private rooted constructor now accepts every complete typed engine family
together: retained document state, exact object backing, route/source indexes,
control, journal-keyed plans, and pending/submitted/completed source maps. Its
release and policy must match the stored control. It installs the original
clocks, registrations and feeder/scheduler state without admission, processing,
provider resolution or route reconstruction. The journal's existing constructor
reads its one STATE metadata row; entry scans and work selection are not part of
opening.

The SDK can create a new owner around that supplied exact engine, retain its
bundled/custom-release distinction, and install all five owner-validating point
maps together. A different actual core policy cannot be relabeled by an SDK
configuration. Old handles remain owned by their old SDK; restored point rows
must produce handles for the new owner and match its verified journal.

All new entry points are private/package-internal. The ordinary resident builder
and processing algorithms are unchanged. The control-only export still rejects
nonempty omitted pending components; complete export is a distinct assembly path.

## Qualification and remaining integration

The focused Java 17 gate passed **22/22 plus Javadoc**, 18 seconds, using the
coherent immutable Language `8542285144a8969f157d73e73292398885105c46`, BEX
`ab72af14ee54c6123e6d80349956af373a887680`, Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f` tuple. Four new constructor controls ran
with the unchanged control, SDK metadata and SDK point-storage controls.
An initial test wrongly forbade even the journal STATE read; only that fixture
expectation changed, while row/cursor reads remain forbidden during open.

This verifies constructor installation, not complete nonempty physical recovery.
The physical assemblers must still detach every family, share the correct view
scope, validate selected cross-record associations, and associate their roots
with one scoped host publication. No durable app mode or full PostgreSQL E2E
success is claimed here.
