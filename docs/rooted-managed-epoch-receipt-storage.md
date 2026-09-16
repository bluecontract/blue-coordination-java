# Exact managed-epoch receipt storage

## Problem and example

A cold parent importing source epoch 1 needs the original ordered, typed emitted
events and private transition evidence, not merely the source's final body and
gas total. A later representation change can also leave the current source view
different from the last numbered epoch receipt. Rebuilding either from the
current head would change the reference calculation.

## Change and rationale

`StoredManagedEpochIndexes` retains the existing document/epoch and receipt-ID
AVL indexes, including their tree shape and logical work counters. Each selected
row contains the original public receipt and Language-owned complete transition
evidence; existing verification checks their association. The current
representation remains an independent field. Selected reads verify both indexes,
owner/epoch coordinates and in-range completeness. Physical reads do not count
as additional logical processing work.

The host must pin the exact immutable index descriptors. Opening and looking up
one owner does not enumerate other owners or decode their nested receipt
histories. Explicit `retainPartition` is a resident-to-stored conversion, not the
final cold-start algorithm. Complete factory integration must preserve these
selected cross-index checks; this component does not publish a mutable runtime
root or install the whole engine.

## Verification

Five new controls cover real SDK-generated typed events after producer closure,
exact next-result equality after replacing the receipt component, same-epoch
representation progress, missing/misbound indexes, and a seven-owner lookup with
every unrelated nested epoch object unavailable. The selected lookup traverses
unrelated outer AVL metadata but never reads an unrelated nested history.

The focused gate passed **21/21 plus Javadoc** on 11 September 2026: five new
controls and sixteen unchanged receipt/session controls, using immutable
Language `f241be7`, BEX `ab72af1`, Catalog `0b68744`. No processing algorithm,
failure disposition, epoch numbering or gas assertion was changed. This is
component qualification, not full cold SDK or PostgreSQL E2E acceptance.
