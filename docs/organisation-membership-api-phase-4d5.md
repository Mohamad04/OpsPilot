# Organisation membership management — Phase 4D.5

Implementation/validation date: **2026-10-29**. Branch:
`feature/phase4-incident-management`. Existing uncommitted incident/frontend/E2E
work was preserved. No development identities, memberships or fixtures were
created, and no development database was changed. Tests use disposable containers.

## Identity and onboarding

1. The identity first signs in through the existing Keycloak hosted PKCE flow.
2. `POST /api/me/provision` resolves the validated JWT subject and provisions an
   ACTIVE local user under the existing verified-email and uniqueness rules.
   Provisioning grants no organisation role.
3. The user's own provisioning response supplies their local `id` and display
   name. Share that UUID with an authorised organisation administrator through an
   approved channel. It is **not** the Keycloak user ID/subject. There is no global
   user lookup by arbitrary email or subject.
4. The existing organisation creation workflow creates its initial OWNER.
5. An authorised OWNER/ADMIN adds the already-provisioned ACTIVE user by local ID.
   Unknown and inactive target users both return the same 404 error.

Keycloak authenticates identity; local PostgreSQL memberships authorize tenant
access. Realm/client roles never grant these permissions. Every endpoint resolves
the authenticated local user and rejects inactive actors. The client cannot
choose the audit actor, email, identity-provider subject or effective privileges.
There is no invitation system or automatic account creation in this API.

## Authorization

| Operation | OWNER | ADMIN | ENGINEER | VIEWER |
| --- | --- | --- | --- | --- |
| Read own organisation's directory | Yes | Yes | Yes | Yes |
| Add ADMIN | Yes | No | No | No |
| Add ENGINEER/VIEWER | Yes | Yes | No | No |
| Change non-OWNER to ADMIN | Yes | No | No | No |
| Change ENGINEER/VIEWER between those roles | Yes | Yes | No | No |
| Modify/remove ADMIN | Yes | No | No | No |
| Remove ENGINEER/VIEWER | Yes | Yes | No | No |
| Add, demote or remove OWNER | No | No | No | No |

The last row preserves the **existing stricter service policy**: ordinary member
operations cannot transfer ownership, create another OWNER, or demote/remove any
OWNER. The last OWNER therefore cannot be lost through these APIs, even under
concurrent requests. Ownership transfer is a separate, unimplemented workflow.
ADMIN cannot promote itself or another member to ADMIN/OWNER or alter an existing
ADMIN/OWNER membership. This is an intentional preservation of the existing
business rule rather than a new ownership-transfer feature.

Non-members receive 404. Foreign/unknown membership IDs have the same scoped 404
response. ENGINEER/VIEWER mutations fail with 403 before target lookup. Malformed
input still returns 400 through the existing request validation conventions.

## API contracts

All routes require an existing valid bearer JWT with the API audience. Let
`{base}` be `/api/organisations/{organisationId}`.

| Method | Route | Success |
| --- | --- | --- |
| GET | `{base}/members?page=0&size=20` | 200, member page |
| POST | `{base}/members` | 201, member |
| PATCH | `{base}/members/{membershipId}` | 200, member |
| DELETE | `{base}/members/{membershipId}` | 204, no body |

POST (substitute an existing local UUID for the placeholder):

```json
{ "userId": "<local-user-uuid>", "role": "ENGINEER" }
```

PATCH:

```json
{ "role": "VIEWER" }
```

Both POST fields are required; PATCH requires role. Roles are case-sensitive enum
strings; unknown, null and numeric values are rejected. User IDs require canonical
UUID strings. A successful same-role PATCH is a no-op with no new audit event.
Duplicate organisation/user POST returns 409; repeat DELETE returns 404.

`OrganisationMemberResponse` contains:

| Field | Type | Purpose |
| --- | --- | --- |
| `id` | UUID | Membership ID used for PATCH/DELETE. |
| `organisationId` | UUID | Authorised tenant. |
| `userId` | UUID | Provisioned local user ID. |
| `displayName` | string | Display label; render as text. |
| `role` | OWNER / ADMIN / ENGINEER / VIEWER | Local organisation role. |

`OrganisationMemberPageResponse` contains `content` (member array), `page`, `size`,
`totalElements`, `totalPages`, `first`, `last`, matching existing page conventions.
No email, identity-provider subject, credentials, tokens or JPA entities are
returned. The directory shows memberships, including users later deactivated;
it does not promise incident-assignment eligibility. Assignment still verifies
ACTIVE operational membership independently.

