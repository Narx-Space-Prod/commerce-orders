ALTER TABLE customers ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'customer' CHECK(role IN ('customer','admin'));
CREATE TABLE products (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    emoji VARCHAR(16) NOT NULL DEFAULT '◈',
    price INTEGER NOT NULL CHECK(price>0 AND price<=100000000),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    stock INTEGER NOT NULL DEFAULT 1000 CHECK(stock>=0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO products(id,name,description,emoji,price) VALUES
 ('mug','Incident Response Mug','A dependable companion for your morning coffee.','☕',1800),
 ('notebook','Runbook Notebook','A place for ideas, plans and your next great thing.','📓',1200),
 ('shirt','Ship It T-Shirt','An everyday favourite for people who make things happen.','👕',2900),
 ('keyboard','Deploy Keyboard','Find your flow, one keystroke at a time.','⌨️',7900);
ALTER TABLE orders ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'confirmed';
ALTER TABLE orders ADD COLUMN payment_status VARCHAR(32) NOT NULL DEFAULT 'demo';
ALTER TABLE orders ADD COLUMN provider_order_id VARCHAR(80) UNIQUE;
ALTER TABLE orders ADD COLUMN provider_payment_id VARCHAR(80) UNIQUE;
ALTER TABLE orders ADD COLUMN refund_id VARCHAR(80) UNIQUE;
ALTER TABLE orders ADD COLUMN refund_key UUID;
ALTER TABLE orders ADD COLUMN refund_reason VARCHAR(300);
ALTER TABLE orders ADD COLUMN stock_reserved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE TABLE payment_events(event_id VARCHAR(100) PRIMARY KEY,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE admin_audit (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES customers(id),
    action VARCHAR(40) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX orders_status_created ON orders(status,created_at DESC);
