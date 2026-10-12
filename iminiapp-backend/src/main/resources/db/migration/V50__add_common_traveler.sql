CREATE TABLE bf_common_traveler (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    traveler_name VARCHAR(64) NOT NULL,
    id_card_cipher VARBINARY(512) NOT NULL,
    id_card_hash CHAR(64) NOT NULL,
    mobile_cipher VARBINARY(512) NOT NULL,
    mobile_hash CHAR(64) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_common_traveler_user_deleted (user_id, deleted, id),
    INDEX idx_common_traveler_identity (user_id, id_card_hash, deleted),
    CONSTRAINT fk_common_traveler_user FOREIGN KEY (user_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户常用旅客（证件和手机加密存储）';
