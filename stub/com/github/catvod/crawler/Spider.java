package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译占位用的 Spider 基类桩 (stub)。
 * 只在编译期提供签名；打包 dex 时不包含它，运行时由 TVBox 宿主提供真正的实现。
 */
public class Spider {
    public void init(Context context) {}
    public void init(Context context, String extend) {}

    public String homeContent(boolean filter) throws Exception { return ""; }
    public String homeVideoContent() throws Exception { return ""; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception { return ""; }
    public String detailContent(List<String> ids) throws Exception { return ""; }
    public String searchContent(String key, boolean quick) throws Exception { return ""; }
    public String searchContent(String key, boolean quick, String pg) throws Exception { return ""; }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return ""; }

    public boolean manualVideoCheck() throws Exception { return false; }
    public boolean isVideoFormat(String url) throws Exception { return false; }
    public Object[] proxyLocal(Map<String, String> params) throws Exception { return null; }
    public void destroy() {}
}
