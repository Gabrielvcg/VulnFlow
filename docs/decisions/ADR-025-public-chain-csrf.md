# Public filter-chain and CSRF-token release repair

The third, public SecurityFilterChain previously disabled CSRF without disabling
Spring Security's implicit logout. Application controller mappings alone therefore
could not establish that this chain had no state-changing behavior. Default logout
could consume a browser session at /logout outside the documented UI API.

The public chain now retains default CSRF and explicitly disables implicit logout.
Machine /api/v1 remains stateless and requires an explicit API-key header, while
session /api/ui/v1 retains its existing CSRF and documented POST /auth/logout.
Public static/health GET behavior remains available. No applied migration changes.

The XSRF cookie now uses CookieCsrfTokenRepository's HttpOnly default. The current
web client already obtains the token from /api/ui/v1/auth/csrf JSON and returns it
in X-XSRF-TOKEN; no document.cookie access or frontend edit is needed. This is
separate from the authentication session cookie, which was already HttpOnly.

UiSecurityIT adds regression cases for JSON-token login with HttpOnly/Secure CSRF
cookie, absence of implicit GET logout, fallback POST CSRF rejection and preserved
explicit UI logout. These run with disposable PostgreSQL in full CI. This change
addresses source behavior and avoids reclassifying the two security alerts merely
to bypass the main quality gate. It does not claim a full security audit or prove
unrelated XSS risks absent.

Main's remaining three gate alerts request Oracle VARCHAR2 for PostgreSQL CHAR(36)
Spring Session columns in already-applied V7. Those need precise owner review of
analyzer applicability, not edits to V7 or global SQL exclusions. No Sonar status,
rule, baseline or setting is changed by this repair.
