package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import com.iminiapp.backend.vehicle.api.VehicleRentalCreateRequest;
import com.iminiapp.backend.vehicle.api.VehicleRentalOrderResponse;
import com.iminiapp.backend.vehicle.api.VehicleRentalModelResponse;
import com.iminiapp.backend.vehicle.api.VehicleRentalQuoteResponse;
import com.iminiapp.backend.vehicle.api.VehicleRentalVehicleResponse;
import com.iminiapp.backend.vehicle.api.VehicleRentalStatusResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 社会租赁的报价和待支付订单。具体派车、还车结算在后续阶段实现。 */
@Service
public class VehicleRentalService {
    private static final String ORDER_TYPE = "VEHICLE_RENTAL";
    private static final List<String> RENTAL_MODES = List.of("HALF_DAY", "FULL_DAY", "MONTH", "BARE_MONTH");
    private final JdbcTemplate jdbcTemplate;
    private final VehicleSensitiveDataReader sensitiveDataReader;

    public VehicleRentalService(JdbcTemplate jdbcTemplate, SensitiveDataCipher sensitiveDataCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveDataReader = new VehicleSensitiveDataReader(sensitiveDataCipher);
    }

    @Transactional(readOnly = true)
    public VehicleRentalQuoteResponse quote(long modelId, String rentalMode, LocalDateTime startAt, LocalDateTime endAt,
                                            Integer passengerCount, BigDecimal estimatedKm) {
        QuoteData data = quoteData(modelId, rentalMode, startAt, endAt, passengerCount, estimatedKm);
        return data.response();
    }

