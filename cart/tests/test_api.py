"""Exercise HTTP behavior while replacing only the external Redis boundary."""

from unittest.mock import AsyncMock, MagicMock
import pytest
from fastapi.testclient import TestClient
import redis
import app as service

SESSION = "a" * 64
TARGET = "b" * 64


@pytest.fixture
def cart(monkeypatch):
    cache = MagicMock()
    cache.ping = AsyncMock(return_value=True)
    cache.hgetall = AsyncMock(return_value={"mug": "2", "book": "1"})
    cache.hdel = AsyncMock()
    cache.eval = AsyncMock()
    cache.aclose = AsyncMock()
    pipe = MagicMock()
    pipe.execute = AsyncMock()
    cache.pipeline.return_value.__aenter__ = AsyncMock(return_value=pipe)
    cache.pipeline.return_value.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(service, "client", cache)
    with TestClient(service.app) as http:
        yield http, cache, pipe
    cache.aclose.assert_awaited_once()


def test_cart_read_is_sorted_and_quantities_are_numeric(cart):
    http, cache, _ = cart
    response = http.get(f"/carts/{SESSION}")
    assert response.status_code == 200
    assert response.json() == {
        "items": [
            {"productId": "book", "quantity": 1},
            {"productId": "mug", "quantity": 2},
        ]
    }
    cache.hgetall.assert_awaited_with("cart:" + SESSION)


def test_quantity_update_batches_write_and_expiry_in_one_transaction(cart):
    http, cache, pipe = cart
    response = http.put(f"/carts/{SESSION}/items/mug", json={"quantity": 2})
    assert response.status_code == 200
    cache.pipeline.assert_called_once_with(transaction=True)
    pipe.hset.assert_called_once_with("cart:" + SESSION, "mug", 2)
    pipe.expire.assert_called_once_with("cart:" + SESSION, 604800)
    pipe.execute.assert_awaited_once()
    assert (
        http.put(f"/carts/{SESSION}/items/invalid@id", json={"quantity": 1}).status_code
        == 400
    )
    assert (
        http.put(f"/carts/{SESSION}/items/mug", json={"quantity": 0}).status_code == 422
    )
    assert http.put("/carts/invalid/items/mug", json={"quantity": 1}).status_code == 400


def test_delete_and_merge_preserve_the_validated_session_boundary(cart):
    http, cache, _ = cart
    assert http.delete(f"/carts/{SESSION}/items/mug").status_code == 200
    cache.hdel.assert_awaited_once_with("cart:" + SESSION, "mug")
    assert http.post(f"/carts/{SESSION}/merge/{TARGET}").status_code == 200
    cache.eval.assert_awaited_once_with(
        service.MERGE_CART, 2, "cart:" + SESSION, "cart:" + TARGET
    )
    cache.hgetall.assert_awaited_with("cart:" + TARGET)
    cache.eval.reset_mock()
    assert http.post(f"/carts/{SESSION}/merge/invalid").status_code == 400
    cache.eval.assert_not_awaited()


def test_dependency_failure_is_unready_without_breaking_liveness(cart):
    http, cache, _ = cart
    assert http.get("/health/live").status_code == 200
    assert http.get("/health/ready").status_code == 200
    cache.ping.side_effect = redis.RedisError("private connection details")
    unavailable = http.get("/health/ready")
    assert unavailable.status_code == 503
    assert unavailable.json() == {"error": "cart_unavailable"}
    assert http.get("/health/live").status_code == 200
    cache.hgetall.side_effect = redis.RedisError("private connection details")
    assert http.get(f"/carts/{SESSION}").json() == {"error": "cart_unavailable"}


def test_metrics_expose_bounded_routes_and_nonstandard_methods(cart):
    http, _, _ = cart
    assert http.request("TRACE", f"/carts/{SESSION}").status_code == 405
    metrics = http.get("/metrics")
    assert metrics.status_code == 200
    assert metrics.headers["content-type"].startswith("text/plain")
    assert 'method="OTHER"' in metrics.text
    assert SESSION not in metrics.text
