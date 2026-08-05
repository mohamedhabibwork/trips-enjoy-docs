You are a Principal Software Architect, Enterprise Solution Architect, Backend Architect, Database Architect, DevOps Architect, and Technical Documentation Lead.

Your task is to design and document a production-grade, configurable, microservices-based platform combining:

1. A ride-hailing system similar in scope to Uber.
2. A food-delivery and marketplace system similar in scope to Uber Eats / Talabat.
3. A shared platform layer supporting customers, drivers, couriers, restaurants, merchants, administrators, payments, locations, notifications, pricing, promotions, support, and operations.

The deliverable is NOT application code.

The primary deliverable is a complete software architecture and requirements documentation repository written entirely in Markdown (.md).

1. Primary Goal

Design the entire system as independently deployable microservices.

Each business capability should be evaluated as a potential service.

Every service must:

* Have a clearly defined responsibility.
* Follow bounded-context principles.
* Own its own PostgreSQL 18 database/schema.
* Never directly modify another service’s database.
* Communicate through explicit APIs and/or asynchronous events.
* Have documented workflows.
* Have documented business requirements.
* Have documented functional and non-functional requirements.
* Have its own ERD.
* Have its own integration documentation.
* Have its own README.
* Support configuration rather than hard-coded business rules wherever practical.
* Be independently deployable and scalable.
* Define failure handling, retries, idempotency, and observability.
* Define security and authorization requirements.

2. Mandatory Technology Baseline

Use the following architecture unless there is a strong technical reason to recommend an additional component:

* Architecture: Microservices
* Database: PostgreSQL 18
* Authentication / Identity Management: Keycloak
* API style: REST for synchronous APIs
* Async integration: event-driven architecture
* API documentation: OpenAPI 3.x
* Authentication: OAuth 2.0 / OpenID Connect through Keycloak
* Authorization: RBAC plus scoped permissions where required
* Containers: Docker
* Orchestration: Kubernetes
* Configuration: externalized configuration
* Secrets: external secret-management approach
* Cache: Redis where justified
* Message broker: recommend an appropriate broker such as Kafka or RabbitMQ and explain the decision
* Object/file storage: S3-compatible storage where needed
* Observability:
    * centralized logs
    * metrics
    * distributed tracing
    * audit logs
    * correlation IDs
* Database migrations: version-controlled migrations
* CI/CD: independent pipeline per service

Do not create a shared application database.

Each service owns its data.

Cross-service relationships should normally be represented using identifiers such as:

* user_id
* customer_id
* driver_id
* courier_id
* merchant_id
* restaurant_id
* order_id
* trip_id

Do NOT create physical foreign-key constraints between databases owned by different microservices.

Explain how referential consistency is maintained through APIs, events, validation, reconciliation, and eventual consistency.

3. Keycloak and Identity Architecture

Keycloak must be the central identity and access-management platform.

Design identity management for:

* Customers
* Drivers
* Couriers
* Restaurant owners
* Restaurant employees
* Merchant owners
* Merchant employees
* Support agents
* Operations teams
* Finance teams
* Administrators
* Super administrators
* Service accounts
* Internal system-to-system communication

Document:

* realms
* clients
* roles
* groups
* scopes
* claims
* permissions
* service accounts
* token flows
* access tokens
* refresh tokens
* logout
* account suspension
* disabled accounts
* MFA
* password policies
* social login readiness
* phone/OTP authentication readiness
* device/session management
* admin access
* machine-to-machine authentication

Application services should store only the identity references and domain-specific profile information they require.

Clearly explain the difference between:

* Keycloak identity
* customer profile
* driver profile
* courier profile
* merchant account
* employee profile
* administrator profile

4. Platform Domains

At minimum, analyze and document microservices for the following areas.

Shared Platform Services

Consider:

* API Gateway
* Identity / Keycloak Integration Service
* User Profile Service
* Customer Service
* Driver Service
* Courier Service
* Vehicle Service
* Address Service
* Geolocation / Geo Service
* Service Area / Zone Service
* Notification Service
* SMS / Email / Push Integration
* Payment Service
* Wallet Service
* Ledger / Financial Transaction Service
* Pricing Service
* Promotion / Coupon Service
* Loyalty Service
* Tax Service
* Configuration Service
* Feature Flag Service
* File / Media Service
* Search Service
* Review / Rating Service
* Support / Ticket Service
* Fraud / Risk Service
* Audit Service
* Reporting Service
* Analytics Integration Service
* Admin / Operations Service

