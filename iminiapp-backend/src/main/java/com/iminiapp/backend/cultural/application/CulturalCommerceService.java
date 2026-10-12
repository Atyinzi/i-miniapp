package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalCommerceModels.*;
import com.iminiapp.backend.cultural.api.CulturalPromotionModels.PricingQuoteResponse;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.order.api.CreateOrderRequest;
import com.iminiapp.backend.order.api.OrderResponse;
import com.iminiapp.backend.order.application.OrderApplicationService;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CulturalCommerceService {
    private final JdbcTemplate jdbcTemplate;
    private final OrderApplicationService orderService;
    private final CulturalPromotionService promotionService;

    public CulturalCommerceService(JdbcTemplate jdbcTemplate, OrderApplicationService orderService,
                                   CulturalPromotionService promotionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderService = orderService;
        this.promotionService = promotionService;
    }

    @Transactional
    public List<AddressResponse> addresses(AuthenticatedPrincipal principal) {
        long userId = ensureUser(principal);
        return jdbcTemplate.query("""
                SELECT id,receiver_name,receiver_mobile,province,city,district,detail_address,postal_code,
                       is_default,updated_at FROM bf_user_address
                WHERE user_id=? AND deleted=0 ORDER BY is_default DESC,id DESC
                """, (rs, n) -> addressMapper(rs), userId);
    }

    @Transactional
    public AddressResponse createAddress(AuthenticatedPrincipal principal, AddressUpsertRequest request) {
        AddressValues value = validateAddress(request);
        long userId = ensureUser(principal);
        if (value.isDefault() || activeAddressCount(userId) == 0) clearDefault(userId);
        boolean makeDefault = value.isDefault() || activeAddressCount(userId) == 0;
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_user_address
                    (user_id,receiver_name,receiver_mobile,province,city,district,detail_address,postal_code,is_default)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId); statement.setString(2, value.receiverName());
            statement.setString(3, value.receiverMobile()); statement.setString(4, value.province());
            statement.setString(5, value.city()); statement.setString(6, value.district());
            statement.setString(7, value.detailAddress()); statement.setString(8, value.postalCode());
            statement.setBoolean(9, makeDefault); return statement;
        }, keys);
        return findAddress(userId, keys.getKey().longValue());
    }

    @Transactional
    public AddressResponse updateAddress(AuthenticatedPrincipal principal, long addressId, AddressUpsertRequest request) {
        long userId = requireUser(principal);
        lockAddress(userId, addressId);
        AddressValues value = validateAddress(request);
        if (value.isDefault()) clearDefault(userId);
        jdbcTemplate.update("""
                UPDATE bf_user_address SET receiver_name=?,receiver_mobile=?,province=?,city=?,district=?,
                       detail_address=?,postal_code=?,is_default=? WHERE id=? AND user_id=? AND deleted=0
                """, value.receiverName(), value.receiverMobile(), value.province(), value.city(), value.district(),
                value.detailAddress(), value.postalCode(), value.isDefault(), addressId, userId);
        ensureDefaultAddress(userId);
        return findAddress(userId, addressId);
    }

    @Transactional
    public void deleteAddress(AuthenticatedPrincipal principal, long addressId) {
        long userId = requireUser(principal);
        AddressResponse address = lockAddress(userId, addressId);
        jdbcTemplate.update("UPDATE bf_user_address SET deleted=1,is_default=0 WHERE id=? AND user_id=?", addressId, userId);
        if (address.isDefault()) ensureDefaultAddress(userId);
    }

    @Transactional
    public List<CartItemResponse> cart(AuthenticatedPrincipal principal) {
        return queryCart(ensureUser(principal));
    }

    @Transactional
    public CartItemResponse putCartItem(AuthenticatedPrincipal principal, CartItemUpsertRequest request) {
        if (request == null || request.skuId() == null || request.quantity() == null
                || request.quantity() < 1 || request.quantity() > 99) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "SKU和购物车数量不合法");
        }
        long userId = ensureUser(principal);
        PurchasableSku sku = requirePurchasableSku(request.skuId());
        if (request.quantity() > sku.available()) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT, "库存不足");
        jdbcTemplate.update("""
                INSERT INTO bf_cultural_cart_item(user_id,sku_id,quantity,selected) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),selected=VALUES(selected),updated_at=CURRENT_TIMESTAMP
                """, userId, request.skuId(), request.quantity(), request.selected() == null || request.selected());
        return findCartItem(userId, request.skuId());
    }

    @Transactional
    public CartItemResponse selectCartItem(AuthenticatedPrincipal principal, long skuId, boolean selected) {
        long userId = requireUser(principal);
        int changed = jdbcTemplate.update("UPDATE bf_cultural_cart_item SET selected=? WHERE user_id=? AND sku_id=?",
                selected, userId, skuId);
        if (changed != 1) throw new BusinessException(CommonErrorCode.NOT_FOUND, "购物车商品不存在");
        return findCartItem(userId, skuId);
    }

    @Transactional
    public void removeCartItem(AuthenticatedPrincipal principal, long skuId) {
        long userId = requireUser(principal);
        if (jdbcTemplate.update("DELETE FROM bf_cultural_cart_item WHERE user_id=? AND sku_id=?", userId, skuId) != 1) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "购物车商品不存在");
        }
    }

    @Transactional
    public CulturalOrderResponse checkout(AuthenticatedPrincipal principal, String idempotencyKey,
                                          CheckoutRequest request) {
        if (request == null || request.addressId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "收货地址不能为空");
        }
        long userId = requireUser(principal);
        AddressResponse address = lockAddress(userId, request.addressId());
        List<CheckoutItem> checkoutItems = request.items();
        if (checkoutItems == null || checkoutItems.isEmpty()) {
            checkoutItems = jdbcTemplate.query("""
                    SELECT sku_id,quantity FROM bf_cultural_cart_item
                    WHERE user_id=? AND selected=1 ORDER BY id
                    """, (rs, n) -> new CheckoutItem(rs.getLong("sku_id"), rs.getInt("quantity")), userId);
        }
        validateCheckoutItems(checkoutItems);
        List<CreateOrderRequest.Item> orderItems = checkoutItems.stream()
                .map(item -> new CreateOrderRequest.Item(item.skuId(), item.quantity(), null, "", Map.of()))
                .toList();
        OrderResponse order = orderService.create(principal, idempotencyKey,
                new CreateOrderRequest("CULTURAL_PRODUCT", orderItems, request.remark()));
        PricingQuoteResponse pricing = promotionService.applyToOrder(principal, order.id(), request.userCouponId());
        order = orderService.detail(principal, order.id());
        jdbcTemplate.update("""
                INSERT IGNORE INTO bf_cultural_order_delivery
                (order_id,source_address_id,receiver_name,receiver_mobile,province,city,district,detail_address,postal_code)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, order.id(), address.id(), address.receiverName(), address.receiverMobile(), address.province(),
                address.city(), address.district(), address.detailAddress(), address.postalCode());
        for (CheckoutItem item : checkoutItems) {
            jdbcTemplate.update("DELETE FROM bf_cultural_cart_item WHERE user_id=? AND sku_id=?", userId, item.skuId());
        }
        return new CulturalOrderResponse(order, delivery(order.id(), userId), pricing);
    }

    @Transactional(readOnly = true)
    public CulturalOrderResponse orderDetail(AuthenticatedPrincipal principal, long orderId) {
        long userId = requireUser(principal);
        OrderResponse order = orderService.detail(principal, orderId);
        if (!"CULTURAL_PRODUCT".equals(order.orderType())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单不存在");
        }
        return new CulturalOrderResponse(order, delivery(orderId, userId), promotionService.orderPricing(orderId, userId));
    }

    @Transactional
    public CulturalOrderResponse cancelOrder(AuthenticatedPrincipal principal, long orderId) {
        OrderResponse current = orderService.detail(principal, orderId);
        if (!"CULTURAL_PRODUCT".equals(current.orderType())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单不存在");
        }
        OrderResponse cancelled = orderService.cancel(principal, orderId);
        long userId = requireUser(principal);
        return new CulturalOrderResponse(cancelled, delivery(orderId, userId), promotionService.orderPricing(orderId, userId));
    }

    private DeliverySnapshot delivery(long orderId, long userId) {
        List<DeliverySnapshot> rows = jdbcTemplate.query("""
                SELECT d.source_address_id,d.receiver_name,d.receiver_mobile,d.province,d.city,d.district,
                       d.detail_address,d.postal_code
                FROM bf_cultural_order_delivery d JOIN bf_order o ON o.id=d.order_id
                WHERE d.order_id=? AND o.user_id=?
                """, (rs, n) -> new DeliverySnapshot(rs.getLong("source_address_id"), rs.getString("receiver_name"),
                rs.getString("receiver_mobile"), rs.getString("province"), rs.getString("city"),
                rs.getString("district"), rs.getString("detail_address"), rs.getString("postal_code")), orderId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创订单收货信息不存在");
        return rows.get(0);
    }

    private List<CartItemResponse> queryCart(long userId) {
        return jdbcTemplate.query(cartSelect() + " WHERE ci.user_id=? ORDER BY ci.updated_at DESC",
                (rs, n) -> cartItemMapper(rs), userId);
    }
    private CartItemResponse findCartItem(long userId, long skuId) {
        List<CartItemResponse> rows = jdbcTemplate.query(cartSelect() + " WHERE ci.user_id=? AND ci.sku_id=?",
                (rs, n) -> cartItemMapper(rs), userId, skuId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "购物车商品不存在");
        return rows.get(0);
    }
    private String cartSelect() {
        return """
                SELECT ci.id,sku.id sku_id,sku.sku_code,r.name product_name,sku.name sku_name,r.cover_url,
                       sku.price_cent,ci.quantity,ci.selected,st.available_quantity,ci.updated_at,
                       r.status product_status,sku.status sku_status,c.status category_status
                FROM bf_cultural_cart_item ci
                JOIN bf_resource_sku sku ON sku.id=ci.sku_id
                JOIN bf_resource r ON r.id=sku.resource_id AND r.resource_type='CULTURAL_PRODUCT'
                JOIN bf_cultural_category c ON c.category_code=r.category_code
                JOIN bf_cultural_stock st ON st.sku_id=sku.id
                """;
    }
    private CartItemResponse cartItemMapper(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean active = "ACTIVE".equals(rs.getString("product_status"))
                && "ACTIVE".equals(rs.getString("sku_status")) && "ACTIVE".equals(rs.getString("category_status"));
        int stock = rs.getInt("available_quantity");
        boolean purchasable = active && stock >= rs.getInt("quantity");
        String reason = purchasable ? null : !active ? "商品已下架" : "库存不足";
        return new CartItemResponse(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("sku_code"),
                rs.getString("product_name"), rs.getString("sku_name"), rs.getString("cover_url"),
                rs.getLong("price_cent"), rs.getInt("quantity"), rs.getBoolean("selected"), stock,
                purchasable, reason, rs.getObject("updated_at", java.time.LocalDateTime.class));
    }

    private PurchasableSku requirePurchasableSku(long skuId) {
        List<PurchasableSku> rows = jdbcTemplate.query("""
                SELECT sku.id,st.available_quantity FROM bf_resource_sku sku
                JOIN bf_resource r ON r.id=sku.resource_id AND r.resource_type='CULTURAL_PRODUCT' AND r.status='ACTIVE'
                JOIN bf_cultural_category c ON c.category_code=r.category_code AND c.status='ACTIVE'
                JOIN bf_cultural_stock st ON st.sku_id=sku.id
                WHERE sku.id=? AND sku.status='ACTIVE'
                """, (rs, n) -> new PurchasableSku(rs.getLong(1), rs.getInt(2)), skuId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "商品不存在、已下架或SKU已停用");
        return rows.get(0);
    }
    private void validateCheckoutItems(List<CheckoutItem> items) {
        if (items == null || items.isEmpty() || items.size() > 20) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "结算商品数量范围为1—20");
        }
        Set<Long> ids = new HashSet<>();
        for (CheckoutItem item : items) {
            if (item == null || item.skuId() == null || item.quantity() == null
                    || item.quantity() < 1 || item.quantity() > 99 || !ids.add(item.skuId())) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "结算商品SKU或数量不合法、存在重复SKU");
            }
        }
    }

    private AddressValues validateAddress(AddressUpsertRequest request) {
        if (request == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "收货地址不能为空");
        String name = required(request.receiverName(), 64, "收货人");
        String mobile = required(request.receiverMobile(), 32, "手机号");
        if (!mobile.matches("^[0-9+ -]{6,32}$")) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "手机号格式不合法");
        return new AddressValues(name, mobile, required(request.province(), 64, "省份"),
                required(request.city(), 64, "城市"), required(request.district(), 64, "区县"),
                required(request.detailAddress(), 255, "详细地址"), optional(request.postalCode(), 16),
                Boolean.TRUE.equals(request.isDefault()));
    }
    private AddressResponse lockAddress(long userId, long addressId) {
        List<AddressResponse> rows = jdbcTemplate.query("""
                SELECT id,receiver_name,receiver_mobile,province,city,district,detail_address,postal_code,
                       is_default,updated_at FROM bf_user_address
                WHERE id=? AND user_id=? AND deleted=0 FOR UPDATE
                """, (rs, n) -> addressMapper(rs), addressId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "收货地址不存在或无权访问");
        return rows.get(0);
    }
    private AddressResponse findAddress(long userId, long addressId) {
        List<AddressResponse> rows = jdbcTemplate.query("""
                SELECT id,receiver_name,receiver_mobile,province,city,district,detail_address,postal_code,
                       is_default,updated_at FROM bf_user_address WHERE id=? AND user_id=? AND deleted=0
                """, (rs, n) -> addressMapper(rs), addressId, userId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "收货地址不存在");
        return rows.get(0);
    }
    private AddressResponse addressMapper(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AddressResponse(rs.getLong("id"), rs.getString("receiver_name"),
                rs.getString("receiver_mobile"), rs.getString("province"), rs.getString("city"),
                rs.getString("district"), rs.getString("detail_address"), rs.getString("postal_code"),
                rs.getBoolean("is_default"), rs.getObject("updated_at", java.time.LocalDateTime.class));
    }
    private void clearDefault(long userId) { jdbcTemplate.update("UPDATE bf_user_address SET is_default=0 WHERE user_id=? AND deleted=0", userId); }
    private void ensureDefaultAddress(long userId) {
        Integer defaults = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_user_address WHERE user_id=? AND deleted=0 AND is_default=1", Integer.class, userId);
        if (defaults != null && defaults == 0) jdbcTemplate.update("UPDATE bf_user_address SET is_default=1 WHERE user_id=? AND deleted=0 ORDER BY id DESC LIMIT 1", userId);
    }
    private int activeAddressCount(long userId) { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_user_address WHERE user_id=? AND deleted=0", Integer.class, userId); }
    private long ensureUser(AuthenticatedPrincipal principal) {
        jdbcTemplate.update("""
                INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE')
                ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)
                """, principal.databaseOpenId(), principal.displayName());
        return requireUser(principal);
    }
    private long requireUser(AuthenticatedPrincipal principal) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
        if (ids.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "当前用户尚未建立商城资料");
        return ids.get(0);
    }
    private String required(String value, int max, String field) {
        if (!StringUtils.hasText(value)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, field + "不能为空");
        String text = value.trim();
        if (text.length() > max) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, field + "长度超过限制");
        return text;
    }
    private String optional(String value, int max) { return StringUtils.hasText(value) ? required(value, max, "可选字段") : null; }

    private record AddressValues(String receiverName, String receiverMobile, String province, String city,
                                 String district, String detailAddress, String postalCode, boolean isDefault) {}
    private record PurchasableSku(long skuId, int available) {}
}
