# Automatic user provisioning

After Keycloak authentication, OpsPilot now provisions the local user before
mounting React's organisation provider. No packages, schema changes, migrations,
organisation roles, or memberships are added by provisioning.

## Endpoint and security policy

`POST /api/me/provision` has no request body. Spring Security applies the existing
JWT signature, issuer, audience (`opspilot-backend`), and timestamp validation.
The controller reads identity claims exclusively from `@AuthenticationPrincipal Jwt`.
Body/query values cannot supply the identity, local ID, status, or role.

Both creation and repeat calls return **200** with only:

```json
{"id":"<local UUID>","displayName":"Alex Example"}
```

Policy:

- The JWT subject must be a nonblank string fitting the existing 255-character
  identity column. The configured single trusted issuer scopes this subject.
- An existing user is resolved only by subject. Existing email/display name and
  memberships are left intact; no email-based linking or profile synchronization
  occurs. Existing users do not need optional profile/email claims on every login.
- New users must have a valid, nonblank email of at most 255 characters and the
  Boolean claim `email_verified: true`. Missing, false, or string `"true"` is rejected
  with 403. No placeholder email is invented.
- New emails are stripped and lowercased. A case-insensitive check prevents collision
  with legacy mixed-case addresses; existing email values are not rewritten.
- Display name uses `name`, then `preferred_username`, then `OpsPilot user` and fits
  the database's 100-character limit without splitting Unicode code points.
- `User.create` supplies the existing ACTIVE domain default. An INACTIVE account is
  never reactivated. The shared authenticated-user resolver also rejects inactive
  accounts on organisation/service endpoints, preventing direct-API bypass of setup.
- A collision with another subject's email returns a generic 409 without identifying
  the other account. It never alters the other identity or grants access to it.
- Missing/malformed/invalid JWTs return 401 before provisioning. Other existing
  membership and RBAC rules, including VIEWER write restrictions, are unchanged.
- A newly provisioned account initially sees an empty organisation list. The existing
  explicit Create organisation workflow creates its OWNER membership, as before.

Do not repoint the issuer to another realm and reuse local subject mappings without
an identity migration. Multiple trusted issuers would require an issuer-aware key.

## Concurrency and idempotence

The service uses `TransactionTemplate` with independent transactions and the existing
PostgreSQL unique subject/email constraints. There is no process-local locking.

Each call first looks up the subject. If another request commits between this lookup
and the email check, the service checks the subject again and returns that identity.
If concurrent inserts race, the losing transaction rolls back completely; a fresh
transaction resolves the winning subject mapping. A different subject with the same
email receives 409. Only the two known identity/email uniqueness violations trigger
this recovery; other integrity failures are not silently treated as success.

Repeated provisioning returns the same local ID and performs no profile or membership
updates. This remains safe across tabs and application instances sharing PostgreSQL.

