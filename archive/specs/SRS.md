# Trips Enjoy: Software Requirements Specification (SRS)

## 1. Document control

| Field | Value |
|---|---|
| Product | Trips Enjoy (trips-enjoy.com) |
| Document type | Software Requirements Specification |
| Version | 1.1 |
| Date | 9 November 2026 |
| Status | For review. Items marked [OPEN] need a decision before the affected behavior ships. Items marked (placeholder) carry a working value that the named owner must confirm. |
| Owner | Product and Engineering (Trips Enjoy) |
| Approvers | [OPEN: named approvers, D-314] |
| Companion documents | BRD.md v1.1 (source of every BR, BRL, OD and RSK ID), ERD.md v1.0 (data model), user-flows/ 00 to 08 (per-app flows), Tasks.md (delivery backlog), trips-enjoy-technical-architecture.md (architecture, 18 Apr 2026). |
| Source status for this version | Read in full: BRD v1.1, technical architecture (18 Apr 2026), system workflows (18 Apr 2026), auth and account rules (4 Oct 2026), design value audit and screen coverage audit (17 Oct 2026, which quote the decision register D-numbers). Where the October documents and the April documents conflict, the October documents win. |

### 1.1 Changes from v1.0

| Area | Change |
|---|---|
| Sources | Architecture, workflows and auth rules now reviewed. Conflict C-06 (architecture not reviewed) and C-07 (user-flows 03 to 08 missing) are closed. |
| Open items closed | About 40 [OPEN] items are now set from the decision register: verification by role, appeal window, deal expiry, refund table (placeholder), retry after hold expiry, payment attempts, active-trip definition, coupon counting, webhook policy, invoice rules, settlement rules, payout schedule, dispute SLAs, incident categories, retention periods, capacity stages, backups, observability tools, paging, deprecation notice, maintenance window, digits. |
| Changed behavior | Organization-cancelled trips now refund in full (D-150, was "same policy as traveler refund"). Filling Up now starts at 80 percent of seats reserved (D-189, was "first booking"). Check-in events are kept 1 year, not 7 (D-45). Account lockout now applies to all roles (D-53). |
| New modules | MKT (markets), ANL (analytics), API (public API, Could). |
| New requirements | FR-ORG-08, FR-STF-07, FR-PAY-08, FR-PAY-09, FR-SUB-08 to FR-SUB-11, FR-TRK-07, FR-DRV-08, FR-NTF-07, FR-ADM-08, FR-ADM-09, FR-ACC-08 to FR-ACC-10, FR-MKT-01 to FR-MKT-05, FR-ANL-01 to FR-ANL-05, FR-API-01. |
| New state machines | 7.9 Settlement, 7.10 Dispute. |
| Data | Section 6 now points to ERD.md for tables and columns; entity list extended with Market, Settlement, Dispute and Analytics. |

## 2. Introduction

### 2.1 Purpose

This SRS states what the Trips Enjoy software shall do and the qualities it shall have. It is the basis for design, build, test and acceptance. Every functional requirement traces to a business requirement (BR), business rule (BRL) or open decision (OD) in the BRD. Section 10 holds the traceability matrix. The BRD states what the business needs. This SRS states how the software must behave to meet those needs. It does not restate business policy except where the software must enforce it.

### 2.2 Scope

Trips Enjoy is a bilingual (Arabic and English) multi-tenant SaaS marketplace for organized group trips. This SRS covers the software for:

- Accounts, identity, sign-in and role-specific registration.
- Applications and approvals for organizations, platform drivers and platform photographers.
- Organization workspaces: trips, programs, fleet, staff, employees and permissions, coupons, subscriptions, accounting, invoices, analytics, CRM and webhooks.
- Traveler booking, seat selection, checkout, payment, refunds, receipts and invoices.
- Deal negotiation between organizations and platform suppliers.
- Vehicle moderator check-in and check-out, live tracking, and the parent tracker.
- Photo albums with three privacy modes.
- Driver lab-test workflow.
- Notifications, the platform admin console, the ledger, payouts and settlements.
- Market configuration by country.

This SRS does not cover business policy beyond what the software enforces (BRD), per-market legal reviews, marketing site content, visual design, or the detailed API specification (OpenAPI, generated from code).

### 2.3 Definitions and acronyms

| Term | Meaning |
|---|---|
| Admin console | The platform staff web application at admin.trips-enjoy.com. |
| Applicant | A user who has submitted an application to become an organization, platform driver or platform photographer. |
| BR, BRL, OD, RSK | Business requirement, business rule, open decision and risk IDs in the BRD. |
| Child link | A record that connects a parent or guardian to a child traveler on one booking. |
| D-nn | A decision in the decision register, as quoted in the design value audit (17 Oct 2026). |
| Deal | A negotiated agreement between an organization and a platform driver or photographer for one vehicle or trip. |
| Dunning | Payment retries and reminders after a failed subscription payment. |
| Feature gate | A server-side check that blocks an action when the account's plan does not allow it. |
| FR | Functional requirement. |
| Grace period | The 7 days after a failed renewal during which features stay active. |
| Hold | A temporary reservation of a seat during checkout: 15 minutes, or the voucher window for Fawry cash. |
| Ledger | The double-entry record of every financial event. |
| Market | The country configuration that sets currency, payment methods, tax rules and default time zone. |
| Moderator | A person supervising one vehicle on one trip. Sees only that vehicle. |
| MoSCoW | Priority scale: Must, Should, Could. Maps to BRD M, S and C. |
| NFR | Non-functional requirement. |
| Organization | A travel company, school, employer or event organizer that runs trips. Each organization is a tenant. |
| Organization-owned driver or photographer | A driver or photographer belonging to one organization and visible only to it. |
| Parent tracker | The section of the traveler app where parents see their linked children's trip status. |
| Platform driver or photographer | An independent, admin-approved supplier who serves any organization through a deal. |
| Plan | A subscription tier with limits and features, for one role, priced per market. |
| Settlement | The per-trip record of amounts owed between the platform, the organization and its suppliers, tracked until every line is paid. |
| Tenant | One organization's isolated workspace. |
| Traveler | A person who books and attends trips. |
| Trip | The single record for one organized trip, which may run many vehicles. |
| Vehicle type | A reusable seat layout. |
| ZATCA | Saudi Arabia's e-invoicing authority and rules. |
| Acronyms | API: application programming interface. CDN: content delivery network. FCM and APNS: Firebase Cloud Messaging and Apple Push Notification service. GDPR: EU General Data Protection Regulation. GPS: global positioning system. HMAC: hash-based message authentication code. JWT: JSON Web Token. MRR: monthly recurring revenue. OTP: one-time password. PDPL: personal data protection law. PWA: progressive web app. RLS: row-level security. RPO and RTO: recovery point and recovery time objectives. SSE: server-sent events. TLS: transport layer security. WCAG: Web Content Accessibility Guidelines. |

### 2.4 References

| Document | Version or date | Use in this SRS |
|---|---|---|
| BRD.md | v1.1, 9 Nov 2026 | Primary source of BR, BRL, OD and RSK IDs, plans, markets and flows. |
| ERD.md | v1.0, 9 Nov 2026 | Tables, columns, lookup types and RLS paths for every entity in section 6. |
| user-flows/ | 9 Nov 2026 | Per-app flows (UF IDs), screens and edge cases. |
| Technical architecture | 18 Apr 2026 | Stack, tenancy model, real-time design, payments, media, jobs, observability, security, capacity, backups. |
| System workflows | 18 Apr 2026 | State machines and sequences, where the October documents are silent. |
| Auth and account rules | 4 Oct 2026 | Verification, sessions, sign-in, applications, lab tests and deletion. |
| Design value audit | 17 Oct 2026 | Decision register values (D-numbers) and canvas conflicts. |

### 2.5 Document conventions

- Requirement IDs use FR-module-NN and NFR-group-NN. IDs are stable. A retired ID is not reused.
- Each functional requirement has a title, a "shall" statement, a priority, its sources, and one to three acceptance criteria written as Given, when, then.
- Priorities use MoSCoW. Must means required for the BRD phase in which the module appears. Should means required in that phase if effort allows. Could means deferrable.
- [OPEN] marks a value no source sets. (placeholder) marks a working value that its owner must confirm. Neither blocks the requirements around it, but the affected behavior does not ship until it is set.
- Money is an amount in minor units with a currency code. Every amount shown carries its currency code.
- Terms follow the BRD glossary.

## 3. Overall description

### 3.1 Product perspective

Trips Enjoy is one product with one codebase (a modular monolith) and shared-schema multi-tenant data with row-level security (architecture sections 1 and 4).

| Part | Form | Primary users |
|---|---|---|
| Traveler app, including the parent tracker section | Responsive web and mobile apps (iOS, Android) | Travelers, parents and guardians |
| Organization dashboard | Responsive web app at business.trips-enjoy.com | Organization owners and employees |
| Admin console | Web app at admin.trips-enjoy.com, staff only | Platform admins |
| Driver app | Mobile app; web limited to documents and profile (D-86) | Platform drivers and organization-owned drivers |
| Photographer studio | Responsive web in phase 1, mobile app available (D-86) | Platform and organization-owned photographers |
| Moderator app | Installable web app on phones, scoped to one vehicle | Vehicle moderators |
| Backend services | REST API, WebSocket channel with SSE fallback, scheduled jobs, queues, notification service, payments and ledger, media pipeline, search | All surfaces and external systems |
| External systems | Payment gateways, messaging, email, push, maps, lab partners, CRM and webhook targets, business registry (manual in phase 1) | See section 4.3 |

### 3.2 Product functions by module

| Code | Module | Summary |
|---|---|---|
| AUTH | Accounts, identity and access | Role-specific registration, verification, sign-in, sessions, multi-role accounts, organization switching, suspension and deletion. |
| ONB | Applications and approvals | Submission, the single admin queue, changes requested, rejection, appeal and re-application, supplier document rules. |
| ORG | Organization tenancy and settings | Tenant setup, isolation, organization-owned suppliers, fleet, seat settings, CRM and webhooks, invoices, branding. |
| EMP | Employees and permissions | Invitations, permission sets, limits, scope enforcement. |
| TRIP | Trips | Wizard, publishing, multi-vehicle trips, lifecycle, changes that affect bookings. |
| PRG | Programs | Pricing models, program types, live summary, enrollment reports. |
| FLT | Fleet and vehicle types | Seat layouts and vehicles. |
| STF | Staffing | Driver and photographer tracks, Ready gate, moderator assignment. |
| NEG | Deal negotiation | Proposals, counters, expiry, mutual acceptance, finalization. |
| SEAT | Seats | Company-assigned and self-selected seats, live seat map, holds. |
| BKG | Booking and checkout | Cart, passengers, programs, seats, coupon, checkout, cancellation, receipts. |
| PAY | Payments and refunds | Gateway handoff, outcomes, pending and voucher states, retries, refunds, reconciliation. |
| CPN | Coupons | Scope, validation, redemption, reversal, analytics, abuse flags. |
| SUB | Plans and subscriptions | Catalog, feature gates, dunning, grace, upgrades and downgrades, overrides, counters. |
| CHK | Check-in and check-out | Moderator events, vehicle match, offline queue, incidents, occupancy. |
| MOD | Moderator app | Scoped access, sign-in, end-of-trip report. |
| TRK | Live tracking | Vehicle positions and passenger movement for operations. |
| PAR | Parent tracker | Child linking, view scope, eligibility, notifications, safety reports. |
| MED | Media and albums | Albums, privacy modes, watermarking, downloads. |
| PHO | Photographer studio | Portfolio, rates, availability, earnings, plan rules. |
| DRV | Driver app and lab tests | Work area, deals, lab-test gate, deadlines, suspension, earnings. |
| ADM | Admin console | Accounts, admin staff, configuration, disputes, lab tests, audit log. |
| ACC | Accounting and settlements | Ledger, accounting views, payouts, settlements, invoices across parties, reconciliation, exports. |
| NTF | Notifications | Channels, language, quiet hours, templates, delivery, reminders. |
| TRV | Traveler profile and history | Profile, visibility, history, discounts, plan changes, privacy requests. |
| MKT | Markets | Country configuration: currency, methods, tax, time zone, prices. |
| ANL | Analytics | Organization, supplier and admin analytics. |
| API | Public API | Documented API for organizations and partners (Could). |

### 3.3 User classes and characteristics

| User class | Characteristics | Plan requirement | Main surface |
|---|---|---|---|
| Visitor | Browses public trips and registration pages. Not signed in. | None | Traveler app |
| Traveler | Books trips. Mobile-first. Any age, so child-data rules apply. | None (Free, Explorer, VIP) | Traveler app |
| Parent or guardian | A traveler on Explorer or VIP with linked children. | Explorer or VIP, plus organization eligibility (FR-PAR-03) | Parent tracker |
| Platform admin | Staff. Roles: Super admin, Operations, Finance (D-49). | Staff | Admin console |
| Organization owner | Registers, pays and runs the tenant. | Starter, Growth or Enterprise | Organization dashboard |
| Organization employee | Works within granted permissions. May belong to several organizations. | Organization plan | Organization dashboard |
| Platform driver | Independent, admin-approved. Works through deals. | Basic, Professional or Premium | Driver app |
| Organization-owned driver | Drives for one organization only. | Organization plan (OD-19, proposed) | Driver app |
| Platform photographer | Independent, admin-approved. Works through deals. | Basic, Studio or Pro | Photographer studio |
| Organization-owned photographer | Works for one organization only. | Organization plan (OD-19, proposed) | Photographer studio |
| Vehicle moderator | One vehicle on one trip. Often non-technical, on a bus, with a phone. | [OPEN: OD-06] | Moderator app |

### 3.4 Operating environment

| Item | Requirement |
|---|---|
| Web browsers | Current and previous major versions of Chrome, Safari, Edge and Firefox on desktop and mobile [OPEN: confirm list]. |
| Mobile operating systems | iOS and Android [OPEN: minimum versions]. Apps prompt for update when below the supported version. |
| Moderator app | Installable web app on Android and iOS, working on mobile data, with an offline queue (FR-CHK-05). |
| Languages | Arabic (right to left) and English from the first release. Western digits 0 to 9 in both (D-159). |
| Time zones | Each trip has a time zone. An organization's time zone defaults to its market's main zone: Cairo, Riyadh or Dubai (D-282). Times show in the trip's time zone, plus the viewer's zone where they differ. |
| Hosting region | Recommended: AWS me-central-1 (UAE) primary with cross-region backups (architecture 20.2) [OPEN: confirm against Egyptian data residency, NFR-DAT-04]. |

### 3.5 Design and implementation constraints

| ID | Constraint |
|---|---|
| CON-01 | Tenant isolation is mandatory. One organization's data is never visible to another (BRL-01). |
| CON-02 | Card data never reaches Trips Enjoy servers. Payment runs on the gateway's hosted pages or SDKs (BR-BKG-02). |
| CON-03 | Arabic and English from the first release, with right-to-left layouts. |
| CON-04 | Markets: Egypt pilot in phase 2; Saudi Arabia and the UAE in phase 5 (D-69). |
| CON-05 | Recommended stack (architecture section 3, to confirm in T-FND-01): TypeScript end to end; NestJS modular monolith; PostgreSQL 16 with RLS and TimescaleDB; Redis 7; SQS and SNS; Next.js web; React Native mobile; S3 or R2 with CDN; AWS with ECS Fargate; Terraform; GitHub Actions. |
| CON-06 | Plan limits and feature gates are enforced on the server (BRL-02). |
| CON-07 | Every financial event is written to a double-entry ledger (BRL-18). |
| CON-08 | Child data is never used for advertising, marketing or model training. |
| CON-09 | Delivery follows the six phases in BRD 3.3. |
| CON-10 | Providers follow BRD 9.2. Gateway choice per market follows OD-11. |
| CON-11 | Database conventions follow ERD.md section 1: BIGSERIAL keys with a public UUID, TIMESTAMPTZ, soft delete with audit columns, and lookup tables instead of enum types. |

### 3.6 Assumptions and dependencies

Assumptions in BRD 9.1 and dependencies in BRD 9.2 apply. Additional assumptions:

| ID | Assumption | Impact if wrong |
|---|---|---|
| ASM-01 | One account can hold traveler and supplier roles at once. | Account model changes. |
| ASM-02 | Lab partner results reach the platform directly. Driver upload is the fallback. | FR-DRV-04 changes. |
| ASM-03 | Live location is collected only while a trip is In Progress. | Tracking and privacy design change. |
| ASM-04 | Only the owner, or an employee with the subscription permission, changes plans and billing. | Permission design changes. |
| ASM-05 | A trip is sold in its organization's market currency only. | Multi-currency pricing would be needed. |

## 4. External interface requirements

### 4.1 User interfaces

| Surface | Users | Access rules |
|---|---|---|
| Traveler app | Travelers, parents | Public browsing. Booking and account actions after sign-in. |
| Organization dashboard | Owners, employees | Password plus SMS or authenticator code at every sign-in. Tenant-scoped. Organization switcher. |
| Admin console | Admins | Password plus mandatory authenticator. Separate route. |
| Driver app | Drivers | Sign-in. Restricted to four actions while a lab test is pending (FR-DRV-03). |
| Photographer studio | Photographers | Sign-in. Plan-gated features. |
| Moderator app | Moderators | Staff code. One vehicle. |
| Parent tracker | Parents | Section of the traveler app (D-124). Linked children only. |

