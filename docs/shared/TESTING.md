# Testing

`platform-spring-boot-test` (test-only artifact) provides the
test-harness services depend on: `BaseIntegrationTest`,
Testcontainers bundles, JWT minting, and a few MockMvc/WebTestClient
extensions.

Add it as a `testImplementation` dependency:

```kotlin
testImplementation("com.trips-enjoy.platform:platform-spring-boot-test:4.1.0")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:kafka")
testImplementation("org.testcontainers:junit-jupiter")
```

The test artifact pulls in:
- JUnit 5.11 + Jupiter + Vintage
- MockK 1.13
- AssertJ 3.25
- Awaitility 4.2
- Testcontainers 1.21
- Spring Boot Test (auto-configured)
- Spring Cloud Contract (for contract tests)
- RestAssured 5.4 (optional)

---

## 1. `BaseIntegrationTest`

Every integration test in a Spring Boot service extends this class.

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class BaseIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var testRestTemplate: TestRestTemplate

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var webTestClient: WebTestClient

    @Autowired
    protected lateinit var jwt: JwtTestUtils

    @Autowired
    protected lateinit var outbox: OutboxTestUtils

    @Container
    @ServiceConnection
    protected val postgres = TestPostgresContainer()

    @Container
    @ServiceConnection
    protected val kafka = TestKafkaContainer()

    @Container
    @ServiceConnection
    protected val redis = TestRedisContainer()

    @Container
    @ServiceConnection
    protected val keycloak = TestKeycloakContainer()

    @BeforeEach
    fun resetState() {
        // Truncate service schema
        // Clear Redis
        // Reset outbox
    }
}
```

Notes:
- Uses Spring Boot 4's `@ServiceConnection` for zero-config Testcontainers wiring.
- The 4 containers (Postgres, Kafka, Redis, Keycloak) start in parallel.
- `resetState()` runs before every test — fast because all 4 are in-memory or tmpfs.
- `outbox: OutboxTestUtils` exposes helpers to assert on emitted events (see 4).
- `jwt: JwtTestUtils` exposes `jwt.userToken()`, `jwt.adminToken(role)`, etc.

### Concrete example

```kotlin
class PaymentCaptureIT : BaseIntegrationTest() {

    @Autowired
    lateinit var paymentRepo: PaymentIntentRepository

