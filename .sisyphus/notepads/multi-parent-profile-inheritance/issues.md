## 2026-03-17 — Stabilization task notes

- Attempting to use Jackson `@JsonFormat(with = ACCEPT_SINGLE_VALUE_AS_ARRAY)` on the list field is incompatible with the current Micronaut Serde annotation processor configuration and causes `:compileJava` failure.

## 2026-03-17 — Task 13 native verification issue

- `OcpCommandTest` included three subprocess startup-migration assertions that pass on the JVM but fail under `nativeTest` because spawning/revalidating the host `java` command from within the GraalVM test image is not reliable/supported there.
