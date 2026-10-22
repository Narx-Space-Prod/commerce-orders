import json, logging, os, re, time
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Request, Response
from pydantic import BaseModel, Field
import redis.asyncio as redis
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
logging.basicConfig(level=logging.INFO, format="%(message)s")
client=redis.from_url(os.environ.get("REDIS_URL","redis://localhost:6379/0"),decode_responses=True,socket_connect_timeout=2,socket_timeout=2)
requests=Counter("boutique_http_requests_total","HTTP requests",["service","method","route","status"])
latency=Histogram("boutique_http_request_duration_seconds","Latency",["service","method","route","status"], buckets=(.01,.05,.1,.25,.5,1,2.5,5,10))
@asynccontextmanager
async def lifespan(app):
    yield
    await client.aclose()
app=FastAPI(lifespan=lifespan)
@app.exception_handler(redis.RedisError)
async def unavailable(request,exc):return Response('{"error":"cart_unavailable"}',503,media_type="application/json")
@app.middleware("http")
async def observe(request:Request,call_next):
    started=time.monotonic(); response=await call_next(request)
    route=request.scope.get("route"); name=route.path if route else "unmatched"
    if not name.startswith("/health/") and name!="/metrics":
        method=request.method if request.method in {"GET","PUT","DELETE","POST","HEAD","OPTIONS"} else "OTHER"
        requests.labels("cart",method,name,response.status_code).inc();latency.labels("cart",method,name,response.status_code).observe(time.monotonic()-started)
    logging.info(json.dumps({"route":name,"status":response.status_code,"request_id":request.headers.get("x-request-id","")}));return response
def key(session):
    if len(session)!=64 or any(c not in "0123456789abcdef" for c in session):raise HTTPException(400,"invalid_session")
    return "cart:"+session
class Item(BaseModel):quantity:int=Field(ge=1,le=20,strict=True)
@app.get("/health/live")
async def live():return {"status":"ok"}
@app.get("/health/ready")
async def ready():
    await client.ping();return {"status":"ok"}
@app.get("/metrics")
async def metrics():return Response(generate_latest(),headers={"Content-Type":CONTENT_TYPE_LATEST})
@app.get("/carts/{session}")
async def get_cart(session:str):return {"items":[{"productId":p,"quantity":int(q)} for p,q in sorted((await client.hgetall(key(session))).items())]}
@app.put("/carts/{session}/items/{product}")
async def put(session:str,product:str,item:Item):
    if not re.fullmatch(r"[a-z0-9-]{1,64}",product):raise HTTPException(400,"invalid_product")
    k=key(session)
    async with client.pipeline(transaction=True) as pipe:
        pipe.hset(k,product,item.quantity);pipe.expire(k,604800);await pipe.execute()
    return await get_cart(session)
@app.delete("/carts/{session}/items/{product}")
async def delete(session:str,product:str):
    await client.hdel(key(session),product);return await get_cart(session)

# Atomic, retry-safe transfer of a guest bag into an authenticated customer bag.
# Only the frontend can reach these private cart routes through the lab policy.
MERGE_CART = """
if KEYS[1] == KEYS[2] then return 0 end
local items = redis.call('HGETALL', KEYS[1])
for index = 1, #items, 2 do
  local old = tonumber(redis.call('HGET', KEYS[2], items[index]) or '0')
  redis.call('HSET', KEYS[2], items[index], math.min(20, old + tonumber(items[index+1])))
end
if #items > 0 then redis.call('EXPIRE', KEYS[2], 604800) end
redis.call('DEL', KEYS[1])
return #items / 2
"""
@app.post("/carts/{session}/merge/{target}")
async def merge_cart(session:str,target:str):
    await client.eval(MERGE_CART,2,key(session),key(target))
    return await get_cart(target)
