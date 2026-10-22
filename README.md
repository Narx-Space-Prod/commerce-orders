# commerce-orders

Owner: Orders team.

Customer accounts, checkout, payment verification, order history, refunds and order
state. Inventory reservation and order insertion currently share one SQL transaction
in the commerce core. The cart service lives in `cart/` under the same owning team
and has its own image and deployment. The planned outbox and worker are not implemented yet.

Project guide: [DOC.md](https://github.com/Narx-Space-Prod/platform-infrastructure/blob/main/DOC.md).

## Current state

The Spring Boot/Java application, SQL migrations and tests are in `src/`. The Python
cart API, hashed dependency locks and tests are in `cart/`. `tests/fixtures/razorpay.py`
is a local payment simulator used only by the integrated developer preview.
AWS deployment, SQS/outbox processing and GitOps promotion remain to be implemented.
All changes after repository initialization go through feature branches and pull requests.
These private repositories use GitHub Free; review enforcement requires the planned Team upgrade.

## Application delivery contract

- Build and release this service independently; preserve existing commerce features during migration.
- Serve health endpoints at `/health/live` and `/health/ready` on an unprivileged port.
- Run as a non-root user with a read-only root filesystem; use `/tmp` for temporary data.
- Handle SIGTERM gracefully and report logs to stdout/stderr without secrets or payment data.
- Expose metrics; propagate request/event IDs across API and queue boundaries.
- Publish immutable images to ECR and promote the same digest between environments.

## Build and preview

```bash
docker build --target runtime -t narx-local/orders:dev .
docker build --target runtime -t narx-local/cart:dev cart
```

Each runtime build runs its own tests. The Java API uses port 8083 and PostgreSQL;
the cart uses port 8082 and Redis. Required configuration and the integrated startup
commands are in the project guide and `platform-gitops/local/compose.yaml`.
Payment provider credentials stay on the backend. Only Razorpay test keys are accepted.
The local simulator is not the Razorpay sandbox and must not be deployed to production.
Checkout requires a signed-in customer and delivery information. The migrated app
does not retain the old anonymous demo-order path that confirmed orders without payment.

The orders API exposes Prometheus metrics at `/actuator/prometheus`.
Health endpoints follow `/health/live` and `/health/ready`.
The cart exposes `/metrics` and the same health paths.

Source provenance (licenses retained):

- [boutique-orders, revision 662f897](https://github.com/Local-Narx-Space/boutique-orders/tree/662f897d6275714680f37c699073fe847714a0d1)
- [boutique-cart, revision 7b52f23](https://github.com/Local-Narx-Space/boutique-cart/tree/7b52f23a63ec8f1a9a942cebdf93420e9ef9b99b)
