package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class HotelManagementService {
    private static final Set<String> PUBLISH_STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final Set<String> ROOM_STATUSES = Set.of("VACANT", "RESERVED", "OCCUPIED", "DIRTY", "MAINTENANCE", "INACTIVE");
    private static final Set<String> RULE_TYPES = Set.of("HOLIDAY", "PEAK_SEASON", "LOW_SEASON", "WEEKEND", "MANUAL");
    private final JdbcTemplate jdbc;

    public HotelManagementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<HotelResponse> hotels(String status) {
        String normalized = optionalStatus(status, PUBLISH_STATUSES);
        String where = normalized == null ? "" : " WHERE h.status=?";
        Object[] args = normalized == null ? new Object[0] : new Object[]{normalized};
        return jdbc.query("""
                SELECT h.*,COUNT(DISTINCT rt.id) room_type_count,COUNT(DISTINCT room.id) room_count
                FROM bf_hotel h LEFT JOIN bf_hotel_room_type rt ON rt.hotel_id=h.id
                LEFT JOIN bf_hotel_room room ON room.hotel_id=h.id
                """ + where + " GROUP BY h.id ORDER BY h.id DESC", (rs, n) -> new HotelResponse(
                rs.getLong("id"), rs.getString("hotel_code"), rs.getString("name"), rs.getString("address"),
                rs.getString("contact_phone"), rs.getString("description"), rs.getObject("check_in_time", LocalTime.class),
                rs.getObject("check_out_time", LocalTime.class), rs.getString("status"), rs.getLong("room_type_count"),
                rs.getLong("room_count"), images(rs.getLong("id"), null, false),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class)), args);
    }

    @Transactional
    public HotelResponse createHotel(AuthenticatedPrincipal principal, HotelUpsertRequest request) {
        HotelValues value = validateHotel(request);
        jdbc.update("""
                INSERT INTO bf_hotel(hotel_code,name,address,contact_phone,description,check_in_time,check_out_time,status)
                VALUES (?,?,?,?,?,?,?,?)
                """, value.code(), value.name(), value.address(), value.phone(), value.description(),
                value.checkIn(), value.checkOut(), value.status());
        long id = lastId();
        replaceImages(id, null, request.images());
        audit(principal, "HOTEL_CREATE", "HOTEL", id, value.status());
        return hotel(id);
    }

    @Transactional
    public HotelResponse updateHotel(AuthenticatedPrincipal principal, long id, HotelUpsertRequest request) {
        HotelValues value = validateHotel(request);
        int changed = jdbc.update("""
                UPDATE bf_hotel SET hotel_code=?,name=?,address=?,contact_phone=?,description=?,check_in_time=?,
                check_out_time=?,status=? WHERE id=?
                """, value.code(), value.name(), value.address(), value.phone(), value.description(),
                value.checkIn(), value.checkOut(), value.status(), id);
        if (changed == 0) throw notFound("酒店不存在");
        replaceImages(id, null, request.images());
        audit(principal, "HOTEL_UPDATE", "HOTEL", id, value.status());
        return hotel(id);
    }

    public List<RoomTypeResponse> roomTypes(Long hotelId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (hotelId != null) { where.append(" AND rt.hotel_id=?"); args.add(hotelId); }
        String normalized = optionalStatus(status, PUBLISH_STATUSES);
        if (normalized != null) { where.append(" AND rt.status=?"); args.add(normalized); }
        return jdbc.query(roomTypeSelect() + where + " GROUP BY rt.id ORDER BY rt.id DESC", roomTypeMapper(), args.toArray());
    }

    @Transactional
    public RoomTypeResponse createRoomType(AuthenticatedPrincipal principal, RoomTypeUpsertRequest request) {
        RoomTypeValues value = validateRoomType(request, null);
        HotelIdentity hotel = hotelIdentity(value.hotelId());
        jdbc.update("""
                INSERT INTO bf_resource(site_id,resource_type,category_code,name,description,cover_url,status,attributes)
                VALUES (NULL,'HOTEL_ROOM','HOTEL',?,?,?,?,JSON_OBJECT('hotelId',?,'hotelCode',?,'roomTypeCode',?))
                """, value.name(), value.description(), value.coverUrl(), value.status(), hotel.id(), hotel.code(), value.code());
        long resourceId = lastId();
        String skuCode = "HOTEL_" + hotel.code() + "_" + value.code();
        jdbc.update("""
                INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
                VALUES (?,?,?,?,?,JSON_OBJECT('hotelId',?,'roomTypeCode',?))
                """, resourceId, skuCode, value.name(), value.basePrice(), value.status(), hotel.id(), value.code());
        long skuId = lastId();
        jdbc.update("""
                INSERT INTO bf_hotel_room_type
                (hotel_id,resource_id,sku_id,type_code,name,bed_type,area_sqm,max_guests,total_rooms,
                 low_stock_threshold,base_price_cent,description,cover_url,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.hotelId(), resourceId, skuId, value.code(), value.name(), value.bedType(), value.area(),
                value.maxGuests(), value.totalRooms(), value.lowStock(), value.basePrice(), value.description(),
                value.coverUrl(), value.status());
        long id = lastId();
        replaceFacilities(id, request.facilities());
        replaceImages(null, id, request.images());
        audit(principal, "HOTEL_ROOM_TYPE_CREATE", "HOTEL_ROOM_TYPE", id, value.status());
        return roomType(id);
    }

    @Transactional
    public RoomTypeResponse updateRoomType(AuthenticatedPrincipal principal, long id, RoomTypeUpsertRequest request) {
        RoomTypeIdentity current = roomTypeIdentity(id);
        RoomTypeValues value = validateRoomType(request, id);
        if (value.hotelId() != current.hotelId()) throw invalid("房型创建后不能更换所属酒店");
        HotelIdentity hotel = hotelIdentity(value.hotelId());
        String skuCode = "HOTEL_" + hotel.code() + "_" + value.code();
        jdbc.update("""
                UPDATE bf_hotel_room_type SET type_code=?,name=?,bed_type=?,area_sqm=?,max_guests=?,total_rooms=?,
                low_stock_threshold=?,base_price_cent=?,description=?,cover_url=?,status=? WHERE id=?
                """, value.code(), value.name(), value.bedType(), value.area(), value.maxGuests(), value.totalRooms(),
                value.lowStock(), value.basePrice(), value.description(), value.coverUrl(), value.status(), id);
        jdbc.update("""
                UPDATE bf_resource SET name=?,description=?,cover_url=?,status=?,
                attributes=JSON_OBJECT('hotelId',?,'hotelCode',?,'roomTypeCode',?) WHERE id=?
                """, value.name(), value.description(), value.coverUrl(), value.status(), hotel.id(), hotel.code(),
                value.code(), current.resourceId());
        jdbc.update("""
                UPDATE bf_resource_sku SET sku_code=?,name=?,price_cent=?,status=?,
                attributes=JSON_OBJECT('hotelId',?,'roomTypeCode',?) WHERE id=?
                """, skuCode, value.name(), value.basePrice(), value.status(), hotel.id(), value.code(), current.skuId());
        replaceFacilities(id, request.facilities());
        replaceImages(null, id, request.images());
        audit(principal, "HOTEL_ROOM_TYPE_UPDATE", "HOTEL_ROOM_TYPE", id, value.status());
        return roomType(id);
    }

    public List<RoomResponse> rooms(Long hotelId, Long roomTypeId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (hotelId != null) { where.append(" AND room.hotel_id=?"); args.add(hotelId); }
        if (roomTypeId != null) { where.append(" AND room.room_type_id=?"); args.add(roomTypeId); }
        String normalized = optionalStatus(status, ROOM_STATUSES);
        if (normalized != null) { where.append(" AND room.status=?"); args.add(normalized); }
        return jdbc.query(roomSelect() + where + " ORDER BY h.id,room.room_no", roomMapper(), args.toArray());
    }

    @Transactional
    public RoomResponse createRoom(AuthenticatedPrincipal principal, RoomUpsertRequest request) {
        RoomValues value = validateRoom(request, null);
        jdbc.update("INSERT INTO bf_hotel_room(hotel_id,room_type_id,room_no,floor_no,status,remark) VALUES (?,?,?,?,?,?)",
                value.hotelId(), value.roomTypeId(), value.roomNo(), value.floor(), value.status(), value.remark());
        long id = lastId();
        audit(principal, "HOTEL_ROOM_CREATE", "HOTEL_ROOM", id, value.status());
        return room(id);
    }

    @Transactional
    public RoomResponse updateRoom(AuthenticatedPrincipal principal, long id, RoomUpsertRequest request) {
        RoomValues value = validateRoom(request, id);
        int changed = jdbc.update("""
                UPDATE bf_hotel_room SET hotel_id=?,room_type_id=?,room_no=?,floor_no=?,status=?,remark=? WHERE id=?
                """, value.hotelId(), value.roomTypeId(), value.roomNo(), value.floor(), value.status(), value.remark(), id);
        if (changed == 0) throw notFound("房间不存在");
        audit(principal, "HOTEL_ROOM_UPDATE", "HOTEL_ROOM", id, value.status());
        return room(id);
    }

    public List<PriceRuleResponse> priceRules(Long roomTypeId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (roomTypeId != null) { where.append(" AND p.room_type_id=?"); args.add(roomTypeId); }
        String normalized = optionalStatus(status, PUBLISH_STATUSES);
        if (normalized != null) { where.append(" AND p.status=?"); args.add(normalized); }
        return jdbc.query(priceRuleSelect() + where + " ORDER BY p.priority DESC,p.id DESC", priceRuleMapper(), args.toArray());
    }

    @Transactional
    public PriceRuleResponse createPriceRule(AuthenticatedPrincipal principal, PriceRuleUpsertRequest request) {
        PriceRuleValues value = validatePriceRule(request);
        jdbc.update("""
                INSERT INTO bf_hotel_price_rule
                (room_type_id,rule_code,name,rule_type,start_date,end_date,days_of_week,price_cent,priority,status)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, value.roomTypeId(), value.code(), value.name(), value.type(), value.start(), value.end(),
                value.days(), value.price(), value.priority(), value.status());
        long id = lastId();
        audit(principal, "HOTEL_PRICE_RULE_CREATE", "HOTEL_PRICE_RULE", id, value.status());
        return priceRule(id);
    }

    @Transactional
    public PriceRuleResponse updatePriceRule(AuthenticatedPrincipal principal, long id, PriceRuleUpsertRequest request) {
        PriceRuleValues value = validatePriceRule(request);
        int changed = jdbc.update("""
                UPDATE bf_hotel_price_rule SET room_type_id=?,rule_code=?,name=?,rule_type=?,start_date=?,end_date=?,
                days_of_week=?,price_cent=?,priority=?,status=? WHERE id=?
                """, value.roomTypeId(), value.code(), value.name(), value.type(), value.start(), value.end(),
                value.days(), value.price(), value.priority(), value.status(), id);
        if (changed == 0) throw notFound("价格规则不存在");
        audit(principal, "HOTEL_PRICE_RULE_UPDATE", "HOTEL_PRICE_RULE", id, value.status());
        return priceRule(id);
    }

    @Transactional
    public CalendarGenerateResponse generateCalendar(AuthenticatedPrincipal principal, CalendarGenerateRequest request) {
        if (request == null || request.roomTypeId() == null || request.startDate() == null || request.endDate() == null) {
            throw invalid("roomTypeId、startDate、endDate不能为空");
        }
        validatePeriod(request.startDate(), request.endDate());
        RoomTypeInventoryIdentity roomType = inventoryIdentity(request.roomTypeId());
        int total = request.totalQuantity() == null ? roomType.totalRooms() : request.totalQuantity();
        if (total < 0 || total > roomType.totalRooms()) throw invalid("日库存必须在0到房型总房量之间");
        List<HotelPricingPolicy.PriceCandidate> rules = activePriceCandidates(request.roomTypeId());
        int generated = 0;
        int updated = 0;
        for (LocalDate day = request.startDate(); !day.isAfter(request.endDate()); day = day.plusDays(1)) {
            HotelPricingPolicy.ResolvedPrice price = HotelPricingPolicy.resolve(day, roomType.basePrice(), rules);
            List<InventoryState> existing = jdbc.query("""
                    SELECT id,total_quantity,available_quantity FROM bf_inventory
                    WHERE sku_id=? AND business_date=? AND time_slot='' FOR UPDATE
                    """, (rs, n) -> new InventoryState(rs.getLong(1), rs.getInt(2), rs.getInt(3)), roomType.skuId(), day);
            long inventoryId;
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO bf_inventory(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent)
                        VALUES (?,?,'',?,?,?)
                        """, roomType.skuId(), day, total, total, price.priceCent());
                inventoryId = lastId();
                generated++;
            } else {
                InventoryState state = existing.get(0);
                int occupied = state.total() - state.available();
                if (total < occupied) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                        day + " 已锁定或售出 " + occupied + " 间，不能把总库存调整为 " + total);
                jdbc.update("""
                        UPDATE bf_inventory SET total_quantity=?,available_quantity=?,price_cent=?,version=version+1 WHERE id=?
                        """, total, total - occupied, price.priceCent(), state.id());
                inventoryId = state.id();
                updated++;
            }
            saveInventorySource(inventoryId, price.ruleId(), price.sourceType());
        }
        audit(principal, "HOTEL_CALENDAR_GENERATE", "HOTEL_ROOM_TYPE", request.roomTypeId(),
                request.startDate() + ":" + request.endDate());
        return new CalendarGenerateResponse(request.roomTypeId(), request.startDate(), request.endDate(), generated, updated);
    }

    public List<InventoryDayResponse> calendar(long roomTypeId, LocalDate startDate, LocalDate endDate) {
        validatePeriod(startDate, endDate);
        roomTypeIdentity(roomTypeId);
        return jdbc.query("""
                SELECT i.id,rt.id room_type_id,i.business_date,i.total_quantity,i.available_quantity,i.price_cent,
                       COALESCE(src.source_type,'BASE') source_type,src.price_rule_id,p.name rule_name,
                       rt.low_stock_threshold
                FROM bf_hotel_room_type rt JOIN bf_inventory i ON i.sku_id=rt.sku_id
                LEFT JOIN bf_hotel_inventory_source src ON src.inventory_id=i.id
                LEFT JOIN bf_hotel_price_rule p ON p.id=src.price_rule_id
                WHERE rt.id=? AND i.time_slot='' AND i.business_date BETWEEN ? AND ? ORDER BY i.business_date
                """, inventoryMapper(), roomTypeId, startDate, endDate);
    }

    @Transactional
    public InventoryDayResponse updateInventoryDay(AuthenticatedPrincipal principal, long roomTypeId, LocalDate date,
                                                    InventoryDayUpsertRequest request) {
        if (date == null || request == null || request.totalQuantity() == null || request.availableQuantity() == null
                || request.priceCent() == null) throw invalid("日期、总库存、可用库存和价格不能为空");
        RoomTypeInventoryIdentity type = inventoryIdentity(roomTypeId);
        int total = request.totalQuantity();
        int available = request.availableQuantity();
        if (total < 0 || total > type.totalRooms() || available < 0 || available > total || request.priceCent() < 0) {
            throw invalid("库存或价格不合法，且总库存不能超过房型总房量");
        }
        List<InventoryState> rows = jdbc.query("""
                SELECT id,total_quantity,available_quantity FROM bf_inventory
                WHERE sku_id=? AND business_date=? AND time_slot='' FOR UPDATE
                """, (rs,n)->new InventoryState(rs.getLong(1),rs.getInt(2),rs.getInt(3)), type.skuId(), date);
        long inventoryId;
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO bf_inventory(sku_id,business_date,time_slot,total_quantity,available_quantity,price_cent) VALUES (?,?,'',?,?,?)",
                    type.skuId(), date, total, available, request.priceCent());
            inventoryId = lastId();
        } else {
            InventoryState current = rows.get(0);
            int occupied = current.total() - current.available();
            if (total - available < occupied) throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,
                    "不能减少已锁定或已售出的房量");
            jdbc.update("UPDATE bf_inventory SET total_quantity=?,available_quantity=?,price_cent=?,version=version+1 WHERE id=?",
                    total, available, request.priceCent(), current.id());
            inventoryId = current.id();
        }
        saveInventorySource(inventoryId, null, "MANUAL");
        audit(principal, "HOTEL_INVENTORY_UPDATE", "INVENTORY", inventoryId, date.toString());
        return calendar(roomTypeId, date, date).get(0);
    }

    private HotelResponse hotel(long id) {
        return hotels(null).stream().filter(value -> value.id() == id).findFirst().orElseThrow(() -> notFound("酒店不存在"));
    }

    private RoomTypeResponse roomType(long id) {
        return jdbc.query(roomTypeSelect() + " WHERE rt.id=? GROUP BY rt.id", roomTypeMapper(), id).stream()
                .findFirst().orElseThrow(() -> notFound("房型不存在"));
    }

    private RoomResponse room(long id) {
        return jdbc.query(roomSelect() + " WHERE room.id=?", roomMapper(), id).stream().findFirst()
                .orElseThrow(() -> notFound("房间不存在"));
    }

    private PriceRuleResponse priceRule(long id) {
        return jdbc.query(priceRuleSelect() + " WHERE p.id=?", priceRuleMapper(), id).stream().findFirst()
                .orElseThrow(() -> notFound("价格规则不存在"));
    }

    private String roomTypeSelect() {
        return """
                SELECT rt.*,h.name hotel_name,COUNT(DISTINCT room.id) physical_room_count
                FROM bf_hotel_room_type rt JOIN bf_hotel h ON h.id=rt.hotel_id
                LEFT JOIN bf_hotel_room room ON room.room_type_id=rt.id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<RoomTypeResponse> roomTypeMapper() {
        return (rs,n)->new RoomTypeResponse(rs.getLong("id"),rs.getLong("hotel_id"),rs.getString("hotel_name"),
                rs.getLong("resource_id"),rs.getLong("sku_id"),rs.getString("type_code"),rs.getString("name"),
                rs.getString("bed_type"),rs.getBigDecimal("area_sqm"),rs.getInt("max_guests"),
                rs.getInt("total_rooms"),rs.getInt("low_stock_threshold"),rs.getLong("base_price_cent"),
                rs.getString("description"),rs.getString("cover_url"),rs.getString("status"),
                rs.getLong("physical_room_count"),facilities(rs.getLong("id")),images(null,rs.getLong("id"),false),
                rs.getObject("created_at",java.time.LocalDateTime.class),rs.getObject("updated_at",java.time.LocalDateTime.class));
    }

    private String roomSelect() {
        return """
                SELECT room.*,h.name hotel_name,rt.name room_type_name FROM bf_hotel_room room
                JOIN bf_hotel h ON h.id=room.hotel_id JOIN bf_hotel_room_type rt ON rt.id=room.room_type_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<RoomResponse> roomMapper() {
        return (rs,n)->new RoomResponse(rs.getLong("id"),rs.getLong("hotel_id"),rs.getString("hotel_name"),
                rs.getLong("room_type_id"),rs.getString("room_type_name"),rs.getString("room_no"),
                rs.getString("floor_no"),rs.getString("status"),rs.getString("remark"),
                rs.getObject("created_at",java.time.LocalDateTime.class),rs.getObject("updated_at",java.time.LocalDateTime.class));
    }

    private String priceRuleSelect() {
        return """
                SELECT p.*,rt.name room_type_name FROM bf_hotel_price_rule p
                JOIN bf_hotel_room_type rt ON rt.id=p.room_type_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<PriceRuleResponse> priceRuleMapper() {
        return (rs,n)->new PriceRuleResponse(rs.getLong("id"),rs.getLong("room_type_id"),rs.getString("room_type_name"),
                rs.getString("rule_code"),rs.getString("name"),rs.getString("rule_type"),
                rs.getObject("start_date",LocalDate.class),rs.getObject("end_date",LocalDate.class),
                parseDays(rs.getString("days_of_week")),rs.getLong("price_cent"),rs.getInt("priority"),
                rs.getString("status"),rs.getObject("created_at",java.time.LocalDateTime.class),
                rs.getObject("updated_at",java.time.LocalDateTime.class));
    }

    private org.springframework.jdbc.core.RowMapper<InventoryDayResponse> inventoryMapper() {
        return (rs,n)->{
            int total=rs.getInt("total_quantity"), available=rs.getInt("available_quantity");
            int threshold=rs.getInt("low_stock_threshold");
            return new InventoryDayResponse(rs.getLong("id"),rs.getLong("room_type_id"),
                    rs.getObject("business_date",LocalDate.class),total,available,total-available,
                    rs.getLong("price_cent"),rs.getString("source_type"),rs.getObject("price_rule_id",Long.class),
                    rs.getString("rule_name"),available<=threshold);
        };
    }

    private List<FacilityResponse> facilities(long roomTypeId) {
        return jdbc.query("""
                SELECT id,facility_code,name,sort_order FROM bf_hotel_facility
                WHERE room_type_id=? ORDER BY sort_order,id
                """,(rs,n)->new FacilityResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4)),roomTypeId);
    }

    private List<ImageResponse> images(Long hotelId, Long roomTypeId, boolean onlyEnabled) {
        String owner = hotelId != null ? "hotel_id=?" : "room_type_id=?";
        Object id = hotelId != null ? hotelId : roomTypeId;
        return jdbc.query("SELECT id,image_url,image_type,sort_order,enabled FROM bf_hotel_image WHERE " + owner
                        + (onlyEnabled ? " AND enabled=1" : "") + " ORDER BY sort_order,id",
                (rs,n)->new ImageResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getBoolean(5)),id);
    }

    private void replaceFacilities(long roomTypeId, List<FacilityInput> values) {
        jdbc.update("DELETE FROM bf_hotel_facility WHERE room_type_id=?",roomTypeId);
        if(values==null)return;
        Set<String> codes=new LinkedHashSet<>();
        for(FacilityInput value:values){
            if(value==null)throw invalid("设施信息不能为空");
            String code=code(value.facilityCode(),"facilityCode",50);
            if(!codes.add(code))throw invalid("设施编码不能重复："+code);
            jdbc.update("INSERT INTO bf_hotel_facility(room_type_id,facility_code,name,sort_order) VALUES (?,?,?,?)",
                    roomTypeId,code,required(value.name(),"设施名称",100),value.sortOrder()==null?0:value.sortOrder());
        }
    }

    private void replaceImages(Long hotelId, Long roomTypeId, List<ImageInput> values) {
        if(hotelId!=null)jdbc.update("DELETE FROM bf_hotel_image WHERE hotel_id=?",hotelId);
        else jdbc.update("DELETE FROM bf_hotel_image WHERE room_type_id=?",roomTypeId);
        if(values==null)return;
        for(ImageInput value:values){
            if(value==null)throw invalid("图片信息不能为空");
            jdbc.update("""
                    INSERT INTO bf_hotel_image(hotel_id,room_type_id,image_url,image_type,sort_order,enabled)
                    VALUES (?,?,?,?,?,?)
                    """,hotelId,roomTypeId,required(value.imageUrl(),"imageUrl",512),
                    optional(value.imageType(),30)==null?"GALLERY":value.imageType().trim().toUpperCase(Locale.ROOT),
                    value.sortOrder()==null?0:value.sortOrder(),value.enabled()==null||value.enabled());
        }
    }

    private HotelValues validateHotel(HotelUpsertRequest request) {
        if(request==null)throw invalid("请求体不能为空");
        LocalTime checkIn=request.checkInTime()==null?LocalTime.of(14,0):request.checkInTime();
        LocalTime checkOut=request.checkOutTime()==null?LocalTime.NOON:request.checkOutTime();
        return new HotelValues(code(request.hotelCode(),"hotelCode",40),required(request.name(),"酒店名称",200),
                required(request.address(),"酒店地址",500),optional(request.contactPhone(),40),optional(request.description(),5000),
                checkIn,checkOut,status(request.status(),PUBLISH_STATUSES,"DRAFT"));
    }

    private RoomTypeValues validateRoomType(RoomTypeUpsertRequest request, Long id) {
        if(request==null||request.hotelId()==null||request.basePriceCent()==null)throw invalid("hotelId和basePriceCent不能为空");
        hotelIdentity(request.hotelId());
        BigDecimal area=request.areaSqm();
        if(area!=null&&(area.signum()<=0||area.compareTo(new BigDecimal("999999.99"))>0))throw invalid("房型面积不合法");
        int max=request.maxGuests()==null?0:request.maxGuests();
        int total=request.totalRooms()==null?0:request.totalRooms();
        int threshold=request.lowStockThreshold()==null?2:request.lowStockThreshold();
        if(max<1||max>99||total<0||total>9999||threshold<0||threshold>total||request.basePriceCent()<0)
            throw invalid("入住人数、总房量、库存预警值或基础价格不合法");
        if(id!=null){
            Integer physical=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel_room WHERE room_type_id=?",Integer.class,id);
            if(physical!=null&&total<physical)throw invalid("总房量不能少于已维护的实体房间数");
        }
        return new RoomTypeValues(request.hotelId(),code(request.typeCode(),"typeCode",40),required(request.name(),"房型名称",200),
                required(request.bedType(),"床型",100),area,max,total,threshold,request.basePriceCent(),
                optional(request.description(),5000),optional(request.coverUrl(),512),status(request.status(),PUBLISH_STATUSES,"DRAFT"));
    }

    private RoomValues validateRoom(RoomUpsertRequest request, Long updatingId) {
        if(request==null||request.hotelId()==null||request.roomTypeId()==null)throw invalid("hotelId和roomTypeId不能为空");
        RoomTypeIdentity type=roomTypeIdentity(request.roomTypeId());
        if(type.hotelId()!=request.hotelId())throw invalid("房间所属酒店与房型不一致");
        if(updatingId==null){
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel_room WHERE room_type_id=?",Integer.class,request.roomTypeId());
            Integer limit=jdbc.queryForObject("SELECT total_rooms FROM bf_hotel_room_type WHERE id=?",Integer.class,request.roomTypeId());
            if(count!=null&&limit!=null&&count>=limit)throw invalid("实体房间数不能超过房型总房量");
        }
        return new RoomValues(request.hotelId(),request.roomTypeId(),required(request.roomNo(),"房间号",40),
                optional(request.floorNo(),40),status(request.status(),ROOM_STATUSES,"VACANT"),optional(request.remark(),500));
    }

    private PriceRuleValues validatePriceRule(PriceRuleUpsertRequest request) {
        if(request==null||request.roomTypeId()==null||request.priceCent()==null)throw invalid("roomTypeId和priceCent不能为空");
        roomTypeIdentity(request.roomTypeId());
        String type=status(request.ruleType(),RULE_TYPES,null);
        if(request.startDate()!=null&&request.endDate()!=null&&request.endDate().isBefore(request.startDate()))throw invalid("价格规则结束日期不能早于开始日期");
        Set<Integer> days=new LinkedHashSet<>();
        if(request.daysOfWeek()!=null)for(Integer day:request.daysOfWeek()){
            if(day==null||day<1||day>7)throw invalid("daysOfWeek范围为1到7");days.add(day);
        }
        if("WEEKEND".equals(type)&&days.isEmpty())days.addAll(List.of(6,7));
        if(request.priceCent()<0)throw invalid("价格不能小于0");
        int priority=request.priority()==null?0:request.priority();
        if(priority<0||priority>10000)throw invalid("价格规则优先级范围为0到10000");
        return new PriceRuleValues(request.roomTypeId(),code(request.ruleCode(),"ruleCode",50),
                required(request.name(),"规则名称",100),type,request.startDate(),request.endDate(),joinDays(days),
                request.priceCent(),priority,status(request.status(),PUBLISH_STATUSES,"ACTIVE"));
    }

    private List<HotelPricingPolicy.PriceCandidate> activePriceCandidates(long roomTypeId) {
        return jdbc.query("""
                SELECT id,name,start_date,end_date,days_of_week,price_cent,priority FROM bf_hotel_price_rule
                WHERE room_type_id=? AND status='ACTIVE'
                """,(rs,n)->new HotelPricingPolicy.PriceCandidate(rs.getLong(1),rs.getString(2),
                rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),new LinkedHashSet<>(parseDays(rs.getString(5))),
                rs.getLong(6),rs.getInt(7)),roomTypeId);
    }

    private void saveInventorySource(long inventoryId, Long ruleId, String source) {
        jdbc.update("""
                INSERT INTO bf_hotel_inventory_source(inventory_id,price_rule_id,source_type) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE price_rule_id=VALUES(price_rule_id),source_type=VALUES(source_type)
                """,inventoryId,ruleId,source);
    }

    private HotelIdentity hotelIdentity(long id) {
        return jdbc.query("SELECT id,hotel_code FROM bf_hotel WHERE id=?",(rs,n)->new HotelIdentity(rs.getLong(1),rs.getString(2)),id)
                .stream().findFirst().orElseThrow(()->notFound("酒店不存在"));
    }
    private RoomTypeIdentity roomTypeIdentity(long id) {
        return jdbc.query("SELECT id,hotel_id,resource_id,sku_id FROM bf_hotel_room_type WHERE id=?",
                (rs,n)->new RoomTypeIdentity(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)),id)
                .stream().findFirst().orElseThrow(()->notFound("房型不存在"));
    }
    private RoomTypeInventoryIdentity inventoryIdentity(long id) {
        return jdbc.query("SELECT sku_id,total_rooms,base_price_cent FROM bf_hotel_room_type WHERE id=?",
                (rs,n)->new RoomTypeInventoryIdentity(rs.getLong(1),rs.getInt(2),rs.getLong(3)),id)
                .stream().findFirst().orElseThrow(()->notFound("房型不存在"));
    }

    private void validatePeriod(LocalDate start, LocalDate end) {
        if(start==null||end==null||end.isBefore(start))throw invalid("日期范围不合法");
        if(ChronoUnit.DAYS.between(start,end)>366)throw invalid("单次价格库存日历范围不能超过367天");
    }
    private String status(String value,Set<String> allowed,String defaultValue){
        if(!StringUtils.hasText(value)){if(defaultValue!=null)return defaultValue;throw invalid("类型或状态不能为空");}
        String normalized=value.trim().toUpperCase(Locale.ROOT);if(!allowed.contains(normalized))throw invalid("不支持的类型或状态："+normalized);return normalized;
    }
    private String optionalStatus(String value,Set<String> allowed){return StringUtils.hasText(value)?status(value,allowed,null):null;}
    private String code(String value,String field,int max){String result=required(value,field,max).toUpperCase(Locale.ROOT);if(!result.matches("[A-Z0-9][A-Z0-9_-]*"))throw invalid(field+"仅支持字母、数字、下划线和连字符");return result;}
    private String required(String value,String field,int max){if(!StringUtils.hasText(value))throw invalid(field+"不能为空");String result=value.trim();if(result.length()>max)throw invalid(field+"最长"+max+"字符");return result;}
    private String optional(String value,int max){if(!StringUtils.hasText(value))return null;String result=value.trim();if(result.length()>max)throw invalid("字段最长"+max+"字符");return result;}
    private String joinDays(Set<Integer> values){return values.isEmpty()?null:values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
    private List<Integer> parseDays(String value){if(!StringUtils.hasText(value))return List.of();return java.util.Arrays.stream(value.split(",")).map(Integer::valueOf).toList();}
    private long lastId(){return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    private void audit(AuthenticatedPrincipal principal,String action,String target,long id,String detail){long user=ensureUser(principal);jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT('detail',?))",user,action,target,String.valueOf(id),detail);}
    private long ensureUser(AuthenticatedPrincipal principal){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",principal.databaseOpenId(),principal.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,principal.databaseOpenId());}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}
    private BusinessException notFound(String message){return new BusinessException(CommonErrorCode.NOT_FOUND,message);}

    private record HotelValues(String code,String name,String address,String phone,String description,LocalTime checkIn,LocalTime checkOut,String status){}
    private record RoomTypeValues(long hotelId,String code,String name,String bedType,BigDecimal area,int maxGuests,int totalRooms,int lowStock,long basePrice,String description,String coverUrl,String status){}
    private record RoomValues(long hotelId,long roomTypeId,String roomNo,String floor,String status,String remark){}
    private record PriceRuleValues(long roomTypeId,String code,String name,String type,LocalDate start,LocalDate end,String days,long price,int priority,String status){}
    private record HotelIdentity(long id,String code){}
    private record RoomTypeIdentity(long id,long hotelId,long resourceId,long skuId){}
    private record RoomTypeInventoryIdentity(long skuId,int totalRooms,long basePrice){}
    private record InventoryState(long id,int total,int available){}
}