GET uses database pagination: zero-based page, default size 20, maximum size 100,
fixed deterministic membership ID ascending order. Negative page, size outside
1–100, non-integers and integer overflow return 400. A valid out-of-range page is
empty. There is no invented filtering/search/sorting contract or multi-page
snapshot guarantee.

Errors retain `ApiError` (`status`, `message`, `errors`):

| Status | Meaning |
| --- | --- |
| 400 | Invalid JSON, role, required field, UUID or pagination. |
| 401 | Missing/invalid/expired/wrongly issued, signed or audienced JWT. |
| 403 | Inactive actor, insufficient role or ownership policy. |
| 404 | No authorised membership, scoped target or eligible local user. |
| 409 | Duplicate membership or translated locking conflict; refresh before retry. |

## Transactions and tenant isolation

`OrganisationMembershipService` remains the policy owner. Thin controllers supply
only the validated JWT subject and validated scoped input. Existing role-policy
helpers are now package-private implementation details, not unauthenticated REST
operations.

Administration runs in one transaction:

1. Resolve the ACTIVE actor and check membership with a scalar existence query.
2. Lock the organisation row using PostgreSQL `FOR NO KEY UPDATE`.
3. Read the actor's membership **after** acquiring the lock. The pre-lock existence
   query does not cache stale membership roles in the persistence context.
4. Resolve the target within scope, apply existing role checks and mutate.
5. Append an authenticated audit receipt and commit both changes or neither.

This serializes cooperating writers across application instances. A queued admin
request rechecks permissions after concurrent demotion/removal. The existing unique
`(organisation_id, user_id)` constraint independently rejects duplicates.
`FOR NO KEY UPDATE` remains compatible with foreign-key KEY SHARE locks used by
incident/event writes; no new lock order is imposed on the correlation engine.
There is no membership expectedVersion contract: permitted operations evaluate
the state current when the lock is acquired. Direct privileged SQL writers are
outside the HTTP authorization boundary.

Removal uses the existing V9 assignee membership FK and V12 trigger. Assigned
incidents are unassigned, their versions advance, and system-origin
`ASSIGNMENT_CHANGED` events retain the previous assignee. The API flushes deletion
before appending its audit receipt; a failure rolls back removal, unassignment,
incident events and auditing together. Historical human authors remain attached
to local users. Role demotion alone retains membership and past assignments;
subsequent backend authorization uses the new role.

## Database: V13

`V13__create_membership_audit_events.sql` adds an audit table, supporting indexes
and immutable-history triggers. V1–V12 are unchanged; existing business records
are not rewritten/backfilled.

Receipts contain UUID ID, organisation/membership/target-user/actor-user IDs,
action (`ADDED`, `ROLE_CHANGED`, `REMOVED`), old/new roles and injected-Clock
`TIMESTAMPTZ`. CHECK constraints validate actions, roles and transition shape.
Organisation/user foreign keys preserve referential integrity; there is no
membership FK because removal receipts outlive memberships. Receipt identities
and tenant scope come from authorised entities, not client audit metadata.

Organisation/history, actor and target indexes support future scoped audit reads
and referential checks. JPA `@Immutable` and ALWAYS-enabled PostgreSQL
UPDATE/DELETE/TRUNCATE rejection triggers protect receipts. Database-owner powers
still require operational controls. No audit API/retention policy is added.
Receipts cover the new administration operations; initial OWNER bootstrap remains
unchanged without a new/backfilled receipt.

V13 is tested through Flyway/Testcontainers and Hibernate validation. The local
development database was not migrated by this task. Normal startup must apply V13
before using the updated code.

## Frontend integration

`OrganisationMember.ts`, `organisationMemberApi.ts` and `useOrganisationMembers.ts`
provide Zod contracts, existing authenticated `apiFetch`, cancellation and all four
operations. Keys include organisation/page/size. Response tenant, identifiers,
pagination and duplicate identities are checked. Cached data is hidden until a
fresh authorised read and after errors; cancelled late responses cannot replace
another tenant's directory.

