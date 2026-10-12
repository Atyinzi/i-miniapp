package com.iminiapp.backend.hotel.application;

import com.iminiapp.backend.hotel.api.HotelOperationModels.*;
import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.shared.security.SensitiveDataCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class HotelOperationService {
    private final JdbcTemplate jdbc;
    private final SensitiveDataCipher cipher;
    private final HotelCheckinCredentialService credentialService;
    public HotelOperationService(JdbcTemplate jdbc,SensitiveDataCipher cipher,
                                 HotelCheckinCredentialService credentialService){
        this.jdbc=jdbc;this.cipher=cipher;this.credentialService=credentialService;
    }

    @Transactional(readOnly=true)
    public List<AdminStaySummary> bookings(String status,String keyword,Long hotelId,LocalDate start,LocalDate end){
        StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();
        if(StringUtils.hasText(status)){
            String normalized=status.trim().toUpperCase(Locale.ROOT);
            if(List.of("BOOKED","CHECKED_IN","CHECKED_OUT","NO_SHOW").contains(normalized)){where.append(" AND r.stay_status=?");args.add(normalized);}
            else{where.append(" AND o.status=?");args.add(normalized);}
        }
        if(hotelId!=null){where.append(" AND r.hotel_id=?");args.add(hotelId);}
        if(start!=null){where.append(" AND r.check_out_date>?");args.add(start);}
        if(end!=null){where.append(" AND r.check_in_date<=?");args.add(end);}
        if(StringUtils.hasText(keyword)){
            String raw=keyword.trim();String hash=cipher.searchHash(raw);
            where.append(" AND (r.reservation_no=? OR o.order_no=? OR r.contact_name_hash=? OR r.contact_mobile_hash=?)");
            args.add(raw);args.add(raw);args.add(hash);args.add(hash);
        }
        return jdbc.query(adminSelect()+where+" ORDER BY r.id DESC",adminMapper(),args.toArray());
    }

    @Transactional(readOnly=true)
    public AdminStayDetail detail(long reservationId){
        AdminStaySummary stay=jdbc.query(adminSelect()+" WHERE r.id=?",adminMapper(),reservationId).stream().findFirst()
                .orElseThrow(()->notFound("住宿订单不存在"));
        return new AdminStayDetail(stay,assignments(reservationId),charges(reservationId),settlement(reservationId));
    }

    @Transactional
    public AdminStayDetail checkIn(AuthenticatedPrincipal operator,long reservationId,String key,CheckInRequest request){
        String idempotency=operationKey(key);Long previous=operationResult(idempotency);
        if(previous!=null)return detail(previous);
        long operatorId=ensureOperator(operator);StayLock stay=lockStay(reservationId);
        if(!"BOOKED".equals(stay.stayStatus()))throw conflict("住宿订单当前状态不能办理入住："+stay.stayStatus());
        if(!List.of("PAID","READY","APPROVED").contains(stay.orderStatus()))throw conflict("住宿订单未支付或状态不允许入住");
        LocalDate today=LocalDate.now();boolean early=request!=null&&Boolean.TRUE.equals(request.allowEarlyCheckIn());
        if(today.isBefore(stay.checkIn())&&!early)throw conflict("尚未到入住日期，如需提前入住请明确授权");
        if(today.isBefore(stay.checkIn().minusDays(1)))throw conflict("最多只允许提前一天办理入住");
        if(!today.isBefore(stay.checkOut()))throw conflict("订单已超过退房日期，不能办理入住");
        List<Long> roomIds=request==null?null:request.roomIds();
        if(roomIds==null||roomIds.size()!=stay.roomCount()||new HashSet<>(roomIds).size()!=roomIds.size())
            throw invalid("分配房间数量必须与预订房间数一致且不能重复");
        List<RoomLock> rooms=lockRooms(roomIds);
        if(rooms.size()!=roomIds.size())throw invalid("部分房间不存在");
        for(RoomLock room:rooms){
            if(room.roomTypeId()!=stay.roomTypeId())throw invalid("分配房间必须属于预订房型");
            if(!"VACANT".equals(room.status()))throw conflict("房间"+room.roomNo()+"当前状态为"+room.status()+"，不能分配");
        }
        for(RoomLock room:rooms){
            jdbc.update("UPDATE bf_hotel_room SET status='OCCUPIED' WHERE id=? AND status='VACANT'",room.id());
            jdbc.update("""
                    INSERT INTO bf_hotel_room_assignment(reservation_id,room_id,assignment_status,assigned_by,change_reason)
                    VALUES (?,?,'ACTIVE',?,?)
                    """,reservationId,room.id(),operatorId,optional(request.note(),500));
        }
        jdbc.update("""
                UPDATE bf_hotel_reservation SET stay_status='CHECKED_IN',actual_check_in_at=CURRENT_TIMESTAMP,checked_in_by=?
                WHERE id=? AND stay_status='BOOKED'
                """,operatorId,reservationId);
        jdbc.update("UPDATE bf_hotel_checkin_credential SET status='USED',used_at=CURRENT_TIMESTAMP WHERE reservation_id=? AND status='ACTIVE'",reservationId);
        log(reservationId,"CHECK_IN","BOOKED","CHECKED_IN",operatorId,request==null?null:request.note(),idempotency);
        return detail(reservationId);
    }

    @Transactional
    public AdminStayDetail checkInByCode(AuthenticatedPrincipal operator,String key,CheckInByCodeRequest request){
        if(request==null)throw invalid("入住核销信息不能为空");
        long reservationId=credentialService.requireReservationByCode(request.checkInCode());
        return checkIn(operator,reservationId,key,new CheckInRequest(request.roomIds(),request.allowEarlyCheckIn(),request.note()));
    }

    @Transactional
    public AdminStayDetail checkInByIdCard(AuthenticatedPrincipal operator,String key,CheckInByIdCardRequest request){
        if(request==null)throw invalid("证件核验信息不能为空");
        long reservationId=credentialService.findReservationByIdCard(request.idNo());
        return checkIn(operator,reservationId,key,new CheckInRequest(request.roomIds(),request.allowEarlyCheckIn(),request.note()));
    }

    @Transactional
    public AdminStayDetail changeRoom(AuthenticatedPrincipal operator,long reservationId,String key,ChangeRoomRequest request){
        if(request==null||request.fromRoomId()==null||request.toRoomId()==null||request.fromRoomId().equals(request.toRoomId()))
            throw invalid("原房间和目标房间不能为空且不能相同");
        String idempotency=operationKey(key);Long previous=operationResult(idempotency);if(previous!=null)return detail(previous);
        long operatorId=ensureOperator(operator);StayLock stay=lockStay(reservationId);
        if(!"CHECKED_IN".equals(stay.stayStatus()))throw conflict("只有已入住订单可以换房");
        List<RoomLock> rooms=lockRooms(List.of(request.fromRoomId(),request.toRoomId()));
        RoomLock from=rooms.stream().filter(r->r.id()==request.fromRoomId()).findFirst().orElseThrow(()->notFound("原房间不存在"));
        RoomLock to=rooms.stream().filter(r->r.id()==request.toRoomId()).findFirst().orElseThrow(()->notFound("目标房间不存在"));
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM bf_hotel_room_assignment WHERE reservation_id=? AND room_id=? AND assignment_status='ACTIVE'",Integer.class,reservationId,from.id());
        if(active==null||active==0)throw conflict("原房间不属于当前住宿订单");
        if(to.roomTypeId()!=stay.roomTypeId())throw invalid("当前仅支持同房型换房");
        if(!"VACANT".equals(to.status()))throw conflict("目标房间不是空闲状态");
        jdbc.update("UPDATE bf_hotel_room_assignment SET assignment_status='CHANGED',assigned_until=CURRENT_TIMESTAMP,change_reason=? WHERE reservation_id=? AND room_id=? AND assignment_status='ACTIVE'",required(request.reason(),"换房原因",500),reservationId,from.id());
        jdbc.update("UPDATE bf_hotel_room SET status='DIRTY' WHERE id=?",from.id());
        jdbc.update("UPDATE bf_hotel_room SET status='OCCUPIED' WHERE id=? AND status='VACANT'",to.id());
        jdbc.update("INSERT INTO bf_hotel_room_assignment(reservation_id,room_id,assignment_status,assigned_by,change_reason) VALUES (?,?,'ACTIVE',?,?)",reservationId,to.id(),operatorId,request.reason().trim());
        log(reservationId,"CHANGE_ROOM","CHECKED_IN","CHECKED_IN",operatorId,request.reason(),idempotency);
        return detail(reservationId);
    }

    @Transactional
    public ExtendStayResponse extendStay(AuthenticatedPrincipal operator,long reservationId,String key,ExtendStayRequest request){
        if(request==null||request.newCheckOutDate()==null)throw invalid("新退房日期不能为空");
        String idempotency=operationKey(key);Long previous=operationResult(idempotency);
        if(previous!=null){StayLock existing=lockStay(previous);return new ExtendStayResponse(previous,existing.checkOut(),existing.checkOut(),0,0,orderAmount(existing.orderId()));}
        long operatorId=ensureOperator(operator);StayLock stay=lockStay(reservationId);
        if(!List.of("BOOKED","CHECKED_IN").contains(stay.stayStatus())||!List.of("PAID","READY","APPROVED").contains(stay.orderStatus()))
            throw conflict("当前住宿订单不能续住");
        if(!request.newCheckOutDate().isAfter(stay.checkOut()))throw invalid("新退房日期必须晚于原退房日期");
        if(ChronoUnit.DAYS.between(stay.checkIn(),request.newCheckOutDate())>30)throw invalid("续住后总住宿时长不能超过30晚");
        long additional=0;int nights=0;
        for(LocalDate day=stay.checkOut();day.isBefore(request.newCheckOutDate());day=day.plusDays(1)){
            LocalDate currentDay=day;
            InventoryLock inventory=jdbc.query("""
                    SELECT i.id,i.price_cent,i.available_quantity,rt.sku_id,sku.sku_code,sku.name,h.name hotel_name
                    FROM bf_hotel_room_type rt JOIN bf_resource_sku sku ON sku.id=rt.sku_id
                    JOIN bf_inventory i ON i.sku_id=rt.sku_id AND i.business_date=? AND i.time_slot=''
                    JOIN bf_hotel h ON h.id=rt.hotel_id WHERE rt.id=? FOR UPDATE
                    """,(rs,n)->new InventoryLock(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7)),currentDay,stay.roomTypeId()).stream().findFirst()
                    .orElseThrow(()->new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,currentDay+"尚未开放续住库存"));
            if(inventory.available()<stay.roomCount())throw new BusinessException(CommonErrorCode.INVENTORY_CONFLICT,currentDay+"续住房量不足");
            jdbc.update("UPDATE bf_inventory SET available_quantity=available_quantity-?,version=version+1 WHERE id=? AND available_quantity>=?",stay.roomCount(),inventory.id(),stay.roomCount());
            int after=inventory.available()-stay.roomCount();
            jdbc.update("INSERT INTO bf_inventory_log(inventory_id,order_no,change_type,quantity_delta,quantity_after,idempotency_key,remark) VALUES (?,(SELECT order_no FROM bf_order WHERE id=?),'ORDER_LOCK',?,?,?,'住宿续住锁定库存')",inventory.id(),stay.orderId(),-stay.roomCount(),after,idempotency+":"+currentDay);
            long amount=inventory.price()*stay.roomCount();additional+=amount;nights++;
            jdbc.update("""
                    INSERT INTO bf_order_item(order_id,sku_id,sku_code,sku_name,resource_name,resource_type,quantity,
                    unit_price_cent,amount_cent,service_date,time_slot,snapshot)
                    VALUES (?,?,?,?,?,'HOTEL_ROOM',?,?,?,?, '',JSON_OBJECT('source','HOTEL_EXTENSION'))
                    """,stay.orderId(),inventory.skuId(),inventory.skuCode(),inventory.skuName(),inventory.hotelName(),
                    stay.roomCount(),inventory.price(),amount,currentDay);
        }
        jdbc.update("UPDATE bf_order SET total_amount_cent=total_amount_cent+?,payable_amount_cent=payable_amount_cent+? WHERE id=?",additional,additional,stay.orderId());
        jdbc.update("UPDATE bf_hotel_reservation SET check_out_date=? WHERE id=?",request.newCheckOutDate(),reservationId);
        jdbc.update("UPDATE bf_hotel_checkin_credential SET valid_until=? WHERE reservation_id=? AND status='ACTIVE'",request.newCheckOutDate().atTime(23,59,59),reservationId);
        log(reservationId,"EXTEND_STAY",stay.stayStatus(),stay.stayStatus(),operatorId,request.remark(),idempotency);
        return new ExtendStayResponse(reservationId,stay.checkOut(),request.newCheckOutDate(),nights,additional,orderAmount(stay.orderId()));
    }

    @Transactional(readOnly=true)
    public CheckoutQuoteResponse checkoutQuote(long reservationId,List<ChargeInput> proposed){
        StayLock stay=readStay(reservationId);if(!"CHECKED_IN".equals(stay.stayStatus()))throw conflict("只有已入住订单可以退房结算");
        long roomAmount=orderAmount(stay.orderId());long stored=charges(reservationId).stream().mapToLong(ChargeResponse::amountCent).sum();
        long proposedAmount=validateCharges(proposed).stream().mapToLong(ChargeValues::amount).sum();long extra=Math.addExact(stored,proposedAmount);
        long paid=jdbc.queryForObject("SELECT paid_amount_cent FROM bf_order WHERE id=?",Long.class,stay.orderId());long total=Math.addExact(roomAmount,extra);
        return new CheckoutQuoteResponse(reservationId,roomAmount,extra,total,paid,Math.max(0,total-paid),Math.max(0,paid-total),charges(reservationId));
    }

    @Transactional
    public SettlementResponse checkout(AuthenticatedPrincipal operator,long reservationId,String key,CheckoutRequest request){
        if(request==null)throw invalid("退房结算信息不能为空");String idempotency=operationKey(key);
        Long previous=operationResult(idempotency);if(previous!=null){SettlementResponse existing=settlement(previous);if(existing!=null)return existing;}
        long operatorId=ensureOperator(operator);StayLock stay=lockStay(reservationId);
        if(!"CHECKED_IN".equals(stay.stayStatus()))throw conflict("只有已入住订单可以办理退房");
        for(ChargeValues charge:validateCharges(request.charges()))jdbc.update("INSERT INTO bf_hotel_charge(reservation_id,charge_type,description,quantity,unit_price_cent,amount_cent,created_by) VALUES (?,?,?,?,?,?,?)",reservationId,charge.type(),charge.description(),charge.quantity(),charge.unitPrice(),charge.amount(),operatorId);
        long roomAmount=orderAmount(stay.orderId());long extra=charges(reservationId).stream().mapToLong(ChargeResponse::amountCent).sum();long total=Math.addExact(roomAmount,extra);
        long paid=jdbc.queryForObject("SELECT paid_amount_cent FROM bf_order WHERE id=?",Long.class,stay.orderId());
        long due=Math.max(0,total-paid),refundable=Math.max(0,paid-total);long additional=request.additionalPaidCent()==null?0:request.additionalPaidCent();long refund=request.refundCent()==null?0:request.refundCent();
        if(additional!=due||refund!=refundable)throw conflict("结算收退款金额不匹配，应补收"+due+"分，应退款"+refundable+"分");
        String method=optional(request.paymentMethod(),30);if((additional>0||refund>0)&&method==null)throw invalid("发生补款或退款时必须填写结算方式");
        String no="HS"+LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+UUID.randomUUID().toString().replace("-","").substring(0,6).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO bf_hotel_settlement(settlement_no,reservation_id,room_amount_cent,extra_charge_cent,
                order_paid_cent,additional_paid_cent,refund_cent,final_amount_cent,payment_method,status,settled_by,
                settled_at,remark) VALUES (?,?,?,?,?,?,?,?,?,'COMPLETED',?,CURRENT_TIMESTAMP,?)
                """,no,reservationId,roomAmount,extra,paid,additional,refund,total,method,operatorId,optional(request.remark(),500));
        jdbc.update("UPDATE bf_order SET total_amount_cent=?,payable_amount_cent=?,paid_amount_cent=?,status='COMPLETED' WHERE id=?",total,total,total,stay.orderId());
        List<Long> roomIds=jdbc.queryForList("SELECT room_id FROM bf_hotel_room_assignment WHERE reservation_id=? AND assignment_status='ACTIVE' FOR UPDATE",Long.class,reservationId);
        jdbc.update("UPDATE bf_hotel_room_assignment SET assignment_status='COMPLETED',assigned_until=CURRENT_TIMESTAMP WHERE reservation_id=? AND assignment_status='ACTIVE'",reservationId);
        for(Long roomId:roomIds)jdbc.update("UPDATE bf_hotel_room SET status='DIRTY' WHERE id=?",roomId);
        jdbc.update("UPDATE bf_hotel_reservation SET stay_status='CHECKED_OUT',actual_check_out_at=CURRENT_TIMESTAMP,checked_out_by=? WHERE id=?",operatorId,reservationId);
        log(reservationId,"CHECK_OUT","CHECKED_IN","CHECKED_OUT",operatorId,request.remark(),idempotency);
        return settlement(reservationId);
    }

    @Transactional
    public AdminStayDetail markNoShow(AuthenticatedPrincipal operator,long reservationId,String key,String note){
        String idempotency=operationKey(key);Long previous=operationResult(idempotency);if(previous!=null)return detail(previous);
        long operatorId=ensureOperator(operator);StayLock stay=lockStay(reservationId);
        if(!"BOOKED".equals(stay.stayStatus())||!List.of("PAID","READY","APPROVED").contains(stay.orderStatus()))throw conflict("当前订单不能标记未到店");
        if(LocalDate.now().isBefore(stay.checkIn()))throw conflict("入住日前不能标记未到店");
        jdbc.update("UPDATE bf_hotel_reservation SET stay_status='NO_SHOW',no_show_at=CURRENT_TIMESTAMP WHERE id=?",reservationId);
        jdbc.update("UPDATE bf_order SET status='COMPLETED' WHERE id=?",stay.orderId());
        jdbc.update("UPDATE bf_hotel_checkin_credential SET status='VOID' WHERE reservation_id=? AND status='ACTIVE'",reservationId);
        log(reservationId,"NO_SHOW","BOOKED","NO_SHOW",operatorId,note,idempotency);return detail(reservationId);
    }

    @Transactional
    public RoomStatusResponse completeCleaning(AuthenticatedPrincipal operator,long roomId){
        ensureOperator(operator);int changed=jdbc.update("UPDATE bf_hotel_room SET status='VACANT' WHERE id=? AND status='DIRTY'",roomId);
        if(changed==0){RoomStatusResponse room=roomStatus(roomId);throw conflict("房间当前状态为"+room.status()+"，不能完成清洁");}
        return roomStatus(roomId);
    }

    @Transactional(readOnly=true)
    public List<OperationWarning> warnings(){
        return jdbc.query("""
                SELECT r.id,r.reservation_no,r.check_in_date,r.check_out_date,r.stay_status,
                CASE WHEN r.stay_status='BOOKED' AND r.check_in_date<CURRENT_DATE THEN 'LATE_ARRIVAL'
                     WHEN r.stay_status='CHECKED_IN' AND r.check_out_date<=CURRENT_DATE THEN 'OVERDUE_CHECKOUT'
                     WHEN r.stay_status='BOOKED' AND r.check_in_date=CURRENT_DATE THEN 'ARRIVAL_TODAY' END warning_type
                FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id
                WHERE o.status IN ('PAID','READY','APPROVED') AND
                ((r.stay_status='BOOKED' AND r.check_in_date<=CURRENT_DATE) OR
                 (r.stay_status='CHECKED_IN' AND r.check_out_date<=CURRENT_DATE))
                ORDER BY r.check_in_date,r.id
                """,(rs,n)->{String type=rs.getString("warning_type");String message=switch(type){case "LATE_ARRIVAL"->"订单已过计划入住日但尚未入住";case "OVERDUE_CHECKOUT"->"订单已到退房日但尚未退房";default->"订单计划今日入住";};return new OperationWarning(rs.getLong("id"),rs.getString("reservation_no"),type,message,rs.getObject("check_in_date",LocalDate.class),rs.getObject("check_out_date",LocalDate.class),rs.getString("stay_status"));});
    }

    private String adminSelect(){return """
            SELECT r.id reservation_id,r.reservation_no,r.order_id,o.order_no,o.status order_status,r.stay_status,
            r.hotel_id,h.name hotel_name,r.room_type_id,rt.name room_type_name,r.check_in_date,r.check_out_date,
            r.room_count,r.guest_count,r.contact_name_cipher,r.contact_mobile_cipher,o.payable_amount_cent,
            o.paid_amount_cent,r.actual_check_in_at,r.actual_check_out_at,r.created_at
            FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id JOIN bf_hotel h ON h.id=r.hotel_id
            JOIN bf_hotel_room_type rt ON rt.id=r.room_type_id
            """;}
    private org.springframework.jdbc.core.RowMapper<AdminStaySummary> adminMapper(){return(rs,n)->new AdminStaySummary(rs.getLong("reservation_id"),rs.getString("reservation_no"),rs.getLong("order_id"),rs.getString("order_no"),rs.getString("order_status"),rs.getString("stay_status"),rs.getLong("hotel_id"),rs.getString("hotel_name"),rs.getLong("room_type_id"),rs.getString("room_type_name"),rs.getObject("check_in_date",LocalDate.class),rs.getObject("check_out_date",LocalDate.class),rs.getInt("room_count"),rs.getInt("guest_count"),cipher.decrypt(rs.getBytes("contact_name_cipher")),cipher.decrypt(rs.getBytes("contact_mobile_cipher")),rs.getLong("payable_amount_cent"),rs.getLong("paid_amount_cent"),rs.getObject("actual_check_in_at",LocalDateTime.class),rs.getObject("actual_check_out_at",LocalDateTime.class),rs.getObject("created_at",LocalDateTime.class));}
    private List<RoomAssignmentResponse> assignments(long reservationId){return jdbc.query("""
            SELECT a.id,a.room_id,room.room_no,room.floor_no,a.assignment_status,a.assigned_from,a.assigned_until,a.change_reason
            FROM bf_hotel_room_assignment a JOIN bf_hotel_room room ON room.id=a.room_id WHERE a.reservation_id=? ORDER BY a.id
            """,(rs,n)->new RoomAssignmentResponse(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,LocalDateTime.class),rs.getObject(7,LocalDateTime.class),rs.getString(8)),reservationId);}
    private List<ChargeResponse> charges(long reservationId){return jdbc.query("SELECT id,charge_type,description,quantity,unit_price_cent,amount_cent,created_at FROM bf_hotel_charge WHERE reservation_id=? ORDER BY id",(rs,n)->new ChargeResponse(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getLong(5),rs.getLong(6),rs.getObject(7,LocalDateTime.class)),reservationId);}
    private SettlementResponse settlement(long reservationId){return jdbc.query("SELECT * FROM bf_hotel_settlement WHERE reservation_id=?",(rs,n)->new SettlementResponse(rs.getLong("id"),rs.getString("settlement_no"),rs.getLong("reservation_id"),rs.getLong("room_amount_cent"),rs.getLong("extra_charge_cent"),rs.getLong("order_paid_cent"),rs.getLong("additional_paid_cent"),rs.getLong("refund_cent"),rs.getLong("final_amount_cent"),rs.getString("payment_method"),rs.getString("status"),rs.getString("remark"),rs.getObject("settled_at",LocalDateTime.class)),reservationId).stream().findFirst().orElse(null);}
    private StayLock lockStay(long id){return stay(id,true);}private StayLock readStay(long id){return stay(id,false);}
    private StayLock stay(long id,boolean lock){return jdbc.query("""
            SELECT r.order_id,r.room_type_id,r.check_in_date,r.check_out_date,r.room_count,r.stay_status,o.status
            FROM bf_hotel_reservation r JOIN bf_order o ON o.id=r.order_id WHERE r.id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new StayLock(rs.getLong(1),rs.getLong(2),rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),rs.getInt(5),rs.getString(6),rs.getString(7)),id).stream().findFirst().orElseThrow(()->notFound("住宿订单不存在"));}
    private List<RoomLock> lockRooms(List<Long> ids){if(ids.isEmpty())return List.of();String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));return jdbc.query("SELECT id,room_type_id,room_no,status FROM bf_hotel_room WHERE id IN ("+placeholders+") ORDER BY id FOR UPDATE",(rs,n)->new RoomLock(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4)),ids.toArray());}
    private RoomStatusResponse roomStatus(long roomId){return jdbc.query("SELECT room.id,room.room_no,room.room_type_id,rt.name,room.status,room.updated_at FROM bf_hotel_room room JOIN bf_hotel_room_type rt ON rt.id=room.room_type_id WHERE room.id=?",(rs,n)->new RoomStatusResponse(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getObject(6,LocalDateTime.class)),roomId).stream().findFirst().orElseThrow(()->notFound("房间不存在"));}
    private List<ChargeValues> validateCharges(List<ChargeInput> inputs){if(inputs==null)return List.of();List<ChargeValues> result=new ArrayList<>();for(ChargeInput input:inputs){if(input==null||input.quantity()==null||input.unitPriceCent()==null)throw invalid("费用项目不完整");String type=required(input.chargeType(),"费用类型",30).toUpperCase(Locale.ROOT);if(!List.of("MINIBAR","DINING","DAMAGE","LATE_CHECKOUT","OTHER").contains(type))throw invalid("不支持的费用类型："+type);if(input.quantity()<1||input.quantity()>999||input.unitPriceCent()<0)throw invalid("费用数量或单价不合法");long amount=Math.multiplyExact(input.quantity().longValue(),input.unitPriceCent());result.add(new ChargeValues(type,required(input.description(),"费用说明",200),input.quantity(),input.unitPriceCent(),amount));}return result;}
    private long orderAmount(long orderId){return jdbc.queryForObject("SELECT COALESCE(SUM(amount_cent),0) FROM bf_order_item WHERE order_id=?",Long.class,orderId);}
    private String operationKey(String key){if(!StringUtils.hasText(key)||key.trim().length()>80)throw invalid("Idempotency-Key不能为空且最长80字符");return key.trim();}
    private Long operationResult(String key){List<Long> ids=jdbc.queryForList("SELECT reservation_id FROM bf_hotel_operation_log WHERE idempotency_key=?",Long.class,key);return ids.isEmpty()?null:ids.get(0);}
    private void log(long reservationId,String action,String before,String after,long operator,String note,String key){jdbc.update("INSERT INTO bf_hotel_operation_log(reservation_id,action,status_before,status_after,operator_id,detail,idempotency_key) VALUES (?,?,?,?,?,JSON_OBJECT('note',?),?)",reservationId,action,before,after,operator,optional(note,500),key);}
    private long ensureOperator(AuthenticatedPrincipal principal){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",principal.databaseOpenId(),principal.displayName());return jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,principal.databaseOpenId());}
    private String required(String value,String field,int max){if(!StringUtils.hasText(value))throw invalid(field+"不能为空");String result=value.trim();if(result.length()>max)throw invalid(field+"最长"+max+"字符");return result;}
    private String optional(String value,int max){if(!StringUtils.hasText(value))return null;String result=value.trim();if(result.length()>max)throw invalid("字段最长"+max+"字符");return result;}
    private BusinessException invalid(String message){return new BusinessException(CommonErrorCode.INVALID_REQUEST,message);}private BusinessException notFound(String message){return new BusinessException(CommonErrorCode.NOT_FOUND,message);}private BusinessException conflict(String message){return new BusinessException(CommonErrorCode.VERIFICATION_CONFLICT,message);}
    private record StayLock(long orderId,long roomTypeId,LocalDate checkIn,LocalDate checkOut,int roomCount,String stayStatus,String orderStatus){}
    private record RoomLock(long id,long roomTypeId,String roomNo,String status){}
    private record InventoryLock(long id,long price,int available,long skuId,String skuCode,String skuName,String hotelName){}
    private record ChargeValues(String type,String description,int quantity,long unitPrice,long amount){}
}
