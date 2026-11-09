# 02. Organization dashboard

Surface: business.trips-enjoy.com (web only for registration; the dashboard is responsive). Roles: organization owner, employee.

## Registration

| Field | Required | Notes |
|---|---|---|
| Owner full name | Yes | Becomes the owner account |
| Owner email and phone | Yes, both verified | D-20 |
| Password | Yes | Second factor set up on first sign-in |
| Country | Yes | Sets the organization's market, currency, tax rules and time zone |
| Legal name and trade name | Yes | Arabic and English |
| Commercial registration number | Yes | |
| Tax number | Yes where the market requires it | |
| Registration documents | Yes | Commercial registration, tax certificate; PDF, JPG or PNG up to 10 MB |
| Business address and category | Yes | Category: school, corporate, religious, tourism, event, other |
| Billing contact | Yes | Email for invoices |
| Plan choice | No | Shown without charge; Starter if none (D-77, D-24) |

## Approval

1. Application enters the admin queue; the applicant sees "under review" with the 2-working-day target.
2. Admin checks documents and registry details (manual in phase 1).
3. Decision: approve (tenant created on the chosen plan or Starter, owner invited); request changes (14 days, reminder day 7, one 7-day extension); reject with reason (appeal within 14 days, re-apply after 30 days).
4. Every decision notifies the owner in their language.

## Flows

### UF-ORG-01. First sign-in and setup
1. Owner signs in with email and password; sets up an SMS or authenticator second factor.
2. Setup checklist: profile and logo, time zone (defaults from market), invoice settings, first vehicle type, first vehicle, invite staff, onboard own drivers and photographers, payout bank details.
3. Empty-state home shows the checklist and plan usage.
Rules: FR-ORG-02, FR-EMP-06.

### UF-ORG-02. Switch organization
1. A staff member whose email is listed by several organizations sees the organization switcher.
2. Choosing one changes the workspace; data from the other is never shown.
Rules: FR-AUTH-07, BRL-01.

### UF-ORG-03. Invite and manage employees
1. Invite by email with a permission template (Operations, Finance, Support, Manager) or custom permissions across trips, bookings, operations, accounting, coupons, employees, fleet, subscription, integrations, analytics.
2. Link valid 7 days; on first sign-in the employee sets a password and second factor.
3. Change permissions (history shows actor, time, before and after); suspend or remove (access ends at once).
Edge case: the plan's employee limit is reached; the invite is blocked with an upgrade prompt.
Rules: FR-EMP-01 to FR-EMP-06.

### UF-ORG-04. Fleet
1. Vehicle types: name, seat grid, labeled seats, accessible and blocked seats; archive.
2. Vehicles: plate, model, year, type, seats (must match), registration validity.
Rules: FR-FLT-01, FR-FLT-02, FR-FLT-06.

### UF-ORG-05. Own drivers and photographers
1. Invite by SMS or email (valid 7 days) with name, phone, country; license for drivers.
2. They appear only in this organization's lists and are accepted on assignment.
3. Removing one releases their future assignments and alerts operations.
Rules: FR-ORG-03, BRL-05.

### UF-ORG-06. Create a trip (wizard)
1. **Basics**: title, description, category, cover image, language.
2. **Schedule and route**: start and end, time zone, pickup points, stops, destination on a map.
3. **Vehicles**: add vehicles up to the plan limit, each with a vehicle type and label.
4. **Staffing**: per vehicle, driver (own, or platform through a deal on Growth and Enterprise) and moderator (with phone); trip photographer (own or platform).
5. **Programs**: add programs with pricing model, price or tiers, type flags, capacity; up to the plan limit.
6. **Seating**: company-assigned or self-select (Growth and Enterprise); priority window for Explorer and VIP.
7. **Pricing and capacity**: base price in the market currency; child tracking switch (Growth and Enterprise).
8. **Publish**: save draft, publish publicly, or publish to a target list.
Edge cases: missing step blocks publishing and names the step; monthly active-trip limit reached; market not live.
Rules: FR-TRIP-01 to FR-TRIP-03, FR-TRIP-07, FR-PRG-01 to FR-PRG-06.

