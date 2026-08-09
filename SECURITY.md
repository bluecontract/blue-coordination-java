# Security policy

## Supported versions

Security fixes are currently prepared for the latest 3.x release candidate.
The removed 2.x experimental engine is not supported.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Email
`devsupport@timeline.blue` with the affected version, impact, reproduction and
any suggested mitigation. Expect acknowledgement within three business days.

## Security boundary

This release is an in-memory, single-process coordination library. It does not
authenticate actors, persist timelines, encrypt application data, provide
distributed consensus or create a durable audit log. The host application is
responsible for authenticated Timeline admission, authorization, persistence,
transport security, secret handling and resource isolation.

Exact BlueId validation protects semantic identity; it is not an authorization
mechanism. Apply input-size and execution limits before accepting untrusted
documents. Treat failure details and metrics as operational data that may reveal
document topology.
