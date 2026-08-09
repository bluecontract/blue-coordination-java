# Large host and PayNote

The Wadowice scenario starts a roughly 60 KB host with 43 workflows, retains a
real PayNote as one exact request value, and attaches it through one `Process
Embedded` field. The host becomes one physical root shell plus one whole
autonomous PayNote; the PayNote itself is not generically fragmented.

The scenario then executes host work, two Alice authorizations, a restaurant
provider confirmation, parent revision propagation, and a warm host operation.
Its report separates append, frozen Contracts, embedded-only layout,
companion-delta commit, Coordination host overhead, and total latency.
