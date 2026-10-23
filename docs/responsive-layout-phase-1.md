# Responsive frontend — Phase 1

## Inspection and implementation plan

The existing shell has a 240px sidebar, a fixed 64px header containing several
unconstrained controls, and one breakpoint that turns navigation into a horizontal
row. Fixed minimum grid widths, an unconstrained services table, and inconsistent
form/card styles contribute to cramped content and horizontal overflow. App.css
contains unused starter styles and is not imported; it will be left untouched.
No desktop/tablet/mobile mockup images were found in docs or frontend assets.

Phase 1 will:

1. Preserve all routes, authentication/provisioning startup, API calls, validation,
   query keys, and organisation selection behavior.
2. Use mobile-first shell CSS: modal navigation below 48rem, a rail from 48rem,
   and expanded/collapsible navigation from 75rem. Keep main content within a
   consistent maximum width.
3. Replace the cramped header with a wrapping organisation control group and an
   accessible account disclosure. Keep creation and logout accessible at every width.
4. Consolidate colors, typography, spacing, borders, shadows, touch targets, focus
   states, cards, buttons, fields, and loading/error/empty presentations.
5. Add narrow-screen containment for the existing table and safe wrapping for
   long names/URLs. Preserve Enabled/Disabled labels without inventing health data.
6. Test drawer/account keyboard behavior, focus restoration, route/organisation
   interactions, and existing frontend workflows; run lint, tests, and build.

This is a shell/foundations change. Individual page composition and new product
features remain outside Phase 1.

## Implemented layout and foundations

| Available width (default 16px root) | Navigation | Header |
| --- | --- | --- |
| Below 48rem / 768px | Hamburger opens a native modal drawer | Brand/account row, organisation/create row |
| 48rem–74.999rem / 768–1199px | 5.5rem navigation rail | Organisation and account share one row |
| From 75rem / 1200px | 15.5rem sidebar, collapsible to the rail | Spacious organisation/account row |

Account names and the create action's text appear from 64rem / 1024px. At
smaller widths the avatar and plus icon retain accessible names; opening the
account disclosure reveals the full name, email when available, and Logout.
Native organisation selection remains available at every breakpoint. Existing
organisation context and query/cache behavior are reused without additional API
requests. The content container has a 90rem maximum width and fluid padding.

`styles/tokens.css` centralizes surface/text/accent/state colors, system font
fallbacks, text sizes, spacing, radii, shadows, content width, and 44px touch
targets. `index.css` provides shared button, field, card, section, stack/grid,
status, and loading/empty/error foundations. Icons are small local SVGs, and the
brand and page-state components are reused. No font download or package is added.

The services table has its own labelled, keyboard-focusable scroll region on
narrow screens; this intentionally permits table scrolling without requiring
horizontal page scrolling. Long values wrap in cells and service detail values.
Existing Enabled/Disabled state labels are preserved; no health data is inferred.
Forms keep their existing fields, validation, mutation handlers, and independent
organisation Name/Slug behavior. Individual page composition remains for Phase 2.

