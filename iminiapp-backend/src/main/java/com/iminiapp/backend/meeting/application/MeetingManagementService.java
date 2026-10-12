package com.iminiapp.backend.meeting.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.meeting.api.MeetingManagementModels.*;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 会议室、设备、营业时段、收费规则和配套服务的管理端应用服务。 */
@Service
public class MeetingManagementService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> SERVICE_TYPES = Set.of("EQUIPMENT", "TEA_BREAK", "ACCOMMODATION");
    private static final Set<String> UNITS = Set.of("PER_EVENT", "PER_PERSON", "PER_HOUR", "PER_DAY");
    private final JdbcTemplate jdbc;

    public MeetingManagementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<RoomResponse> rooms(String status) {
        String where = StringUtils.hasText(status) ? " WHERE r.status=?" : "";
        Object[] args = StringUtils.hasText(status) ? new Object[]{status(status)} : new Object[0];
        return jdbc.query("""
                SELECT r.*, (SELECT COUNT(*) FROM bf_meeting_room_equipment e WHERE e.room_id=r.id) equipment_count,
                (SELECT COUNT(*) FROM bf_meeting_opening_rule o WHERE o.room_id=r.id) opening_count
                FROM bf_meeting_room r""" + where + " ORDER BY r.id", (rs, n) -> room(rs), args);
    }

    @Transactional
    public RoomResponse createRoom(AuthenticatedPrincipal p, RoomUpsertRequest request) {
        RoomValue v = roomValue(request); unique("SELECT COUNT(*) FROM bf_meeting_room WHERE room_code=?", "会议室编码已存在", v.code());
        long actor = actor(p);
        jdbc.update("INSERT INTO bf_meeting_room(room_code,name,location,capacity,description,open_time,close_time,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                v.code(), v.name(), v.location(), v.capacity(), v.description(), v.open(), v.close(), v.status(), actor, actor);
        long id = lastId(); audit(actor, "MEETING_ROOM_CREATE", "MEETING_ROOM", id, v.code()); return roomById(id);
    }

    @Transactional
    public RoomResponse updateRoom(AuthenticatedPrincipal p, long id, RoomUpsertRequest request) {
        roomById(id); RoomValue v = roomValue(request); unique("SELECT COUNT(*) FROM bf_meeting_room WHERE room_code=? AND id<>?", "会议室编码已存在", v.code(), id);
        if ("INACTIVE".equals(v.status()) && count("SELECT COUNT(*) FROM bf_meeting_opening_rule WHERE room_id=? AND status='ACTIVE'", id) > 0)
            throw conflict("停用会议室前请先停用其开放时段");
        long actor = actor(p); jdbc.update("UPDATE bf_meeting_room SET room_code=?,name=?,location=?,capacity=?,description=?,open_time=?,close_time=?,status=?,updated_by=? WHERE id=?",
                v.code(), v.name(), v.location(), v.capacity(), v.description(), v.open(), v.close(), v.status(), actor, id);
        audit(actor, "MEETING_ROOM_UPDATE", "MEETING_ROOM", id, v.code()); return roomById(id);
    }

    @Transactional(readOnly = true)
    public List<EquipmentResponse> equipment(Long roomId, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1"); ArrayList<Object> args = new ArrayList<>();
        if (roomId != null) { where.append(" AND e.room_id=?"); args.add(roomId); }
        if (StringUtils.hasText(status)) { where.append(" AND e.status=?"); args.add(status(status)); }
        return jdbc.query("SELECT e.*,r.name room_name FROM bf_meeting_room_equipment e JOIN bf_meeting_room r ON r.id=e.room_id" + where + " ORDER BY e.room_id,e.id", (rs,n) -> equipment(rs), args.toArray());
    }

    @Transactional
    public EquipmentResponse createEquipment(AuthenticatedPrincipal p, EquipmentUpsertRequest request) {
        EquipmentValue v = equipmentValue(request); roomById(v.roomId()); unique("SELECT COUNT(*) FROM bf_meeting_room_equipment WHERE room_id=? AND equipment_code=?", "同一会议室下设备编码已存在", v.roomId(), v.code());
        long actor=actor(p); jdbc.update("INSERT INTO bf_meeting_room_equipment(room_id,equipment_code,name,quantity,description,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)", v.roomId(),v.code(),v.name(),v.quantity(),v.description(),v.status(),actor,actor);
        long id=lastId(); audit(actor,"MEETING_EQUIPMENT_CREATE","MEETING_EQUIPMENT",id,v.code()); return equipmentById(id);
    }

    @Transactional
    public EquipmentResponse updateEquipment(AuthenticatedPrincipal p, long id, EquipmentUpsertRequest request) {
        equipmentById(id); EquipmentValue v=equipmentValue(request); roomById(v.roomId()); unique("SELECT COUNT(*) FROM bf_meeting_room_equipment WHERE room_id=? AND equipment_code=? AND id<>?", "同一会议室下设备编码已存在",v.roomId(),v.code(),id);
        long actor=actor(p); jdbc.update("UPDATE bf_meeting_room_equipment SET room_id=?,equipment_code=?,name=?,quantity=?,description=?,status=?,updated_by=? WHERE id=?",v.roomId(),v.code(),v.name(),v.quantity(),v.description(),v.status(),actor,id);
        audit(actor,"MEETING_EQUIPMENT_UPDATE","MEETING_EQUIPMENT",id,v.code()); return equipmentById(id);
    }

    @Transactional(readOnly = true)
    public List<OpeningRuleResponse> openings(Long roomId) {
        String where=roomId == null ? "" : " WHERE o.room_id=?"; Object[] args=roomId == null ? new Object[0] : new Object[]{roomId};
        return jdbc.query("SELECT o.*,r.name room_name FROM bf_meeting_opening_rule o JOIN bf_meeting_room r ON r.id=o.room_id"+where+" ORDER BY o.room_id,o.day_of_week,o.start_time", (rs,n)->opening(rs),args);
    }

    @Transactional
    public OpeningRuleResponse createOpening(AuthenticatedPrincipal p, OpeningRuleUpsertRequest request) {
        OpeningValue v=openingValue(request); openingNoOverlap(v,null); long actor=actor(p);
        jdbc.update("INSERT INTO bf_meeting_opening_rule(room_id,day_of_week,start_time,end_time,slot_minutes,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",v.roomId(),v.day(),v.start(),v.end(),v.slot(),v.status(),actor,actor);
        long id=lastId(); audit(actor,"MEETING_OPENING_CREATE","MEETING_OPENING_RULE",id,String.valueOf(v.roomId())); return openingById(id);
    }

    @Transactional
    public OpeningRuleResponse updateOpening(AuthenticatedPrincipal p, long id, OpeningRuleUpsertRequest request) {
        openingById(id); OpeningValue v=openingValue(request); openingNoOverlap(v,id); long actor=actor(p);
        jdbc.update("UPDATE bf_meeting_opening_rule SET room_id=?,day_of_week=?,start_time=?,end_time=?,slot_minutes=?,status=?,updated_by=? WHERE id=?",v.roomId(),v.day(),v.start(),v.end(),v.slot(),v.status(),actor,id);
        audit(actor,"MEETING_OPENING_UPDATE","MEETING_OPENING_RULE",id,String.valueOf(v.roomId())); return openingById(id);
    }

    @Transactional(readOnly = true)
    public List<PriceRuleResponse> prices(Long roomId, String status) {
        StringBuilder where=new StringBuilder(" WHERE 1=1"); ArrayList<Object> args=new ArrayList<>();
        if(roomId!=null){where.append(" AND p.room_id=?");args.add(roomId);} if(StringUtils.hasText(status)){where.append(" AND p.status=?");args.add(status(status));}
        return jdbc.query("SELECT p.*,r.name room_name FROM bf_meeting_price_rule p JOIN bf_meeting_room r ON r.id=p.room_id"+where+" ORDER BY p.room_id,p.priority DESC,p.id",(rs,n)->price(rs),args.toArray());
    }

    @Transactional
    public PriceRuleResponse createPrice(AuthenticatedPrincipal p, PriceRuleUpsertRequest request) {
        PriceValue v=priceValue(request); roomById(v.roomId()); unique("SELECT COUNT(*) FROM bf_meeting_price_rule WHERE rule_code=?","收费规则编码已存在",v.code()); priceNoOverlap(v,null); long actor=actor(p);
        jdbc.update("INSERT INTO bf_meeting_price_rule(room_id,rule_code,name,start_date,end_date,day_of_week,start_time,end_time,min_attendees,max_attendees,price_cent,priority,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",v.roomId(),v.code(),v.name(),v.startDate(),v.endDate(),v.day(),v.start(),v.end(),v.min(),v.max(),v.price(),v.priority(),v.status(),actor,actor);
        long id=lastId(); audit(actor,"MEETING_PRICE_CREATE","MEETING_PRICE_RULE",id,v.code()); return priceById(id);
    }

    @Transactional
    public PriceRuleResponse updatePrice(AuthenticatedPrincipal p, long id, PriceRuleUpsertRequest request) {
        priceById(id); PriceValue v=priceValue(request); roomById(v.roomId()); unique("SELECT COUNT(*) FROM bf_meeting_price_rule WHERE rule_code=? AND id<>?","收费规则编码已存在",v.code(),id); priceNoOverlap(v,id); long actor=actor(p);
        jdbc.update("UPDATE bf_meeting_price_rule SET room_id=?,rule_code=?,name=?,start_date=?,end_date=?,day_of_week=?,start_time=?,end_time=?,min_attendees=?,max_attendees=?,price_cent=?,priority=?,status=?,updated_by=? WHERE id=?",v.roomId(),v.code(),v.name(),v.startDate(),v.endDate(),v.day(),v.start(),v.end(),v.min(),v.max(),v.price(),v.priority(),v.status(),actor,id);
        audit(actor,"MEETING_PRICE_UPDATE","MEETING_PRICE_RULE",id,v.code()); return priceById(id);
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> services(String serviceType, String status) {
        StringBuilder where=new StringBuilder(" WHERE 1=1"); ArrayList<Object> args=new ArrayList<>();
        if(StringUtils.hasText(serviceType)){where.append(" AND service_type=?");args.add(serviceType(serviceType));} if(StringUtils.hasText(status)){where.append(" AND status=?");args.add(status(status));}
        return jdbc.query("SELECT * FROM bf_meeting_service"+where+" ORDER BY service_type,id",(rs,n)->service(rs),args.toArray());
    }

    @Transactional
    public ServiceResponse createService(AuthenticatedPrincipal p, ServiceUpsertRequest request) {
        ServiceValue v=serviceValue(request); unique("SELECT COUNT(*) FROM bf_meeting_service WHERE service_code=?","配套服务编码已存在",v.code()); long actor=actor(p);
        jdbc.update("INSERT INTO bf_meeting_service(service_code,name,service_type,pricing_unit,price_cent,description,status,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?)",v.code(),v.name(),v.type(),v.unit(),v.price(),v.description(),v.status(),actor,actor);
        long id=lastId(); audit(actor,"MEETING_SERVICE_CREATE","MEETING_SERVICE",id,v.code()); return serviceById(id);
    }

    @Transactional
    public ServiceResponse updateService(AuthenticatedPrincipal p, long id, ServiceUpsertRequest request) {
        serviceById(id); ServiceValue v=serviceValue(request); unique("SELECT COUNT(*) FROM bf_meeting_service WHERE service_code=? AND id<>?","配套服务编码已存在",v.code(),id); long actor=actor(p);
        jdbc.update("UPDATE bf_meeting_service SET service_code=?,name=?,service_type=?,pricing_unit=?,price_cent=?,description=?,status=?,updated_by=? WHERE id=?",v.code(),v.name(),v.type(),v.unit(),v.price(),v.description(),v.status(),actor,id);
        audit(actor,"MEETING_SERVICE_UPDATE","MEETING_SERVICE",id,v.code()); return serviceById(id);
    }

    private RoomResponse roomById(long id){return rooms(null).stream().filter(x->x.id()==id).findFirst().orElseThrow(()->notFound("会议室不存在"));}
    private EquipmentResponse equipmentById(long id){return equipment(null,null).stream().filter(x->x.id()==id).findFirst().orElseThrow(()->notFound("会议室设备不存在"));}
    private OpeningRuleResponse openingById(long id){return openings(null).stream().filter(x->x.id()==id).findFirst().orElseThrow(()->notFound("可预订时段不存在"));}
    private PriceRuleResponse priceById(long id){return prices(null,null).stream().filter(x->x.id()==id).findFirst().orElseThrow(()->notFound("收费规则不存在"));}
    private ServiceResponse serviceById(long id){return services(null,null).stream().filter(x->x.id()==id).findFirst().orElseThrow(()->notFound("配套服务不存在"));}

    private RoomResponse room(ResultSet r) throws SQLException { return new RoomResponse(r.getLong("id"),r.getString("room_code"),r.getString("name"),r.getString("location"),r.getInt("capacity"),r.getString("description"),r.getObject("open_time",LocalTime.class),r.getObject("close_time",LocalTime.class),r.getString("status"),r.getLong("equipment_count"),r.getLong("opening_count"),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class)); }
    private EquipmentResponse equipment(ResultSet r)throws SQLException{return new EquipmentResponse(r.getLong("id"),r.getLong("room_id"),r.getString("room_name"),r.getString("equipment_code"),r.getString("name"),r.getInt("quantity"),r.getString("description"),r.getString("status"),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class));}
    private OpeningRuleResponse opening(ResultSet r)throws SQLException{return new OpeningRuleResponse(r.getLong("id"),r.getLong("room_id"),r.getString("room_name"),r.getInt("day_of_week"),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class),r.getInt("slot_minutes"),r.getString("status"),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class));}
    private PriceRuleResponse price(ResultSet r)throws SQLException{return new PriceRuleResponse(r.getLong("id"),r.getLong("room_id"),r.getString("room_name"),r.getString("rule_code"),r.getString("name"),r.getObject("start_date",LocalDate.class),r.getObject("end_date",LocalDate.class),r.getObject("day_of_week",Integer.class),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class),r.getObject("min_attendees",Integer.class),r.getObject("max_attendees",Integer.class),r.getLong("price_cent"),r.getInt("priority"),r.getString("status"),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class));}
    private ServiceResponse service(ResultSet r)throws SQLException{return new ServiceResponse(r.getLong("id"),r.getString("service_code"),r.getString("name"),r.getString("service_type"),r.getString("pricing_unit"),r.getLong("price_cent"),r.getString("description"),r.getString("status"),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class));}

    private RoomValue roomValue(RoomUpsertRequest r){if(r==null||r.capacity()==null||r.capacity()<1||r.capacity()>100000||r.openTime()==null||r.closeTime()==null||!r.openTime().isBefore(r.closeTime()))throw invalid("会议室容量或营业时间不合法");return new RoomValue(code(r.roomCode(),50,"会议室编码"),required(r.name(),120,"会议室名称"),required(r.location(),255,"会议室位置"),r.capacity(),optional(r.description(),2000),r.openTime(),r.closeTime(),status(r.status()));}
    private EquipmentValue equipmentValue(EquipmentUpsertRequest r){if(r==null||r.roomId()==null||r.roomId()<1||r.quantity()==null||r.quantity()<1||r.quantity()>100000)throw invalid("设备归属或数量不合法");return new EquipmentValue(r.roomId(),code(r.equipmentCode(),50,"设备编码"),required(r.name(),120,"设备名称"),r.quantity(),optional(r.description(),500),status(r.status()));}
    private OpeningValue openingValue(OpeningRuleUpsertRequest r){if(r==null||r.roomId()==null||r.roomId()<1||r.dayOfWeek()==null||r.dayOfWeek()<1||r.dayOfWeek()>7||r.startTime()==null||r.endTime()==null||!r.startTime().isBefore(r.endTime())||r.slotMinutes()==null||r.slotMinutes()<15||r.slotMinutes()>720)throw invalid("开放日期、时间或时段长度不合法");RoomResponse room=roomById(r.roomId());if(r.startTime().isBefore(room.openTime())||r.endTime().isAfter(room.closeTime()))throw invalid("开放时段必须位于会议室营业时间内");if(Duration.between(r.startTime(),r.endTime()).toMinutes()%r.slotMinutes()!=0)throw invalid("开放时段必须能被预订时长整除");return new OpeningValue(r.roomId(),r.dayOfWeek(),r.startTime(),r.endTime(),r.slotMinutes(),status(r.status()));}
    private PriceValue priceValue(PriceRuleUpsertRequest r){if(r==null||r.roomId()==null||r.roomId()<1||r.priceCent()==null||r.priceCent()<0)throw invalid("会议室或收费金额不合法");if((r.startDate()==null)!=(r.endDate()==null)||(r.startDate()!=null&&r.endDate().isBefore(r.startDate())))throw invalid("收费规则日期范围不合法");if(r.dayOfWeek()!=null&&(r.dayOfWeek()<1||r.dayOfWeek()>7))throw invalid("星期范围应为1至7");if((r.startTime()==null)!=(r.endTime()==null)||(r.startTime()!=null&&!r.startTime().isBefore(r.endTime())))throw invalid("收费规则时间范围不合法");if((r.minAttendees()==null)!=(r.maxAttendees()==null)||(r.minAttendees()!=null&&(r.minAttendees()<1||r.maxAttendees()<r.minAttendees())))throw invalid("参会人数范围不合法");RoomResponse room=roomById(r.roomId());if(r.maxAttendees()!=null&&r.maxAttendees()>room.capacity())throw invalid("参会人数上限不能超过会议室容量");return new PriceValue(r.roomId(),code(r.ruleCode(),64,"收费规则编码"),required(r.name(),120,"收费规则名称"),r.startDate(),r.endDate(),r.dayOfWeek(),r.startTime(),r.endTime(),r.minAttendees(),r.maxAttendees(),r.priceCent(),r.priority()==null?0:r.priority(),status(r.status()));}
    private ServiceValue serviceValue(ServiceUpsertRequest r){if(r==null||r.priceCent()==null||r.priceCent()<0)throw invalid("服务收费不合法");String type=serviceType(r.serviceType()), unit=unit(r.pricingUnit());if(("TEA_BREAK".equals(type)&&!Set.of("PER_EVENT","PER_PERSON").contains(unit))||("ACCOMMODATION".equals(type)&&!Set.of("PER_PERSON","PER_DAY").contains(unit)))throw invalid("该服务类型不支持所选计价单位");return new ServiceValue(code(r.serviceCode(),50,"服务编码"),required(r.name(),120,"服务名称"),type,unit,r.priceCent(),optional(r.description(),1000),status(r.status()));}

    private void openingNoOverlap(OpeningValue v,Long ignored){if(!"ACTIVE".equals(v.status()))return;if(count("SELECT COUNT(*) FROM bf_meeting_opening_rule WHERE room_id=? AND day_of_week=? AND status='ACTIVE' AND (? IS NULL OR id<>?) AND start_time<? AND end_time>?",v.roomId(),v.day(),ignored,ignored,v.end(),v.start())>0)throw conflict("同一会议室同一天的开放时段不能重叠");}
    private void priceNoOverlap(PriceValue v,Long ignored){if(!"ACTIVE".equals(v.status()))return;for(PriceRuleResponse old:prices(v.roomId(),"ACTIVE")){if(ignored!=null&&old.id()==ignored)continue;if(old.priority()==v.priority()&&overlap(v.startDate(),v.endDate(),old.startDate(),old.endDate())&&overlap(v.day(),old.dayOfWeek())&&overlap(v.start(),v.end(),old.startTime(),old.endTime())&&overlap(v.min(),v.max(),old.minAttendees(),old.maxAttendees()))throw conflict("同一优先级下存在覆盖相同日期、星期、时间和人数范围的收费规则");}}
    private boolean overlap(LocalDate a,LocalDate b,LocalDate c,LocalDate d){return (a==null||d==null||!a.isAfter(d))&&(c==null||b==null||!c.isAfter(b));}
    private boolean overlap(Integer a,Integer b){return a==null||b==null||a.equals(b);}
    private boolean overlap(LocalTime a,LocalTime b,LocalTime c,LocalTime d){return (a==null||d==null||a.isBefore(d))&&(c==null||b==null||c.isBefore(b));}
    private boolean overlap(Integer a,Integer b,Integer c,Integer d){return (a==null||d==null||a<=d)&&(c==null||b==null||c<=b);}

    private long actor(AuthenticatedPrincipal p){jdbc.update("INSERT INTO bf_user(wechat_openid,nickname,status) VALUES (?,?,'ACTIVE') ON DUPLICATE KEY UPDATE nickname=VALUES(nickname)",p.databaseOpenId(),p.displayName());Long id=jdbc.queryForObject("SELECT id FROM bf_user WHERE wechat_openid=?",Long.class,p.databaseOpenId());return id==null?0:id;}
    private void audit(long actor,String action,String target,long id,String value){jdbc.update("INSERT INTO bf_audit_log(operator_id,action,target_type,target_id,detail) VALUES (?,?,?,?,JSON_OBJECT('value',?))",actor,action,target,String.valueOf(id),value);}
    private long lastId(){Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);return id==null?0:id;}
    private long count(String sql,Object...args){Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n;}
    private void unique(String sql,String message,Object...args){if(count(sql,args)>0)throw conflict(message);}
    private String status(String s){String x=StringUtils.hasText(s)?s.trim().toUpperCase(Locale.ROOT):"ACTIVE";if(!STATUSES.contains(x))throw invalid("状态仅支持ACTIVE或INACTIVE");return x;}
    private String serviceType(String s){String x=code(s,20,"服务类型");if(!SERVICE_TYPES.contains(x))throw invalid("服务类型仅支持EQUIPMENT、TEA_BREAK或ACCOMMODATION");return x;}
    private String unit(String s){String x=code(s,20,"计价单位");if(!UNITS.contains(x))throw invalid("计价单位不合法");return x;}
    private String code(String s,int max,String field){return required(s,max,field).toUpperCase(Locale.ROOT);}
    private String required(String s,int max,String field){if(!StringUtils.hasText(s))throw invalid(field+"不能为空");String x=s.trim();if(x.length()>max)throw invalid(field+"最长"+max+"字符");return x;}
    private String optional(String s,int max){return StringUtils.hasText(s)?required(s,max,"字段"):null;}
    private BusinessException invalid(String m){return new BusinessException(CommonErrorCode.INVALID_REQUEST,m);}
    private BusinessException conflict(String m){return new BusinessException(CommonErrorCode.CONFLICT,m);}
    private BusinessException notFound(String m){return new BusinessException(CommonErrorCode.NOT_FOUND,m);}

    private record RoomValue(String code,String name,String location,int capacity,String description,LocalTime open,LocalTime close,String status){}
    private record EquipmentValue(long roomId,String code,String name,int quantity,String description,String status){}
    private record OpeningValue(long roomId,int day,LocalTime start,LocalTime end,int slot,String status){}
    private record PriceValue(long roomId,String code,String name,LocalDate startDate,LocalDate endDate,Integer day,LocalTime start,LocalTime end,Integer min,Integer max,long price,int priority,String status){}
    private record ServiceValue(String code,String name,String type,String unit,long price,String description,String status){}
}