Successful writes invalidate that organisation's members, organisation permission
context and incident queries (removal can change assignments/versions). 403/404/409
also trigger revalidation. Writes are not automatically retried. These utilities
do not introduce an administration screen or directory picker. The existing
incident form still requests a known local UUID and states that limitation.

## E2E workflow and limitations

The [existing setup guide](e2e-environment-fixture-setup.md) now uses this API:
create dedicated Keycloak identities through authorised administration; sign
each in/provision locally; create disposable organisations as OWNER; POST members
using engineer/viewer local IDs; GET members to verify roles; then prepare real
monitoring-driven incident fixtures. Existing E2E variable names are unchanged.
No identity, credential, membership or incident fixture was created here.

Invitations, ownership transfer, global user search, member administration UI,
incident directory picker and audit retention/export remain outside this phase.
Immutable receipts restrict deletion of referenced users/organisations, consistent
with historical authorship. This task does not complete authenticated Playwright
acceptance or claim fresh browser/screenshots evidence.

## Validation evidence

The initial focused run passed **64 tests** (43 new PostgreSQL/API cases plus 21
existing membership/organisation-response cases). Added unit tests cover scoped
lookup, authorization after locking, trusted audit attribution, inactive users,
no-op receipts and flush ordering. Real-JWT tests exercise all four routes,
tenant scope, fake realm privilege claims and invalid JWT variants.

The first full regression run had 45 context-loading errors in two existing
test slices, due to newly required audit/Clock/EntityManager dependencies.
Supplying appropriate test beans retains the real service and all authorization
assertions. The MVC/unit recheck passed 48 tests; the affected JPA DTO slice then
passed its four tests. The complete suite was rerun after both corrections.

The Windows Maven wrapper still fails before Maven starts with its existing
null-array error. The verified cached Maven 3.9.16 offline executable is used with
explicit Maven repository/home paths. No wrapper/dependency changes were made.

### Final executed results

| Check | Result |
| --- | --- |
| Focused membership PostgreSQL + existing membership/response tests | 64 passed, 0 failed/errors/skipped. |
| MVC authorization + new administration unit recheck | 48 passed, 0 failed/errors/skipped. |
| Existing detached-DTO JPA test slice recheck | 4 passed, 0 failed/errors/skipped. |
| Complete backend suite | **862 passed, 0 failures, 0 errors, 0 skipped**. |
| Offline backend `-DskipTests package`, after full test run | Passed; executable Spring Boot JAR produced. |
| Complete frontend `npm run test:run` | **391 passed in 22 files**, no failed/skipped tests. |
| Final incident-controls/membership-client focused run | 37 passed in 2 files. |
| Final `npm.cmd run lint` | Passed. |
| Final `npm.cmd run build` | Passed, including `tsc -b` and Vite production build. |
| Documentation relative file links | 64 checked across the six documentation files, none missing. |
| `git diff --check` | Passed; existing line-ending notices are not whitespace errors. |
| Live development migration / authenticated browser setup | Not executed; no claim of live fixture or Playwright acceptance. |

Backend commands used the cached Maven 3.9.16 executable with
`-Dmaven.repo.local=C:/Users/moham/.m2/repository`,
`-Duser.home=C:/Users/moham` and `-o`. Test goals/selectors were:

```text
-Dtest=OrganisationMembershipManagementIntegrationTest,OrganisationMembershipServiceTest,OrganisationResponseServiceTest test
-Dtest=MonitoredServiceAuthorizationTest,OrganisationMemberAdministrationServiceTest test
-Dtest=ResponseDtoIntegrationTest test
test
-DskipTests package
```

Frontend commands ran from `frontend`; focused tests used
`npm.cmd run test:run -- src/test/IncidentControls.test.tsx src/test/organisationMemberApi.test.tsx`.
The initial npm PowerShell shim emitted a prefix-path permission notice before
successfully running tests/lint; the final checks used `npm.cmd` successfully.
An initial sandboxed Maven compile could not read a cached Tomcat JAR; permitted
cache/Testcontainers execution succeeded without dependency changes. All expected
SQL-constraint/audit-failure errors in negative tests were asserted; no failing
tests remain in the final run.

## Files created or modified in this task

This inventory excludes unrelated pre-existing uncommitted work.

