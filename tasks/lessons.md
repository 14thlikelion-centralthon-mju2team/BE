# Lessons

- Calendar sync tombstones are valid Google change records with an `id` and `status=cancelled` but no start/end. Validate cancellation before requiring timed-event fields.
- Constraint recovery must be operation-specific: never turn arbitrary `DataIntegrityViolationException` into a successful idempotent result merely because an Event can be re-read.
- Orchestration gate tests must pass through the real Event loop and a non-null final sync token; early-return fixtures do not prove the gate's behavior.
- Tests for same-day behavior must pin a safe local time; `Instant.now().plusSeconds(...)` can cross midnight and invalidate the fixture while production behavior remains correct.
- Concurrency tests must signal ordering only after the database lock is actually acquired. A latch fired before a repository invocation proves intent, not lock ownership, and leaves PostgreSQL waiter order nondeterministic.
- A timestamp is not a safe stale-result token across JVM and PostgreSQL precision boundaries. Use a persisted optimistic revision and carry the exact creation revision through asynchronous classification.
- Any configurable endpoint receiving a secret or private title must be an explicit HTTPS allowlist decision in both bean activation and the per-call gate; nonblank URL validation is not a security boundary.
- Every writer that can touch Event must join the Event-first lock order. A transactional writer without the row lock can still flush a stale entity snapshot over user-owned fields.
