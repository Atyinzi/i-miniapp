package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalStoreModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.api.PageResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CulturalStoreService {
    private static final String RESOURCE_TYPE = "CULTURAL_PRODUCT";
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final Set<String> STOCK_TYPES = Set.of("INBOUND", "OUTBOUND", "ADJUSTMENT", "RETURN", "DAMAGE");
    private final JdbcTemplate jdbcTemplate;

    public CulturalStoreService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories(boolean publicOnly) {
        String statusFilter = publicOnly ? " AND c.status='ACTIVE'" : "";
        return jdbcTemplate.query("""
                SELECT c.id,c.category_code,c.name,c.parent_id,c.sort_order,c.status,
                       COUNT(p.resource_id) product_count
                FROM bf_cultural_category c
                LEFT JOIN bf_cultural_product_profile p ON p.category_id=c.id
                WHERE 1=1
                """ + statusFilter + " GROUP BY c.id,c.category_code,c.name,c.parent_id,c.sort_order,c.status"
                + " ORDER BY c.sort_order,c.id", (rs, rowNum) -> new CategoryResponse(
                rs.getLong("id"), rs.getString("category_code"), rs.getString("name"),
                rs.getObject("parent_id", Long.class), rs.getInt("sort_order"), rs.getString("status"),
                rs.getLong("product_count")));
    }

    @Transactional
    public CategoryResponse createCategory(AuthenticatedPrincipal principal, CategoryUpsertRequest request) {
        CategoryValues value = validateCategory(request);
        requireUniqueCategoryCode(value.code(), null);
        validateParent(value.parentId(), null);
        long operatorId = ensureOperator(principal);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_cultural_category(category_code,name,parent_id,sort_order,status)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, value.code());
            statement.setString(2, value.name());
            if (value.parentId() == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, value.parentId());
            statement.setInt(4, value.sortOrder());
            statement.setString(5, value.status());
            return statement;
        }, keys);
        long id = generatedId(keys);
        audit(operatorId, "CULTURAL_CATEGORY_CREATE", "CULTURAL_CATEGORY", id, value.code());
        return findCategory(id);
    }

    @Transactional
    public CategoryResponse updateCategory(AuthenticatedPrincipal principal, long id, CategoryUpsertRequest request) {
        requireCategory(id);
        CategoryValues value = validateCategory(request);
        requireUniqueCategoryCode(value.code(), id);
        validateParent(value.parentId(), id);
        long operatorId = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_cultural_category SET category_code=?,name=?,parent_id=?,sort_order=?,status=? WHERE id=?
                """, value.code(), value.name(), value.parentId(), value.sortOrder(), value.status(), id);
        audit(operatorId, "CULTURAL_CATEGORY_UPDATE", "CULTURAL_CATEGORY", id, value.code());
        return findCategory(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductListItem> adminProducts(String keyword, Long categoryId, String status,
                                                       int page, int pageSize) {
        return products(keyword, categoryId, status, false, page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductListItem> publicProducts(String keyword, Long categoryId, int page, int pageSize) {
        return products(keyword, categoryId, "ACTIVE", true, page, pageSize);
    }

    private PageResponse<ProductListItem> products(String keyword, Long categoryId, String status,
                                                   boolean publicOnly, int page, int pageSize) {
        validatePage(page, pageSize);
        StringBuilder where = new StringBuilder(" WHERE r.resource_type=?");
        List<Object> args = new ArrayList<>(); args.add(RESOURCE_TYPE);
        if (publicOnly) where.append(" AND r.status='ACTIVE' AND c.status='ACTIVE'");
        else if (StringUtils.hasText(status)) { where.append(" AND r.status=?"); args.add(normalizeStatus(status)); }
        if (categoryId != null) { where.append(" AND p.category_id=?"); args.add(categoryId); }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (r.name LIKE ? OR p.product_code LIKE ? OR COALESCE(r.description,'') LIKE ?)");
            String pattern = "%" + keyword.trim() + "%"; args.add(pattern); args.add(pattern); args.add(pattern);
        }
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bf_resource r
                JOIN bf_cultural_product_profile p ON p.resource_id=r.id
                JOIN bf_cultural_category c ON c.id=p.category_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((page - 1) * pageSize);
        List<ProductListItem> items = jdbcTemplate.query("""
                SELECT r.id,p.product_code,r.name,r.description,c.id category_id,c.name category_name,r.cover_url,r.status,
                       MIN(CASE WHEN sku.status='ACTIVE' THEN sku.price_cent END) minimum_price_cent,
                       COALESCE(SUM(CASE WHEN sku.status='ACTIVE' THEN st.available_quantity ELSE 0 END),0) total_available,
                       p.sales_count,COUNT(sku.id) sku_count,r.updated_at
                FROM bf_resource r
                JOIN bf_cultural_product_profile p ON p.resource_id=r.id
                JOIN bf_cultural_category c ON c.id=p.category_id
                LEFT JOIN bf_resource_sku sku ON sku.resource_id=r.id
                LEFT JOIN bf_cultural_stock st ON st.sku_id=sku.id
                """ + where + " GROUP BY r.id,p.product_code,r.name,r.description,c.id,c.name,r.cover_url,r.status,p.sales_count,r.updated_at"
                + " ORDER BY r.updated_at DESC,r.id DESC LIMIT ? OFFSET ?", (rs, n) -> new ProductListItem(
                rs.getLong("id"), rs.getString("product_code"), rs.getString("name"), rs.getString("description"), rs.getLong("category_id"),
                rs.getString("category_name"), rs.getString("cover_url"), rs.getString("status"),
                rs.getObject("minimum_price_cent", Long.class), rs.getLong("total_available"),
                rs.getLong("sales_count"), rs.getInt("sku_count"),
                rs.getObject("updated_at", java.time.LocalDateTime.class)), pageArgs.toArray());
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
    }

    @Transactional
    public ProductDetail createProduct(AuthenticatedPrincipal principal, ProductUpsertRequest request) {
        ProductValues value = validateProduct(request, true);
        requireUniqueProductCode(value.productCode(), null);
        long operatorId = ensureOperator(principal);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_resource(resource_type,category_code,name,description,cover_url,status,attributes)
                    VALUES (?,?,?,?,?,?,JSON_OBJECT())
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, RESOURCE_TYPE); statement.setString(2, value.categoryCode());
            statement.setString(3, value.name()); statement.setString(4, value.description());
            statement.setString(5, value.coverUrl()); statement.setString(6, value.status()); return statement;
        }, keys);
        long resourceId = generatedId(keys);
        jdbcTemplate.update("""
                INSERT INTO bf_cultural_product_profile
                (resource_id,category_id,product_code,detail_html,market_price_cent,weight_gram)
                VALUES (?,?,?,?,?,?)
                """, resourceId, value.categoryId(), value.productCode(), value.detailHtml(),
                value.marketPriceCent(), value.weightGram());
        replaceImages(resourceId, request.images());
        if (request.skus() == null || request.skus().isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "商品至少需要一个SKU");
        }
        for (SkuCreateRequest sku : request.skus()) createSkuInternal(resourceId, sku, operatorId);
        audit(operatorId, "CULTURAL_PRODUCT_CREATE", "CULTURAL_PRODUCT", resourceId, value.productCode());
        return productDetail(resourceId, false);
    }

    @Transactional
    public ProductDetail updateProduct(AuthenticatedPrincipal principal, long productId, ProductUpsertRequest request) {
        requireProduct(productId, false);
        ProductValues value = validateProduct(request, false);
        requireUniqueProductCode(value.productCode(), productId);
        long operatorId = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_resource SET category_code=?,name=?,description=?,cover_url=?,status=? WHERE id=?
                """, value.categoryCode(), value.name(), value.description(), value.coverUrl(), value.status(), productId);
        jdbcTemplate.update("""
                UPDATE bf_cultural_product_profile
                SET category_id=?,product_code=?,detail_html=?,market_price_cent=?,weight_gram=? WHERE resource_id=?
                """, value.categoryId(), value.productCode(), value.detailHtml(), value.marketPriceCent(),
                value.weightGram(), productId);
        replaceImages(productId, request.images());
        audit(operatorId, "CULTURAL_PRODUCT_UPDATE", "CULTURAL_PRODUCT", productId, value.productCode());
        return productDetail(productId, false);
    }

    @Transactional
    public ProductDetail updateProductStatus(AuthenticatedPrincipal principal, long productId, String status) {
        requireProduct(productId, false);
        String normalized = normalizeStatus(status);
        if ("ACTIVE".equals(normalized)) {
            Integer skuCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_resource_sku WHERE resource_id=? AND status='ACTIVE'", Integer.class, productId);
            if (skuCount == null || skuCount == 0) throw new BusinessException(CommonErrorCode.CONFLICT, "至少有一个启用SKU才能上架商品");
        }
        long operatorId = ensureOperator(principal);
        jdbcTemplate.update("UPDATE bf_resource SET status=? WHERE id=?", normalized, productId);
        audit(operatorId, "CULTURAL_PRODUCT_STATUS", "CULTURAL_PRODUCT", productId, normalized);
        return productDetail(productId, false);
    }

    @Transactional
    public SkuResponse createSku(AuthenticatedPrincipal principal, long productId, SkuCreateRequest request) {
        requireProduct(productId, false);
        long operatorId = ensureOperator(principal);
        long skuId = createSkuInternal(productId, request, operatorId);
        audit(operatorId, "CULTURAL_SKU_CREATE", "CULTURAL_SKU", skuId, request.skuCode());
        return findSku(skuId);
    }

    private long createSkuInternal(long productId, SkuCreateRequest request, long operatorId) {
        SkuValues value = validateSkuCreate(request);
        if (!jdbcTemplate.queryForList("SELECT id FROM bf_resource_sku WHERE sku_code=?", Long.class, value.skuCode()).isEmpty()) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "SKU编码已存在");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
                    VALUES (?,?,?,?,?,CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, productId); statement.setString(2, value.skuCode()); statement.setString(3, value.name());
            statement.setLong(4, value.priceCent()); statement.setString(5, value.status());
            statement.setString(6, value.attributes()); return statement;
        }, keys);
        long skuId = generatedId(keys);
        jdbcTemplate.update("""
                INSERT INTO bf_cultural_stock(sku_id,on_hand_quantity,locked_quantity,available_quantity,warning_threshold)
                VALUES (?,?,?,?,?)
                """, skuId, value.initialStock(), 0, value.initialStock(), value.warningThreshold());
        if (value.initialStock() > 0) {
            jdbcTemplate.update("""
                    INSERT INTO bf_cultural_stock_log
                    (sku_id,change_type,quantity_delta,on_hand_after,available_after,idempotency_key,operator_id,remark)
                    VALUES (?,'INBOUND',?,?,?,?,?,'创建SKU时初始化库存')
                    """, skuId, value.initialStock(), value.initialStock(), value.initialStock(),
                    "CULTURAL-INITIAL-" + skuId, operatorId);
        }
        return skuId;
    }

    @Transactional
    public SkuResponse updateSku(AuthenticatedPrincipal principal, long skuId, SkuUpdateRequest request) {
        requireCulturalSku(skuId);
        if (!StringUtils.hasText(request.name()) || request.priceCent() == null || request.priceCent() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "SKU名称和非负价格不能为空");
        }
        int threshold = request.warningThreshold() == null ? 10 : request.warningThreshold();
        if (threshold < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "预警阈值不能为负数");
        long operatorId = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_resource_sku SET name=?,price_cent=?,status=?,attributes=CAST(? AS JSON) WHERE id=?
                """, request.name().trim(), request.priceCent(), normalizeStatus(request.status()),
                jsonOrEmpty(request.attributes()), skuId);
        jdbcTemplate.update("UPDATE bf_cultural_stock SET warning_threshold=? WHERE sku_id=?", threshold, skuId);
        audit(operatorId, "CULTURAL_SKU_UPDATE", "CULTURAL_SKU", skuId, request.name());
        return findSku(skuId);
    }

    @Transactional(readOnly = true)
    public ProductDetail adminProductDetail(long productId) { return productDetail(productId, false); }

    @Transactional(readOnly = true)
    public ProductDetail publicProductDetail(long productId) { return productDetail(productId, true); }

    private ProductDetail productDetail(long productId, boolean publicOnly) {
        ProductHead head = requireProduct(productId, publicOnly);
        List<ImageResponse> images = jdbcTemplate.query("""
                SELECT id,image_url,image_type,sort_order FROM bf_resource_image
                WHERE resource_id=? ORDER BY sort_order,id
                """, (rs, n) -> new ImageResponse(rs.getLong("id"), rs.getString("image_url"),
                rs.getString("image_type"), rs.getInt("sort_order")), productId);
        String skuStatus = publicOnly ? " AND sku.status='ACTIVE'" : "";
        List<SkuResponse> skus = jdbcTemplate.query(skuSelect() + " WHERE sku.resource_id=?" + skuStatus
                + " ORDER BY sku.id", skuMapper(), productId);
        return new ProductDetail(head.id(), head.productCode(), head.name(), head.categoryId(), head.categoryName(),
                head.description(), head.coverUrl(), head.detailHtml(), head.marketPriceCent(), head.weightGram(),
                head.salesCount(), head.status(), images, skus);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockResponse> stocks(Boolean lowStock, String keyword, int page, int pageSize) {
        validatePage(page, pageSize);
        StringBuilder where = new StringBuilder(" WHERE r.resource_type=?");
        List<Object> args = new ArrayList<>(); args.add(RESOURCE_TYPE);
        if (Boolean.TRUE.equals(lowStock)) where.append(" AND st.available_quantity<=st.warning_threshold");
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (r.name LIKE ? OR sku.name LIKE ? OR sku.sku_code LIKE ?)");
            String p = "%" + keyword.trim() + "%"; args.add(p); args.add(p); args.add(p);
        }
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + stockFrom() + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((page - 1) * pageSize);
        List<StockResponse> items = jdbcTemplate.query(stockSelect() + where + " ORDER BY st.available_quantity,sku.id LIMIT ? OFFSET ?",
                stockMapper(), pageArgs.toArray());
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
    }

    @Transactional
    public StockResponse adjustStock(AuthenticatedPrincipal principal, long skuId, String idempotencyKey,
                                     StockAdjustmentRequest request) {
        return adjustStockInternal(principal, skuId, idempotencyKey, request.quantityDelta(), request.changeType(),
                request.referenceNo(), request.remark());
    }

    @Transactional
    public List<StockResponse> batchAdjustStock(AuthenticatedPrincipal principal, String idempotencyKey,
                                                BatchStockRequest request) {
        requireKey(idempotencyKey);
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "批量库存操作每次需要1—200条");
        }
        List<StockResponse> result = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            BatchStockItem item = request.items().get(i);
            if (item == null || item.skuId() == null) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "批量库存SKU不能为空");
            result.add(adjustStockInternal(principal, item.skuId(), idempotencyKey.trim() + ":" + i,
                    item.quantityDelta(), item.changeType(), item.referenceNo(), item.remark()));
        }
        return result;
    }

    private StockResponse adjustStockInternal(AuthenticatedPrincipal principal, long skuId, String key,
                                              Integer deltaValue, String typeValue, String referenceNo, String remark) {
        requireKey(key);
        if (deltaValue == null || deltaValue == 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "库存变更数量不能为0");
        String type = normalizeStockType(typeValue);
        if ((Set.of("INBOUND", "RETURN").contains(type) && deltaValue < 0)
                || (Set.of("OUTBOUND", "DAMAGE").contains(type) && deltaValue > 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "库存变更类型与数量正负方向不一致");
        }
        List<Long> duplicate = jdbcTemplate.queryForList("SELECT sku_id FROM bf_cultural_stock_log WHERE idempotency_key=?", Long.class, key.trim());
        if (!duplicate.isEmpty()) {
            if (duplicate.get(0) != skuId) throw new BusinessException(CommonErrorCode.CONFLICT, "幂等键已用于其他SKU");
            return findStock(skuId);
        }
        requireCulturalSku(skuId);
        StockLocked stock = jdbcTemplate.queryForObject("""
                SELECT on_hand_quantity,locked_quantity,available_quantity FROM bf_cultural_stock
                WHERE sku_id=? FOR UPDATE
                """, (rs, n) -> new StockLocked(rs.getInt(1), rs.getInt(2), rs.getInt(3)), skuId);
        int onHandAfter = stock.onHand() + deltaValue;
        int availableAfter = stock.available() + deltaValue;
        if (onHandAfter < stock.locked() || availableAfter < 0) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "可售库存不足，不能完成出库");
        }
        long operatorId = ensureOperator(principal);
        jdbcTemplate.update("""
                UPDATE bf_cultural_stock SET on_hand_quantity=?,available_quantity=?,version=version+1 WHERE sku_id=?
                """, onHandAfter, availableAfter, skuId);
        jdbcTemplate.update("""
                INSERT INTO bf_cultural_stock_log
                (sku_id,change_type,quantity_delta,on_hand_after,available_after,idempotency_key,operator_id,reference_no,remark)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, skuId, type, deltaValue, onHandAfter, availableAfter, key.trim(), operatorId,
                nullableText(referenceNo, 64), nullableText(remark, 255));
        audit(operatorId, "CULTURAL_STOCK_" + type, "CULTURAL_SKU", skuId, String.valueOf(deltaValue));
        return findStock(skuId);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockLogResponse> stockLogs(Long skuId, int page, int pageSize) {
        validatePage(page, pageSize);
        String where = skuId == null ? "" : " WHERE l.sku_id=?";
        Object[] args = skuId == null ? new Object[]{} : new Object[]{skuId};
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bf_cultural_stock_log l" + where, Long.class, args);
        List<Object> pageArgs = new ArrayList<>(List.of(args)); pageArgs.add(pageSize); pageArgs.add((page - 1) * pageSize);
        List<StockLogResponse> items = jdbcTemplate.query("""
                SELECT l.id,l.sku_id,sku.sku_code,l.change_type,l.quantity_delta,l.on_hand_after,l.available_after,
                       l.reference_no,l.remark,l.operator_id,l.created_at
                FROM bf_cultural_stock_log l JOIN bf_resource_sku sku ON sku.id=l.sku_id
                """ + where + " ORDER BY l.id DESC LIMIT ? OFFSET ?", (rs, n) -> new StockLogResponse(
                rs.getLong("id"), rs.getLong("sku_id"), rs.getString("sku_code"), rs.getString("change_type"),
                rs.getInt("quantity_delta"), rs.getInt("on_hand_after"), rs.getInt("available_after"),
                rs.getString("reference_no"), rs.getString("remark"), rs.getObject("operator_id", Long.class),
                rs.getObject("created_at", java.time.LocalDateTime.class)), pageArgs.toArray());
        return new PageResponse<>(items, page, pageSize, total, total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
    }

    private ProductHead requireProduct(long id, boolean publicOnly) {
        String active = publicOnly ? " AND r.status='ACTIVE' AND c.status='ACTIVE'" : "";
        List<ProductHead> rows = jdbcTemplate.query("""
                SELECT r.id,p.product_code,r.name,p.category_id,c.name category_name,r.description,r.cover_url,
                       p.detail_html,p.market_price_cent,p.weight_gram,p.sales_count,r.status
                FROM bf_resource r JOIN bf_cultural_product_profile p ON p.resource_id=r.id
                JOIN bf_cultural_category c ON c.id=p.category_id
                WHERE r.id=? AND r.resource_type=?
                """ + active, (rs, n) -> new ProductHead(rs.getLong("id"), rs.getString("product_code"),
                rs.getString("name"), rs.getLong("category_id"), rs.getString("category_name"),
                rs.getString("description"), rs.getString("cover_url"), rs.getString("detail_html"),
                rs.getObject("market_price_cent", Long.class), rs.getObject("weight_gram", Integer.class),
                rs.getLong("sales_count"), rs.getString("status")), id, RESOURCE_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, publicOnly ? "商品不存在或未上架" : "文创商品不存在");
        return rows.get(0);
    }

    private void requireCulturalSku(long skuId) {
        if (jdbcTemplate.queryForList("""
                SELECT sku.id FROM bf_resource_sku sku JOIN bf_resource r ON r.id=sku.resource_id
                WHERE sku.id=? AND r.resource_type=?
                """, Long.class, skuId, RESOURCE_TYPE).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创商品SKU不存在");
        }
    }

    private SkuResponse findSku(long skuId) {
        List<SkuResponse> rows = jdbcTemplate.query(skuSelect() + " WHERE sku.id=?", skuMapper(), skuId);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创商品SKU不存在");
        return rows.get(0);
    }

    private StockResponse findStock(long skuId) {
        List<StockResponse> rows = jdbcTemplate.query(stockSelect() + " WHERE sku.id=? AND r.resource_type=?",
                stockMapper(), skuId, RESOURCE_TYPE);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "文创商品库存不存在");
        return rows.get(0);
    }

    private String skuSelect() {
        return """
                SELECT sku.id,sku.sku_code,sku.name,sku.price_cent,sku.status,CAST(sku.attributes AS CHAR) attributes,
                       st.on_hand_quantity,st.locked_quantity,st.available_quantity,st.warning_threshold
                FROM bf_resource_sku sku JOIN bf_cultural_stock st ON st.sku_id=sku.id
                """;
    }
    private org.springframework.jdbc.core.RowMapper<SkuResponse> skuMapper() {
        return (rs, n) -> new SkuResponse(rs.getLong("id"), rs.getString("sku_code"), rs.getString("name"),
                rs.getLong("price_cent"), rs.getString("status"), rs.getString("attributes"),
                rs.getInt("on_hand_quantity"), rs.getInt("locked_quantity"), rs.getInt("available_quantity"),
                rs.getInt("warning_threshold"), rs.getInt("available_quantity") <= rs.getInt("warning_threshold"));
    }
    private String stockFrom() { return " FROM bf_cultural_stock st JOIN bf_resource_sku sku ON sku.id=st.sku_id JOIN bf_resource r ON r.id=sku.resource_id"; }
    private String stockSelect() { return "SELECT sku.id,sku.sku_code,r.name product_name,sku.name sku_name,st.on_hand_quantity,st.locked_quantity,st.available_quantity,st.warning_threshold,st.version,st.updated_at" + stockFrom(); }
    private org.springframework.jdbc.core.RowMapper<StockResponse> stockMapper() {
        return (rs, n) -> new StockResponse(rs.getLong("id"), rs.getString("sku_code"), rs.getString("product_name"),
                rs.getString("sku_name"), rs.getInt("on_hand_quantity"), rs.getInt("locked_quantity"),
                rs.getInt("available_quantity"), rs.getInt("warning_threshold"),
                rs.getInt("available_quantity") <= rs.getInt("warning_threshold"), rs.getInt("version"),
                rs.getObject("updated_at", java.time.LocalDateTime.class));
    }

    private ProductValues validateProduct(ProductUpsertRequest request, boolean creating) {
        if (request == null || !StringUtils.hasText(request.productCode()) || !StringUtils.hasText(request.name()) || request.categoryId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "商品编码、名称和分类不能为空");
        }
        CategoryResponse category = requireCategory(request.categoryId());
        if (request.marketPriceCent() != null && request.marketPriceCent() < 0 || request.weightGram() != null && request.weightGram() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "市场价和重量不能为负数");
        }
        return new ProductValues(request.productCode().trim().toUpperCase(Locale.ROOT), request.name().trim(),
                request.categoryId(), category.categoryCode(), nullableText(request.description(), 65535),
                nullableText(request.coverUrl(), 512), request.detailHtml(), request.marketPriceCent(),
                request.weightGram(), normalizeStatus(request.status()));
    }
    private SkuValues validateSkuCreate(SkuCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.skuCode()) || !StringUtils.hasText(request.name())
                || request.priceCent() == null || request.priceCent() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "SKU编码、名称和非负价格不能为空");
        }
        int stock = request.initialStock() == null ? 0 : request.initialStock();
        int threshold = request.warningThreshold() == null ? 10 : request.warningThreshold();
        if (stock < 0 || threshold < 0) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "初始库存和预警阈值不能为负数");
        return new SkuValues(request.skuCode().trim().toUpperCase(Locale.ROOT), request.name().trim(),
                request.priceCent(), normalizeStatus(request.status()), jsonOrEmpty(request.attributes()), stock, threshold);
    }
    private CategoryValues validateCategory(CategoryUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.categoryCode()) || !StringUtils.hasText(request.name())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "分类编码和名称不能为空");
        }
        return new CategoryValues(request.categoryCode().trim().toUpperCase(Locale.ROOT), request.name().trim(),
                request.parentId(), request.sortOrder() == null ? 0 : request.sortOrder(), normalizeStatus(request.status()));
    }
    private CategoryResponse requireCategory(long id) {
        List<CategoryResponse> rows = jdbcTemplate.query("""
                SELECT c.id,c.category_code,c.name,c.parent_id,c.sort_order,c.status,
                       (SELECT COUNT(*) FROM bf_cultural_product_profile p WHERE p.category_id=c.id) product_count
                FROM bf_cultural_category c WHERE c.id=?
                """, (rs, n) -> new CategoryResponse(rs.getLong("id"), rs.getString("category_code"), rs.getString("name"),
                rs.getObject("parent_id", Long.class), rs.getInt("sort_order"), rs.getString("status"),
                rs.getLong("product_count")), id);
        if (rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND, "商品分类不存在");
        return rows.get(0);
    }
    private CategoryResponse findCategory(long id) { return requireCategory(id); }
    private void validateParent(Long parentId, Long currentId) {
        if (parentId == null) return;
        if (parentId.equals(currentId)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "分类不能作为自己的上级");
        requireCategory(parentId);
    }
    private void requireUniqueCategoryCode(String code, Long excludeId) {
        List<Long> rows = excludeId == null
                ? jdbcTemplate.queryForList("SELECT id FROM bf_cultural_category WHERE category_code=?", Long.class, code)
                : jdbcTemplate.queryForList("SELECT id FROM bf_cultural_category WHERE category_code=? AND id<>?", Long.class, code, excludeId);
        if (!rows.isEmpty()) throw new BusinessException(CommonErrorCode.CONFLICT, "商品分类编码已存在");
    }
    private void requireUniqueProductCode(String code, Long excludeId) {
        List<Long> rows = excludeId == null
                ? jdbcTemplate.queryForList("SELECT resource_id FROM bf_cultural_product_profile WHERE product_code=?", Long.class, code)
                : jdbcTemplate.queryForList("SELECT resource_id FROM bf_cultural_product_profile WHERE product_code=? AND resource_id<>?", Long.class, code, excludeId);
        if (!rows.isEmpty()) throw new BusinessException(CommonErrorCode.CONFLICT, "商品编码已存在");
    }
    private void replaceImages(long resourceId, List<ImageInput> images) {
        jdbcTemplate.update("DELETE FROM bf_resource_image WHERE resource_id=?", resourceId);
        if (images == null) return;
        if (images.size() > 20) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "每个商品最多20张图片");
        for (ImageInput image : images) {
            if (image == null || !StringUtils.hasText(image.imageUrl())) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "商品图片地址不能为空");
            String type = StringUtils.hasText(image.imageType()) ? image.imageType().trim().toUpperCase(Locale.ROOT) : "DETAIL";
            if (!Set.of("COVER", "GALLERY", "DETAIL").contains(type)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "商品图片类型不合法");
            jdbcTemplate.update("INSERT INTO bf_resource_image(resource_id,image_url,image_type,sort_order) VALUES (?,?,?,?)",
                    resourceId, image.imageUrl().trim(), type, image.sortOrder() == null ? 0 : image.sortOrder());
        }
    }
    private String normalizeStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "DRAFT";
        if (!STATUSES.contains(value)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "状态不合法");
        return value;
    }
    private String normalizeStockType(String type) {
        String value = StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : "ADJUSTMENT";
        if (!STOCK_TYPES.contains(value)) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "库存变更类型不合法");
        return value;
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
        return jdbcTemplate.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?", Long.class, principal.databaseOpenId());
    }
    private void audit(long operatorId, String action, String targetType, long targetId, String detail) {
        jdbcTemplate.update("""
                INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail)
                VALUES (?,?,?,?,JSON_OBJECT('value',?))
                """, operatorId, action, targetType, String.valueOf(targetId), detail);
    }
    private long generatedId(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) throw new BusinessException(CommonErrorCode.INTERNAL_ERROR, "数据创建失败");
        return key.longValue();
    }
    private String jsonOrEmpty(String value) { return StringUtils.hasText(value) ? value.trim() : "{}"; }
    private String nullableText(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim();
        if (text.length() > max) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "文本长度超过限制");
        return text;
    }

    private record CategoryValues(String code, String name, Long parentId, int sortOrder, String status) {}
    private record ProductValues(String productCode, String name, long categoryId, String categoryCode,
                                 String description, String coverUrl, String detailHtml, Long marketPriceCent,
                                 Integer weightGram, String status) {}
    private record SkuValues(String skuCode, String name, long priceCent, String status, String attributes,
                             int initialStock, int warningThreshold) {}
    private record ProductHead(long id, String productCode, String name, long categoryId, String categoryName,
                               String description, String coverUrl, String detailHtml, Long marketPriceCent,
                               Integer weightGram, long salesCount, String status) {}
    private record StockLocked(int onHand, int locked, int available) {}
}
