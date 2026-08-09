# NBA historical catch-up

The NBA scenario replays start, two scoring plays, and game end on the
commissioner's historical Timeline. A statistics or host document can attach
the exact original game state before, during, or after that history. The child
processes each source entry once and the parent consumes the committed revision
stream through the attachment frontier.

Four admission orders—host first, completed game first, partial history first,
and a second game instance sharing the same initial document—all converge on
the same final host state and `gameEnded` flag. The executable release-owned
scenario is `NbaHostLifecycleConvergenceTest`; `../blue-basic` retains only its
historical timing variants.
