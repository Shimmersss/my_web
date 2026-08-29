package com.web.backen.matchmaking;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public aggregate context only; no value here is used as an individual score or recommendation weight. */
final class MatchmakingBenchmarks {
    static final String VERSION = "NBS-2025-C06-18-C22-24";
    static final String SOURCE = "国家统计局《中国统计年鉴 2025》表 C06-18、C22-24（2024 年）";
    static final String URL = "https://www.stats.gov.cn/sj/ndsj/2025/indexch.htm";
    private static final Map<String, Province> PROVINCES = provinces();
    private static final Map<String, String> CITIES = Map.ofEntries(
            Map.entry("北京", "北京"), Map.entry("上海", "上海"), Map.entry("天津", "天津"), Map.entry("重庆", "重庆"),
            Map.entry("广州", "广东"), Map.entry("深圳", "广东"), Map.entry("杭州", "浙江"), Map.entry("南京", "江苏"),
            Map.entry("成都", "四川"), Map.entry("武汉", "湖北"), Map.entry("西安", "陕西"), Map.entry("长沙", "湖南"),
            Map.entry("郑州", "河南"), Map.entry("青岛", "山东"), Map.entry("厦门", "福建"), Map.entry("苏州", "江苏"));

    static Map<String, Object> context(String city) {
        String normalized = city == null ? "" : city.trim();
        String province = CITIES.getOrDefault(normalized, normalized);
        Province value = PROVINCES.get(province);
        if (value == null) throw new IllegalArgumentException("请选择支持的城市或省份");
        boolean municipality = CITIES.containsKey(normalized) && normalized.equals(province);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selection", normalized.isBlank() ? province : normalized);
        result.put("province", province);
        result.put("granularity", municipality ? "municipality" : normalized.equals(province) ? "province" : "provinceFallback");
        result.put("granularityLabel", municipality ? "直辖市公开聚合基准" : normalized.equals(province) ? "省级公开聚合基准" : "暂以所属省级公开聚合基准说明");
        result.put("disposableIncome", value.income());
        result.put("marriageRegistrationsWan", value.marriages());
        result.put("statYear", 2024); result.put("sourceVersion", VERSION); result.put("source", SOURCE); result.put("sourceUrl", URL);
        return result;
    }

    static Map<String, Object> catalogue() {
        return Map.of("cities", CITIES.keySet().stream().sorted().toList(), "provinces", PROVINCES.keySet(),
                "sourceVersion", VERSION, "source", SOURCE, "sourceUrl", URL, "statYear", 2024);
    }

    private static Map<String, Province> provinces() {
        Map<String, Province> values = new LinkedHashMap<>();
        add(values,"北京",85415,11.45); add(values,"天津",53581,6.04); add(values,"河北",34665,27.94); add(values,"山西",32441,16); add(values,"内蒙古",40077,10.29); add(values,"辽宁",39844,14.95); add(values,"吉林",31318,10.21); add(values,"黑龙江",31269,13.13); add(values,"上海",88366,9.02); add(values,"江苏",55415,35.61); add(values,"浙江",67013,23.81); add(values,"安徽",36782,28.44); add(values,"福建",47857,15.16); add(values,"江西",36007,18.01); add(values,"山东",42077,35.30); add(values,"河南",31552,47.13); add(values,"湖北",36947,23.02); add(values,"湖南",37679,23.26); add(values,"广东",51474,51.19); add(values,"广西",31125,20.82); add(values,"海南",34829,4.68); add(values,"重庆",39713,14.68); add(values,"四川",34325,39.44); add(values,"贵州",28561,26.12); add(values,"云南",29932,26.24); add(values,"西藏",31358,2.76); add(values,"陕西",33905,16.84); add(values,"甘肃",26612,13.02); add(values,"青海",30117,3.89); add(values,"宁夏",33355,3.98); add(values,"新疆",30899,18.12); return values;
    }
    private static void add(Map<String, Province> map, String name, int income, double marriages) { map.put(name, new Province(income, marriages)); }
    private record Province(int income, double marriages) {}
}