Ride-Hailing Domain

Consider:

* Ride Request Service
* Trip Service
* Driver Availability Service
* Driver Location Service
* Dispatch / Matching Service
* Ride Pricing / Fare Service
* ETA / Routing Integration Service
* Trip Tracking Service
* Ride Cancellation Service
* Ride Payment Integration
* Ride Rating Service
* Driver Earnings Service
* Driver Incentive Service
* Ride History Service
* Scheduled Ride Service
* Ride Safety / Emergency Service

Support workflows such as:

Customer:
search location
→ select pickup
→ select destination
→ obtain fare estimate
→ select ride type
→ request ride
→ find driver
→ driver accepts
→ driver approaches pickup
→ customer pickup
→ trip starts
→ live trip tracking
→ trip completes
→ final fare calculated
→ payment
→ driver earning recorded
→ rating/review
→ receipt

Also cover:

* driver rejection
* no drivers available
* customer cancellation
* driver cancellation
* cancellation fees
* payment failure
* route changes
* additional stops
* waiting charges
* surge/dynamic pricing
* scheduled rides
* cash payments
* wallet payments
* refunds
* disputes

Food Delivery / Marketplace Domain

Consider:

* Merchant Service
* Restaurant Service
* Branch Service
* Restaurant Staff Service
* Catalog Service
* Menu Service
* Category Service
* Product / Item Service
* Modifier / Add-on Service
* Inventory / Availability Service
* Restaurant Hours Service
* Marketplace Search Service
* Restaurant Discovery Service
* Cart Service
* Checkout Service
* Food Order Service
* Order State Management Service
* Restaurant Order Management Service
* Courier Dispatch Service
* Delivery Service
* Courier Tracking Service
* Delivery Pricing Service
* Food Payment Integration
* Restaurant Settlement Service
* Courier Earnings Service
* Promotion Service
* Rating / Review Integration

Support workflows such as:

Customer:
select delivery address
→ discover restaurants
→ search/filter
→ open restaurant
→ browse menu
→ configure item/modifiers
→ add to cart
→ apply promotion
→ calculate fees/tax
→ checkout
→ authorize payment
→ submit order
→ restaurant accepts/rejects
→ food preparation
→ courier matching
→ courier accepts
→ courier arrives at restaurant
→ order pickup
→ delivery tracking
→ courier arrives at customer
→ delivery confirmation
→ capture/settle payment
→ restaurant settlement
→ courier earnings
→ customer receipt
→ review/rating

Also cover:

* restaurant offline
* item unavailable
* inventory changes after cart creation
* price changes
* minimum order
* delivery fee
* service fee
* tax
* tipping
* scheduled orders
* customer cancellation
* restaurant cancellation
* courier cancellation
* courier reassignment
* partial refund
* full refund
* failed payment
* delayed preparation
* delayed courier
* failed delivery
* wrong/missing items
* customer unreachable
* cash-on-delivery if configured

5. Configuration-Driven Architecture

Make the platform highly configurable.

Identify configuration that should NOT require deployments, including:

* countries
* cities
* currencies
* languages
* time zones
* service areas
* ride categories
* vehicle categories
* delivery zones
* base fares
* distance rates
* time rates
* minimum fares
* surge rules
* cancellation rules
* waiting fees
* service fees
* delivery fees
* platform commissions
* merchant commissions
* courier commissions
* taxes
* tips
* payment methods
* promotion rules
* restaurant operating rules
* driver eligibility
* courier eligibility
* order limits
* maximum delivery distance
* feature flags
* rollout percentages
* customer eligibility rules

Classify configuration as:

* global
* country
* city
* zone
* merchant
* restaurant
* branch
* ride type
* user segment
* environment

Document configuration precedence and override rules.

6. Documentation Repository Structure

Create a proposed repository similar to:

