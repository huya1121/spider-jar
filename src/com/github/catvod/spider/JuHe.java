package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 聚合搜索 + 自定义公告/提醒 (饭太硬风格)
 *
 * 站点入口写法 (type=3 csp):
 *   { "key":"juhe", "name":"🔍聚合搜索", "type":3, "api":"csp_JuHe",
 *     "searchable":1, "quickSearch":1, "ext":"{...见下...}" }
 *
 * ext 可以是内联 JSON 字符串，也可以是一个返回该 JSON 的 http(s) 地址。
 * ext 结构:
 *   {
 *     "notice": "第一行公告\n第二行公告",         // 直接写公告文本
 *     "noticeUrl": "https://xxx/notice.txt",     // 或从远程拉取(优先级低于 notice)
 *     "sites": [                                  // 参与聚合的采集站(maccms/苹果CMS json 接口)
 *        { "name":"量子", "api":"https://cj.lzcaiji.com/api.php/provide/vod" },
 *        { "name":"非凡", "api":"https://api.ffzyapi.com/api.php/provide/vod" }
 *     ]
 *   }
 */
public class JuHe extends Spider {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final List<String[]> sites = new ArrayList<>(); // [name, api]
    private String notice = "";

    // ---------------------------------------------------------------------
    // 初始化
    // ---------------------------------------------------------------------
    @Override
    public void init(Context context, String extend) {
        try {
            loadConfig(extend);
        } catch (Exception e) {
            // 出错也不能崩，用内置默认
        }
        if (sites.isEmpty()) loadDefaults();
    }

    private void loadConfig(String extend) throws Exception {
        if (TextUtils.isEmpty(extend)) return;
        String json = extend.trim();
        if (json.startsWith("http")) json = get(json); // ext 为远程地址
        if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) return;

