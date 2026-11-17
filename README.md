# alpaca-trader

Spring Boot 4 service around the Alpaca **Broker API** (`broker-api.json`) and **Market Data API**
(`market-data-api.json`): place orders, receive fill/fulfilment events, and track instrument prices.
It runs a full trade flow (account → funding → instrument → quote → order → events → settlement), stores every
step in Postgres (Liquibase schema), and has a web UI at http://localhost:8080. See [Solution.md](Solution.md) for
the design: database tables, Alpaca endpoints used, and how Alpaca streams events back.
The pass-through endpoints under `/api/broker` and `/api/market` call Alpaca directly.

## Auth

The service gets an OAuth2 token with the `client_credentials` grant from
`https://authx.sandbox.alpaca.markets/v1/oauth2/token`, caches it, and refreshes it about 60 seconds
before it expires (tokens last 900 seconds). It sends the token as `Authorization: Bearer …` to both APIs.
If Alpaca returns a 401, the service refreshes the token and retries once.

## Setup

```bash
cp .env.example .env   # fill in ALPACA_CLIENT_ID / ALPACA_CLIENT_SECRET / ALPACA_CORRESPONDENT
```

Run everything in Docker (app on :8080, Postgres on host port 5433):

```bash
docker compose up -d --build
```

Or run only Postgres in Docker and the app locally (Java 25+):

```bash
docker compose up -d db
./gradlew bootRun   # export the .env variables first
```

Swagger UI: http://localhost:8080/swagger-ui.html

The defaults target the sandbox. To use production, set `ALPACA_TOKEN_URL`, `ALPACA_BROKER_URL` and
`ALPACA_MARKET_DATA_URL` (see `.env.example`).

## Endpoints

Query parameters and JSON bodies are passed through to Alpaca unchanged, and Alpaca error
responses come back with their original status and body.

| Purpose | Endpoint | Alpaca |
|---|---|---|
| Accounts | `GET /api/broker/accounts`, `GET /api/broker/accounts/{id}` | `/v1/accounts` |
| Buying power / equity | `GET /api/broker/accounts/{id}/trading` | `/v1/trading/accounts/{id}/account` |
| Place order | `POST /api/broker/accounts/{id}/orders` | `POST /v1/trading/accounts/{id}/orders` |
| List / get orders | `GET …/orders`, `GET …/orders/{orderId}`, `GET …/orders/by-client-order-id/{cid}` | |
| Replace / cancel | `PATCH …/orders/{orderId}`, `DELETE …/orders/{orderId}`, `DELETE …/orders` | |
| Positions | `GET …/positions`, `GET …/positions/{symbol}` | |
| Fulfilment events (SSE) | `GET /api/broker/events/trades` | `/v2/events/trades` |
| Assets, clock, calendar | `GET /api/broker/assets[/{symbol}]`, `/api/broker/clock`, `/api/broker/calendar` | |
| Stock prices | `GET /api/market/stocks/{quotes,trades,bars}/latest?symbols=AAPL,MSFT`, `/stocks/snapshots`, `/stocks/bars` | `/v2/stocks/...` |
| Single stock | `GET /api/market/stocks/{symbol}/snapshot`, `/quotes/latest`, `/trades/latest` | |
| Crypto | `GET /api/market/crypto/us/{quotes/latest,snapshots,bars}?symbols=BTC/USD` | `/v1beta3/crypto/us/...` |

Examples:

```bash
curl -X POST localhost:8080/api/broker/accounts/$ACCOUNT/orders -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","qty":"1","side":"buy","type":"market","time_in_force":"day"}'
curl -N localhost:8080/api/broker/events/trades
curl 'localhost:8080/api/market/stocks/quotes/latest?symbols=AAPL,MSFT&feed=iex'
```
