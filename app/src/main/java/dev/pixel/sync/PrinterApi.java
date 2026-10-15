package dev.pixel.sync;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PrinterApi {

    public static final class Printer {
        public final String id, name, host, model, location, state;
        public final int port;
        public final boolean online;
        Printer(JSONObject o) throws Exception {
            id = o.getString("id"); name = o.getString("name"); host = o.getString("host");
            port = o.optInt("port", 631); model = o.optString("model", ""); location = o.optString("location", "");
            online = o.optBoolean("online", false); state = o.optString("state", "unknown");
        }
    }

    public static final class PrintJob {
        public final String id, printerId, printerName, fileName, fileType, status, error, createdAt, completedAt;
        public final int copies;
        PrintJob(JSONObject o) {
            id = o.optString("id", "");
            printerId = o.optString("printer_id", "");
            printerName = o.optString("printer_name", "未知打印机");
            fileName = o.optString("file_name", "未命名文件");
            fileType = o.optString("file_type", "");
            copies = o.optInt("copies", 1);
            status = o.optString("status", "unknown");
            error = o.optString("error", "");
            createdAt = o.optString("created_at", "");
            completedAt = o.optString("completed_at", "");
        }
    }

    private static String read(InputStream s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = s.read(b)) != -1) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    private static String serverUrl(Context ctx) {
        return ctx.getSharedPreferences("gray_settings", Context.MODE_PRIVATE).getString("server_url", "").replaceAll("/+$", "");
    }

    private static String token(Context ctx) throws Exception {
        return new SecureStore(ctx).get("device_token");
    }

    private static HttpURLConnection open(Context ctx, String path, String method) throws Exception {
        String url = serverUrl(ctx), tok = token(ctx);
        if (url.isEmpty() || tok.isEmpty()) throw new IOException("尚未配对设备");
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        return c;
    }

    private static JSONObject post(Context ctx, String path) throws Exception {
        HttpURLConnection c = open(ctx, path, "POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException("请求失败（" + code + "）：" + body);
        return new JSONObject(body);
    }

    private static JSONObject get(Context ctx, String path) throws Exception {
        HttpURLConnection c = open(ctx, path, "GET");
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException("请求失败（" + code + "）：" + body);
        return new JSONObject(body);
    }

    public static List<Printer> listPrinters(Context ctx) throws Exception {
        HttpURLConnection c = open(ctx, "/v1/printers", "GET");
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException("请求失败（" + code + "）：" + body);
        JSONArray arr = new JSONArray(body);
        List<Printer> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) result.add(new Printer(arr.getJSONObject(i)));
        return result;
    }

    public static List<PrintJob> listPrintJobs(Context ctx) throws Exception {
        HttpURLConnection c = open(ctx, "/v1/print-jobs", "GET");
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException("请求失败（" + code + "）：" + body);
        JSONArray arr = new JSONArray(body);
        List<PrintJob> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) result.add(new PrintJob(arr.getJSONObject(i)));
        return result;
    }

    public static List<Printer> discover(Context ctx) throws Exception {
        JSONObject resp = post(ctx, "/v1/printers/discover");
        JSONArray arr = resp.optJSONArray("printers");
        List<Printer> result = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) result.add(new Printer(arr.getJSONObject(i)));
        return result;
    }

    public static void deletePrinter(Context ctx, String printerId) throws Exception {
        HttpURLConnection c = open(ctx, "/v1/printers/" + printerId, "DELETE");
        int code = c.getResponseCode();
        if (code >= 300) throw new IOException("删除失败（" + code + "）");
    }

    public static String printText(Context ctx, String printerId, String text, int copies, String paperSize, String colorMode) throws Exception {
        String boundary = "----GrayPrint" + System.currentTimeMillis();
        HttpURLConnection c = open(ctx, "/v1/printers/" + printerId + "/print", "POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setDoOutput(true);
        c.setReadTimeout(120000);
        OutputStream out = c.getOutputStream();
        writeField(out, boundary, "copies", String.valueOf(copies));
        writeField(out, boundary, "paper_size", paperSize);
        writeField(out, boundary, "color_mode", colorMode);
        writeField(out, boundary, "text", text);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush(); out.close();
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException(parseError(body, code));
        return new JSONObject(body).optString("job_id", "");
    }

    public static String printFile(Context ctx, String printerId, Uri fileUri, int copies, String paperSize, String colorMode) throws Exception {
        String fileName = queryFileName(ctx, fileUri);
        byte[] fileBytes = readUri(ctx, fileUri);
        String boundary = "----GrayPrint" + System.currentTimeMillis();
        HttpURLConnection c = open(ctx, "/v1/printers/" + printerId + "/print", "POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setDoOutput(true);
        c.setReadTimeout(120000);
        OutputStream out = c.getOutputStream();
        writeField(out, boundary, "copies", String.valueOf(copies));
        writeField(out, boundary, "paper_size", paperSize);
        writeField(out, boundary, "color_mode", colorMode);
        writeFilePart(out, boundary, "file", fileName, fileBytes);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush(); out.close();
        int code = c.getResponseCode();
        String body = read(code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 300) throw new IOException(parseError(body, code));
        return new JSONObject(body).optString("job_id", "");
    }

    private static String parseError(String body, int code) {
        try { return new JSONObject(body).optString("detail", "请求失败（" + code + "）"); }
        catch (Exception e) { return "请求失败（" + code + "）：" + body; }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(OutputStream out, String boundary, String name, String fileName, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readUri(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取文件");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String queryFileName(Context ctx, Uri uri) {
        try (Cursor cur = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cur.getString(idx);
            }
        } catch (Exception ignored) {}
        String path = uri.getLastPathSegment();
        return path != null ? path : "document";
    }
}
