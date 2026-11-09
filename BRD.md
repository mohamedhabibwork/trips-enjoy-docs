# Trips Enjoy: Business Requirements Document (BRD)

## 1. Document control

| Field | Value |
|---|---|
| Product | Trips Enjoy (trips-enjoy.com) |
| Document type | Business Requirements Document |
| Version | 1.1 |
| Date | 9 November 2026 |
| Status | For review. Items marked [OPEN] need a decision. Items marked (placeholder) carry a working value that the named owner must confirm. |
| Owner | Product (Trips Enjoy) |
| Approvers | [OPEN: named approvers, D-314] |
| Companion documents | SRS.md v1.1 (software requirements), ERD.md (data model), user-flows/ (per-app flows), Tasks.md (delivery backlog), trips-enjoy-technical-architecture.md (architecture). |
| Rule for this document | No code, schemas or diagrams in code form. Those live in the SRS, the ERD and the architecture document. |

### 1.1 What changed in v1.1

| Change | Reason |
|---|---|
| Rebuilt the numbered BRD (BR, BRL, OD, RSK IDs) that SRS v1.0 traces to. The v1.0 file was not in the project, so IDs are reconstructed from the SRS traceability matrix and kept stable. | SRS v1.0 cites BRD.md v1.0, which was missing from the project. |
| Folded in the October 2026 product rules: country-based market values, active plan required for every non-traveler role, organization CRM and webhooks, B2B and B2C invoices, settlements between all parties, analytics by page, organization, trip, customer and provider, staff switching between organizations, lab tests at any time. | Product decisions recorded October 2026. |
| Added BR-MKT, BR-ANL, BR-ACC-07, BR-ADM-07, BR-ORG-08, BR-API-01 and BRL-25 to BRL-30. | New scope. |
| Applied decided values from the decision register (D-numbers) where the earlier text was open. | Decision register values as quoted in claude/design-value-conflicts.md (17 Oct 2026). |
| Fixed the lab-test deadline in BRL-16 (3 days, admin-adjustable 1 to 7). | SRS conflict C-05. |

## 2. Executive summary

### 2.1 Problem and opportunity

Organized group trips in Egypt and the Gulf (school trips, company outings, religious and tourism tours, events) are run on spreadsheets, WhatsApp groups and cash. Organizers cannot see which traveler is on which bus, suppliers (drivers and photographers) are booked informally with no record of the agreed price, and parents have no reliable way to know where their children are. Travelers pay without receipts, and organizers have no accounting per trip.

Trips Enjoy is a bilingual (Arabic and English), multi-tenant marketplace and operations platform for organized group trips. One trip record covers many vehicles, each with its own driver and moderator. Organizations sell trips and optional programs to travelers, hire independent drivers and photographers through negotiated deals, check every passenger onto the right vehicle, give parents a live view of their own children, and get per-trip accounting, invoices and settlements.

### 2.2 Business objectives and success measures

| ID | Objective | Measure | Target |
|---|---|---|---|
| OBJ-01 | Let organizations run multi-vehicle trips from one record | Pilot trip: three buses, about 120 passengers, run end to end in the platform | Passed in phase 3 |
| OBJ-02 | Know where every passenger is | Check-in or check-out event logged after physical boarding | Within 60 seconds of boarding, including after short connection loss |
| OBJ-03 | Admit suppliers and organizations quickly and safely | Median time from submitted application to decision | 2 working days or less |
| OBJ-04 | Make supplier prices transparent | Platform suppliers assigned through a finalized deal with both parties' acceptance | 100 percent |
| OBJ-05 | Build recurring revenue | Monthly recurring revenue from plans across all roles | [OPEN: target, D-71] |
| OBJ-06 | Keep money correct | Ledger entries balance; exports reconcile with the ledger | 100 percent; daily reconciliation with gateways |
| OBJ-07 | Give parents confidence | Parents on eligible plans see their linked children's vehicle, seat, check-in status and programs | Live during the trip |
| OBJ-08 | Serve each market correctly | Currency, payment methods and tax rules follow the country of the account | Every amount labeled with its currency |

## 3. Scope

### 3.1 In scope

- Role-specific registration, verification, sign-in and multi-role accounts.
- Applications and admin approval for organizations, platform drivers and platform photographers.
- Organization workspaces: employees and permissions, fleet and vehicle types, trips and programs, organization-owned drivers and photographers, coupons, plans, invoices, accounting, analytics, CRM and webhooks.
- Traveler discovery, booking, seats, programs, coupons, payment, cancellation, refunds, receipts and invoices.
- Deal negotiation between organizations and platform suppliers.
- Vehicle moderators: check-in, check-out, incidents, end-of-trip report.
- Live tracking for operations and for parents of linked children.
- Photo albums with three privacy modes.
- Driver lab-test workflow.
- Plans and subscriptions for every role, feature gates, dunning and overrides.
- Ledger, payouts, settlements, reconciliation and exports.
- Notifications on email, SMS, WhatsApp, push and in-app.
- Market configuration by country.
- Platform admin console.

### 3.2 Out of scope (this release)

