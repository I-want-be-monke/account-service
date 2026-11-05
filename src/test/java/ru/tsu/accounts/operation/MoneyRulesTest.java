package ru.tsu.accounts.operation;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class MoneyRulesTest {
 @Test void decimalsRejectInvalidScaleAndOverflow(){
  assertThat(MoneyRules.amount("0.01").add(MoneyRules.amount("0.02"))).isEqualByComparingTo("0.03");
  assertThat(MoneyRules.amount("1000,50")).isEqualByComparingTo("1000.50");
  for(String input:new String[]{"0","-1","0.001","1000,500","100000000000000000","NaN","1.2.3"}){
   assertThatThrownBy(()->MoneyRules.amount(input)).as(input).isInstanceOf(RuntimeException.class);
  }
  assertThat(MoneyRules.nextBalance(new BigDecimal("400.00"),new BigDecimal("-500.00")))
   .isEqualByComparingTo("-100.00");
  assertThatThrownBy(()->MoneyRules.nextBalance(new BigDecimal("99999999999999999.99"),
   new BigDecimal("0.01"))).isInstanceOf(RuntimeException.class);
 }
}