| ID | Interface requirement | Priority |
|---|---|---|
| UI-01 | Every surface supports Arabic and English, with the language switch available on every screen without losing the current task. | Must |
| UI-02 | Arabic layouts mirror fully: navigation, forms, tables, charts and icons that imply direction. | Must |
| UI-03 | Every surface meets WCAG 2.1 Level AA (proposed). | Must |
| UI-04 | Every surface reflows to a 360 px phone width without horizontal page scrolling, with a 16 px side gutter. | Must |
| UI-05 | Forms show errors next to the field in the user's language and keep entered data after an error. | Must |
| UI-06 | Every amount is labeled with its currency code and follows the market's rounding rule. | Must |
| UI-07 | Admin console and organization dashboard use separate routes from the traveler app. | Must |
| UI-08 | Destructive and money-moving actions use a confirmation step that names the effect. | Must |
| UI-09 | No screen shows a card number field. Payment opens the gateway page or SDK. | Must |

### 4.2 Hardware interfaces

No special hardware. Phones are used for GPS (check-in location, live tracking), camera (document capture, ticket scan, photo upload) and push. Where a permission is denied, the user is told what is missing and what still works. If location is denied at check-in, the event is still recorded without location and flagged (proposed).

### 4.3 Software interfaces

| Interface | Purpose | Notes |
|---|---|---|
| Stripe (cards, Apple Pay, Google Pay, Billing, Connect Express) | Bookings, plans, refunds, payouts | Hosted pages or Elements; webhooks. |
| Paymob or Tap | Local cards in MENA | Per market (OD-11). |
| Fawry | Cash vouchers in Egypt | Voucher valid 48 hours (D-31). |
| SMS and WhatsApp (Twilio or local provider) | OTP, staff codes, notifications | Delivery status recorded; switch channel after 3 failed deliveries. |
| Transactional email (AWS SES, Postmark fallback) | Notifications, receipts, invoices | Bounce and complaint handling. |
| Push (FCM, APNS) | Mobile notifications | Token per signed-in device. |
| Maps and routing (Mapbox) | Routes, stops, live map | Tiles and routing only; location data stays on platform storage. |
| Object storage and CDN (S3 or R2, CloudFront or Cloudflare) | Media and documents | Identity documents in a restricted bucket. Direct upload by presigned URL. |
| Lab partner network | Driver lab tests | Results sent from the lab to the platform. |
| Business registry and tax checks | Organization onboarding | Manual in phase 1 (OD-16). |
| CRM and outbound webhooks | Events to organization systems | FR-ORG-06. |
| Search (Postgres full-text first; OpenSearch or Meilisearch later) | Trip and supplier discovery | Event-driven indexing; 5 to 30 seconds lag accepted. |

### 4.4 Communications interfaces

| ID | Requirement | Priority |
|---|---|---|
| COM-01 | All client-server traffic uses HTTPS with TLS 1.3 and HSTS. Mobile apps pin certificates. | Must |
| COM-02 | The real-time channel uses WebSocket with SSE fallback, for the seat map, live tracking, deal updates and operations dashboards. | Must |
| COM-03 | Every real-time subscription is authenticated and authorized for the trip, vehicle, deal or account it names before data is sent. | Must |
| COM-04 | Outbound webhooks use HTTPS, are signed with HMAC-SHA256 over the body and a timestamp, and carry an event identifier. Receivers reject timestamps older than 5 minutes (D-62). | Must |
| COM-05 | Inbound gateway and messaging callbacks are verified against the provider's signature before any state change. | Must |

## 5. Functional requirements

Each module lists its requirements. Source IDs refer to the BRD. Acceptance criteria are Given, when, then sentences.

### 5.1 Accounts, identity and access (AUTH)

**FR-AUTH-01. Role-specific registration paths.** The platform shall give each role its own registration path (/register/traveler, /register/driver, /register/photographer, /register/organization) and shall not show a combined onboarding form. Organization registration shall be web only.
Priority: Must. Source: BR-AUTH-01.
- Given a visitor opens /register, when the visitor chooses a role, then only that role's form is shown.
- Given the mobile app, when a user chooses Organization, then the app hands off to the web registration page.

**FR-AUTH-02. Country on every registration form.** Every registration form shall collect the applicant's country and shall not submit without it. The country sets the account's market (FR-MKT-02).
Priority: Must. Source: BR-AUTH-02; BRL-23; BRL-27.
- Given no country is selected, when the applicant submits, then submission is refused and the field is named.

**FR-AUTH-03. Verification by one-time code.** Accounts shall verify contact details by one-time code before acting: travelers at least one of email or phone; drivers, photographers and organization owners both (D-20). Codes expire after 5 minutes. After 5 wrong codes, verification pauses for 15 minutes. Resend is offered after a 45 to 60 second countdown. A resend does not reset the wrong-code count. After 3 failed deliveries, the code is offered on the other channel (D-58, D-59).
Priority: Must. Source: BR-AUTH-03; BRL-20; OD-23.
- Given a code older than 5 minutes, when it is entered, then it is rejected and resend is offered after the countdown.
- Given five wrong codes, when a sixth is entered, then verification is paused for 15 minutes and the resume time is shown.
- Given a photographer verified only email, when they submit the application, then submission is refused until the phone is verified.

**FR-AUTH-04. Role-specific sign-in methods.** Sign-in shall follow the role: admins use email and password plus a mandatory authenticator code; organization staff use email and password plus an SMS or authenticator code at every sign-in (D-48); travelers and suppliers use password or a one-time code, both offered (proposed, auth canvas); moderators use a staff code (FR-MOD-05). No biometric sign-in in phase 1.
Priority: Must. Source: BR-AUTH-04; OD-03.
- Given an admin enters a correct password without a code, when sign-in is attempted, then access is refused and the code is requested.
- Given organization staff sign in, when no second-factor code is entered, then sign-in is refused.

**FR-AUTH-05. Sign-in lockout.** The system shall lock any account for 15 minutes after 5 failed sign-in attempts, for every role (D-53), and rate-limit sign-in by IP (NFR-SEC-13).
Priority: Must. Source: BRL-21.
- Given five failed attempts, when a sixth is made within 15 minutes, then it is refused and the lock end time is shown.

**FR-AUTH-06. Multi-role accounts.** One person shall hold several roles and switch between them without a second account. The active role is part of the session.
Priority: Must. Source: BR-AUTH-05.
- Given a person holds traveler and approved photographer roles, when they switch to photographer, then the studio opens and traveler data is not shown in it.

**FR-AUTH-07. Staff access to several organizations.** Organization staff shall see and switch between the organizations whose member list includes their email, and no others. Switching changes the tenant context of the session.
Priority: Must. Source: BR-AUTH-06.
- Given a staff email listed by two organizations, when the staff member signs in, then both are listed and either opens.
- Given an organization that does not list the email, when the staff member searches for it, then it is not listed.

**FR-AUTH-08. Suspension and reinstatement.** Admin shall suspend and reinstate any account with a reason code (D-234). A suspended account loses access at once and keeps its data. Early lifting of a safety suspension requires a second admin (D-233).
Priority: Must. Source: BR-AUTH-07; BR-ADM-02.
- Given a suspended account, when its user signs in, then access is refused with a suspension message and no data is deleted.

**FR-AUTH-09. Account deletion with a 30-day grace period.** A user shall request deletion. During the 30-day grace period no booking or payment is allowed, and signing in restores the account. On day 31, deletion becomes permanent under DAT-03. An organization owner cannot delete the account while any of the organization's trips is In Progress (D-168).
Priority: Must. Source: BR-AUTH-08; BRL-15.
- Given a request made 10 days ago, when the user signs in, then the account is restored and the request cancelled.
- Given 30 days have passed, when the nightly job runs, then the account is deleted per section 6.4.

**FR-AUTH-10. Sessions and tokens.** Access tokens shall last 15 minutes. Refresh tokens rotate on every use and last 30 days. Web sessions end after 12 hours; drafts are kept (OD-02). Mobile apps stay signed in through refresh.
Priority: Must. Source: BRL-22.
- Given a web session in use for 12 hours, when the next request is made, then the user must sign in again.
- Given an unpublished trip draft when the session ended, when the user signs in again, then the draft is available.

### 5.2 Applications and approvals (ONB)

**FR-ONB-01. Application and supplier access.** The platform shall accept applications from organizations, platform drivers and platform photographers. A plan may be chosen at registration with no charge; it is assigned on approval (D-77). Supplier actions are refused until approval.
Priority: Must. Source: BR-ONB-01; BRL-02.
- Given a pending application, when the applicant tries to create a trip or accept a deal, then the action is refused with an approval-pending message.

**FR-ONB-02. Single admin queue.** The admin console shall show all applications in one queue with their documents. Admin shall approve, request changes or reject. A rejection requires a reason code (D-234) and a message in the applicant's language.
Priority: Must. Source: BR-ONB-02; BR-ADM-01.
- Given a rejection without a reason, when the admin submits, then it is refused.

**FR-ONB-03. Decision time target.** The queue shall flag any application pending for more than 2 working days.
Priority: Must. Source: BR-ONB-03.
- Given an application pending for 3 working days, when the queue opens, then it is flagged overdue.

**FR-ONB-04. Changes requested.** The applicant shall have 14 days to respond, with a reminder on day 7 and one 7-day extension on request (D-235). Resubmission returns the application to Pending Review. Without a response, the application closes [OPEN: closure day when the extension is used, OD-32].
Priority: Must. Source: BR-ONB-04.
- Given changes were requested 15 days ago with no extension and no response, when the daily job runs, then the application is Closed.

**FR-ONB-05. Rejection, appeal and re-application.** A rejected applicant shall appeal within 14 days of the notice, with a statement and up to 5 files; one appeal per rejection; decided within 5 working days (D-237). Re-application is allowed 30 days after rejection; a closed application counts as rejected. The traveler account stays active.
Priority: Must. Source: BR-ONB-05; BRL-06.
- Given a rejection 12 days ago, when the applicant re-applies, then it is refused and the earliest date is shown.
- Given a rejection 15 days ago, when the applicant tries to appeal, then the appeal is refused as out of time.

**FR-ONB-06. Photographer portfolio minimum.** A photographer application shall require at least 8 portfolio photos (JPEG, PNG or HEIC, 25 MB each, D-81).
Priority: Must. Source: BR-ONB-06.
- Given 7 uploaded photos, when the photographer submits, then submission is refused and the required count is shown.

**FR-ONB-07. Driver vehicle and insurance details.** A driver using their own vehicle shall give plate, model, year, seat count, registration and insurance valid for at least 3 more months. Documents are PDF, JPG or PNG up to 10 MB (D-80).
Priority: Must. Source: BR-ONB-07.
- Given insurance expiring in 2 months, when the driver submits, then submission is refused with the minimum shown.

**FR-ONB-08. Lab consent and initial result.** A driver application shall record consent to lab tests (NFR-DAT-05) and include an initial lab result dated within the last 30 days.
Priority: Must. Source: BR-ONB-08.
- Given a lab result dated 31 days before submission, when the driver submits, then submission is refused.

**FR-ONB-09. Applicant status notifications.** Each state change shall notify the applicant in their language on at least one enabled channel.
Priority: Must. Source: BR-ONB-09; BR-NTF-01.
- Given an application moves to Approved, when the state changes, then the applicant is notified in their language.

### 5.3 Organization tenancy and settings (ORG)

**FR-ORG-01. Tenant isolation.** No user outside an organization shall read or change its trips, staff, suppliers, vehicles, coupons, invoices or accounts. Requests for another tenant's records return not found.
Priority: Must. Source: BR-ORG-01; BRL-01.
- Given a user of organization A requests a trip of organization B, when it is processed, then the response is not found with no trip data.

**FR-ORG-02. Tenant setup on approval.** Approval shall create the tenant in the applicant's market, assign the plan chosen at registration or Starter by default (D-24, D-77), make the applicant the owner, and send a sign-in invitation.
Priority: Must. Source: BR-ORG-01; BRL-02; OD-21.
- Given an approved application with no plan chosen, when approval is saved, then the tenant exists on Starter and the owner is invited.

**FR-ORG-03. Organization-owned drivers and photographers.** An organization shall invite its own drivers and photographers by SMS or email (link valid 7 days, D-94). They are visible only to that organization and accepted on assignment without admin approval. Removing one releases their future assignments and notifies operations.
Priority: Must. Source: BR-ORG-03; BRL-05.
- Given organization A onboards a driver, when organization B searches the pool, then the driver is not listed.
- Given organization A assigns its own driver, when the assignment is saved, then it is Accepted with no admin step.

**FR-ORG-04. Fleet registration.** An organization shall register vehicles with plate, model, year, vehicle type, seats and registration status.
Priority: Must. Source: BR-ORG-04.
- Given a vehicle with a seat count that differs from its type, when it is saved, then it is refused with the mismatch shown.

**FR-ORG-05. Seat self-selection setting.** An organization shall enable or disable traveler seat self-selection per trip or as an account default, only on plans that include it.
Priority: Must. Source: BR-ORG-05; BR-SEAT-01.
- Given a Starter organization, when it tries to enable self-selection, then the action is blocked with an upgrade prompt.

**FR-ORG-06. CRM and webhooks.** An organization shall connect a CRM and register webhook endpoints for trip published, trip cancelled, booking confirmed, booking cancelled, check-in and check-out events. Deliveries are signed (COM-04), retried 5 times over 24 hours, then the owner is emailed; deliveries can be replayed for 7 days; after a secret rotation the old secret stays valid for 24 hours. No plan gate applies (D-62 to D-64). Endpoint count per plan is [OPEN: D-65]. CRM targets are [OPEN: OD-18].
Priority: Should. Source: BR-ORG-06.
- Given an endpoint subscribed to booking confirmed, when a booking is confirmed, then a signed event with an event ID is delivered.
- Given five failed attempts over 24 hours, when the last fails, then the owner is emailed and the delivery shows as failed with a replay option.

**FR-ORG-07. B2B and B2C invoices.** An organization shall issue invoices for its trips on every plan (D-178). An invoice is B2B when the buyer gave a tax number, otherwise B2C. An issued invoice is never edited; corrections are credit notes referencing the original. If numbering fails, no invoice is issued, the booking stays Confirmed, numbering retries 3 times, then the case goes to finance review (D-179 to D-181). ZATCA e-invoicing is out of scope until OD-10.
Priority: Must. Source: BR-ORG-07; BRL-18; BRL-28.
- Given a buyer entered a tax number at checkout, when the booking is confirmed, then a B2B invoice is issued and posted to the ledger.
- Given an issued invoice needs a correction, when finance corrects it, then a credit note referencing the original number is issued and the original is unchanged.

**FR-ORG-08. Custom branding.** Enterprise organizations shall upload a logo and brand colors applied to their public trip pages, emails and invoice PDFs.
Priority: Could. Source: BR-ORG-08.
- Given a Growth organization, when it opens branding settings, then they are locked with an upgrade prompt.

### 5.4 Employees and permissions (EMP)

**FR-EMP-01. Permission sets.** The owner shall invite employees and grant each a set of permissions in the areas trips, bookings, operations, accounting, coupons, employees, fleet, subscription, integrations and analytics. Templates (Operations, Finance, Support, Manager) are offered as starting points.
Priority: Must. Source: BR-ORG-02.
- Given an employee without accounting permission, when they open accounting, then the area is hidden and direct requests are refused.

**FR-EMP-02. Permission changes are audited.** Every grant, change and removal shall be written to the audit log with actor, time, and before and after values, and shown in the employee's permission history.
Priority: Must. Source: BRL-19.
- Given an owner removes a permission, when saved, then the audit log records owner, employee, time and the removed permission.

**FR-EMP-03. Employee limit per plan.** Active employees shall not exceed the plan limit (Starter 5, Growth 20, Enterprise unlimited).
Priority: Must. Source: BR-ORG-02; BR-SUB-02.
- Given a Starter organization with 5 active employees, when a sixth is invited, then it is blocked with an upgrade prompt.

**FR-EMP-04. Server-side scope.** Every permission shall be enforced by the server on every request.
Priority: Must. Source: BR-ORG-02; BRL-01.
- Given an employee with trip scope only calls an accounting endpoint, when processed, then it is refused as forbidden.

**FR-EMP-05. Employee removal.** Removing an employee shall end their access to that organization at once; past actions and audit entries remain.
Priority: Must. Source: BR-ORG-02; BR-AUTH-07.
- Given a removed employee, when they sign in, then the organization is not listed and audit entries still show their name.

**FR-EMP-06. Invitation and first sign-in.** An invitation link shall go to the employee's email, valid 7 days (D-286). On first sign-in the employee sets a password and enrolls a second factor (SMS or authenticator) before the workspace opens.
Priority: Must. Source: BR-ORG-02; BR-AUTH-04.
- Given an accepted invitation, when the employee signs in for the first time, then second-factor setup is required before the workspace opens.

### 5.5 Trips (TRIP)

**FR-TRIP-01. Trip creation wizard.** The organization shall create a trip through a wizard: basics, schedule and route, vehicles, staffing, programs, seating, pricing and capacity, publishing. Publishing is blocked until required steps are complete. Drafts save automatically.
Priority: Must. Source: BR-TRIP-01.
- Given the vehicles step is incomplete, when the organization publishes, then publishing is blocked and the step is named.

