package com.iminiapp.backend.cultural.application;

import com.iminiapp.backend.cultural.api.CulturalReportModels.*;
import com.iminiapp.backend.shared.api.PageResponse;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CulturalReportService {
    private final JdbcTemplate jdbc;
    public CulturalReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(LocalDate startDate, LocalDate endDate) {
        return new DashboardResponse(overview(startDate, endDate), salesTrend(startDate, endDate),
                topProducts(startDate, endDate, 10), afterSaleSummary(startDate, endDate));
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview(LocalDate startDate, LocalDate endDate) {
        Range r = range(startDate, endDate);
        long created = value("SELECT COUNT(*) FROM bf_order WHERE order_type='CULTURAL_PRODUCT' AND created_at>=? AND created_at<?", r);
        long paid = value(paidOrderBase("COUNT(*)"), r);
        long completed = value("SELECT COUNT(*) FROM bf_order WHERE order_type='CULTURAL_PRODUCT' AND completed_at>=? AND completed_at<?", r);
        long soldQuantity = value("""
                SELECT COALESCE(SUM(i.quantity),0) FROM bf_order_item i JOIN bf_order o ON o.id=i.order_id
                WHERE o.order_type='CULTURAL_PRODUCT' AND EXISTS (SELECT 1 FROM bf_payment p WHERE p.order_id=o.id
                AND p.status='SUCCESS' AND p.paid_at>=? AND p.paid_at<?)
                """, r);
        long gross = value(paidOrderBase("COALESCE(SUM(o.paid_amount_cent),0)"), r);
        long discount = value(paidOrderBase("COALESCE(SUM(o.discount_amount_cent),0)"), r);
        long refundOrders = value(refundBase("COUNT(*)"), r);
        long refundAmount = value(refundBase("COALESCE(SUM(f.amount_cent),0)"), r);
        long afterSales = value("SELECT COUNT(*) FROM bf_cultural_after_sale WHERE created_at>=? AND created_at<?", r);
        long pendingAfterSales = single("SELECT COUNT(*) FROM bf_cultural_after_sale WHERE status IN ('PENDING_REVIEW','WAITING_RETURN','RETURNED','REFUNDING')");
        long pendingShipments = single("SELECT COUNT(*) FROM bf_order WHERE order_type='CULTURAL_PRODUCT' AND status IN ('PAID','PREPARING')");
        long lowStock = single("SELECT COUNT(*) FROM bf_cultural_stock WHERE available_quantity<=warning_threshold");
        long availableQuantity = single("SELECT COALESCE(SUM(available_quantity),0) FROM bf_cultural_stock");
        long availableValue = single("SELECT COALESCE(SUM(s.available_quantity*k.price_cent),0) FROM bf_cultural_stock s JOIN bf_resource_sku k ON k.id=s.sku_id");
        BigDecimal refundRate = paid == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(refundOrders)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(paid), 2, RoundingMode.HALF_UP);
        return new OverviewResponse(r.startDate(), r.endDate(), created, paid, completed, soldQuantity,
                gross, discount, refundOrders, refundAmount, gross - refundAmount, refundRate,
                afterSales, pendingAfterSales, pendingShipments, lowStock, availableQuantity, availableValue);
    }

    @Transactional(readOnly = true)
    public List<SalesTrendItem> salesTrend(LocalDate startDate, LocalDate endDate) {
        Range r = range(startDate, endDate);
        Map<LocalDate, SalesDaily> sales = new HashMap<>();
        jdbc.query("""
                SELECT DATE(p.paid_at) report_date,COUNT(*) order_count,
                       COALESCE(SUM(items.sold_quantity),0) sold_quantity,
                       COALESCE(SUM(o.paid_amount_cent),0) item_amount
                FROM bf_payment p JOIN bf_order o ON o.id=p.order_id
                JOIN (SELECT order_id,SUM(quantity) sold_quantity FROM bf_order_item GROUP BY order_id) items ON items.order_id=o.id
                WHERE p.status='SUCCESS' AND o.order_type='CULTURAL_PRODUCT' AND p.paid_at>=? AND p.paid_at<?
                GROUP BY DATE(p.paid_at)
                """, rs -> { sales.put(rs.getObject("report_date", LocalDate.class), new SalesDaily(
                rs.getLong("order_count"), rs.getLong("sold_quantity"), rs.getLong("item_amount"))); }, r.start(), r.endExclusive());
        Map<LocalDate, Long> refunds = new HashMap<>();
        jdbc.query("""
                SELECT DATE(f.refunded_at) report_date,COALESCE(SUM(f.amount_cent),0) refund_amount
                FROM bf_refund f JOIN bf_order o ON o.id=f.order_id
                WHERE f.status='SUCCESS' AND o.order_type='CULTURAL_PRODUCT' AND f.refunded_at>=? AND f.refunded_at<?
                GROUP BY DATE(f.refunded_at)
                """, rs -> { refunds.put(rs.getObject("report_date", LocalDate.class), rs.getLong("refund_amount")); }, r.start(), r.endExclusive());
        List<SalesTrendItem> result = new ArrayList<>();
        for (LocalDate date = r.startDate(); !date.isAfter(r.endDate()); date = date.plusDays(1)) {
            SalesDaily s = sales.getOrDefault(date, new SalesDaily(0,0,0)); long refund = refunds.getOrDefault(date, 0L);
            result.add(new SalesTrendItem(date, s.orders(), s.quantity(), s.amount(), refund, s.amount() - refund));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ProductRankingItem> topProducts(LocalDate startDate, LocalDate endDate, int limit) {
        Range r = range(startDate, endDate);
        if (limit < 1 || limit > 100) throw invalid("limit范围为1—100");
        return jdbc.query("""
                SELECT res.id,profile.product_code,res.name,COUNT(DISTINCT i.sku_id) sku_count,
                       COALESCE(SUM(i.quantity),0) sold_quantity,COALESCE(SUM(i.amount_cent),0) sales_amount,
                       COUNT(DISTINCT CASE WHEN EXISTS (SELECT 1 FROM bf_refund f WHERE f.order_id=o.id
                           AND f.status='SUCCESS' AND f.refunded_at>=? AND f.refunded_at<?) THEN o.id END) refunded_orders
                FROM bf_order_item i JOIN bf_order o ON o.id=i.order_id
                JOIN bf_resource_sku sku ON sku.id=i.sku_id JOIN bf_resource res ON res.id=sku.resource_id
                JOIN bf_cultural_product_profile profile ON profile.resource_id=res.id
                WHERE o.order_type='CULTURAL_PRODUCT' AND EXISTS (SELECT 1 FROM bf_payment p WHERE p.order_id=o.id
                    AND p.status='SUCCESS' AND p.paid_at>=? AND p.paid_at<?)
                GROUP BY res.id,profile.product_code,res.name ORDER BY sold_quantity DESC,sales_amount DESC,res.id LIMIT ?
                """, (rs,n)->new ProductRankingItem(rs.getLong("id"),rs.getString("product_code"),rs.getString("name"),
                rs.getLong("sku_count"),rs.getLong("sold_quantity"),rs.getLong("sales_amount"),rs.getLong("refunded_orders")),
                r.start(),r.endExclusive(),r.start(),r.endExclusive(),limit);
    }

    @Transactional(readOnly = true)
    public AfterSaleSummary afterSaleSummary(LocalDate startDate, LocalDate endDate) {
        Range r = range(startDate, endDate);
        return jdbc.queryForObject("""
                SELECT COUNT(*) total,
                SUM(after_sale_type='REFUND_ONLY') refund_only,SUM(after_sale_type='RETURN_REFUND') return_refund,
                SUM(status='PENDING_REVIEW') pending_review,SUM(status='WAITING_RETURN') waiting_return,
                SUM(status='RETURNED') returned_count,SUM(status='REFUNDING') refunding,
                SUM(status='COMPLETED') completed,SUM(status='REJECTED') rejected,SUM(status='CANCELLED') cancelled,
                COALESCE(SUM(CASE WHEN status='COMPLETED' THEN refund_amount_cent ELSE 0 END),0) completed_amount
                FROM bf_cultural_after_sale WHERE created_at>=? AND created_at<?
                """, (rs,n)->new AfterSaleSummary(rs.getLong("total"),rs.getLong("refund_only"),rs.getLong("return_refund"),
                rs.getLong("pending_review"),rs.getLong("waiting_return"),rs.getLong("returned_count"),
                rs.getLong("refunding"),rs.getLong("completed"),rs.getLong("rejected"),rs.getLong("cancelled"),
                rs.getLong("completed_amount")),r.start(),r.endExclusive());
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryReportItem> inventory(String keyword, Boolean lowStockOnly, int page, int pageSize) {
        validatePage(page,pageSize); List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(Boolean.TRUE.equals(lowStockOnly))where.append(" AND s.available_quantity<=s.warning_threshold");
        if(StringUtils.hasText(keyword)){where.append(" AND (k.sku_code LIKE ? OR k.name LIKE ? OR r.name LIKE ?)");String p="%"+keyword.trim()+"%";args.add(p);args.add(p);args.add(p);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM bf_cultural_stock s JOIN bf_resource_sku k ON k.id=s.sku_id JOIN bf_resource r ON r.id=k.resource_id"+where,Long.class,args.toArray());
        List<Object> queryArgs=new ArrayList<>(args);queryArgs.add(pageSize);queryArgs.add((page-1)*pageSize);
        List<InventoryReportItem> items=jdbc.query("""
                SELECT s.sku_id,k.sku_code,r.name product_name,k.name sku_name,k.price_cent,s.on_hand_quantity,
                s.locked_quantity,s.available_quantity,s.warning_threshold,s.updated_at
                FROM bf_cultural_stock s JOIN bf_resource_sku k ON k.id=s.sku_id JOIN bf_resource r ON r.id=k.resource_id
                """+where+" ORDER BY (s.available_quantity<=s.warning_threshold) DESC,s.available_quantity,s.sku_id LIMIT ? OFFSET ?",
                (rs,n)->new InventoryReportItem(rs.getLong("sku_id"),rs.getString("sku_code"),rs.getString("product_name"),
                rs.getString("sku_name"),rs.getLong("price_cent"),rs.getInt("on_hand_quantity"),rs.getInt("locked_quantity"),
                rs.getInt("available_quantity"),rs.getInt("warning_threshold"),rs.getInt("available_quantity")<=rs.getInt("warning_threshold"),
                rs.getLong("price_cent")*rs.getInt("available_quantity"),rs.getObject("updated_at",LocalDateTime.class)),queryArgs.toArray());
        return new PageResponse<>(items,page,pageSize,total,total==0?0:(int)((total+pageSize-1)/pageSize));
    }

    @Transactional(readOnly = true)
    public List<StockMovementItem> stockMovements(LocalDate startDate, LocalDate endDate) {
        Range r=range(startDate,endDate);Map<LocalDate,Movement> map=new HashMap<>();
        jdbc.query("""
                SELECT DATE(created_at) report_date,
                COALESCE(SUM(CASE WHEN change_type='INBOUND' THEN quantity_delta ELSE 0 END),0) inbound_qty,
                COALESCE(SUM(CASE WHEN change_type IN ('OUTBOUND','DAMAGE') THEN -quantity_delta ELSE 0 END),0) outbound_qty,
                COALESCE(SUM(CASE WHEN change_type='SALE_DEDUCT' THEN -quantity_delta ELSE 0 END),0) sale_qty,
                COALESCE(SUM(CASE WHEN change_type='RETURN' THEN quantity_delta ELSE 0 END),0) return_qty,
                COALESCE(SUM(CASE WHEN change_type='ADJUSTMENT' THEN quantity_delta ELSE 0 END),0) adjustment_qty,
                COALESCE(SUM(quantity_delta),0) net_qty FROM bf_cultural_stock_log
                WHERE created_at>=? AND created_at<? GROUP BY DATE(created_at)
                """,rs->{ map.put(rs.getObject("report_date",LocalDate.class),new Movement(rs.getLong("inbound_qty"),
                rs.getLong("outbound_qty"),rs.getLong("sale_qty"),rs.getLong("return_qty"),
                rs.getLong("adjustment_qty"),rs.getLong("net_qty"))); },r.start(),r.endExclusive());
        List<StockMovementItem> result=new ArrayList<>();for(LocalDate d=r.startDate();!d.isAfter(r.endDate());d=d.plusDays(1)){Movement m=map.getOrDefault(d,new Movement(0,0,0,0,0,0));result.add(new StockMovementItem(d,m.inbound(),m.outbound(),m.sale(),m.returned(),m.adjustment(),m.net()));}return result;
    }

    @Transactional(readOnly = true)
    public byte[] salesCsv(LocalDate startDate, LocalDate endDate) {
        Range r=range(startDate,endDate);StringBuilder out=new StringBuilder("\uFEFF订单号,支付时间,订单状态,商品数量,商品原价分,优惠分,实付分,退款状态,退款金额分,收货人,物流公司,物流单号\r\n");
        jdbc.query("""
                SELECT o.order_no,p.paid_at,o.status,(SELECT SUM(i.quantity) FROM bf_order_item i WHERE i.order_id=o.id) quantity,
                o.total_amount_cent,o.discount_amount_cent,o.paid_amount_cent,COALESCE(f.status,''),COALESCE(f.amount_cent,0),
                d.receiver_name,COALESCE(s.carrier_name,''),COALESCE(s.tracking_no,'')
                FROM bf_payment p JOIN bf_order o ON o.id=p.order_id JOIN bf_cultural_order_delivery d ON d.order_id=o.id
                LEFT JOIN bf_refund f ON f.order_id=o.id AND f.status='SUCCESS' LEFT JOIN bf_shipment s ON s.order_id=o.id
                WHERE p.status='SUCCESS' AND o.order_type='CULTURAL_PRODUCT' AND p.paid_at>=? AND p.paid_at<? ORDER BY p.paid_at,o.id
                """,rs->{out.append(cell(rs.getString(1))).append(',').append(cell(String.valueOf(rs.getObject(2,LocalDateTime.class)))).append(',')
                .append(cell(rs.getString(3))).append(',').append(rs.getLong(4)).append(',').append(rs.getLong(5)).append(',')
                .append(rs.getLong(6)).append(',').append(rs.getLong(7)).append(',').append(cell(rs.getString(8))).append(',')
                .append(rs.getLong(9)).append(',').append(cell(rs.getString(10))).append(',').append(cell(rs.getString(11))).append(',')
                .append(cell(rs.getString(12))).append("\r\n");},r.start(),r.endExclusive());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String paidOrderBase(String expression){return "SELECT "+expression+" FROM bf_order o WHERE o.order_type='CULTURAL_PRODUCT' AND EXISTS (SELECT 1 FROM bf_payment p WHERE p.order_id=o.id AND p.status='SUCCESS' AND p.paid_at>=? AND p.paid_at<?)";}
    private String refundBase(String expression){return "SELECT "+expression+" FROM bf_refund f JOIN bf_order o ON o.id=f.order_id WHERE o.order_type='CULTURAL_PRODUCT' AND f.status='SUCCESS' AND f.refunded_at>=? AND f.refunded_at<?";}
    private long value(String sql,Range r){Long v=jdbc.queryForObject(sql,Long.class,r.start(),r.endExclusive());return v==null?0:v;}
    private long single(String sql){Long v=jdbc.queryForObject(sql,Long.class);return v==null?0:v;}
    private Range range(LocalDate start,LocalDate end){if(start==null||end==null||end.isBefore(start)||ChronoUnit.DAYS.between(start,end)>366)throw invalid("日期范围不正确且最长366天");return new Range(start,end,start.atStartOfDay(),end.plusDays(1).atStartOfDay());}
    private void validatePage(int page,int size){if(page<1||size<1||size>100)throw invalid("page从1开始，pageSize范围为1—100");}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private String cell(String value){String text=value==null?"":value;return "\""+text.replace("\"","\"\"")+"\"";}
    private record Range(LocalDate startDate,LocalDate endDate,LocalDateTime start,LocalDateTime endExclusive){}
    private record SalesDaily(long orders,long quantity,long amount){}
    private record Movement(long inbound,long outbound,long sale,long returned,long adjustment,long net){}
}
