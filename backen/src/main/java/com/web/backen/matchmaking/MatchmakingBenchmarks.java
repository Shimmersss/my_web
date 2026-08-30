package com.web.backen.matchmaking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public aggregate context only; no value here is used as an individual score or recommendation weight.
 * Income: NBS 2025 annual release (statistical year 2025, all 31 provinces).
 * Marriage: MCA 2025 Q4 release for the national total; province-level registrations keep the
 * 2025 statistical yearbook (2024 figures) because only 12 provinces had published 2025 values.
 */
final class MatchmakingBenchmarks {
    static final String VERSION = "NBS-2025-Income+MCA-2025Q4-Marriage";
    static final int INCOME_STAT_YEAR = 2025;
    static final int NATIONAL_INCOME = 43377;
    static final int MARRIAGE_STAT_YEAR = 2025;
    static final double NATIONAL_MARRIAGES_WAN = 676.3;
    static final String INCOME_SOURCE = "国家统计局《2025年居民收入和消费支出情况》（2026年1月发布）及各省统计局";
    static final String INCOME_URL = "https://www.stats.gov.cn/sj/zxfb/202601/t20260119_1962321.html";
    static final String MARRIAGE_SOURCE = "民政部《2025年4季度民政统计数据》（2026年2月发布）";
    static final String MARRIAGE_URL = "https://www.mca.gov.cn/mzsj/xzqh/2025/202502tjsj.htm";
    static final String PROVINCE_MARRIAGE_NOTE = "分省结婚登记为《中国统计年鉴 2025》2024 年口径（2025 年仅部分省份公布）";
    static final String PROVINCE_MARRIAGE_URL = "https://www.stats.gov.cn/sj/ndsj/2025/indexch.htm";
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
        result.put("incomeStatYear", INCOME_STAT_YEAR);
        result.put("nationalDisposableIncome", NATIONAL_INCOME);
        result.put("marriageRegistrationsWan", value.marriages());
        result.put("marriageRegistrationsYear", 2024);
        result.put("nationalMarriageRegistrationsWan", NATIONAL_MARRIAGES_WAN);
        result.put("nationalMarriageYear", MARRIAGE_STAT_YEAR);
        result.put("incomeSource", INCOME_SOURCE); result.put("incomeSourceUrl", INCOME_URL);
        result.put("marriageSource", MARRIAGE_SOURCE); result.put("marriageSourceUrl", MARRIAGE_URL);
        result.put("provinceMarriageNote", PROVINCE_MARRIAGE_NOTE); result.put("provinceMarriageUrl", PROVINCE_MARRIAGE_URL);
        result.put("sourceVersion", VERSION);
        return result;
    }

    static Map<String, Object> catalogue() {
        return Map.of("cities", CITIES.keySet().stream().sorted().toList(), "provinces", PROVINCES.keySet(),
                "sourceVersion", VERSION, "incomeSource", INCOME_SOURCE, "incomeSourceUrl", INCOME_URL,
                "incomeStatYear", INCOME_STAT_YEAR, "nationalDisposableIncome", NATIONAL_INCOME);
    }

    private static Map<String, Province> provinces() {
        Map<String, Province> values = new LinkedHashMap<>();
        add(values,"北京",89090,11.45); add(values,"天津",55918,6.04); add(values,"河北",36439,27.94); add(values,"山西",33923,16); add(values,"内蒙古",41921,10.29); add(values,"辽宁",41703,14.95); add(values,"吉林",32881,10.21); add(values,"黑龙江",32851,13.13); add(values,"上海",91987,9.02); add(values,"江苏",57971,35.61); add(values,"浙江",70240,23.81); add(values,"安徽",38755,28.44); add(values,"福建",50302,15.16); add(values,"江西",37846,18.01); add(values,"山东",44180,35.30); add(values,"河南",33215,47.13); add(values,"湖北",38881,23.02); add(values,"湖南",39545,23.26); add(values,"广东",53669,51.19); add(values,"广西",32751,20.82); add(values,"海南",36306,4.68); add(values,"重庆",41580,14.68); add(values,"四川",36120,39.44); add(values,"贵州",30001,26.12); add(values,"云南",31311,26.24); add(values,"西藏",33690,2.76); add(values,"陕西",35790,16.84); add(values,"甘肃",28224,13.02); add(values,"青海",31661,3.89); add(values,"宁夏",35184,3.98); add(values,"新疆",32881,18.12); return values;
    }
    private static void add(Map<String, Province> map, String name, int income, double marriages) { map.put(name, new Province(income, marriages)); }
    private record Province(int income, double marriages) {}
}
