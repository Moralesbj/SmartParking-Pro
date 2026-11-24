package com.smartparking.common.api;

import com.smartparking.common.audit.AuditService;
import com.smartparking.common.errors.ApiException;
import com.smartparking.common.tenancy.*;
import com.smartparking.modules.vehicles.domain.Plate;
import com.smartparking.modules.vehicles.domain.VehicleCategory;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared persistence for small reference catalogs. Operational transitions never go through CRUD. */
@Service
public class CatalogService {
    public record Field(String name, String type, boolean required, List<String> options) {
        public Field(String name,String type,boolean required,String... options) { this(name,type,required,List.of(options)); }
    }
    public record Catalog(String table, boolean branch, boolean operatorWrite, List<Field> fields) {}
    private static Field f(String n,String t,boolean r,String... options) { return new Field(n,t,r,options); }
    public static final Map<String,Catalog> CATALOGS=Map.ofEntries(
        Map.entry("branches",new Catalog("branches",false,false,List.of(f("code","text",true),f("name","text",true),f("address","text",true),f("timezone","text",true)))),
        Map.entry("customers",new Catalog("customers",false,true,List.of(f("name","text",true),f("document_number","text",true),f("email","email",false),f("phone","text",false),f("consent","boolean",true)))),
        Map.entry("vehicles",new Catalog("vehicles",false,true,List.of(f("plate","text",true),f("country_code","select",true,"CO"),f("type","select",true,VehicleCategory.names()),f("customer_id","uuid",false),f("brand","text",false)))),
        Map.entry("zones",new Catalog("parking_zones",true,false,List.of(f("name","text",true),f("level","text",true)))),
        Map.entry("spaces",new Catalog("parking_spaces",true,false,List.of(f("code","text",true),f("zone_id","uuid",false),f("type","select",true,VehicleCategory.names()),f("feature","select",false,"STANDARD","EV_CHARGING","ACCESSIBLE"),f("status","select",true,"AVAILABLE","BLOCKED","MAINTENANCE")))),
        Map.entry("subscription-plans",new Catalog("subscription_plans",true,false,List.of(f("name","text",true),f("vehicle_type","select",true,VehicleCategory.names()),f("price","money",false),f("duration_days","integer",true),f("allowed_days","text",false),f("allowed_start","time",false),f("allowed_end","time",false),f("grace_days","integer",false)))),
        Map.entry("devices",new Catalog("devices",true,false,List.of(f("name","text",true),f("type","select",true,"CAMERA","BARRIER","SENSOR","READER")))),
        Map.entry("settings",new Catalog("branch_settings",true,false,List.of(f("key","text",true),f("value","text",true))))
    );
    private final JdbcClient db; private final ScopeService scopes; private final AuditService audit;
    public CatalogService(JdbcClient db,ScopeService scopes,AuditService audit) { this.db=db; this.scopes=scopes; this.audit=audit; }
    public Catalog definition(String key) { var c=CATALOGS.get(key); if(c==null) throw ApiException.notFound(); return c; }
    public Map<String,Object> list(String key,int page,int size,String search) {
        var c=definition(key); var scope=scopes.staff();
        int limit=Math.clamp(size,1,100), offset=Math.max(page,0)*limit;
        String where="organization_id=:org"+(c.branch()?" AND branch_id=:branch":"");
        var fields=c.fields().stream().filter(f -> Set.of("text","email","select").contains(f.type())).map(f -> "coalesce("+f.name()+"::text,'')").toList();
        if(!fields.isEmpty()) where+=" AND concat_ws(' ',"+String.join(",",fields)+") ILIKE :search";
        Map<String,Object> params=new HashMap<>(); params.put("org",scope.organizationId()); params.put("branch",scope.branchId()); params.put("search","%"+search+"%");
        long total=db.sql("SELECT count(*) FROM "+c.table()+" WHERE "+where).params(params).query(Long.class).single();
        params.put("limit",limit); params.put("offset",offset);
        var rows=db.sql("SELECT * FROM "+c.table()+" WHERE "+where+" ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset").params(params).query().listOfRows();
        return Map.of("items",rows,"total",total,"page",Math.max(page,0),"size",limit);
    }
    @Transactional public Map<String,Object> save(String key,UUID id,Map<String,Object> input) {
        var c=definition(key); var scope=c.operatorWrite()?scopes.staff():scopes.admin();
        if(input.keySet().stream().anyMatch(k -> c.fields().stream().noneMatch(f -> f.name().equals(k)))) throw new ApiException(422,"Campo no permitido");
        Map<String,Object> values=new LinkedHashMap<>();
        for(var field:c.fields()) {
            Object raw=input.get(field.name()); String value=raw==null?"":raw.toString().trim();
            if(field.required() && value.isEmpty()) throw new ApiException(422,"Campo requerido: "+field.name());
            Object parsed=value.isEmpty()?null:switch(field.type()) {
                case "uuid" -> UUID.fromString(value);
                case "money" -> { var money=new BigDecimal(value); if(money.signum()<0 || money.scale()>2) throw new ApiException(422,"Importe inválido"); yield money; }
                case "integer" -> { int number=Integer.parseInt(value); if(number<0) throw new ApiException(422,"Número inválido"); yield number; }
                case "boolean" -> { if(!Set.of("true","false").contains(value)) throw new ApiException(422,"Valor booleano inválido"); yield Boolean.parseBoolean(value); }
                case "time" -> java.sql.Time.valueOf(java.time.LocalTime.parse(value.length()==5?value+":00":value));
                case "date" -> java.sql.Date.valueOf(java.time.LocalDate.parse(value));
                case "select" -> { if(!field.options().contains(value)) throw new ApiException(422,"Opción inválida"); yield value; }
                default -> { if(value.length()> (key.equals("settings")?2000:254)) throw new ApiException(422,"Texto demasiado largo"); yield value; }
            };
            // Empty optional text stays '' (NOT NULL DEFAULT ''); empty typed values become NULL.
            if(parsed!=null || !Set.of("text","email","select").contains(field.type())) values.put(field.name(),parsed);
            else values.put(field.name(),"");
        }
        if(key.equals("vehicles")) values.put("plate",Plate.normalize((String)values.get("plate"),(String)values.get("country_code"),(String)values.get("type")));
        if(key.equals("branches")) ZoneId.of((String)values.get("timezone"));
        if(key.equals("spaces") && "".equals(values.get("feature"))) values.put("feature","STANDARD");
        if(key.equals("subscription-plans")) {
            String days=((String)values.get("allowed_days")).replaceAll("[\\s,]","");
            if(days.isEmpty()) days="1234567";
            if(!days.matches("[1-7]{1,7}") || days.chars().distinct().count()!=days.length()) throw new ApiException(422,"Días permitidos: dígitos 1 (lunes) a 7 (domingo), sin repetir");
            values.put("allowed_days",days);
            if((values.get("allowed_start")==null)!=(values.get("allowed_end")==null)) throw new ApiException(422,"Indique hora de inicio y de fin, o ninguna para todo el día");
            if(values.get("allowed_start")!=null && values.get("allowed_start").equals(values.get("allowed_end"))) throw new ApiException(422,"El horario permitido no puede iniciar y terminar a la misma hora");
            if(values.get("grace_days")==null) values.put("grace_days",0);
            if((int)values.get("grace_days")>31) throw new ApiException(422,"Días de gracia entre 0 y 31");
            int duration=(int)values.get("duration_days"); if(duration<1 || duration>366) throw new ApiException(422,"Duración entre 1 y 366 días");
        }
        UUID ref=id==null?UUID.randomUUID():id;
        Map<String,Object> params=new HashMap<>(values); params.put("id",ref); params.put("org",scope.organizationId()); params.put("branch",scope.branchId());
        if(id==null) {
            String columns="id,organization_id"+(c.branch()?",branch_id":"")+","+String.join(",",values.keySet());
            String placeholders=":id,:org"+(c.branch()?",:branch":"")+","+String.join(",",values.keySet().stream().map(v -> ":"+v).toList());
            db.sql("INSERT INTO "+c.table()+"("+columns+") VALUES("+placeholders+")").params(params).update();
        } else {
            String where="id=:id AND organization_id=:org"+(c.branch()?" AND branch_id=:branch":"");
            if(key.equals("spaces")) {
                db.sql("SELECT id FROM branches WHERE id=:branch AND organization_id=:org FOR UPDATE").params(params).query(UUID.class).single();
                long used=db.sql("SELECT count(*) FROM parking_sessions WHERE space_id=:id AND status='OPEN'").param("id",id).query(Long.class).single();
                long booked=db.sql("SELECT count(*) FROM reservations WHERE space_id=:id AND status IN('CONFIRMED','CHECKED_IN') AND ends_at>now()").param("id",id).query(Long.class).single();
                if(used+booked>0) throw ApiException.conflict("No se modifica un cupo ocupado o reservado");
            }
            String assignments=String.join(",",values.keySet().stream().map(v -> v+"=:"+v).toList());
            if(db.sql("UPDATE "+c.table()+" SET "+assignments+",updated_at=now() WHERE "+where).params(params).update()==0) throw ApiException.notFound();
        }
        audit.record(scope,id==null?"CREATE":"UPDATE",key,ref);
        return Map.of("id",ref);
    }
    @Transactional public void deactivate(String key,UUID id) {
        var c=definition(key); var scope=scopes.admin();
        if(key.equals("settings")) throw new ApiException(422,"Modifique el valor de configuración");
        if(key.equals("spaces") || key.equals("zones") || key.equals("branches") || key.equals("vehicles")) throw new ApiException(422,"Este catálogo requiere revisión de dependencias para desactivar; cambie estado cuando corresponda");
        int updated=db.sql("UPDATE "+c.table()+" SET active=false,updated_at=now() WHERE id=:id AND organization_id=:org"+(c.branch()?" AND branch_id=:branch":""))
            .param("id",id).param("org",scope.organizationId()).param("branch",scope.branchId()).update();
        if(updated==0) throw ApiException.notFound(); audit.record(scope,"DEACTIVATE",key,id);
    }
}
