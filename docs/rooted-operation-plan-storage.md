# Retained operation plans

## Problem and example

An SDK operation can explicitly create a managed child or select a particular
historical source epoch. The Timeline entry alone does not replace those exact
append-time plans. After restart, reconstructing a selector from the current
source head or guessing whether a child was explicitly created would change the
operation's inputs and potentially its history.

## Storage boundary

`OperationPlanStorageCodec` is package-private and stores one journal-keyed row.
It retains the captured target identity/epoch, exact authored child values with
their original frozen/resolver/cyclic evidence, identity policy, known epoch,
request-field mapping, activation modes and occurrence-specific historical
selectors. Draft and selector plans sharing a row must agree on the captured
target. The decoder requires the expected entry key, bounded canonical bytes,
and all existing plan-constructor checks.

`ContractsClosureAdapter.operationPlanForStorage` only reads an existing row.
It does not prepare work, initialize children, resolve a provider or install a
runtime. The existing complete-control guard still rejects omitted plans until
the complete owning factory integrates this component. A trusted pinned host
reference and verified original journal membership remain required; the codec
is not a new user-admission API or standalone proof of correct execution.

## Verification

Actual SDK submissions exercise explicit child creation and an exact epoch-zero
selector. Encoding/decoding leaves the parent and absent child unchanged; the
original operation still succeeds, and the captured plan also decodes after the
producer closes. Root-owned processing retains its original append-time plan;
the test does not invent eager cleanup. Negative checks cover another entry key,
conflicting targets, truncation, trailing bytes and physical size bounds.

The focused gate passed 15/15 plus strict Javadocs on Language `bd09c281`, BEX
`ab72af14` and Catalog `0b68744b` with matching immutable bindings: 3 new plan
controls, 6 rooted-created-child cases, 2 SDK selector controls and 4 engine
control cases. This is not full cold-runtime or application acceptance.
Evidence is archived under `rooted-operation-plan-evidence.XqWH9a`.
