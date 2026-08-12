# zy2 聚合搜索 Spider（饭太硬风格）

一个 TVBox / CatVod 的自定义 `spider` jar，提供：

- **聚合搜索**：一次搜索并发查询多个采集站（苹果CMS/maccms 的 `?ac=list&wd=` 接口），合并结果，名称后带 `【源名】`。
- **豆瓣主界面（可配置）**：`douban:true` 时首页/分类改用豆瓣热门，海报走豆瓣 CDN；点开某片后用片名回资源站**并发聚合搜索**，把各源命中的播放地址聚成多线路详情，直接播放。
- **自定义公告/提醒**：首页第一个分类「📢公告」，点击进入详情显示公告文本；公告可内联写死，也可从远程 URL 拉取。
- **首页竞速加载**：无豆瓣时，并发向前 4 个源请求，谁先返回用谁，避免被单个慢源拖住；结果缓存 10 分钟。
- **分类浏览**：豆瓣分类（可配）或第一个源的分类树。
- **豆瓣海报防盗链处理**：豆瓣图床有 Referer 防盗链，客户端直连会 403/418、退化成“首字占位图”。支持三种代理写法（host 替换 CDN / `{url}` 路径型 / `?url=` 查询型），或不配代理时由 jar 服务端补 Referer 取图（全客户端通用）。地址全走配置，jar 不写死。
- **m3u8 播放去广告**：直链走本地代理，以分片时长中位数为基准剔除广告分片（过短/过长的插入片），并丢弃 `#EXT-X-DISCONTINUITY`、把相对地址绝对化。

## 目录结构

```
zy2-spider/
├── src/com/github/catvod/spider/JuHe.java   业务代码（聚合+公告）
├── stub/com/github/catvod/crawler/Spider.java  编译占位基类（不打包进 jar）
├── build.sh                                  编译脚本 javac -> d8 -> jar
└── README.md
```

## 为什么不能直接给你编译好的 jar

TVBox 用的是 Android 的 `DexClassLoader`，jar 里必须是 **classes.dex**（Android 字节码），
而不是普通 `.class`。生成 dex 需要 Android SDK 的 `d8` 工具，所以要在装了
Android SDK 的机器上跑一次 `build.sh`。源码本身是完整、可直接编译的。

## 构建

需要：JDK 8+、Android SDK（`build-tools` 含 `d8`，`platforms` 含 `android.jar`）。

```bash
export ANDROID_HOME=/path/to/Android/Sdk
cd zy2-spider
bash build.sh          # 产出 juhe.jar
```

Windows 下可用 Git Bash 运行，或把命令换成 `d8.bat`。也可以直接把 `src` 丢进
Android Studio 的一个 module，用 IDE 的 d8 出包。

产出的 `juhe.jar` 放到你配置里 `spider` 指向的位置：`./jar/juhe.jar`。

## 加进你的配置

`spider` 字段保持不变（`./jar/juhe.jar`），在 `sites` 里加一个 `type:3` 的入口：

`ext` 推荐直接写成对象（影视仓/FongMi 系支持，底层会自动传给 jar），比转义字符串好读好改：

```json
{
  "key": "juhe",
  "name": "🔍聚合搜索",
  "type": 3,
  "api": "csp_JuHe",
  "searchable": 1,
  "quickSearch": 1,
  "filterable": 1,
  "ext": {
    "douban": true,
    "sites": [
      { "name": "量子", "api": "https://cj.lzcaiji.com/api.php/provide/vod" },
      { "name": "非凡", "api": "https://api.ffzyapi.com/api.php/provide/vod" },
      { "name": "暴风", "api": "https://bfzyapi.com/api.php/provide/vod" }
    ]
  }
}
```

- `api` 必须是 `csp_` + 类名，即 `csp_JuHe`（对应 `JuHe.java`）。
- **必填**：`sites`（聚合的采集站）。其余都可省略，jar 有默认值：
  - `douban`：省略即 `false`（用源站竞速首页）；填 `true` 用豆瓣主界面。
  - `doubanApi` / `doubanCategories`：省略用内置默认，需要时再覆盖。
  - `doubanImageProxy` / `doubanReferer`：豆瓣海报代理与 Referer，见下「豆瓣海报防盗链 / CDN 配置」；省略走本地代理兜底。
  - `notice` / `noticeUrl`：公告文本 / 远程 txt 地址；省略则「📢公告」卡片显示“暂无公告”（jar 不内置公告）。
- 若某些老 App 不认对象 `ext`，把它整体转成一行字符串（`"ext":"{...}"`）也可；或写成一个返回该 JSON 的 http 地址，方便远程改源。

### 豆瓣主界面配置（可选）

在 `ext` 里加：

```json
"douban": true,
"doubanApi": "https://movie.douban.com/j/search_subjects",
"doubanCategories": [
  { "name": "热门电影", "type": "movie", "tag": "热门" },
  { "name": "豆瓣高分", "type": "movie", "tag": "豆瓣高分" },
  { "name": "热门剧集", "type": "tv", "tag": "热门" },
  { "name": "综艺", "type": "tv", "tag": "综艺" }
]
```

