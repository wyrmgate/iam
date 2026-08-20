# Shared Packages

Shared code belongs here only when it is genuinely cross-application and does not weaken bounded-context ownership.

Do not move domain concepts into `packages/` merely to make imports convenient. Stable IAM domain concepts remain owned by their backend bounded context; frontend feature code remains owned by the console feature that uses it.

Appropriate future examples may include generated API clients, shared schema artifacts, test fixtures, or carefully scoped UI primitives.
