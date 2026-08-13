# Large host and PayNote

The Wadowice scenario starts a roughly 60 KB host with 43 workflows, retains a
real PayNote as one exact request value, and attaches it through one `Process
Embedded` field. The host becomes one physical root shell plus one whole managed
PayNote document; the PayNote itself is not generically fragmented.

The scenario appends host and PayNote entries once, then lets the environment
drain them in canonical order. It executes host work, two Alice authorizations,
a restaurant provider confirmation, processor-owned parent epoch application,
and a warm host operation.
Its report separates append, frozen Contracts, the derived non-frozen drain
residual, the unattributed portion within that residual, and test-fixture-only nested
delivery-plan/platform-commit diagnostics.