The drawer uses `showModal()` for browser focus containment and an inert
background, with close, backdrop, Escape/cancel, route-selection, history, and
breakpoint handling. Closing restores trigger focus; changing to a rail focuses
main content. Body scrolling is restored on dismissal/unmount. The account
control is a disclosure with ordinary Tab navigation, outside-click dismissal,
Escape focus return, and the existing Keycloak logout action. Focus indicators,
a skip link, semantic navigation, active-route attributes, and reduced-motion
styles are included. Native dialog behavior follows the
[MDN dialog documentation](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/dialog)
and [W3C H102](https://www.w3.org/WAI/WCAG22/Techniques/html/H102).

## Files changed for this phase

Created:

- `frontend/src/styles/tokens.css`: shared design tokens.
- `frontend/src/components/Icon.tsx`: decorative SVG icons without dependencies.
- `frontend/src/components/Brand.tsx`: reusable home/brand link.
- `frontend/src/components/PageState.tsx`: reusable loading, empty, and error panel.
- `frontend/src/test/ResponsiveLayout.test.tsx`: 12 interaction tests.
- `docs/responsive-layout-phase-1.md`: inspection, design, results, and verification.

Modified:

- `frontend/src/index.css`: responsive shell and consolidated shared styling.
- `frontend/src/layouts/AppLayout.tsx`: drawer/collapse state, focus/scroll handling,
  skip link, content container, and organisation feedback.
- `frontend/src/components/Header.tsx`: responsive organisation controls and account
  disclosure, preserving organisation selection and logout behavior.
- `frontend/src/components/Sidebar.tsx`: rail/sidebar controls and modal drawer.
- `frontend/src/components/Navigation.tsx`: semantic icon links and drawer dismissal.
- `frontend/src/auth/AuthenticationBoundary.tsx`: shared visual feedback only;
  authentication and retry/logout behavior are retained.
- `frontend/src/pages/ServicesPage.tsx`: table scroll region and Enabled/Disabled badge.
- `frontend/src/pages/ServiceDetailsPage.tsx`: same Enabled/Disabled presentation.
- `frontend/src/test/AuthRoutes.test.tsx`: account disclosure assertions and logout interaction.
- `frontend/src/test/setup.ts`: jsdom media-query and dialog test shims.

Other pre-existing uncommitted files remain in the working tree. No backend,
authentication startup, API client, provider, dependency, or route-definition file
was changed in this phase.

## Validation results

Run from `C:\projects\OpsPilot\frontend`:

| Command | Final result |
| --- | --- |
| `npm.cmd run lint` | Passed |
| `npm.cmd run test:run` | Passed: 86 tests across 10 files |
| `npm.cmd run build` | Passed: TypeScript and Vite production build |

New tests cover drawer open/close, Escape/backdrop handling, initial/return focus,
scroll locking, navigation/active routes, breakpoint dismissal, desktop collapse,
account keyboard behavior/dismissal/logout, organisation context selection,
creation navigation, long accessible names, and loading/error/empty feedback.
Existing authentication/API, provisioning bootstrap, organisation onboarding,
creation validation, service registration/list/details/action tests also pass.

The initial run found two test failures caused by the jsdom dialog-focus shim and
a build failure caused by a widened fixture status type. Both were corrected;
the full checks above were rerun successfully.

The connected computer-use inventory had no browser, and opening the in-app
browser returned "Browser is not available: iab". No browser screenshots or visual
viewport checks were executed. jsdom does not lay out CSS, and its dialog shim
does not prove native focus trapping, background inertness, text zoom behavior, or
absence of overflow. Those remain browser verification items. No known visual
defect is asserted resolved by the component tests alone.

## Exact manual browser verification

1. Start the existing local PostgreSQL, Keycloak (`http://localhost:8082`), and
   backend (`http://localhost:8080`) using your normal project configuration.
   No Keycloak Admin Console changes are required by this phase.
2. In PowerShell run `cd C:\projects\OpsPilot\frontend` followed by
   `npm.cmd run dev -- --host 127.0.0.1`. Open `http://localhost:5173` and complete
   the real Keycloak login. Use the existing redirect host `localhost`.
3. Open browser DevTools responsive mode and test **320, 358, 390, 480, 600, 768,
   779, 977, 1024, 1280, 1440, and 1920px**. Inspect Dashboard, Services,
   `/services/new`, a real `/services/:id`, and `/organisations/new` at each width.
   Pay special attention to 358, 779, and 977px and both sides of the breakpoints.
4. Below 768px, open the hamburger. Check initial close-button focus, Tab and
   Shift+Tab containment, background inertness, scroll locking, Escape, close
   button, backdrop dismissal, and focus return. Open again and navigate to
   Services. Resize an open drawer past 768px; it should close and focus content.
5. Check the rail at 779/977px and expanded/collapsed sidebar at 1280/1440px.
   Verify active routes and visible keyboard focus. Tab from the first page focus
   stop and activate Skip to content. Confirm no hidden navigation controls can
   receive keyboard focus.
6. Switch between existing organisations and confirm that the selector and real
   services match. Use the plus/Create Organisation control, create a unique
   organisation, and confirm selection, success feedback, and `/services`
   navigation. With a user who has no memberships, verify the welcome state and
   that creation stays reachable. Name and Slug must remain independent.
7. Use permitted long real organisation/user/service names and long service URL
   values. Check wrapping, full account details in the disclosure, usable select
   controls, and unclipped buttons. The table may scroll within its own region.
   In the console, with the drawer closed, check
   `document.documentElement.scrollWidth <= document.documentElement.clientWidth`.
   Repeat at 200% browser zoom and with larger text. This phase does not alter
   the existing dashboard placeholder metrics.
8. Open the account disclosure using Enter/Space, Tab to Logout, and test Escape,
   outside clicks, and focus leaving the control. Then activate Logout and verify
   the real Keycloak logout; protected routes should require sign-in again.
9. Verify real service registration/details/enable/disable workflows. Status must
   say Enabled/Disabled, never Healthy/Online solely from the enabled flag.
10. Enable reduced motion in browser/OS settings and repeat drawer/account use.
    Check short landscape viewports, vertical scrolling, focus visibility, and
    legibility. Record any page-level composition work for Phase 2.