    @Test
    fun `captures an authorized payment`() {
        val payment = paymentRepo.save(
            PaymentIntent(money = Money.of("19.99", "USD"), status = PaymentStatus.AUTHORIZED)
        )

        val response = testRestTemplate.exchange(
            "/v1/payments/${payment.id.value}/capture",
            HttpMethod.POST,
            HttpEntity(null, jwt.userToken("customer-123").authHeader()),
            PaymentResponse::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(paymentRepo.findById(payment.id).get().status).isEqualTo(PaymentStatus.CAPTURED)
    }

    @Test
    fun `returns 404 with RFC 7807 when payment not found`() {
        val response = testRestTemplate.exchange(
            "/v1/payments/${UUID.randomUUID()}/capture",
            HttpMethod.POST,
            HttpEntity(null, jwt.userToken("customer-123").authHeader()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThatJson(response.body).node("code").isEqualTo("PAYMENT_NOT_FOUND")
    }

    @Test
    fun `admin force-capture emits audit event`() {
        val payment = paymentRepo.save(
            PaymentIntent(money = Money.of("19.99", "USD"), status = PaymentStatus.AUTHORIZED)
        )

        testRestTemplate.exchange(
            "/admin/v1/payments/${payment.id.value}/force-capture",
            HttpMethod.POST,
            HttpEntity("""{"reasonCode":"support-ticket-#12345"}""", jwt.adminToken("platform.admin").authHeader()),
            String::class.java,
        )

        outbox.assertEmitted(
            topic = "audit.admin.payment.v1",
            key = payment.id.value.toString(),
            predicate = { it.getString("action") == "force-capture" },
        )
    }
}
```

---

## 2. `JwtTestUtils`

Mint Keycloak-shaped JWTs for tests.

```kotlin
@Autowired lateinit var jwt: JwtTestUtils

// Customer token
val customerToken = jwt.userToken(
    subject = "customer-123",
    roles = listOf("customer"),
)
val headers = HttpHeaders().apply { setBearerAuth(customerToken.value) }

// Admin token with a specific role
val adminToken = jwt.adminToken(role = "platform.admin")
val headers = HttpHeaders().apply { setBearerAuth(adminToken.value) }

// Admin token with a service-specific role
val paymentAdminToken = jwt.adminToken(role = "payment.admin")
```

The minted tokens are signed with a test key that the
`TestKeycloakContainer`'s JWKS endpoint publishes. The Spring Boot
resource server is configured to trust that JWKS during tests. This
matches production behaviour — the test JWTs go through the same
validation pipeline.

---

## 3. `OutboxTestUtils`

Assert on events emitted via the outbox.

```kotlin
@Autowired lateinit var outbox: OutboxTestUtils

// Assert an event was emitted to a specific topic
outbox.assertEmitted(
    topic = "payment.intent.captured.v1",
    key = paymentId.toString(),
    predicate = { json ->
        json.getString("paymentId") == paymentId.toString() &&
        json.getString("currency") == "USD"
    },
)

// Wait for an event to arrive (with timeout)
val event = outbox.awaitEmitted(
    topic = "payment.intent.captured.v1",
    timeout = Duration.ofSeconds(5),
    predicate = { it.getString("paymentId") == paymentId.toString() },
)

// Consume from a topic directly
val consumer = outbox.consumer("payment.intent.captured.v1")
val record = consumer.poll(Duration.ofSeconds(5))
```

`OutboxTestUtils` reads from the same `outbox_event` table the
`OutboxRelay` writes to, so it sees events *before* they hit Kafka.
For tests that exercise the consumer, use the Kafka TestConsumer
directly.

---

## 4. Test slices

The library also provides test-slice annotations that load only the
parts of the context you need:

| Annotation | Loads | Use for |
|---|---|---|
| `@PlatformWebMvcTest` | Web layer + security | Controller tests with MockMvc |
| `@PlatformDataJpaTest` | Data layer (with Testcontainers) | Repository tests |
| `@PlatformKafkaTest` | Kafka layer | Producer/consumer tests |
| `@PlatformCacheTest` | Redis layer | Cache tests |

```kotlin
@PlatformWebMvcTest(PaymentController::class)
class PaymentControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var commands: PaymentCommandService

    @Test
    fun `POST capture returns 200`() {
        every { commands.capture(any()) } returns PaymentIntent(Money.of("19.99", "USD"))

        mockMvc.perform(
            post("/v1/payments/{id}/capture", UUID.randomUUID())
                .with(jwt().userToken("customer-123"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.amount").value("19.99"))
    }
}
```

---

## 5. Contract tests

The library supports Spring Cloud Contract for producer/consumer
contract testing. The `payment-service` (producer) defines a contract
in `src/test/resources/contracts/`, the consumer (e.g. ``food-order-service` (checkout)`)
verifies against the same contract.

```groovy
// services/payment-service/src/test/resources/contracts/payment_capture.groovy
Contract.make {
    request {
        method POST()
        url '/v1/payments/abc123/capture'
        headers {
            contentType(applicationJson())
            header 'Authorization': $(anyNonEmptyString())
        }
    }
    response {
        status OK()
        headers { contentType(applicationJson()) }
        body([
            id:        "abc123",
            status:    "CAPTURED",
            amount:    "19.99",
            currency:  "USD"
        ])
    }
}
```

Contracts are published as a stub JAR; consumers add the JAR as a
test dependency and Spring Cloud Contract verifies at consumer build
time.

---

## 6. Coverage bar

Every service is expected to maintain:

- ≥ 80% line coverage on production code.
- 100% coverage on `BusinessException` throws.
- Every public REST endpoint has at least one happy-path IT.
- Every admin endpoint has at least one IT that asserts the audit
  event was emitted.
- Every state-machine transition has an IT.

Coverage is reported in CI; a service that drops below 80% is
flagged.

---

## 7. Performance / load tests

The library does **not** ship a load-test framework. Each service
defines its own load tests in `src/test/kotlin/loadtest/`, run via
[k6](https://k6.io) or [Gatling](https://gatling.io). The shared
library's role is to make sure the service starts up cleanly and
exposes the metrics a load test needs (`/actuator/prometheus`).

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