| File (relative to repository root) | Change |
| --- | --- |
| `backend/src/main/java/com/opspilot/backend/application/OrganisationMembershipService.java` | Authenticated directory/administration, scoped locks, existing role policy, active-target check and audit transaction. |
| `backend/src/main/java/com/opspilot/backend/domain/OrganisationRepository.java` | Organisation administration lock. |
| `backend/src/main/java/com/opspilot/backend/domain/OrganisationMembershipRepository.java` | Scoped target lookup, scalar membership check, paginated user fetch. |
| `backend/src/main/java/com/opspilot/backend/domain/MembershipAuditEvent.java` | New immutable validated receipt entity. |
| `backend/src/main/java/com/opspilot/backend/domain/MembershipAuditEventRepository.java` | New append-only repository surface. |
| `backend/src/main/java/com/opspilot/backend/web/OrganisationMemberController.java` | New four-route thin controller. |
| `backend/src/main/java/com/opspilot/backend/web/dto/AddOrganisationMemberRequest.java` | New strict user-ID/role input. |
| `backend/src/main/java/com/opspilot/backend/web/dto/ChangeOrganisationMemberRoleRequest.java` | New strict role input. |
| `backend/src/main/java/com/opspilot/backend/web/dto/OrganisationMemberResponse.java` | New safe member DTO. |
| `backend/src/main/java/com/opspilot/backend/web/dto/OrganisationMemberPageResponse.java` | New bounded directory DTO. |
| `backend/src/main/resources/db/migration/V13__create_membership_audit_events.sql` | New audit schema, checks, indexes and immutability triggers. |
| `backend/src/test/java/com/opspilot/backend/application/OrganisationMemberAdministrationServiceTest.java` | New scoped/locking/audit unit tests. |
| `backend/src/test/java/com/opspilot/backend/application/OrganisationMembershipManagementIntegrationTest.java` | New PostgreSQL/API/concurrency/removal/rollback tests. |
| `backend/src/test/java/com/opspilot/backend/web/TenantIsolationIntegrationTest.java` | All membership routes under real JWT authentication, local RBAC and tenant isolation. |
| `backend/src/test/java/com/opspilot/backend/web/MonitoredServiceAuthorizationTest.java` | New dependency mocks, existing real authorization assertions retained. |
| `backend/src/test/java/com/opspilot/backend/application/ResponseDtoIntegrationTest.java` | Clock dependency for existing JPA test slice. |
| `backend/src/test/java/com/opspilot/backend/application/IncidentCollaborationIntegrationTest.java` | Expected latest migration now 13. |
| `backend/src/test/java/com/opspilot/backend/application/IncidentCorrelationIntegrationTest.java` | Expected latest migration now 13. |
| `backend/src/test/java/com/opspilot/backend/domain/IncidentCollaborationMigrationTest.java` | Expected latest migration now 13. |
| `backend/src/test/java/com/opspilot/backend/domain/IncidentLifecycleMigrationTest.java` | Expected latest migration now 13. |
| `backend/src/test/java/com/opspilot/backend/domain/IncidentPersistenceIntegrationTest.java` | Expected latest migration now 13. |
| `frontend/src/types/OrganisationMember.ts` | New Zod/TypeScript contracts. |
| `frontend/src/api/organisationMemberApi.ts` | New authenticated/scoped API client. |
| `frontend/src/hooks/useOrganisationMembers.ts` | New isolated query keys, directory hook and mutation invalidation. |
| `frontend/src/test/organisationMemberApi.test.tsx` | New API, tenant-switch/cache and invalidation tests. |
| `frontend/src/components/IncidentActions.tsx` | Correct outdated directory-unavailable hint; picker remains unimplemented. |
| `README.md` | Capabilities, API, ownership policy, schema, E2E prerequisites, roadmap and documentation links. |
| `docs/organisation-membership-api-phase-4d5.md` | This contract, decisions, validation and file inventory. |
| `docs/organisation-onboarding.md` | Membership administration follow-up. |
| `docs/e2e-environment-fixture-setup.md` | Replace historical missing-API prerequisite with supported API setup. |
| `docs/playwright-e2e-phase-4e.md` | Link the implemented membership setup workflow. |
| `docs/incident-assignment-comments-phase-4d.md` | Distinguish original scope from later directory API. |

Phase 4D.5 acceptance is satisfied for the implemented backend/API and utility
scope, preserving the stricter existing OWNER rule. Live deployment of V13 and
dedicated E2E identity/fixture setup remain operator follow-up steps. No commit,
push, merge or Phase 4F implementation was performed.