**FR-TRIP-02. Draft, public and targeted publishing.** A trip shall be saved as a hidden draft, published publicly, or published to a target list of travelers.
Priority: Must. Source: BR-TRIP-02.
- Given a draft, when travelers search, then it is not shown.
- Given a targeted trip, when a traveler not on the list browses, then it is not shown.

**FR-TRIP-03. Multi-vehicle trips.** A trip shall support several vehicles, each with one vehicle type, one driver and one moderator, up to the plan's vehicle limit.
Priority: Must. Source: BR-TRIP-03; BRL-07.
- Given a vehicle with no moderator, when Ready is requested, then it is refused and the vehicle is named.

**FR-TRIP-04. Program changes until departure.** Programs shall be addable, editable and removable until the trip starts, subject to FR-TRIP-06.
Priority: Must. Source: BR-TRIP-04.
- Given the trip has started, when a program is added, then the request is refused.

**FR-TRIP-05. Trip lifecycle.** A trip shall move only through the transitions in section 7.1. A trip In Progress cannot be cancelled (D-25, OD-22).
Priority: Must. Source: BRL-07; BRL-30; BR-TRIP-02.
- Given a Draft trip, when In Progress is requested, then the transition is refused.

**FR-TRIP-06. Changes that affect confirmed bookings.** A change to programs, seat mode, vehicles or times that would break a confirmed booking shall notify each affected traveler and offer remedies (keep with the change, switch option, or cancel with full refund) before it takes effect. The traveler has 7 days to choose; then the case goes to admin (D-148). Vehicle or driver changes are notified within 1 hour (D-186).
Priority: Must. Source: BRL-24.
- Given a confirmed booking includes a program being removed, when the change is confirmed, then the traveler is notified and offered remedies before the removal applies.

**FR-TRIP-07. Active trip limit.** Trips created in the current cycle that reach Published through Completed count against the plan's monthly limit (D-143). Beyond the limit, publishing is blocked with an upgrade prompt.
Priority: Must. Source: BR-SUB-02; BRL-02.
- Given a Starter organization with 5 counted trips this month, when it publishes a sixth, then it is blocked with an upgrade prompt.

### 5.6 Programs (PRG)

**FR-PRG-01. Pricing models.** Each program shall have exactly one pricing model: free, per person, by quantity, per group or tiered.
Priority: Must. Source: BR-PRG-01.
- Given a program with two pricing models, when saved, then it is refused.

**FR-PRG-02. Program types.** A program shall be optional, pre-required (auto-included, not removable), capacity-limited or VIP-only.
Priority: Must. Source: BR-PRG-02.
- Given a pre-required program, when a traveler books, then it is included and cannot be removed.
- Given a VIP-only program, when a Free or Explorer traveler opens it, then it is locked with an upgrade prompt.

**FR-PRG-03. Live booking summary.** The booking summary shall update the total as programs and quantities change, before the next step, including the plan's program discount.
Priority: Must. Source: BR-PRG-03.
- Given a per-person program for two passengers, when added, then the total rises by price times two before the next step.

**FR-PRG-04. Enrollment report.** An organization shall see per program who chose it, quantities and revenue. No plan gate until OD-25 is decided.
Priority: Must. Source: BR-PRG-04; OD-25.
- Given quantities 2 and 3 on one program, when the report opens, then both rows and a total of 5 show.

**FR-PRG-05. Program capacity.** A capacity-limited program shall stop accepting selections at its cap and show as unavailable.
Priority: Must. Source: BR-PRG-02.
- Given a cap of 20 with 20 selections, when a 21st traveler opens it, then it is unavailable.

**FR-PRG-06. Program count limit.** Programs per trip shall not exceed the plan limit (Starter 3, Growth 15, Enterprise unlimited).
Priority: Must. Source: BR-SUB-02; BR-PRG-01.
- Given a Starter trip with 3 programs, when a fourth is added, then it is blocked with an upgrade prompt.

### 5.7 Fleet and vehicle types (FLT)

**FR-FLT-01. Reusable vehicle types.** A vehicle type shall define a seat layout with labeled seats, reusable across trips.
Priority: Must. Source: BR-FLT-01.
- Given a 48-seat type, when a new trip selects it, then the trip uses the same 48 labeled seats.

**FR-FLT-02. Vehicle registry.** A vehicle shall use one vehicle type and its seat count shall match the type.
Priority: Must. Source: BR-ORG-04; BR-FLT-01.
- Given a 48-seat type and a vehicle saying 50, when saved, then it is refused.

**FR-FLT-03. Vehicle limit per trip.** Vehicles per trip shall not exceed the plan limit (Starter 2, Growth 10, Enterprise unlimited).
Priority: Must. Source: BR-TRIP-03; BR-SUB-02.
- Given a Starter trip with 2 vehicles, when a third is added, then it is blocked with an upgrade prompt.

**FR-FLT-04. Seat map from vehicle type.** Each trip vehicle's seat map shall come from its vehicle type and show each seat's label and state.
Priority: Must. Source: BR-FLT-01; BR-SEAT-02.
- Given seat 12B is reserved, when a traveler views the map, then 12B shows unavailable.

**FR-FLT-05. Vehicle removal after bookings.** A vehicle with confirmed bookings shall not be removed until those bookings are reassigned or resolved under FR-TRIP-06.
Priority: Must. Source: BRL-24; BR-TRIP-03.
- Given a vehicle with confirmed bookings, when removal is attempted, then it is refused with the count shown.

**FR-FLT-06. Archiving vehicle types.** An archived type shall not be offered for new trips; existing trips keep their layout.
Priority: Should. Source: BR-FLT-01.
- Given an archived type, when a new trip is created, then it is not offered.

### 5.8 Staffing (STF)

**FR-STF-01. Driver tracks.** Each vehicle shall be driven by an organization-owned driver or by a platform driver through a finalized deal.
Priority: Must. Source: BR-STF-01.
- Given a vehicle being staffed, when a driver is chosen, then only these two options are offered.

**FR-STF-02. Photographer tracks.** A trip's photographer shall be organization-owned or a platform photographer through a finalized deal.
Priority: Must. Source: BR-STF-02.
- Given a platform photographer is chosen, when the organization proceeds, then a deal starts and no assignment is made until it is finalized.

**FR-STF-03. Ready gate.** A trip shall not move to Ready until every vehicle has a confirmed driver and moderator and every platform deal is finalized. The system lists each gap.
Priority: Must. Source: BR-STF-05; BRL-07.
- Given vehicle 3 has no moderator, when Ready is requested, then it is refused and vehicle 3 is listed.

**FR-STF-04. Assignment after finalized deal.** A platform supplier shall be assigned only after both parties accept the same terms.
Priority: Must. Source: BR-STF-04; BRL-04.
- Given a deal in Countered by Provider, when assignment is attempted, then it is refused.

**FR-STF-05. Moderator assignment.** The organization shall assign one moderator per vehicle, with a phone number (D-107). Access is limited to that vehicle on that trip until 24 hours after the trip ends.
Priority: Must. Source: BR-STF-05; BR-CHK-04.
- Given moderator A on vehicle 1, when A opens the app, then only vehicle 1 shows.

**FR-STF-06. Deal request visibility.** A supplier shall see trip, route, date, vehicle and pay before responding.
Priority: Must. Source: BR-STF-06.
- Given an open deal request, when the supplier opens it, then those details show before accept or counter is enabled.

**FR-STF-07. Reassignment deadline.** When a supplier drops out or is paused, the organization shall be able to reassign until 24 hours before departure; after that the trip shows "driver needed" and operations is alerted. A staffing-gap email goes out 48 hours before departure (D-185, D-187).
Priority: Must. Source: BR-STF-05; BRL-17.
- Given a vehicle without a driver 48 hours before departure, when the job runs, then the organization is emailed about the gap.

### 5.9 Deal negotiation (NEG)

**FR-NEG-01. Deal proposal.** Either party shall propose a price for a specific vehicle or trip.
Priority: Must. Source: BR-STF-03.
- Given an organization proposes for vehicle 2, when saved, then the supplier receives the request with the price.

**FR-NEG-02. Counter-offers and expiry.** Either party shall counter with no round limit. An offer expires 72 hours after the last counter; the deal then ends as Rejected with reason expired (BRL-26).
Priority: Must. Source: BR-STF-03; BRL-26; OD-13.
- Given a supplier counter, when saved, then the state is Countered by Provider and the organization is notified.
- Given 72 hours since the last counter with no response, when the job runs, then the deal is Rejected with reason expired.

**FR-NEG-03. Mutual acceptance.** A deal shall be finalized only when both parties accept the same terms. A counter after an acceptance voids that acceptance.
Priority: Must. Source: BR-STF-03; BRL-04.
- Given the supplier accepted, when the organization counters, then the acceptance is void and the state is Countered by Organization.

**FR-NEG-04. Rejection.** Either party shall reject a deal. The vehicle slot stays open for another supplier.
Priority: Must. Source: BR-STF-03.
- Given a rejected deal, when saved, then the organization can start a new deal with a different supplier.

**FR-NEG-05. Finalization.** A finalized deal shall assign the supplier and record the agreed amount as a deal cost in the ledger, with commission per OD-15 (placeholder 10 percent).
Priority: Must. Source: BR-STF-04; BRL-18.
- Given a finalized deal, when saved, then the ledger holds one deal-cost transaction linked to the trip.

**FR-NEG-06. Deal audit.** Every state change and offer shall be recorded and visible to admins. Admins can flag a deal with a reason; a flag can pause it (D-252).
Priority: Must. Source: BRL-18; BR-ACC-02.
- Given a deal with five counters, when an admin opens it, then all five show in order with actor and time.

**FR-NEG-07. Negotiation by plan.** Organizations shall negotiate with platform suppliers only on Growth and Enterprise. Platform suppliers negotiate on every tier.
Priority: Must. Source: BR-SUB-02; BR-STF-03.
- Given a Starter organization, when it starts a deal, then it is blocked with an upgrade prompt.

### 5.10 Seats (SEAT)

**FR-SEAT-01. Company-assigned by default.** Every vehicle shall use company-assigned seating unless self-selection is on for the trip.
Priority: Must. Source: BR-SEAT-01.
- Given self-selection off, when a traveler books, then no seat picker shows and the booking shows "seat assigned later".

**FR-SEAT-02. Live seat map.** With self-selection on, the map shall show each seat's state and update within 500 ms when another traveler takes or releases a seat. A list view is available for screen readers.
Priority: Must. Source: BR-SEAT-02.
- Given two viewers, when one holds 7A, then the other sees 7A unavailable within 500 ms.

**FR-SEAT-03. Priority window.** Explorer and VIP travelers shall get a priority window for seat selection before others [OPEN: window length, D-316, OD-08].
Priority: Should. Source: BR-SEAT-03.
- Given the window is open, when a Free traveler opens the map, then selection is disabled until the window closes.

**FR-SEAT-04. Seat hold.** A seat selected at checkout shall be held for 15 minutes and released automatically when the hold expires or payment does not complete. The price is locked when the hold starts (D-192). Fawry cash vouchers hold seats for the voucher window (FR-PAY-09).
Priority: Must. Source: BR-SEAT-04; BRL-08.
- Given a hold with no completed payment after 15 minutes, when it expires, then the seat returns to Available.

**FR-SEAT-05. Seat states.** Seats shall move only through the states in section 7.3.
Priority: Must. Source: BR-SEAT-04; BR-CHK-03.
- Given a Checked In seat, when a change is attempted, then it is refused.

**FR-SEAT-06. Staff seat assignment.** In company-assigned mode, employees with the operations permission shall assign and reassign seats. A reserved seat cannot be given to a second passenger.
Priority: Must. Source: BR-SEAT-01.
- Given 9C reserved for one passenger, when assigned to another, then it is refused as a conflict.

**FR-SEAT-07. Self-selection by plan.** Self-selection shall be available only on Growth and Enterprise.
Priority: Must. Source: BR-SEAT-01; BR-ORG-05.
- Given a Starter organization, when it views the setting, then it is locked with an upgrade prompt.

### 5.11 Booking and checkout (BKG)

**FR-BKG-01. Booking composition.** A booking shall hold the passengers (with a child flag), program selections, seats where allowed, at most one coupon unless stackable, an optional buyer tax number, and a payment method.
Priority: Must. Source: BR-BKG-01.
- Given one non-stackable coupon, when a second is entered, then it is refused and the first kept.

**FR-BKG-02. Checkout handoff.** Card details shall be entered only on the gateway page or SDK, using the methods of the trip's market (FR-MKT-03). Card data never reaches Trips Enjoy.
Priority: Must. Source: BR-BKG-02.
- Given checkout reaches payment, when the traveler pays, then no card number, expiry or security code appears in platform logs or data stores.

**FR-BKG-03. Retry without losing the cart.** A failed payment shall be retryable while the hold lasts, up to 3 attempts per hold; after the third, the booking stays Payment Failed until the hold expires (D-197). A retry after the hold expires keeps the cart, re-checks the seats and starts a new 15-minute hold only if they are still free (D-191).
Priority: Must. Source: BR-BKG-03; BRL-08.
- Given payment failed 10 minutes into a hold, when the traveler retries, then cart and seats are kept for the remaining 5 minutes.
- Given the hold expired and seat 4A was taken meanwhile, when the traveler retries, then the cart is kept and the traveler is asked to pick another seat.

**FR-BKG-04. Traveler cancellation and refund.** A traveler shall cancel a confirmed booking before departure. The refund follows BRL-12 (placeholder: 80 percent at 48 hours or more, 50 percent from 24 to under 48 hours, none under 24 hours). Requests after the cut-off go to the organization, which decides within 2 working days, then to admin (D-151). Cancellation is not available after departure or after check-in; those become disputes (D-152, D-153). The preview shows the refund amount before confirmation.
Priority: Must. Source: BR-BKG-04; BRL-11; BRL-12; OD-07.
- Given cancellation 50 hours before departure, when confirmed, then 80 percent is refunded and the coupon redemption reversed.
- Given a checked-in passenger, when cancellation is attempted, then it is refused and a dispute is offered.

**FR-BKG-05. Receipts and invoices.** Each confirmed booking shall produce a receipt and an invoice (FR-ORG-07) visible in the traveler's booking history.
Priority: Must. Source: BR-BKG-05.
- Given a booking becomes Confirmed, when the payment is recorded, then receipt and invoice are visible.

**FR-BKG-06. Organization cancellation of a trip.** When an organization cancels a trip, every confirmed booking shall move to Cancelled and be refunded in full, with coupon redemptions reversed (D-150, BRL-30).
Priority: Must. Source: BR-BKG-04; BRL-24; BRL-30.
- Given a trip with 40 confirmed bookings is cancelled, when saved, then 40 full refunds are queued.

**FR-BKG-07. Booking states.** Bookings shall move only through the states in section 7.2.
Priority: Must. Source: BR-BKG-03.
- Given a booking in Cart, when Checked In is set, then it is refused.

### 5.12 Payments and refunds (PAY)

**FR-PAY-01. Payment outcomes.** The platform shall record each gateway result with its reference. Success moves the booking to Confirmed and posts to the ledger; failure moves it to Payment Failed.
Priority: Must. Source: BR-BKG-02; BRL-18.
- Given a success callback, when processed, then the booking is Confirmed and one payment transaction exists in the ledger.

**FR-PAY-02. Idempotent callbacks.** Callbacks shall be verified and processed at most once per gateway event identifier.
Priority: Must. Source: BR-BKG-02; BRL-18.
- Given the same success callback twice, when both are processed, then one payment entry exists.

**FR-PAY-03. Refund execution.** An approved refund shall be sent to the gateway within 2 working days and shown as "refund in progress". It is Refunded only after the gateway confirms; the ledger reverses revenue, commission and fees then. Failed refunds retry 3 times, then stay Refund Pending for finance review (D-149, D-35, D-322).
Priority: Must. Source: BR-BKG-04; BRL-18.
- Given a sent refund not yet confirmed, when the traveler views the booking, then it shows Refund Pending and no reversal is posted.

**FR-PAY-04. Refunds outside policy.** Refunds outside the policy shall be issued by an admin with a reason recorded in the audit log.
Priority: Must. Source: BR-ADM-04; BRL-19.
- Given an out-of-policy refund, when saved, then reason and admin are audited.

**FR-PAY-05. Gateway reconciliation.** The platform shall compare gateway settlement reports with the ledger daily and list each difference as an exception (D-29).
Priority: Must. Source: BR-ACC-05.
- Given a gateway payment missing from the ledger, when reconciliation runs, then it is listed as an exception.

**FR-PAY-06. Subscription charges and dunning.** Plan charges shall run through the gateway's billing product. A failed charge starts dunning (FR-SUB-08) and the grace period (FR-SUB-03).
Priority: Must. Source: BR-SUB-03.
- Given a failed renewal, when the failure is received, then a reminder is sent and grace starts.

**FR-PAY-07. Payment methods per market.** Checkout shall offer the methods configured for the trip's market: in Egypt, Stripe cards and wallets, Paymob or Tap local cards, and Fawry cash; Gulf methods are confirmed at launch (OD-11).
Priority: Must. Source: BR-BKG-02; BR-MKT-01.
- Given an Egyptian trip, when the traveler reaches payment, then the Fawry cash option is offered.

**FR-PAY-08. Payment pending.** If the gateway does not answer within 30 seconds, the booking shall stay Payment Pending with a "confirming payment" message. The platform checks status at 1, 5 and 10 minutes and escalates to admin at 15 minutes. Seats stay held while pending (D-30, D-201).
Priority: Must. Source: BR-BKG-02; RSK-06.
- Given a gateway timeout, when 15 minutes pass with no result, then the case appears in the admin queue and the traveler is told the payment is being checked.

