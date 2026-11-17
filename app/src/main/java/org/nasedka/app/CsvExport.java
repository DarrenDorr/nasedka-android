package org.nasedka.app;

import android.content.Context;
import android.net.Uri;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class CsvExport {
    private CsvExport() {}
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static String quote(String s) { return "\"" + (s == null ? "" : s).replace("\"", "\"\"") + "\""; }
    private static String date(long t) { return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(FORMAT); }

    public static void write(Context c, long batchId, Uri target) throws Exception {
        try (Db db = new Db(c); OutputStream output = c.getContentResolver().openOutputStream(target)) {
            if (output == null) throw new IllegalStateException("Не удалось открыть файл.");
            Batch b = db.batch(batchId);
            if (b == null) throw new IllegalStateException("Закладка не найдена.");
            StringBuilder sb = new StringBuilder();
            sb.append("sep=;\r\n");
            sb.append("Закладка;").append(quote(b.name)).append("\r\n");
            sb.append("Птица;").append(quote(b.species)).append("\r\n");
            sb.append("Заложено;").append(b.eggs).append("\r\n");
            sb.append("Выведено;").append(b.active ? "" : b.hatched).append("\r\n");
            sb.append("Дата;Температура °C;Влажность %;Заметка\r\n");
            List<Db.Entry> entries = db.entries(batchId, 1000);
            for (int i = entries.size() - 1; i >= 0; i--) {
                Db.Entry e = entries.get(i);
                sb.append(quote(date(e.time))).append(';')
                        .append(e.temperature == null ? "" : e.temperature).append(';')
                        .append(e.humidity == null ? "" : e.humidity).append(';')
                        .append(quote(e.text)).append("\r\n");
            }
            sb.append("\r\nВыполненные задачи;Описание;Дата исполнения\r\n");
            for (String[] d : db.finishedActions(batchId)) {
                sb.append(quote(d[0])).append(';').append(quote(d[1])).append(';')
                        .append(quote(date(Long.parseLong(d[2])))).append("\r\n");
            }
            output.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
            output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