docs/
README.md
architecture/
SYSTEM_OVERVIEW.md
ARCHITECTURE.md
MICROSERVICES_MAP.md
DOMAIN_MAP.md
CONTEXT_MAP.md
DATA_OWNERSHIP.md
EVENT_ARCHITECTURE.md
API_STANDARDS.md
SECURITY_ARCHITECTURE.md
KEYCLOAK_ARCHITECTURE.md
DATABASE_ARCHITECTURE.md
CONFIGURATION_ARCHITECTURE.md
OBSERVABILITY.md
DEPLOYMENT_ARCHITECTURE.md
FAILURE_HANDLING.md
CONSISTENCY_STRATEGY.md
ADR_INDEX.md

workflows/
RIDE_WORKFLOWS.md
FOOD_ORDER_WORKFLOWS.md
PAYMENT_WORKFLOWS.md
DRIVER_WORKFLOWS.md
COURIER_WORKFLOWS.md
MERCHANT_WORKFLOWS.md
REFUND_WORKFLOWS.md

services/
/
README.md
BRD.md
SRS.md
ERD.md
INTEGRATION.md
WORKFLOWS.md

The final service list should be determined through domain analysis rather than blindly using this example.

7. Mandatory Documents for EVERY Microservice

For every service, generate these six Markdown documents:

README.md

Include:

* service name
* service purpose
* bounded context
* responsibilities
* responsibilities explicitly NOT owned by the service
* actors
* dependencies
* technology assumptions
* database ownership
* API overview
* events produced
* events consumed
* external integrations
* configuration
* security
* observability
* scalability
* local-development considerations
* deployment considerations

BRD.md

Business Requirements Document.

Include:

* document purpose
* business context
* objectives
* stakeholders
* actors/personas
* business capabilities
* business requirements
* business rules
* assumptions
* constraints
* dependencies
* business workflows
* exception workflows
* success criteria
* KPIs where relevant
* acceptance criteria

Give requirements unique IDs such as:

BR--001

Use tables where appropriate.

SRS.md

Software Requirements Specification.

Include:

* introduction
* scope
* system context
* actors
* functional requirements
* non-functional requirements
* API requirements
* data requirements
* validation rules
* state transitions
* authorization requirements
* configuration requirements
* error handling
* concurrency requirements
* idempotency requirements
* performance
* scalability
* availability
* security
* privacy
* auditability
* observability
* maintainability
* disaster recovery
* acceptance criteria

Give requirements IDs such as:

FR--001
NFR--001
SEC--001
DATA--001

Use MUST / SHOULD / MAY consistently.

ERD.md

Include:

* service-owned database name/schema
* entity descriptions
* Mermaid ER diagram
* table definitions
* columns
* PostgreSQL data types
* primary keys
* foreign keys internal to the service
* unique constraints
* indexes
* check constraints
* status/state fields
* audit columns
* timestamps
* soft-delete strategy if used
* JSONB usage if justified
* partitioning recommendations
* data-retention rules
* migration considerations

Use UUIDs consistently unless another identifier strategy is justified.

For cross-service entities, store external IDs without database-level cross-service foreign keys.

Example:

customer_id UUID NOT NULL

should represent the Customer Service identifier without a PostgreSQL FK to another service’s database.

Explain this explicitly.

INTEGRATION.md

Include:

Inbound APIs

For every endpoint specify:

* method
* URI
* purpose
* authentication
* required roles/scopes
* request fields
* response fields
* HTTP status codes
* validation
* idempotency
* error conditions

Outbound APIs

Document service dependencies.

Produced Events

For every event specify:

* event name
* producer
* topic/queue
* trigger
* schema
* example payload
* partition/routing key
* version
* consumers
* retry behavior
* dead-letter behavior

Consumed Events

Document:

* event
* producer
* reason consumed
* handler behavior
* duplicate-event handling
* retry behavior
* failure behavior

Reliability

Document:

* timeout
* retry
* exponential backoff
* circuit breakers
* bulkheads
* idempotency
* deduplication
* transactional outbox
* inbox pattern where useful
* dead-letter queues
* reconciliation jobs
* correlation IDs
* distributed tracing

WORKFLOWS.md

Document every important workflow.

For each workflow include:

* workflow name
* objective
* initiating actor
* participating services
* prerequisites
* happy path
* alternate paths
* failure paths
* business rules
* state transitions
* events
* APIs involved
* compensation/rollback
* final state

Use Mermaid sequence diagrams.

