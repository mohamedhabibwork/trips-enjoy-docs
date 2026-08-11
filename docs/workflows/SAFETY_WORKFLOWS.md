# Safety Workflows

Safety flows cover emergencies during rides and deliveries, account
suspension, fraud, and incident handling. Reflects the **20-service
architecture** consolidated 2026-08-05 per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md):
the safety capability is owned by `trip-service` (absorbed from
`ride-safety-service`) and the support module is owned by
`admin-service`.

## Request lifecycle note

Safety events are orthogonal to the request lifecycle. An SOS, share, or
recording can be triggered at any point during an active request
(`requested`, `matched`, `in_progress`). Safety workflows use the
`request_id` (with implicit `service='trip'`) to locate the trip
context; they do not emit `request.*.v1` events or alter the request
state machine. Per [ADR-0020](../architecture/adrs/0020-polymorphic-request-id.md).

## Workflow: Customer SOS During a Ride

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service (safety)
    participant NOT as notification-service
    participant ADM as admin-service (support module)
    participant SEC as Security On-call
    participant LAW as Emergency Services (manual)

    C->>TR: POST /v1/trips/{id}/sos (request_id, location)
    TR->>TR: get trip context
    TR-->>TR: trip + driver details
    TR->>NOT: notify trusted contacts (SMS + push)
    NOT-->>C: SMS
    NOT-->>TC: SMS to trusted contact
    TR->>ADM: open P1 ticket (via support.admin)
    ADM->>SEC: page on-call
    TR->>TR: persist incident (encrypted, audit)
    TR-->>C: 200 OK (we are with you)
    SEC->>LAW: if needed
    Note over TR: live location continues to update
    TR-->>SEC: live location
```

## Workflow: Driver SOS During a Ride

```mermaid
sequenceDiagram
    participant DR as Driver
    participant TR as trip-service (safety)
    participant NOT as notification-service
    participant ADM as admin-service (support module)
    participant SEC as Security On-call
    participant C as Customer

    DR->>TR: POST /v1/trips/{id}/sos (request_id, location)
    TR->>TR: get trip + customer details
    TR->>NOT: notify trusted contacts (driver's)
    TR->>ADM: open P1 ticket
    ADM->>SEC: page on-call
    TR->>TR: state=incident
    Note over TR: live location continues
```

## Workflow: Share Trip

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service (safety)
    participant NOT as notification-service
    participant TC as Trusted Contact

    C->>TR: POST /v1/trips/{id}/share (request_id, contact)
    TR->>NOT: send SMS to contact with link
    NOT-->>TC: SMS with live location link
    loop every 30s
        TR->>TR: re-fetch current location
    end
    Note over TR: until trip completes
    TR-->>C: ok
```

## Workflow: Audio Recording

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service (safety)
    participant FS as file-service
    participant AUD as audit-service
    participant SEC as Security On-call

    C->>TR: POST /v1/trips/{id}/record (request_id, start)
    TR->>FS: reserve storage
    TR->>TR: stream audio to FS (encrypted)
    Note over TR: trip ends
    TR->>TR: stop recording
    TR->>FS: finalize
    TR->>AUD: ride.safety.recording.finalized.v1
    TR-->>C: recording saved
    Note over TR: recordings are accessible only to security
```

## Workflow: Account Suspension (Customer)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant CST as customer-service
    participant ID as identity-service
    participant FR as fraud-risk-service
    participant NOT as notification-service
    participant ADM2 as admin-service (support module)
    participant C as Customer

    ADM->>CST: POST /v1/customers/{id}/suspend (reason, duration)
    CST->>CST: state=suspended
    CST->>ID: customer.suspended.v1
    ID->>ID: revoke sessions, block login
    ID->>NOT: notify trusted contacts (configurable)
    ID->>FR: update risk profile
    CST->>NOT: notify customer
    NOT-->>C: email + push
    CST->>ADM2: open ticket (via support.admin)
```

## Workflow: Account Suspension (Driver / Courier)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant DRV as driver-service
    participant ID as identity-service
    participant NOT as notification-service

    ADM->>DRV: POST /v1/drivers/{id}/suspend (reason)
    DRV->>DRV: state=suspended
    DRV->>DRV: own producer emits driver.suspended.v1
    DRV->>DRV: own consumer sets state=offline (busy)
    DRV->>DRV: own producer stops dispatching
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
    participant ADM2 as admin-service (support module)
    participant USR as User

    FR->>FR: score (login / payment / GPS)
    alt score high
        FR->>ID: block account (Idempotency-Key=event:event_id:block)
        ID->>ID: state=blocked
        ID->>USR: revoke sessions
        FR->>PAY: hold any in-flight authorization
        FR->>ADM: high-severity event
        ADM->>ADM2: open P1 ticket (via support.admin)
    end
```

## Workflow: Account Re-instatement

```mermaid
sequenceDiagram
    participant USR as User
    participant ADM as admin-service (support module)
    participant ADM2 as admin-service
    participant CST as customer-service
    participant ID as identity-service
    participant NOT as notification-service

    USR->>ADM: request reinstatement
    ADM->>ADM: agent reviews
    alt approved
        ADM->>ADM2: approve
        ADM2->>CST: reinstate
        CST->>ID: customer.reinstated.v1
        ID->>ID: state=active
        ID->>USR: allow login (forced password reset)
        ID->>NOT: notify user
        NOT-->>USR: "Account re-instated. Reset your password."
    else rejected
        ADM-->>USR: rejection reason
    end
```

## Workflow: Incident Reporting (General)

```mermaid
sequenceDiagram
    participant U as User
    participant ADM as admin-service (support module)
    participant AUD as audit-service
    participant ADM2 as admin-service
    participant LAW as Law Enforcement (manual)

    U->>ADM: report incident
    ADM->>ADM: classify severity
    alt P1 (life safety)
        ADM->>AUD: incident.opened.v1
        ADM->>ADM2: page on-call
        ADM2->>LAW: contact if needed
    else P2
        ADM->>AUD: incident.opened.v1
        ADM->>ADM: investigate within 24h
    end
    ADM-->>U: ticket number
    Note over ADM: investigation tracked in admin-service support module
    ADM->>AUD: incident.resolved.v1
```

## Workflow: Data Subject Access / Erasure (GDPR / PDPL)

```mermaid
sequenceDiagram
    participant U as User
    participant ADM as admin-service (support module)
    participant ID as identity-service
    participant CST as customer-service
    participant DRV as driver-service
    participant COS as courier-service
    participant PAY as payment-service
    participant LD as ledger-service
    participant AUD as audit-service

    U->>ADM: request data export / erasure
    ADM->>ADM: verify identity
    ADM->>ID: identify user
    alt export
        ADM->>CST,DRV,COS: collect profile
        ADM->>PAY: collect payment history (no PAN)
        ADM-->>U: data package (signed URL, encrypted)
    else erasure
        ADM->>CST,DRV,COS: erase PII columns
        ADM->>ID: anonymize Keycloak user
        ADM->>PAY: mark "erased" (financial records retained per law)
        ADM->>LD: retain ledger (de-identified)
        ADM->>AUD: erasure.completed.v1
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