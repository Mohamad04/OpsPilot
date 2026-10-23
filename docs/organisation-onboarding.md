# Create Organisation and authenticated onboarding

The existing form, authenticated POST, backend creation service, and automatic
creator OWNER membership were already connected. This change completes the empty
state, error feedback, cache/selection transition, and success feedback without
changing backend production code, authentication, RBAC, dependencies, or migrations.

## Resulting behavior

After Keycloak authentication and successful `/api/me/provision`, the existing
OrganisationProvider loads the user's memberships. Once that query succeeds with
an empty list, the layout shows a welcome message and **Create Organisation** link.
It does not automatically redirect or create an organisation. The creation route
remains accessible directly, including with a trailing slash.

The header also provides Create Organisation for existing members. Its organisation
selector has an accessible label and a disabled loading/empty option when appropriate.
An existing explicit selection survives navigation to the creation form.

React Hook Form and the existing Zod schema remain in use:

- Name is trimmed, required, and at most 100 characters.
- Slug is trimmed, 2–100 characters, with lowercase letters/numbers and single
  hyphens between words. Backend validation remains authoritative.
- The POST body contains only `name` and `slug`. The shared API client refreshes
  the in-memory access token as needed and adds its bearer header.
- Pending submission disables the button; a ref guard also blocks overlapping
  submit events before a rerender.
- HTTP 400 shows a useful validation summary and available name/slug field errors.
  HTTP 409 marks the slug as already taken. Existing centralized 401 handling ends
  the local session and exposes sign-in recovery; 403 remains a permission error.
  Failed requests do not change the selected organisation or navigate away.

On success, the page cancels older organisation-list requests, merges the server's
created organisation into the existing query cache, selects its ID, and navigates
to `/services`. There is no immediate follow-up organisation-list fetch. The list
query now passes its AbortSignal to the API client so late earlier responses cannot
erase the cached creation result. Existing entries and normal query refresh behavior
are preserved.

Services displays a success message for the organisation just created. Its existing
query key and request use the selected organisation ID; switching to another
organisation removes that creation message. No service cache is shared across IDs.

## Security verification

The unchanged backend controller takes the creator from `@AuthenticationPrincipal
Jwt` and passes its subject to `createOrganisationWithOwner`. The unchanged service
resolves the ACTIVE local user and transactionally creates the organisation and OWNER
membership. Frontend owner/user/role fields cannot choose another creator or alter
the server-assigned role. Provisioning itself still creates no membership.

PostgreSQL tests verify the creator/OWNER workflow, rejection of invalid slugs, a
409 for an existing slug without creating another membership, and rejection of
the other user's access to that organisation. Existing RBAC and tenant-isolation
tests remain enabled. Organisation status retains its existing INACTIVE creation
default.

## Files changed for this task

| File | Change |
| --- | --- |
| `frontend/src/pages/CreateOrganisationPage.tsx` | Guards duplicate submits; displays field/server errors; updates cache, selection, navigation, and success state. |
| `frontend/src/api/organisationApi.ts` | Handles creation 400/409 errors; supports cancellation of list requests. |
| `frontend/src/api/client.ts` | Adds optional field-error metadata to the existing ApiError, preserving existing authentication behavior. |
| `frontend/src/context/OrganisationProvider.tsx` | Passes the list query's cancellation signal to its existing API function. |
| `frontend/src/components/Header.tsx` | Accessible loading/empty selector and Create Organisation link. |
| `frontend/src/layouts/AppLayout.tsx` | First-organisation welcome state without redirects or blocking the form. |
| `frontend/src/pages/ServicesPage.tsx` | Success feedback after creation, scoped to the selected organisation. |
| `frontend/src/test/OrganisationOnboarding.test.tsx` (new) | Tests real frontend routes/provider/form/API-client wiring with mocked HTTP and authentication tokens. |
| `frontend/src/test/AuthRoutes.test.tsx` | Uses an existing-member fixture for the existing route regression tests. |
| `backend/src/test/java/com/opspilot/backend/application/UserProvisioningIntegrationTest.java` | Adds PostgreSQL/HTTP cases for creator identity, OWNER, duplicate slug, invalid slugs, and tenant isolation. |
| `backend/src/test/java/com/opspilot/backend/web/OrganisationControllerTest.java` | Explicitly verifies that unauthenticated organisation creation returns 401 before service invocation. |
| `docs/organisation-onboarding.md` (new) | This report and manual verification steps. |

