"""Local payment test double. Not a Razorpay sandbox or a production service."""

import base64
import hashlib
import hmac
import json
import os
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ["FIXTURE_PAYMENT_SECRET"]
AUTH = "Basic " + base64.b64encode(f"rzp_test_fixture:{SECRET}".encode()).decode()
ORDERS, PAYMENTS, REFUNDS, REFUND_KEYS = {}, {}, {}, {}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Request bodies and auth headers must not be logged.

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        with LOCK:
            if self.path == "/health/live":
                return self.reply(200, {"status": "ok", "mode": "local-test-fixture"})
            if self.path.startswith("/_capture/"):
                order_id = self.path.removeprefix("/_capture/")
                if order_id not in ORDERS:
                    return self.reply(404, {"error": "unknown_order"})
                order = ORDERS[order_id]
                payment_id = order.setdefault("payment_id", "pay_" + uuid.uuid4().hex)
                PAYMENTS[payment_id] = dict(id=payment_id, order_id=order_id,
                    amount=order["amount"], currency=order["currency"], captured=True, status="captured")
                proof = hmac.new(SECRET.encode(), f"{order_id}|{payment_id}".encode(), hashlib.sha256).hexdigest()
                return self.reply(200, dict(razorpay_order_id=order_id,
                    razorpay_payment_id=payment_id, razorpay_signature=proof))
            if self.headers.get("Authorization") != AUTH:
                return self.reply(401, {"error": "unauthorized"})
            for prefix, values in [("/v1/orders/", ORDERS), ("/v1/payments/", PAYMENTS), ("/v1/refunds/", REFUNDS)]:
                if self.path.startswith(prefix):
                    value = values.get(self.path.removeprefix(prefix))
                    return self.reply(200 if value else 404, value or {"error": "not_found"})
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.headers.get("Authorization") != AUTH:
            return self.reply(401, {"error": "unauthorized"})
        size = int(self.headers.get("Content-Length", "0"))
        if size > 8192:
            return self.reply(413, {"error": "too_large"})
        try:
            body = json.loads(self.rfile.read(size))
        except (ValueError, UnicodeDecodeError):
            return self.reply(400, {"error": "invalid_json"})
        with LOCK:
            if self.path == "/v1/orders":
                order_id = "order_" + uuid.uuid4().hex
                value = dict(body, id=order_id)
                ORDERS[order_id] = value
                return self.reply(200, value)
            parts = self.path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "payments"] and parts[3] == "refund":
                payment_id = parts[2]
                if payment_id not in PAYMENTS:
                    return self.reply(404, {"error": "unknown_payment"})
                key = (payment_id, self.headers.get("X-Refund-Idempotency"))
                refund_id = REFUND_KEYS.setdefault(key, "rfnd_" + uuid.uuid4().hex)
                value = REFUNDS.setdefault(refund_id, dict(id=refund_id,
                    payment_id=payment_id, amount=body["amount"], status="processed"))
                return self.reply(200, value)
            self.reply(404, {"error": "not_found"})


if __name__ == "__main__":
    print("Local-only payment fixture listening on port 8090", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