**FR-PAY-09. Fawry cash vouchers.** Choosing Fawry shall issue a voucher valid 48 hours and hold the seats for that window. An unpaid voucher expires and releases the seats (D-31, D-41).
Priority: Must. Source: BR-BKG-02; BRL-08.
- Given an unpaid voucher at 48 hours, when it expires, then the seats return to Available and the traveler is notified.

### 5.13 Coupons (CPN)

**FR-CPN-01. Creator scope.** A coupon shall apply only inside its creator's scope: admin coupons to any trip (optionally limited to a market); organization coupons to its own trips; driver and photographer coupons to bookings where that supplier is assigned.
Priority: Must. Source: BR-CPN-01; BRL-03.
- Given a driver coupon and a booking without that driver, when applied, then it is refused.

**FR-CPN-02. Cross-entity rejection.** Cross-entity use shall always be rejected with "not applicable to this booking", without naming the other entity (OD-20).
Priority: Must. Source: BR-CPN-02; BRL-03.
- Given organization A's coupon on organization B's trip, when validated, then the generic message shows.

**FR-CPN-03. Real-time validation.** Validation shall check existence, status, validity window, scope, usage limit, per-user limit and minimum spend, in that order, and return the first failing reason.
Priority: Must. Source: BR-CPN-03.
- Given a coupon that expired yesterday, when applied, then the reason is "expired".
- Given a total below the minimum, when applied, then the reason is "minimum spend not met".

**FR-CPN-04. Coupon types.** Coupons shall support fixed amount, percentage, program-specific, free program, first booking and minimum spend.
Priority: Must. Source: BR-CPN-03.
- Given a first-booking coupon, when a traveler with a confirmed booking with that entity applies it, then it is refused.

**FR-CPN-05. Lifecycle and controls.** Coupons shall use the states in section 7.7. Creators pause, resume and revoke their own; admins revoke any.
Priority: Must. Source: BR-CPN-01.
- Given a Paused coupon, when applied, then it is refused with reason "paused".

**FR-CPN-06. Redemption and reversal.** Each redemption shall be recorded against its booking. A refund reverses the redemption, frees the traveler's per-user use, and decrements the total usage count (architecture 10.6); an Exhausted coupon returns to Active if still in its window.
Priority: Must. Source: BR-CPN-04; BRL-11.
- Given a refunded booking with a coupon, when saved, then the redemption is reversed and the traveler can use the coupon again.

**FR-CPN-07. Analytics and abuse flags.** Creators shall see redemptions, discount total and remaining uses per coupon with 7, 30 and 90-day presets. Admins see platform analytics and abuse flags. Flags never block automatically; admins review within 2 working days (D-217). Thresholds are [OPEN: D-216].
Priority: Must. Source: BR-CPN-05; RSK-05.
- Given a flagged coupon, when the admin opens it, then the flag shows the rule that raised it and its review due date.

**FR-CPN-08. Coupon count by plan.** Active and paused coupons count against the plan limit (D-203): organizations 5, 20, unlimited; drivers and photographers 3, 10, unlimited.
Priority: Must. Source: BR-SUB-02.
- Given a Starter organization with 5 active or paused coupons, when it creates a sixth, then it is blocked with an upgrade prompt.

### 5.14 Plans and subscriptions (SUB)

**FR-SUB-01. Plan catalog.** Admins shall define plans per role with limits, features, trial length, and monthly and annual prices per market (BR-MKT-01). Changes apply to new actions at once and are audited. Prices are [OPEN: OD-26]; annual discount placeholder 15 percent (OD-14).
Priority: Must. Source: BR-SUB-01; BRL-19.
- Given the Starter employee limit changes from 5 to 6, when saved, then it applies at once and the audit log holds old and new values.

**FR-SUB-02. Feature gates.** Every plan-limited action shall be checked on the server, returning the plan-required category with the current plan, the limit and the next tier. Every non-traveler role needs an active plan (BRL-02).
Priority: Must. Source: BR-SUB-02; BRL-02.
- Given a limit reached, when the action is attempted, then it is blocked and an upgrade prompt shows.

**FR-SUB-03. Grace period.** A failed renewal shall start 7 days of grace with features kept and a payment banner. What happens when grace ends is [OPEN: OD-29]; until decided, the account drops to the role's basic tier and new bookings or new deals are blocked (D-08).
Priority: Must. Source: BR-SUB-03; BRL-14.
- Given a renewal failed 6 days ago, when the user works, then features are active with a warning.
- Given a renewal failed 8 days ago, when the organization publishes a trip, then it is blocked.

**FR-SUB-04. Upgrade, downgrade and cancellation.** Upgrades take effect at once with the prorated difference charged. Downgrades take effect at cycle end with no proration and can be cancelled before then (D-219, D-232). Cancellation keeps access to the end of the paid period. Cancelling during grace ends the plan at once with no refund (D-223).
Priority: Must. Source: BRL-13.
- Given a mid-cycle downgrade, when saved, then the current plan stays until cycle end.

**FR-SUB-05. Admin overrides.** Admins shall grant, revoke or change a plan for one account with a reason and an end date, approved by a second admin. The owner is emailed and the plan reverts at the end date (D-227, D-230).
Priority: Must. Source: BR-SUB-05; BRL-19.
- Given one admin requests a 3-month VIP grant, when a second admin approves, then it applies at once and is audited with its end date.

**FR-SUB-06. Revenue reporting.** Admins shall see MRR, churn, upgrades, downgrades and plan distribution by role, plan, market and billing status.
Priority: Must. Source: BR-SUB-04.
- Given last month, when the view opens, then MRR, churn and upgrade counts for that month show.

**FR-SUB-07. Trials.** A plan with a trial shall not charge until the trial ends. A trial-start notice goes on day 0 and reminders 3 days and 1 day before the end (D-231). No charge happens before an application is approved (D-77).
Priority: Must. Source: BR-SUB-01; OD-28.
- Given a 14-day trial, when the account starts it, then no charge is made until day 15.

**FR-SUB-08. Dunning.** Failed charges shall retry on days 1, 3, 5 and 7, with an email at each attempt (D-09).
Priority: Must. Source: BR-SUB-03.
- Given a charge fails on day 0, when day 3 arrives, then the second retry runs and an email is sent.

**FR-SUB-09. Limits after a downgrade.** Items above a lower limit shall keep working; no new items can be added until the count is under the limit (D-144).
Priority: Must. Source: BR-SUB-02.
- Given 8 employees after a downgrade to Starter, when the owner invites another, then it is blocked, and the 8 keep access.

**FR-SUB-10. Counter reset.** Monthly usage counters shall reset at 00:00 on the 1st of each month in the owner's time zone (D-218).
Priority: Must. Source: BR-SUB-02.
- Given the 1st at 00:00 Cairo time, when the reset job runs, then the Egyptian organization's trip counter is zero.

**FR-SUB-11. Plan service failure.** If the plan service is unavailable, actions that move money or consume a limit shall fail closed; read-only screens stay available (D-224).
Priority: Must. Source: BR-SUB-02.
- Given the plan service is down, when an organization publishes a trip, then publishing is refused with a service-unavailable message.

### 5.15 Check-in and check-out (CHK)

**FR-CHK-01. Check-in at boarding.** The moderator shall check each passenger in by scanning the booking code or searching the manifest.
Priority: Must. Source: BR-CHK-01.
- Given passenger P on vehicle 2, when moderator 2 checks P in, then the event is recorded and P shows on board.

**FR-CHK-02. Vehicle match.** A check-in shall be accepted only for the passenger's assigned vehicle. Otherwise the app names the correct vehicle number only, without other names or moderators.
Priority: Must. Source: BR-CHK-02; BRL-09.
- Given P is on vehicle 2, when moderator 1 scans P, then it is refused and "vehicle 2" is shown.

**FR-CHK-03. Event record.** Each event shall store event time, server receipt time, moderator, vehicle, stop and location where permitted, and appear in the passenger movement log.
Priority: Must. Source: BR-CHK-03.
- Given a check-in, when the log is read, then it shows time, moderator, vehicle and location if permitted.

**FR-CHK-04. Check-out at stops.** The moderator shall check passengers out at stops and the destination. A passenger checked out at an intermediate stop can be checked in again on the same vehicle (proposed, workflows 16.2).
Priority: Must. Source: BR-CHK-01.
- Given P checked out at stop 3, when P re-boards, then a new check-in is accepted on the same vehicle.

**FR-CHK-05. Offline queue.** Without connection, events shall queue on the device (up to 1,000; warning at 800), retry every 30 seconds, and reach the server in order with original timestamps (D-117). Duplicate replays are ignored by client event ID.
Priority: Must. Source: BR-CHK-03.
- Given 3 minutes offline and five check-ins, when the connection returns, then five events arrive in order with original times.

**FR-CHK-06. Incident reporting.** Moderators shall report incidents with a category (safety, medical, vehicle fault, lost traveler, conduct, other; D-331), description, photo and location. Reports reach the organization's operations view in real time.
Priority: Should. Source: BR-CHK-05.
- Given an incident is submitted, when saved, then an operations user sees it without refreshing.

**FR-CHK-07. Occupancy.** Each vehicle shall show checked-in passengers against seats, updated in real time.
Priority: Must. Source: BR-CHK-01; BR-TRK-01.
- Given 40 of 48 checked in, when operations opens the trip, then vehicle 2 shows 40 of 48.

### 5.16 Moderator app (MOD)

**FR-MOD-01. Vehicle-scoped access.** A moderator shall see and act only on the assigned vehicle.
Priority: Must. Source: BR-CHK-04.
- Given moderator A on vehicle 1 calls check-in for vehicle 2, when processed, then it is refused as forbidden.

**FR-MOD-02. End-of-trip report.** The moderator shall submit a report with the final headcount; a headcount that differs from the checked-in count requires a reason.
Priority: Must. Source: BR-MOD-01.
- Given 47 checked in and 46 entered without a reason, when submitted, then it is refused.

**FR-MOD-03. Access revocation.** Replacing a moderator, or 24 hours after the trip ends, shall end access at once and show an access-ended screen.
Priority: Must. Source: BR-CHK-04; BRL-09.
- Given A is replaced on vehicle 1, when saved, then A's session shows access ended and A's requests are refused.

**FR-MOD-04. Installable app.** The moderator app shall be an installable web app that opens to the vehicle view.
Priority: Must. Source: BR-CHK-03.
- Given the app is added to the home screen, when opened, then it opens to the vehicle view.

**FR-MOD-05. Moderator sign-in.** Moderators shall sign in with phone and a staff code. The code is valid until 24 hours after the trip ends, on one device at a time (D-107, D-108). Moderator plan is [OPEN: OD-06].
Priority: Must. Source: BR-AUTH-04.
- Given a second device signs in with the same code, when it succeeds, then the first device's session ends.

**FR-MOD-06. Report lock.** A submitted report shall be read-only for the moderator; the organization can add a note.
Priority: Must. Source: BR-MOD-01.
- Given a submitted report, when the moderator edits it, then it is refused.

### 5.17 Live tracking (TRK)

**FR-TRK-01. Live vehicle location.** Operations staff with trip access shall see each vehicle's live position, updated within 500 ms of arrival.
Priority: Must. Source: BR-TRK-01.
- Given a new GPS point for vehicle 2, when the map is open, then vehicle 2 moves within 500 ms.

**FR-TRK-02. Location collection.** The driver app (or the moderator app as fallback) shall send a point every 5 to 10 seconds only while the trip is In Progress, batching up to 10 points per request and buffering up to 60 minutes offline (D-34, D-121).
Priority: Must. Source: BR-TRK-01; ASM-03.
- Given the trip has not started, when the device is online, then no points are sent.

**FR-TRK-03. Passenger vehicle view.** Operations shall see which vehicle each checked-in passenger is in.
Priority: Must. Source: BR-TRK-01.
- Given P checked in to vehicle 2 and not out, when operations looks up P, then vehicle 2 shows.

**FR-TRK-04. Per-trip child tracking switch.** Parent live tracking shall be available only where the trip's child tracking switch is on (FR-PAR-03).
Priority: Must. Source: BR-PAR-04.
- Given the switch is off, when a parent opens the tracker, then no live position shows.

**FR-TRK-05. Tracking data scope.** Positions and passenger movement shall be visible only to the owning organization's staff with operations access, admins, and eligible linked parents.
Priority: Must. Source: BRL-01; BRL-29.
- Given another organization's user requests positions, when processed, then the response is not found.

**FR-TRK-06. End of tracking.** When the trip is marked ended, collection shall stop and an end event go to linked parents and operations.
Priority: Must. Source: BR-TRK-01; BR-PAR-03.
- Given the trip is ended, when a new point arrives, then it is rejected.

**FR-TRK-07. No live position for travelers.** Travelers shall never see a vehicle's live position or ETA; their trip-day view shows the vehicle, seat, check-in status and the schedule (D-317).
Priority: Must. Source: BRL-29.
- Given a traveler on an In Progress trip, when they open trip day, then no map position or distance is shown.

### 5.18 Parent tracker (PAR)

**FR-PAR-01. Linking a child.** A parent shall link a child passenger to a booking with a valid booking reference, the child's name as on the booking, and a guardianship declaration recorded as consent (D-126). Invalid or mismatched input is refused with a generic message.
Priority: Must. Source: BR-PAR-02; BRL-10.
- Given a valid reference and matching name, when submitted with the declaration, then the child is linked.
- Given an invalid reference, when submitted, then it is refused without revealing booking details.

**FR-PAR-02. Parent view scope.** A parent shall see only linked children's vehicle, seat, check-in status and programs.
Priority: Must. Source: BR-PAR-01; BRL-10.
- Given two linked children and another passenger on a different booking, when the tracker opens, then only the two children show.

**FR-PAR-03. Eligibility.** Child tracking shall be available where the parent is on Explorer or VIP and the organization is on Growth or Enterprise with the trip switch on (OD-05, proposed). Live position shows only while the trip is In Progress (D-317). The blocked state says whether the parent's plan or the organization's setting is the reason; only the plan case offers an upgrade.
Priority: Must. Source: BR-PAR-04.
- Given a Free parent, when linking, then an upgrade prompt shows.
- Given the organization is on Starter, when the parent opens the trip, then tracking is unavailable with no upgrade offered.

**FR-PAR-04. Parent notifications.** Parents shall be notified at boarding, trip start, each stop and trip end.
Priority: Must. Source: BR-PAR-03; BR-NTF-01.
- Given trip start, when published, then each linked parent is notified in their language.

**FR-PAR-05. Child-data restrictions.** Child data shall not be used for marketing, advertising, analytics events or model training and shall not appear in any marketing export or audience.
Priority: Must. Source: BR-PAR-01; BRL-10.
- Given a marketing audience is built, when generated, then no child-linked record is included.

**FR-PAR-06. Link ends with the booking.** A child link shall end when its booking is cancelled or refunded.
Priority: Must. Source: BRL-10; BR-PAR-02.
- Given a refunded booking, when saved, then the parent no longer sees the child's status.

**FR-PAR-07. Child-safety reporting.** Parents and organization staff shall report a child-safety concern, creating a case routed to the configured escalation contact [OPEN: contacts and procedure, D-68].
Priority: Must. Source: BR-CHK-05; BR-PAR-01.
- Given a parent report, when saved, then a case exists and the escalation contact is notified.

### 5.19 Media and albums (MED)

**FR-MED-01. Album creation window.** A photographer shall create albums for a trip only after it is Completed and within 14 days; albums archive 30 days after the window closes (D-267, D-268).
Priority: Must. Source: BR-MED-01.
- Given an In Progress trip, when an album is created, then it is refused.

**FR-MED-02. Resumable batch upload.** Photos shall upload directly to storage by presigned URL in batches; an interrupted batch resumes without re-uploading finished files. 95 percent of photos finish processing within 5 minutes (D-274).
Priority: Must. Source: BR-MED-01.
- Given 300 photos interrupted after 120, when resumed, then the 120 are not uploaded again.

**FR-MED-03. Privacy modes by plan.** An album shall be public to trip participants, shared by link (Studio, Pro) or for selected users (Pro).
Priority: Must. Source: BR-MED-02; BR-SUB-02.
- Given a Basic photographer, when privacy options show, then only public is offered.

**FR-MED-04. Shared links.** A shared link shall use a 32-character token and be revocable; a revoked link returns not found.
Priority: Must. Source: BR-MED-02.
- Given a revoked link, when opened, then the response is not found.

**FR-MED-05. Selected-user access.** A selected-users album shall be visible only to users on its list.
Priority: Must. Source: BR-MED-02.
- Given a traveler not on the list, when they open the trip's albums, then the album is not shown.

**FR-MED-06. Watermarking.** Studio and Pro photographers shall watermark public copies at the bottom right, inset 3 percent of width, 30 percent opacity (D-275). Originals are unchanged.
Priority: Should. Source: BR-MED-03.
- Given watermarking on, when a traveler views a photo, then the watermarked copy is served.

**FR-MED-07. Traveler access and download.** Signed-in travelers who took the trip shall view allowed albums. Public albums allow download of the watermarked or medium copy. Shared-link and selected-user albums show no download button. Downloads require sign-in and are logged (D-264, D-266).
Priority: Must. Source: BR-MED-04.
- Given a shared-link album, when opened, then no download button shows.

