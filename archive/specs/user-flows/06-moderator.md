# 06. Moderator app

Surface: installable web app on phones. Role: vehicle moderator.

## Registration

No self-registration. The organization assigns a moderator to one vehicle on one trip with name and phone (D-107). The moderator receives an SMS link to install the app and a staff code.

## Approval

The assignment by the organization is the approval. Access lasts until 24 hours after the trip ends, on one device at a time (D-108). Replacing the moderator ends access at once. The moderator's plan rule is OD-06.

## Flows

### UF-MOD-01. Install and first open
1. Opens the SMS link; adds the app to the home screen.
2. Permission primer: location (for check-in location and tracking fallback), camera (scanning), notifications.
3. Opens to the vehicle view.
Rules: FR-MOD-04.

### UF-MOD-02. Sign in
Phone and staff code. Signing in on a second device ends the first session. No assignment, or an expired assignment, shows "no active vehicle".
Rules: FR-MOD-05.

### UF-MOD-03. Vehicle home and manifest
Vehicle label, trip, departure time, pickup point, driver contact, operations contact; manifest of passengers with seat and status (not boarded, on board, off vehicle); occupancy counter.
Rules: FR-MOD-01, FR-CHK-07.

### UF-MOD-04. Check in a passenger
1. Scan the passenger's boarding code, or search the manifest by name.
2. Results:
   - **Checked in**: seat confirmed; event recorded with time and location where permitted.
   - **Wrong vehicle**: refused; shows "vehicle N" only.
   - **Not found or not confirmed**: refused; escalate to operations.
   - **Already on board**: duplicate ignored.
   - **Queued**: offline; will sync.
Rules: FR-CHK-01 to FR-CHK-03, BRL-09.

```mermaid
flowchart TD
    S[Scan or search] --> V{Booking confirmed?}
    V -->|No| R1[Refuse, escalate to operations]
    V -->|Yes| M{Assigned to this vehicle?}
    M -->|No| R2[Refuse, show vehicle number only]
    M -->|Yes| A{Already on board?}
    A -->|Yes| R3[Ignore duplicate]
    A -->|No| C[Check in, lock seat]
    C --> N{Online?}
    N -->|Yes| U[Send now, parents and operations updated]
    N -->|No| Q[Queue with original time, retry every 30 s]
```

### UF-MOD-05. Check out at a stop or destination
Select the stop; check passengers out individually or all at once; a passenger checked out at a stop can re-board and be checked in again (proposed).
Rules: FR-CHK-04.

### UF-MOD-06. Offline queue
Banner shows offline state and the number of queued events; warning at 800; maximum 1,000; events send in order with original times when the connection returns.
Rules: FR-CHK-05.

### UF-MOD-07. Report an incident
Category (safety, medical, vehicle fault, lost traveler, conduct, other), description, photo, location; operations sees it at once.
Rules: FR-CHK-06.

### UF-MOD-08. End-of-trip report and access end
1. Enter final headcount; if it differs from the checked-in count, a reason is required; add notes; submit (read-only after).
2. Access ends 24 hours after the trip ends, or at once on replacement, with an "access ended" screen.
Rules: FR-MOD-02, FR-MOD-03, FR-MOD-06.
