# commerce-orders

Owner: Orders team.

Checkout, payment-webhook verification, order history, refunds and order state. The orders-owned worker and transactional outbox stay in this repository and deploy independently from the API.

Project guide: [DOC.md](https://github.com/Narx-Space-Prod/platform-infrastructure/blob/main/DOC.md).
The guide is introduced in the infrastructure foundation PR and becomes available on main after review.

## Current state

Foundation only. Application code, cloud infrastructure and end-to-end delivery are not deployed.
All changes after repository initialization go through feature branches and pull requests.
These private repositories use GitHub Free; review enforcement requires the planned Team upgrade.

## Application delivery contract

- Build and release this service independently; preserve existing commerce features during migration.
- Serve health endpoints at `/health/live` and `/health/ready` on an unprivileged port.
- Run as a non-root user with a read-only root filesystem; use `/tmp` for temporary data.
- Handle SIGTERM gracefully and report logs to stdout/stderr without secrets or payment data.
- Expose metrics; propagate request/event IDs across API and queue boundaries.
- Publish immutable images to ECR and promote the same digest between environments.

The foundation workflow only validates workflow syntax. Application tests and builds will be
added with application source; a passing foundation check does not certify an application release.
