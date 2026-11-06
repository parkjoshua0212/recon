CREATE TABLE feed (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    feed_type         VARCHAR(16)  NOT NULL,
    file_hash         CHAR(64)     NOT NULL,
    original_filename VARCHAR(255) NULL,
    total_rows        INT          NOT NULL,
    accepted_rows     INT          NOT NULL,
    rejected_rows     INT          NOT NULL,
    uploaded_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_feed_type_hash UNIQUE (feed_type, file_hash),
    CONSTRAINT ck_feed_type CHECK (feed_type IN ('LEDGER', 'EXTERNAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- txn_id is deliberately not unique: duplicates must be stored so they can be reported.
CREATE TABLE feed_transaction (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    feed_id     BIGINT          NOT NULL,
    line_number INT             NOT NULL,
    txn_id      VARCHAR(64)     NOT NULL,
    account_id  VARCHAR(64)     NOT NULL,
    asset       VARCHAR(16)     NOT NULL,
    amount      DECIMAL(38, 18) NOT NULL,
    status      VARCHAR(16)     NOT NULL,
    txn_time    DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_feed_transaction_line UNIQUE (feed_id, line_number),
    INDEX idx_feed_transaction_txn (feed_id, txn_id),
    CONSTRAINT fk_feed_transaction_feed FOREIGN KEY (feed_id) REFERENCES feed (id),
    CONSTRAINT ck_feed_transaction_status CHECK (status IN ('PENDING', 'SETTLED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE feed_row_error (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    feed_id     BIGINT       NOT NULL,
    line_number INT          NOT NULL,
    raw_line    TEXT         NOT NULL,
    reason      VARCHAR(512) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_feed_row_error_feed (feed_id, line_number),
    CONSTRAINT fk_feed_row_error_feed FOREIGN KEY (feed_id) REFERENCES feed (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE reconciliation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    ledger_feed_id   BIGINT       NOT NULL,
    external_feed_id BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    started_at       DATETIME(6)  NOT NULL,
    finished_at      DATETIME(6)  NULL,
    failure_reason   VARCHAR(512) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reconciliation_ledger_feed FOREIGN KEY (ledger_feed_id) REFERENCES feed (id),
    CONSTRAINT fk_reconciliation_external_feed FOREIGN KEY (external_feed_id) REFERENCES feed (id),
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE reconciliation_break (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    reconciliation_id BIGINT          NOT NULL,
    break_type        VARCHAR(32)     NOT NULL,
    txn_id            VARCHAR(64)     NOT NULL,
    feed_type         VARCHAR(16)     NULL,
    ledger_amount     DECIMAL(38, 18) NULL,
    external_amount   DECIMAL(38, 18) NULL,
    ledger_status     VARCHAR(16)     NULL,
    external_status   VARCHAR(16)     NULL,
    occurrences       INT             NULL,
    PRIMARY KEY (id),
    INDEX idx_break_page (reconciliation_id, id),
    INDEX idx_break_type_page (reconciliation_id, break_type, id),
    CONSTRAINT fk_break_reconciliation FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id),
    CONSTRAINT ck_break_type CHECK (break_type IN (
        'MISSING_EXTERNAL', 'MISSING_INTERNAL', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE'
    )),
    CONSTRAINT ck_break_feed_type CHECK (feed_type IS NULL OR feed_type IN ('LEDGER', 'EXTERNAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
