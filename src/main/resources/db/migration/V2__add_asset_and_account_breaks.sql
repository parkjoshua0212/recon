-- V1 is already applied, so the break type list is widened here rather than edited there.
ALTER TABLE reconciliation_break
    DROP CHECK ck_break_type,
    ADD CONSTRAINT ck_break_type CHECK (break_type IN (
        'MISSING_EXTERNAL', 'MISSING_INTERNAL', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE',
        'ASSET_MISMATCH', 'ACCOUNT_MISMATCH'
    )),
    ADD COLUMN ledger_asset        VARCHAR(16) NULL AFTER external_status,
    ADD COLUMN external_asset      VARCHAR(16) NULL AFTER ledger_asset,
    ADD COLUMN ledger_account_id   VARCHAR(64) NULL AFTER external_asset,
    ADD COLUMN external_account_id VARCHAR(64) NULL AFTER ledger_account_id;