Example:

sequenceDiagram
    participant Customer
    participant Gateway
    participant Ride
    participant Pricing
    participant Dispatch
    participant Driver
    Customer->>Gateway: Request Ride
    Gateway->>Ride: Create Ride Request
    Ride->>Pricing: Request Fare
    Pricing-->>Ride: Fare
    Ride->>Dispatch: Find Driver
    Dispatch->>Driver: Offer Ride
    Driver-->>Dispatch: Accept
    Dispatch-->>Ride: Driver Assigned
    Ride-->>Customer: Ride Confirmed

8. Service State Machines

Where a domain entity has a lifecycle, define an explicit state machine.

At minimum include state machines for:

* ride request
* trip
* driver availability
* food order
* restaurant acceptance
* food preparation
* courier assignment
* delivery
* payment
* refund
* settlement
* promotion
* support ticket

For every state machine define:

* states
* valid transitions
* actor/system allowed to trigger transition
* prerequisites
* side effects
* emitted events
* invalid transitions
* timeout transitions
* compensation behavior

Provide Mermaid state diagrams.

9. Event-Driven Architecture

Create an event catalog.

Follow a naming standard such as:

domain.entity.event.v1

Example:

ride.request.created.v1
ride.driver.assigned.v1
trip.started.v1
trip.completed.v1
food.order.created.v1
food.order.accepted.v1
food.order.ready.v1
delivery.courier.assigned.v1
delivery.completed.v1
payment.authorized.v1
payment.captured.v1
payment.failed.v1
refund.completed.v1

For every event define:

* purpose
* owner
* producer
* consumers
* schema
* version
* aggregate ID
* event ID
* correlation ID
* causation ID
* occurred_at
* idempotency behavior
* retention
* ordering requirement

Design for eventual consistency.

Explicitly identify where strong consistency is required and where eventual consistency is acceptable.

10. Data Ownership Matrix

Create a global matrix with columns:

| Domain Entity | Owning Service | Database | Source of Truth | Referenced By | Sync Method |

Cover at least:

* Keycloak user
* customer
* driver
* courier
* vehicle
* merchant
* restaurant
* branch
* menu
* product
* address
* service zone
* cart
* food order
* delivery
* ride request
* trip
* payment
* wallet
* ledger transaction
* promotion
* review
* support ticket

Explicitly prevent ambiguous data ownership.

11. API Architecture

Define standards for:

* REST resource naming
* URI versioning
* pagination
* filtering
* sorting
* search
* errors
* validation
* idempotency keys
* correlation IDs
* tracing headers
* authentication
* authorization
* rate limiting
* API versioning
* deprecation
* backward compatibility

Define a standard error structure.

Example:

{
  "code": "ORDER_NOT_AVAILABLE",
  "message": "The requested order is not available.",
  "correlationId": "...",
  "details": []
}

12. Security Architecture

Document security for:

* Keycloak
* OAuth2/OIDC
* RBAC
* scopes
* service-to-service authentication
* secrets
* encryption in transit
* encryption at rest
* personally identifiable information
* payment information
* PCI-related boundaries
* audit logs
* suspicious activity
* brute-force protection
* API rate limiting
* token validation
* admin security
* least privilege
* tenant isolation if applicable

Never store raw payment-card information unless explicitly required by an external payment architecture.

Prefer payment-provider tokens.

13. Financial Architecture

Clearly separate:

* payment authorization
* payment capture
* payment failure
* payment reversal
* refund
* customer wallet
* driver earnings
* courier earnings
* merchant payable
* platform commission
* taxes
* tips
* settlement
* accounting/financial ledger

Design financial operations to be auditable and idempotent.

Recommend an immutable double-entry ledger where appropriate.

Document all major money flows.

14. Pricing Architecture

Support configurable pricing for both ride-hailing and food delivery.

Ride pricing may include:

fare =
base fare

* distance
* duration
* waiting
* tolls
* surge
* fees
* tax

* discounts

Food order pricing may include:

total =
items

* modifiers
* delivery fee
* service fee
* small-order fee
* tax
* tip

* discounts
* credits

Clearly distinguish:

* estimate
* quote
* authorization amount
* final amount

Document price snapshots so historical transactions are not changed when pricing configuration changes.

15. Geospatial Architecture

