-- 会议预订阶段 4：按会议室和日期串行化占用检查，记录占用与释放时间。
CREATE TABLE bf_meeting_room_day_lock (
    room_id BIGINT NOT NULL,
    booking_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, booking_date),
    CONSTRAINT fk_meeting_day_lock_room FOREIGN KEY (room_id) REFERENCES bf_meeting_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室每日预订并发锁';

ALTER TABLE bf_meeting_reservation
    ADD COLUMN occupied_at DATETIME NULL AFTER submitted_at,
    ADD COLUMN released_at DATETIME NULL AFTER occupied_at,
    ADD INDEX idx_meeting_reservation_occupancy
        (room_id, booking_date, status, start_time, end_time);

UPDATE bf_meeting_reservation
SET occupied_at=COALESCE(submitted_at, updated_at)
WHERE status IN ('PENDING_PAYMENT','PENDING_APPROVAL','PAID','APPROVED','CONFIRMED','VERIFIED','COMPLETED')
  AND occupied_at IS NULL;

UPDATE bf_meeting_reservation
SET released_at=COALESCE(cancelled_at, updated_at)
WHERE status IN ('CANCELLED','REJECTED') AND released_at IS NULL;
