-- No customer address, email, session token or secret is placed on the queue.
CREATE TABLE order_outbox (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX order_outbox_pending ON order_outbox(next_attempt_at,created_at) WHERE published_at IS NULL;

CREATE TABLE processed_order_events (
    event_id UUID PRIMARY KEY REFERENCES order_outbox(id),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- An internal payment acknowledgement, not a tax invoice or an external email.
CREATE TABLE payment_receipts (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
    event_id UUID NOT NULL UNIQUE REFERENCES order_outbox(id),
    amount INTEGER NOT NULL CHECK(amount>0),
    currency CHAR(3) NOT NULL,
    payment_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
