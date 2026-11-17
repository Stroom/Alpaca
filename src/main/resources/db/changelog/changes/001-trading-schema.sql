--liquibase formatted sql

--changeset alpaca-trader:001-account
CREATE TABLE account (
    id               UUID PRIMARY KEY,              -- Alpaca account id
    account_number   VARCHAR(32),
    status           VARCHAR(32)  NOT NULL,         -- SUBMITTED, APPROVED, ACTIVE, ...
    given_name       VARCHAR(100),
    family_name      VARCHAR(100),
    email            VARCHAR(255),
    currency         VARCHAR(8),
    alpaca_created_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);

--changeset alpaca-trader:001-funding
CREATE TABLE funding_transfer (
    id               UUID PRIMARY KEY,              -- Alpaca id of the simulated incoming wire
    account_id       UUID         NOT NULL REFERENCES account (id),
    method           VARCHAR(32)  NOT NULL,         -- INCOMING_WIRE
    amount           NUMERIC(20, 2) NOT NULL,
    currency         VARCHAR(8)   NOT NULL,
    status           VARCHAR(32)  NOT NULL,         -- QUEUED -> EXECUTED (when the CSD activity arrives)
    activity_event_id VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX funding_transfer_account_idx ON funding_transfer (account_id);

--changeset alpaca-trader:001-instrument
CREATE TABLE instrument (
    id               UUID PRIMARY KEY,              -- Alpaca asset id
    symbol           VARCHAR(32)  NOT NULL UNIQUE,
    name             VARCHAR(255),
    exchange         VARCHAR(32),
    asset_class      VARCHAR(32),
    status           VARCHAR(16),
    tradable         BOOLEAN,
    fractionable     BOOLEAN,
    shortable        BOOLEAN,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX instrument_name_idx ON instrument (lower(name));

--changeset alpaca-trader:001-price-quote
CREATE TABLE price_quote (
    id               BIGSERIAL PRIMARY KEY,
    symbol           VARCHAR(32)  NOT NULL,
    feed             VARCHAR(16),
    bid_price        NUMERIC(20, 6),
    bid_size         NUMERIC(20, 6),
    ask_price        NUMERIC(20, 6),
    ask_size         NUMERIC(20, 6),
    last_price       NUMERIC(20, 6),
    quote_time       TIMESTAMPTZ,
    trade_time       TIMESTAMPTZ,
    daily_open       NUMERIC(20, 6),
    daily_high       NUMERIC(20, 6),
    daily_low        NUMERIC(20, 6),
    daily_close      NUMERIC(20, 6),
    daily_volume     NUMERIC(24, 2),
    prev_close       NUMERIC(20, 6),
    fetched_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX price_quote_symbol_idx ON price_quote (symbol, fetched_at DESC);

--changeset alpaca-trader:001-trade-order
CREATE TABLE trade_order (
    id               UUID PRIMARY KEY,              -- Alpaca order id
    client_order_id  VARCHAR(128) NOT NULL UNIQUE,
    account_id       UUID         NOT NULL REFERENCES account (id),
    quote_id         BIGINT       REFERENCES price_quote (id), -- price seen when the order was placed
    symbol           VARCHAR(32)  NOT NULL,
    asset_class      VARCHAR(32),
    side             VARCHAR(8)   NOT NULL,
    order_type       VARCHAR(32)  NOT NULL,
    time_in_force    VARCHAR(8),
    qty              NUMERIC(20, 9),
    notional         NUMERIC(20, 2),
    limit_price      NUMERIC(20, 6),
    stop_price       NUMERIC(20, 6),
    status           VARCHAR(32)  NOT NULL,
    filled_qty       NUMERIC(20, 9),
    filled_avg_price NUMERIC(20, 6),
    submitted_at     TIMESTAMPTZ,
    filled_at        TIMESTAMPTZ,
    canceled_at      TIMESTAMPTZ,
    alpaca_updated_at TIMESTAMPTZ,                  -- order.updated_at, guards against out-of-order events
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX trade_order_account_idx ON trade_order (account_id, created_at DESC);

--changeset alpaca-trader:001-trade-event
CREATE TABLE trade_event (
    event_id         VARCHAR(64)  PRIMARY KEY,      -- ULID from Alpaca, makes processing idempotent
    account_id       UUID         NOT NULL REFERENCES account (id),
    order_id         UUID         NOT NULL REFERENCES trade_order (id),
    event_type       VARCHAR(32)  NOT NULL,         -- new, fill, partial_fill, canceled, ...
    event_at         TIMESTAMPTZ  NOT NULL,
    execution_id     VARCHAR(64),
    execution_time   TIMESTAMPTZ,
    price            NUMERIC(20, 6),
    qty              NUMERIC(20, 9),
    position_qty     NUMERIC(20, 9),
    settle_date      DATE,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX trade_event_order_idx ON trade_event (order_id, event_at);

--changeset alpaca-trader:001-settlement
CREATE TABLE settlement (
    id               BIGSERIAL PRIMARY KEY,
    execution_id     VARCHAR(64)  NOT NULL UNIQUE,  -- one settlement per execution (fill / partial fill)
    trade_event_id   VARCHAR(64)  NOT NULL REFERENCES trade_event (event_id),
    order_id         UUID         NOT NULL REFERENCES trade_order (id),
    account_id       UUID         NOT NULL REFERENCES account (id),
    symbol           VARCHAR(32)  NOT NULL,
    side             VARCHAR(8)   NOT NULL,
    qty              NUMERIC(20, 9) NOT NULL,
    price            NUMERIC(20, 6) NOT NULL,
    gross_amount     NUMERIC(20, 2) NOT NULL,       -- signed: buy = cash out (negative), sell = cash in
    trade_date       DATE         NOT NULL,
    settle_date      DATE,
    status           VARCHAR(16)  NOT NULL,         -- PENDING -> SETTLED
    activity_event_id VARCHAR(64),                  -- FILL activity confirming the booking
    activity_status  VARCHAR(16),
    settled_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX settlement_order_idx ON settlement (order_id);
CREATE INDEX settlement_pending_idx ON settlement (status, settle_date);

--changeset alpaca-trader:001-account-activity
CREATE TABLE account_activity (
    event_id         VARCHAR(64)  PRIMARY KEY,
    account_id       UUID         NOT NULL REFERENCES account (id),
    activity_type    VARCHAR(16)  NOT NULL,         -- FILL, CSD, JNLC, DIV, FEE, ...
    activity_subtype VARCHAR(16),
    order_id         UUID,
    symbol           VARCHAR(32),
    qty              NUMERIC(20, 9),
    price            NUMERIC(20, 6),
    net_amount       NUMERIC(20, 2),
    currency         VARCHAR(8),
    status           VARCHAR(16),
    executed_at      TIMESTAMPTZ,
    settle_date      DATE,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);
CREATE INDEX account_activity_account_idx ON account_activity (account_id, executed_at DESC);

--changeset alpaca-trader:001-funding-event
CREATE TABLE funding_event (
    event_id         VARCHAR(64)  PRIMARY KEY,
    account_id       UUID         NOT NULL REFERENCES account (id),
    entity_type      VARCHAR(32)  NOT NULL,         -- Transfer, BankRelationship, WireBank, ...
    entity_id        UUID,
    status_from      VARCHAR(32),
    status_to        VARCHAR(32)  NOT NULL,
    reason           TEXT,
    event_at         TIMESTAMPTZ  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw              JSONB
);

--changeset alpaca-trader:001-event-cursor
CREATE TABLE event_stream_cursor (
    stream           VARCHAR(32)  PRIMARY KEY,      -- trades, activities, funding
    last_event_id    VARCHAR(64)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
