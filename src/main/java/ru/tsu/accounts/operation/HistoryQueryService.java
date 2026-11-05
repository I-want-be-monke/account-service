package ru.tsu.accounts.operation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.AccountAccessPolicy;
import ru.tsu.accounts.identity.UserCommandException;

@Service
public class HistoryQueryService {
 private static final Set<String> KINDS=Set.of("CHARGE","PAYMENT","CORRECTION");
 private static final String FIELDS="""
   select o.id,o.account_id,o.kind,
    case when exists(select 1 from financial_operation c
      where c.corrects_operation_id=o.id and c.storage_status='POSTED')
      then 'CORRECTED' else 'POSTED' end as display_status,
    o.operation_date,o.amount,o.delta,o.period_start,o.basis,o.payment_method,
    o.external_number,o.posted_at,o.corrects_operation_id,o.target_amount,o.correction_reason
   from financial_operation o
   """;
 private final NamedParameterJdbcTemplate jdbc;
 private final AccountAccessPolicy access;
 public HistoryQueryService(NamedParameterJdbcTemplate jdbc,AccountAccessPolicy access){
  this.jdbc=jdbc;this.access=access;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public Page<OperationView> search(UUID accountId,String from,String to,String kind,int page,int size){
  var account=access.require(accountId);
  if(page<0||page>100000||size<1||size>100)throw bad("Страница должна быть неотрицательной, размер — от 1 до 100.");
  LocalDate start=date(from),end=date(to);
  if(start!=null&&start.isBefore(account.getHistoryStartOn())
     ||end!=null&&end.isBefore(account.getHistoryStartOn())
     ||start!=null&&end!=null&&start.isAfter(end))
   throw bad("Проверьте период: даты не раньше начала истории и начало не позже конца.");
  String chosen=kind==null?"":kind.strip();
  if(!chosen.isEmpty()&&!KINDS.contains(chosen))throw bad("Неизвестный вид операции.");
  StringBuilder where=new StringBuilder(" where o.account_id=:accountId and o.storage_status='POSTED'");
  var args=new MapSqlParameterSource("accountId",accountId);
  if(start!=null){where.append(" and o.operation_date>=:fromDate");args.addValue("fromDate",start);}
  if(end!=null){where.append(" and o.operation_date<=:toDate");args.addValue("toDate",end);}
  if(!chosen.isEmpty()){where.append(" and o.kind=:kind");args.addValue("kind",chosen);}
  Long count=jdbc.queryForObject("select count(*) from financial_operation o"+where,args,Long.class);
  args.addValue("limit",size).addValue("offset",(long)page*size);
  List<OperationView> rows=jdbc.query(FIELDS+where+
   " order by o.operation_date desc,o.posted_at desc,o.id desc limit :limit offset :offset",args,
   (rs,n)->view(rs));
  return new PageImpl<>(rows,PageRequest.of(page,size),count==null?0:count);
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public OperationView detail(UUID id){
  List<UUID> accounts=jdbc.query("select account_id from financial_operation where id=:id",
   new MapSqlParameterSource("id",id),(rs,n)->rs.getObject(1,UUID.class));
  if(accounts.isEmpty())throw OperationService.missing();
  access.require(accounts.getFirst());
  List<OperationView> rows=jdbc.query(FIELDS+
   " where o.id=:id and o.storage_status='POSTED'",new MapSqlParameterSource("id",id),
   (rs,n)->view(rs));
  if(rows.isEmpty())throw OperationService.forbidden();
  return rows.getFirst();
 }
 private static LocalDate date(String text){
  if(text==null||text.isBlank())return null;
  if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad("Дата должна быть в формате ГГГГ-ММ-ДД.");
  try{return LocalDate.parse(text);}
  catch(DateTimeParseException e){throw bad("Некорректная календарная дата.");}
 }
 private static OperationView view(ResultSet rs) throws SQLException {
  var period=rs.getDate("period_start");
  return new OperationView(rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),
   rs.getString("kind"),rs.getString("display_status"),rs.getDate("operation_date").toLocalDate(),
   rs.getBigDecimal("amount"),rs.getBigDecimal("delta"),period==null?null:period.toLocalDate(),
   rs.getString("basis"),rs.getString("payment_method"),rs.getString("external_number"),
   rs.getTimestamp("posted_at").toInstant(),rs.getObject("corrects_operation_id",UUID.class),
   rs.getBigDecimal("target_amount"),rs.getString("correction_reason"));
 }
 private static UserCommandException bad(String message){
  return new UserCommandException(HttpStatus.BAD_REQUEST,"HISTORY_VALIDATION",message);
 }
}
