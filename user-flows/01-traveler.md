# 01. Traveler app

Surfaces: responsive web (trips-enjoy.com) and mobile apps. Roles: visitor, traveler. Parent features are in 07-parent.md.

## Registration

| Field | Required | Notes |
|---|---|---|
| Full name | Yes | |
| Email | One of email or phone | Must verify the one given (D-20) |
| Phone | One of email or phone | E.164; must verify if given |
| Password | Yes, unless the traveler chooses code-only sign-in | 12+ characters, breached-password check |
| Country | Yes | Sets the account's market (BRL-23, BRL-27) |
| Language | Yes | Arabic or English; defaults from the device |
| Terms and privacy consent | Yes | Recorded with text version |
| Marketing consent | No | Separate from transactional messages |

## Approval

None. The account is active on verification, on the Free plan.

## Flows

### UF-TRV-01. Browse as a visitor
Actor: visitor. Trigger: opens the site or app.
1. Home shows featured and upcoming public trips from live markets, with search by destination, date, organization, price and category.
2. Visitor opens a trip: description, organization, dates, route and stops, vehicles, programs with prices, base price in the trip's currency, seat mode, policy summary.
3. Book Now asks the visitor to sign up or sign in, then returns to the same trip.
Rules: FR-TRIP-02 (drafts and targeted trips hidden), FR-TRV-02 (VIP early access hidden), FR-MKT-03.

### UF-TRV-02. Register and verify
Actor: visitor. Trigger: Sign up as traveler.
1. Fills the registration form above.
2. Receives a code by SMS, WhatsApp or email; enters it.
3. Lands on a short onboarding: profile photo (optional), notification preferences, plan choice (Free selected; Explorer and VIP offered).
4. Returns to the trip they were viewing, or to home.
Edge cases: code expired (resend after countdown); 5 wrong codes (paused 15 minutes); 3 failed deliveries (offer the other channel); email or phone already registered (offer sign-in and password reset).
Rules: FR-AUTH-01 to FR-AUTH-03.

### UF-TRV-03. Book a trip
Actor: traveler. Trigger: Book Now on a published trip.
1. **Travelers**: number of passengers; name per passenger; child flag where a passenger is a minor.
2. **Programs**: pre-required programs are added and locked; optional ones can be added by toggle, quantity, or tier; VIP-only programs are locked for Free and Explorer with an upgrade prompt; full programs show unavailable. The summary updates live with the plan discount on programs (5 or 10 percent).
3. **Seats**: if self-selection is on, a live seat map of the assigned vehicle; picking a seat holds it for 15 minutes with a visible countdown. During the priority window, Free travelers see the map read-only. If company-assigned, the step shows "seat assigned later".
4. **Summary**: base, programs, discounts, coupon field, optional tax number for a B2B invoice, total with currency code. The price is locked from the start of the hold.
5. **Payment**: methods of the trip's market; the gateway page or SDK opens; no card fields on the platform.
6. **Result**: Confirmed (ticket with booking reference and boarding code, receipt, invoice), Pending (confirming payment), or Failed (retry).
Edge cases: hold expires during payment (cart kept, seats re-checked, new hold if free); third failed attempt (stays Payment Failed until the hold ends); Fawry voucher (seats held 48 hours, voucher code shown, reminder before expiry); trip sells out while in cart (seat step shows unavailable, other vehicles offered).
Rules: FR-BKG-01 to FR-BKG-05, FR-SEAT-02 to FR-SEAT-04, FR-PAY-07 to FR-PAY-09, FR-PRG-02, FR-PRG-03.

### UF-TRV-04. Apply a coupon
1. Enters a code at Summary.
2. Valid: discount line appears and the total updates. Invalid: the first failing reason (not found, paused, expired, not applicable to this booking, exhausted, already used, minimum spend not met).
3. A second non-stackable coupon is refused; the first stays.
Rules: FR-CPN-02, FR-CPN-03.

### UF-TRV-05. Manage bookings
1. My Bookings lists upcoming, past and cancelled bookings with state.
2. Booking detail: trip, passengers, seats, programs, totals, payment status, policy, ticket, receipt, invoice, and actions allowed by state.
3. Receipts and invoices list; export on VIP.
Rules: FR-BKG-05, FR-TRV-03.

### UF-TRV-06. Cancel a booking
1. From booking detail, Cancel shows the refund preview from the policy (placeholder: 80 percent at 48 hours or more, 50 percent from 24 to under 48, none under 24) and the coupon effect.
2. Confirms. The booking moves to Cancelled, then Refund Pending until the gateway confirms ("refund in progress"), then Refunded.
3. After the cut-off, the traveler can send a request to the organization instead (decided within 2 working days, then admin).
Edge cases: after departure or check-in, cancel is replaced by Open a dispute.
Rules: FR-BKG-04, FR-PAY-03, BRL-11, BRL-12.

### UF-TRV-07. Choose a remedy after a trip change
Trigger: the organization changes a program, seat mode, vehicle or time that affects the booking.
1. Notification and banner on the booking with the change.
2. Options: accept the change, switch option (another program or vehicle where offered), or cancel with a full refund.
3. Choice within 7 days; otherwise the case goes to admin.
Rules: FR-TRIP-06, BRL-24.

### UF-TRV-08. Trip day
1. Ticket with boarding code, vehicle label, seat (when assigned), pickup point and time, moderator contact, check-in status.
2. After check-in: status "on board"; programs and schedule.
3. No live vehicle position or ETA is shown to travelers.
Rules: FR-TRK-07, BRL-29.

### UF-TRV-09. Albums
1. Trip history lists completed trips; each shows albums the traveler may see.
2. Public albums: view; download the watermarked or medium copy. Shared-link and selected-user albums: view only.
3. A shared link opened while signed out asks the traveler to sign in.
Rules: FR-MED-05, FR-MED-07.

### UF-TRV-10. Change plan
1. Plans page compares Free, Explorer and VIP with prices in the account's market currency.
2. Upgrade opens the gateway page in the browser; benefits apply at once with proration.
3. Downgrade is scheduled for the cycle end and can be cancelled.
Rules: FR-TRV-05, FR-SUB-04.

### UF-TRV-11. Apply for a supplier role
1. Account menu shows Become a driver, Become a photographer, Register an organization.
2. Driver: handoff to the driver app. Photographer: handoff to the studio. Organization: handoff to the web form (web only).
3. While pending or after rejection, traveler access is unchanged.
Rules: FR-TRV-06, FR-AUTH-06.

### UF-TRV-12. Profile, language and notifications
Edit name, photo, contacts (re-verify on change), language, and per-category channels. Security messages cannot be turned off.
Rules: FR-TRV-01, FR-NTF-01.

### UF-TRV-13. Privacy requests
1. Request a data copy: verified by code, delivered within 30 days as a download link.
2. Request deletion: see UF-SHR-09.
Rules: FR-TRV-07, FR-AUTH-09.

### UF-TRV-14. Open a dispute
1. From a booking: choose type (booking, payment, refund, organization conduct, other), describe, attach up to 5 files.
2. Track status: Open, Information Requested, Under Review, Resolved, Closed.
Rules: FR-ADM-08.

## Booking states seen by the traveler

| State | Label shown |
|---|---|
| Seats Held | Seats held, time left mm:ss |
| Payment Pending | Confirming payment |
| Payment Failed | Payment did not go through, retry |
| Confirmed | Confirmed |
| Checked In / Checked Out | On board / Off vehicle |
| Cancelled | Cancelled |
| Refund Pending | Refund in progress |
| Refunded | Refunded |
