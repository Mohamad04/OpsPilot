# Keycloak authentication milestone

Automatic provisioning was added in the subsequent milestone. See
[user-provisioning.md](user-provisioning.md) for the current account policy,
additional changed files, verification results, and first-login checks.

The existing application now requires Keycloak login before React mounts. All five
routes, provider order, organisation selection, query keys, and mutation invalidation
remain in place. Authentication uses the installed `keycloak-js` adapter with the
Authorization Code flow and PKCE S256. There is no custom credential form or token
persistence. The adapter may persist temporary OIDC callback state/PKCE material;
access and refresh tokens remain in memory.

## Files changed

| File | Change |
| --- | --- |
| `frontend/src/auth/auth.ts` (new) | One adapter and initialization promise per page; hosted login, identity/status subscriptions, shared refresh, session termination, and Keycloak logout. |
| `frontend/src/auth/useAuth.ts` (new) | React subscription to authentication state. |
| `frontend/src/auth/AuthenticationBoundary.tsx` (new) | Hides/unmounts protected content when the session ends; provides useful errors and explicit recovery actions. |
| `frontend/src/main.tsx` | Awaits authentication before mounting; retains StrictMode, QueryClientProvider, OrganisationProvider, BrowserRouter, and App. |
| `frontend/src/api/client.ts` (new) | Refreshes tokens with a 30-second validity margin, preserves request options/headers, attaches bearer credentials only to the configured API, and handles 401/403. |
| `frontend/src/api/organisationApi.ts` | Routes all three existing operations through the shared client. |
| `frontend/src/api/monitoredServiceApi.ts` | Routes all five existing operations through the shared client. |
| `frontend/src/components/Header.tsx` | Displays the authenticated display name/username and Logout button while retaining the organisation selector. |
| `frontend/.env.example` (new) | Documents the existing non-secret development configuration. The existing `.env` already had these values and was not modified. |
| `backend/src/main/resources/application.properties` | Adds Spring Boot audience validation, defaulting to `opspilot-backend`, configurable with `KEYCLOAK_API_AUDIENCE`. |
| `frontend/src/test/auth.test.ts` (new) | Initialization, hosted-login invocation/callback, configuration failure, refresh concurrency/failure, expiry/session notification, and logout tests using a mocked adapter. |
| `frontend/src/test/apiClient.test.ts` (new) | Every existing API operation uses bearer authentication; verifies preserved payload/options, 401/403, failed refresh, existing errors, and URL restrictions. |
| `frontend/src/test/AuthRoutes.test.tsx` (new) | All existing route mappings with page stubs, real layout/header, Logout invocation, and removal of protected content on session loss under StrictMode. |
| `backend/src/test/java/com/opspilot/backend/web/JwtAuthenticationTest.java` (new) | Real JWT decoding against a local test JWKS server; verifies signature, issuer, audience, expiry, and CORS. |
| `docs/keycloak-authentication.md` (new) | Setup, provisioning gap, verification results, and local test procedure. |

The pre-existing uncommitted edits to `frontend/package.json` and
`frontend/package-lock.json` were preserved. No dependencies were installed.
`App.tsx`, `AppLayout.tsx`, Flyway migrations, membership services, and the RBAC model
were not modified.

## Authentication behavior

- Initialization runs outside React and is cached in a module-level promise.
  StrictMode cannot initialize a second adapter.
- `init()` handles the authorization callback before BrowserRouter sees its URL.
  When it returns unauthenticated, an explicit adapter `login()` redirects to
  Keycloak and returns to the original route/query. This deliberately avoids the
  adapter's `login-required` option, which automatically logs in on `clearToken()`.
  The application still always requires login before mounting.
- Concurrent API requests share token refresh work. Expiry also triggers refresh.
  A failed refresh removes access to the application and requires explicit sign-in.
- A backend 401 removes the session and displays a recovery message. It does not
  automatically replay requests or redirect repeatedly. A 403 reports insufficient
  organisation permission without signing out. Other status/network errors retain
  the existing API-specific behavior. TanStack Query caching/invalidation is unchanged.
- Logout immediately hides protected content and clears adapter tokens. The adapter
  submits a POST to Keycloak's end-session endpoint, keeping its ID-token hint out
  of the URL, then returns to `/`. The next initialization requires login again.
  Logout in another tab is detected by the adapter's session iframe when supported,
  or on refresh otherwise. Existing JWTs remain valid at the API until expiry;
  this is the existing stateless JWT model, so use short access-token lifetimes.
