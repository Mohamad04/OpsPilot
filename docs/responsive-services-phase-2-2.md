# Services List and Create Service — Phase 2.2

## Scope and implementation

Inspection confirmed that the existing organisation-scoped list API returns the
entire service array. Search, environment/type/state filtering, and pagination
therefore run locally without adding endpoints or requests. The existing
`["monitored-services", organisationId]` query key is preserved.

The registry includes service names linked to existing detail routes, base URLs,
readable environment badges, ownership, and Enabled/Disabled configuration state.
Search is case insensitive and covers name, owner, URL, protocol, and environment.
Filters combine; Clear filters resets them and pagination. At most 25 services
are rendered per page. Empty registry, no search matches, initial loading/error,
and background refresh failures have distinct feedback. Cached services remain
available during a failed refresh, with a retry action.

The same semantic table becomes compact cards below 42rem of **available page
width**, retaining table/header/cell roles and a single set of service links in
the DOM. Wider layouts display the conventional five-column registry. Header
labels remain available to assistive technology in the compact layout. Values
wrap, and there is no forced table minimum width or horizontal scroll container.

The registration form retains Name, Owner, Service type, Environment, Base URL,
and Health Endpoint. All six are required by the existing, unchanged Zod schema.
HTTP and Development remain the initial selections. Service information and
endpoints are grouped into cards, displayed side by side from 48rem of available
page width. Fields have native required attributes, associated labels,
`aria-invalid`, and contextual `aria-describedby` feedback. Short hints appear
on focus; errors appear after interaction/blur or invalid submission and clear
as values become valid. Space is reserved for short feedback.

Submission disables fields and the submit/cancel controls, announces progress,
and prevents duplicate requests. Errors preserve entries and expose the existing
API error message, including authentication/permission errors. Success invalidates
the organisation's existing list query and navigates to `/services/:id`.
Changing organisation resets the draft and registry filters. A pending request
for the previous organisation can invalidate only its own cache and cannot
navigate the newly selected organisation.

Both pages reuse the existing tokens, card/button foundations, Icon, and
PageState. Enabled does not imply Healthy or Online; neither page manufactures
monitoring results. Dashboard, Service Details, shell, authentication, API
clients/contracts, validation schema, dependencies, and backend are unchanged.

## Files

Modified:

- `frontend/src/pages/ServicesPage.tsx`
- `frontend/src/pages/AddServicePage.tsx`
- `frontend/src/test/ServicesPage.test.tsx`
- `frontend/src/test/AddServicePage.test.tsx`

Created:

- `frontend/src/styles/services.css`: styles scoped to these pages, using existing tokens.
- `docs/responsive-services-phase-2-2.md`: implementation and verification notes.

## Automated validation

Commands run from `C:\projects\OpsPilot\frontend`:

| Command | Result |
| --- | --- |
| `npm.cmd run lint` | Passed |
| `npm.cmd run test:run` | Passed: 146 tests across 10 files |
| `npm.cmd run build` | Passed: TypeScript and Vite production build |

The registry and registration suites contain 41 cases in total. They cover
local search/filter combinations, reset/no-match behavior, pagination, navigation,
query retries and cached error states, long values, tenant changes, field
grouping/required defaults, contextual validation, unchanged length/URL/path
constraints, submission/cancellation, duplicate prevention, error retention,
cache invalidation, redirect, and an old tenant's in-flight creation.

The first lint run flagged passing a ref-using submission callback into a
function during rendering. Calling React Hook Form's handler from the submit
event fixed that rule without suppressing lint. The subsequent checks passed.

## Browser verification status

**The viewport matrix has not been visually verified.** Component tests use
jsdom and cannot establish layout, browser text zoom, or absence of overflow.

The computer-use skill found Brave and created a separate empty test window.
Automatic approval review rejected accessibility text reads because the browser
could contain unrelated private content, particularly after detected user input.
Attempts to use screenshot-only observation encountered a minimized window;
activation was blocked by the tool's user-input guard. No screenshot of either
application page was captured, and no browser-level integration check passed.
The existing development server already occupied port 5173; no second server was
started. Further browser checks need a dedicated OpsPilot window open and idle,
with approval for task-specific inspection.

## Manual browser checklist

1. Start the project's existing backend, PostgreSQL, Keycloak, and frontend as
   usual. If needed, run `npm.cmd run dev` from `frontend`. Open
   `http://localhost:5173`, sign in normally, and select an organisation.
   No Keycloak or backend configuration change is needed for this phase.
2. Test both `/services` and `/services/new` at **320, 390, 768, 977, 1280,
   and 1440px** using responsive DevTools. These are required verification
   targets, not completed visual tests.

| Width | Registry presentation expected with the standard shell | Registration expectation |
| --- | --- | --- |
| 320 / 390px | Compact service cards; vertically stacked filters | One card column; full-width actions |
| 768px | Compact cards because the rail reduces available width | One card column |
| 977px | Table when available content reaches 42rem | Two card columns when content reaches 48rem |
| 1280 / 1440px | Table with expanded or collapsed sidebar | Two card columns |

3. Confirm no page-level horizontal scrolling or clipped controls, including
   long real names/owners/URLs, 200% browser zoom, short viewports, and sidebar
   collapse. Verify labels, focus outlines, touch targets, and keyboard navigation.
4. Search by mixed-case name, owner, or URL; combine environment, type, and status
   filters; clear them; search for a missing value. Confirm the no-match state
   differs from an organisation with no services. Check that filtering causes no
   new requests in DevTools Network.
5. With more than 25 real services, use Next/Previous, then filter from a later
   page. Confirm page resets, counts, and service links are correct in both compact
   and table layouts. Switch organisation and verify filters reset and only that
   organisation's services appear.
6. On registration, focus and blur fields to check contextual hints and errors.
   Submit an empty form; verify focus moves to Name. Try an invalid URL, a path
   without `/`, and valid values. Verify all fields remain required and protocol
   choices are unchanged. Name and Owner are independent inputs.
7. Register a permitted real service. Confirm one authenticated POST, the same
   request fields, list invalidation, and navigation to its details. Check slow
   submission feedback and that double submission is blocked. Cancel before
   submission and confirm navigation back to Services.
8. With a user lacking permission, verify the backend still rejects registration
   and the form preserves entries with an error. Do not infer service health
   from an enabled flag. Check the empty-registry Add service entry point and the
   existing organisation-creation success message.
