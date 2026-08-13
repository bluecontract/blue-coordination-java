# NBA historical catch-up

The NBA scenario replays start, scoring plays, and game end on commissioner
Timelines. A statistics Root discovers Games from direct stable-key members of
`Process Embedded.collectionPaths`. Each Game processes each source entry once;
League, Team, and Player documents consume its immutable epochs through their
own occurrence cursors.

Histories from several Games are merged by canonical source order under one
parent barrier. Initialization precedes replay, the attachment entry is
exclusive, and the next statistics entry waits until every nested prerequisite
and cursor is complete. Host-first, completed-game-first, partial-history, and
reused-history admission orders must converge to the same exact final BlueIds
and aggregates. Verified Round 10.1 scenario status is tracked in the
[RC test report](../releases/3.0.0-rc.1-test-report.md); `../blue-basic` retains
only historical timing variants.