- Ratings and reviews (OD-17, after phase 6).
- Hotel and flight inventory.
- In-app purchase of plans on mobile (plans are paid through the gateway's browser page).
- Biometric sign-in (no app lock or Face ID in phase 1).
- Machine-learning recommendations and fraud scoring.
- Saudi ZATCA e-invoicing integration until OD-10 is decided (phase 5 at the latest).

### 3.3 Delivery phases

| Phase | Theme | Definition of done |
|---|---|---|
| P1 Foundation | Accounts, organization applications and approval, tenancy | An organization can apply, be approved and sign in to an empty workspace. |
| P2 Single-vehicle pilot (Egypt) | Trip creation, traveler booking, payment in EGP, admin basics | One pilot organization sells one trip to real travelers with real payments. |
| P3 Operational depth | Multi-vehicle trips, programs, seat modes, moderator app, check-in | A three-bus trip with about 120 passengers runs end to end. |
| P4 Marketplace supply | Platform drivers and photographers, lab tests, negotiation, media | Organizations book platform suppliers through deals and deliver albums with all privacy modes. |
| P5 Monetization and Gulf launch | Plans for all roles, feature gates, coupons, full accounting, invoices; Saudi Arabia (SAR) and UAE (AED) | Recurring revenue is measurable, limits are enforced, and the Gulf markets are live once OD-10 is decided. |
| P6 Family and scale | Live tracking, parent tracker, analytics, public API | Parents see their children live; organizations get analytics and integrations. |

## 4. Stakeholders and roles

### 4.1 Roles

| Role | Description | Plan required to use the app |
|---|---|---|
| Visitor | Not signed in. Browses public trips and registration pages. | No |
| Traveler | Books and attends trips. Can be a parent or guardian. | No. Free by default; Explorer and VIP add features. |
| Parent or guardian | A traveler who links children to bookings and tracks them. | Explorer or VIP for child tracking |
| Organization owner | Registers the organization, pays the plan, runs the workspace. | Yes: Starter, Growth or Enterprise |
| Organization employee | Works inside one or more organizations within granted permissions. | Covered by the organization's plan |
| Organization-owned driver or photographer | Works for one organization only; invisible to others. | Covered by the organization's plan (OD-19, proposed) |
| Platform driver | Independent, admin-approved; hired through deals. | Yes: Basic, Professional or Premium |
| Platform photographer | Independent, admin-approved; hired through deals. | Yes: Basic, Studio or Pro |
| Vehicle moderator | Supervises one vehicle on one trip; checks passengers in and out. | [OPEN: OD-06] |
| Platform admin | Trips Enjoy staff. Roles: Super admin, Operations, Finance (D-49). | Staff |

### 4.2 Glossary

| Term | Meaning |
|---|---|
| Organization | A travel company, school, employer or event organizer that runs trips. Older documents say "Company". Each organization is an isolated tenant. |
| Traveler | A person who books and attends trips. Older documents say "User" or "Passenger". |
| Trip | One organized trip record. It may run many vehicles. |
| Program | An optional or required activity inside a trip, with its own pricing. |
| Vehicle type | A reusable seat layout with labeled seats. |
| Deal | A negotiated price between an organization and a platform supplier for one vehicle or trip. |
| Moderator | The person supervising one vehicle on one trip. |
| Market | The country whose currency, payment methods, tax rules and default time zone apply. |
| Settlement | The record of amounts owed between the platform, an organization and its suppliers for a trip, tracked until paid. |
| Ledger | The double-entry record of every financial event. |
| Hold | A temporary 15-minute reservation of a seat during checkout. |
| Grace period | 7 days after a failed renewal during which plan features stay active. |

### 4.3 Role journeys at a glance

The detailed flows are in user-flows/. This table states, for each role, what registration needs, how approval works and what the role can do once active.

| Role | Registration needs | Approval | Main actions once active |
|---|---|---|---|
| Traveler | Name, email and/or phone, password or one-time code, country, language. At least one of email or phone verified (D-20). | None. Active on verification, Free plan. | Browse and book trips, choose programs and seats where allowed, apply coupons, pay, cancel, get receipts and invoices, view albums, link children and track them (Explorer or VIP), upgrade plan, apply for a supplier role. |
| Organization | Owner name, email and phone (both verified), country, legal and trade names, commercial registration and tax numbers, registration documents, billing contact, chosen plan (no charge before approval). Web only. | Admin reviews documents and registry check (manual in P1). Approve, request changes (14 days) or reject with reason (re-apply after 30 days, appeal within 14 days). On approval: tenant created, Starter by default (D-24) or the chosen plan. | Run the workspace: employees and permissions, fleet, trips and programs, own drivers and photographers, deals with platform suppliers, seats, coupons, bookings, check-in oversight and live operations, invoices, accounting, settlements, analytics, CRM and webhooks, plan and billing. |
| Platform driver | Name, email and phone (both verified), country, national ID and driving license, own-vehicle details where used (plate, model, year, seats, registration, insurance valid 3+ months), consent to lab tests, initial lab result dated within 30 days, service regions, chosen plan. | Admin reviews documents, license, insurance and lab result. Same decision options as organizations. On approval: Basic plan by default (D-78) or the chosen plan. | Set availability, receive and negotiate deals, run assigned vehicles, share GPS during trips, see earnings and weekly payouts, create coupons for own bookings, complete lab tests when requested. |
| Platform photographer | Name, email and phone (both verified), country, ID, at least 8 portfolio photos, equipment, service areas, rates, chosen plan. | Admin reviews identity and portfolio. Same decision options. Basic plan by default (D-78) or the chosen plan. | Manage portfolio, rates and availability, negotiate deals, upload albums after the trip with plan-allowed privacy, watermark (Studio and Pro), see earnings, create coupons for own bookings. |
| Organization-owned driver or photographer | Invited by the organization by SMS or email (link valid 7 days, D-94). Name, phone, country; driver license for drivers. | No admin approval. Accepted on assignment by the organization (BRL-05). | Work only on that organization's trips. |
| Employee | Invited by email (link valid 7 days, D-286). Sets password and second factor on first sign-in. | Owner grants a permission set. | Act within granted permissions; switch between organizations that list their email. |
| Moderator | Phone required. Assigned by the organization to one vehicle on one trip. Staff code valid until 24 hours after trip end, one device at a time (D-107, D-108). | Assignment by the organization. | Manifest, check-in and check-out, incidents, end-of-trip report, for the assigned vehicle only. |
| Platform admin | Created by an existing admin (invite valid 72 hours, D-50). Authenticator set up on first sign-in. | Super admin creates. | Review applications and appeals, manage accounts, plans, coupons, templates and flags, request lab tests, handle disputes and refunds outside policy, run payouts, oversee settlements and reconciliation, read the audit log. |

## 5. Commercial model

### 5.1 Plans

No plan prices are set in any approved document. Prices come from the plan catalog per market and need Finance sign-off (OD-26). Trial length is a per-plan setting with no approved default (OD-28).

**Organization plans**

| Feature | Starter | Growth | Enterprise |
|---|---|---|---|
| Active trips per month (trips Published through Completed that were created this cycle, D-143) | 5 | 20 | Unlimited |
| Employees | 5 | 20 | Unlimited |
| Vehicles per trip | 2 | 10 | Unlimited |
| Programs per trip | 3 | 15 | Unlimited |
| Platform drivers and photographers | Own only | Own and platform | Own and platform |
| Seat self-selection | No | Yes | Yes |
| Child tracking | No | Yes | Yes |
| Coupons (active and paused, D-203) | 5 | 20 | Unlimited |
| Accounting | Basic | Advanced | Full with export |
| Analytics | No | Basic | Full |
| Invoices (B2B and B2C) | Yes (D-178) | Yes | Yes |
| CRM and webhooks | Yes, no plan gate (D-64); endpoint count per plan [OPEN: D-65] | Yes | Yes |
| Priority support | No | No | Yes |
| Custom branding | No | No | Yes |

**Platform driver plans**

| Feature | Basic | Professional | Premium |
|---|---|---|---|
| Active bookings per month | 3 | 10 | Unlimited |
| Profile visibility | Standard | Featured | Top placement |
| Deal negotiation | Yes | Yes | Yes |
| Coupons | 3 | 10 | Unlimited |
| History and reports | Basic | Full | Full with export |
| Search priority and verified badge | No | Yes | Yes |
| Earnings dashboard | Basic | Advanced | Advanced |
| Support | Standard | Priority | Dedicated |

**Photographer plans**

| Feature | Basic | Studio | Pro |
|---|---|---|---|
| Active bookings per month | 3 | 10 | Unlimited |
| Portfolio images | 10 | 50 | Unlimited |
| Albums per trip | 1 | 5 | Unlimited |
| Coupons | 3 | 10 | Unlimited |
| Album privacy | Public only | Public and shared link | Public, shared link and selected users |
| Profile visibility | Standard | Featured | Top placement |
| Verified badge and watermarking | No | Yes | Yes |
| Earnings and reports | Basic | Full | Full with export |
| Support | Standard | Priority | Dedicated |

**Traveler plans**

| Feature | Free | Explorer | VIP |
|---|---|---|---|
| Trip bookings | Unlimited | Unlimited | Unlimited |
| Program discount (programs only, not the base trip price) | None | 5 percent | 10 percent |
| Coupons | Yes | Yes | Yes |
| Early access to trips | No | No | Yes (window [OPEN: D-146]) |
| Priority seat selection | No | Yes | Yes (window [OPEN: D-316]) |
| Child tracking | No | Yes | Yes |
| Booking history and receipts | Basic (12 months, proposed) | Full | Full with export |
| VIP-only programs | No | No | Yes |
| Ad-free | No | Yes | Yes |
| Support | Standard | Priority | Dedicated |

**Plan lifecycle (all roles)**

- Billing monthly or annually. Annual discount placeholder 15 percent (D-16, OD-14).
- Upgrade takes effect at once, with the prorated difference charged. Downgrade takes effect at the end of the cycle with no proration. A scheduled downgrade can be cancelled (BRL-13).
- Failed renewal: retries on days 1, 3, 5 and 7 with an email at each attempt (D-09); 7-day grace period with features kept (BRL-14). What happens after grace is OD-29.
- Items above a lower limit after a downgrade keep working; no new items can be added until the count is under the limit (D-144).
- Monthly counters reset at 00:00 on the 1st in the organization's time zone (D-218).
- Admin overrides need a second admin and an end date, notify the owner, and revert at the end date (D-227, D-230).
- If the plan service is down, actions that involve money or limits fail closed; read-only screens stay available (D-224).

### 5.2 Revenue and costs

| Stream | Type | Rule |
|---|---|---|
| Plan subscriptions (all roles) | Revenue | Recurring, per market currency. |
| Booking commission | Revenue | Placeholder 10 percent of the base trip price, configurable per market and per deal type (D-15, OD-15). Provider screens show a ledger line without printing the rate until OD-15 is decided. |
| Deal commission | Revenue | Per deal type and market (OD-15). |
| Gateway fees | Cost | Recorded as a platform cost in the ledger. No rate set. |
| Supplier payouts | Pass-through | Weekly, Monday 06:00, through Stripe Connect Express, bank transfer or a local provider (D-28). |
| Refunds | Reversal | Reverse revenue, commission and coupon redemption. |

### 5.3 Markets

Market values are country-based (BRL-27).

| Market | Phase | Currency | Payment methods | Default time zone | Tax and invoicing |
|---|---|---|---|---|---|
| Egypt | P2 pilot | EGP | Stripe (international cards, Apple Pay, Google Pay), Paymob or Tap (local cards), Fawry (cash) | Cairo | Egyptian rules; VAT rate to be supplied by Finance (OD-27) |
| Saudi Arabia | P5 | SAR | Stripe, Paymob or Tap; local methods confirmed at launch (OD-11) | Riyadh | Saudi VAT; ZATCA e-invoicing scope decided before launch (OD-10) |
| United Arab Emirates | P5 | AED | Stripe, Paymob or Tap; confirmed at launch (OD-11) | Dubai | UAE VAT (OD-27) |

- Every amount shows its currency code. Western digits 0 to 9 in both languages (D-159).
- Amounts are kept in minor units and rounded half up per line; totals are the sum of the rounded lines (D-157).
- A booking is priced and paid in the currency of the organization's market. Cross-currency booking is out of scope.

## 6. Business requirements

Priority uses MoSCoW: M must, S should, C could. Phase is the delivery phase in section 3.3.

### 6.1 Accounts and access (AUTH)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-AUTH-01 | Each role registers through its own path (traveler, driver, photographer, organization). There is no combined onboarding form. | M | P1 |
| BR-AUTH-02 | Every registration form records the applicant's country. | M | P1 |
| BR-AUTH-03 | Accounts verify contact details by one-time code before acting: travelers verify at least one of email or phone; drivers, photographers and organization owners verify both (D-20). | M | P1 |
| BR-AUTH-04 | Sign-in strength follows the role: admins use password plus an authenticator; organization staff use password plus an SMS or authenticator code at every sign-in; travelers and suppliers use password or one-time code; moderators use a staff code. | M | P1 |
| BR-AUTH-05 | One person can hold several roles (for example traveler and approved photographer) and switch between them without a second account. | M | P1 |
| BR-AUTH-06 | Organization staff can switch between the organizations whose access list includes their email, and see no other organization. | M | P1 |
| BR-AUTH-07 | Admin can suspend and reinstate any account. Suspension stops access at once and keeps the data. | M | P1 |
| BR-AUTH-08 | A user can request account deletion, with a 30-day grace period during which signing in restores the account. | M | P5 |

### 6.2 Applications and approvals (ONB)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-ONB-01 | Organizations, platform drivers and platform photographers apply before they can act. Supplier actions stay blocked until approval. | M | P1 (organizations), P4 (suppliers) |
| BR-ONB-02 | All applications reach one admin queue with their documents. Admin can approve, request changes or reject; a rejection needs a reason. | M | P1 |
| BR-ONB-03 | The median decision time is 2 working days or less; overdue applications are flagged. | M | P1 |
| BR-ONB-04 | When changes are requested, the applicant has 14 days to respond, with a reminder on day 7 and one 7-day extension on request (D-235). Unanswered applications close. | M | P1 |
| BR-ONB-05 | A rejected applicant can appeal within 14 days of the notice (statement and up to 5 files, decided within 5 working days, one appeal per rejection, D-237) and can re-apply after 30 days. A closed application counts as rejected. The traveler account keeps working. | M | P1 |
| BR-ONB-06 | Photographer applications include at least 8 portfolio photos. | M | P4 |
| BR-ONB-07 | Drivers using their own vehicle give plate, model, year, seat count, registration and insurance valid for at least 3 more months. | M | P4 |
| BR-ONB-08 | Driver applications record consent to lab tests and include an initial lab result dated within the last 30 days. | M | P4 |
| BR-ONB-09 | Applicants are notified of every status change in their language. | M | P1 |

### 6.3 Organizations and employees (ORG)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-ORG-01 | Each approved organization gets an isolated workspace that no other organization can see. | M | P1 |
| BR-ORG-02 | The owner invites employees and grants each a permission set (trips, bookings, operations, accounting, coupons, employees, fleet, subscription, integrations, analytics). Employee count follows the plan. | M | P1 |
| BR-ORG-03 | Organizations can onboard their own drivers and photographers, visible only to them and accepted on assignment without admin approval. | M | P1 |
| BR-ORG-04 | Organizations register their vehicles (plate, model, year, seats, registration status). | M | P2 |
| BR-ORG-05 | Organizations choose whether travelers can pick their own seats, per trip or for the account, where the plan allows. | M | P3 |
| BR-ORG-06 | Organizations can connect a CRM and send trip, booking and check-in events to external apps through signed webhooks. | S | P6 |
| BR-ORG-07 | Organizations issue B2B and B2C invoices for their trips; corrections are made by credit note. | M | P5 |
| BR-ORG-08 | Enterprise organizations can apply custom branding to traveler-facing trip pages, emails and invoices. | C | P6 |

### 6.4 Trips, programs and fleet (TRIP, PRG, FLT)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-TRIP-01 | Organizations create trips through a guided wizard: basics, schedule and route, vehicles, staffing, programs, seating, pricing and capacity, publishing. | M | P2 |
| BR-TRIP-02 | A trip can be saved as a hidden draft, published publicly, or published to targeted travelers only. | M | P2 |
| BR-TRIP-03 | A trip can run several vehicles at once, each with one vehicle type, one driver and one moderator. The vehicle count follows the plan. | M | P3 |
| BR-TRIP-04 | Programs can be added, edited and removed until the trip starts. | M | P3 |
| BR-PRG-01 | Each program has one pricing model: free, per person, by quantity, per group, or tiered. Program count follows the plan. | M | P3 |
| BR-PRG-02 | A program can be optional, pre-required (auto-included), capacity-limited or VIP-only. | M | P3 |
| BR-PRG-03 | Travelers see a live booking total as they add or remove programs, including their plan's program discount. | M | P3 |
| BR-PRG-04 | Organizations see who chose each program, quantities and revenue. Report depth by plan is OD-25. | M | P3 |
| BR-FLT-01 | Vehicle types are reusable seat layouts with labeled seats. | M | P2 |

### 6.5 Staffing and deals (STF)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-STF-01 | Each vehicle is driven either by an organization-owned driver or by a platform driver hired through a deal. | M | P3 |
| BR-STF-02 | A trip's photographer is either organization-owned or a platform photographer hired through a deal. | M | P4 |
| BR-STF-03 | Organizations and platform suppliers negotiate the price of a deal: either side proposes, counters or rejects, with no round limit. An offer expires 72 hours after the last counter (BRL-26). Platform suppliers are available only on plans that include them. | M | P4 |
| BR-STF-04 | A platform supplier is assigned only after both parties accept the same terms. | M | P4 |
| BR-STF-05 | A trip is Ready only when every vehicle has a confirmed driver and moderator and every platform deal is finalized. | M | P3 |
| BR-STF-06 | Suppliers see trip, route, date and pay before they respond to a deal. | M | P4 |

### 6.6 Seats (SEAT)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-SEAT-01 | Seating is company-assigned by default. Self-selection is available only on Growth and Enterprise and only where the organization enables it. | M | P3 |
| BR-SEAT-02 | With self-selection on, travelers see a live seat map where taken seats show as unavailable immediately. | M | P3 |
| BR-SEAT-03 | Explorer and VIP travelers get a priority window for seat selection (length [OPEN: D-316]). | S | P3 |
| BR-SEAT-04 | A seat chosen at checkout is held for 15 minutes and released automatically if payment does not complete. | M | P3 |

### 6.7 Booking and payment (BKG)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-BKG-01 | A booking holds the passengers, chosen programs, seats where allowed, at most one coupon unless the coupon is stackable, and a payment method. | M | P2 |
| BR-BKG-02 | Travelers pay on the gateway's own page or SDK, with the methods of the organization's market. Card data never reaches Trips Enjoy. | M | P2 |
| BR-BKG-03 | A failed payment can be retried without losing the cart while the hold lasts, up to 3 attempts per hold (D-197). A retry after the hold expires re-checks the seats and starts a new hold only if they are free (D-191). | M | P2 |
| BR-BKG-04 | Travelers can cancel a booking and receive a refund under the cancellation policy (BRL-12). Requests after the cut-off go to the organization, which decides within 2 working days, then to admin (D-151). No cancellation after departure or after check-in (D-152, D-153). | M | P2 |
| BR-BKG-05 | Every confirmed booking yields a receipt and an invoice the traveler can view. A traveler who enters a tax number at checkout gets a B2B invoice (BRL-28). | M | P2 |

### 6.8 Coupons (CPN)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-CPN-01 | Admins, organizations, drivers and photographers create coupons that apply only inside their own scope. | M | P5 |
| BR-CPN-02 | Coupon use across entity boundaries is always rejected with a generic message that does not name the other entity (OD-20). | M | P5 |
| BR-CPN-03 | Coupons support fixed amount, percentage, program-specific, free program, first booking and minimum spend types, and are validated in real time at checkout. | M | P5 |
| BR-CPN-04 | Each redemption is recorded against its booking and reversed on refund. | M | P5 |
| BR-CPN-05 | Creators see coupon analytics; admin sees platform-wide analytics and abuse flags. Flags never block automatically and are reviewed within 2 working days (D-217). | M | P5 |

### 6.9 Check-in, moderators and tracking (CHK, MOD, TRK)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-CHK-01 | Moderators check each passenger in at boarding and out at each stop and at the destination. | M | P3 |
| BR-CHK-02 | A passenger can be checked in only to the vehicle they are assigned to. | M | P3 |
| BR-CHK-03 | Every check-in and check-out records time, moderator, vehicle and location where permitted, and reaches the platform within 60 seconds, including after short connection loss. | M | P3 |
| BR-CHK-04 | A moderator sees and acts on their assigned vehicle only. | M | P3 |
| BR-CHK-05 | Moderators, parents and staff can report incidents and child-safety concerns, which reach the organization's operations team at once. Incident categories: safety, medical, vehicle fault, lost traveler, conduct, other (D-331). | S | P3 |
| BR-MOD-01 | At the end of the trip each moderator submits a report with the final headcount; a mismatch needs a reason. | M | P3 |
| BR-TRK-01 | Operations staff see each vehicle's live location and which vehicle each passenger is in. Travelers never see the live vehicle position (BRL-29). | M | P6 |

### 6.10 Parents and children (PAR)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-PAR-01 | Parents see only their own linked children: vehicle, seat, check-in status and programs. Child data is never used for marketing, advertising or model training. | M | P6 |
| BR-PAR-02 | Parents link a child to a booking with a valid booking reference and a guardianship declaration (D-126). The link ends when the booking is cancelled or refunded. | M | P6 |
| BR-PAR-03 | Parents are notified when boarding is confirmed, the trip starts, a stop is reached and the trip ends. | M | P6 |
| BR-PAR-04 | Child tracking is available where the parent is on Explorer or VIP and the organization is on Growth or Enterprise with tracking enabled for the trip (OD-05). Live position is shown only while the trip is In Progress (D-317). | M | P6 |

### 6.11 Media and photographers (MED, PHO)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-MED-01 | Photographers upload albums after the trip is Completed, within 14 days (D-267). Album count per trip follows the plan. | M | P4 |
| BR-MED-02 | Albums use one of three privacy modes allowed by the photographer's plan: public to trip participants, shared link, or selected users. | M | P4 |
| BR-MED-03 | Studio and Pro photographers can watermark public copies (bottom right, inset 3 percent, 30 percent opacity, D-275). | S | P4 |
| BR-MED-04 | Signed-in travelers who took the trip view the albums they are allowed to see. Public albums allow download of the watermarked or medium copy; shared-link and selected-user albums do not offer download (D-264, D-266). | M | P4 |
| BR-PHO-01 | Photographers manage portfolio, rates and availability (dates and ranges, no weekly rules, D-89) and see earnings and payouts. | M | P4 |

### 6.12 Notifications (NTF)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-NTF-01 | Notifications go out on the channels each user enables per category: email, SMS, WhatsApp, push and in-app. | M | P1 |
| BR-NTF-02 | Notifications use the user's language, with right-to-left layout for Arabic. | M | P1 |
| BR-NTF-03 | SMS and push respect quiet hours 22:00 to 07:00 local time, except urgent messages; deferred messages go at 07:00, and trip-day events older than 2 hours are dropped (D-290). | S | P3 |

### 6.13 Plans and subscriptions (SUB)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-SUB-01 | Admin defines plans per role and market: limits, features, monthly and annual prices, annual discount and trial length. | M | P5 |
| BR-SUB-02 | Plan limits and features are enforced automatically. A blocked action shows an upgrade prompt. Every non-traveler role needs an active plan to use the app (BRL-02). | M | P5 |
| BR-SUB-03 | Failed renewals trigger dunning and a 7-day grace period before the account loses plan features. | M | P5 |
| BR-SUB-04 | Admin sees recurring revenue, churn, upgrades, downgrades and plan distribution. | M | P5 |
| BR-SUB-05 | Admin can grant, revoke or change a plan for one account, with a reason, a second admin's approval and an end date. | M | P5 |

### 6.14 Accounting, invoices and settlements (ACC)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-ACC-01 | Organizations see, per trip, base revenue, program revenue, coupon impact, supplier costs, commission and gateway fees. Depth follows the plan. | M | P5 |
| BR-ACC-02 | Admin sees system-wide finance: subscription revenue, commission, deal audit, coupon redemptions, disputes and payouts. | M | P5 |
| BR-ACC-03 | Suppliers see their earnings and receive weekly payouts. A negative balance is netted against the next payout in the same currency (D-173). | M | P4 |
| BR-ACC-04 | Exports in CSV and PDF follow the plan; admin system-wide exports are CSV only; export files expire after 7 days (D-249). | M | P5 |
| BR-ACC-05 | Every financial event is recorded in a double-entry ledger and reconciled daily with gateway records (D-29). | M | P2 |
| BR-ACC-06 | The platform records amounts owed between the platform, organizations and suppliers per trip and tracks each settlement to completion. A settlement opens when the trip is Completed and closes when every payout is made and no dispute is open (D-174). | M | P4 |
| BR-ACC-07 | Settlements and invoices cover every party pair: traveler to organization (B2C or B2B), organization to supplier (deal), platform to organization and supplier (commission and payout), and organization or supplier to platform (plan). | M | P5 |

### 6.15 Platform administration (ADM)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-ADM-01 | Admin reviews all applications and appeals from one queue. | M | P1 |
| BR-ADM-02 | Admin manages any account: suspend, reinstate, reset credentials, review deletion and data-copy requests. | M | P1 |
| BR-ADM-03 | Admin can require any approved driver to take a lab test at any time; the driver is paused until the result is accepted (BRL-16, BRL-17). Lab tests apply to drivers only (D-98). | M | P4 |
| BR-ADM-04 | Admin records disputes, decides outcomes and issues refunds, including refunds outside policy with a reason. | M | P3 |
| BR-ADM-05 | Every admin action and permission change is written to an audit log no one can edit. | M | P1 |
| BR-ADM-06 | Admin manages plans, coupons, notification templates, feature flags and market settings. | M | P1 |
| BR-ADM-07 | Disputes are acknowledged within 2 working days and resolved within 10 working days; information requests are answered within 5 working days. Types: booking, payment, refund, supplier conduct, organization conduct, other (D-32, D-254). | M | P3 |

### 6.16 Markets (MKT)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-MKT-01 | Admin configures each market: currency, enabled payment methods and gateways, tax rules and invoice format, default time zone, and plan prices. | M | P2 |
| BR-MKT-02 | An account's market is set from the country it registered with. An organization's market decides the currency, payment methods and tax rules of its trips. | M | P2 |
| BR-MKT-03 | Travelers see trips from every market, with prices in the trip's currency, and pay with that market's methods. | M | P5 |
| BR-MKT-04 | A market can be switched on or off; trips in a market that is off cannot be published. | S | P5 |

### 6.17 Analytics (ANL)

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| BR-ANL-01 | Organizations see analytics by trip (views, conversion, bookings, revenue, occupancy, programs), by page (trip page and organization page traffic), by customer (new and returning travelers, repeat rate) and by provider (deals, costs, on-time record). Depth follows the plan (Growth basic, Enterprise full). | M | P6 |
| BR-ANL-02 | Drivers and photographers see analytics for their own bookings and earnings at the depth of their plan. | S | P6 |
| BR-ANL-03 | Admin sees platform analytics by market, organization, role and plan. | M | P6 |
| BR-ANL-04 | Analytics offer 7, 30 and 90-day presets and a custom range, lag the source by no more than 5 minutes, and exports are ready within 15 minutes (D-177, D-281). | S | P6 |
| BR-API-01 | Organizations and partners can use a documented public API for trips and bookings. | C | P6 |

## 7. Business rules

| ID | Rule |
|---|---|
| BRL-01 | Trip records are tenant-isolated. Only the owning organization's staff, the platform admin, and the people on a booking within their own scope (traveler, linked parent, assigned moderator, driver or photographer) may see them. |
| BRL-02 | Every non-traveler role must hold an active plan to use the app. A traveler can always use the app for free. |
| BRL-03 | A coupon applies only inside its creator's scope: admin to any trip; organization to its own trips; driver or photographer to bookings where they are assigned. Cross-entity use is rejected. |
| BRL-04 | A platform driver or photographer is assigned only after a finalized deal accepted by both parties. |
| BRL-05 | An organization-owned driver or photographer is accepted on assignment and is never visible to other organizations. |
| BRL-06 | A rejected supplier or organization application can be re-submitted after 30 days. A closed application counts as rejected. The traveler account keeps working throughout. |
| BRL-07 | A trip cannot move to Ready until every vehicle has a driver and a moderator and every platform deal is finalized. |
| BRL-08 | A seat hold lasts 15 minutes and is released automatically when it expires. Exception: a Fawry cash voucher holds seats for the voucher's 48-hour window (D-31, D-41). |
| BRL-09 | A moderator can check a passenger in only to the vehicle assigned to that passenger on that trip. |
| BRL-10 | Parents see data only for children linked to their own bookings. |
| BRL-11 | A refund reverses the coupon redemption and frees the traveler's per-user limit. |
| BRL-12 | Cancellation refund depends on time before departure: 80 percent at 48 hours or more, 50 percent from 24 to under 48 hours, none under 24 hours (placeholder, D-07, OD-07). All fees are refundable until non-refundable fees are set (D-156). |
| BRL-13 | A plan upgrade takes effect at once with proration; a downgrade takes effect at the end of the cycle. |
| BRL-14 | A failed renewal starts a 7-day grace period with features kept. |
| BRL-15 | Account deletion becomes permanent after a 30-day grace period. Trip records are kept 5 years after deletion. Future bookings are cancelled and refunded; remaining earnings are paid on the next payout date. No booking or payment is allowed during the grace period. |
| BRL-16 | A lab test has a deadline of 3 days from request (admin may set 1 to 7 days, D-21). A missed deadline suspends the driver; a suspension longer than 30 days closes the driver account. A second failed result within 12 months closes the account (D-242). |
| BRL-17 | A driver whose lab test is requested is paused at once. Upcoming trips return to their organizers. Completed trips are paid; trips not yet completed are held (D-175). |
| BRL-18 | Every financial event (booking, refund, coupon, subscription charge, payout, deal cost, commission, invoice) is written to a double-entry ledger. |
| BRL-19 | Every admin action and every permission change is written to an immutable audit log. |
| BRL-20 | Verification codes expire after 5 minutes. After 5 wrong codes, verification pauses for 15 minutes. A resend does not reset the wrong-code count; after 3 failed deliveries the code goes by the other channel (D-58, D-59). |
| BRL-21 | Accounts lock for 15 minutes after 5 failed sign-in attempts (all roles, D-53). |
| BRL-22 | Web sessions end after 12 hours of use. Drafts are kept. |
| BRL-23 | Every registration form records the applicant's country. |
| BRL-24 | A trip's programs, seat mode or vehicles cannot be changed in a way that breaks a confirmed booking until the traveler is notified and offered a remedy. The traveler has 7 days to choose; then the case goes to admin (D-148). |
| BRL-25 | A trip is archived 30 days after Completed, and only once its settlement is closed (D-26). |
| BRL-26 | A deal offer expires 72 hours after the last counter-offer and the deal ends as Rejected with reason "expired" (D-14). |
| BRL-27 | Market values (currency, payment methods, tax and invoicing rules, default time zone) follow the country: the organization's country for its trips and invoices, and the account's country for its own plan. |
| BRL-28 | An invoice is B2B when the buyer gives a tax number, otherwise B2C (D-180). An issued invoice is never edited; a correction is a credit note that references it (D-181). |
| BRL-29 | Travelers never see a vehicle's live position. Only operations staff of the owning organization, the platform admin and eligible linked parents do, and parents only while the trip is In Progress (D-317). |
| BRL-30 | When an organization cancels a trip, every confirmed booking is refunded in full and coupon redemptions are reversed (D-150). A trip that is In Progress cannot be cancelled; it is handled as an incident (D-25, OD-22). |

## 8. Compliance, safety and legal

### 8.1 Child safety

- Minors travel on bookings made by a parent or guardian. A child is linked to a parent account only through a booking reference and a guardianship declaration.
- Child data is visible only to the linked parent, the owning organization's authorized staff, the assigned moderator for the passenger list, and the platform admin.
- Child data is never used for advertising, marketing or model training and never appears in any marketing export or audience.
- Child-safety reports create a case routed to a named escalation contact and procedure, which must exist before the phase 3 pilot ([OPEN: D-68]).
- Child trip history is kept 1 year.

### 8.2 Driver lab tests and background checks

- Lab tests (drug screening and/or medical fitness) and background checks run in a market only after a signed legal review for that market.
- Drivers consent at registration; consent records keep the type, time and text version.
- Tests are free at partner labs; partner labs send results to the platform directly.
- Identity documents and lab results are visible only to admins. Retention per market is set by the legal review.

### 8.3 Data protection

- Applicable regimes: Egypt's Personal Data Protection Law, Saudi PDPL, UAE data protection law, and GDPR where EU residents' data is processed.
- Users can request a copy of their data (delivered within 30 days of a verified request, D-162) and deletion (BRL-15).
- Marketing consent is separate from transactional messages.
- Card data is handled only by gateways.
- Hosting region and data residency per market are an engineering decision recorded in the SRS.

### 8.4 Tax and invoicing

- Invoices follow each market's tax rules. VAT rates per market are supplied by Finance (OD-27).
- ZATCA e-invoicing is out of scope for phases 1 to 4; its scope is decided before the Saudi launch (OD-10).
- If invoice numbering fails, no invoice is issued, the booking stays Confirmed, numbering retries 3 times and the case goes to finance review (D-179).

### 8.5 Audit and retention

| Data | Retention |
|---|---|
| Audit log (admin actions, permission changes) | 7 years |
| Bookings, payments, invoices, ledger and payouts | 7 years (OD-04, pending legal review) |
| Trip records after account deletion | 5 years |
| Check-in and check-out events | 1 year |
| Raw GPS points | 90 days |
| Child trip history | 1 year |
| Album download logs | 1 year |
| Identity documents and lab results | Per market legal review |

## 9. Assumptions, dependencies and constraints

### 9.1 Assumptions

- One product, one codebase, multi-tenant data.
- Organizations have a verifiable legal entity in their market.
- Travelers pay by card, wallet or local cash methods.
- Moderators have a smartphone with GPS and mobile data during the trip.
- Lab partners exist in each launch market and can send results to the platform.

### 9.2 Dependencies

- Payment gateways: Stripe (international cards, Apple Pay, Google Pay, Stripe Connect Express payouts), Paymob or Tap (local cards in MENA), Fawry (cash in Egypt).
- SMS and WhatsApp messaging, transactional email, push (FCM and APNS).
- Maps and routing.
- Lab partner network per market.
- Business registry and tax number checks (manual in phase 1; automated provider OD-16).

### 9.3 Constraints

- Arabic and English from the first release, with right-to-left layouts.
- Launch order: Egypt pilot in phase 2; Saudi Arabia and the UAE in phase 5 (D-69).
- Organizations register on the web only.
- Admin console and organization dashboard use separate routes (admin.trips-enjoy.com, business.trips-enjoy.com).
- The driver app is mobile; the photographer studio is responsive web in phase 1; the moderator app is an installable web app on phones; the parent tracker is a section of the traveler app (D-86, D-124).
- Maintenance windows fall between 01:00 and 03:00 Cairo time, no more than 4 hours per month, with 72 hours' notice (D-303).

## 10. Risks

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| RSK-01 | Data leaks between organizations | Loss of trust, legal exposure | Isolation at database, service and API layers; cross-tenant tests and security review each phase |
| RSK-02 | Low supplier supply in the pilot | Organizations cannot staff trips | Organization-owned suppliers first (P1 to P3); platform supply in P4 |
| RSK-03 | Lab tests or background checks unlawful in a market | Feature blocked, legal exposure | Legal review per market before use (section 8.2) |
| RSK-04 | Poor connectivity on buses | Missed check-ins, wrong headcounts | Offline queue with original timestamps; 60-second target after reconnect |
| RSK-05 | Coupon abuse | Revenue loss | Scope rules, per-user limits, abuse flags with admin review |
| RSK-06 | Payment gateway outage on a launch day | Lost bookings | Several gateways per market; queued callbacks; pending state instead of failure |
| RSK-07 | Plan prices not set before P5 | Monetization delayed | OD-26 owned by Finance with a P4 deadline |
| RSK-08 | Design canvas shows values that conflict with decisions (Saudi-first, SAR, ZATCA, monthly payouts) | Wrong build | Design value audit (claude/design-value-conflicts.md) resolved before P2 build |

## 11. Open decisions

Status: Decided (value applied in this BRD), Proposed (working value, needs sign-off), Open (no value).

| ID | Topic | Status | Value or note | Owner |
|---|---|---|---|---|
| OD-01 | Launch market order | Decided | Egypt pilot P2; Saudi Arabia and UAE P5 (D-69) | Product |
| OD-02 | Drafts when a session ends | Decided | Drafts kept (BRL-22) | Product |
| OD-03 | Second factor for organization staff | Decided | SMS or authenticator at every sign-in (D-48) | Product |
| OD-04 | Financial record retention | Proposed | 7 years, pending legal review | Legal |
| OD-05 | Child tracking eligibility | Proposed | Parent Explorer or VIP AND organization Growth or Enterprise with trip switch on | Product |
| OD-06 | Plan for moderators | Open | Proposed: covered by the organization's plan, like OD-19 | Product |
| OD-07 | Refund table and non-refundable fees | Proposed | 80 / 50 / 0 (BRL-12); no non-refundable fees until set | Operations |
| OD-08 | Priority seat window length | Open | D-316 | Product |
| OD-09 | VIP early-access window | Open | D-146 | Product |
| OD-10 | Saudi e-invoicing (ZATCA) scope | Open | Out of P1 to P4; decide before Saudi launch | Finance |
| OD-11 | Gateway per market | Proposed | Stripe, Paymob or Tap, Fawry (Egypt); Gulf confirmed at launch | Engineering |
| OD-12 | Admin account provisioning | Decided | Created by an existing admin; authenticator on first sign-in | Product |
| OD-13 | Negotiation limit and expiry | Decided | No round limit; 72 hours after last counter (BRL-26) | Product |
| OD-14 | Annual billing discount | Proposed | 15 percent (D-16) | Finance |
| OD-15 | Commission rates | Proposed | 10 percent of base trip price, per market and deal type (D-15) | Finance |
| OD-16 | Business registry check provider | Open | Manual in P1 | Operations |
| OD-17 | Ratings and reviews | Decided | Out of scope until after P6 | Product |
| OD-18 | CRM targets and webhook policy | Decided (policy), Open (CRM targets) | Signed, 5 retries over 24 hours, 7-day replay, no plan gate (D-62 to D-64) | Product |
| OD-19 | Plan for organization-owned drivers and photographers | Proposed | Covered by the organization's plan; allowance per plan open (D-97) | Product |
| OD-20 | Out-of-scope coupon message | Decided | Generic "not applicable to this booking" | Product |
| OD-21 | Default tier on approval | Decided | Organization Starter (D-24); driver and photographer Basic (D-78); plan chosen at registration with no charge before approval (D-77) | Finance |
| OD-22 | Cancelling a trip in progress | Decided | Not allowed; incident handling only (D-25) | Operations |
| OD-23 | Verification by role | Decided | Travelers one contact; suppliers and owners both (D-20) | Product |
| OD-24 | Lab-test deadline | Decided | 3 days default, admin 1 to 7 (D-21) | Operations |
| OD-25 | Plan gate on program enrollment report | Open | No gate until confirmed | Product |
| OD-26 | Plan prices per role and market | Open | None set in any approved doc | Finance |
| OD-27 | VAT rate per market | Open | Finance to supply, Egypt first | Finance |
| OD-28 | Trial lengths per plan | Open | Configurable; no default | Finance |
| OD-29 | What happens when grace ends | Open | Decision register D-08 says downgrade to the basic tier and block new bookings; BRL-02 requires an active plan for non-travelers, which implies a lockout. Product to reconcile. | Product |
| OD-30 | Webhook endpoints per plan | Open | D-65 | Product |
| OD-31 | Deal fee when cancelled after assignment | Open | D-278 | Finance |
| OD-32 | Changes-requested closure day when the extension is used | Open | Register says day 14; mobile decisions propose day 21 | Product |

## 12. Approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Product owner | [OPEN] | | |
| Finance | [OPEN] | | |
| Operations | [OPEN] | | |
| Legal | [OPEN] | | |
| Engineering lead | [OPEN] | | |
