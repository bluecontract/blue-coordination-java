# Bounded journal processing through a submitted input

A synchronous host cannot stop after the first source result for an entry when
independent rooted observers still have that same input pending. It also cannot
replace that stop with an unlimited drain that consumes later entries.

`AdvancedCoordination.drainJournalThrough(EntryHandle, DrainBudget)` exposes the
existing journal lane with an inclusive full ExternalOrderKey cutoff. The SDK
requires a handle from its own runtime and the engine validates the canonical
retained entry. Each call still selects at most one ordinary journal obligation,
with the unchanged configured processor gas policy and publication boundary.
The caller can aggregate separate root outcomes until that interval is quiescent.

The ordinary fair-turn check remains. If a retained managed application owns the
next turn, the journal-only call rejects with PROCESSING_SELECTION_MISMATCH.
It cannot collapse historical applications into the calling operation's durable
command. A later journal entry is left pending, even when it was already stored.

This is host scheduling through existing processor semantics. It does not add
reverse observers to a source's calculation, change ownership/gas or alter the
single-root process/processNext APIs. No public API types or tariff counters were
added. The unchanged API boundary checks remain required.

`RootedJournalCutoffTest` verifies separate source and observer completions,
unchanged future input, repeat/no-duplicate behavior, foreign-handle rejection,
and the separate historical turn. Existing global-driver, gas and frozen-frontier
regressions also exercise the revised engine path. The integration campaign keeps
exact run logs and the MyOS public regression that exposed the old early stop.
