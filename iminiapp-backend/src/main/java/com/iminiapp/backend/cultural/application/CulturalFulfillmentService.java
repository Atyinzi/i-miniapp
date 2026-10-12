package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.DeliverySnapshot;
import com.iminiapp.backend.cultural.api.CulturalFulfillmentModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.AdminOrderQueryService;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CulturalFulfillmentService {
    private static final Set<String> TRACKING_EVENTS = Set.of(
            "SHIPPED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION");
    private final JdbcTemplate jdbcTemplate;
    private final AdminOrderQueryService adminOrderQueryService;

    public CulturalFulfillmentService(JdbcTemplate jdbcTemplate, AdminOrderQueryService adminOrderQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminOrderQueryService = adminOrderQueryService;
    }

    @Transactional(readOnly = true)
    public List<CarrierResponse> carriers(boolean enabledOnly) {
        String filter = enabledOnly ? " WHERE enabled=1" : "";
        return jdbcTemplate.query("""
                SELECT id,carrier_code,carrier_name,tracking_url_template,enabled,sort_order
                FROM bf_logistics_carrier
                """ + filter + " ORDER BY sort_order,id", (rs, n) -> new CarrierResponse(
                rs.getLong("id"), rs.getString("carrier_code"), rs.getString("carrier_name"),
                rs.getString("tracking_url_template"), rs.getBoolean("enabled"), rs.getInt("sort_order")));
    }

    @Transactional
    public CarrierResponse createCarrier(AuthenticatedPrincipal principal, CarrierUpsertRequest request) {
        CarrierValues value = validateCarrier(request);
        if (!jdbcTemplate.queryForList("SELECT id FROM bf_logistics_carrier WHERE carrier_code=?", Long.class, value.code()).isEmpty()) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "物流公司编码已存在");
        }
        long operator = ensureOperator(principal);
        jdbcTemplate.update("""
                INSERT INTO bf_logistics_carrier(carrier_code,carrier_name,tracking_url_template,enabled,sort_order)
                VALUES (?,?,?,?,?)
                """, value.code(), value.name(), value.url(), value.enabled(), value.sortOrder());
        long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit(operator, "LOGISTICS_CARRIER_CREATE", "LOGISTICS_CARRIER", id, value.code());
        return carrier(id);
    }

    @Transactional
    public CarrierResponse updateCarrier(AuthenticatedPrincipal principal, long id, CarrierUpsertRequest request) {
        carrier(id);
        CarrierValues value = validateCarrier(request);
        if (!jdbcTemplate.queryForList("SELECT id FROM bf_logistics_carrier WHERE carrier_code=? AND id<>?", Long.class, value.code(), id).isEmpty()) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "物流公司编码已存在");
        }
        long operator = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_logistics_carrier SET carrier_code=?,carrier_name=?,tracking_url_template=?,enabled=?,sort_order=?
                WHERE id=?
                """, value.code(), value.name(), value.url(), value.enabled(), value.sortOrder(), id);
        audit(operator, "LOGISTICS_CARRIER_UPDATE", "LOGISTICS_CARRIER", id, value.code());
        return carrier(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<CulturalAdminOrderItem> adminOrders(String status, String keyword, int page, int pageSize) {
        validatePage(page, pageSize);
        StringBuilder where = new StringBuilder(" WHERE o.order_type='CULTURAL_PRODUCT'");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) { where.append(" AND o.status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (o.order_no LIKE ? OR u.nickname LIKE ? OR d.receiver_name LIKE ? OR d.receiver_mobile LIKE ? OR s.tracking_no LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) args.add(pattern);
        }
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_order o JOIN bf_user u ON u.id=o.user_id
                JOIN bf_cultural_order_delivery d ON d.order_id=o.id LEFT JOIN bf_shipment s ON s.order_id=o.id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((page - 1) * pageSize);
        List<CulturalAdminOrderItem> items = jdbcTemplate.query("""
                SELECT o.id,o.order_no,o.user_id,u.nickname,o.status,o.total_amount_cent,o.discount_amount_cent,
                       o.payable_amount_cent,o.created_at,d.receiver_name,d.receiver_mobile,
                       CONCAT(d.province,d.city,d.district,d.detail_address) full_address,
                       s.status shipment_status,s.carrier_name,s.tracking_no
                FROM bf_order o JOIN bf_user u ON u.id=o.user_id
                JOIN bf_cultural_order_delivery d ON d.order_id=o.id LEFT JOIN bf_shipment s ON s.order_id=o.id
                """ + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", (rs, n) -> new CulturalAdminOrderItem(
                rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"), rs.getString("nickname"),
                rs.getString("status"), rs.getLong("total_amount_cent"), rs.getLong("discount_amount_cent"),
                rs.getLong("payable_amount_cent"), rs.getObject("created_at", LocalDateTime.class),
                rs.getString("receiver_name"), rs.getString("receiver_mobile"), rs.getString("full_address"),
                rs.getString("shipment_status"), rs.getString("carrier_name"), rs.getString("tracking_no")),
                pageArgs.toArray());
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
    }

    @Transactional(readOnly = true)
    public CulturalAdminOrderDetail adminOrderDetail(long orderId) {
        OrderResponse order = adminOrderQueryService.detail(orderId);
        if (!"CULTURAL_PRODUCT".equals(order.orderType())) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单不存在");
        return new CulturalAdminOrderDetail(order, delivery(orderId, null), findShipment(orderId, null, false));
    }

    @Transactional
    public ShipmentResponse prepare(AuthenticatedPrincipal principal, long orderId, String key, PrepareRequest request) {
        requireKey(key);
        ShipmentResponse duplicate = duplicateOperation(orderId, key);
        if (duplicate != null) return duplicate;
        long operator = ensureOperator(principal);
        OrderLocked order = lockOrder(orderId);
        if (!"PAID".equals(order.status())) throw new BusinessException(CommonErrorCode.ORDER_CONFLICT, "只有已支付订单可以开始备货");
        long shipmentId = createPendingShipment(orderId);
        jdbcTemplate.update("UPDATE bf_order SET status='PREPARING' WHERE id=? AND status='PAID'", orderId);
        logOperation(shipmentId, orderId, "PREPARE", "PAID", "PREPARING", operator, key, request == null ? null : request.remark());
        audit(operator, "CULTURAL_ORDER_PREPARE", "ORDER", orderId, order.orderNo());
        return findShipment(orderId, null, true);
    }

    @Transactional
    public ShipmentResponse ship(AuthenticatedPrincipal principal, long orderId, String key, ShipRequest request) {
        requireKey(key);
        long operator = ensureOperator(principal);
        return shipInternal(orderId, key, request, operator);
    }

    @Transactional
    public List<ShipmentResponse> batchShip(AuthenticatedPrincipal principal, String key, BatchShipRequest request) {
        requireKey(key);
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "批量发货每次需要1—100条");
        }
        long operator = ensureOperator(principal);
        List<ShipmentResponse> result = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            BatchShipItem item = request.items().get(i);
            if (item == null || item.orderId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "批量发货订单不能为空");
            result.add(shipInternal(item.orderId(), key + ":" + i, new ShipRequest(item.carrierCode(),
                    item.trackingNo(), item.autoReceiveDays(), item.remark()), operator));
        }
        return result;
    }

    private ShipmentResponse shipInternal(long orderId, String key, ShipRequest request, long operator) {
        ShipmentResponse duplicate = duplicateOperation(orderId, key);
        if (duplicate != null) return duplicate;
        ShipValues value = validateShip(request);
        CarrierResponse carrier = activeCarrier(value.carrierCode());
        OrderLocked order = lockOrder(orderId);
        if (!Set.of("PAID", "PREPARING").contains(order.status())) {
            throw new BusinessException(CommonErrorCode.ORDER_CONFLICT, "只有已支付或备货中订单可以发货");
        }
        ensureTrackingUnique(value.carrierCode(), value.trackingNo(), null);
        long shipmentId = existingShipmentId(orderId);
        if (shipmentId == 0) shipmentId = createPendingShipment(orderId);
        ShipmentLocked shipment = lockShipment(shipmentId);
        LocalDateTime shippedAt = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE bf_shipment SET carrier_code=?,carrier_name=?,tracking_no=?,status='SHIPPED',
                       prepared_at=COALESCE(prepared_at,CURRENT_TIMESTAMP),shipped_at=?,shipped_by=?,
                       auto_receive_at=?,exception_flag=0,exception_reason=NULL WHERE id=?
                """, carrier.carrierCode(), carrier.carrierName(), value.trackingNo(), shippedAt, operator,
                shippedAt.plusDays(value.autoReceiveDays()), shipmentId);
        jdbcTemplate.update("UPDATE bf_order SET status='SHIPPED' WHERE id=?", orderId);
        insertTracking(shipmentId, "SHIPPED", "商家已发货", null, shippedAt, "ADMIN", key + ":TRACK");
        logOperation(shipmentId, orderId, "SHIP", order.status(), "SHIPPED", operator, key, value.remark());
        notifyUser(order.userId(), orderId, "订单已发货", "订单" + order.orderNo() + "已由" + carrier.carrierName() + "承运");
        audit(operator, "CULTURAL_ORDER_SHIP", "ORDER", orderId, value.trackingNo());
        return findShipment(orderId, null, true);
    }

    @Transactional
    public ShipmentResponse updateShipment(AuthenticatedPrincipal principal, long orderId, String key,
                                           ShipRequest request) {
        requireKey(key);
        ShipmentResponse duplicate = duplicateOperation(orderId, key);
        if (duplicate != null) return duplicate;
        ShipValues value = validateShip(request);
        CarrierResponse carrier = activeCarrier(value.carrierCode());
        OrderLocked order = lockOrder(orderId);
        long shipmentId = existingShipmentId(orderId);
        if (shipmentId == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单尚未发货");
        ShipmentLocked shipment = lockShipment(shipmentId);
        if (!Set.of("SHIPPED", "IN_TRANSIT", "EXCEPTION").contains(shipment.status())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "当前物流状态不允许修改运单");
        }
        ensureTrackingUnique(value.carrierCode(), value.trackingNo(), shipmentId);
        long operator = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_shipment SET carrier_code=?,carrier_name=?,tracking_no=?,auto_receive_at=? WHERE id=?
                """, carrier.carrierCode(), carrier.carrierName(), value.trackingNo(),
                shipment.shippedAt().plusDays(value.autoReceiveDays()), shipmentId);
        logOperation(shipmentId, orderId, "UPDATE_WAYBILL", shipment.status(), shipment.status(), operator, key, value.remark());
        audit(operator, "CULTURAL_SHIPMENT_UPDATE", "SHIPMENT", shipmentId, value.trackingNo());
        return findShipment(orderId, null, true);
    }

    @Transactional
    public ShipmentResponse appendTracking(AuthenticatedPrincipal principal, long orderId, String key,
                                           TrackingEventRequest request) {
        requireKey(key);
        ShipmentResponse duplicate = duplicateOperation(orderId, key);
        if (duplicate != null) return duplicate;
        TrackingValues value = validateTracking(request);
        OrderLocked order = lockOrder(orderId);
        long shipmentId = existingShipmentId(orderId);
        if (shipmentId == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单尚未发货");
        ShipmentLocked shipment = lockShipment(shipmentId);
        if ("DELIVERED".equals(shipment.status())) return findShipment(orderId, null, true);
        long operator = ensureOperator(principal);
        insertTracking(shipmentId, value.eventCode(), value.description(), value.location(), value.occurredAt(),
                value.source(), value.externalEventId());
        String toStatus = shipment.status();
        if ("DELIVERED".equals(value.eventCode())) {
            toStatus = "DELIVERED";
            jdbcTemplate.update("""
                    UPDATE bf_shipment SET status='DELIVERED',received_at=?,last_tracking_at=?,exception_flag=0,
                           exception_reason=NULL WHERE id=?
                    """, value.occurredAt(), value.occurredAt(), shipmentId);
            jdbcTemplate.update("UPDATE bf_order SET status='COMPLETED',completed_at=? WHERE id=? AND status='SHIPPED'",
                    value.occurredAt(), orderId);
            notifyUser(order.userId(), orderId, "订单已签收", "订单" + order.orderNo() + "已完成签收");
        } else if ("EXCEPTION".equals(value.eventCode())) {
            toStatus = "EXCEPTION";
            jdbcTemplate.update("""
                    UPDATE bf_shipment SET status='EXCEPTION',last_tracking_at=?,exception_flag=1,exception_reason=? WHERE id=?
                    """, value.occurredAt(), value.description(), shipmentId);
        } else {
            toStatus = "IN_TRANSIT";
            jdbcTemplate.update("""
                    UPDATE bf_shipment SET status='IN_TRANSIT',last_tracking_at=?,exception_flag=0,exception_reason=NULL WHERE id=?
                    """, value.occurredAt(), shipmentId);
        }
        logOperation(shipmentId, orderId, "TRACKING", shipment.status(), toStatus, operator, key, value.description());
        return findShipment(orderId, null, true);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse userShipment(AuthenticatedPrincipal principal, long orderId) {
        return findShipment(orderId, requireUser(principal), true);
    }

    @Transactional
    public ShipmentResponse confirmReceipt(AuthenticatedPrincipal principal, long orderId, String key) {
        requireKey(key);
        long userId = requireUser(principal);
        ShipmentResponse duplicate = duplicateOperation(orderId, key);
        if (duplicate != null) return findShipment(orderId, userId, true);
        OrderLocked order = lockOwnedOrder(orderId, userId);
        long shipmentId = existingShipmentId(orderId);
        if (shipmentId == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单尚未发货");
        ShipmentLocked shipment = lockShipment(shipmentId);
        if (!Set.of("SHIPPED", "IN_TRANSIT", "EXCEPTION").contains(shipment.status())) {
            throw new BusinessException(CommonErrorCode.ORDER_CONFLICT, "当前状态不能确认收货");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE bf_shipment SET status='DELIVERED',received_at=?,last_tracking_at=?,exception_flag=0,
                       exception_reason=NULL WHERE id=?
                """, now, now, shipmentId);
        jdbcTemplate.update("UPDATE bf_order SET status='COMPLETED',completed_at=? WHERE id=?", now, orderId);
        insertTracking(shipmentId, "DELIVERED", "用户确认收货", null, now, "USER", key + ":TRACK");
        logOperation(shipmentId, orderId, "CONFIRM_RECEIPT", shipment.status(), "DELIVERED", userId, key, "用户确认收货");
        return findShipment(orderId, userId, true);
    }

    @Scheduled(fixedDelayString = "${app.cultural.auto-receive-scan-ms:3600000}", initialDelay = 60000)
    public void scheduledAutoReceive() { autoReceiveOverdue(); }

    @Transactional
    public int autoReceiveOverdue() {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT order_id FROM bf_shipment WHERE status IN ('SHIPPED','IN_TRANSIT')
                  AND exception_flag=0 AND auto_receive_at IS NOT NULL AND auto_receive_at<CURRENT_TIMESTAMP
                ORDER BY auto_receive_at LIMIT 100
                """, Long.class);
        int count = 0;
        for (Long orderId : ids) {
            OrderLocked order = lockOrder(orderId);
            long shipmentId = existingShipmentId(orderId);
            ShipmentLocked shipment = lockShipment(shipmentId);
            if (!Set.of("SHIPPED", "IN_TRANSIT").contains(shipment.status())) continue;
            String key = "AUTO-RECEIVE:" + order.orderNo();
            if (operationExists(key)) continue;
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update("UPDATE bf_shipment SET status='DELIVERED',received_at=?,last_tracking_at=? WHERE id=?",
                    now, now, shipmentId);
            jdbcTemplate.update("UPDATE bf_order SET status='COMPLETED',completed_at=? WHERE id=? AND status='SHIPPED'",
                    now, orderId);
            insertTracking(shipmentId, "DELIVERED", "超过确认期限，系统自动确认收货", null, now, "SYSTEM", key + ":TRACK");
            logOperation(shipmentId, orderId, "AUTO_RECEIVE", shipment.status(), "DELIVERED", null, key, "系统自动收货");
            count++;
        }
        return count;
    }

    private long createPendingShipment(long orderId) {
        jdbcTemplate.update("""
                INSERT INTO bf_shipment(order_id,receiver_snapshot,status,prepared_at)
                SELECT d.order_id,JSON_OBJECT('receiverName',d.receiver_name,'receiverMobile',d.receiver_mobile,
                       'province',d.province,'city',d.city,'district',d.district,
                       'detailAddress',d.detail_address,'postalCode',d.postal_code),
                       'PENDING',CURRENT_TIMESTAMP
                FROM bf_cultural_order_delivery d WHERE d.order_id=?
                ON DUPLICATE KEY UPDATE prepared_at=COALESCE(prepared_at,CURRENT_TIMESTAMP)
                """, orderId);
        long id = existingShipmentId(orderId);
        if (id == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单收货地址不存在");
        return id;
    }

    private ShipmentResponse findShipment(long orderId, Long userId, boolean includeTracking) {
        String owner = userId == null ? "" : " AND o.user_id=?";
        Object[] args = userId == null ? new Object[]{orderId} : new Object[]{orderId, userId};
        List<ShipmentHead> rows = jdbcTemplate.query("""
                SELECT s.id,s.order_id,o.order_no,s.status,s.carrier_code,s.carrier_name,s.tracking_no,
                       c.tracking_url_template,s.prepared_at,s.shipped_at,s.auto_receive_at,s.received_at,
                       s.exception_flag,s.exception_reason
                FROM bf_shipment s JOIN bf_order o ON o.id=s.order_id
                LEFT JOIN bf_logistics_carrier c ON c.carrier_code=s.carrier_code
                WHERE s.order_id=?
                """ + owner, (rs, n) -> new ShipmentHead(rs.getLong("id"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("status"), rs.getString("carrier_code"),
                rs.getString("carrier_name"), rs.getString("tracking_no"), rs.getString("tracking_url_template"),
                rs.getObject("prepared_at", LocalDateTime.class), rs.getObject("shipped_at", LocalDateTime.class),
                rs.getObject("auto_receive_at", LocalDateTime.class), rs.getObject("received_at", LocalDateTime.class),
                rs.getBoolean("exception_flag"), rs.getString("exception_reason")), args);
        if (rows.isEmpty()) return null;
        ShipmentHead h = rows.get(0);
        List<TrackingEventResponse> events = includeTracking ? jdbcTemplate.query("""
                SELECT id,event_code,event_description,location,occurred_at,source FROM bf_shipment_tracking
                WHERE shipment_id=? ORDER BY occurred_at DESC,id DESC
                """, (rs, n) -> new TrackingEventResponse(rs.getLong("id"), rs.getString("event_code"),
                rs.getString("event_description"), rs.getString("location"),
                rs.getObject("occurred_at", LocalDateTime.class), rs.getString("source")), h.id()) : List.of();
        String url = h.urlTemplate() == null || h.trackingNo() == null ? null
                : h.urlTemplate().replace("{trackingNo}", h.trackingNo());
        return new ShipmentResponse(h.id(), h.orderId(), h.orderNo(), h.status(), h.carrierCode(), h.carrierName(),
                h.trackingNo(), url, h.preparedAt(), h.shippedAt(), h.autoReceiveAt(), h.receivedAt(),
                h.exceptionFlag(), h.exceptionReason(), events);
    }

    private DeliverySnapshot delivery(long orderId, Long userId) {
        String owner = userId == null ? "" : " AND o.user_id=?";
        Object[] args = userId == null ? new Object[]{orderId} : new Object[]{orderId, userId};
        List<DeliverySnapshot> rows = jdbcTemplate.query("""
                SELECT d.source_address_id,d.receiver_name,d.receiver_mobile,d.province,d.city,d.district,
                       d.detail_address,d.postal_code FROM bf_cultural_order_delivery d
                JOIN bf_order o ON o.id=d.order_id WHERE d.order_id=?
                """ + owner, (rs, n) -> new DeliverySnapshot(rs.getLong("source_address_id"),
                rs.getString("receiver_name"), rs.getString("receiver_mobile"), rs.getString("province"),
                rs.getString("city"), rs.getString("district"), rs.getString("detail_address"),
                rs.getString("postal_code")), args);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单收货信息不存在");
        return rows.get(0);
    }

    private void insertTracking(long shipmentId, String code, String description, String location,
                                LocalDateTime occurredAt, String source, String externalId) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_shipment_tracking
                (shipment_id,event_code,event_description,location,occurred_at,source,external_event_id)
                VALUES (?,?,?,?,?,?,?)
                """, shipmentId, code, description, location, occurredAt, source, externalId);
    }
    private void logOperation(Long shipmentId, long orderId, String action, String from, String to,
                              Long operator, String key, String detail) {
        jdbcTemplate.update("""
                INSERT INTO bf_shipment_operation_log
                (shipment_id,order_id,action,from_status,to_status,operator_id,idempotency_key,detail)
                VALUES (?,?,?,?,?,?,?,JSON_OBJECT('remark',?))
                """, shipmentId, orderId, action, from, to, operator, key, detail);
    }
    private ShipmentResponse duplicateOperation(long orderId, String key) {
        if (!operationExists(key)) return null;
        List<Long> ids = jdbcTemplate.queryForList("SELECT order_id FROM bf_shipment_operation_log WHERE idempotency_key=?", Long.class, key);
        if (ids.isEmpty() || ids.get(0) != orderId) throw new BusinessException(CommonErrorCode.CONFLICT, "幂等键已用于其他订单");
        return findShipment(orderId, null, true);
    }
    private boolean operationExists(String key) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_shipment_operation_log WHERE idempotency_key=?", Integer.class, key);
        return count != null && count > 0;
    }
    private long existingShipmentId(long orderId) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_shipment WHERE order_id=?", Long.class, orderId);
        return ids.isEmpty() ? 0 : ids.get(0);
    }
    private ShipmentLocked lockShipment(long id) {
        List<ShipmentLocked> rows = jdbcTemplate.query("""
                SELECT id,status,shipped_at FROM bf_shipment WHERE id=? FOR UPDATE
                """, (rs, n) -> new ShipmentLocked(rs.getLong("id"), rs.getString("status"),
                rs.getObject("shipped_at", LocalDateTime.class)), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "发货单不存在");
        return rows.get(0);
    }
    private OrderLocked lockOrder(long id) {
        List<OrderLocked> rows = jdbcTemplate.query("""
                SELECT id,order_no,user_id,status,order_type FROM bf_order WHERE id=? FOR UPDATE
                """, (rs, n) -> new OrderLocked(rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
                rs.getString("status"), rs.getString("order_type")), id);
        if (rows.isEmpty() || !"CULTURAL_PRODUCT".equals(rows.get(0).orderType())) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单不存在");
        return rows.get(0);
    }
    private OrderLocked lockOwnedOrder(long id, long userId) {
        OrderLocked order = lockOrder(id);
        if (order.userId() != userId) throw new BusinessException(CommonErrorCode.NOT_FOUND, "订单不存在或无权访问");
        return order;
    }
    private CarrierResponse activeCarrier(String code) {
        List<CarrierResponse> rows = jdbcTemplate.query("""
                SELECT id,carrier_code,carrier_name,tracking_url_template,enabled,sort_order
                FROM bf_logistics_carrier WHERE carrier_code=? AND enabled=1
                """, (rs, n) -> new CarrierResponse(rs.getLong("id"), rs.getString("carrier_code"),
                rs.getString("carrier_name"), rs.getString("tracking_url_template"),
                rs.getBoolean("enabled"), rs.getInt("sort_order")), code);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "物流公司不存在或已停用");
        return rows.get(0);
    }
    private CarrierResponse carrier(long id) {
        List<CarrierResponse> rows = jdbcTemplate.query("""
                SELECT id,carrier_code,carrier_name,tracking_url_template,enabled,sort_order
                FROM bf_logistics_carrier WHERE id=?
                """, (rs, n) -> new CarrierResponse(rs.getLong("id"), rs.getString("carrier_code"),
                rs.getString("carrier_name"), rs.getString("tracking_url_template"),
                rs.getBoolean("enabled"), rs.getInt("sort_order")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "物流公司不存在");
        return rows.get(0);
    }
    private void ensureTrackingUnique(String carrier, String tracking, Long excludeShipment) {
        List<Long> ids = excludeShipment == null ? jdbcTemplate.queryForList("""
                SELECT id FROM bf_shipment WHERE carrier_code=? AND tracking_no=?
                """, Long.class, carrier, tracking) : jdbcTemplate.queryForList("""
                SELECT id FROM bf_shipment WHERE carrier_code=? AND tracking_no=? AND id<>?
                """, Long.class, carrier, tracking, excludeShipment);
        if (!ids.isEmpty()) throw new BusinessException(CommonErrorCode.CONFLICT, "物流单号已用于其他订单");
    }
    private void notifyUser(long userId, long orderId, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO bf_notification(user_id,notification_type,title,content,business_type,business_id)
                VALUES (?,'ORDER',?,?, 'CULTURAL_ORDER',?)
                """, userId, title, content, orderId);
    }
    private CarrierValues validateCarrier(CarrierUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.carrierCode()) || !StringUtils.hasText(request.carrierName())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "物流公司编码和名称不能为空");
        }
        String code = request.carrierCode().trim().toUpperCase(Locale.ROOT);
        if (code.length() > 32 || request.carrierName().trim().length() > 64) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "物流公司字段长度超限");
        String url = optional(request.trackingUrlTemplate(), 512);
        if (url != null && !url.contains("{trackingNo}")) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "查询地址必须包含{trackingNo}占位符");
        return new CarrierValues(code, request.carrierName().trim(), url,
                request.enabled() == null || request.enabled(), request.sortOrder() == null ? 0 : request.sortOrder());
    }
    private ShipValues validateShip(ShipRequest request) {
        if (request == null || !StringUtils.hasText(request.carrierCode()) || !StringUtils.hasText(request.trackingNo())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "物流公司和物流单号不能为空");
        }
        int days = request.autoReceiveDays() == null ? 10 : request.autoReceiveDays();
        if (days < 1 || days > 30) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "自动收货期限范围为1—30天");
        return new ShipValues(request.carrierCode().trim().toUpperCase(Locale.ROOT),
                required(request.trackingNo(), 64, "物流单号"), days, optional(request.remark(), 255));
    }
    private TrackingValues validateTracking(TrackingEventRequest request) {
        if (request == null || !StringUtils.hasText(request.eventCode()) || !StringUtils.hasText(request.eventDescription())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "物流事件编码和说明不能为空");
        }
        String code = request.eventCode().trim().toUpperCase(Locale.ROOT);
        if (!TRACKING_EVENTS.contains(code) || "SHIPPED".equals(code)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "物流事件编码不合法");
        return new TrackingValues(code, required(request.eventDescription(), 500, "物流说明"),
                optional(request.location(), 255), request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt(),
                StringUtils.hasText(request.source()) ? request.source().trim().toUpperCase(Locale.ROOT) : "MANUAL",
                optional(request.externalEventId(), 100));
    }
    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "page从1开始，pageSize范围为1—100");
    }
    private void requireKey(String key) {
        if (!StringUtils.hasText(key) || key.trim().length() > 100) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Idempotency-Key不能为空且最多100字符");
    }
    private long ensureOperator(AuthenticatedPrincipal principal) {
        jdbcTemplate.update("""
                INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE')
                ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)
                """, principal.databaseOpenId(), principal.displayName());
        return requireUser(principal);
    }
    private long requireUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户不存在");
        return ids.get(0);
    }
    private void audit(long operator, String action, String targetType, long targetId, String value) {
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,?,?,JSON_OBJECT('value',?))
                """, operator, action, targetType, String.valueOf(targetId), value);
    }
    private String required(String value, int max, String name) {
        if (!StringUtils.hasText(value)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, name + "不能为空");
        String text = value.trim();
        if (text.length() > max) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, name + "长度超过限制");
        return text;
    }
    private String optional(String value, int max) { return StringUtils.hasText(value) ? required(value, max, "字段") : null; }

    private record CarrierValues(String code,String name,String url,boolean enabled,int sortOrder) {}
    private record ShipValues(String carrierCode,String trackingNo,int autoReceiveDays,String remark) {}
    private record TrackingValues(String eventCode,String description,String location,LocalDateTime occurredAt,
                                  String source,String externalEventId) {}
    private record OrderLocked(long id,String orderNo,long userId,String status,String orderType) {}
    private record ShipmentLocked(long id,String status,LocalDateTime shippedAt) {}
    private record ShipmentHead(long id,long orderId,String orderNo,String status,String carrierCode,String carrierName,
                                String trackingNo,String urlTemplate,LocalDateTime preparedAt,LocalDateTime shippedAt,
                                LocalDateTime autoReceiveAt,LocalDateTime receivedAt,boolean exceptionFlag,
                                String exceptionReason) {}
}