### UF-ORG-07. Hire a platform supplier (deal)
1. Search platform drivers or photographers by date availability, region, rating badge and price.
2. Propose a price for a vehicle or the trip; the supplier sees trip, route, date, vehicle and pay.
3. Counter back and forth with no round limit; each offer expires 72 hours after the last counter.
4. Accept: when both sides accept the same terms, the deal is finalized and the supplier assigned.
5. Rejected or expired: the slot reopens for another supplier.
Rules: FR-NEG-01 to FR-NEG-07, BRL-04, BRL-26.

### UF-ORG-08. Move a trip to Ready
1. Staffing board shows each vehicle's driver and moderator status and each open deal.
2. Ready is refused with the list of gaps until every vehicle is staffed and every deal finalized.
3. A staffing-gap email arrives 48 hours before departure; reassignment is allowed until 24 hours before.
Rules: FR-STF-03, FR-STF-07, BRL-07.

### UF-ORG-09. Manage bookings and seats
1. Bookings list with filters by state, vehicle and program; passenger roster.
2. Booking detail: passengers, seats, programs, totals, payments, refunds, notes.
3. Seat map in assign mode: drag passengers to seats; conflicts refused.
4. Handle cancellation requests after the policy cut-off within 2 working days.
Rules: FR-SEAT-06, FR-BKG-04.

### UF-ORG-10. Change a trip after bookings
1. Edit programs, vehicles, seat mode or times.
2. If confirmed bookings are affected, the system lists them and requires sending remedy offers before saving.
3. Travelers have 7 days to choose; unresolved cases go to admin.
Rules: FR-TRIP-06, BRL-24.

### UF-ORG-11. Cancel a trip
1. Cancel with a reason before the trip starts; the preview shows bookings and the full refund total.
2. All confirmed bookings are cancelled and refunded in full; suppliers and travelers are notified.
3. In Progress trips cannot be cancelled; the screen points to incident handling.
Rules: FR-BKG-06, BRL-30.

### UF-ORG-12. Live operations
1. Trip command view on departure day: vehicles on a map, occupancy per vehicle, check-ins as they happen, incidents in real time.
2. Look up any passenger to see their vehicle and status.
3. Review end-of-trip reports and add a note.
Rules: FR-TRK-01, FR-TRK-03, FR-CHK-06, FR-CHK-07, FR-MOD-06.

### UF-ORG-13. Coupons
Create, pause, resume and revoke coupons for own trips; per-coupon analytics with 7, 30 and 90-day presets; count limited by plan (active and paused).
Rules: FR-CPN-01, FR-CPN-07, FR-CPN-08.

### UF-ORG-14. Invoices
1. B2C invoices issue automatically on confirmation; B2B when the buyer gave a tax number.
2. Issue a manual invoice for a group or corporate buyer.
3. Corrections by credit note only.
Rules: FR-ORG-07, BRL-28.

### UF-ORG-15. Accounting and settlements
1. Trip accounting: base revenue, program revenue, coupon impact, supplier costs, commission, gateway fees.
2. Ledger browser with filters; exports by plan.
3. Settlements: one per completed trip, with lines per party, status, and closure when paid and dispute-free.
Rules: FR-ACC-02, FR-ACC-05 to FR-ACC-07.

### UF-ORG-16. Analytics
Trip, page, customer and provider analytics (Growth basic, Enterprise full), date presets and export.
Rules: FR-ANL-01, FR-ANL-04.

### UF-ORG-17. CRM and webhooks
1. Connect a CRM; register webhook endpoints and choose events.
2. See deliveries, failures and replay any delivery for 7 days; rotate the secret (old secret valid 24 hours).
Rules: FR-ORG-06.

### UF-ORG-18. Plan and billing
1. Current plan, usage against limits, renewal date, invoices.
2. Upgrade (at once, prorated) or downgrade (cycle end); cancel.
3. Grace banner during failed renewals with retry dates.
Rules: FR-SUB-02 to FR-SUB-04, FR-SUB-08.

### UF-ORG-19. Child-safety reports
Staff file a report about a child passenger; the case routes to the escalation contact; the list shows status.
Rules: FR-PAR-07.

### UF-ORG-20. Branding (Enterprise)
Logo and colors applied to public trip pages, emails and invoices.
Rules: FR-ORG-08.
