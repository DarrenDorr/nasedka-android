package org.nasedka.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Pure validation and accounting rules; no Android dependencies. */
public final class FarmLogic {
    private FarmLogic() {}

    public static String required(String input, String field, int max) {
        String value=input==null?"":input.trim();
        if(value.isEmpty() || value.length()>max)
            throw new IllegalArgumentException(field+": укажите от 1 до "+max+" символов.");
        return value;
    }
    public static int count(int n, int maximum) {
        if(n<0 || n>maximum)throw new IllegalArgumentException("Количество должно быть от 0 до "+maximum+".");
        return n;
    }
    public static long cents(String input) {
        String s=required(input,"Сумма",40).replace(',', '.');
        try {
            BigDecimal amount=new BigDecimal(s);
            if(amount.signum()<=0 || amount.compareTo(new BigDecimal("9999999999.99"))>0)
                throw new IllegalArgumentException("Сумма должна быть больше нуля.");
            return amount.movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException|NumberFormatException e) {
            throw new IllegalArgumentException("Сумма: укажите рубли и не более двух знаков после запятой.");
        }
    }
    public static String rub(long cents) {
        BigDecimal amount=BigDecimal.valueOf(cents,2);
        return amount.setScale(2,RoundingMode.UNNECESSARY).toPlainString()+" ₽";
    }
    public static double quantity(String input) {
        String s=required(input,"Количество",30).replace(',','.');
        try {
            double value=Double.parseDouble(s);
            if(!Double.isFinite(value) || value<=0 || value>10000000)
                throw new IllegalArgumentException("Количество должно быть положительным.");
            return Math.round(value*1000d)/1000d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Количество: введите положительное число.");
        }
    }
    public static double newBalance(double current, double change) {
        double result=Math.round((current+change)*1000d)/1000d;
        if(!Double.isFinite(change) || result < -0.000001)
            throw new IllegalArgumentException("Недостаточный остаток, нельзя списать больше имеющегося.");
        return result;
    }
    public static int newHeadcount(int current, int change) {
        long v=(long)current+change;
        if(v<0 || v>10000000)throw new IllegalArgumentException("Недопустимый остаток поголовья.");
        return (int)v;
    }
    public static long percentagePart(int numerator,int denominator) {
        if(denominator<=0 || numerator<0 || numerator>denominator)
            throw new IllegalArgumentException("Неверные данные вывода.");
        return Math.round((100.0*numerator)/denominator);
    }
    public static LocalDate dueDate(long start,int days,ZoneId zone) {
        if(start<=0||days<3||days>60)throw new IllegalArgumentException("Неправильная дата или срок.");
        return java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().plusDays(days);
    }
}