**FR-MED-08. Albums per trip by plan.** Albums per trip shall not exceed the plan limit (Basic 1, Studio 5, Pro unlimited).
Priority: Must. Source: BR-SUB-02; BR-MED-01.
- Given a Basic photographer with one album, when creating a second, then it is blocked with an upgrade prompt.

### 5.20 Photographer studio (PHO)

**FR-PHO-01. Portfolio.** Photographers shall manage the portfolio within the plan allowance (Basic 10, Studio 50, Pro unlimited).
Priority: Must. Source: BR-PHO-01; BR-ONB-06.
- Given 10 images on Basic, when another is uploaded, then it is blocked with an upgrade prompt.

**FR-PHO-02. Rates and availability.** Photographers shall set rates and mark available or unavailable dates and ranges (no weekly rules, D-89). Deals for unavailable dates are refused.
Priority: Must. Source: BR-PHO-01.
- Given a date marked unavailable, when an organization proposes a deal for it, then the proposal is refused.

**FR-PHO-03. Earnings.** Photographers shall see earnings, statements and payouts; commission shows as a ledger line without a printed rate until OD-15 is decided.
Priority: Must. Source: BR-PHO-01; BR-ACC-03.
- Given a payout was made, when earnings open, then it shows with amount and date.

**FR-PHO-04. Active bookings by plan.** Bookings per month shall follow the plan (Basic 3, Studio 10, Pro unlimited).
Priority: Must. Source: BR-SUB-02.
- Given 3 bookings this month on Basic, when a fourth deal is finalized, then it is blocked with an upgrade prompt.

**FR-PHO-05. Photographer coupons.** Photographers shall create coupons that apply only to bookings where they are assigned.
Priority: Must. Source: BR-CPN-01; BRL-03.
- Given another photographer's booking, when the coupon is applied, then it is refused.

**FR-PHO-06. Visibility and badge.** Profile visibility and the verified badge shall follow the plan; Basic shows no badge.
Priority: Should. Source: BR-SUB-02.
- Given a Basic photographer, when the profile is viewed, then no badge shows.

### 5.21 Driver app and lab tests (DRV)

**FR-DRV-01. Driver work area.** A driver shall see only their own assignments; an organization-owned driver only that organization's trips.
Priority: Must. Source: BR-ORG-03; BR-STF-01.
- Given an own driver of organization A, when trips load, then only A's trips show.

**FR-DRV-02. Deal responses.** A platform driver shall accept, counter or reject deal requests after seeing trip, route, date, vehicle and pay.
Priority: Must. Source: BR-STF-06; BR-STF-03.
- Given a counter, when sent, then the state is Countered by Provider and the organization is notified.

**FR-DRV-03. Lab-test pause.** When a lab test is requested, the driver shall be paused at once. The app allows only: book the lab, upload the result, contact support, sign out. Open deals end with reason "provider paused"; upcoming assignments return to their organizers, who are notified to reassign.
Priority: Must. Source: BRL-17; BR-ADM-03.
- Given a requested test, when the driver opens the app, then only the four actions are available.

**FR-DRV-04. Lab deadline and result.** The deadline shall default to 3 days from request; admins may set 1 to 7 days (D-21). Results come from the partner lab, or by driver upload as fallback.
Priority: Must. Source: BR-ADM-03; BRL-16; OD-24.
- Given 3 days pass with no result, when the deadline passes, then the test is Missed and the driver suspended.

**FR-DRV-05. Admin validation.** Admins shall mark each result Passed or Failed. Late results are reviewed within 2 working days (D-243). A pass unpauses the driver; lifting a suspension early needs a pass and a second admin (D-239). A failed driver stays paused; a second failure within 12 months closes the account (D-242).
Priority: Must. Source: BR-ADM-03.
- Given a Passed result, when saved, then the driver is unpaused and the change audited.
- Given a second failure within 12 months, when saved, then the driver account is closed.

**FR-DRV-06. Missed-deadline suspension.** A missed deadline shall suspend the driver; a suspension over 30 days closes the driver account.
Priority: Must. Source: BRL-16.
- Given a suspension of 31 days, when the daily job runs, then the account is closed.

**FR-DRV-07. Earnings and payouts.** Drivers shall see earnings and receive weekly payouts on Mondays at 06:00. While paused, completed trips are paid and uncompleted trips are held (D-28, D-175).
Priority: Must. Source: BR-ACC-03.
- Given a weekly run, when it completes, then the week's payout shows in earnings.

**FR-DRV-08. Driver availability.** Drivers shall mark available or unavailable dates and ranges (no weekly rules, D-89); deals that conflict with an existing assignment show a conflict.
Priority: Must. Source: BR-STF-06.
- Given an existing assignment on 10 March, when another deal for that date is opened, then a conflict warning shows.

### 5.22 Admin console (ADM)

**FR-ADM-01. Account administration.** Admins shall search any account, suspend, reinstate, reset credentials and review deletion and data-copy requests with their due dates.
Priority: Must. Source: BR-ADM-02; BR-AUTH-07.
- Given an open deletion request, when the account opens, then the grace end date shows.

**FR-ADM-02. Admin staff.** A Super admin shall invite admins (link valid 72 hours, D-50) with a role: Super admin, Operations or Finance (D-49). New admins set up the authenticator on first sign-in before the console opens.
Priority: Must. Source: BR-AUTH-04; BRL-21; OD-12.
- Given a new admin, when they first sign in, then authenticator setup is required.

**FR-ADM-03. Configuration.** Admins shall manage plans, coupons, templates, feature flags and markets. Changes apply at once and are audited.
Priority: Must. Source: BR-ADM-06; BRL-19.
- Given a flag turned off, when saved, then the feature hides for all users and is audited.

**FR-ADM-04. Dispute outcomes and refunds.** Admins shall record disputes, decide outcomes and issue refunds from the dispute, within or outside policy (FR-PAY-04).
Priority: Must. Source: BR-ADM-04; BRL-11.
- Given a dispute resolved with a refund, when saved, then the refund links to the dispute and the booking.

**FR-ADM-05. Immutable audit log.** Every admin action and permission change shall be written to an audit log no role can edit or delete, kept 7 years.
Priority: Must. Source: BR-ADM-05; BRL-19.
- Given an audit entry, when anyone edits it, then the change is refused.

**FR-ADM-06. Platform-wide coupons.** Admins shall create coupons for any trip, optionally limited by market, and revoke any coupon.
Priority: Must. Source: BR-CPN-01.
- Given a platform coupon, when a traveler applies it to any trip, then it is accepted if other rules pass.

**FR-ADM-07. Abuse flags.** Admins shall see abuse flags with the rule that raised each and its review due date.
Priority: Should. Source: BR-CPN-05.
- Given a flagged coupon, when the list opens, then the flag and rule show.

**FR-ADM-08. Dispute handling.** Disputes shall have a type (booking, payment, refund, supplier conduct, organization conduct, other) and the states in section 7.10. They are acknowledged within 2 working days and resolved within 10; information requests are answered within 5 working days. Up to 5 files of 10 MB can be attached (D-32, D-254, D-256, D-259).
Priority: Must. Source: BR-ADM-07.
- Given a dispute open for 3 working days without acknowledgment, when the queue opens, then it is flagged overdue.

**FR-ADM-09. Lab-test requests.** Admins shall request a lab test for any approved driver at any time, choose the test type and deadline, and see all tests by state and deadline. Lab tests do not apply to photographers (D-98).
Priority: Must. Source: BR-ADM-03.
- Given an approved driver, when an admin requests a test, then the driver is paused and the test shows in the queue with its deadline.

### 5.23 Accounting and settlements (ACC)

**FR-ACC-01. Double-entry ledger.** Every financial event shall be written as a balanced ledger transaction.
Priority: Must. Source: BR-ACC-05; BRL-18.
- Given a confirmed booking, when the ledger is checked, then its entries balance to zero.

**FR-ACC-02. Organization accounting per trip.** Organizations shall see per trip base revenue, program revenue, coupon impact, supplier costs, commission and gateway fees, at plan depth.
Priority: Must. Source: BR-ACC-01.
- Given a trip with all event types, when trip accounting opens, then each line shows its total.

**FR-ACC-03. Supplier earnings and payouts.** The platform shall compute supplier earnings and create weekly payout runs on Mondays at 06:00 (D-28). A negative balance is netted against the next payout in the same currency, and no payout runs while it is negative (D-173).
Priority: Must. Source: BR-ACC-03.
- Given a supplier with a negative balance, when the run executes, then no payout is made and the balance carries forward.

**FR-ACC-04. Platform financial view.** Admins shall see subscription revenue, commission, deal audit, coupon redemptions, disputes and payouts, by market and month.
Priority: Must. Source: BR-ACC-02.
- Given a month, when the view opens, then all six areas show totals.

**FR-ACC-05. Settlements.** A settlement shall open when a trip is Completed and close when every payout is made and no dispute is open (D-174). Each line names the paying party, receiving party, reason and amount.
Priority: Must. Source: BR-ACC-06.
- Given a completed trip with an open dispute, when all payouts are made, then the settlement stays open until the dispute closes.

**FR-ACC-06. Exports by plan.** CSV and PDF exports shall follow the plan; admin system-wide exports are CSV only; files expire after 7 days and are ready within 15 minutes (D-249, D-177).
Priority: Must. Source: BR-ACC-04; BR-SUB-02.
- Given a Starter organization, when it requests a full export, then it is refused with an upgrade prompt.

**FR-ACC-07. Reconciled exports.** Every export shall match the ledger for the same period and filters.
Priority: Must. Source: BRL-18; BR-ACC-05.
- Given a monthly revenue export, when compared with the ledger, then totals match.

