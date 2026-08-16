# Migrations

The `api-gateway` is a **stateless** service per
[`docs/services/api-gateway/ERD.md`](../../docs/services/api-gateway/ERD.md) §1
("Database: not applicable — the gateway owns no PostgreSQL
schema"). There are therefore no `CREATE TABLE`, `Flyway`, or
`golang-migrate` files in this directory. Configuration is
delivered via `configuration-service` (`gateway.*` keys per
[README §13](../../docs/services/api-gateway/README.md#13-configuration))
and hot-reloaded in-process through the
[`configuration.updated.v1`](../../docs/services/api-gateway/INTEGRATION.md#44-configurationupdatedv1)
event.

If a future change adds persistent state to the gateway (which
would violate the platform's "edge is stateless" rule per
[ERD §1.5](../../docs/services/api-gateway/ERD.md#15-stateless-architecture-note)),
it MUST be done by adding a *new* service rather than a new
schema to the gateway. See ERD §11.
