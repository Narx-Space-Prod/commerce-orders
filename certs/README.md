# RDS trust bundle

This public AWS certificate bundle is retained from the source application. It is
not an application secret. Before an AWS release, verify its checksum, review AWS
certificate rotation guidance and refresh it from the official AWS trust store if needed:

```bash
cd certs
sha256sum --check global-bundle.sha256
# Refresh only as a reviewed change, then rebuild the application image.
curl --fail --location https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem --output global-bundle.pem
sha256sum global-bundle.pem > global-bundle.sha256
```

AWS connections must use `sslmode=verify-full` with this trust bundle. Local PostgreSQL
connections do not require it. Never downgrade TLS verification to bypass a missing bundle.