- Recovery buttons reload the page for a fresh hosted login, or retry provider logout.
  A logout retry after tokens were cleared uses GET without an ID-token hint;
  Keycloak can show its hosted logout confirmation.
  A reload can reuse a still-active Keycloak SSO session; it does not bypass Keycloak.

## Existing backend security

`SecurityConfig` requires authentication for `/api/**`, uses the OAuth2 JWT resource
server, and retains stateless sessions. The configured issuer is
`http://localhost:8082/realms/opspilot` (overridable with `KEYCLOAK_ISSUER_URI`).
Spring verifies signatures against the issuer's JWKS, issuer, expiry/not-before,
and now the API audience. No frontend secret is required.

`WebConfig` already permits `http://localhost:5173` through `FRONTEND_URL`, the
existing API methods, and `Authorization`/`Content-Type` headers. It needed no edit.
Security filter failures keep their generic JSON 401/403 responses.

Organisation access continues to come from local database memberships. All four
roles can read their own organisation's services. OWNER, ADMIN, and ENGINEER can
write; VIEWER cannot. Non-members and cross-organisation service IDs continue to
be rejected using the existing 404 behavior. Keycloak realm/client roles are not
converted into organisation memberships.

## Keycloak Admin Console configuration

Read-only inspection of the running Keycloak instance on 2026-10-22 confirmed:

- Realm `opspilot`, client `opspilot-frontend`, public client, Standard Flow enabled.
- PKCE S256 configured, Direct Access Grants and Implicit Flow disabled.
- Redirect URI `http://localhost:5173/*`, web origin `http://localhost:5173`.
- Post-logout redirect URI `http://localhost:5173/`.
- Two existing Postman callback URIs are also configured; this task left them intact.

Complete this required audience setup before using the protected API:

1. Open `http://localhost:8082/admin/`, select realm **opspilot**.
2. Go to **Clients → opspilot-frontend → Client scopes** and open its dedicated scope
   (`opspilot-frontend-dedicated`).
3. Under **Mappers**, choose **Configure a new mapper → Audience** (or **Add mapper →
   By configuration → Audience**, depending on the console).
4. Name it `opspilot-backend-audience` and set **Included Custom Audience** to
   `opspilot-backend`. Enable **Add to access token**; keep **Add to ID token** off.
   Save. A separate backend client is not needed for this custom audience.
5. Obtain a fresh login/access token. Its `aud` must include `opspilot-backend`.
   If you choose another API audience, set `KEYCLOAK_API_AUDIENCE` on the backend
   to exactly that value. Do not use the SPA's client ID as the API audience.

No Keycloak server configuration was changed by this task. In another environment,
configure Client authentication **Off**, Standard Flow **On**, PKCE method **S256**,
and the frontend's exact origins/redirects. Do not put client secrets or admin
credentials into any `VITE_*` variable.

## Local user provisioning

The frontend now calls authenticated `POST /api/me/provision` before mounting the
organisation provider. It creates an ACTIVE local user from the verified JWT
subject and verified email, or returns the existing ACTIVE subject mapping.
Email collisions are rejected without linking accounts; no memberships are created.
Inactive local users are rejected by provisioning and the shared authenticated-user
resolver. Manual database insertion is no longer required.

See [user-provisioning.md](user-provisioning.md) for email-verification requirements,
transaction/concurrency handling, error responses, and first/subsequent-login checks.

## Automated verification

The counts below describe the original authentication milestone. Current provisioning
milestone results are recorded in [user-provisioning.md](user-provisioning.md).

Executed from `frontend`:

```powershell
npm.cmd run test:run
npm.cmd run lint
npm.cmd run build
```

Frontend tests passed: **43 tests across 7 files**; lint and production build also
passed. These cover adapter/client/UI behavior with mocks, not a live browser.

Executed from `backend`, using the already cached Maven distribution and dependencies:

```powershell
$maven = 'C:/Users/moham/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn.cmd'
& $maven '-Dmaven.repo.local=C:/Users/moham/.m2/repository' '-Duser.home=C:/Users/moham' -o test
```

The complete backend suite passed: **204 tests, zero failures/errors/skips**.
This includes PostgreSQL Testcontainers, real JWT decoding, missing/invalid bearer
rejection, and existing cross-organisation/VIEWER/write/member-administration tests.

Initial attempts encountered an environment-specific wrapper startup error, an
incorrect default Maven home, a transient compiler JAR access error, and restricted
Docker pipe access. Explicit existing Maven paths and a Docker-enabled rerun resolved
them; the final full suite succeeded. No build or security settings were weakened.

