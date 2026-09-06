# Archived r15.2 chronology experiment

These are unchanged source copies of the early, pre-implementation experiment. Original repository
paths are preserved below this directory. They are deliberately outside the current Gradle source
sets: neither these Java tests nor their historical build wiring is added to today's test gates.

The [original report](../phase-1-chronology.md), [build patch](../build-support.patch),
[attachment result](../evidence/attachment-junit.xml) and
[history result](../evidence/history-junit.xml) retain the original evidence: three passing attachment
controls and four failing history cases. They do not describe the current external-state API's
verification status; use [Phase1/2 readiness](../phase-1-2-readiness.md) for that.

## Preserved inputs

- `src/test/java/blue/coordination/sdk/SdkAttachmentChronologyTest.java`
- `src/pocTest/java/blue/coordination/sdk/PocHistoryChronologyTest.java`
- `src/test/resources/poc/chronology/source.yaml`
- `src/test/resources/poc/chronology/observer.yaml`

For historical reproduction, use a separate disposable checkout of Coordination
`20fca9fd9934f367612c27348b8b6532d4029672`, restore these four files at their original paths and apply
the linked build patch. Use the original locked `published-artifact` dependencies and commands in
the report, not the new sibling library builds. The old patch's G1 language belongs to that snapshot.

Do not apply that patch to the current implementation just to make the archive runnable. In
particular, restoring the attachment class under today's `src/test` would silently add it to the
normal test suite without establishing current-scope acceptance. No assertions or historical results
were changed when archiving this material.
