# Unique occurrence inventories in the inactive source-reference proof

The helper supporting the pending historical source publication proposal originally found a successor with the same occurrence identity without consuming it. A duplicated prior row could match the same successor twice and leave a different equal-count successor unchecked; duplicated rows on both sides were also accepted. Two new negative tests reproduced both failures (nine tests, two failures). This is an isolated proof-helper defect; the helper has no production call sites, and the publication guard remains unchanged.

The correction indexes successor rows by exact occurrence identity, rejects duplicates, and consumes each match exactly once. Equal sizes then require a bijection. All existing exact child-body checks, lineage/policy/path/activation/cursor checks, and complete remaining parent comparison are preserved. It introduces no runtime publication permission or inventory exception.

All nine focused tests pass after repair, including the six prior proof tests and a two-occurrence control with reordered inventory, inline/reference representations, and an independently forged second child. Source and failed/passing JUnit evidence are retained under rc-closeout/evidence/three-node-ring-reference-proof/inventory-bijection-{red,green} in the integrated MyOS closeout workspace. The exact development dependencies are Language2c52f9af, BEX2948afaa and catalog45ef8d06.

The separate proposed source-history publication correction is still unwired and awaits its own explicit approval. These tests do not establish graph recovery, public acceptance or final RC readiness.
