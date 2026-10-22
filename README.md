# commerce-orders

Owner: Orders team.

Customer accounts, checkout, payment verification, order history, refunds and order
state. Inventory reservation and order insertion currently share one SQL transaction
in the commerce core. The cart service lives in `cart/` under the same owning team
and has its own image and deployment. The API, outbox publisher and receipt worker
share one Java image and run as independently scalable processes.

Project guide: [DOC.md](https://github.com/Narx-Space-Prod/platform-infrastructure/blob/main/DOC.md).

## Current state

The Spring Boot/Java application, SQL migrations and tests are in `src/`. The Python
cart API, hashed dependency locks and tests are in `cart/`. `tests/fixtures/razorpay.py`
is a local payment simulator used only by the integrated developer preview.
Local SQS-compatible processing is implemented; AWS deployment and GitOps promotion remain pending.
All changes after repository initialization go through feature branches and pull requests.
These repositories are public on GitHub Free. Protected `main` requires a CODEOWNER
approval, passing CI checks and resolved conversations; administrators follow the same rules.

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

## Background delivery roles

`DELIVERY_ROLE=api` is the default and always records an outbox event with each newly
confirmed payment. `publisher` sends due events; `worker` creates an internal payment
receipt and inbox marker atomically, then acknowledges the message. These roles use
the same tested image, not additional builds. Order detail returns `receipt` when ready.
No email, shipping call or tax invoice is generated. Existing paid orders are not backfilled.

Required background configuration: `DELIVERY_QUEUE_URL` and `AWS_REGION`, plus the
same database configuration as the API. Real AWS uses the default SDK credential
chain. Only the local preview sets `DELIVERY_LOCAL=true` and `DELIVERY_ENDPOINT`.
Visibility defaults to 60 seconds, exceeding the 10-second processing transaction.
Worker/publisher readiness checks both database and queue; API readiness requires
only its database. Only the API should receive application traffic.

The local Compose preview has persistent ElasticMQ queues, five-receive DLQ redrive
and a separate opt-in failure exercise. DOC.md explains commands, metrics, retries,
replay, retention limitations and real AWS validation still required.

## Delivery

PRs run shared workflow/secret checks, then component Docker tests, a non-root runtime
check, a blocking HIGH/CRITICAL vulnerability scan and SBOM generation. Trusted main
can publish the exact tested image to ECR through OIDC and sign its digest, provenance
and SBOM. Publishing is held unless `DELIVERY_ENABLED=true`; no AWS resources are
created by this repository. GitOps verifies release records and promotes the same
digests through reviewed dev, QA and production PRs.

App credentials, AWS setup and local registry refresh are documented in the single
[project guide](https://github.com/Narx-Space-Prod/platform-infrastructure/blob/main/DOC.md).
