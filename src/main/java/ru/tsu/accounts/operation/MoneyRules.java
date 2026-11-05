package ru.tsu.accounts.operation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import ru.tsu.accounts.identity.UserCommandException;
public final class MoneyRules {
 private static final BigDecimal MAX=new BigDecimal("99999999999999999.99");
 private MoneyRules() {}
 public record DraftInput(String kind,LocalDate operationDate,BigDecimal amount,LocalDate periodStart,
                          String basis,String paymentMethod,String externalSource,String externalNumber,String externalNumberKey) {}
 public static BigDecimal amount(String raw) {
  if(raw==null || !raw.strip().matches("[0-9]{1,17}([.,][0-9]{1,2})?")) throw bad("Укажите положительную сумму до 17 целых и 2 дробных знаков.");
  BigDecimal value=new BigDecimal(raw.strip().replace(',','.'));
  if(value.signum()<=0 || value.compareTo(MAX)>0) throw bad("Сумма должна быть положительной и в пределах допустимого.");
  return value.setScale(2);
 }
 public static BigDecimal nextBalance(BigDecimal current,BigDecimal delta) {
  BigDecimal next=current.add(delta);
  if(next.abs().compareTo(MAX)>0) throw bad("Новое сальдо превышает допустимый предел.");
  return next.setScale(2);
 }
 public static DraftInput validate(OperationForm form,LocalDate historyStart) {
  String kind=form.getKind();
  if(!"CHARGE".equals(kind) && !"PAYMENT".equals(kind)) throw bad("Неизвестный вид операции.");
  LocalDate date=date(form.getOperationDate(),"дату операции");
  if(date.isBefore(historyStart) || date.isAfter(LocalDate.now(ZoneId.of("Europe/Moscow"))))
   throw bad("Дата операции должна входить в доступную историю и не быть будущей.");
  String basis=required(form.getBasis(),500,"Основание");
  BigDecimal amount=amount(form.getAmount());
  String number=optional(form.getExternalNumber(),100,"Номер документа");
  String key=number==null?null:number.toLowerCase(Locale.ROOT);
  LocalDate period=null; String method=null;
  if("CHARGE".equals(kind)) {
   period=date(form.getPeriodStart(),"расчетный период");
   if(period.getDayOfMonth()!=1 || period.isAfter(date)) throw bad("Расчетный период начинается первого числа и не позже даты операции.");
  } else {
   if(number==null) throw bad("Для платежа укажите номер документа.");
   method=form.getPaymentMethod();
   if(!"MANUAL_TRANSFER".equals(method) && !"CASH_REGISTRATION".equals(method))
    throw bad("Выберите разрешенный способ регистрации платежа.");
  }
  return new DraftInput(kind,date,amount,period,basis,method,number==null?null:"MANUAL",number,key);
 }
 private static LocalDate date(String raw,String label){
  try{return LocalDate.parse(raw);}catch(Exception ex){throw bad("Укажите корректную "+label+".");}
 }
 private static String required(String raw,int max,String label){
  String clean=optional(raw,max,label); if(clean==null) throw bad(label+" обязательно."); return clean;
 }
 private static String optional(String raw,int max,String label){
  if(raw==null||raw.strip().isEmpty())return null;
  String clean=raw.strip();
  if(clean.length()>max||clean.chars().anyMatch(Character::isISOControl))
   throw bad(label+" содержит недопустимые символы или слишком длинное.");
  return clean;
 }
 private static UserCommandException bad(String message){
  return new UserCommandException(HttpStatus.BAD_REQUEST,"OPERATION_VALIDATION",message);
 }
}