    @Transactional(readOnly = true)
    public List<VehicleRentalModelResponse> listModels() {
        return jdbcTemplate.query("""
                SELECT m.id,m.model_code,m.name,m.seat_count,t.half_day_cent,t.full_day_cent,t.month_cent,t.bare_month_cent
                FROM bf_vehicle_model m JOIN bf_resource_sku s ON s.sku_code=CONCAT('VEHICLE_RENTAL_',m.model_code) AND s.status='ACTIVE'
                JOIN bf_vehicle_tariff t ON t.model_id=m.id AND t.enabled=1 AND t.effective_from<=CURRENT_DATE
                    AND (t.effective_to IS NULL OR t.effective_to>=CURRENT_DATE)
                WHERE m.status='ACTIVE' ORDER BY m.seat_count,m.id
                """, (rs, n) -> new VehicleRentalModelResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                nullableLong(rs, "half_day_cent"), nullableLong(rs, "full_day_cent"), nullableLong(rs, "month_cent"), nullableLong(rs, "bare_month_cent")));
    }

    @Transactional(readOnly = true)
    public List<VehicleRentalVehicleResponse> listModelVehicles(long modelId) {
        Integer modelCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_vehicle_model WHERE id=? AND status='ACTIVE'", Integer.class, modelId);
        if (modelCount == null || modelCount == 0) throw new BusinessException(CommonErrorCode.NOT_FOUND, "车型不存在或未上架");
        return jdbcTemplate.query("""
                SELECT v.id,v.model_id,m.name,v.image_url,CAST(v.configuration AS CHAR),v.status,v.mileage_km
                FROM bf_vehicle v JOIN bf_vehicle_model m ON m.id=v.model_id
                WHERE v.model_id=? AND v.status NOT IN ('INACTIVE','MAINTENANCE') ORDER BY v.id
                """, (rs,n) -> new VehicleRentalVehicleResponse(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getBigDecimal(7)), modelId);
    }

    @Transactional
    public List<VehicleRentalOrderResponse> listAdmin(AuthenticatedPrincipal principal, String status) {
        ensureUser(principal);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        String sql = "SELECT id FROM bf_order WHERE order_type=?" + (normalized == null ? " ORDER BY id DESC" : " AND status=? ORDER BY id DESC");
        List<Long> ids = normalized == null
                ? jdbcTemplate.query(sql,(rs,n)->rs.getLong(1),ORDER_TYPE)
                : jdbcTemplate.query(sql,(rs,n)->rs.getLong(1),ORDER_TYPE,normalized);
        return ids.stream().map(id -> find(id,0,false)).toList();
    }

    @Transactional
    public VehicleRentalOrderResponse create(AuthenticatedPrincipal principal, String idempotencyKey, VehicleRentalCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Idempotency-Key 不能为空且最长64字符");
        }
        if (request == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "请求体不能为空");
        long userId = ensureUser(principal);
        List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM bf_order WHERE user_id=? AND client_request_id=?", Long.class, userId, idempotencyKey.trim());
        if (!existing.isEmpty()) return find(existing.get(0), userId, true);
        if (request.modelId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "modelId 不能为空");
        QuoteData quote = quoteData(request.modelId(), request.rentalMode(), request.startAt(), request.endAt(), request.passengerCount(), request.estimatedKm());
        if (quote.response().availableCount() < 1) throw new BusinessException(CommonErrorCode.CONFLICT, "该车型在所选时段已无可用车辆");
        String departure = required(request.departure(), "departure", 255);
        String destination = required(request.destination(), "destination", 255);
        validateCoordinates(request.departureLongitude(), request.departureLatitude(), "上车地点");
        validateCoordinates(request.destinationLongitude(), request.destinationLatitude(), "目的地");
        String coordinateSystem = normalizeCoordinateSystem(request.coordinateSystem());
        String remark = optional(request.remark(), 500);
        String orderNo = "BFHVR" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        long orderId = insertOrder(userId, idempotencyKey.trim(), orderNo, quote.response().payableAmountCent(), remark);
        long itemId = insertItem(orderId, quote);
        jdbcTemplate.update("""
                INSERT INTO bf_vehicle_booking(order_item_id,business_mode,start_at,end_at,departure,departure_longitude,departure_latitude,
                    destination,destination_longitude,destination_latitude,coordinate_system,passenger_count,estimated_km,rental_mode,dispatch_deadline,deposit_amount_cent)
                VALUES (?,'SOCIAL_RENTAL',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, itemId, quote.startAt(), quote.endAt(), departure, request.departureLongitude(), request.departureLatitude(),
                destination, request.destinationLongitude(), request.destinationLatitude(), coordinateSystem, quote.passengerCount(), quote.estimatedKm(),
                quote.response().rentalMode(), quote.startAt().minusHours(1), quote.response().depositAmountCent());
        jdbcTemplate.update("INSERT INTO bf_order_status_log(order_id,from_status,to_status,operator_id,reason,idempotency_key) VALUES (?,?,?,?,?,?)",
                orderId, null, "PENDING_PAYMENT", userId, "社会租赁订单创建", "VEHICLE-RENTAL-CREATE:" + orderId);
        jdbcTemplate.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,'VEHICLE_RENTAL_CREATE','ORDER',?,JSON_OBJECT('modelId',?,'payableAmountCent',?))",
                userId, String.valueOf(orderId), quote.modelId(), quote.response().payableAmountCent());
        return find(orderId, userId, true);
    }

    @Transactional(readOnly = true)
    public List<VehicleRentalOrderResponse> listMine(AuthenticatedPrincipal principal) {
        long userId = requireUser(principal);
        return jdbcTemplate.query("SELECT id FROM bf_order WHERE user_id=? AND order_type=? ORDER BY id DESC", (rs, n) -> rs.getLong(1), userId, ORDER_TYPE)
                .stream().map(id -> find(id, userId, true)).toList();
    }

    @Transactional(readOnly = true)
    public VehicleRentalStatusResponse status(AuthenticatedPrincipal principal, long orderId) {
        long userId = requireUser(principal);
        List<VehicleRentalStatusResponse> rows = jdbcTemplate.query("""
                SELECT o.id,o.order_no,o.status,
                       COALESCE((SELECT MAX(l.created_at) FROM bf_order_status_log l WHERE l.order_id=o.id),o.created_at) status_updated_at,
                       d.id dispatch_id,d.status dispatch_status,dr.id driver_id,
                       dr.name_cipher driver_name_cipher,dr.mobile_cipher driver_phone_cipher,v.plate_no,
                       d.current_longitude,d.current_latitude,d.coordinate_system,d.location_updated_at
                FROM bf_order o
                LEFT JOIN bf_order_item i ON i.order_id=o.id
                LEFT JOIN bf_vehicle_dispatch d ON d.order_item_id=i.id
                LEFT JOIN bf_driver dr ON dr.id=d.driver_id
                LEFT JOIN bf_vehicle v ON v.id=d.vehicle_id
                WHERE o.id=? AND o.user_id=? AND o.order_type=?
                ORDER BY d.id DESC LIMIT 1
                """, (rs, n) -> {
            byte[] nameCipher = rs.getBytes("driver_name_cipher");
            byte[] phoneCipher = rs.getBytes("driver_phone_cipher");
            String driverName = sensitiveDataReader.decryptOrNull(nameCipher);
            String phone = sensitiveDataReader.decryptOrNull(phoneCipher);
            return new VehicleRentalStatusResponse(
                    rs.getLong("id"), rs.getString("order_no"), uiStatus(rs.getString("status")),
                    rs.getObject("status_updated_at", LocalDateTime.class),
                    rs.getObject("dispatch_id", Long.class), rs.getString("dispatch_status"),
                    rs.getObject("driver_id", Long.class), driverName, phone,
                    maskPhone(phone), rs.getString("plate_no"), rs.getBigDecimal("current_longitude"),
                    rs.getBigDecimal("current_latitude"), rs.getString("coordinate_system"),
                    rs.getObject("location_updated_at", LocalDateTime.class));
        }, orderId, userId, ORDER_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "社会租赁订单不存在");
        return rows.get(0);
    }

    private String uiStatus(String orderStatus) {
        return switch (orderStatus) {
            case "WAITING_DRIVER" -> "PENDING_ACCEPT";
            case "ACCEPTED" -> "DRIVER_ACCEPTED";
            case "IN_SERVICE" -> "SERVICE_IN_PROGRESS";
            case "COMPLETED" -> "SERVICE_COMPLETED";
            default -> orderStatus;
        };
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private QuoteData quoteData(long modelId, String modeValue, LocalDateTime startAt, LocalDateTime endAt,
                                Integer passengersValue, BigDecimal estimatedKm) {
        String mode = normalizeMode(modeValue);
        if (startAt == null || endAt == null || !endAt.isAfter(startAt) || startAt.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "租用开始和结束时间不正确");
        }
        ModelTariff tariff = findTariff(modelId, startAt);
        int passengers = passengersValue == null ? 1 : passengersValue;
        if (passengers < 1 || passengers > tariff.seatCount()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "乘车人数应为1至" + tariff.seatCount() + "人");
        if (estimatedKm != null && estimatedKm.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "预计里程不能为负数");
        long rentalAmount = amountFor(tariff, mode, Duration.between(startAt, endAt));
        long deposit = defaultDeposit();
        long payable;
        try { payable = Math.addExact(rentalAmount, deposit); } catch (ArithmeticException ex) { throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "订单金额超出范围"); }
        int available = availableCount(tariff.modelId(), tariff.skuId(), startAt, endAt);
        return new QuoteData(tariff.modelId(), tariff.skuId(), startAt, endAt, passengers, estimatedKm,
                new VehicleRentalQuoteResponse(tariff.modelId(), tariff.modelCode(), tariff.modelName(), tariff.seatCount(),
                        mode, startAt, endAt, available, rentalAmount, deposit, payable, estimatedKm));
    }

    private ModelTariff findTariff(long modelId, LocalDateTime startAt) {
        List<ModelTariff> rows = jdbcTemplate.query("""
                SELECT m.id,m.model_code,m.name,m.seat_count,s.id sku_id,t.half_day_cent,t.full_day_cent,t.month_cent,t.bare_month_cent,
                       t.half_day_limit_hours,t.full_day_limit_hours
                FROM bf_vehicle_model m JOIN bf_resource_sku s ON s.sku_code=CONCAT('VEHICLE_RENTAL_',m.model_code) AND s.status='ACTIVE'
                JOIN bf_vehicle_tariff t ON t.model_id=m.id AND t.enabled=1 AND t.effective_from<=DATE(?)
                  AND (t.effective_to IS NULL OR t.effective_to>=DATE(?))
                WHERE m.id=? AND m.status='ACTIVE' ORDER BY t.effective_from DESC LIMIT 1
                """, (rs, n) -> new ModelTariff(rs.getLong("id"), rs.getString("model_code"), rs.getString("name"), rs.getInt("seat_count"),
                rs.getLong("sku_id"), nullableLong(rs, "half_day_cent"), nullableLong(rs, "full_day_cent"), nullableLong(rs, "month_cent"),
                nullableLong(rs, "bare_month_cent"), nullableInt(rs, "half_day_limit_hours"), nullableInt(rs, "full_day_limit_hours")), startAt, startAt, modelId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "车型不存在、未上架或当前没有有效价格");
        return rows.get(0);
    }

    private long amountFor(ModelTariff tariff, String mode, Duration duration) {
        long hours = Math.max(1, duration.toHours());
        return switch (mode) {
            case "HALF_DAY" -> amountWithin(tariff.halfDayCent(), hours, tariff.halfDayLimitHours(), "半天租赁");
            case "FULL_DAY" -> amountWithin(tariff.fullDayCent(), hours, tariff.fullDayLimitHours(), "全天租赁");
            case "MONTH" -> amountAtLeast(tariff.monthCent(), duration, 28, "月租");
            case "BARE_MONTH" -> amountAtLeast(tariff.bareMonthCent(), duration, 28, "裸租月租");
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "租赁方式不正确");
        };
    }
    private long amountWithin(Long amount, long hours, Integer limit, String label) { if (amount == null || limit == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, label + "暂未配置"); if (hours > limit) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, label + "最长" + limit + "小时"); return amount; }
    private long amountAtLeast(Long amount, Duration duration, int days, String label) { if (amount == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, label + "暂未配置"); if (duration.toDays() < days) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, label + "至少" + days + "天"); return amount; }

    private int availableCount(long modelId, long skuId, LocalDateTime startAt, LocalDateTime endAt) {
        Integer usable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_vehicle v WHERE v.model_id=? AND v.status NOT IN ('MAINTENANCE','INACTIVE')
                AND NOT EXISTS (SELECT 1 FROM bf_vehicle_unavailable_period u WHERE u.vehicle_id=v.id AND u.status='ACTIVE'
                    AND u.start_at<? AND u.end_at>?)
                """, Integer.class, modelId, endAt, startAt);
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_vehicle_booking b JOIN bf_order_item i ON i.id=b.order_item_id JOIN bf_order o ON o.id=i.order_id
                WHERE i.sku_id=? AND b.business_mode='SOCIAL_RENTAL' AND o.status IN ('PENDING_PAYMENT','PAID','WAITING_DRIVER','ACCEPTED','IN_SERVICE')
                  AND b.start_at<? AND b.end_at>?
                """, Integer.class, skuId, endAt, startAt);
        return Math.max(0, (usable == null ? 0 : usable) - (reserved == null ? 0 : reserved));
    }

    private long defaultDeposit() { List<String> values=jdbcTemplate.queryForList("SELECT setting_value FROM bf_platform_setting WHERE setting_key='vehicle.social_rental.default_deposit_cent'",String.class); if(values.isEmpty()) return 100000L; try { long value=Long.parseLong(values.get(0)); if(value<0) throw new NumberFormatException(); return value; } catch(NumberFormatException ex) { throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,"社会租赁押金配置不正确"); } }
    private long insertOrder(long userId,String key,String orderNo,long amount,String remark){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_order(order_no,user_id,client_request_id,order_type,status,total_amount_cent,discount_amount_cent,payable_amount_cent,paid_amount_cent,expires_at,remark) VALUES (?,?,?,?,'PENDING_PAYMENT',?,0,?,0,?,?)",Statement.RETURN_GENERATED_KEYS);s.setString(1,orderNo);s.setLong(2,userId);s.setString(3,key);s.setString(4,ORDER_TYPE);s.setLong(5,amount);s.setLong(6,amount);s.setObject(7,LocalDateTime.now().plusMinutes(15));s.setString(8,remark);return s;},h);return key(h,"社会租赁订单创建失败");}
    private long insertItem(long orderId,QuoteData quote){KeyHolder h=new GeneratedKeyHolder();jdbcTemplate.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO bf_order_item(order_id,sku_id,sku_code,sku_name,resource_name,resource_type,quantity,unit_price_cent,amount_cent,snapshot) SELECT ?,s.id,s.sku_code,s.name,r.name,r.resource_type,1,?,?,JSON_OBJECT('rentalAmountCent',?,'depositAmountCent',?) FROM bf_resource_sku s JOIN bf_resource r ON r.id=s.resource_id WHERE s.id=?",Statement.RETURN_GENERATED_KEYS);s.setLong(1,orderId);s.setLong(2,quote.response().rentalAmountCent());s.setLong(3,quote.response().rentalAmountCent());s.setLong(4,quote.response().rentalAmountCent());s.setLong(5,quote.response().depositAmountCent());s.setLong(6,quote.skuId());return s;},h);return key(h,"社会租赁订单明细创建失败");}
    private VehicleRentalOrderResponse find(long orderId,long userId,boolean owned){List<VehicleRentalOrderResponse> rows=jdbcTemplate.query("""
                SELECT o.id,o.order_no,o.status,m.id model_id,m.name model_name,b.rental_mode,i.amount_cent rental_amount,b.deposit_amount_cent,o.payable_amount_cent,o.expires_at,o.created_at
                FROM bf_order o JOIN bf_order_item i ON i.order_id=o.id JOIN bf_vehicle_booking b ON b.order_item_id=i.id
                JOIN bf_vehicle_model m ON i.sku_code=CONCAT('VEHICLE_RENTAL_',m.model_code) WHERE o.id=? AND o.order_type=?
                """,(rs,n)->new VehicleRentalOrderResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getObject(10,LocalDateTime.class),rs.getObject(11,LocalDateTime.class)),orderId,ORDER_TYPE);if(rows.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"社会租赁订单不存在");if(owned){Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_order WHERE id=? AND user_id=?",Integer.class,orderId,userId);if(count==null||count==0)throw new BusinessException(CommonErrorCode.NOT_FOUND,"社会租赁订单不存在");}return rows.get(0);}
    private String normalizeMode(String value){if(!StringUtils.hasText(value))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"rentalMode 不能为空");String mode=value.trim().toUpperCase(Locale.ROOT);if(!RENTAL_MODES.contains(mode))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"rentalMode 仅支持 HALF_DAY、FULL_DAY、MONTH、BARE_MONTH");return mode;}
    private long ensureUser(AuthenticatedPrincipal p){jdbcTemplate.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());return jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());}
    private long requireUser(AuthenticatedPrincipal p){List<Long> ids=jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());if(ids.isEmpty())throw new BusinessException(CommonErrorCode.NOT_FOUND,"用户不存在");return ids.get(0);}
    private long key(KeyHolder h,String message){if(h.getKey()==null)throw new BusinessException(CommonErrorCode.INTERNAL_ERROR,message);return h.getKey().longValue();}
    private Long nullableLong(java.sql.ResultSet rs,String column)throws java.sql.SQLException{long value=rs.getLong(column);return rs.wasNull()?null:value;}
    private Integer nullableInt(java.sql.ResultSet rs,String column)throws java.sql.SQLException{int value=rs.getInt(column);return rs.wasNull()?null:value;}
    private String required(String value,String field,int max){if(!StringUtils.hasText(value)||value.trim().length()>max)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,field+" 不能为空且最长"+max+"字符");return value.trim();}
    private String optional(String value,int max){if(!StringUtils.hasText(value))return null;if(value.trim().length()>max)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"字段最长"+max+"字符");return value.trim();}
    private String normalizeCoordinateSystem(String value){if(!StringUtils.hasText(value))return "GCJ-02";String normalized=value.trim().toUpperCase(Locale.ROOT);if(!"GCJ-02".equals(normalized))throw new BusinessException(CommonErrorCode.INVALID_REQUEST,"地图坐标系仅支持 GCJ-02");return normalized;}
    private void validateCoordinates(BigDecimal longitude,BigDecimal latitude,String label){if(longitude==null&&latitude==null)return;if(longitude==null||latitude==null)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,label+"经纬度必须同时提供");if(longitude.compareTo(BigDecimal.valueOf(-180))<0||longitude.compareTo(BigDecimal.valueOf(180))>0||latitude.compareTo(BigDecimal.valueOf(-90))<0||latitude.compareTo(BigDecimal.valueOf(90))>0)throw new BusinessException(CommonErrorCode.INVALID_REQUEST,label+"经纬度超出范围");}
    private record ModelTariff(long modelId,String modelCode,String modelName,int seatCount,long skuId,Long halfDayCent,Long fullDayCent,Long monthCent,Long bareMonthCent,Integer halfDayLimitHours,Integer fullDayLimitHours){}
    private record QuoteData(long modelId,long skuId,LocalDateTime startAt,LocalDateTime endAt,int passengerCount,BigDecimal estimatedKm,VehicleRentalQuoteResponse response){}
}