All earlier uncommitted authentication/provisioning work and package-file edits were
preserved. `meApi.ts` and the provisioning lifecycle required no further changes.

## Executed checks

From `frontend`:

```powershell
npm.cmd run test:run
npm.cmd run lint
npm.cmd run build
```

Results: **65 tests passed across 9 files**, ESLint passed, and the production build
passed. The new workflow tests cover zero organisations, direct form access, success,
selector/cache updates, `/services` navigation, bearer headers/payload, duplicate
submission prevention, a late earlier list response, existing-member selection,
client validation, and 400/401/403/409 handling.

The first parallel run hit the empty-state test's default short wait; the test now
allows five seconds for initial query/render completion. The submit handler was
also adjusted to satisfy the React ref lint rule without disabling it. The final
full frontend checks passed.

From `backend`, using existing cached Maven dependencies and Docker:

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
```

Results: **231 tests passed**, zero failures/errors/skips. PostgreSQL Testcontainers,
JWT validation, provisioning, organisation creation, RBAC, and tenant isolation ran.

`git diff --check` passed. No browser-level integration test was executed; frontend
tests use jsdom and mocked HTTP/authentication, while backend tests exercise HTTP
filters/controllers and PostgreSQL separately.

## Exact manual browser verification

No new Keycloak, database, or environment configuration is required beyond the
working authentication/provisioning setup. Use the existing startup commands in
[keycloak-authentication.md](keycloak-authentication.md), with the backend on 8080,
Vite on 5173, and Keycloak on 8082.

1. Open a private window at `http://localhost:5173/` and sign in as an ACTIVE,
   provisioned user with no memberships. In Network, verify provisioning returns
   200, then the organisation list returns `[]`. Confirm the welcome text and Create
   Organisation action appear, with no automatic redirect or service request.
2. Click the welcome action. Confirm `/organisations/new` displays Name, Slug, and
   Create Organisation. Opening that URL directly must also show the form.
3. Submit empty fields, then try an invalid slug such as `Bad Slug`. Confirm inline
   validation appears and no POST is sent. Slugs such as `-team`, `team-`, `team--one`,
   and a single character must also be rejected.
4. Enter a unique name/slug, for example `Browser test team` and
   `browser-test-team-20261023`. Enable network throttling if desired and submit.
   The button should read Creating organisation and be disabled; repeated clicks
   or Enter presses must not create another in-flight POST.
5. Inspect the single `POST /api/organisations` locally: it must carry an Authorization
   bearer header and a JSON body containing only name and slug. Do not copy tokens
   into source, logs, tickets, or screenshots. Confirm a 201 response.
6. Confirm navigation to `/services`, a success message, and the new organisation
   selected in the header. Confirm its `GET /api/organisations/<new-id>/monitored-services`
   uses a bearer header and returns the new organisation's services. There should
   be no extra organisation-list request solely to finish creation.
7. In PostgreSQL, verify the membership using a read-only query:

   ```sql
   SELECT o.id, o.slug, u.identity_provider_subject, m.role
   FROM organisations o
   JOIN organisation_memberships m ON m.organisation_id = o.id
   JOIN users u ON u.id = m.user_id
   WHERE o.slug = 'browser-test-team-20261023';
   ```

   The creator's Keycloak subject must have OWNER. Reload and sign in again; the
   organisation and membership must remain. No membership should have existed
   merely from provisioning.
8. Use the header's Create Organisation link and submit the same slug. Confirm 409,
   a useful slug error, retained form values/selection, and no navigation. Change
   to a unique slug and retry; creation should succeed normally.
9. Sign in as an existing member with multiple organisations. Select another one,
   navigate between Dashboard, Services, and the creation page, and verify the
   selection persists. Create another organisation: existing entries must remain,
   with the new entry selected and its service requests using its own ID.
10. Use separate VIEWER/writer/non-member accounts to verify the existing permission
    behavior: VIEWER cannot mutate services, authorised writers can, and another
    organisation's IDs remain inaccessible. Creating a new organisation follows the
    existing authenticated-user policy; it does not grant roles in other organisations.
11. Check the protected creation endpoint without credentials:

    ```powershell
    curl.exe -i -X POST http://localhost:8080/api/organisations -H 'Content-Type: application/json' --data '{"name":"Team","slug":"team"}'
    ```

    It must return 401. With controlled development test requests, invalid backend
    values should return 400, and an INACTIVE local account should receive 403.
    The frontend must show the appropriate error or existing authentication recovery,
    without repeated redirects. Automated tests cover those status cases.
