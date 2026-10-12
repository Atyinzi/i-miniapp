package com.iminiapp.backend.order.application;

import com.iminiapp.backend.cultural.application.CulturalPromotionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExpiredOrderService {
    private final JdbcTemplate jdbcTemplate;
    private final CulturalPromotionService culturalPromotionService;

    public ExpiredOrderService(JdbcTemplate jdbcTemplate, CulturalPromotionService culturalPromotionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.culturalPromotionService = culturalPromotionService;
    }

    @Scheduled(fixedDelayString = "${app.order.expiration-scan-ms:60000}", initialDelay = 30000)
    public void scheduledExpire() {
        expireOverdue();
    }

    @Transactional
    public int expireOverdue() {
        List<Long> orderIds = jdbcTemplate.queryForList("""
                SELECT id FROM bf_order
                WHERE status='PENDING_PAYMENT' AND expires_at IS NOT NULL AND expires_at < ?
                ORDER BY expires_at LIMIT 50
                """, Long.class, LocalDateTime.now());
        int expired = 0;
        for (Long orderId : orderIds) {
            if (expireOne(orderId)) expired++;
        }
        return expired;
    }

    private boolean expireOne(long orderId) {
        List<ExpiredOrder> orders = jdbcTemplate.query("""
                SELECT id, order_no, status, expires_at FROM bf_order WHERE id=? FOR UPDATE
                """, (rs, n) -> new ExpiredOrder(rs.getLong("id"), rs.getString("order_no"),
                rs.getString("status"), rs.getObject("expires_at", LocalDateTime.class)), orderId);
        if (orders.isEmpty()) return false;
        ExpiredOrder order = orders.get(0);
        if (!"PENDING_PAYMENT".equals(order.status()) || order.expiresAt() == null
                || !order.expiresAt().isBefore(LocalDateTime.now())) return false;

        List<ExpiredItem> items = jdbcTemplate.query("""
                SELECT oi.id,oi.sku_id,oi.resource_type,oi.quantity,inv.id inventory_id
                FROM bf_order_item oi LEFT JOIN bf_inventory inv
                  ON inv.sku_id=oi.sku_id AND inv.business_date=oi.service_date
                 AND inv.time_slot=COALESCE(oi.time_slot,'')
                WHERE oi.order_id=? ORDER BY oi.id FOR UPDATE
                """, (rs, n) -> new ExpiredItem(rs.getLong("id"), rs.getLong("sku_id"),
                rs.getString("resource_type"), rs.getInt("quantity"),
                rs.getObject("inventory_id", Long.class)), orderId);
        for (ExpiredItem item : items) {
            if ("MEETING_BOOKING".equals(item.resourceType()) || "DINING_ORDER".equals(item.resourceType())) {
                continue;
            }
            if ("CULTURAL_PRODUCT".equals(item.resourceType())) {
                String key = "EXPIRE-RELEASE:" + order.orderNo() + ":" + item.orderItemId();
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM bf_cultural_stock_log WHERE idempotency_key=?", Integer.class, key);
                if (exists != null && exists > 0) continue;
                jdbcTemplate.queryForObject("SELECT sku_id FROM bf_cultural_stock WHERE sku_id=? FOR UPDATE",
                        Long.class, item.skuId());
                int released = jdbcTemplate.update("""
                        UPDATE bf_cultural_stock
                        SET available_quantity=available_quantity+?,locked_quantity=locked_quantity-?,version=version+1
                        WHERE sku_id=? AND locked_quantity>=?
                        """, item.quantity(), item.quantity(), item.skuId(), item.quantity());
                if (released != 1) throw new IllegalStateException("文创订单锁定库存状态不一致：" + order.orderNo());
                jdbcTemplate.update("""
                        INSERT INTO bf_cultural_stock_log
                        (sku_id,change_type,quantity_delta,on_hand_after,available_after,idempotency_key,reference_no,remark)
                        SELECT ?, 'EXPIRE_RELEASE', 0, on_hand_quantity, available_quantity, ?, ?, '支付超时自动释放锁定库存'
                        FROM bf_cultural_stock WHERE sku_id=?
                        """, item.skuId(), key, order.orderNo(), item.skuId());
                continue;
            }
            if (item.inventoryId() == null) throw new IllegalStateException("订单日期库存不存在：" + order.orderNo());
            String key = "EXPIRE-RELEASE:" + order.orderNo() + ":" + item.orderItemId();
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bf_inventory_log WHERE idempotency_key=?", Integer.class, key);
            if (exists != null && exists > 0) continue;
            jdbcTemplate.update("""
                    UPDATE bf_inventory SET available_quantity=available_quantity+?, version=version+1 WHERE id=?
                    """, item.quantity(), item.inventoryId());
            Integer after = jdbcTemplate.queryForObject(
                    "SELECT available_quantity FROM bf_inventory WHERE id=?", Integer.class, item.inventoryId());
            jdbcTemplate.update("""
                    INSERT INTO bf_inventory_log
                    (inventory_id, order_no, change_type, quantity_delta, quantity_after, idempotency_key, remark)
                    VALUES (?, ?, 'EXPIRE_RELEASE', ?, ?, ?, '支付超时自动释放库存')
                    """, item.inventoryId(), order.orderNo(), item.quantity(), after, key);
        }
        releaseDiningStock(orderId, order.orderNo());
        int changed = jdbcTemplate.update("""
                UPDATE bf_order SET status='CANCELLED', cancelled_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING_PAYMENT'
                """, orderId);
        if (changed != 1) return false;
        jdbcTemplate.update("""
                UPDATE bf_meeting_reservation SET status='CANCELLED',cancelled_at=COALESCE(cancelled_at,CURRENT_TIMESTAMP),
                released_at=CASE WHEN occupied_at IS NULL THEN released_at ELSE CURRENT_TIMESTAMP END
                WHERE order_id=? AND status='PENDING_PAYMENT'
                """, orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_meeting_reservation_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'PENDING_PAYMENT','CANCELLED',NULL,'支付超时自动取消',CONCAT('MEETING-EXPIRE:',id)
                FROM bf_meeting_reservation WHERE order_id=? AND status='CANCELLED'
                """, orderId);
        jdbcTemplate.update("UPDATE bf_sport_booking SET status='CANCELLED',cancelled_at=COALESCE(cancelled_at,CURRENT_TIMESTAMP) WHERE order_id=?", orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_sport_booking_status_log
                (booking_id,from_status,to_status,operator_id,note,idempotency_key)
                SELECT id,'HELD','CANCELLED',NULL,'支付超时自动取消',CONCAT('SPORT-EXPIRE:',id)
                FROM bf_sport_booking WHERE order_id=?
                """, orderId);
        jdbcTemplate.update("UPDATE bf_dining_reservation SET status='CANCELLED',cancelled_at=COALESCE(cancelled_at,CURRENT_TIMESTAMP) WHERE order_id=? AND status='PENDING_PAYMENT'",orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_dining_status_log(reservation_id,from_status,to_status,note,idempotency_key)
                SELECT id,'PENDING_PAYMENT','CANCELLED','支付超时自动取消',CONCAT('DINING-EXPIRE:',id)
                FROM bf_dining_reservation WHERE order_id=? AND status='CANCELLED'
                """,orderId);
        culturalPromotionService.releaseCouponForCancelledOrder(orderId);
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_order_status_log
                (order_id, from_status, to_status, operator_id, reason, idempotency_key)
                VALUES (?, 'PENDING_PAYMENT', 'CANCELLED', NULL, '支付超时自动取消', ?)
                """, orderId, "EXPIRE:" + order.orderNo());
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log (operator_id, action, target_type, target_id, detail)
                VALUES (NULL, 'ORDER_EXPIRED', 'ORDER', ?, JSON_OBJECT('orderNo', ?))
                """, String.valueOf(orderId), order.orderNo());
        return true;
    }

    private void releaseDiningStock(long orderId,String orderNo){
        List<Long> reservations=jdbcTemplate.queryForList("SELECT id FROM bf_dining_reservation WHERE order_id=? AND status='PENDING_PAYMENT'",Long.class,orderId);
        for(Long id:reservations){
            List<DiningStock> rows=jdbcTemplate.query("""
                    SELECT x.dish_id,SUM(x.qty) qty FROM (
                    SELECT i.dish_id,i.quantity qty FROM bf_dining_reservation_item i WHERE i.reservation_id=? AND i.item_type='DISH'
                    UNION ALL SELECT pi.dish_id,pi.quantity*i.quantity FROM bf_dining_reservation_item i JOIN bf_dining_package_item pi ON pi.package_id=i.package_id WHERE i.reservation_id=? AND i.item_type='PACKAGE')x GROUP BY x.dish_id
                    """,(rs,n)->new DiningStock(rs.getLong(1),rs.getInt(2)),id,id);
            for(DiningStock row:rows){String key="DINING-EXPIRE-RELEASE:"+id+":"+row.dishId();Integer exists=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_dining_stock_log WHERE idempotency_key=?",Integer.class,key);if(exists!=null&&exists>0)continue;jdbcTemplate.update("UPDATE bf_dining_stock SET locked_quantity=locked_quantity-?,version=version+1 WHERE dish_id=? AND locked_quantity>=?",row.quantity(),row.dishId(),row.quantity());jdbcTemplate.update("INSERT INTO bf_dining_stock_log(dish_id,change_type,quantity_delta,on_hand_after,locked_after,idempotency_key,reference_no,remark) SELECT ?,'EXPIRE_RELEASE',0,on_hand_quantity,locked_quantity,?,?, '支付超时释放菜品库存' FROM bf_dining_stock WHERE dish_id=?",row.dishId(),key,orderNo,row.dishId());}
        }
    }

    private record ExpiredOrder(long id, String orderNo, String status, LocalDateTime expiresAt) {}
    private record ExpiredItem(long orderItemId, long skuId, String resourceType, int quantity, Long inventoryId) {}
    private record DiningStock(long dishId,int quantity) {}
}