**FR-ACC-08. Invoices across all parties.** The platform shall issue and track invoices for every party pair: organization to traveler (B2C or B2B), supplier to organization for deals (issued by the platform on the supplier's behalf, proposed), platform to organization or supplier for commission, and platform to plan holders for subscriptions.
Priority: Must. Source: BR-ACC-07; BRL-28.
- Given a finalized deal is settled, when the settlement closes, then a deal invoice and a commission invoice exist and both appear in the ledger.

**FR-ACC-09. Daily reconciliation and finance digest.** Reconciliation shall run daily (FR-PAY-05). A finance exceptions digest goes out daily at 08:00 Cairo time (D-176). Dashboards warn when data is more than 26 hours old (D-250).
Priority: Must. Source: BR-ACC-05.
- Given reconciliation found 2 exceptions, when 08:00 Cairo arrives, then Finance admins get the digest listing both.

**FR-ACC-10. Archive after settlement.** A trip shall archive 30 days after Completed, and only once its settlement is closed (BRL-25).
Priority: Must. Source: BR-ACC-06; BRL-25.
- Given a trip completed 40 days ago with an open settlement, when the archive job runs, then the trip is not archived.

### 5.24 Notifications (NTF)

**FR-NTF-01. Channels by preference.** Each notification shall go on the channels the user enabled for its category: email, SMS, WhatsApp, push, in-app. Account security messages cannot be disabled.
Priority: Must. Source: BR-NTF-01.
- Given push is off for promotions, when a promotion is sent, then no push is delivered.

**FR-NTF-02. Language and layout.** Notifications shall use the user's language, right to left for Arabic.
Priority: Must. Source: BR-NTF-02.
- Given an Arabic user, when a confirmation email is sent, then it is in Arabic and right to left.

**FR-NTF-03. Quiet hours.** SMS and push shall not be sent between 22:00 and 07:00 recipient local time unless urgent (D-289 list). Deferred messages go at 07:00; trip-day events older than 2 hours are dropped (D-290).
Priority: Should. Source: BR-NTF-03.
- Given a non-urgent push due at 22:30, when processed, then it is sent at 07:00.

**FR-NTF-04. Versioned templates.** Text shall come from versioned templates by category, channel and language, managed by admins.
Priority: Must. Source: BR-ADM-06.
- Given a template edit, when saved, then new messages use it and sent ones are unchanged.

**FR-NTF-05. Delivery records and retries.** Each delivery shall record its status by channel. Failures retry 3 times with exponential backoff (architecture 13.2).
Priority: Must. Source: BR-NTF-01.
- Given an SMS failure, when recorded, then a retry is scheduled.

**FR-NTF-06. No duplicate delivery.** One event shall produce at most one message per channel per recipient.
Priority: Must. Source: BR-NTF-01.
- Given a duplicated trip-started event, when processed, then each parent gets one push.

**FR-NTF-07. Trip reminders and change notices.** Travelers shall get a reminder 24 hours before departure by push and email, plus SMS if enabled (D-27). Vehicle, driver or time changes are notified within 1 hour (D-186).
Priority: Must. Source: BR-NTF-01.
- Given a trip departing in 24 hours, when the hourly job runs, then each confirmed traveler gets the reminder once.

### 5.25 Traveler profile and history (TRV)

**FR-TRV-01. Profile and preferences.** Travelers shall manage profile, language and notification preferences; a language change applies at once.
Priority: Must. Source: BR-NTF-02; BR-NTF-01.
- Given a switch to Arabic, when saved, then the interface and later notifications are in Arabic.

**FR-TRV-02. Trip visibility and early access.** Travelers shall see public trips and trips targeted to them. VIP travelers see trips during the early-access window before others [OPEN: window, D-146, OD-09].
Priority: Must. Source: BR-TRIP-02.
- Given an early-access window, when a Free traveler browses, then the trip is hidden until the window closes.

**FR-TRV-03. History and receipts by plan.** History and receipts shall follow the plan: basic (12 months, proposed) on Free, full on Explorer, full with export on VIP.
Priority: Must. Source: BR-BKG-05; BR-SUB-02.
- Given a Free traveler, when a receipt opens, then export is not offered.

**FR-TRV-04. Program discounts.** Explorer travelers get 5 percent and VIP 10 percent off program prices; base trip prices are not discounted.
Priority: Must. Source: BR-PRG-03.
- Given VIP and a program of 200 EGP, when the summary shows, then the line is 180 EGP.

**FR-TRV-05. Plan changes.** Traveler upgrades take effect at once with proration; downgrades at cycle end. Plans are paid on the gateway's browser page, not by in-app purchase.
Priority: Must. Source: BRL-13.
- Given an Explorer downgrade mid-cycle, when saved, then benefits stay to cycle end.

**FR-TRV-06. Supplier application keeps traveler access.** A traveler shall apply for a supplier role while keeping full traveler access, pending or after rejection.
Priority: Must. Source: BR-AUTH-05; BRL-06.
- Given a pending photographer application, when the traveler browses, then booking still works.

**FR-TRV-07. Data copy and deletion.** Travelers shall request a copy of their data (delivered within 30 days of verification, D-162) and account deletion (FR-AUTH-09).
Priority: Must. Source: BR-AUTH-08; BRL-15.
- Given a data-copy request, when saved, then it shows in the admin queue with a due date 30 days out.

### 5.26 Markets (MKT)

**FR-MKT-01. Market configuration.** Admins shall configure each market: currency, payment methods and gateways for bookings and for plans, tax rates and invoice rules, default time zone, commission rate (placeholder 10 percent), plan prices and status (draft, live, paused).
Priority: Must. Source: BR-MKT-01.
- Given the Egypt market, when an admin adds Fawry for bookings, then Egyptian checkouts offer it.

**FR-MKT-02. Market from country.** An account's market shall come from its registration country; an organization's market decides its trips' currency, methods and tax rules (BRL-27). Changing an organization's country after approval requires admin action.
Priority: Must. Source: BR-MKT-02; BRL-27.
- Given an organization registered in Egypt, when it creates a trip, then the trip's currency is EGP.

**FR-MKT-03. Cross-market browsing.** Travelers shall see trips from every live market with prices in each trip's currency, and pay with that market's methods.
Priority: Must. Source: BR-MKT-03.
- Given an Egyptian traveler opens a Saudi trip, when they reach payment, then the Saudi methods and SAR amount are shown.

**FR-MKT-04. Market switch.** Trips in a paused or draft market shall not be publishable; published trips stay bookable unless admins pause bookings too.
Priority: Should. Source: BR-MKT-04.
- Given the UAE market is draft, when a UAE organization publishes, then publishing is refused.

**FR-MKT-05. Rounding and display.** Amounts shall be computed in minor units, rounded half up per line, with totals as the sum of rounded lines (D-157), and displayed with the currency code and Western digits.
Priority: Must. Source: BR-MKT-01.
- Given three lines of 33.335 EGP, when totaled, then each line rounds to 33.34 and the total is 100.02.

### 5.27 Analytics (ANL)

**FR-ANL-01. Organization analytics.** Organizations shall see analytics by trip (views, conversion, bookings, revenue, occupancy, programs), by page (trip and organization page traffic and sources), by customer (new and returning travelers, repeat rate) and by provider (deals, costs, response time). Growth gets basic, Enterprise full; Starter none.
Priority: Must. Source: BR-ANL-01.
- Given a Growth organization, when it opens trip analytics, then views, bookings and revenue show for the selected range.

**FR-ANL-02. Supplier analytics.** Drivers and photographers shall see bookings, earnings and deal response analytics at their plan's report depth.
Priority: Should. Source: BR-ANL-02.
- Given a Basic driver, when analytics open, then the basic report shows without export.

**FR-ANL-03. Admin analytics.** Admins shall see platform analytics by market, organization, role and plan.
Priority: Must. Source: BR-ANL-03.
- Given a market filter, when applied, then all charts show that market only.

**FR-ANL-04. Ranges, freshness and export.** Analytics shall offer 7, 30 and 90-day presets and a custom range, lag the source by no more than 5 minutes, and export within 15 minutes (D-177, D-281).
Priority: Should. Source: BR-ANL-04.
- Given a booking confirmed at 10:00, when analytics refresh at 10:05, then it is counted.

**FR-ANL-05. Privacy in analytics.** Analytics events shall exclude child-linked records and personal identifiers; page analytics use an anonymous identifier for visitors.
Priority: Must. Source: BR-PAR-01; BR-ANL-01.
- Given a parent views the tracker, when analytics events are written, then no child identifier is stored.

### 5.28 Public API (API)

**FR-API-01. Public API.** Organizations and partners shall access trips and bookings through a versioned public API documented at docs.trips-enjoy.com, authenticated with organization API keys scoped by permission, and rate-limited per key.
Priority: Could. Source: BR-API-01.
- Given a revoked API key, when used, then the request is refused as unauthenticated.

## 6. Data requirements

### 6.1 Core entities

ERD.md holds the tables, columns, keys, lookup types and RLS paths. This table states each entity's purpose, owner scope and retention. Scope means who owns the rows: tenant (one organization), platform, account (one person) or supplier.

| Entity | Purpose | Scope | ERD tables | Retention |
|---|---|---|---|---|
| Account and roles | Login identity and held roles | Account | users, user_roles, auth_factors, sessions, devices | Until deletion, then section 6.4 |
| Market | Country configuration | Platform | markets, market_payment_methods, tax_rates | Life of platform |
| Organization | Tenant root and settings | Tenant | organizations | Life of tenant, then financial retention |
| Member and permissions | Staff access per organization | Tenant | organization_members, member_permissions, invitations | Kept with audit log |
| Application | Organization and supplier applications | Platform | applications, application_documents, application_decisions, appeals | [OPEN: rejected applications and documents] |
| Supplier profiles | Driver and photographer details | Platform, or tenant for organization-owned | driver_profiles, photographer_profiles, provider_vehicles, portfolio_items, availability_slots | Life of account |
| Lab test | Driver lab-test record | Platform | lab_tests, lab_partners | Per market legal review |
| Vehicle type and vehicle | Seat layouts and fleet | Tenant | vehicle_types, vehicle_type_seats, vehicles | Life of tenant |
| Trip | One organized trip with stops, audience, vehicles and staff | Tenant | trips, trip_stops, trip_audiences, trip_vehicles, trip_staff_assignments | 5 years after account deletion |
| Program | Activities and tiers | Tenant | programs, program_tiers | As trip |
| Booking | Reservation, passengers, programs, seats, remedies | Tenant and traveler | bookings, booking_passengers, booking_programs, trip_seats, booking_remedies | 7 years (financial) |
| Deal | Negotiation and offers | Tenant and supplier | deals, deal_offers | 7 years (financial) |
| Payment and refund | Gateway payments, attempts, refunds | Tenant or account | payments, payment_attempts, refunds | 7 years |
| Coupon | Codes, scope, redemptions, flags | Creator scope | coupons, coupon_programs, coupon_audiences, coupon_redemptions, abuse_flags | Redemptions 7 years |
| Plan and subscription | Catalog, prices, subscriptions, charges, overrides, counters | Platform and owner | plans, plan_limits, plan_prices, subscriptions, subscription_charges, plan_overrides, usage_counters | Charges 7 years |
| Check-in and tracking | Boarding events, positions, incidents, reports | Tenant | checkin_events, tracking_points, incidents, trip_reports | Check-in 1 year; GPS 90 days |
| Child link and safety case | Parent links and safety cases | Parent and tenant | child_links, safety_cases | Child trip history 1 year |
| Album and photo | Trip media and access | Photographer and trip | albums, photos, album_shared_links, album_members, download_logs | Albums until deleted; download logs 1 year |
| Ledger | Double-entry record | Platform and tenant | ledger_accounts, ledger_transactions, ledger_entries | 7 years |
| Invoice | B2C, B2B and credit notes for all parties | Issuer | invoices, invoice_lines | 7 years |
| Payout and settlement | Weekly runs and per-trip settlements | Supplier, tenant | payout_runs, payouts, payout_methods, settlements, settlement_lines | 7 years |
| Reconciliation | Gateway differences | Platform | reconciliation_exceptions | 7 years |
| Dispute | Disputes and files | Platform | disputes, dispute_files | 7 years |
| Notification | Preferences, messages, deliveries, templates | Account and platform | notification_preferences, notifications, notification_deliveries, notification_templates | Delivery logs [OPEN] |
| Analytics | Events and daily rollups | Tenant and platform | analytics_events, analytics_daily | Events 13 months (proposed); rollups life of tenant |
| Consent and privacy | Consents and data requests | Account | consents, privacy_requests | Life of account plus 7 years (proposed) |
| Audit | Admin actions and permission changes | Platform | audit_logs | 7 years |

### 6.2 Data isolation

| ID | Requirement | Priority |
|---|---|---|
| DAT-01 | Every tenant row carries `organization_id`. Reads and writes are limited to the tenant at the database (RLS), repository and API layers. | Must |
| DAT-02 | No user-facing query joins across tenants. Platform-wide reads run under the admin or platform repository and are logged. | Must |
| DAT-03 | When deletion becomes permanent, personal fields are removed or replaced with "Deleted user", except records kept under section 6.4. Trip records stay 5 years. Future bookings are cancelled and refunded; remaining earnings are paid on the next payout date. | Must |
| DAT-04 | Identity documents and lab results are in a restricted bucket visible only to admins; every open is audited. | Must |
| DAT-05 | Phone numbers, ID and license numbers, and payout details are encrypted at column level. | Must |
| DAT-06 | Money is stored as minor units plus currency code; rounding follows FR-MKT-05. | Must |
| DAT-10 | Every API and file path uses the public UUID; internal numeric IDs never leave the backend. | Must |

### 6.3 Soft delete, immutability and deletion

| ID | Requirement | Priority |
|---|---|---|
| DAT-07 | Suspended accounts, cancelled bookings and withdrawn applications are soft-deleted or kept, hidden from user views and visible to admins. | Must |
| DAT-08 | Ledger, audit, deal offer, check-in, tracking, consent and outbox rows are insert-only. Corrections are reversing rows. | Must |
| DAT-09 | A deleted account keeps its trip and financial records for the periods in 6.4, with personal fields removed where the law allows. | Must |

### 6.4 Retention

| Data | Retention | Basis |
|---|---|---|
| Account during deletion grace | 30 days; sign-in restores | BRL-15 |
| Account personal data after deletion | Removed, except records below | BRL-15 |
| Trip records | 5 years after account deletion | BRL-15 |
| Bookings, payments, invoices, ledger, payouts, deals | 7 years | OD-04 (pending legal review) |
| Audit log | 7 years, object-locked | D-04, BRD 8.5 |
| Check-in and check-out events | 1 year | D-45 |
| Raw GPS points | 90 days, then an hourly summary is kept | Architecture 18.4 |
| Child trip history | 1 year | D-135 |
| Album download logs | 1 year | D-271 |
| Verification codes | 5 minutes | BRL-20 |
| Idempotency keys | 24 hours | NFR-SEC-06 |
| Identity documents and lab results | [OPEN: per market legal review] | BRD 8.2 |
| Notification delivery logs | [OPEN] | Not set |
| Application logs | 7 years in archive storage | Architecture 22.1 |
| Database backups | Daily snapshots 35 days; point-in-time 7 days; cross-region copies 30 days | Architecture 22.1 |

## 7. State machines

Transitions not listed are refused. Guards marked (proposed) need product confirmation.

### 7.1 Trip lifecycle

States: Draft, Published, Negotiating, Ready, Filling Up, Sold Out, In Progress, Partially Completed, Completed, Post-Processing, Archived, Cancelled, Refunds Issued.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Organization creates a trip | Active plan; market live | Draft |
| Draft | Publish publicly or to targets | Wizard complete; active-trip limit not reached | Published |
| Published | A platform deal is started | At least one slot staffed through a platform supplier | Negotiating |
| Negotiating | Last platform deal finalized | Every vehicle has driver and moderator (BRL-07) | Ready |
| Published | Last vehicle staffed with own staff | BRL-07 | Ready |
| Published, Negotiating or Ready | Reserved seats reach 80 percent of capacity | D-189 | Filling Up |
| Filling Up | Every vehicle full | | Sold Out |
| Sold Out | A seat is released | | Filling Up |
| Filling Up | Reserved seats fall below 80 percent | (proposed) | Ready, or Published if not yet Ready |
| Ready, Filling Up or Sold Out | First vehicle departs | Trip start time reached; trip is Ready (BRL-07) | In Progress |
| In Progress | Cancellation requested | Not allowed (D-25); incident flow only | In Progress |
| In Progress | Some vehicles ended | At least one ended, others running | Partially Completed |
| In Progress or Partially Completed | Last vehicle ended | Every trip report submitted | Completed |
| Completed | Settlement opens | Automatic | Post-Processing |
| Post-Processing | Archive job | 30 days after Completed and settlement closed (BRL-25) | Archived |
| Draft through Sold Out | Organization or admin cancels | Reason recorded; full refunds queued (BRL-30) | Cancelled |
| Cancelled | All refunds confirmed | Every confirmed booking Refunded | Refunds Issued |
| Refunds Issued | Archive job | 30 days later | Archived |

### 7.2 Booking

States: Cart, Seats Held, Payment Pending, Confirmed, Payment Failed, Checked In, Checked Out, Cancelled, Refund Pending, Refunded.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Traveler starts a booking | Trip bookable; traveler not in deletion grace | Cart |
| Cart | Seats selected or capacity reserved (company-assigned) | Seats free; hold starts; price locked | Seats Held |
| Seats Held | Proceed to payment | Hold active; attempts under 3 | Payment Pending |
| Seats Held | Hold expires | 15 minutes | Cart (cart kept, D-191) |
| Payment Pending | Gateway success | Callback verified | Confirmed |
| Payment Pending | Gateway failure | | Payment Failed |
| Payment Pending | No answer in 30 s | | Payment Pending (checks at 1, 5, 10 min; admin at 15, D-30) |
| Payment Pending (Fawry) | Voucher unpaid at 48 h | D-31 | Cart |
| Payment Failed | Retry | Hold active; attempts under 3 (D-197) | Payment Pending |
| Payment Failed | Hold expires | | Cart |
| Confirmed | Moderator checks passenger in | Vehicle match | Checked In |
| Checked In | Check-out at a stop or destination | | Checked Out |
| Checked Out | Re-boards at a stop | Same vehicle (proposed) | Checked In |
| Confirmed | Traveler cancels | Before departure; BRL-12 | Cancelled |
| Confirmed | Organization cancels trip | BRL-30 | Cancelled |
| Cancelled | Refund sent | Amount above zero | Refund Pending |
| Cancelled | No refund due | Under 24 hours | Cancelled (final) |
| Refund Pending | Gateway confirms | | Refunded |

### 7.3 Seat

States: Available, Held, Reserved, Checked In, Checked Out, Released.

| From | Event | Guard | To |
|---|---|---|---|
| Available | Selected at checkout | Self-selection on, or staff assignment | Held |
| Held | Payment succeeds | Booking Confirmed | Reserved |
| Held | Hold or voucher expires | BRL-08 | Available |
| Reserved | Passenger checks in | Vehicle match | Checked In |
| Checked In | Passenger checks out | | Checked Out |
| Checked Out | Passenger re-boards | (proposed) | Checked In |
| Reserved | Booking cancelled before departure | | Released |
| Released | Seat re-offered | Trip still bookable | Available |

### 7.4 Deal

States: Proposed, Countered by Provider, Countered by Organization, Accepted by One Side, Finalized, Rejected.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Organization or supplier proposes | Slot open; supplier available; plan allows | Proposed |
| Proposed or Countered by Organization | Supplier counters | | Countered by Provider |
| Proposed or Countered by Provider | Organization counters | | Countered by Organization |
| Any open state | One party accepts current terms | | Accepted by One Side |
| Accepted by One Side | Other party accepts | Terms unchanged | Finalized |
| Accepted by One Side | Other party counters | First acceptance void | Countered by that party |
| Any open state | Either party rejects | | Rejected (reason rejected) |
| Any open state | 72 hours since last counter | BRL-26 | Rejected (reason expired) |
| Any open state | Supplier paused for lab test | BRL-17 | Rejected (reason provider paused) |
| Any open state | Trip cancelled | | Rejected (reason trip cancelled) |

### 7.5 Application

States: Draft, Submitted, Pending Review, Changes Requested, Approved, Rejected, Appealed, Closed.

| From | Event | Guard | To |
|---|---|---|---|
| Draft | Applicant submits | Contacts verified per role; required documents present | Submitted |
| Submitted | Enters queue | Automatic | Pending Review |
| Pending Review | Approve | | Approved (plan assigned, tenant or profile created) |
| Pending Review | Request changes | Items and message recorded | Changes Requested |
| Pending Review | Reject | Reason code recorded | Rejected |
| Changes Requested | Resubmit | Within 14 days, or 21 with extension | Pending Review |
| Changes Requested | No response by the due date | OD-32 | Closed |
| Rejected or Closed | Appeal | Within 14 days; one per rejection | Appealed |
| Appealed | Appeal upheld | Within 5 working days | Approved |
| Appealed | Appeal refused | | Rejected (final) |
| Rejected or Closed | Re-apply | 30 days after decision | Submitted (new application) |

### 7.6 Subscription

States: Trial, Active, Grace, Downgraded, Cancelling, Ended.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Plan starts | Trial configured, or payment taken; application approved | Trial or Active |
| Trial | Trial ends, payment succeeds | | Active |
| Trial | Trial ends, payment fails | | Grace |
| Active | Renewal fails | | Grace |
| Grace | Payment succeeds | Retries on days 1, 3, 5, 7 | Active |
| Grace | 7 days pass | OD-29 (current rule D-08) | Downgraded |
| Grace | Owner cancels | No refund (D-223) | Ended |
| Downgraded | Payment succeeds | | Active |
| Active | Upgrade | Payment succeeds | Active (new plan at once) |
| Active | Downgrade | Effective at period end | Active (downgrade scheduled) |
| Active | Cancel | | Cancelling |
| Cancelling | Period ends | | Ended |
| Any | Admin override | Second admin; end date | Active on the override plan; reverts at end date |

### 7.7 Coupon

States: Draft, Active, Paused, Expired, Exhausted, Revoked.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Created | Scope valid; plan count not reached | Draft |
| Draft | Activated or start time reached | | Active |
| Active | Paused by creator | | Paused |
| Paused | Resumed | Window still open | Active |
| Active or Paused | End time passes | | Expired |
| Active | Usage limit reached | | Exhausted |
| Exhausted | A redemption is reversed | Window open | Active |
| Draft, Active, Paused or Exhausted | Revoked by creator or admin | | Revoked |

### 7.8 Lab test

States: Requested, Pending, Result Uploaded, Passed, Failed, Missed.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Admin requests | Approved driver | Requested |
| Requested | Driver notified and paused | BRL-17 | Pending (deadline 1 to 7 days, default 3) |
| Pending | Result received from lab or uploaded | Before deadline | Result Uploaded |
| Pending | Deadline passes | | Missed (driver suspended) |
| Missed | Late result received | Reviewed within 2 working days | Result Uploaded |
| Result Uploaded | Admin passes | | Passed (driver unpaused; early lift from suspension needs a second admin) |
| Result Uploaded | Admin fails | | Failed (driver stays paused; second failure in 12 months closes the account) |
| Missed | Suspension exceeds 30 days | BRL-16 | Account closed |

### 7.9 Settlement

States: Open, Awaiting Payouts, Disputed, Closed.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Trip Completed | | Open (lines computed from the ledger) |
| Open | Payout instructions created | | Awaiting Payouts |
| Open or Awaiting Payouts | Dispute opened on the trip | | Disputed |
| Disputed | Dispute closed | Adjustment lines posted | Awaiting Payouts |
| Awaiting Payouts | Every line paid | No open dispute (D-174) | Closed |

### 7.10 Dispute

States: Open, Information Requested, Under Review, Resolved, Closed.

| From | Event | Guard | To |
|---|---|---|---|
| (none) | Party raises a dispute | Booking, payment or deal reference | Open |
| Open | Admin acknowledges | Within 2 working days | Under Review |
| Under Review | Admin requests information | | Information Requested |
| Information Requested | Party responds | Within 5 working days | Under Review |
| Under Review | Outcome recorded | Within 10 working days of opening | Resolved |
| Resolved | No appeal within 7 days (proposed) | | Closed |

## 8. Non-functional requirements

### 8.1 Performance (NFR-PERF)

| ID | Requirement | Target |
|---|---|---|
| NFR-PERF-01 | API latency per endpoint, server side | p50 under 150 ms; p95 under 500 ms; p99 under 1,500 ms |
| NFR-PERF-02 | Booking flow from trip page to confirmed booking, excluding time on the gateway page | Under 5 seconds |
| NFR-PERF-03 | Trip search | Under 300 ms |
| NFR-PERF-04 | Seat map load | Under 200 ms |
| NFR-PERF-05 | GPS point ingestion | Under 50 ms per point |
| NFR-PERF-06 | Real-time delivery (seat map, tracking, deals, operations) | Under 500 ms from event to client |
| NFR-PERF-07 | Thumbnail from CDN | Under 100 ms |
| NFR-PERF-08 | Dashboard first meaningful paint | Under 2 seconds |
| NFR-PERF-09 | Check-in event reaches the server after boarding | Within 60 seconds, including reconnect after short loss (BRD OBJ-02) |

### 8.2 Security (NFR-SEC)

| ID | Requirement |
|---|---|
| NFR-SEC-01 | TLS 1.3 and HSTS on all traffic; certificate pinning on mobile. |
| NFR-SEC-02 | Passwords hashed with Argon2id; minimum 12 characters; checked against breached-password lists (D-51). |
| NFR-SEC-03 | Lockout after 5 failed sign-ins for 15 minutes, all roles. |
| NFR-SEC-04 | Second factors per role (FR-AUTH-04); OTP rules per BRL-20. |
| NFR-SEC-05 | Access tokens are RS256 JWTs carrying user, active role, organization and token version, validated on every request; 15-minute life; refresh tokens rotate. |
| NFR-SEC-06 | Mutating endpoints accept an idempotency key kept 24 hours. |
| NFR-SEC-07 | Tenant isolation at database (RLS), repository and API layers; cross-tenant test suite passes before each phase. |
| NFR-SEC-08 | Encryption at rest everywhere; column-level encryption for listed identifiers. |
| NFR-SEC-09 | Secrets in a managed store, rotated every 90 days, never in code or logs. |
| NFR-SEC-10 | Least privilege for services, jobs and staff roles. |
| NFR-SEC-11 | Restricted storage for identity documents and lab results. |
| NFR-SEC-12 | No card data is received, logged or stored. |
| NFR-SEC-13 | Rate limits: unauthenticated 60 requests per minute; traveler 600; organization staff 1,200; admin 3,000; moderator check-in 120 per vehicle per minute. Drivers, photographers and parents [OPEN]. |
| NFR-SEC-14 | Security review and SAST, dependency scan and monthly DAST before each phase; annual external penetration test. |
| NFR-SEC-15 | CAPTCHA on registration forms; booking throttle of 5 per traveler per hour unless verified (architecture 17.4). |

### 8.3 Availability (NFR-AVL)

| ID | Requirement | Target |
|---|---|---|
| NFR-AVL-01 | Monthly uptime of traveler app, organization dashboard and booking services | 99.9 percent (proposed) |
| NFR-AVL-02 | Planned maintenance | 01:00 to 03:00 Cairo time, at most 4 hours per month, 72 hours' notice in app and on the status page (D-303) |
| NFR-AVL-03 | Gateway outage loses no confirmed payment | Callbacks queued and replayed; pending state per FR-PAY-08 |

### 8.4 Scalability (NFR-SCL)

| ID | Requirement | Target |
|---|---|---|
| NFR-SCL-01 | Capacity stages | Stage 1 (pilot): 5,000 traveler accounts, 500 trips, 200 concurrent checkouts (D-304). Launch: 10,000 active users, 500 bookings per day. Growth: 100,000 users, 5,000 bookings per day. Scale: 1 million users, 50,000 bookings per day (architecture 19.3). |
| NFR-SCL-02 | Phase 3 pilot | Three-bus trip, about 120 passengers, end to end |
| NFR-SCL-03 | Stateless services | Scale by adding instances |
| NFR-SCL-04 | Load tests before each major release | 1,000 concurrent browsers, 500 concurrent bookings, 100 concurrent payments, 10,000 GPS events per second (architecture 19.4) |

### 8.5 Data (NFR-DAT)

| ID | Requirement |
|---|---|
| NFR-DAT-01 | Tenant isolation per DAT-01. |
| NFR-DAT-02 | Retention per 6.4, enforced by scheduled purge jobs that log each run. |
| NFR-DAT-03 | Data copy and deletion requests tracked to completion. |
| NFR-DAT-04 | Hosting region and residency per market [OPEN: confirm AWS me-central-1 against Egyptian residency rules]. |
| NFR-DAT-05 | Consent records keep type, time and text version for lab tests, background checks, guardianship, child tracking and marketing. |

### 8.6 Observability (NFR-OBS)

| ID | Requirement |
|---|---|
| NFR-OBS-01 | Structured logs carry request, trace, tenant and user IDs; never passwords, codes or card data. |
| NFR-OBS-02 | Per-endpoint rate, errors and latency percentiles. |
| NFR-OBS-03 | OpenTelemetry traces across API, jobs, real-time channel and gateway callbacks. |
| NFR-OBS-04 | Critical alerts page on-call through PagerDuty; warnings go to Slack; low priority goes to an email digest. |
| NFR-OBS-05 | Dashboards: platform health, booking funnel, payments, subscriptions, tracking, database, notification delivery by channel. |
| NFR-OBS-06 | Application logs kept 7 years in archive storage; hot search window [OPEN]. |
| NFR-OBS-07 | Error tracking with Sentry on backend and mobile. |

### 8.7 Internationalization (NFR-I18N)

| ID | Requirement |
|---|---|
| NFR-I18N-01 | Every screen and message in Arabic and English from the first release. |
| NFR-I18N-02 | Full right-to-left mirroring for Arabic. |
| NFR-I18N-03 | Launch currencies EGP (P2), SAR and AED (P5); every amount labeled. |
| NFR-I18N-04 | Western digits 0 to 9 in both languages (D-159); dates follow the language. |
| NFR-I18N-05 | A missing translation fails the build. |

### 8.8 Accessibility (NFR-A11Y)

| ID | Requirement |
|---|---|
| NFR-A11Y-01 | WCAG 2.1 AA on all surfaces (proposed). |
| NFR-A11Y-02 | Every web action is keyboard-operable. |
| NFR-A11Y-03 | The seat map has a list alternative readable by screen readers. |
| NFR-A11Y-04 | AA contrast in light and dark themes, including brand orange text (primary text #C93A00 light, #FF8655 dark). |

### 8.9 Maintainability (NFR-MNT)

| ID | Requirement |
|---|---|
| NFR-MNT-01 | Versioned migrations with expand-contract for breaking changes; rollback documented. |
| NFR-MNT-02 | Every acceptance criterion maps to a test. |
| NFR-MNT-03 | Features ship behind flags per phase. |
| NFR-MNT-04 | Automated test coverage target [OPEN]. |
| NFR-MNT-05 | Major decisions recorded as ADRs (architecture appendix B). |

### 8.10 Compliance (NFR-COM)

| ID | Requirement |
|---|---|
| NFR-COM-01 | Data access and deletion requests answered within 30 days. |
| NFR-COM-02 | Consent per NFR-DAT-05. |
| NFR-COM-03 | Retention per 6.4. |
| NFR-COM-04 | No child data in marketing, advertising, analytics events or model training. |
| NFR-COM-05 | Lab tests and background checks used in a market only after legal review there. |
| NFR-COM-06 | Regimes: Egypt PDPL, Saudi PDPL, UAE data protection law, GDPR where applicable. |
| NFR-COM-07 | ZATCA scope decided before the Saudi launch (OD-10). |
| NFR-COM-08 | PCI DSS SAQ A scope through gateway-hosted payment. |
| NFR-COM-09 | Data processing agreements with every processor (cloud, gateways, messaging, email, monitoring). |

### 8.11 Backup and recovery (NFR-BKP)

| ID | Requirement | Target |
|---|---|---|
| NFR-BKP-01 | Recovery time objective | 4 hours |
| NFR-BKP-02 | Recovery point objective | 15 minutes |
| NFR-BKP-03 | Backups | Daily database snapshots kept 35 days; point-in-time recovery 7 days; daily cross-region copies kept 30 days; media versioning 90 days with cross-region replication; Redis daily snapshot 7 days |
| NFR-BKP-04 | Restore tests | Daily automated restore to staging; quarterly disaster-recovery drill with measured RTO recorded |

## 9. Interface and API requirements

### 9.1 Resource groups

| Resource group | Covers | Main actors | Modules |
|---|---|---|---|
| Auth and accounts | Registration, verification, sessions, roles, privacy | All | AUTH, TRV |
| Applications | Submissions, decisions, appeals | Applicants, admins | ONB |
| Organizations | Settings, members, permissions, integrations, branding | Staff, admins | ORG, EMP |
| Fleet | Vehicle types, vehicles | Staff | FLT |
| Trips | Trips, stops, vehicles, staffing, programs, publishing | Staff; travelers read | TRIP, PRG, STF |
| Deals | Negotiation and offers | Staff, suppliers, admins | NEG |
| Bookings | Cart, holds, checkout, cancellation, remedies, receipts | Travelers; staff and admins read | BKG, SEAT |
| Payments | Payment status, refunds, gateway callbacks | Travelers, admins | PAY |
| Coupons | Coupons, validation, redemptions | Creators, travelers, admins | CPN |
| Plans | Catalog, subscriptions, overrides | Owners, admins | SUB |
| Operations | Manifests, check-in events, incidents, reports | Moderators, staff | CHK, MOD |
| Tracking | Positions, passenger movement, parent view | Staff, parents | TRK, PAR |
| Media | Albums, photos, links | Photographers, travelers | MED, PHO |
| Finance | Ledger views, invoices, payouts, settlements, reconciliation, exports | Staff, suppliers, admins | ACC |
| Markets | Market configuration | Admins | MKT |
| Analytics | Reports and exports | Staff, suppliers, admins | ANL |

### 9.2 Conventions

| ID | Convention | Priority |
|---|---|---|
| IF-01 | URL-versioned REST (/api/v1). Breaking changes only in a new major version. OpenAPI 3.1 generated from code. | Must |
| IF-02 | JSON in UTF-8; ISO 8601 times with offset; money as `{amount_minor, currency}`; resources identified by UUID. | Must |
| IF-03 | One envelope: `data` and `meta` on success; `error` (code, message in caller's language, details) and `meta` on failure; `meta.request_id` always. | Must |
| IF-04 | Error categories: VALIDATION_FAILED 400, UNAUTHENTICATED 401, PLAN_REQUIRED 402, FORBIDDEN 403, NOT_FOUND 404 (also for other tenants' data), CONFLICT 409, BUSINESS_RULE_VIOLATED 422, RATE_LIMITED 429, INTERNAL_ERROR 500, SERVICE_UNAVAILABLE 503. | Must |
| IF-05 | Cursor paging; default 20, maximum 100 per page (D-308). | Must |
| IF-06 | Idempotency-Key on every mutating request; same key and body returns the original result; same key with a different body returns CONFLICT. | Must |
| IF-07 | Deprecated versions and fields return Deprecation and Sunset headers for 90 days before removal. | Must |
| IF-08 | Error codes are stable; messages are translated. | Must |
| IF-09 | Rate-limited responses include Retry-After. | Should |
| IF-10 | Webhook events carry an event ID, type, occurred time and are signed (COM-04). | Must |
| IF-11 | Real-time subscriptions are authorized per channel at subscribe time: trip seats, trip vehicle, family child, deal, organization operations. | Must |
| IF-12 | Another tenant's record returns NOT_FOUND. | Must |

### 9.3 API authentication

First-party clients use the access token from sign-in. Public API clients (FR-API-01, phase 6) use organization API keys with scopes, sent as a bearer key, rate-limited per key.

## 10. Traceability matrix

Generated from the Source line of each functional requirement in section 5 and the BR and BRL tables in BRD.md v1.1.

### 10.1 Business requirements to functional requirements

| Business requirement | BRD priority | Phase | Functional requirements |
|---|---|---|---|
| BR-AUTH-01 | M | P1 | FR-AUTH-01 |
| BR-AUTH-02 | M | P1 | FR-AUTH-02 |
| BR-AUTH-03 | M | P1 | FR-AUTH-03 |
| BR-AUTH-04 | M | P1 | FR-AUTH-04, FR-EMP-06, FR-MOD-05, FR-ADM-02 |
| BR-AUTH-05 | M | P1 | FR-AUTH-06, FR-TRV-06 |
| BR-AUTH-06 | M | P1 | FR-AUTH-07 |
| BR-AUTH-07 | M | P1 | FR-AUTH-08, FR-EMP-05, FR-ADM-01 |
| BR-AUTH-08 | M | P5 | FR-AUTH-09, FR-TRV-07 |
| BR-ONB-01 | M | P1 (organizations), P4 (suppliers) | FR-ONB-01 |
| BR-ONB-02 | M | P1 | FR-ONB-02 |
| BR-ONB-03 | M | P1 | FR-ONB-03 |
| BR-ONB-04 | M | P1 | FR-ONB-04 |
| BR-ONB-05 | M | P1 | FR-ONB-05 |
| BR-ONB-06 | M | P4 | FR-ONB-06, FR-PHO-01 |
| BR-ONB-07 | M | P4 | FR-ONB-07 |
| BR-ONB-08 | M | P4 | FR-ONB-08 |
| BR-ONB-09 | M | P1 | FR-ONB-09 |
| BR-ORG-01 | M | P1 | FR-ORG-01, FR-ORG-02 |
| BR-ORG-02 | M | P1 | FR-EMP-01, FR-EMP-03, FR-EMP-04, FR-EMP-05, FR-EMP-06 |
| BR-ORG-03 | M | P1 | FR-ORG-03, FR-DRV-01 |
| BR-ORG-04 | M | P2 | FR-ORG-04, FR-FLT-02 |
| BR-ORG-05 | M | P3 | FR-ORG-05, FR-SEAT-07 |
| BR-ORG-06 | S | P6 | FR-ORG-06 |
| BR-ORG-07 | M | P5 | FR-ORG-07 |
| BR-ORG-08 | C | P6 | FR-ORG-08 |
| BR-TRIP-01 | M | P2 | FR-TRIP-01 |
| BR-TRIP-02 | M | P2 | FR-TRIP-02, FR-TRIP-05, FR-TRV-02 |
| BR-TRIP-03 | M | P3 | FR-TRIP-03, FR-FLT-03, FR-FLT-05 |
| BR-TRIP-04 | M | P3 | FR-TRIP-04 |
| BR-PRG-01 | M | P3 | FR-PRG-01, FR-PRG-06 |
| BR-PRG-02 | M | P3 | FR-PRG-02, FR-PRG-05 |
| BR-PRG-03 | M | P3 | FR-PRG-03, FR-TRV-04 |
| BR-PRG-04 | M | P3 | FR-PRG-04 |
| BR-FLT-01 | M | P2 | FR-FLT-01, FR-FLT-02, FR-FLT-04, FR-FLT-06 |
| BR-STF-01 | M | P3 | FR-STF-01, FR-DRV-01 |
| BR-STF-02 | M | P4 | FR-STF-02 |
| BR-STF-03 | M | P4 | FR-NEG-01, FR-NEG-02, FR-NEG-03, FR-NEG-04, FR-NEG-07, FR-DRV-02 |
| BR-STF-04 | M | P4 | FR-STF-04, FR-NEG-05 |
| BR-STF-05 | M | P3 | FR-STF-03, FR-STF-05, FR-STF-07 |
| BR-STF-06 | M | P4 | FR-STF-06, FR-DRV-02, FR-DRV-08 |
| BR-SEAT-01 | M | P3 | FR-ORG-05, FR-SEAT-01, FR-SEAT-06, FR-SEAT-07 |
| BR-SEAT-02 | M | P3 | FR-FLT-04, FR-SEAT-02 |
| BR-SEAT-03 | S | P3 | FR-SEAT-03 |
| BR-SEAT-04 | M | P3 | FR-SEAT-04, FR-SEAT-05 |
| BR-BKG-01 | M | P2 | FR-BKG-01 |
| BR-BKG-02 | M | P2 | FR-BKG-02, FR-PAY-01, FR-PAY-02, FR-PAY-07, FR-PAY-08, FR-PAY-09 |
| BR-BKG-03 | M | P2 | FR-BKG-03, FR-BKG-07 |
| BR-BKG-04 | M | P2 | FR-BKG-04, FR-BKG-06, FR-PAY-03 |
| BR-BKG-05 | M | P2 | FR-BKG-05, FR-TRV-03 |
| BR-CPN-01 | M | P5 | FR-CPN-01, FR-CPN-05, FR-PHO-05, FR-ADM-06 |
| BR-CPN-02 | M | P5 | FR-CPN-02 |
| BR-CPN-03 | M | P5 | FR-CPN-03, FR-CPN-04 |
| BR-CPN-04 | M | P5 | FR-CPN-06 |
| BR-CPN-05 | M | P5 | FR-CPN-07, FR-ADM-07 |
| BR-CHK-01 | M | P3 | FR-CHK-01, FR-CHK-04, FR-CHK-07 |
| BR-CHK-02 | M | P3 | FR-CHK-02 |
| BR-CHK-03 | M | P3 | FR-SEAT-05, FR-CHK-03, FR-CHK-05, FR-MOD-04 |
| BR-CHK-04 | M | P3 | FR-STF-05, FR-MOD-01, FR-MOD-03 |
| BR-CHK-05 | S | P3 | FR-CHK-06, FR-PAR-07 |
| BR-MOD-01 | M | P3 | FR-MOD-02, FR-MOD-06 |
| BR-TRK-01 | M | P6 | FR-CHK-07, FR-TRK-01, FR-TRK-02, FR-TRK-03, FR-TRK-06 |
| BR-PAR-01 | M | P6 | FR-PAR-02, FR-PAR-05, FR-PAR-07, FR-ANL-05 |
| BR-PAR-02 | M | P6 | FR-PAR-01, FR-PAR-06 |
| BR-PAR-03 | M | P6 | FR-TRK-06, FR-PAR-04 |
| BR-PAR-04 | M | P6 | FR-TRK-04, FR-PAR-03 |
| BR-MED-01 | M | P4 | FR-MED-01, FR-MED-02, FR-MED-08 |
| BR-MED-02 | M | P4 | FR-MED-03, FR-MED-04, FR-MED-05 |
| BR-MED-03 | S | P4 | FR-MED-06 |
| BR-MED-04 | M | P4 | FR-MED-07 |
| BR-PHO-01 | M | P4 | FR-PHO-01, FR-PHO-02, FR-PHO-03 |
| BR-NTF-01 | M | P1 | FR-ONB-09, FR-PAR-04, FR-NTF-01, FR-NTF-05, FR-NTF-06, FR-NTF-07, FR-TRV-01 |
| BR-NTF-02 | M | P1 | FR-NTF-02, FR-TRV-01 |
| BR-NTF-03 | S | P3 | FR-NTF-03 |
| BR-SUB-01 | M | P5 | FR-SUB-01, FR-SUB-07 |
| BR-SUB-02 | M | P5 | FR-EMP-03, FR-TRIP-07, FR-PRG-06, FR-FLT-03, FR-NEG-07, FR-CPN-08, FR-SUB-02, FR-SUB-09, FR-SUB-10, FR-SUB-11, FR-MED-03, FR-MED-08, FR-PHO-04, FR-PHO-06, FR-ACC-06, FR-TRV-03 |
| BR-SUB-03 | M | P5 | FR-PAY-06, FR-SUB-03, FR-SUB-08 |
| BR-SUB-04 | M | P5 | FR-SUB-06 |
| BR-SUB-05 | M | P5 | FR-SUB-05 |
| BR-ACC-01 | M | P5 | FR-ACC-02 |
| BR-ACC-02 | M | P5 | FR-NEG-06, FR-ACC-04 |
| BR-ACC-03 | M | P4 | FR-PHO-03, FR-DRV-07, FR-ACC-03 |
| BR-ACC-04 | M | P5 | FR-ACC-06 |
| BR-ACC-05 | M | P2 | FR-PAY-05, FR-ACC-01, FR-ACC-07, FR-ACC-09 |
| BR-ACC-06 | M | P4 | FR-ACC-05, FR-ACC-10 |
| BR-ACC-07 | M | P5 | FR-ACC-08 |
| BR-ADM-01 | M | P1 | FR-ONB-02 |
| BR-ADM-02 | M | P1 | FR-AUTH-08, FR-ADM-01 |
| BR-ADM-03 | M | P4 | FR-DRV-03, FR-DRV-04, FR-DRV-05, FR-ADM-09 |
| BR-ADM-04 | M | P3 | FR-PAY-04, FR-ADM-04 |
| BR-ADM-05 | M | P1 | FR-ADM-05 |
| BR-ADM-06 | M | P1 | FR-ADM-03, FR-NTF-04 |
| BR-ADM-07 | M | P3 | FR-ADM-08 |
| BR-MKT-01 | M | P2 | FR-PAY-07, FR-MKT-01, FR-MKT-05 |
| BR-MKT-02 | M | P2 | FR-MKT-02 |
| BR-MKT-03 | M | P5 | FR-MKT-03 |
| BR-MKT-04 | S | P5 | FR-MKT-04 |
| BR-ANL-01 | M | P6 | FR-ANL-01, FR-ANL-05 |
| BR-ANL-02 | S | P6 | FR-ANL-02 |
| BR-ANL-03 | M | P6 | FR-ANL-03 |
| BR-ANL-04 | S | P6 | FR-ANL-04 |
| BR-API-01 | C | P6 | FR-API-01 |

### 10.2 Business rules to functional requirements

| Business rule | Rule (shortened) | Functional requirements |
|---|---|---|
| BRL-01 | Trip records are tenant-isolated. Only the owning organization's staff, the platform admin, and the people... | FR-ORG-01, FR-EMP-04, FR-TRK-05 |
| BRL-02 | Every non-traveler role must hold an active plan to use the app. A traveler can always use the app for free. | FR-ONB-01, FR-ORG-02, FR-TRIP-07, FR-SUB-02 |
| BRL-03 | A coupon applies only inside its creator's scope: admin to any trip; organization to its own trips; driver... | FR-CPN-01, FR-CPN-02, FR-PHO-05 |
| BRL-04 | A platform driver or photographer is assigned only after a finalized deal accepted by both parties. | FR-STF-04, FR-NEG-03 |
| BRL-05 | An organization-owned driver or photographer is accepted on assignment and is never visible to other... | FR-ORG-03 |
| BRL-06 | A rejected supplier or organization application can be re-submitted after 30 days. A closed application... | FR-ONB-05, FR-TRV-06 |
| BRL-07 | A trip cannot move to Ready until every vehicle has a driver and a moderator and every platform deal is... | FR-TRIP-03, FR-TRIP-05, FR-STF-03 |
| BRL-08 | A seat hold lasts 15 minutes and is released automatically when it expires. Exception: a Fawry cash... | FR-SEAT-04, FR-BKG-03, FR-PAY-09 |
| BRL-09 | A moderator can check a passenger in only to the vehicle assigned to that passenger on that trip. | FR-CHK-02, FR-MOD-03 |
| BRL-10 | Parents see data only for children linked to their own bookings. | FR-PAR-01, FR-PAR-02, FR-PAR-05, FR-PAR-06 |
| BRL-11 | A refund reverses the coupon redemption and frees the traveler's per-user limit. | FR-BKG-04, FR-CPN-06, FR-ADM-04 |
| BRL-12 | Cancellation refund depends on time before departure: 80 percent at 48 hours or more, 50 percent from 24... | FR-BKG-04 |
| BRL-13 | A plan upgrade takes effect at once with proration; a downgrade takes effect at the end of the cycle. | FR-SUB-04, FR-TRV-05 |
| BRL-14 | A failed renewal starts a 7-day grace period with features kept. | FR-SUB-03 |
| BRL-15 | Account deletion becomes permanent after a 30-day grace period. Trip records are kept 5 years after... | FR-AUTH-09, FR-TRV-07 |
| BRL-16 | A lab test has a deadline of 3 days from request (admin may set 1 to 7 days, D-21). A missed deadline... | FR-DRV-04, FR-DRV-06 |
| BRL-17 | A driver whose lab test is requested is paused at once. Upcoming trips return to their organizers.... | FR-STF-07, FR-DRV-03 |
| BRL-18 | Every financial event (booking, refund, coupon, subscription charge, payout, deal cost, commission,... | FR-ORG-07, FR-NEG-05, FR-NEG-06, FR-PAY-01, FR-PAY-02, FR-PAY-03, FR-ACC-01, FR-ACC-07 |
| BRL-19 | Every admin action and every permission change is written to an immutable audit log. | FR-EMP-02, FR-PAY-04, FR-SUB-01, FR-SUB-05, FR-ADM-03, FR-ADM-05 |
| BRL-20 | Verification codes expire after 5 minutes. After 5 wrong codes, verification pauses for 15 minutes. A... | FR-AUTH-03 |
| BRL-21 | Accounts lock for 15 minutes after 5 failed sign-in attempts (all roles, D-53). | FR-AUTH-05, FR-ADM-02 |
| BRL-22 | Web sessions end after 12 hours of use. Drafts are kept. | FR-AUTH-10 |
| BRL-23 | Every registration form records the applicant's country. | FR-AUTH-02 |
| BRL-24 | A trip's programs, seat mode or vehicles cannot be changed in a way that breaks a confirmed booking until... | FR-TRIP-06, FR-FLT-05, FR-BKG-06 |
| BRL-25 | A trip is archived 30 days after Completed, and only once its settlement is closed (D-26). | FR-ACC-10 |
| BRL-26 | A deal offer expires 72 hours after the last counter-offer and the deal ends as Rejected with reason... | FR-NEG-02 |
| BRL-27 | Market values (currency, payment methods, tax and invoicing rules, default time zone) follow the country:... | FR-AUTH-02, FR-MKT-02 |
| BRL-28 | An invoice is B2B when the buyer gives a tax number, otherwise B2C (D-180). An issued invoice is never... | FR-ORG-07, FR-ACC-08 |
| BRL-29 | Travelers never see a vehicle's live position. Only operations staff of the owning organization, the... | FR-TRK-05, FR-TRK-07 |
| BRL-30 | When an organization cancels a trip, every confirmed booking is refunded in full and coupon redemptions... | FR-TRIP-05, FR-BKG-06 |

## 11. Open items and conflicts

### 11.1 Open items that still affect requirements

| Ref | Open item | Affects | Owner |
|---|---|---|---|
| OD-05 | Confirm child tracking eligibility ("and" as drafted) | FR-PAR-03, FR-TRK-04 | Product |
| OD-06 | Plan for moderators | FR-MOD-05; section 3.3 | Product |
| OD-07 | Confirm refund table 80/50/0 and non-refundable fees | FR-BKG-04, FR-PAY-03 | Operations |
| OD-08 | Priority seat window length (D-316) | FR-SEAT-03 | Product |
| OD-09 | VIP early-access window (D-146) | FR-TRV-02 | Product |
| OD-10 | ZATCA e-invoicing scope | FR-ORG-07, FR-ACC-08, NFR-COM-07 | Finance |
| OD-11 | Gulf payment methods and gateways | FR-PAY-07, FR-MKT-01 | Engineering |
| OD-14 | Annual discount (placeholder 15 percent) | FR-SUB-01 | Finance |
| OD-15 | Commission rates (placeholder 10 percent) | FR-NEG-05, FR-ACC-02, FR-PHO-03 | Finance |
| OD-16 | Automated business registry check | FR-ONB-02 | Operations |
| OD-18 | CRM targets | FR-ORG-06 | Product |
| OD-19 | Organization plan covering own drivers and photographers; allowance per plan (D-97) | FR-ORG-03, FR-DRV-01 | Product |
| OD-25 | Plan gate on enrollment report | FR-PRG-04 | Product |
| OD-26 | Plan prices per role and market | FR-SUB-01, FR-MKT-01 | Finance |
| OD-27 | VAT rate per market | FR-ORG-07, FR-MKT-01 | Finance |
| OD-28 | Trial lengths | FR-SUB-07 | Finance |
| OD-29 | What happens when grace ends (downgrade vs lockout) | FR-SUB-03; section 7.6 | Product |
| OD-30 | Webhook endpoints per plan (D-65) | FR-ORG-06 | Product |
| OD-31 | Deal fee when cancelled after assignment (D-278) | FR-NEG-05, FR-STF-07 | Finance |
| OD-32 | Changes-requested closure day when extension used | FR-ONB-04; section 7.5 | Product |
| D-68 | Child-safety escalation contacts and procedure | FR-PAR-07, FR-CHK-06 | Operations |
| D-216 | Coupon abuse thresholds | FR-CPN-07, FR-ADM-07 | Product |
| D-314 | SRS and BRD approvers | Section 1 | Product |
| BRD 8.2 | Legal review per market for lab tests; document and lab-result retention | FR-ONB-08, FR-DRV-04; 6.4 | Legal |
| OD-04 | Financial retention 7 years, pending legal review | 6.4 | Legal |
| NFR-DAT-04 | Hosting region against Egyptian residency rules | 3.4 | Engineering |
| CON-05 | Confirm recommended stack (T-FND-01) | All | Engineering |
| NFR-SEC-13 | Rate limits for drivers, photographers and parents | NFR-SEC-13 | Engineering |
| NFR-MNT-04 | Test coverage target | NFR-MNT-04 | Engineering |
| NFR-OBS-06 | Hot log search window | NFR-OBS-06 | Engineering |
| 3.4 | Supported browser and OS versions | 3.4 | Product |
| 6.4 | Retention of notification delivery logs and rejected applications | 6.4 | Legal |
| D-289 | Confirm the urgent notification list | FR-NTF-03 | Product |
| Proposed | Re-boarding after check-out at a stop | FR-CHK-04; 7.2, 7.3 | Operations |
| Proposed | Traveler sign-in offers both password and code | FR-AUTH-04 | Product |
| Proposed | Platform issues deal invoices on the supplier's behalf | FR-ACC-08 | Finance |
| Proposed | Free-plan history depth 12 months | FR-TRV-03 | Product |

### 11.2 Items closed in v1.1

| Item (v1.0 reference) | Resolution | Source |
|---|---|---|
| Traveler verification and uniqueness | Travelers verify one contact; suppliers and owners both | D-20 |
| Appeal window | 14 days; 5 files; 5 working days; one per rejection | D-237 |
| Negotiation expiry (OD-13) | 72 hours after last counter; no round limit | D-14 |
| Cart after hold expiry | Cart kept; seats re-checked; new hold if free | D-191 |
| Payment attempts | 3 per hold | D-197 |
| Definition of active trips | Created this cycle and Published through Completed | D-143 |
| Coupon count basis | Active and paused | D-203 |
| Total usage restored on refund | Yes, usage count decrements | Architecture 10.6 |
| Webhook retry and alerts | 5 retries over 24 h, owner emailed, 7-day replay, no plan gate | D-62 to D-64 |
| Invoice plan gate and numbering failure | All plans; retry 3 times then finance review | D-178, D-179 |
| Settlement rules | Open on Completed; close when paid and no dispute | D-174 |
| Payout schedule | Weekly, Monday 06:00 | D-28 |
| Default tiers (OD-21) | Organization Starter; suppliers Basic; plan chosen without charge | D-24, D-77, D-78 |
| Admin roles and invite | Super admin, Operations, Finance; 72 h link | D-49, D-50 |
| Moderator sign-in | Phone plus staff code valid to trip end plus 24 h, one device | D-107, D-108 |
| Parent tracker surface | Section of the traveler app | D-124 |
| Offline check-in scope | 1,000 events; retry every 30 s | D-117 |
| Incident categories | Six categories | D-331 |
| Dispute SLA and types | 2 / 10 working days; six types | D-32, D-254 |
| Notification retries | 3 with exponential backoff | Architecture 13.2 |
| Data copy response time | 30 days | D-162 |
| Default organization time zone | Market main zone | D-282 |
| Digits | Western digits | D-159 |
| Maintenance windows | 01:00 to 03:00 Cairo, 4 h per month, 72 h notice | D-303 |
| Paging | 20 default, 100 maximum | D-308 |
| Deprecation notice | 90 days | Architecture 7.6 |
| Capacity stages | Stage 1 values plus architecture stages | D-304; architecture 19.3 |
| Backups and DR drills | As NFR-BKP-03 and NFR-BKP-04 | Architecture 22 |
| Alert tools | PagerDuty, Slack, email digest | Architecture 16.3 |
| Lab deadline (C-05) | 3 days, admin 1 to 7, now in BRL-16 | D-21 |
| Retention of GPS, check-in, child history | 90 days, 1 year, 1 year | Architecture 18.4; D-45; D-135 |
| Launch currencies | EGP in P2; SAR and AED in P5 | D-69, D-158 |

### 11.3 Source conflicts

| # | Conflict | Sources | Resolution in this SRS |
|---|---|---|---|
| C-01 | Own-driver plan: full BRD context says own drivers are not subject to plans; BRL-02 says every non-traveler needs a plan. | BRD context section 9; BRL-02 | Organization's plan covers them (OD-19, proposed). |
| C-02 | Out-of-scope coupon: "silently reject" vs "clear error". | BRD context 3A, 3E | Generic "not applicable" message (OD-20). Closed. |
| C-03 | Traveler verification. | BR-AUTH-03; flow 01 | D-20 applied. Closed. |
| C-04 | Enrollment report plan gate. | BR-PRG-04; flow 02 | No gate until OD-25. |
| C-05 | Lab deadline missing from BRL-16. | Auth rules; BRL-16 | BRL-16 updated. Closed. |
| C-06 | Architecture not reviewed. | v1.0 | Reviewed. Closed. |
| C-07 | User-flows 03 to 08 missing. | v1.0 | Written in v1.1. Closed. |
| C-08 | After grace: register says downgrade and block new bookings (D-08); BRL-02 implies lockout; design canvas shows lockout. | D-08; BRL-02; canvas | Downgrade applied until OD-29 is decided. |
| C-09 | Organization-cancelled trip refund: v1.0 used the traveler policy; D-150 says full refund. | SRS v1.0; D-150 | Full refund (BRL-30). Closed. |
| C-10 | Check-in event retention: v1.0 kept 7 years as audit; register and architecture say 1 year. | SRS v1.0; D-45; architecture 18.4 | 1 year; admin actions stay 7 years in the audit log. Closed. |
| C-11 | Filling Up trigger: v1.0 "first booking"; register 80 percent. | SRS v1.0; D-189 | 80 percent. Closed. |
| C-12 | Lockout scope: BRL-21 admin only; register all roles. | BRL-21 v1.0; D-53 | All roles; BRL-21 updated. Closed. |
| C-13 | Design canvas is Saudi-first in SAR with ZATCA, HyperPay, monthly payouts, 72-hour free cancellation, 48-hour deal expiry, traveler live ETA, Face ID. | claude/design-value-conflicts.md | This SRS follows the decisions; the canvas must be relabeled as a P5 Saudi view or corrected before P2 build. |
| C-14 | Photographer studio: architecture lists separate role apps; register makes the studio responsive web in phase 1. | Architecture 3.1; D-86 | Responsive web first; mobile studio follows. |