- `douban:true` 开启后，首页/分类用豆瓣热门（海报来自豆瓣 CDN），关闭则回退到源站分类竞速首页。
- `type` 取 `movie` 或 `tv`；`tag` 是豆瓣标签（热门/最新/豆瓣高分/国产剧/美剧/日本动画…）。
- 不填 `doubanCategories` 会用一套内置默认分类。
- `doubanApi` 可换成你自己的豆瓣接口/镜像。
- 点开豆瓣海报 → 用片名并发搜所有 `sites` → 命中项的播放地址聚成多条线路（标注【源名】），选一条播放。若某片各源都没有，会提示用顶部搜索。

不配 `ext` 时会用内置默认（量子/非凡/暴风/爱奇艺 + 默认公告）。

### 豆瓣海报防盗链 / CDN 配置（可选，强烈建议配）

豆瓣图床（`imgN.doubanio.com`）对图片做了 Referer 防盗链：客户端直接请求会被返回 418/403，
TVBox 便退化成“标题第一个字”的占位图。用 `doubanImageProxy` 指定一个补 Referer 的代理即可解决。

```json
"doubanImageProxy": "https://dbimg.你的域名.com",
"doubanReferer": "https://movie.douban.com/"
```

`doubanImageProxy` 支持三种写法，jar 会按内容自动识别：

| 写法 | 示例 | 适用 |
|---|---|---|
| **host 替换型** | `https://dbimg.你的域名.com` | 换掉 `imgN.doubanio.com` 域名、保留原路径。CDN 型代理（如 CMLiussss 豆瓣 CDN、自建 CF Worker）用这种。 |
| **`{url}` 路径型** | `https://你的域名/dbimg/{url}` | 把整条豆瓣 URL 塞进 `{url}` 占位符。路径型反代（如 nginx `location /dbimg/`）用这种。 |
| **`?url=` 查询型** | `https://你的域名/img?url=` | 把豆瓣 URL 编码后拼在末尾。查询参数型代理用这种。 |

- **不配 `doubanImageProxy`**：jar 走宿主本地代理，在服务端带 `doubanReferer` 取图后返回，**对所有客户端通用**；取不到本地代理地址时退化成 `图片URL@Referer=...` 后缀（仅 FongMi/影视仓系客户端认）。
- `doubanReferer`：补给豆瓣图床/接口的 Referer，省略默认 `https://movie.douban.com/`。
- **换 CDN/域名只改这一行，不用重编 jar。** 公共域名寿命不稳，建议自建（见下）。

#### 自建 Cloudflare Worker（host 替换型，最稳）

在自己的 Cloudflare 账号部署一个反代 Worker，用自有域名，不受公共域名关停影响：

```js
export default {
  hosts: [
    "https://img1.doubanio.com", "https://img2.doubanio.com",
    "https://img3.doubanio.com", "https://img9.doubanio.com",
  ],
  async fetch(request) {
    const url = new URL(request.url);
    const pathq = url.pathname + url.search;
    for (const host of this.hosts) {
      try {
        const resp = await fetch(host + pathq, {
          headers: {
            "Referer": "https://movie.douban.com/",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
          },
          cf: { cacheTtl: 86400, cacheEverything: true },
        });
        if (resp.ok) {
          const headers = new Headers(resp.headers);
          headers.set("Access-Control-Allow-Origin", "*");
          headers.set("Cache-Control", "public, max-age=86400");
          return new Response(resp.body, { status: resp.status, headers });
        }
      } catch (e) {}
    }
    return new Response("douban image unavailable", { status: 502 });
  },
};
```

- 部署后在 Worker 的 **Settings → Domains & Routes** 绑定自有域名（`*.workers.dev` 在国内多不稳，务必绑自有域名）。
- 验证：浏览器打开 `https://你的域名/view/photo/s_ratio_poster/public/p2934049524.jpg`，返回图片即可。
- 配置里把 `doubanImageProxy` 填成 `https://你的域名` 即可（host 替换型）。

## 自定义提醒的三种玩法

1. **内联**：`ext` 里 `"notice":"...\n..."`，改配置即改公告。
2. **远程文本**：`"noticeUrl":"https://你的域名/notice.txt"`，改服务器上的 txt 即可。
3. **整体远程**：`"ext":"https://你的域名/juhe.json"`，源列表和公告一起远程托管。

## 注意

- 采集站接口地址会失效/被墙，`sites` 请换成你自己可用的源。
- `playerContent` 对 `.m3u8/.mp4/.flv/.ts/.mkv` 直链返回 `parse:0` 直接播放，
  其它（如需网页解析的 vip 链接）返回 `parse:1`，交给配置里的 `parses` 处理。
- 想改类名（比如叫 `csp_Juhe` 之外的名字），改 `JuHe.java` 的类名并同步 `api` 字段。
