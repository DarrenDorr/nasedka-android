package org.nasedka.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Plain functional UI for the farm ledger. Future visual redesign can replace this
 * class without changing saved data, audit movements or the existing incubation UI.
 */
public final class FarmScreens {
    public static final int FLOCK=1, FEED=2, FINANCE=3, REPORTS=4,
            NESTS=5, EGGS=6, TASKS=7;
    private static final DateTimeFormatter DATE=
            DateTimeFormatter.ofPattern("dd.MM.yyyy",new Locale("ru"));
    private static final DateTimeFormatter TIME=
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",new Locale("ru"));
    private final Activity activity;
    private final LinearLayout body;
    private final Runnable refresh,back;
    private final IntConsumer navigate;
    private final ZoneId zone=ZoneId.systemDefault();

    public FarmScreens(Activity activity,LinearLayout body,Runnable refresh,
                       Runnable back,IntConsumer navigate) {
        this.activity=activity;this.body=body;this.refresh=refresh;
        this.back=back;this.navigate=navigate;
    }

    private TextView txt(String message,int size,boolean strong) {
        return Ui.text(activity,message,size,strong?Ui.DARK:Ui.MUTED,strong);
    }
    private TextView button(String label,boolean filled,Runnable action) {
        TextView item=Ui.button(activity,label,filled);
        item.setOnClickListener(v->action.run());
        return item;
    }
    private void add(LinearLayout parent,View view,int space) {
        Ui.addMargin(parent,view,activity,space);
    }
    private void paragraph(LinearLayout parent,String text) { add(parent,txt(text,13,false),9); }
    private void title(String name,String text) {
        add(body,txt(name,26,true),6);if(text!=null)paragraph(body,text);
    }
    private void heading(String text) {add(body,txt(text,18,true),12);}
    private void fail(Exception e) {
        String text=e.getMessage()==null?"Проверьте введённые данные.":e.getMessage();
        new AlertDialog.Builder(activity).setTitle("Не удалось сохранить")
                .setMessage(text).setPositiveButton("Понятно",null).show();
    }
    private LinearLayout form() {
        LinearLayout l=Ui.vertical(activity);
        l.setPadding(Ui.dp(activity,18),Ui.dp(activity,12),
                Ui.dp(activity,18),Ui.dp(activity,8));return l;
    }
    private EditText field(LinearLayout form,String label,String hint,String value,boolean number) {
        Ui.label(form,activity,label);
        EditText e=Ui.field(activity,hint,value,number);
        add(form,e,12);return e;
    }
    private Spinner choice(LinearLayout form,String label,String[] items) {
        Ui.label(form,activity,label);
        Spinner spinner=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item,items);
        spinner.setAdapter(adapter);
        add(form,spinner,12);return spinner;
    }
    private interface Submit {void save();}
    private void dialog(String title,LinearLayout content,String buttonText,Submit callback) {
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
                .setNegativeButton("Отмена",null)
                .setPositiveButton(buttonText,null).create();
        d.setOnShowListener(ignored->d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v->{
                    try{callback.save();d.dismiss();refresh.run();}
                    catch(Exception e){fail(e);}
                }));
        d.show();
    }
    private String read(EditText field){return field.getText().toString().trim();}
    private int intValue(EditText e){try{return Integer.parseInt(read(e));}
        catch(NumberFormatException ex){throw new IllegalArgumentException("Введите целое количество.");}}
    private String when(long millis){return Instant.ofEpochMilli(millis).atZone(zone).format(TIME);}
    private String date(long millis){return Instant.ofEpochMilli(millis).atZone(zone).format(DATE);}
    private String num(double amount){
        return String.format(new Locale("ru"),"%.3f",amount)
                .replaceAll("0+$","").replaceAll("[,.]$","");
    }
    private void item(String label,String details,Runnable open) {
        LinearLayout card=Ui.card(activity);
        add(card,txt(label,17,true),8);
        if(details!=null)paragraph(card,details);
        if(open!=null)card.addView(button("Открыть",false,open));
        add(body,card,9);
    }
    public void appendHome() {
        heading("Моё хозяйство");
        try(FarmDb db=new FarmDb(activity)) {
            List<FarmDb.Flock> birds=db.flocks();
            long now=System.currentTimeMillis();
            long month=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                    .withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long[] totals=db.totals(month,Long.MAX_VALUE);
            item("Поголовье · "+db.totalBirds()+" птиц",
                    "Групп: "+birds.size()+". Возраст, поступления, выбытия и история изменений.",
                    ()->navigate.accept(FLOCK));
            item("Инкубация под наседкой · "+db.openNests()+" активных",
                    "Гнёзда, наблюдения, ожидаемый и фактический вывод.",
                    ()->navigate.accept(NESTS));
            item("Корма и запасы · "+db.feeds().size()+" позиций",
                    "Поступления, расход и фактический остаток.",()->navigate.accept(FEED));
            item("Доходы и расходы",
                    "Этот месяц: доходы "+FarmLogic.rub(totals[0])
                            +", расходы "+FarmLogic.rub(totals[1])+".",()->navigate.accept(FINANCE));
            item("Сбор яиц","Записи по группам птицы, статистика.",()->navigate.accept(EGGS));
            item("Задачи по хозяйству · "+db.tasks(true).size()+" открытых",
                    "Список действий с отметкой выполнения.",()->navigate.accept(TASKS));
            item("Отчёты","Финансы, поголовье, запасы, яйценоскость, результаты вывода.",
                    ()->navigate.accept(REPORTS));
        }
    }
    public void render(int page) {
        add(body,button(page==NESTS?"← К инкубации":"← Моё хозяйство",false,back),14);
        switch(page) {
            case FLOCK:flock();break;
            case FEED:feed();break;
            case FINANCE:finance();break;
            case REPORTS:reports();break;
            case NESTS:nests();break;
            case EGGS:eggs();break;
            case TASKS:tasks();break;
            default:title("Моё хозяйство",null);appendHome();
        }
    }

    private void flock() {
        title("Поголовье","Учёт групп птицы, движение и остаток. Любое изменение сохраняется в истории.");
        add(body,button("+ Новая группа птицы",true,this::addFlock),13);
        try(FarmDb db=new FarmDb(activity)) {
            List<FarmDb.Flock> items=db.flocks();
            if(items.isEmpty())paragraph(body,"Пока нет групп. Добавьте птицу и её исходное количество.");
            for(FarmDb.Flock f:items){
                LinearLayout card=Ui.card(activity);
                add(card,txt(f.name+" · "+f.count+" птиц",18,true),7);
                paragraph(card,f.species+(f.breed.isEmpty()?"":" · "+f.breed)
                        +(f.location.isEmpty()?"":" · "+f.location));
                add(card,button("Приход / выбытие",false,()->flockMovement(f)),8);
                card.addView(button("История движения",false,()->movementHistory(f.name,dbFlockMoves(f.id))));
                add(body,card,11);
            }
        }
    }
    private List<FarmDb.Movement> dbFlockMoves(long id) {
        try(FarmDb db=new FarmDb(activity)){return db.flockMoves(id,200);}
    }
    private void addFlock() {
        LinearLayout f=form();
        EditText name=field(f,"Название группы","Например, Несушки-2026","",false);
        Spinner species=choice(f,"Вид птицы",Batch.SPECIES);
        EditText breed=field(f,"Порода (необязательно)","Например, Ломан Браун","",false);
        EditText place=field(f,"Место содержания","Курятник","",false);
        EditText count=field(f,"Сколько сейчас птиц","0","0",true);
        dialog("Новая группа",f,"Создать",()->{
            try(FarmDb db=new FarmDb(activity)){
                db.addFlock(read(name),species.getSelectedItem().toString(),
                        read(breed),read(place),intValue(count));
            }
        });
    }
    private void flockMovement(FarmDb.Flock group) {
        LinearLayout f=form();
        Spinner type=choice(f,"Что произошло",new String[]{
                "Поступление / покупка","Вывод / выращивание","Перевод в группу",
                "Продажа / передача","Падёж / потеря","Корректировка: прибавить",
                "Корректировка: убавить"});
        EditText count=field(f,"Количество птиц","1","1",true);
        EditText note=field(f,"Комментарий / основание","Например, передача в другую группу","",false);
        dialog("Движение: "+group.name,f,"Записать",()->{
            int n=intValue(count);if(n<=0)throw new IllegalArgumentException("Укажите больше нуля.");
            String reason=type.getSelectedItem().toString();
            boolean out=type.getSelectedItemPosition()==3||type.getSelectedItemPosition()==4
                    ||type.getSelectedItemPosition()==6;
            try(FarmDb db=new FarmDb(activity)){db.moveFlock(group.id,out?-n:n,reason,read(note));}
        });
    }

    private void feed() {
        title("Корма и запасы","Покупки и расход учитываются отдельно. Стоимость покупки может сразу попасть в финансы.");
        add(body,button("+ Добавить вид корма",true,this::addFeed),13);
        try(FarmDb db=new FarmDb(activity)) {
            List<FarmDb.Feed> items=db.feeds();
            if(items.isEmpty())paragraph(body,"Добавьте корм, например комбикорм (кг).");
            for(FarmDb.Feed feed:items){
                LinearLayout card=Ui.card(activity);
                add(card,txt(feed.name+" · "+num(feed.stock)+" "+feed.unit,17,true),8);
                add(card,button("+ Поступление / покупка",false,()->changeFeed(feed,true)),8);
                add(card,button("− Использовано / списано",false,()->changeFeed(feed,false)),8);
                card.addView(button("История расхода",false,()->{
                    try(FarmDb fresh=new FarmDb(activity)){
                        movementHistory(feed.name,fresh.feedMoves(feed.id,200));
                    }
                }));
                add(body,card,11);
            }
        }
    }
    private void addFeed() {
        LinearLayout f=form();
        EditText name=field(f,"Название","Комбикорм","",false);
        Spinner unit=choice(f,"Единица учёта",new String[]{"кг","л","шт","мешок","упаковка"});
        dialog("Новый корм",f,"Сохранить",()->{
            try(FarmDb db=new FarmDb(activity)){
                db.addFeed(read(name),unit.getSelectedItem().toString());
            }
        });
    }
    private void changeFeed(FarmDb.Feed feed,boolean incoming) {
        LinearLayout f=form();
        EditText qty=field(f,"Количество, "+feed.unit,"Например, 5","",true);
        EditText cost=incoming?field(f,
                "Общая стоимость покупки, ₽ (0 или пусто, если неизвестна)",
                "Например, 450,00","",true):null;
        EditText note=field(f,"Комментарий","Например, корм для молодняка","",false);
        dialog(incoming?"Поступление: "+feed.name:"Расход: "+feed.name,f,"Записать",()->{
            double value=FarmLogic.quantity(read(qty));
            long cents=(cost==null||read(cost).isEmpty()||read(cost).equals("0"))
                    ?0:FarmLogic.cents(read(cost));
            try(FarmDb db=new FarmDb(activity)){
                db.moveFeed(feed.id,incoming?value:-value,
                        incoming?"Поступление":"Использовано",cents,read(note));
            }
        });
    }
    private void movementHistory(String title,List<FarmDb.Movement> rows) {
        LinearLayout content=form();
        if(rows.isEmpty())paragraph(content,"Записей пока нет.");
        for(FarmDb.Movement m:rows) {
            add(content,txt(when(m.at)+" · "+m.kind+" · "+num(m.delta),14,true),3);
            if(!m.note.isEmpty())paragraph(content,m.note);
            Ui.space(content,activity,6);
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("История: "+title)
                .setView(scroll).setPositiveButton("Закрыть",null).show();
    }

    private void finance() {
        title("Доходы и расходы","Денежные операции учитываются в рублях. Закупка корма с ценой автоматически добавляет расход.");
        add(body,button("+ Добавить доход",true,()->addMoney(true)),9);
        add(body,button("− Добавить расход",false,()->addMoney(false)),14);
        try(FarmDb db=new FarmDb(activity)) {
            long[] sums=db.totals(0,Long.MAX_VALUE);
            item("За всё время",
                    "Доходы: "+FarmLogic.rub(sums[0])+" · Расходы: "+
                            FarmLogic.rub(sums[1])+" · Итог: "+FarmLogic.rub(sums[0]-sums[1]),null);
            heading("История операций");
            for(FarmDb.Money m:db.money(150)) {
                item((m.type.equals("Доход")?"+ ":"− ")+FarmLogic.rub(m.cents)
                                +" · "+m.title,
                        when(m.at)+" · "+m.category+
                                (m.note.isEmpty()?"":"\n"+m.note),null);
            }
        }
    }
    private void addMoney(boolean income) {
        LinearLayout f=form();
        EditText sum=field(f,"Сумма, ₽","Например, 250,50","",true);
        Spinner category=choice(f,"Категория",income?
                new String[]{"Продажа яиц","Продажа молодняка","Продажа птицы","Другое"}:
                new String[]{"Корма","Оборудование","Электроэнергия",
                        "Ветеринария","Покупка птицы","Транспорт","Другое"});
        EditText title=field(f,"За что получено / потрачено","Назначение операции","",false);
        EditText note=field(f,"Примечание","Необязательно","",false);
        dialog(income?"Запись дохода":"Запись расхода",f,"Сохранить",()->{
            try(FarmDb db=new FarmDb(activity)) {
                db.addMoney(income?"Доход":"Расход",category.getSelectedItem().toString(),
                        FarmLogic.cents(read(sum)),read(title),read(note));
            }
        });
    }

    private void nests() {
        title("Инкубация под наседкой",
                "Отдельный дневник: здесь не используются искусственные режимы инкубатора. "
                +"Наблюдения и состояние птицы заносятся вручную.");
        add(body,button("+ Новая закладка под птицей",true,this::addNest),13);
        try(FarmDb db=new FarmDb(activity)) {
            List<FarmDb.Nest> rows=db.nests();
            if(rows.isEmpty())paragraph(body,"Пока нет закладок под наседкой.");
            for(FarmDb.Nest n:rows) {
                LocalDate due=FarmLogic.dueDate(n.startedAt,n.days,zone);
                item(n.name+" · "+n.hen, n.species+" · "+DailyTimeline.eggs(n.eggs)
                        +" · "+(n.active?"В процессе":"Завершена")
                        +"\nНачало "+date(n.startedAt)+" · Ожидаемый вывод "+due.format(DATE),
                        ()->nestDetails(n.id));
            }
        }
    }
    private void addNest() {
        LinearLayout f=form();
        EditText name=field(f,"Название закладки","Например, Клуша №1","",false);
        EditText hen=field(f,"Кличка наседки / гнездо","Например, Рыжая","",false);
        Spinner species=choice(f,"Вид птицы",Batch.SPECIES);
        EditText eggs=field(f,"Количество яиц","10","",true);
        EditText days=field(f,"Плановый срок, дней (настраивается)","21","21",true);
        TextView dateLabel=txt("Начало: сегодня",14,true);
        add(f,dateLabel,6);
        final Calendar start=Calendar.getInstance();
        add(f,button("Выбрать дату начала",false,()->datePicker(start,dateLabel)),12);
        EditText note=field(f,"Заметка / условия гнезда","Проверил корм, воду и спокойное место","",false);
        dialog("Новая закладка под наседкой",f,"Создать",()->{
            try(FarmDb db=new FarmDb(activity)){
                db.addNest(read(name),read(hen),species.getSelectedItem().toString(),
                        intValue(eggs),start.getTimeInMillis(),intValue(days),read(note));
            }
        });
    }
    private void datePicker(Calendar calendar,TextView label) {
        new DatePickerDialog(activity,(picker,year,month,day)->{
            calendar.set(Calendar.YEAR,year);calendar.set(Calendar.MONTH,month);
            calendar.set(Calendar.DAY_OF_MONTH,day);
            label.setText("Дата: "+date(calendar.getTimeInMillis()));
        },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void nestDetails(long nestId) {
        try(FarmDb db=new FarmDb(activity)) {
            FarmDb.Nest n=db.nest(nestId);
            if(n==null){fail(new IllegalArgumentException("Гнездо не найдено."));return;}
            LinearLayout content=form();
            add(content,txt(n.name+" · "+n.hen,19,true),8);
            paragraph(content,n.species+" · "+DailyTimeline.eggs(n.eggs)+
                    "\nНачало: "+date(n.startedAt)+"\nОжидаемый вывод: "+
                    FarmLogic.dueDate(n.startedAt,n.days,zone).format(DATE));
            paragraph(content,"Под наседкой нужно наблюдать состояние птицы, "
                    +"гнезда, доступность корма и воды. Даты и конкретные процедуры "
                    +"уточняйте для вида птицы. Температуру и влажность инкубатора здесь не назначаем.");
            if(!n.note.isEmpty())paragraph(content,"Начальные условия: "+n.note);
            if(n.active) {
                add(content,button("+ Записать осмотр / наблюдение",true,
                        ()->nestNote(nestId)),9);
                add(content,button("Завершить и записать вывод",false,
                        ()->finishNest(nestId)),10);
            } else {
                add(content,txt("Выведено: "+n.hatched+" из "+n.eggs,17,true),9);
                if(n.hatched>0 && !n.transferred)
                    add(content,button("Перенести молодняк в поголовье",false,
                            ()->transferNest(n)),9);
                if(n.transferred)paragraph(content,"Молодняк уже внесён в поголовье.");
            }
            for(FarmDb.Note note:db.nestNotes(nestId)){
                add(content,txt(when(note.at)+" · "+note.kind,13,true),4);
                paragraph(content,note.text);
            }
            ScrollView scroll=new ScrollView(activity);scroll.addView(content);
            new AlertDialog.Builder(activity).setTitle("Дневник наседки")
                    .setView(scroll).setPositiveButton("Закрыть",null).show();
        }
    }
    private void nestNote(long id) {
        LinearLayout f=form();
        Spinner kind=choice(f,"Тип записи",new String[]{
                "Ежедневный осмотр","Корм и вода","Состояние гнезда",
                "Просвечивание / контроль развития","Проблема / замечание","Другое"});
        EditText note=field(f,"Что вы наблюдали","Только фактические сведения","",false);
        dialog("Дневник насиживания",f,"Записать",()->{
            try(FarmDb db=new FarmDb(activity)){
                db.nestObservation(id,kind.getSelectedItem().toString(),read(note));
            }
        });
    }
    private void finishNest(long id) {
        LinearLayout f=form();
        EditText hatch=field(f,"Сколько птенцов вывелось","0","0",true);
        dialog("Завершение насиживания",f,"Завершить",()->{
            try(FarmDb db=new FarmDb(activity)){db.finishNest(id,intValue(hatch));}
        });
    }

    public void transferIncubator(Batch batch) {
        if(batch==null||batch.active||batch.hatched<1)return;
        try(FarmDb db=new FarmDb(activity)){
            if(db.incubatorTransferred(batch.id)) {
                new AlertDialog.Builder(activity).setMessage(
                        "Птенцы из этой закладки уже перенесены в поголовье.")
                        .setPositiveButton("Закрыть",null).show();return;
            }
        }
        transferToGroup(batch.species,batch.hatched,"Вывод в инкубаторе",
                groupId->{
                    try(FarmDb db=new FarmDb(activity)){db.transferIncubator(batch,groupId);}
                });
    }
    private interface Transfer {void move(long groupId);}
    private void transferNest(FarmDb.Nest nest) {
        transferToGroup(nest.species,nest.hatched,"Вывод под наседкой",
                groupId->{
                    try(FarmDb db=new FarmDb(activity)){db.transferNest(nest.id,groupId);}
                });
    }
    private void transferToGroup(String species,int number,String source,Transfer transfer) {
        List<FarmDb.Flock> target=new ArrayList<>();
        try(FarmDb db=new FarmDb(activity)){
            for(FarmDb.Flock f:db.flocks())if(f.species.equals(species))target.add(f);
        }
        if(target.isEmpty()){
            new AlertDialog.Builder(activity).setTitle("Сначала создайте группу")
                    .setMessage("Для учёта вывода создайте в разделе «Поголовье» группу вида «"+
                            species+"» (с начальным количеством 0), затем повторите перенос.")
                    .setPositiveButton("Понятно",null).show();return;
        }
        LinearLayout form=form();
        String[] options=new String[target.size()];
        for(int i=0;i<options.length;i++)
            options[i]=target.get(i).name+" · сейчас "+target.get(i).count;
        Spinner group=choice(form,"Куда учесть "+number+" птенцов",options);
        paragraph(form,"Перенос будет выполнен только один раз. Сумма поголовья увеличится "+
                "на фактический результат; сам дневник инкубации сохранится.");
        dialog("Перенести в поголовье · "+source,form,"Перенести",()->{
            transfer.move(target.get(group.getSelectedItemPosition()).id);
        });
    }

    private void eggs() {
        title("Сбор яиц","Учёт собранных яиц по группам птицы, без автоматических предположений о яйценоскости.");
        add(body,button("+ Записать сбор яиц",true,this::addEggs),13);
        try(FarmDb db=new FarmDb(activity)){
            for(String[] r:db.latestEggs(120))
                item(r[1]+" яиц · "+r[2],when(Long.parseLong(r[0]))+
                        (r[3].isEmpty()?"":"\n"+r[3]),null);
        }
    }
    private void addEggs() {
        List<FarmDb.Flock> groups;
        try(FarmDb db=new FarmDb(activity)){groups=db.flocks();}
        if(groups.isEmpty()){new AlertDialog.Builder(activity).setMessage(
                "Сначала добавьте группу птицы в разделе «Поголовье».")
                .setPositiveButton("Понятно",null).show();return;}
        LinearLayout f=form();
        String[] items=new String[groups.size()];
        for(int i=0;i<items.length;i++)items[i]=groups.get(i).name;
        Spinner group=choice(f,"Группа птицы",items);
        EditText count=field(f,"Количество собранных яиц","10","",true);
        EditText note=field(f,"Заметка","Необязательно","",false);
        dialog("Новая запись сбора",f,"Сохранить",()->{
            try(FarmDb db=new FarmDb(activity)){
                db.collectEggs(groups.get(group.getSelectedItemPosition()).id,
                        intValue(count),read(note));
            }
        });
    }

    private void tasks() {
        title("Задачи по хозяйству","План и выполнение. Пока это локальный список; системные уведомления для этих задач ещё не включены.");
        add(body,button("+ Новая задача",true,this::newTask),13);
        try(FarmDb db=new FarmDb(activity)) {
            List<FarmDb.Task> items=db.tasks(false);
            if(items.isEmpty())paragraph(body,"Добавьте первую задачу по уходу.");
            int shown=0;
            for(FarmDb.Task task:items) {
                if(shown++>150)break;
                LinearLayout card=Ui.card(activity);
                add(card,txt((task.doneAt>0?"✓ ":"")+task.title,16,true),8);
                paragraph(card,when(task.dueAt)+
                        (task.doneAt>0?" · выполнено "+when(task.doneAt):
                        (task.dueAt<System.currentTimeMillis()?" · просрочено":" · ожидается")));
                if(!task.notes.isEmpty())paragraph(card,task.notes);
                if(task.doneAt==0)card.addView(button("✓ Выполнено",false,()->{
                    try(FarmDb fresh=new FarmDb(activity)){fresh.completeTask(task.id);}
                    refresh.run();
                }));
                add(body,card,8);
            }
        }
    }
    private void newTask() {
        LinearLayout f=form();
        EditText title=field(f,"Что нужно сделать","Проверить запас корма","",false);
        EditText note=field(f,"Описание","Необязательно","",false);
        Calendar when=Calendar.getInstance();
        when.add(Calendar.DAY_OF_YEAR,1);
        when.set(Calendar.HOUR_OF_DAY,8);when.set(Calendar.MINUTE,0);
        TextView due=txt("Срок: "+when(when.getTimeInMillis()),14,true);
        add(f,due,8);
        add(f,button("Выбрать дату",false,()->{
            new DatePickerDialog(activity,(d,y,m,day)->{
                when.set(y,m,day);due.setText("Срок: "+when(when.getTimeInMillis()));
            },when.get(Calendar.YEAR),when.get(Calendar.MONTH),
                    when.get(Calendar.DAY_OF_MONTH)).show();
        }),7);
        add(f,button("Выбрать время",false,()->{
            new TimePickerDialog(activity,(picker,hour,min)->{
                when.set(Calendar.HOUR_OF_DAY,hour);
                when.set(Calendar.MINUTE,min);
                when.set(Calendar.SECOND,0);
                due.setText("Срок: "+when(when.getTimeInMillis()));
            },when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),true).show();
        }),12);
        dialog("Новая задача",f,"Запланировать",()->{
            try(FarmDb db=new FarmDb(activity)){db.addTask(read(title),read(note),when.getTimeInMillis());}
        });
    }

    private void reports() {
        title("Отчёты","Фактические данные из журналов. Денежный результат не равен прибыли с учётом всех запасов и активов.");
        try(FarmDb db=new FarmDb(activity)) {
            LocalDate today=LocalDate.now(zone);
            long month=today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long end=today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long week=today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli();
            for(int i=0;i<3;i++){
                long start=i==0?week:i==1?month:0;
                String period=i==0?"Последние 7 дней":i==1?"С начала месяца":"За всё время";
                long[] values=db.totals(start,end);
                item(period,
                        "Доходы: "+FarmLogic.rub(values[0])
                        +"\nРасходы: "+FarmLogic.rub(values[1])
                        +"\nДенежный итог: "+FarmLogic.rub(values[0]-values[1])
                        +"\nСобрано яиц: "+db.eggsCollected(start,end),null);
            }
            heading("Поголовье и инкубация");
            item("Всего живой птицы в учёте: "+db.totalBirds(),
                    "Активных закладок под наседкой: "+db.openNests(),null);
            int active=0,eggs=0,hatched=0,completed=0;
            try(Db old=new Db(activity)) {
                for(Batch b:old.allBatches()) {
                    if(b.active)active++;
                    else {completed++;eggs+=b.eggs;hatched+=b.hatched;}
                }
            }
            item("В инкубаторе: "+active+" активных",
                    "Завершено партий: "+completed+" · Заложено: "+eggs+
                    " · Фактически выведено: "+hatched+
                    (eggs>0?" · Выводимость: "+FarmLogic.percentagePart(hatched,eggs)+"%":""),null);
            heading("Фактические остатки кормов");
            for(FarmDb.Feed feed:db.feeds())item(feed.name,
                    num(feed.stock)+" "+feed.unit+" на учёте",null);
            heading("Что ещё не рассчитано");
            paragraph(body,"Себестоимость выращивания, прогноз доходов и ветеринарные "
                    +"показатели появятся после накопления и проверки достаточных исходных данных. "
                    +"Сейчас никакие цены и показатели не подставляются автоматически.");
        }
    }
}