Spring transaction behavior is documented in
[TransactionTemplate](https://docs.spring.io/spring-framework/docs/7.1.0-M2/javadoc-api/org/springframework/transaction/support/TransactionTemplate.html)
and [transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

## Frontend lifecycle

`initializeApplication()` caches one startup promise outside React:

1. Initialize the existing Keycloak adapter and finish hosted login.
2. Call the provisioning endpoint through the existing authenticated API client,
   retaining its bearer-token refresh and 401 handling.
3. Mount the existing StrictMode, authentication boundary, QueryClientProvider,
   OrganisationProvider, BrowserRouter, and App only after provisioning succeeds.

StrictMode cannot duplicate startup provisioning. The endpoint is also idempotent
for later page loads or multiple tabs. Query keys, caching, invalidation, routes,
and token storage behavior are unchanged; JWTs remain in adapter memory.

On setup failure the authentication boundary hides the providers/application and
shows an account-setup error with explicit Retry and Logout buttons. There is no
automatic retry/redirect loop. A 401 retains the existing signed-out behavior.

## Files changed in this milestone

| File | Change |
| --- | --- |
| `backend/.../application/UserProvisioningService.java` (new) | Validates creation claims; resolves/creates active users with transaction-safe uniqueness recovery. |
| `backend/.../application/AuthenticatedUserService.java` | Shared ACTIVE-status enforcement. |
| `backend/.../application/exception/UserProvisioningConflictException.java` (new) | Generic account-conflict error. |
| `backend/.../domain/UserRepository.java` | Case-insensitive email-existence check. |
| `backend/.../web/MeController.java` (new) | Authenticated, bodyless provisioning endpoint using JWT claims only. |
| `backend/.../web/dto/UserResponse.java` (new) | Minimal local ID/display-name response. |
| `backend/.../web/exception/GlobalExceptionHandler.java` | Maps provisioning conflicts to the existing JSON error format with 409. |
| `backend/.../application/UserProvisioningIntegrationTest.java` (new test) | PostgreSQL/HTTP provisioning, concurrency, conflict, status, profile, empty-list, membership-retention, and explicit organisation-creation tests. |
| `backend/.../web/JwtAuthenticationTest.java` (test) | Extends real JWT-decoder tests to the provisioning endpoint; tests missing/malformed tokens. |
| `backend/.../web/MonitoredServiceAuthorizationTest.java` (test) | Marks the existing mocked local user ACTIVE; retains all RBAC cases. |
| `frontend/src/api/meApi.ts` (new) | Calls provisioning through the existing client, with actionable setup errors. |
| `frontend/src/auth/bootstrap.ts` (new) | Shared authentication-then-provisioning startup sequence. |
| `frontend/src/main.tsx` | Waits for the complete startup sequence before mounting providers. |
| `frontend/src/auth/AuthenticationBoundary.tsx` | Blocks application rendering after setup failure; provides explicit recovery/logout. |
| `frontend/src/test/bootstrap.test.ts` (new) | Verifies startup ordering, deduplication, and authentication/provisioning failures. |
| `frontend/src/test/apiClient.test.ts` | Tests the bodyless bearer POST and safe 401/403/409/500/network failure behavior. |
| `frontend/src/test/AuthRoutes.test.tsx` | Verifies setup failures hide the application under StrictMode. |
| `docs/keycloak-authentication.md` | Replaces obsolete manual provisioning instructions with the current flow. |
| `docs/user-provisioning.md` (new) | This implementation report and verification procedure. |

Backend main paths abbreviated above are under
`backend/src/main/java/com/opspilot/backend`; test paths are under
`backend/src/test/java/com/opspilot/backend`.

Pre-existing uncommitted work, including the package manifests/lockfile and the first
authentication milestone, was preserved. No dependencies were installed or migrations
edited. Existing security/CORS configuration, User entity, membership services, and
organisation controllers needed no architectural changes.

## Validation

From `frontend`:

```powershell
npm.cmd run test:run
npm.cmd run lint
npm.cmd run build
```

All **54 tests across 8 files passed**, ESLint passed, and the production build passed.
Frontend startup/adapter tests use mocks; they are not real-browser integration tests.

From `backend`, using already cached Maven and Docker for PostgreSQL Testcontainers:

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
```

All **220 backend tests passed**, zero failures/errors/skips. The new provisioning
integration class has 14 passing test invocations. The full suite includes existing
organisation RBAC and PostgreSQL repository tests, plus actual JWT signature/issuer/
audience/expiry rejection on the new endpoint. Compilation passed as part of this run.
There is no backend lint goal configured in the current Maven project.

An initial concurrent same-subject test exposed an email-check race; the subject
recheck fixed it, and the complete suite passed afterward. An ESLint caught-error
rule was satisfied by preserving causes on wrapped setup errors.

Packaging was also attempted with `-o -DskipTests package`, after the full test run.
Offline packaging is blocked by uncached dependencies of the existing Maven JAR
plugin (including `file-management`, `maven-archiver`, and `plexus-archiver`). No
dependencies were downloaded; download approval was requested separately. This is
a packaging-tool dependency limitation, not a compilation/test failure.

## Keycloak setup and real-browser verification

Read-only inspection on 2026-10-23 confirmed the realm's email scope already emits
`email` and Boolean `email_verified` in access tokens. The realm currently has
**Verify email disabled and no SMTP server configured**. These settings were not changed.

Before provisioning a new user:

1. In realm **opspilot**, ensure `opspilot-frontend` retains the `email` client scope
   and the existing backend audience mapper. No frontend secret or new audience is
   needed for provisioning.
2. Configure realm SMTP, enable **Realm settings → Login → Verify email**, and have
   the user complete Keycloak's hosted verification. An operator may instead mark a
   disposable development user's email verified after independently confirming
   ownership; do not mark arbitrary real addresses verified merely to bypass setup.
   See [Keycloak's administration guide](https://www.keycloak.org/docs/26.5.7/server_admin/index.html).
3. Sign out and sign in again after verification so the access token contains the
   updated Boolean claim. No manual PostgreSQL user insert is required.

Then verify:

1. Start the existing PostgreSQL/Keycloak services, backend on 8080, and Vite on 5173,
   using the commands in [keycloak-authentication.md](keycloak-authentication.md).
2. Use a Keycloak test user with a unique verified email and no existing local subject
   mapping. In a private browser, open `http://localhost:5173/services` and sign in.
3. In DevTools Network, confirm one `POST /api/me/provision` with a bearer header and
   no request body returns 200. Its response must contain only `id` and `displayName`.
   Only afterward should `GET /api/organisations` run and return `[]`.
4. Confirm in PostgreSQL that exactly one ACTIVE `users` row has that Keycloak subject,
   and that no organisation membership exists for it. Use a read-only query, for example:

   ```sql
   SELECT id, identity_provider_subject, status FROM users
   WHERE identity_provider_subject = '<Keycloak user ID>';
   SELECT role FROM organisation_memberships
   WHERE user_id = '<local UUID returned by provision>';
   ```

5. Reload, open a second tab, and sign out/in again. Each page startup may call the
   endpoint, but every response must return the same local ID, with one database row
   and no new memberships. Verify JWTs remain absent from browser token storage.
6. Submit Create organisation. Confirm the existing workflow explicitly creates one
   OWNER membership. Sign out/in again and verify that organisation/membership remains.
7. Use an existing VIEWER and writer account to check that VIEWER writes still return
   403 and authorised writes succeed; non-member/cross-organisation IDs remain rejected.
8. With separate test identities, check unverified/missing email (403), duplicate email
   under a different subject (409), and an INACTIVE local account (403). The UI must
   stay on the setup screen without an automatic redirect loop or organisation queries.
   Direct organisation requests from the inactive subject must also return 403.
9. A request without bearer credentials or with `Bearer invalid` to the provisioning
   endpoint must return 401 and create no user.

The agent did not execute an interactive browser flow for this milestone; these are
manual verification steps, separate from the passing HTTP/PostgreSQL and frontend tests.
