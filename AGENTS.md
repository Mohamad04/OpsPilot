# OpsPilot Engineering Guidance

## Project rules

- Inspect the actual source and relevant phase documentation before implementing changes. Respect the existing modular monolith, package conventions and API contracts; avoid unrelated refactoring.
- Preserve uncommitted work. Never discard, overwrite or stash another change without authorization. Do not automatically commit, push or merge.
- Protect tenant isolation: authorize the validated local user/membership on the backend and scope queries and associations by organisation. Frontend controls and caller-supplied IDs or roles are not authorization.
- Preserve backward compatibility unless an explicitly documented security correction requires a contract change.
- Use a new sequential Flyway migration for schema changes. Never rewrite applied migrations or reset development/business data to make tests pass.
- Keep domain invariants in domain/application code, controllers thin and transaction boundaries explicit. Preserve established locking, idempotency and immutable audit-history guarantees.
- Add appropriate focused tests and run relevant regression checks. Report commands and actual results; distinguish automated, manual and unverified evidence. Do not weaken or skip tests to obtain a green build.
- Do not invent unsupported functionality, performance claims or production-readiness. Keep technical documentation consistent with the implementation and distinguish current capabilities from future plans.
- Stay within the requested phase and scope. Do not introduce dependencies, infrastructure or security exceptions without the required authorization.

## Mandatory README Maintenance

1. Read the root `README.md` before implementing changes that may affect project documentation.
2. After completing every implementation task, review whether the README accurately reflects the repository.
3. Update `README.md` in the same task whenever changes affect features/capabilities, architecture, technology/dependencies, API surface, configuration/installation, authentication/authorization, testing/CI, screenshots/demonstrations, milestones/roadmap, deployment or operational behavior.
4. Never describe planned features as implemented.
5. Never mark a phase complete until its implementation has passed its acceptance criteria. Record remaining blockers and limitations accurately.
6. Keep the README concise, accurate and recruiter-friendly. Put detailed contracts and execution evidence in linked technical documentation.
7. Prefer updating existing sections over duplicating information.
8. Preserve accurate documentation when changing unrelated code.
9. Verify documentation links, code examples and setup instructions where possible. State verification limitations instead of claiming unexecuted checks passed.
10. Include README changes alongside the corresponding implementation changes whenever appropriate.
11. In every final development report, include a **Documentation** section stating whether `README.md` was updated, which sections changed, or why no update was necessary.
12. Treat README maintenance as part of the definition of done for every feature.
13. Do not add unnecessary README changes for work with no externally relevant impact.
