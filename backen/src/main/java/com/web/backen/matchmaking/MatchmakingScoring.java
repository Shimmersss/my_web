package com.web.backen.matchmaking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, transparent scoring for the matchmaking market positioning report.
 * Same input always produces the same scores; every dimension ships its own basis line.
 * Age and height curves are gender specific (the user opted into market-realistic curves);
 * every other rule is identical for all genders. Marriage history, hukou, ethnicity and
 * health deliberately never enter the score.
 */
final class MatchmakingScoring {
    static final List<String> DIMENSION_NAMES = List.of("经济基础", "学历职业", "年龄外形", "家庭支持", "性格相处");
    private static final Map<String, Double> INCOME_MIDPOINTS = Map.ofEntries(
            Map.entry("3千以下", 2500d), Map.entry("3千-5千", 4000d), Map.entry("5千-8千", 6500d),
            Map.entry("8千-1万", 9000d), Map.entry("1万-1万5", 12500d), Map.entry("1万5-2万", 17500d),
            Map.entry("2万-3万", 25000d), Map.entry("3万-5万", 40000d), Map.entry("5万以上", 60000d),
            Map.entry("不愿透露", 0d));

    private MatchmakingScoring() {}

    static double incomeMidpoint(String band) { return band == null ? 0d : INCOME_MIDPOINTS.getOrDefault(band, 0d); }

    static Map<String, Object> score(Map<String, Object> p) {
        Map<?, ?> personality = p.get("personality") instanceof Map<?, ?> m ? m : Map.of();
        List<Map<String, Object>> dimensions = List.of(
                economy(p), educationCareer(p), ageAppearance(p), familySupport(p), character(personality));
        double total = 0;
        for (Map<String, Object> dimension : dimensions) total = round(total + (double) dimension.get("score"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("level", total >= 85 ? "优选档" : total >= 70 ? "稳健档" : total >= 55 ? "潜力档" : "提升档");
        result.put("personalityType", MatchmakingPersonality.type(personality));
        result.put("dimensions", dimensions);
        return result;
    }

    private static Map<String, Object> dimension(String name, double max, double value, String basis) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name); item.put("score", round(Math.min(value, max))); item.put("max", max); item.put("basis", basis);
        return item;
    }

    private static Map<String, Object> economy(Map<String, Object> p) {
        String band = string(p, "incomeBand");
        double income = band.isBlank() || "不愿透露".equals(band) ? 3 : switch (band) {
            case "3千以下" -> 1; case "3千-5千" -> 2; case "5千-8千" -> 3.5; case "8千-1万" -> 5;
            case "1万-1万5" -> 6; case "1万5-2万" -> 7; case "2万-3万" -> 8; case "3万-5万" -> 9.5; default -> 10; };
        double savings = number(p, "savings");
        double savingsScore = savings <= 0 ? 0 : savings < 100_000 ? 2 : savings < 300_000 ? 4 : savings < 1_000_000 ? 5.5 : 6;
        String savingsBucket = savings <= 0 ? "暂无储蓄" : savings < 100_000 ? "十万以内" : savings < 300_000 ? "十万到三十万" : savings < 1_000_000 ? "三十万到一百万" : "一百万以上";
        String housing = string(p, "housingStatus");
        double housingScore = switch (housing) {
            case "自有房无贷款" -> 6; case "自有房有月供" -> 4; case "与父母同住" -> 4.5; case "租住" -> 2.5; default -> 3; };
        String car = string(p, "hasCar");
        double carScore = switch (car) { case "有" -> 3; case "有贷款" -> 2; case "无" -> 1; default -> 1.5; };
        String basis = "收入档" + (band.isBlank() ? "未填" : band) + "、储蓄" + savingsBucket + "、住房"
                + (housing.isBlank() ? "未填" : housing) + "、车辆" + (car.isBlank() ? "未填" : car);
        return dimension("经济基础", 25, income + savingsScore + housingScore + carScore, basis);
    }

    private static Map<String, Object> educationCareer(Map<String, Object> p) {
        String education = string(p, "education");
        double educationScore = switch (education) { case "博士及以上" -> 8; case "硕士" -> 7; case "本科" -> 5; case "大专" -> 3; default -> 4; };
        String jobType = string(p, "jobType");
        double jobScore = switch (jobType) {
            case "公务员" -> 8; case "事业编" -> 7.5; case "国企" -> 7; case "外企", "民营大厂" -> 6;
            case "民营中小企业" -> 4.5; case "自由职业" -> 4; case "个体经营" -> 3.5; default -> 4; };
        String intensity = string(p, "workIntensity");
        double intensityScore = switch (intensity) {
            case "965", "自由安排" -> 3; case "大小周" -> 2; case "经常出差" -> 1.5; case "996", "倒班" -> 1; default -> 2; };
        double years = number(p, "workYears");
        double yearsBonus = years >= 3 && years <= 15 ? 1 : 0;
        String basis = (education.isBlank() ? "学历未填" : education) + "、" + (jobType.isBlank() ? "单位性质未填" : jobType)
                + "、工作" + (years > 0 ? (int) years + "年" : "年限未填") + (intensity.isBlank() ? "" : "、" + intensity);
        return dimension("学历职业", 20, educationScore + jobScore + intensityScore + yearsBonus, basis);
    }

