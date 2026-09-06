# 5. Read only what this change needs

> **Design baseline:** 15.12 · **Status:** logical POC walkthrough; see the [implementation boundary](README.md) for what is verified

[Previous: sharing](04-one-agreement-many-orders.md) · [Tutorial map](README.md) · [Next: time and order](06-time-and-order.md)

An Order can refer to a large Agreement, which refers to other content. Expanding every reference
up front can turn a small-looking document into a very large in-memory object.

The proposed POC keeps exact content and document state in storage. It tries to load only the parts
required for the selected change. **Lazy** means “load when needed”, not “guess when missing”.

## A small change in a large document

Suppose an Order has shipping rules, product descriptions, and a long archive of attachments. The
current request checks only whether its Agreement is active.

The intended path is:

1. Read the Order's current state and relevant relationship information.
2. Read the exact Agreement value selected for this observation, including an intermediate logical
   boundary when required—not whichever source head is newest in storage.
3. Run the relevant rule and required reactions.
4. Leave unrelated attachment bodies unopened.

The arrows below mean **reads required for this example**:

```mermaid
flowchart LR
    requestWork["Check Order"] --> orderState["Order state"]
    orderState --> agreementState["Agreement status"]
    agreementState --> resultState["Calculate result"]
```

The database may still need relationship and subscription metadata to establish which documents
are affected. Avoiding large content reads does not prove that metadata lookup is cheap. The POC
measures those two costs separately.

There is an important first-implementation limit: this does not mean every individual field or
every recorded action is streamed directly from SQL. Managed document bodies can be requested when
needed, but Coordination still needs complete relationship/order evidence for the selected work.
It currently decodes a selected source observation program and its reused dependencies within
configured physical limits. Phase3 will measure that memory cost as well as the content left unopened.

## What if processing discovers missing content?

Imagine Agreement's source result is already committed. Its next Order reaction needs a rule body
that has not been supplied yet.

For the proposed POC boundary:

1. The attempt reports what exact material is missing.
2. This Order invocation's tentative changes and cursor advance are **not committed**.
3. MyOS obtains the material, verifies it, and keeps it durably or in an appropriate cache.
4. Processing restarts from the invocation's beginning using valid input evidence.

Agreement remains committed, and other independent Orders may proceed. Recovery restarts only this
Order invocation; the target is to reuse sufficient source evidence without rerunning source rules.
It does not save a half-finished Order queue as a portable continuation.
The restarted local invocation preserves its required history and gas behavior; extra physical
attempts are measured separately. Missing material during the source's own tentative invocation
similarly retries that source invocation, not a partially committed source result.

This restart rule is simple to recover correctly, but it can be expensive. If one more missing item
is found on each attempt, repeated processing may dominate the cost. Batching predictable reads and
sharing immutable content can help. The first implementation must measure data-dependent discovery
before claiming that lazy processing is fast.

## A cache is useful, but never the truth

A warm cache can avoid a database read. An empty cache must not change the answer. A fresh process
must be able to reconstruct the correct work from durable input and state.

Sometimes correctness requires more information than a single path. Entering a cyclic component,
for example, needs complete relevant cycle evidence. “Lazy” is not permission to ignore that proof.

**Check your understanding:** can unavailable content be treated as an absent field? No. “Not yet
loaded” and “does not exist” are different facts.
