package org.nasedka.app;

/** Human-readable structured log entries. All counts are user-entered. */
public final class IncubationRecords {
    private IncubationRecords(){}

    public static String candling(int totalEggs,int inspected,int visible,
                                  int clear,int uncertain,int removed,String note) {
        if(totalEggs<1||inspected<1||inspected>totalEggs||visible<0||clear<0||
                uncertain<0||removed<0||visible+clear+uncertain>inspected||
                removed>inspected)
            throw new IllegalArgumentException("Проверьте количество яиц: категории не должны превышать осмотренные.");
        return "ОВОСКОПИРОВАНИЕ (запись пользователя)\n"
                +"Осмотрено: "+inspected+" из "+totalEggs+"\n"
                +"Наблюдаются признаки развития: "+visible+"\n"
                +"Светлые яйца: "+clear+"\n"
                +"Сомнительные: "+uncertain+"\n"
                +"Фактически убрано: "+removed+"\n"
                +"Остальные результаты не определены; вывод о жизнеспособности "
                +"не делается автоматически."
                +note(note);
    }

    public static String hatch(int eggs,int hatched,Integer clear,
                               Integer stopped,Integer other,String note) {
        if(eggs<1||hatched<0||hatched>eggs)
            throw new IllegalArgumentException("Число выведенных должно быть от 0 до числа яиц.");
        int allocated=hatched;
        if(clear!=null){if(clear<0)throw new IllegalArgumentException("Неверное число неоплодотворённых.");allocated+=clear;}
        if(stopped!=null){if(stopped<0)throw new IllegalArgumentException("Неверное число замерших.");allocated+=stopped;}
        if(other!=null){if(other<0)throw new IllegalArgumentException("Неверное число остальных.");allocated+=other;}
        if(allocated>eggs)throw new IllegalArgumentException("Сумма результатов больше заложенных яиц.");
        return "РЕЗУЛЬТАТ ВЫВОДА (запись пользователя)\n"
                +"Заложено: "+eggs+"\nВывелось: "+hatched
                +"\nНеоплодотворённых (по наблюдению): "+(clear==null?"не записано":clear)
                +"\nОстановка развития (по наблюдению): "+(stopped==null?"не записано":stopped)
                +"\nПрочие причины: "+(other==null?"не записано":other)
                +"\nНе распределено по указанным категориям: "+(eggs-allocated)
                +"\nПричины потерь не диагностируются приложением."
                +note(note);
    }
    private static String note(String text) {
        String n=text==null?"":text.trim();
        if(n.length()>500)throw new IllegalArgumentException("Заметка — не более 500 символов.");
        return n.isEmpty()?"":"\nКомментарий: "+n;
    }
}