    private static Map<String, Object> ageAppearance(Map<String, Object> p) {
        String gender = string(p, "gender");
        boolean male = "男".equals(gender);
        boolean female = "女".equals(gender);
        double age = number(p, "age");
        double ageScore;
        String ageBasis;
        if (age < 16) { ageScore = 6; ageBasis = "年龄未填"; }
        else { ageScore = male ? maleAge(age) : female ? femaleAge(age) : (maleAge(age) + femaleAge(age)) / 2; ageBasis = (int) age + "岁"; }
        double height = number(p, "heightCm");
        double heightScore;
        if (height < 100) heightScore = 3;
        else if (male) heightScore = height >= 180 ? 4 : height >= 175 ? 3.5 : height >= 172 ? 3 : height >= 168 ? 2.5 : 2;
        else if (female) heightScore = height >= 168 ? 4 : height >= 163 ? 3.5 : height >= 160 ? 3 : height >= 156 ? 2.5 : 2;
        else heightScore = height >= 175 ? 4 : height >= 170 ? 3.5 : height >= 165 ? 3 : height >= 160 ? 2.5 : 2;
        String appearance = string(p, "appearanceSelf");
        double appearanceScore = switch (appearance) { case "出众" -> 4; case "中上" -> 3; case "一般" -> 2; default -> 2.5; };
        String basis = ageBasis + (height >= 100 ? "、身高" + (int) height + "cm" : "、身高未填") + "、外形自评" + (appearance.isBlank() ? "未评" : appearance);
        return dimension("年龄外形", 20, ageScore + heightScore + appearanceScore, basis);
    }

    private static double maleAge(double age) {
        if (age < 24) return 10;
        if (age < 29) return 11;
        if (age < 34) return 12;
        if (age < 37) return 11;
        if (age < 41) return 9;
        if (age < 46) return 7;
        return 5;
    }
    private static double femaleAge(double age) {
        if (age < 24) return 10;
        if (age < 28) return 12;
        if (age < 32) return 11;
        if (age < 35) return 9;
        if (age < 39) return 7;
        if (age < 44) return 5;
        return 4;
    }

    private static Map<String, Object> familySupport(Map<String, Object> p) {
        String onlyChild = string(p, "onlyChild");
        double onlyScore = "是".equals(onlyChild) ? 3 : "否".equals(onlyChild) ? 2 : 2;
        String pension = string(p, "parentsPension");
        double pensionScore = switch (pension) { case "有稳定退休金" -> 5; case "有部分" -> 3.5; case "无" -> 2; default -> 3; };
        String health = string(p, "parentsHealth");
        double healthScore = switch (health) { case "健康" -> 4; case "一般" -> 3; case "需要照顾" -> 2; default -> 3; };
        String support = string(p, "parentsSupport");
        double supportScore = switch (support) {
            case "资助购房+帮带娃均可" -> 3; case "可资助购房" -> 2.5; case "可帮带娃" -> 2; case "暂无支持" -> 1; default -> 1.5; };
        String basis = "独生子女" + ("是".equals(onlyChild) ? "是" : "否") + "、父母" + (pension.isBlank() ? "退休金未填" : pension)
                + "、" + (health.isBlank() ? "健康未填" : health) + "、家庭" + (support.isBlank() ? "支持未填" : support);
        return dimension("家庭支持", 15, onlyScore + pensionScore + healthScore + supportScore, basis);
    }

    private static Map<String, Object> character(Map<?, ?> personality) {
        double sum = 0;
        for (MatchmakingPersonality.Question question : MatchmakingPersonality.QUESTIONS) {
            Object answer = personality.get(question.key());
            sum += MatchmakingPersonality.points(question.key(), answer == null ? "" : String.valueOf(answer));
        }
        String type = MatchmakingPersonality.type(personality);
        String basis = "8 题自评合计 " + round(sum) + "/24，类型：" + type;
        return dimension("性格相处", 20, sum * 20 / 24, basis);
    }

    private static double round(double value) { return Math.round(value * 2) / 2.0; }
    private static String string(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? "" : String.valueOf(v); }
    private static double number(Map<String, Object> m, String key) { return m.get(key) instanceof Number n ? n.doubleValue() : 0d; }
}