Design support for:

* latitude/longitude
* pickup/dropoff
* restaurant locations
* delivery addresses
* service zones
* geofencing
* driver/courier location
* nearest-driver queries
* delivery-radius checks
* distance
* ETA
* route-provider integrations

Evaluate PostgreSQL PostGIS and explain where it should be used.

Avoid storing extremely high-frequency live location updates inefficiently in normal transactional tables.

Describe an appropriate real-time location strategy.

16. Reliability and Distributed Transactions

Avoid distributed ACID transactions between microservices.

Document patterns including:

* Saga
* orchestration vs choreography
* transactional outbox
* inbox/deduplication
* idempotent consumers
* retries
* circuit breakers
* compensation
* reconciliation

Provide concrete examples for:

1. Ride completion + payment + driver earning.
2. Food order + payment + restaurant acceptance.
3. Food delivery + merchant settlement + courier earning.
4. Cancellation + refund.
5. Promotion redemption.

17. Observability

Each service must document:

* structured logging
* correlation IDs
* request IDs
* trace IDs
* metrics
* tracing
* health checks
* readiness checks
* liveness checks
* business metrics
* technical metrics
* alerting
* audit logging

Recommend OpenTelemetry-compatible architecture.

18. Deployment Architecture

Document:

* Docker
* Kubernetes
* namespaces
* services
* ingress/API gateway
* autoscaling
* resource limits
* health probes
* secrets
* configuration
* database connectivity
* migrations
* rolling deployments
* zero-downtime deployment considerations
* rollback
* environments

Environments:

* local
* development
* testing
* staging
* production

19. Configurable Multi-Region / Multi-Country Support

Design so that the platform can operate in multiple:

* countries
* cities
* currencies
* languages
* time zones
* tax systems
* payment providers
* SMS providers
* map providers
* business-rule configurations

Avoid hard-coding Talabat/trips-enjoy-specific behaviors.

Build a configurable platform inspired by their business capabilities rather than copying proprietary implementation details.

20. Administrative Capabilities

Document administrative workflows for:

* customer management
* driver approval
* courier approval
* vehicle approval
* merchant onboarding
* restaurant onboarding
* branch management
* menu moderation
* service zones
* pricing
* promotions
* payment review
* refunds
* disputes
* suspension
* fraud review
* support
* system configuration
* feature flags
* audit trails

Every sensitive admin action should be authenticated, authorized, and auditable.

21. Required Global Diagrams

Generate Mermaid diagrams for:

1. System context.
2. High-level architecture.
3. Domain decomposition.
4. Microservice dependency map.
5. Keycloak/authentication flow.
6. Ride request workflow.
7. Ride lifecycle.
8. Food ordering workflow.
9. Food delivery workflow.
10. Payment workflow.
11. Refund workflow.
12. Event architecture.
13. Deployment architecture.
14. Data ownership overview.

Then create detailed diagrams inside each service’s documentation.

22. Architecture Decision Records

Create ADR recommendations for major decisions such as:

* microservices architecture
* PostgreSQL per service
* PostgreSQL 18
* Keycloak
* REST
* event broker choice
* Redis
* PostGIS
* API gateway
* transactional outbox
* Saga pattern
* OpenTelemetry
* Kubernetes
* payment ledger architecture

Use:

docs/architecture/adrs/

with individual Markdown files.

23. Documentation Quality Rules

Do not produce shallow documentation.

Avoid statements such as:

“Payment Service handles payments.”

Instead specify:

* responsibilities
* boundaries
* inputs
* outputs
* APIs
* entities
* states
* events
* dependencies
* failures
* authorization
* business rules
* persistence
* configuration
* operational behavior

Every service should be detailed enough that a backend team could begin implementation from the documentation.

Do not duplicate ownership between services.

When functionality appears related to multiple services, define:

* owner
* collaborators
* integration mechanism
* source of truth

24. Traceability

Provide traceability between:

Business Requirement
→ Functional Requirement
→ API
→ Database entity
→ Workflow
→ Event
→ Acceptance Criterion

Use requirement IDs consistently.

25. Output Strategy

This is a very large system.

Do NOT attempt to generate every Markdown document in one uncontrolled response.

Work in phases.

Phase 1 — Architecture Discovery

