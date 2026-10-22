CREATE TABLE customers (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE customer_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX customer_sessions_expiry ON customer_sessions(expires_at);
CREATE TABLE authentication_attempts (
    identity_hash CHAR(64) PRIMARY KEY,
    attempts INTEGER NOT NULL,
    window_start TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE orders ADD COLUMN customer_id UUID REFERENCES customers(id);
ALTER TABLE orders ADD COLUMN delivery JSONB;
CREATE INDEX orders_customer_created ON orders(customer_id,created_at DESC,id DESC);
