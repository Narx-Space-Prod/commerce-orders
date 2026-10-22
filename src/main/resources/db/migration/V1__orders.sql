CREATE TABLE orders(id UUID PRIMARY KEY,session_id VARCHAR(64) NOT NULL,idempotency_key VARCHAR(64) NOT NULL,total INTEGER NOT NULL CHECK(total>0),currency VARCHAR(3) NOT NULL,items JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(session_id,idempotency_key));
CREATE INDEX orders_session_created ON orders(session_id,created_at DESC);
