# 1. One Order: from request to stored change

> **Design baseline:** 15.12 · **Status:** logical POC walkthrough; see the [implementation boundary](README.md) for what is verified

[Tutorial map](README.md) · [Next: document or content?](02-document-or-content.md)

Start with one Order already stored in PostgreSQL. It has no embedded documents yet.

```text
Order A
status: draft
```

A customer sends “Confirm this Order”. The document has a rule saying that a draft Order becomes
confirmed when it receives that request. We will assume the request is valid and its turn has arrived.
How MyOS knows it is the request's turn is the subject of chapter 6.

## What happens, step by step

1. **Record the input.** A Timeline provider accepts the request as an exact entry. MyOS durably
   records that entry and the fact that it needs processing. Receiving a request is not yet the
   same thing as changing the Order.
2. **Read the Order.** MyOS retrieves the current Order and the small amount of information needed
   to prove that this is the correct state to process. It does not load every other Order.
3. **Decide and calculate.** Coordination checks that this input may run. It calls Contracts, which
   applies the Order's rule and calculates the proposed confirmed state.
4. **Save one complete result.** MyOS checks that the state used in the calculation is still valid.
   In one database transaction it saves the change, its history, and the record that this work has
   completed. Any resulting notification is also recorded for later delivery.

This diagram shows **logical actions**, not separately deployed services:

```mermaid
flowchart LR
    inputEntry["Record request"] --> readOrder["Read Order"]
    readOrder --> calculate["Calculate change"]
    calculate --> commitResult["Commit complete result"]
```

## What changed in the database?

| Logical record | Before | After the successful commit |
|---|---|---|
| Order A | draft | confirmed |
| Order history | earlier committed changes | earlier changes plus this confirmation |
| Work for this input | waiting | completed, with its result |

The calculation before step 4 is **tentative**: it is not an authoritative change. If MyOS crashes
there, the durable Order remains draft. A new worker can read the input and calculate again.

If MyOS crashes just after step 4, the Order is already confirmed. Recovery must find the committed
result, not append a second confirmation. We return to that distinction in chapter 10.

## Who makes which decision?

MyOS handles storage and execution. Coordination decides which work is allowed and calls the document
processor. Contracts applies the document's rules. PostgreSQL makes the final set of writes all-or-nothing.

This division is the starting point of the POC: document behavior must not depend on whether a Java
object was already in memory or was just loaded from the database.

**Check your understanding:** after the request is recorded but before its result commits, is the
Order confirmed? No. The input is safe, but the document change has not happened yet.
