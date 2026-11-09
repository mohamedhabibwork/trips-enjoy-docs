# 08. Shared flows

Flows used on every surface.

### UF-SHR-01. Sign up chooser
/register shows Traveler, Driver, Photographer and Organization. Each opens its own form; Organization is web only (the app hands off to the browser).
Rules: FR-AUTH-01.

### UF-SHR-02. One-time code
Code by SMS, WhatsApp or email; 6 digits; expires in 5 minutes; resend after a 45 to 60 second countdown; 5 wrong codes pause 15 minutes; resend does not reset the count; after 3 failed deliveries the other channel is offered.
Rules: FR-AUTH-03, BRL-20.

### UF-SHR-03. Sign in

| Role | Method |
|---|---|
| Traveler, driver, photographer | Password, or one-time code |
| Organization staff | Password, then SMS or authenticator code every time |
| Admin | Password, then authenticator code |
| Moderator | Phone and staff code |

5 failed attempts lock the account 15 minutes. Web sessions end after 12 hours; drafts are kept.
Rules: FR-AUTH-04, FR-AUTH-05, FR-AUTH-10.

### UF-SHR-04. Password reset
Request by email or phone; code or link; new password (12+ characters, breached check); all other sessions end.

### UF-SHR-05. Role and organization switcher
Account menu lists held roles (traveler, driver, photographer, staff) and, for staff, organizations that list the user's email. Switching changes the workspace; data from other roles and organizations is never mixed.
Rules: FR-AUTH-06, FR-AUTH-07.

### UF-SHR-06. Plan-limit prompt
When a limit or feature blocks an action: what was blocked, current usage and limit, the next plan that allows it, and Upgrade. If the plan service is down, a "try again shortly" message instead (fail closed).
Rules: FR-SUB-02, FR-SUB-11.

### UF-SHR-07. Grace banner
After a failed renewal: banner with retry dates and Update payment; features stay for 7 days; then the rule in OD-29 applies.
Rules: FR-SUB-03, FR-SUB-08.

### UF-SHR-08. Application status, changes, appeal
Status page for any application: state, due dates, requested items, extension request (once), appeal form (14 days, statement, up to 5 files), re-apply date.
Rules: FR-ONB-04, FR-ONB-05.

### UF-SHR-09. Account deletion
1. Request in privacy settings; confirm by code; see what happens (future bookings cancelled and refunded, earnings paid on the next payout date, trip records kept 5 years).
2. Grace period 30 days: no bookings or payments; signing in shows "restore account".
3. Day 31: permanent. Organization owners cannot request it while a trip is In Progress.
Rules: FR-AUTH-09, BRL-15.

### UF-SHR-10. Suspended account
Sign-in shows the suspension message and a support contact; no data is shown.
Rules: FR-AUTH-08.

### UF-SHR-11. Notification centre
In-app list of notifications by category with unread state; tapping opens the target, or a fallback if the target no longer exists.
Rules: FR-NTF-01.

### UF-SHR-12. Errors and service states

| State | Shown when | Message intent |
|---|---|---|
| 401 | Session ended | Sign in again; return to the same place |
| 403 | Missing permission | You do not have access; ask your organization owner |
| 404 | Not found or another tenant's data | Not found |
| 402 | Plan required | UF-SHR-06 |
| 429 | Rate limited | Try again in N seconds |
| 500 | Server error | Something went wrong; reference ID |
| 503 | Dependency down | Service temporarily unavailable |
| Maintenance | Planned window | 01:00 to 03:00 Cairo, announced 72 hours ahead |
| Offline | No connection | Banner; queued actions where supported |
| Update required | App below supported version | Update to continue |

### UF-SHR-13. Language and layout
Language switch on every screen; Arabic mirrors the layout; Western digits in both languages; amounts with currency code.
Rules: UI-01, UI-02, NFR-I18N-04.

### UF-SHR-14. Confirmation step
Destructive or money-moving actions (cancel, refund, revoke, suspend, publish, finalize deal) use a confirmation that names the effect, for example "Cancel trip: 40 bookings will be refunded in full, 12,400 EGP".
Rules: UI-08.