First output:

1. Assumptions.
2. Business domains.
3. Proposed microservice catalog.
4. Responsibility of each service.
5. Data ownership.
6. Service dependency map.
7. Sync vs async communication decisions.
8. Recommended event broker and justification.
9. Global architecture.
10. Proposed documentation directory tree.

Do not write all service documents yet.

Phase 2 — Global Architecture Documentation

Generate the global files under:

docs/architecture/
docs/workflows/

Phase 3 — Shared Platform Services

Generate documentation service-by-service.

For each service generate:

README.md
BRD.md
SRS.md
ERD.md
INTEGRATION.md
WORKFLOWS.md

Phase 4 — Ride-Hailing Services

Generate all ride-domain service documentation.

Phase 5 — Food Marketplace Services

Generate all marketplace and restaurant-domain documentation.

Phase 6 — Delivery Services

Generate courier, dispatch, tracking, and delivery documentation.

Phase 7 — Financial Services

Generate payment, wallet, ledger, earnings, commission, refund, and settlement documentation.

Phase 8 — Operations

Generate admin, support, fraud, configuration, reporting, and audit documentation.

Phase 9 — Architecture Validation

Review the complete architecture for:

* duplicated responsibilities
* shared database coupling
* circular synchronous dependencies
* missing workflows
* missing events
* missing states
* missing failure handling
* security gaps
* payment consistency risks
* data ownership conflicts
* scalability bottlenecks
* single points of failure

Produce a final architecture validation report.

26. File Output Format

Whenever generating files, use this exact pattern:

— FILE: docs/services/trip-service/README.md —

Trip Service

…

— END FILE —

— FILE: docs/services/trip-service/BRD.md —

Trip Service — Business Requirements Document

…

— END FILE —

This allows the generated content to be saved directly into files.

Do not combine different documents without clear file boundaries.

27. Naming Standards

Use consistent names.

Examples:

* user-profile-service
* customer-service
* driver-service
* vehicle-service
* ride-service
* trip-service
* dispatch-service
* pricing-service
* merchant-service
* restaurant-service
* catalog-service
* cart-service
* food-order-service
* delivery-service
* courier-service
* payment-service
* wallet-service
* ledger-service
* notification-service

Use:

* kebab-case for service names
* snake_case for PostgreSQL tables/columns
* PascalCase for logical domain entities
* explicit versioning for events/APIs

28. Important Architectural Constraints

Strictly enforce:

1. No shared business database between services.
2. No cross-service database joins.
3. No cross-service foreign keys.
4. No service directly updates another service’s tables.
5. Keycloak is the authority for authentication identities.
6. Domain services remain authorities for domain profiles.
7. APIs/events are the integration boundaries.
8. Financial operations must be idempotent.
9. Important state transitions must be auditable.
10. Integration events must be versioned.
11. Consumers must tolerate duplicate delivery.
12. Business rules should be externally configurable where practical.
13. Historical orders/trips must preserve price and business-rule snapshots.
14. Every service has clearly documented ownership.
15. Every important workflow includes failure scenarios.

29. Architectural Review Before Generation

Before finalizing the service catalog, actively challenge the design.

For every proposed microservice ask:

* Does it have a genuine independent business capability?
* Does it own meaningful data or behavior?
* Does it require independent scaling?
* Does it have a distinct lifecycle?
* Is splitting it creating unnecessary distributed complexity?
* Would combining it with another bounded context be better?

Avoid both:

* a distributed monolith
* excessive nano-services

Explain important decisions.

30. Start Now

Start with PHASE 1 only.

Produce:

1. Architecture assumptions.
2. Domain/bounded-context map.
3. Complete recommended microservice catalog grouped by domain.
4. Responsibility and data ownership of every service.
5. Services that should NOT be separated and why.
6. Keycloak architecture overview.
7. PostgreSQL database-per-service strategy.
8. Synchronous API dependency map.
9. Asynchronous event dependency map.
10. Major event catalog.
11. Major end-to-end workflows.
12. Configuration hierarchy.
13. Security boundaries.
14. Proposed complete documentation repository tree.
15. Risks and architecture decisions that need special attention.

Use detailed Markdown tables and Mermaid diagrams.

Do not begin Phase 2 until Phase 1 has been fully designed.