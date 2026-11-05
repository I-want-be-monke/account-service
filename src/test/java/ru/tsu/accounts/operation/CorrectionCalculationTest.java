package ru.tsu.accounts.operation;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ru.tsu.accounts.identity.UserCommandException;

class CorrectionCalculationTest {
 private static BigDecimal value(String text){return new BigDecimal(text);}
 @Test void chargeUsesCurrentEffectForEachTarget(){
  var first=CorrectionRules.calculate(value("1000.00"),1,value("0.00"),0,value("900.00"));
  assertThat(first.delta()).isEqualByComparingTo("-100.00");
  var second=CorrectionRules.calculate(value("1000.00"),1,first.delta(),1,value("800.00"));
  assertThat(second.delta()).isEqualByComparingTo("-100.00");
  assertThat(CorrectionRules.calculate(value("1000.00"),1,value("-200.00"),2,value("0.00")).delta())
   .isEqualByComparingTo("-800.00");
 }
 @Test void paymentReversalAndValidation(){
  assertThat(CorrectionRules.calculate(value("400.00"),-1,value("0.00"),0,value("350.00")).delta())
   .isEqualByComparingTo("50.00");
  assertThatThrownBy(()->CorrectionRules.calculate(value("400.00"),-1,value("0.00"),0,value("400.00")))
   .isInstanceOf(UserCommandException.class);
  var form=new CorrectionForm();form.setTargetAmount("-1");form.setReason("причина");form.setBasis("основание");
  assertThatThrownBy(()->CorrectionRules.validate(form,java.time.LocalDate.now()))
   .isInstanceOf(UserCommandException.class);
  form.setTargetAmount("0");form.setReason("  ");
  assertThatThrownBy(()->CorrectionRules.validate(form,java.time.LocalDate.now()))
   .isInstanceOf(UserCommandException.class);
 }
}
