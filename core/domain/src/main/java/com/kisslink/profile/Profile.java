package com.kisslink.profile;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 個人名片資料模型——姓名 + 可自訂的聯絡欄位（電話／Email／公司…皆為自訂欄位）。
 * 頭像不存在這裡（由 {@link ProfileStore} 以檔案管理），這個物件只負責可序列化的文字資料。
 */
public final class Profile {

    /** 單一聯絡欄位（label 自訂，例如「電話」「Email」「公司」）。 */
    public static final class Field {
        private final String label;
        private final String value;
        public Field(String label, String value) { this.label = label; this.value = value; }
        public String getLabel() { return label; }
        public String getValue() { return value; }
    }

    private String name;
    private final List<Field> fields;
    /** 名片頭像（JPEG/PNG bytes，可為 null）——隨 vCard 以 PHOTO 欄位攜帶，供接收端顯示。 */
    @androidx.annotation.Nullable private byte[] photo;

    public Profile(@NonNull String name, @NonNull List<Field> fields) {
        this.name = name;
        this.fields = fields;
    }

    public static Profile empty() {
        return new Profile("", new ArrayList<>());
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @NonNull public List<Field> getFields() { return fields; }
    public void addField(@NonNull Field field) { fields.add(field); }

    @androidx.annotation.Nullable public byte[] getPhoto() { return photo; }
    public void setPhoto(@androidx.annotation.Nullable byte[] photo) { this.photo = photo; }

    public boolean isBlank() {
        return name.trim().isEmpty() && fields.isEmpty();
    }

    /**
     * 組成 vCard 3.0（可被系統聯絡人 App 匯入）。欄位 label 對應到常見 vCard 屬性，
     * 認不得的就放進備註，確保不漏資料。
     */
    @NonNull
    public byte[] toVCard() {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\r\n");
        sb.append("VERSION:3.0\r\n");
        String fn = name.trim().isEmpty() ? "KissLink Contact" : name.trim();
        sb.append("FN:").append(escape(fn)).append("\r\n");
        sb.append("N:").append(escape(fn)).append(";;;;\r\n");
        List<String> notes = new ArrayList<>();
        for (Field f : fields) {
            if (f.value == null || f.value.trim().isEmpty()) continue;
            String key = f.label == null ? "" : f.label.trim().toLowerCase(Locale.ROOT);
            String v = escape(f.value.trim());
            if (key.contains("phone") || key.contains("電話") || key.contains("手機") || key.contains("tel")) {
                sb.append("TEL;TYPE=CELL:").append(v).append("\r\n");
            } else if (key.contains("mail") || key.contains("信箱") || key.contains("郵")) {
                sb.append("EMAIL:").append(v).append("\r\n");
            } else if (key.contains("公司") || key.contains("org") || key.contains("company")) {
                sb.append("ORG:").append(v).append("\r\n");
            } else if (key.contains("title") || key.contains("職") ) {
                sb.append("TITLE:").append(v).append("\r\n");
            } else if (key.contains("url") || key.contains("網") || key.contains("web")) {
                sb.append("URL:").append(v).append("\r\n");
            } else if (key.contains("addr") || key.contains("地址")) {
                sb.append("ADR:;;").append(v).append(";;;;\r\n");
            } else {
                notes.add((f.label == null ? "" : f.label.trim() + ": ") + f.value.trim());
            }
        }
        if (!notes.isEmpty()) {
            sb.append("NOTE:").append(escape(String.join(" | ", notes))).append("\r\n");
        }
        if (photo != null && photo.length > 0) {
            // vCard 3.0 內嵌頭像（base64，單行不折行以便自家解析）。
            String b64 = java.util.Base64.getEncoder().encodeToString(photo);
            sb.append("PHOTO;ENCODING=b;TYPE=JPEG:").append(b64).append("\r\n");
        }
        sb.append("END:VCARD\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    /** 解析 vCard（收到名片時用），抽出姓名與常見欄位。 */
    @NonNull
    public static Profile fromVCard(@NonNull byte[] vcf) {
        Profile p = Profile.empty();
        String text = new String(vcf, StandardCharsets.UTF_8);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).toUpperCase(Locale.ROOT);
            String val = unescape(line.substring(colon + 1).trim());
            if (val.isEmpty()) continue;
            if (key.equals("FN")) {
                p.name = val;
            } else if (key.startsWith("PHOTO")) {
                try { p.photo = java.util.Base64.getDecoder().decode(val); }
                catch (Exception ignored) {}
            } else if (key.startsWith("TEL")) {
                p.fields.add(new Field("電話", val));
            } else if (key.startsWith("EMAIL")) {
                p.fields.add(new Field("Email", val));
            } else if (key.startsWith("ORG")) {
                p.fields.add(new Field("公司", val.replace(";", " ").trim()));
            } else if (key.startsWith("TITLE")) {
                p.fields.add(new Field("職稱", val));
            } else if (key.startsWith("URL")) {
                p.fields.add(new Field("網址", val));
            } else if (key.startsWith("ADR")) {
                String adr = val.replace(";", " ").trim();
                if (!adr.isEmpty()) p.fields.add(new Field("地址", adr));
            } else if (key.startsWith("NOTE")) {
                for (String part : val.split("\\|")) {
                    String s = part.trim();
                    if (s.isEmpty()) continue;
                    int sep = s.indexOf(": ");
                    if (sep > 0) p.fields.add(new Field(s.substring(0, sep), s.substring(sep + 2)));
                    else p.fields.add(new Field("備註", s));
                }
            }
        }
        return p;
    }
}
