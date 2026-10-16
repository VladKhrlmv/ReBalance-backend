-- Baseline schema for the first public test (replaces the old V1–V13 history; no data is migrated).
-- Money is DECIMAL(19,2); every expense share is stored in exact cents, so shares always sum to the expense amount.
-- Balances are derived from expenses on read; nothing stores a running balance.

CREATE TABLE users
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(254) NOT NULL,
    nickname   VARCHAR(64)  NOT NULL,
    password   VARCHAR(100) NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_token
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_token_hash UNIQUE (token_hash),
    KEY idx_auth_token_expires (expires_at),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_group
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    personal       BIT(1)       NOT NULL DEFAULT b'0',
    creator_id     BIGINT       NOT NULL,
    owner_id       BIGINT       NOT NULL,
    invite_token   VARCHAR(32)  NULL,
    invite_enabled BIT(1)       NOT NULL DEFAULT b'0',
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    deleted_at     DATETIME(6)  NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    -- at most one personal group per user
    personal_owner BIGINT GENERATED ALWAYS AS (IF(personal = b'1', creator_id, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_group_invite_token UNIQUE (invite_token),
    CONSTRAINT uk_group_personal_owner UNIQUE (personal_owner),
    CONSTRAINT fk_group_creator FOREIGN KEY (creator_id) REFERENCES users (id),
    CONSTRAINT fk_group_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE group_member
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    group_id  BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    status    VARCHAR(16) NOT NULL,
    favorite  BIT(1)      NOT NULL DEFAULT b'0',
    joined_at DATETIME(6) NOT NULL,
    left_at   DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_group_user UNIQUE (group_id, user_id),
    KEY idx_member_user_status (user_id, status),
    CONSTRAINT fk_member_group FOREIGN KEY (group_id) REFERENCES app_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE category
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    group_id     BIGINT      NOT NULL,
    name         VARCHAR(64) NOT NULL,
    last_used_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_group_name UNIQUE (group_id, name),
    CONSTRAINT fk_category_group FOREIGN KEY (group_id) REFERENCES app_group (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE expense
(
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    group_id        BIGINT         NOT NULL,
    type            VARCHAR(16)    NOT NULL,
    split_type      VARCHAR(16)    NOT NULL,
    description     VARCHAR(255)   NOT NULL,
    amount          DECIMAL(19, 2) NOT NULL,
    expense_date    DATE           NOT NULL,
    category_id     BIGINT         NULL,
    payer_member_id BIGINT         NOT NULL,
    created_by      BIGINT         NOT NULL,
    updated_by      BIGINT         NOT NULL,
    deleted_by      BIGINT         NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    deleted_at      DATETIME(6)    NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_expense_group_list (group_id, deleted_at, expense_date, id),
    CONSTRAINT chk_expense_amount CHECK (amount > 0),
    CONSTRAINT fk_expense_group FOREIGN KEY (group_id) REFERENCES app_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_expense_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE SET NULL,
    CONSTRAINT fk_expense_payer FOREIGN KEY (payer_member_id) REFERENCES group_member (id),
    CONSTRAINT fk_expense_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_expense_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT fk_expense_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE expense_share
(
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    expense_id   BIGINT         NOT NULL,
    member_id    BIGINT         NOT NULL,
    weight       INT            NULL,
    share_amount DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_expense_member UNIQUE (expense_id, member_id),
    KEY idx_share_member (member_id),
    CONSTRAINT chk_share_amount CHECK (share_amount >= 0),
    CONSTRAINT chk_share_weight CHECK (weight IS NULL OR weight > 0),
    CONSTRAINT fk_share_expense FOREIGN KEY (expense_id) REFERENCES expense (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_member FOREIGN KEY (member_id) REFERENCES group_member (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE expense_image
(
    expense_id   BIGINT       NOT NULL,
    storage_key  VARCHAR(100) NOT NULL,
    content_type VARCHAR(50)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    width        INT          NOT NULL,
    height       INT          NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (expense_id),
    CONSTRAINT fk_image_expense FOREIGN KEY (expense_id) REFERENCES expense (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notification
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    type          VARCHAR(40)  NOT NULL,
    actor_user_id BIGINT       NOT NULL,
    group_id      BIGINT       NULL,
    expense_id    BIGINT       NULL,
    message       VARCHAR(500) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_group FOREIGN KEY (group_id) REFERENCES app_group (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notification_recipient
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    notification_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    seen_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recipient_notification_user UNIQUE (notification_id, user_id),
    KEY idx_recipient_user_seen (user_id, seen_at),
    CONSTRAINT fk_recipient_notification FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE CASCADE,
    CONSTRAINT fk_recipient_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
