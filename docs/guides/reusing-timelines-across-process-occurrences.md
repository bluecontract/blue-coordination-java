# Reusing Timelines across process occurrences

An immutable Timeline or Channel definition can be referenced from many
embedded scopes. Reuse reduces content duplication; it does not merge the
scope occurrences.

Suppose two lesson keys reference the same lesson and Timeline definitions:

```text
/portfolios/uk/lessons/algebra
/portfolios/uk/lessons/geometry
```

Language's effective catalog produces two concrete scope paths. Coordination
projects two subscription occurrences and two activation intervals. A single
canonical definition fragment may back both references, but each occurrence
retains its own:

- scope path and occurrence key;
- active/retired interval;
- Channel matching context;
- checkpoint domain and subject;
- workflow state within Root.

## Safe reuse checklist

1. Put the shared immutable Timeline/Channel content behind an exact BlueId.
2. Use stable object keys for every collection occurrence.
3. Let `EmbeddedScopePlanView` produce the concrete paths; do not synthesize
   wildcard or list-position paths.
4. Persist subscription keys by occurrence, never by definition BlueId alone.
5. Include the scope occurrence in checkpoint evidence.
6. Test two keys with the same child BlueId and prove exactly one execution
   per selected occurrence.
7. Remove and re-add one key and prove the new interval does not inherit the
   retired occurrence's checkpoint.

The flagship collection scenario exercises definition reuse across nested
lesson and payment-process occurrences.
