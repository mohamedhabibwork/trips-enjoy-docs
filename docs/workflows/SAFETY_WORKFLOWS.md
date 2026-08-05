# Safety Workflows

Safety flows cover emergencies during rides and deliveries, account
suspension, fraud, and incident handling.

## Workflow: Customer SOS During a Ride

```mermaid
sequenceDiagram
    participant C as Customer
    participant RS as ride-safety-service
    participant TR as trip-service
    participant NOT as notification-service
    participant SUP as support-service
    participant SEC as Security On-call
    participant LAW as Emergency Services (manual)

    C->>RS: POST /v1/safety/sos (trip_id, location)
    RS->>TR: get trip context
    TR-->>RS: trip + driver details
    RS->>NOT: notify trusted contacts (SMS + push)
    NOT-->>C: SMS
    NOT-->>TC: SMS to trusted contact
    RS->>SUP: open P1 ticket
    SUP->>SEC: page on-call
    RS->>RS: persist incident (encrypted, audit)
    RS-->>C: 200 OK (we are with you)
    SEC->>LAW: if needed
    Note over RS: live location continues to update
    RS-->>SEC: live location
```

## Workflow: Driver SOS During a Ride

```mermaid
sequenceDiagram
    participant DR as Driver
    participant RS as ride-safety-service
    participant TR as trip-service
    participant NOT as notification-service
    participant SUP as support-service
    participant SEC as Security On-call
    participant C as Customer

    DR->>RS: POST /v1/safety/sos (trip_id, location)
    RS->>TR: get trip + customer details
    RS->>NOT: notify trusted contacts (driver's)
    RS->>SUP: open P1 ticket
    SUP->>SEC: page on-call
    TR->>TR: state=incident
    Note over RS: live location continues
```

## Workflow: Share Trip

```mermaid
sequenceDiagram
    participant C as Customer
    participant RS as ride-safety-service
    participant NOT as notification-service
    participant TC as Trusted Contact

    C->>RS: POST /v1/safety/share (trip_id, contact)
    RS->>NOT: send SMS to contact with link
    NOT-->>TC: SMS with live location link
    loop every 30s
        RS->>RS: re-fetch current location
    end
    Note over RS: until trip completes
    RS-->>C: ok
```

## Workflow: Audio Recording

```mermaid
sequenceDiagram
    participant C as Customer
    participant RS as ride-safety-service
    participant FS as file-service
    participant AUD as audit-service
    participant SEC as Security On-call

    C->>RS: POST /v1/safety/record (trip_id, start)
    RS->>FS: reserve storage
    RS->>RS: stream audio to FS (encrypted)
    Note over RS: trip ends
    RS->>RS: stop recording
    RS->>FS: finalize
    RS->>AUD: ride.safety.recording.finalized.v1
    RS-->>C: recording saved
    Note over RS: recordings are accessible only to security
```

## Workflow: Account Suspension (Customer)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant CST as customer-service
    participant ID as identity-service
    participant FR as fraud-risk-service
    participant NOT as notification-service
    participant SUP as support-service
    participant C as Customer

    ADM->>CST: POST /v1/customers/{id}/suspend (reason, duration)
    CST->>CST: state=suspended
    CST->>ID: customer.suspended.v1
    ID->>ID: revoke sessions, block login
    ID->>NOT: notify trusted contacts (configurable)
    ID->>FR: update risk profile
    CST->>NOT: notify customer
    NOT-->>C: email + push
    CST->>SUP: open ticket
```

## Workflow: Account Suspension (Driver / Courier)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant DRV as driver-service
    participant DA as driver-availability-service
    participant DSP as dispatch-service
    participant ID as identity-service
    participant NOT as notification-service

    ADM->>DRV: POST /v1/drivers/{id}/suspend (reason)
    DRV->>DRV: state=suspended
    DRV->>DA: driver.suspended.v1
    DA->>DA: state=offline
    DRV->>DSP: stop dispatching
    DRV->>ID: revoke sessions
    DRV->>NOT: notify driver
    NOT-->>DR: push: "Account suspended"
```

## Workflow: Fraud Detection → Account Block

```mermaid
sequenceDiagram
    participant FR as fraud-risk-service
    participant ID as identity-service
    participant PAY as payment-service
    participant ADM as admin-service
    participant SUP as support-service
    participant USR as User

    FR->>FR: score (login / payment / GPS)
    alt score high
        FR->>ID: block account (Idempotency-Key=event:event_id:block)
        ID->>ID: state=blocked
        ID->>USR: revoke sessions
        FR->>PAY: hold any in-flight authorization
        FR->>ADM: high-severity event
        ADM->>SUP: open P1 ticket
    end
```

## Workflow: Account Re-instatement

```mermaid
sequenceDiagram
    participant USR as User
    participant SUP as support-service
    participant ADM as admin-service
    participant CST as customer-service
    participant ID as identity-service
    participant NOT as notification-service

    USR->>SUP: request reinstatement
    SUP->>SUP: agent reviews
    alt approved
        SUP->>ADM: approve
        ADM->>CST: reinstate
        CST->>ID: customer.reinstated.v1
        ID->>ID: state=active
        ID->>USR: allow login (forced password reset)
        ID->>NOT: notify user
        NOT-->>USR: "Account re-instated. Reset your password."
    else rejected
        SUP-->>USR: rejection reason
    end
```

## Workflow: Incident Reporting (General)

```mermaid
sequenceDiagram
    participant U as User
    participant SUP as support-service
    participant AUD as audit-service
    participant ADM as admin-service
    participant LAW as Law Enforcement (manual)

    U->>SUP: report incident
    SUP->>SUP: classify severity
    alt P1 (life safety)
        SUP->>AUD: incident.opened.v1
        SUP->>ADM: page on-call
        ADM->>LAW: contact if needed
    else P2
        SUP->>AUD: incident.opened.v1
        SUP->>SUP: investigate within 24h
    end
    SUP-->>U: ticket number
    Note over SUP: investigation tracked in support-service
    SUP->>AUD: incident.resolved.v1
```

## Workflow: Data Subject Access / Erasure (GDPR / PDPL)

```mermaid
sequenceDiagram
    participant U as User
    participant SUP as support-service
    participant ID as identity-service
    participant CST as customer-service
    participant DRV as driver-service
    participant COS as courier-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant AUD as audit-service

    U->>SUP: request data export / erasure
    SUP->>SUP: verify identity
    SUP->>ID: identify user
    alt export
        SUP->>CST,DRV,COS: collect profile
        SUP->>PAY: collect payment history (no PAN)
        SUP-->>U: data package (signed URL, encrypted)
    else erasure
        SUP->>CST,DRV,COS: erase PII columns
        SUP->>ID: anonymize Keycloak user
        SUP->>PAY: mark "erased" (financial records retained per law)
        SUP->>LD: retain ledger (de-identified)
        SUP->>AUD: erasure.completed.v1
    end
```

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| SOS endpoint unreachable | Mobile app retries with backoff; falls back to phone call to emergency number |
| Trusted contact SMS fails | Retry with alternative channel (push); on failure, log |
| Recording upload fails | Retained in mobile local cache; uploaded on next launch with backoff |
| Account suspension race | Outbox + idempotency ensures only one suspension applies |
| Reinstatement by unauthorized actor | RBAC + audit; high-severity event on every action |

## Acceptance Criteria

- 100% of SOS events open a P1 support ticket within 60 seconds.
- 100% of SOS events notify trusted contacts within 60 seconds.
- 100% of account suspensions are audited.
- 100% of data subject requests are processed within 30 days
  (legal requirement).
