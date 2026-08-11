# zy2 聚合搜索 Spider（饭太硬风格）

一个 TVBox / CatVod 的自定义 `spider` jar，提供：

- **聚合搜索**：一次搜索并发查询多个采集站（苹果CMS/maccms 的 `?ac=detail&wd=` 接口），合并结果，名称后带 `【源名】`。
- **自定义公告/提醒**：首页第一个分类「📢公告」，点击进入详情显示公告文本；公告可内联写死，也可从远程 URL 拉取。
- **分类浏览**：借用第一个源的分类树。

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
bash build.sh          # 产出 zy2.jar
```

Windows 下可用 Git Bash 运行，或把命令换成 `d8.bat`。也可以直接把 `src` 丢进
Android Studio 的一个 module，用 IDE 的 d8 出包。

产出的 `zy2.jar` 放到你配置里 `spider` 指向的位置：`./jar/zy2.jar`。

## 加进你的配置

`spider` 字段保持不变（`./jar/zy2.jar`），在 `sites` 里加一个 `type:3` 的入口：

```json
{
  "key": "juhe",
  "name": "🔍聚合搜索",
  "type": 3,
  "api": "csp_JuHe",
  "searchable": 1,
  "quickSearch": 1,
  "filterable": 0,
  "ext": "{\"notice\":\"📢 今日公告\\n· 新增暴风源\\n· 有问题重进App\",\"sites\":[{\"name\":\"量子\",\"api\":\"https://cj.lzcaiji.com/api.php/provide/vod\"},{\"name\":\"非凡\",\"api\":\"https://api.ffzyapi.com/api.php/provide/vod\"},{\"name\":\"暴风\",\"api\":\"https://bfzyapi.com/api.php/provide/vod\"}]}"
}
```

- `api` 必须是 `csp_` + 类名，即 `csp_JuHe`（对应 `JuHe.java`）。
- `ext` 里 `notice` 直接写公告；也可用 `"noticeUrl":"https://xxx/notice.txt"` 远程拉取。
- `ext` 整体也可以写成一个返回上面 JSON 的 http 地址，方便后台随时改公告和源。

不配 `ext` 时会用内置默认（量子/非凡/暴风/爱奇艺 + 默认公告）。

## 自定义提醒的三种玩法

1. **内联**：`ext` 里 `"notice":"...\n..."`，改配置即改公告。
2. **远程文本**：`"noticeUrl":"https://你的域名/notice.txt"`，改服务器上的 txt 即可。
3. **整体远程**：`"ext":"https://你的域名/juhe.json"`，源列表和公告一起远程托管。

## 注意

- 采集站接口地址会失效/被墙，`sites` 请换成你自己可用的源。
- `playerContent` 对 `.m3u8/.mp4/.flv/.ts/.mkv` 直链返回 `parse:0` 直接播放，
  其它（如需网页解析的 vip 链接）返回 `parse:1`，交给配置里的 `parses` 处理。
- 想改类名（比如叫 `csp_Juhe` 之外的名字），改 `JuHe.java` 的类名并同步 `api` 字段。