Read-only HTTP checks confirmed realm discovery and S256 availability. No interactive
browser was available to the agent. Hosted-login return, browser token refresh,
and hosted logout must be checked manually using the procedure below.

## Exact local test procedure

1. Start the existing services, if needed:

   ```powershell
   Set-Location C:\projects\OpsPilot\infrastructure
   docker compose up -d postgres keycloak
   ```

2. Complete the audience mapper above and ensure new Keycloak users have verified
   email addresses as described in the provisioning guide. Local users are created
   automatically. To test permissions, prepare existing organisation memberships for
   separate VIEWER and ENGINEER/ADMIN/OWNER test users using the existing authorized
   membership process. Do not assign roles merely because users authenticate.

3. In a backend terminal, load the existing infrastructure database configuration
   into the process environment and start Spring Boot (PowerShell):

   ```powershell
   Set-Location C:\projects\OpsPilot
   $localSettings = Get-Content -Raw infrastructure/.env | ConvertFrom-StringData
   $env:DB_URL = 'jdbc:postgresql://localhost:5432/' + $localSettings.POSTGRES_DB
   $env:DB_USERNAME = $localSettings.POSTGRES_USER
   $env:DB_PASSWORD = $localSettings.POSTGRES_PASSWORD
   $env:FRONTEND_URL = 'http://localhost:5173'
   $env:KEYCLOAK_ISSUER_URI = 'http://localhost:8082/realms/opspilot'
   $env:KEYCLOAK_API_AUDIENCE = 'opspilot-backend'
   $localSettings = $null
   Set-Location backend
   .\mvnw.cmd spring-boot:run
   ```

   If the wrapper encounters the same startup problem, use the cached `$maven`
   executable above with the same `maven.repo.local`/`user.home` arguments and
   `-o spring-boot:run`. The backend must be reachable on port 8080.

4. In another terminal, confirm `frontend/.env` contains the four non-secret values
   in `.env.example`, preserving other settings. Then run:

   ```powershell
   Set-Location C:\projects\OpsPilot\frontend
   npm.cmd run dev
   ```

5. Open a private browser window at **http://localhost:5173/services**. Use
   `localhost`, not `127.0.0.1`, because Keycloak permits the configured exact origin.
   Confirm you reach Keycloak's hosted login. Its authorization request should use
   `response_type=code` and `code_challenge_method=S256`. Sign in and confirm the
   browser returns to `/services` and the header displays your name.

6. Visit `/`, `/services`, `/services/new`, `/services/<existing-service-id>`, and
   `/organisations/new`. Select an organisation and exercise existing service
   creation/read/enable/disable operations with an authorized writer. A new user
   without memberships should first use Create organisation.

7. In browser DevTools Network, inspect API calls locally: each must have an
   Authorization bearer header, with unchanged methods and JSON bodies. Do not
   copy tokens into logs, tickets, or source. In Application/Storage, confirm there
   are no stored access or refresh tokens. Temporary adapter OIDC callback entries
   are not access/refresh-token persistence.

8. Keep Network open, wait past the realm's access-token lifespan, then make another
   API request. Confirm a successful refresh-token grant and a successful API call.
   Concurrent queries should share refresh work. End the user's Keycloak session
   from the Admin Console, then wait for iframe detection or refresh; protected
   content should disappear with an explicit sign-in action and no redirect loop.

9. Sign in again and click **Logout**. Confirm Keycloak receives the end-session
   POST, returns to OpsPilot, and the hosted login is required. Browser Back or
   revisiting a protected URL must not reveal the protected application without
   a new Keycloak authentication. External identity-provider SSO, if configured,
   may authenticate without prompting for credentials again.

10. Check backend rejection without credentials:

    ```powershell
    curl.exe -i http://localhost:8080/api/organisations
    curl.exe -i -H 'Authorization: Bearer invalid' http://localhost:8080/api/organisations
    ```

    Both should return 401. With deliberately obtained test tokens, wrong issuer,
    wrong/missing API audience, expired tokens, and invalid signatures must also
    return 401. Automated JWT tests already exercise these cases without Keycloak.

11. Sign in as VIEWER: list/detail requests should succeed for its memberships,
    while create/enable/disable return 403 without triggering login. Sign in as a
    writer: those mutations should succeed. Using another organisation's ID or
    service ID should retain the existing 404 rejection. A provisioned non-member
    should not see another organisation. A Keycloak user without a local record
    should be provisioned with no membership; a missing/unverified email or account
    conflict must block application startup without granting any role.

References: [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter),
[Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
