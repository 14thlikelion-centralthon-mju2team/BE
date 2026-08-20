# Lessons

- Calendar sync tombstones are valid Google change records with an `id` and `status=cancelled` but no start/end. Validate cancellation before requiring timed-event fields.
- Constraint recovery must be operation-specific: never turn arbitrary `DataIntegrityViolationException` into a successful idempotent result merely because an Event can be re-read.
- Orchestration gate tests must pass through the real Event loop and a non-null final sync token; early-return fixtures do not prove the gate's behavior.