        JSONObject cfg = new JSONObject(json);
        this.notice = cfg.optString("notice", "");
        if (TextUtils.isEmpty(this.notice)) {
            String noticeUrl = cfg.optString("noticeUrl", "");
            if (!TextUtils.isEmpty(noticeUrl)) {
                try { this.notice = get(noticeUrl); } catch (Exception ignored) {}
            }
        }
        JSONArray arr = cfg.optJSONArray("sites");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("name", "源" + (i + 1));
                String api = s.optString("api", "").trim();
                if (!TextUtils.isEmpty(api)) sites.add(new String[]{name, api});
            }
        }
    }

    private void loadDefaults() {
        sites.add(new String[]{"量子", "https://cj.lzcaiji.com/api.php/provide/vod"});
        sites.add(new String[]{"非凡", "https://api.ffzyapi.com/api.php/provide/vod"});
        sites.add(new String[]{"暴风", "https://bfzyapi.com/api.php/provide/vod"});
        sites.add(new String[]{"爱奇艺", "https://iqiyizyapi.com/api.php/provide/vod"});
        if (TextUtils.isEmpty(notice)) {
            notice = "📢 欢迎使用聚合搜索\n\n"
                    + "· 一次搜索，聚合多个资源站结果\n"
                    + "· 结果名称后带【源名】方便辨别\n"
                    + "· 如需自定义公告与源，请在站点 ext 里配置\n";
        }
    }

    // ---------------------------------------------------------------------
    // 首页：一个公告分类 + 各源的最新
    // ---------------------------------------------------------------------
    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray classes = new JSONArray();
        JSONObject filters = new JSONObject();
        classes.put(clazz("notice", "📢公告"));
        // 用第一个源的分类作为浏览分类（含层级 + 类型筛选）
        if (!sites.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(get(sites.get(0)[1] + "?ac=list"));
                JSONArray cls = obj.optJSONArray("class");
                if (cls != null) buildClasses(cls, classes, filters);
            } catch (Exception ignored) {}
        }
        result.put("class", classes);
        if (filters.length() > 0) result.put("filters", filters);
        result.put("list", homeList());
        return result.toString();
    }

    /** 依据 type_pid 把分类构建成 顶级分类 + 子类型筛选；无层级信息则平铺 */
    private void buildClasses(JSONArray cls, JSONArray classes, JSONObject filters) {
        java.util.Map<String, JSONObject> parents = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<JSONObject>> children = new java.util.HashMap<>();
        boolean hierarchy = false;
        for (int i = 0; i < cls.length(); i++) {
            JSONObject c = cls.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("type_id");
            if (TextUtils.isEmpty(id)) continue;
            String pid = c.optString("type_pid", "0");
            if (TextUtils.isEmpty(pid) || "0".equals(pid)) {
                parents.put(id, c);
            } else {
                hierarchy = true;
                if (!children.containsKey(pid)) children.put(pid, new ArrayList<JSONObject>());
                children.get(pid).add(c);
            }
        }
        if (!hierarchy) { // 平铺
            for (int i = 0; i < cls.length(); i++) {
                JSONObject c = cls.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("type_id"), name = c.optString("type_name");
                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name)) classes.put(clazz("0@" + id, name));
            }
            return;
        }
        try {
            for (java.util.Map.Entry<String, JSONObject> e : parents.entrySet()) {
                String tid = "0@" + e.getKey();
                classes.put(clazz(tid, e.getValue().optString("type_name")));
                List<JSONObject> ch = children.get(e.getKey());
                if (ch == null || ch.isEmpty()) continue;
                JSONArray values = new JSONArray();
                values.put(kv("全部", ""));
                for (JSONObject cc : ch) values.put(kv(cc.optString("type_name"), cc.optString("type_id")));
                JSONObject f = new JSONObject();
                f.put("key", "cateId");
                f.put("name", "类型");
                f.put("value", values);
                filters.put(tid, new JSONArray().put(f));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public String homeVideoContent() throws Exception {
        JSONObject result = new JSONObject();
        result.put("list", homeList());
        return result.toString();
    }

    private JSONArray homeList() {
        JSONArray list = new JSONArray();
        // 公告卡片
        list.put(vod("notice", "📢 点击查看公告", "", "公告"));
        // 第一个源的最新
        if (!sites.isEmpty()) {
            try {
                String body = get(sites.get(0)[1] + "?ac=detail&pg=1");
                appendVodList(list, new JSONObject(body).optJSONArray("list"), 0);
            } catch (Exception ignored) {}
        }
        return list;
    }

    // ---------------------------------------------------------------------
    // 分类浏览
    // ---------------------------------------------------------------------
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();

        if ("notice".equals(tid)) {
            list.put(vod("notice", "📢 点击查看公告", "", "公告"));
            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", 1);
            result.put("total", 1);
            return result.toString();
        }

        int idx = 0;
        String typeId = tid;
        if (tid.contains("@")) {
            idx = safeInt(tid.substring(0, tid.indexOf('@')), 0);
            typeId = tid.substring(tid.indexOf('@') + 1);
        }
        if (idx < 0 || idx >= sites.size()) idx = 0;

        // 若用户选了「类型」筛选，用子类型 id 覆盖
        if (extend != null) {
            String cate = extend.get("cateId");
            if (!TextUtils.isEmpty(cate)) typeId = cate;
        }

        String url = sites.get(idx)[1] + "?ac=detail&t=" + typeId + "&pg=" + pg;
        JSONObject obj = new JSONObject(get(url));
        appendVodList(list, obj.optJSONArray("list"), idx);

        result.put("list", list);
        result.put("page", obj.optInt("page", safeInt(pg, 1)));
        result.put("pagecount", obj.optInt("pagecount", 1));
        result.put("limit", obj.optInt("limit", 20));
        result.put("total", obj.optInt("total", list.length()));
        return result.toString();
    }

    // ---------------------------------------------------------------------
    // 聚合搜索：并发查询所有源，合并
    // ---------------------------------------------------------------------
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return doSearch(key);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        // 只在第一页做聚合，避免翻页重复
        if (!TextUtils.isEmpty(pg) && !"1".equals(pg)) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
        return doSearch(key);
    }

    private String doSearch(final String key) throws Exception {
        final JSONArray merged = new JSONArray();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, sites.size()));
        List<Future<JSONArray>> futures = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            final int idx = i;
            futures.add(pool.submit(new Callable<JSONArray>() {
                @Override
                public JSONArray call() {
                    JSONArray out = new JSONArray();
                    try {
                        // 搜索用轻量 ac=list（不含播放地址），详情再按需拉取，多源并发更快
                        String url = sites.get(idx)[1] + "?ac=list&wd="
                                + URLEncoder.encode(key, "UTF-8");
                        JSONObject obj = new JSONObject(get(url));
                        appendVodList(out, obj.optJSONArray("list"), idx);
                    } catch (Exception ignored) {}
                    return out;
                }
            }));
        }
        for (Future<JSONArray> f : futures) {
            try {
                JSONArray part = f.get(10, TimeUnit.SECONDS);
                for (int i = 0; i < part.length(); i++) merged.put(part.get(i));
            } catch (Exception ignored) {}
        }
        pool.shutdownNow();
        return new JSONObject().put("list", merged).toString();
    }

    // ---------------------------------------------------------------------
    // 详情
    // ---------------------------------------------------------------------
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vid = ids.get(0);

        if ("notice".equals(vid)) {
            JSONObject v = new JSONObject();
            v.put("vod_id", "notice");
            v.put("vod_name", "📢 公告");
            v.put("vod_content", TextUtils.isEmpty(notice) ? "暂无公告" : notice);
            v.put("vod_play_from", "公告");
            v.put("vod_play_url", "知道了$about:blank");
            JSONArray list = new JSONArray();
            list.put(v);
            return new JSONObject().put("list", list).toString();
        }

        int idx = 0;
        String realId = vid;
        if (vid.contains("@")) {
            idx = safeInt(vid.substring(0, vid.indexOf('@')), 0);
            realId = vid.substring(vid.indexOf('@') + 1);
        }
        if (idx < 0 || idx >= sites.size()) idx = 0;

        String url = sites.get(idx)[1] + "?ac=detail&ids=" + realId;
        JSONObject obj = new JSONObject(get(url));
        JSONArray list = obj.optJSONArray("list");
        if (list == null || list.length() == 0) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
        JSONObject v = list.getJSONObject(0);
        // 保持 vod_id 带源标记，便于播放追溯
        v.put("vod_id", idx + "@" + realId);
        // 名称标注来源
        v.put("vod_name", v.optString("vod_name") + "【" + sites.get(idx)[0] + "】");
        JSONArray out = new JSONArray();
        out.put(v);
        return new JSONObject().put("list", out).toString();
    }

    // ---------------------------------------------------------------------
    // 播放
    // ---------------------------------------------------------------------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject result = new JSONObject();
        boolean direct = isDirect(id);
        String url = id;
        // m3u8 直链且拿得到本地代理地址时，走去广告代理
        if (direct && id.toLowerCase().contains(".m3u8")) {
            String proxy = proxyUrl();
            if (!TextUtils.isEmpty(proxy)) {
                url = proxy + "?do=juhe&type=m3u8&url=" + URLEncoder.encode(id, "UTF-8");
            }
        }
        result.put("parse", direct ? 0 : 1);
        result.put("playUrl", "");
        result.put("url", url);
        JSONObject header = new JSONObject();
        header.put("User-Agent", UA);
        result.put("header", header);
        return result.toString();
    }

    // ---------------------------------------------------------------------
    // 本地代理：清洗 m3u8 去广告（时长突变过滤）
    // ---------------------------------------------------------------------
    @Override
    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        String type = params.get("type");
        String url = params.get("url");
        if (!"m3u8".equals(type) || TextUtils.isEmpty(url)) return null;
        try {
            String raw = get(url);
            String cleaned = cleanM3U8(raw, url);
            byte[] bytes = cleaned.getBytes("UTF-8");
            return new Object[]{200, "application/vnd.apple.mpegurl",
                    new ByteArrayInputStream(bytes)};
        } catch (Exception e) {
            return null; // 失败则播放器仍可直连原始链接（parse:0 的 url 已给出）
        }
    }

    /**
     * 时长突变过滤：以所有分片时长的中位数为基准，剔除时长明显偏离（过短的插入广告）
     * 的分片。master 播放列表只做绝对化处理，不过滤。
     */
    private String cleanM3U8(String raw, String baseUrl) {
        if (TextUtils.isEmpty(raw)) return raw;
        String[] lines = raw.split("\\r?\\n");

        // master 列表：把子流/KEY 的相对地址绝对化后原样返回
        boolean master = raw.contains("#EXT-X-STREAM-INF");
        StringBuilder out = new StringBuilder();
        if (master) {
            for (String line : lines) {
                out.append(absLine(line, baseUrl)).append("\n");
            }
            return out.toString();
        }

        // 先收集所有 EXTINF 时长求中位数
        List<Double> durs = new ArrayList<>();
        for (String line : lines) {
            Double d = parseExtinf(line);
            if (d != null) durs.add(d);
        }
        double median = median(durs);
        // 中位数拿不到就不过滤，直接绝对化返回
        if (median <= 0) {
            for (String line : lines) out.append(absLine(line, baseUrl)).append("\n");
            return out.toString();
        }
        double low = median * 0.5;   // 短于中位数一半判为广告
        double high = median * 3.0;  // 长于中位数三倍也判为异常插入

        // 逐分片处理：一个分片 = 若干 #EXT 标签 + 一行 url
        List<String> pending = new ArrayList<>();
        Double pendingDur = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#EXT-X-DISCONTINUITY")) {
                // 丢弃广告常用的不连续标记，避免播放器计时错乱
                continue;
            }
            if (t.startsWith("#")) {
                Double d = parseExtinf(t);
                if (d != null) pendingDur = d;
                pending.add(absLine(t, baseUrl));
                // 头部全局标签（无对应分片）直接落盘
                if (pendingDur == null && !t.startsWith("#EXTINF")) {
                    out.append(pending.remove(pending.size() - 1)).append("\n");
                }
                continue;
            }
            // 到这里 t 是分片 url 行
            boolean isAd = pendingDur != null && (pendingDur < low || pendingDur > high);
            if (!isAd) {
                for (String p : pending) out.append(p).append("\n");
                out.append(absLine(t, baseUrl)).append("\n");
            }
            pending.clear();
            pendingDur = null;
        }
        // 收尾遗留标签
        for (String p : pending) out.append(p).append("\n");
        if (!out.toString().contains("#EXT-X-ENDLIST")) out.append("#EXT-X-ENDLIST\n");
        return out.toString();
    }

    private Double parseExtinf(String line) {
        if (!line.startsWith("#EXTINF")) return null;
        try {
            int colon = line.indexOf(':');
            String rest = line.substring(colon + 1);
            int comma = rest.indexOf(',');
            String num = (comma >= 0 ? rest.substring(0, comma) : rest).trim();
            return Double.parseDouble(num);
        } catch (Exception e) {
            return null;
        }
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    /** 把 m3u8 里的相对分片/KEY 地址转成绝对地址，代理只发列表、分片仍走源站直连 */
    private String absLine(String line, String baseUrl) {
        try {
            String t = line.trim();
            if (t.startsWith("#EXT-X-KEY") && t.contains("URI=\"")) {
                int s = t.indexOf("URI=\"") + 5;
                int e = t.indexOf('"', s);
                String uri = t.substring(s, e);
                return t.substring(0, s) + abs(uri, baseUrl) + t.substring(e);
            }
            if (t.isEmpty() || t.startsWith("#")) return line;
            return abs(t, baseUrl);
        } catch (Exception e) {
            return line;
        }
    }

    private String abs(String ref, String baseUrl) throws Exception {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        return new URL(new URL(baseUrl), ref).toString();
    }

    /** 反射获取宿主本地代理地址，取不到就返回空（不同 TVBox 分支实现不一） */
    private String proxyUrl() {
        String[][] targets = {
                {"com.github.catvod.utils.Proxy", "getUrl"},
                {"com.github.catvod.server.Server", "getAddress"},
        };
        for (String[] t : targets) {
            try {
                Class<?> cls = Class.forName(t[0]);
                Method m = cls.getMethod(t[1]);
                Object r = m.invoke(null);
                if (r != null && !TextUtils.isEmpty(r.toString())) return r.toString();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    @Override
    public boolean manualVideoCheck() {
        return false;
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------
    private void appendVodList(JSONArray target, JSONArray src, int idx) {
        if (src == null) return;
        String tag = idx < sites.size() ? sites.get(idx)[0] : ("源" + idx);
        for (int i = 0; i < src.length(); i++) {
            JSONObject item = src.optJSONObject(i);
            if (item == null) continue;
            String vid = item.optString("vod_id");
            if (TextUtils.isEmpty(vid)) continue;
            JSONObject v = new JSONObject();
            try {
                v.put("vod_id", idx + "@" + vid);
                v.put("vod_name", item.optString("vod_name") + "【" + tag + "】");
                v.put("vod_pic", item.optString("vod_pic"));
                String remark = item.optString("vod_remarks");
                v.put("vod_remarks", TextUtils.isEmpty(remark) ? tag : remark);
                target.put(v);
            } catch (Exception ignored) {}
        }
    }

    private JSONObject kv(String n, String v) {
        JSONObject o = new JSONObject();
        try {
            o.put("n", n);
            o.put("v", v);
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject clazz(String id, String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("type_id", id);
            o.put("type_name", name);
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject vod(String id, String name, String pic, String remark) {
        JSONObject o = new JSONObject();
        try {
            o.put("vod_id", id);
            o.put("vod_name", name);
            o.put("vod_pic", pic);
            o.put("vod_remarks", remark);
        } catch (Exception ignored) {}
        return o;
    }

    private boolean isDirect(String url) {
        if (TextUtils.isEmpty(url)) return true;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
                || u.contains(".mkv") || u.contains(".ts");
    }

    private int safeInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private String get(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
