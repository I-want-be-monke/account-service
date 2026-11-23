package ru.tsu.accounts.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import ru.tsu.accounts.identity.UserCommandException;

public final class CorrectionRules {
 private static final BigDecimal MAX=new BigDecimal("99999999999999999.99");
 private CorrectionRules() {}
 public record Input(LocalDate date,BigDecimal target,String reason,String basis) {}
 public record Effect(BigDecimal delta,long correctionCount) {}

 public static Input validate(CorrectionForm form,LocalDate historyStart) {
  LocalDate today=LocalDate.now(ZoneId.of("Europe/Moscow"));
  LocalDate date;
  try {date=LocalDate.parse(form.getOperationDate());}
  catch(Exception ex){throw bad("Некорректная дата исправления.","operationDate");}
  if(!date.equals(today)||date.isBefore(historyStart))throw bad("Исправление проводится текущей датой счета.","operationDate");
  String target=form.getTargetAmount()==null?"":form.getTargetAmount().strip();
  if(!target.matches("[0-9]{1,17}([.,][0-9]{1,2})?"))throw bad("Укажите неотрицательную целевую сумму с двумя знаками после запятой.","targetAmount");
  BigDecimal amount=new BigDecimal(target.replace(',','.')).setScale(2);
  if(amount.compareTo(MAX)>0)throw bad("Целевая сумма выходит за допустимый предел.","targetAmount");
  String reason=required(form.getReason(),1000,"Причина","reason");
  String basis=required(form.getBasis(),500,"Основание","basis");
  return new Input(date,amount,reason,basis);
 }
 public static Effect calculate(BigDecimal originalAmount,int originalSign,BigDecimal postedCorrectionEffect,
                                long correctionCount,BigDecimal target) {
  if(originalSign!=1&&originalSign!=-1||target.signum()<0||target.compareTo(MAX)>0)
   throw bad("Недопустимые параметры исправления.");
  BigDecimal current=originalAmount.multiply(BigDecimal.valueOf(originalSign)).add(postedCorrectionEffect);
  BigDecimal desired=target.multiply(BigDecimal.valueOf(originalSign));
  BigDecimal delta=desired.subtract(current);
  if(delta.signum()==0)throw bad("Целевая сумма уже действует. Нулевое исправление не создается.","targetAmount");
  if(delta.abs().compareTo(MAX)>0)throw bad("Разница выходит за допустимый предел.","targetAmount");
  return new Effect(delta.setScale(2),correctionCount);
 }
 private static String required(String raw,int max,String label,String field){
  if(raw==null||raw.strip().isEmpty())throw bad(label+" обязательна.",field);
  String value=raw.strip();
  if(value.length()>max||value.chars().anyMatch(Character::isISOControl))throw bad(label+" содержит недопустимые символы или слишком длинная.",field);
  return value;
 }
 private static UserCommandException bad(String message){
  return bad(message,null);
 }
 private static UserCommandException bad(String message,String field){
  return new UserCommandException(HttpStatus.BAD_REQUEST,"CORRECTION_VALIDATION",message,field);
 }
}
