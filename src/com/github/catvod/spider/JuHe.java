package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    // 豆瓣主界面：用豆瓣热门做首页/分类，海报走豆瓣 CDN；点开后回资源站聚合搜索取播放地址
    private boolean doubanOn = false;
    private String doubanApi = "https://movie.douban.com/j/search_subjects";
    // 豆瓣图床有防盗链，客户端直接取会 403，TVBox 就退化成“首字占位图”。
    // doubanImgProxy 为空时：给海报URL附加 @Referer 头（FongMi/影视仓系客户端会据此带头请求）。
    // 也可配成一个图片代理模板（含 {url}），由代理服务器补 Referer，兼容所有客户端。
    private String doubanImgProxy = "";
    // 补给豆瓣图床的 Referer，默认豆瓣自家域名；可用 ext 的 doubanReferer 覆盖，jar 不写死。
    private String doubanReferer = "https://movie.douban.com/";
    private final List<String[]> doubanCats = new ArrayList<>();      // [显示名, type, tag]
    // 标题 -> 海报(原始URL)，供详情页复用；并发（detailFromSources 多线程读、doubanList 写）用并发 Map
    private final Map<String, String> doubanPics = new java.util.concurrent.ConcurrentHashMap<>();

    // 首页缓存：分类/筛选/推荐一次拉取后复用，避免重复重请求
    private JSONArray homeClass;
    private JSONObject homeFilter;
    private JSONArray homeVods;
    private long homeTime = 0;
    private static final long HOME_TTL = 10 * 60 * 1000L; // 10 分钟

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

        // 豆瓣主界面配置
        this.doubanOn = cfg.optBoolean("douban", false);
        String da = cfg.optString("doubanApi", "");
        if (!TextUtils.isEmpty(da)) this.doubanApi = da.trim();
        String dip = cfg.optString("doubanImageProxy", "");
        if (!TextUtils.isEmpty(dip)) this.doubanImgProxy = dip.trim();
        String dref = cfg.optString("doubanReferer", "");
        if (!TextUtils.isEmpty(dref)) this.doubanReferer = dref.trim();
        JSONArray dc = cfg.optJSONArray("doubanCategories");
        if (dc != null) {
            for (int i = 0; i < dc.length(); i++) {
                JSONObject c = dc.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name", "");
                String type = c.optString("type", "movie");
                String tag = c.optString("tag", "热门");
                if (!TextUtils.isEmpty(name)) doubanCats.add(new String[]{name, type, tag});
            }
        }
        if (doubanOn && doubanCats.isEmpty()) loadDefaultDoubanCats();
    }

    private void loadDefaultDoubanCats() {
        doubanCats.add(new String[]{"热门电影", "movie", "热门"});
        doubanCats.add(new String[]{"最新电影", "movie", "最新"});
        doubanCats.add(new String[]{"豆瓣高分", "movie", "豆瓣高分"});
        doubanCats.add(new String[]{"冷门佳片", "movie", "冷门佳片"});
        doubanCats.add(new String[]{"热门剧集", "tv", "热门"});
        doubanCats.add(new String[]{"国产剧", "tv", "国产剧"});
        doubanCats.add(new String[]{"美剧", "tv", "美剧"});
        doubanCats.add(new String[]{"综艺", "tv", "综艺"});
        doubanCats.add(new String[]{"动漫", "tv", "日本动画"});
    }

    private void loadDefaults() {
        // 源统一由配置的 ext 提供，jar 不写死任何源。
        // 这里只在完全没配 ext（或 ext 里没有 sites）时兜底提示，正常不会触发。
        if (TextUtils.isEmpty(notice)) {
            notice = "📢 未检测到源配置\n\n"
                    + "请在 TVBox 配置里 juhe 站点的 ext 中填写 sites（以及可选的 douban 参数）。\n"
                    + "所有源都在配置里，改源只改配置，无需重新编译 jar。";
        }
    }

    // ---------------------------------------------------------------------
    // 首页：一个公告分类 + 各源的最新
    // ---------------------------------------------------------------------
    @Override
    public String homeContent(boolean filter) throws Exception {
        loadHome();
        JSONObject result = new JSONObject();
        result.put("class", homeClass);
        if (homeFilter != null && homeFilter.length() > 0) result.put("filters", homeFilter);
        result.put("list", homeVods);
        putPosterStyle(result);
        return result.toString();
    }

    /**
     * 一次拉取构建首页：maccms 的 ?ac=list&pg=1 同时返回 class(分类) 与 list(最新)，
     * 一个轻请求搞定分类+筛选+推荐；结果缓存 10 分钟，刷新/切页秒回。
     */
    private synchronized void loadHome() {
        long now = System.currentTimeMillis();
        if (homeClass != null && (now - homeTime) < HOME_TTL) return;

        JSONArray classes = new JSONArray();
        JSONObject filters = new JSONObject();
        JSONArray vods = new JSONArray();
        classes.put(clazz("notice", "📢公告"));
        vods.put(vod("notice", "📢 点击查看公告", "", "公告"));

        boolean ok = false;
        if (doubanOn && !doubanCats.isEmpty()) {
            // 豆瓣主界面：分类来自豆瓣，首页推荐用第一个豆瓣分类的热门，海报走豆瓣 CDN
            for (String[] c : doubanCats) classes.put(clazz("DB:" + c[1] + ":" + c[2], c[0]));
            try {
                JSONArray dv = doubanList(doubanCats.get(0)[1], doubanCats.get(0)[2], "1");
                for (int i = 0; i < dv.length(); i++) vods.put(dv.get(i));
                ok = dv.length() > 0;
            } catch (Exception ignored) {}
        } else {
            // 无豆瓣时：竞速取最快源的分类 + 最新
            Object[] win = raceHome();
            if (win != null) {
                int idx = (Integer) win[0];
                JSONObject obj = (JSONObject) win[1];
                JSONArray cls = obj.optJSONArray("class");
                if (cls != null) buildClasses(cls, classes, filters, idx);
                appendVodList(vods, obj.optJSONArray("list"), idx);
                ok = true;
            }
        }
        homeClass = classes;
        homeFilter = filters;
        homeVods = vods;
        // 成功按完整 TTL 缓存；全部失败时只缓存 ~30s，尽快重试，避免长时间空首页
        homeTime = ok ? now : (now - HOME_TTL + 30 * 1000L);
    }

    /**
     * 对冲/竞速请求：并发向前若干源请求 ?ac=list&pg=1，取首个成功返回的结果，
     * 从而把首页延迟降到「最快源」的水平，而非被排在前面的慢源阻塞。
     * @return Object[]{Integer 源索引, JSONObject 响应}，全失败返回 null
     */
    private Object[] raceHome() {
        int n = Math.min(sites.size(), 4); // 竞速前 4 个源足够
        if (n <= 0) return null;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        java.util.concurrent.CompletionService<Object[]> cs =
                new java.util.concurrent.ExecutorCompletionService<>(pool);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            cs.submit(new Callable<Object[]>() {
                @Override
                public Object[] call() {
                    try {
                        JSONObject obj = new JSONObject(get(sites.get(idx)[1] + "?ac=list&pg=1"));
                        JSONArray list = obj.optJSONArray("list");
                        // 有内容才算有效，避免用到空壳响应
                        if (list != null && list.length() > 0) return new Object[]{idx, obj};
                    } catch (Exception ignored) {}
                    return null;
                }
            });
        }
        Object[] win = null;
        try {
            for (int i = 0; i < n; i++) {
                Future<Object[]> f = cs.poll(8, TimeUnit.SECONDS);
                if (f == null) break; // 整体超时
                Object[] r = null;
                try { r = f.get(); } catch (Exception ignored) {}
                if (r != null) { win = r; break; } // 首个成功者胜出
            }
        } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return win;
    }

    /** 依据 type_pid 把分类构建成 顶级分类 + 子类型筛选；无层级信息则平铺 */
    private void buildClasses(JSONArray cls, JSONArray classes, JSONObject filters, int idx) {
        String pfx = idx + "@";
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
                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name)) classes.put(clazz(pfx + id, name));
            }
            return;
        }
        try {
            for (java.util.Map.Entry<String, JSONObject> e : parents.entrySet()) {
                String tid = pfx + e.getKey();
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
        loadHome(); // 复用首页缓存，不再重复发请求
        return new JSONObject().put("list", homeVods).toString();
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

        // 豆瓣分类：DB:type:tag
        if (tid.startsWith("DB:")) {
            String[] p = tid.split(":", 3);
            String type = p.length > 1 ? p[1] : "movie";
            String tag = p.length > 2 ? p[2] : "热门";
            JSONArray dv = doubanList(type, tag, pg);
            result.put("list", dv);
            result.put("page", safeInt(pg, 1));
            result.put("pagecount", dv.length() < 20 ? safeInt(pg, 1) : safeInt(pg, 1) + 1);
            result.put("limit", 20);
            result.put("total", 9999);
            putPosterStyle(result);
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
        putPosterStyle(result);
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
        // 按片名归一化去重：同名影片只保留首个命中源，避免多源刷屏
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<JSONArray> f : futures) {
            try {
                JSONArray part = f.get(10, TimeUnit.SECONDS);
                for (int i = 0; i < part.length(); i++) {
                    JSONObject it = part.optJSONObject(i);
                    if (it == null) continue;
                    String dedupKey = normalize(rawName(it.optString("vod_name")));
                    if (!TextUtils.isEmpty(dedupKey) && !seen.add(dedupKey)) continue; // 已见过同名，跳过
                    merged.put(it);
                }
            } catch (Exception ignored) {}
        }
        pool.shutdownNow();
        JSONObject result = new JSONObject().put("list", merged);
        putPosterStyle(result);
        return result.toString();
    }

    // ---------------------------------------------------------------------
    // 豆瓣：海报防盗链处理 + 热门列表
    // ---------------------------------------------------------------------
    /**
     * 处理豆瓣海报防盗链：
     *  - 配了 doubanImageProxy：走代理模板（{url} 占位，或直接拼在后面），由代理补 Referer，
     *    兼容所有客户端（推荐，尤其你已有 nginx）。
     *  - 未配：附加 @Referer=... 头，FongMi/影视仓系客户端会据此带 Referer 请求豆瓣图床。
     */
    private String doubanPic(String cover) {
        if (TextUtils.isEmpty(cover)) return "";
        if (!TextUtils.isEmpty(doubanImgProxy)) {
            try {
                // 三种代理写法自动识别：
                //  1) 含 {url}：整条豆瓣URL塞进去（路径型代理，如 /dbimg/<原URL>）
                //  2) 含 '='  ：URL编码后拼末尾（查询型代理，如 ...?url=<编码URL>）
                //  3) 其余    ：host 替换型 CDN（如「豆瓣CDN by CMLiussss」）——
                //               把 imgN.doubanio.com 换成 CDN 域名，保留原路径，CDN 侧补 Referer 回源
                if (doubanImgProxy.contains("{url}")) {
                    return doubanImgProxy.replace("{url}", cover);
                }
                if (doubanImgProxy.contains("=")) {
                    return doubanImgProxy + URLEncoder.encode(cover, "UTF-8");
                }
                String base = doubanImgProxy.replaceAll("/+$", "");   // 去掉结尾多余的 /
                return cover.replaceFirst("^https?://[^/]+", base);   // 换掉 scheme+host，留路径
            } catch (Exception e) {
                return cover;
            }
        }
        // 未配外部代理：优先走宿主本地代理，由 jar 在服务端补豆瓣 Referer 取图，
        // 对所有客户端通用（客户端只看到本地地址，不受豆瓣防盗链影响）。
        try {
            String proxy = proxyUrl();
            if (!TextUtils.isEmpty(proxy)) {
                return proxy + "?do=juhe&type=img&url=" + URLEncoder.encode(cover, "UTF-8");
            }
        } catch (Exception ignored) {}
        // 取不到本地代理地址时退化成 @Referer 后缀（仅 FongMi/影视仓系客户端认）。
        return cover + "@Referer=" + doubanReferer;
    }

    private JSONArray doubanList(String type, String tag, String pg) throws Exception {
        int start = (Math.max(1, safeInt(pg, 1)) - 1) * 20;
        String url = doubanApi + "?type=" + type
                + "&tag=" + URLEncoder.encode(tag, "UTF-8")
                + "&sort=recommend&page_limit=20&page_start=" + start;
        JSONArray out = new JSONArray();
        String body = getReferer(url, doubanReferer);
        if (TextUtils.isEmpty(body)) return out;
        JSONArray subs = new JSONObject(body).optJSONArray("subjects");
        if (subs == null) return out;
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null) continue;
            String title = s.optString("title");
            if (TextUtils.isEmpty(title)) continue;
            String cover = s.optString("cover");
            String rate = s.optString("rate");
            if (!TextUtils.isEmpty(cover)) doubanPics.put(title, cover);
            JSONObject v = new JSONObject();
            v.put("vod_id", "DBV:" + title);
            v.put("vod_name", title);
            v.put("vod_pic", doubanPic(cover));
            v.put("vod_remarks", TextUtils.isEmpty(rate) ? "" : ("★" + rate));
            out.put(v);
        }
        return out;
    }

    /**
     * 豆瓣条目 -> 各资源站并发聚合搜索，把命中项的播放地址聚成多线路详情。
     * 一个源可能有多个播放组(以 $$$ 分隔)，逐组打标为「源名·组名」，方便选可用线路。
     */
    private String detailFromSources(final String title) throws Exception {
        final String cover = doubanPics.get(title);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, sites.size()));
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            final int idx = i;
            futures.add(pool.submit(new Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    try {
                        String url = sites.get(idx)[1] + "?ac=detail&wd="
                                + URLEncoder.encode(title, "UTF-8");
                        JSONObject obj = new JSONObject(get(url));
                        JSONObject best = pickMatch(obj.optJSONArray("list"), title);
                        if (best == null) return null;
                        String playUrl = best.optString("vod_play_url");
                        if (TextUtils.isEmpty(playUrl)) return null;
                        JSONObject r = new JSONObject();
                        r.put("src", sites.get(idx)[0]);
                        r.put("from", best.optString("vod_play_from"));
                        r.put("url", playUrl);
                        r.put("pic", best.optString("vod_pic"));
                        r.put("content", best.optString("vod_content"));
                        r.put("year", best.optString("vod_year"));
                        return r;
                    } catch (Exception e) {
                        return null;
                    }
                }
            }));
        }

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        String pic = TextUtils.isEmpty(cover) ? "" : doubanPic(cover);
        String content = "", year = "";
        for (Future<JSONObject> f : futures) {
            try {
                JSONObject r = f.get(12, TimeUnit.SECONDS);
                if (r == null) continue;
                String src = r.optString("src");
                String[] fromArr = r.optString("from").split("\\$\\$\\$");
                String[] urlArr = r.optString("url").split("\\$\\$\\$");
                for (int g = 0; g < urlArr.length; g++) {
                    if (TextUtils.isEmpty(urlArr[g])) continue;
                    String grp = (fromArr.length > 1 && g < fromArr.length
                            && !TextUtils.isEmpty(fromArr[g])) ? ("·" + fromArr[g]) : "";
                    froms.add(src + grp);
                    urls.add(urlArr[g]);
                }
                if (TextUtils.isEmpty(pic)) pic = r.optString("pic");
                if (TextUtils.isEmpty(content)) content = r.optString("content");
                if (TextUtils.isEmpty(year)) year = r.optString("year");
            } catch (Exception ignored) {}
        }
        pool.shutdownNow();

        JSONObject v = new JSONObject();
        v.put("vod_id", "DBV:" + title);
        v.put("vod_name", title);
        v.put("vod_pic", pic);
        v.put("vod_year", year);
        if (froms.isEmpty()) {
            v.put("vod_content", "未在资源站找到「" + title + "」的播放地址，可试试顶部搜索换个关键词。");
        } else {
            v.put("vod_content", TextUtils.isEmpty(content) ? title : content);
            v.put("vod_play_from", join(froms, "$$$"));
            v.put("vod_play_url", join(urls, "$$$"));
        }
        return new JSONObject().put("list", new JSONArray().put(v)).toString();
    }

    /** 从搜索结果里挑名称最匹配、且带播放地址的一条：优先完全相等，其次互相包含 */
    private JSONObject pickMatch(JSONArray list, String title) {
        if (list == null) return null;
        String norm = normalize(title);
        JSONObject contains = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.optJSONObject(i);
            if (it == null) continue;
            if (TextUtils.isEmpty(it.optString("vod_play_url"))) continue;
            String nm = normalize(it.optString("vod_name"));
            if (nm.equals(norm)) return it;
            if (contains == null && (nm.contains(norm) || norm.contains(nm))) contains = it;
        }
        return contains;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    /** 去掉列表里名称尾部的【源名】标记，得到纯片名（用于去重比对） */
    private String rawName(String s) {
        if (s == null) return "";
        return s.replaceAll("【[^】]*】\\s*$", "").trim();
    }

    private String join(List<String> arr, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(arr.get(i));
        }
        return sb.toString();
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

        // 豆瓣条目：用标题回资源站聚合搜索，聚成多线路详情
        if (vid.startsWith("DBV:")) {
            return detailFromSources(vid.substring(4));
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
        if (TextUtils.isEmpty(url)) return null;

        // 图片代理：豆瓣海报防盗链，服务端补 Referer 取图后原样吐回
        if ("img".equals(type)) {
            try {
                Object[] r = getBytesReferer(url, doubanReferer);
                if (r == null) return null;
                String ct = (String) r[0];
                byte[] data = (byte[]) r[1];
                return new Object[]{200, TextUtils.isEmpty(ct) ? "image/jpeg" : ct,
                        new ByteArrayInputStream(data)};
            } catch (Exception e) {
                return null; // 失败则客户端仍会退化成首字占位图，不影响其它功能
            }
        }

        if (!"m3u8".equals(type)) return null;
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

    /** 二进制拉取（带可选 Referer），仅 2xx 才返回 {contentType, bytes}，否则 null */
    private Object[] getBytesReferer(String urlStr, String referer) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            if (!TextUtils.isEmpty(referer)) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream is = conn.getInputStream();
            if (is == null) return null;
            String ct = conn.getContentType();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            return new Object[]{ct, bos.toByteArray()};
        } finally {
            if (conn != null) conn.disconnect();
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
        // 中位数拿不到、或分片太少(样本不足中位数不可信，易误删正常片尾/短片)就不过滤，直接绝对化返回
        if (median <= 0 || durs.size() < 6) {
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

    /** 反射获取宿主本地代理地址，取不到就返回空（不同 TVBox 分支实现不一）。成功结果缓存，避免每张海报重复反射 */
    private volatile String proxyBase;

    private String proxyUrl() {
        if (!TextUtils.isEmpty(proxyBase)) return proxyBase;
        String[][] targets = {
                {"com.github.catvod.utils.Proxy", "getUrl"},
                {"com.github.catvod.server.Server", "getAddress"},
        };
        for (String[] t : targets) {
            try {
                Class<?> cls = Class.forName(t[0]);
                Method m = cls.getMethod(t[1]);
                Object r = m.invoke(null);
                if (r != null && !TextUtils.isEmpty(r.toString())) {
                    proxyBase = r.toString();   // 仅缓存成功结果；失败(空)下次再探
                    return proxyBase;
                }
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

    /** 给结果附加竖版海报卡片样式（FongMi 支持），让影片按 2:3 比例渲染，观感统一 */
    private void putPosterStyle(JSONObject result) {
        try {
            result.put("style", new JSONObject().put("type", "rect").put("ratio", 0.72));
        } catch (Exception ignored) {}
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
        return getReferer(urlStr, null);
    }

    private String getReferer(String urlStr, String referer) throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA);
        if (!TextUtils.isEmpty(referer)) header.put("Referer", referer);
        // 优先走宿主 OkHttp：复用连接池、并统一吃配置里的 DoH / 代理。
        // 不可用或异常时回退到裸 HttpURLConnection，保证在任何分支都能跑。
        String s = okhttpString(urlStr, header);
        if (s != null) return s;
        return rawGet(urlStr, referer);
    }

    // 反射调用宿主 com.github.catvod.net.OkHttp.string(url, header)；探测结果缓存。
    private static volatile Method OKHTTP_STRING;
    private static volatile boolean OKHTTP_PROBED;

    private String okhttpString(String url, Map<String, String> header) {
        try {
            if (!OKHTTP_PROBED) {
                synchronized (JuHe.class) {
                    if (!OKHTTP_PROBED) {
                        try {
                            Class<?> cls = Class.forName("com.github.catvod.net.OkHttp");
                            OKHTTP_STRING = cls.getMethod("string", String.class, Map.class);
                        } catch (Throwable t) {
                            OKHTTP_STRING = null;
                        }
                        OKHTTP_PROBED = true;
                    }
                }
            }
            if (OKHTTP_STRING == null) return null;
            Object r = OKHTTP_STRING.invoke(null, url, header);
            String s = r == null ? null : r.toString();
            return TextUtils.isEmpty(s) ? null : s; // 空当失败，交给下面回退
        } catch (Throwable t) {
            return null;
        }
    }

    /** 裸 HttpURLConnection 回退实现，逐行读取并保留换行（m3u8 清洗依赖行结构） */
    private String rawGet(String urlStr, String referer) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            if (!TextUtils.isEmpty(referer)) conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
