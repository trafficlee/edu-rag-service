package com.traffic.rag.eval;


import java.util.ArrayList;
import java.util.List;

public class RagAnswerEvaluator {

    public EvaluationResult evaluate(
            RagEvalCase testCase,
            String answer) {

        List<String> keyPoints = testCase.keyPoints();

        if (keyPoints == null) {
            keyPoints = List.of();
        }

        boolean emptyAnswer =
                answer == null || answer.isBlank();

        List<String> matchedKeyPoints = new ArrayList<>();
        List<String> missingKeyPoints = new ArrayList<>();

        String normalizedAnswer = normalize(answer);

        for (String keyPoint : keyPoints) {
            if (keyPoint == null || keyPoint.isBlank()) {
                continue;
            }


            List<String> acceptedExpressions = getAcceptedExpressions(keyPoint);

            boolean matched = !emptyAnswer
                    && acceptedExpressions.stream()
                    .map(this::normalize)
                    .anyMatch(normalizedAnswer::contains);

            if (matched) {
                matchedKeyPoints.add(keyPoint);
            } else {
                missingKeyPoints.add(keyPoint);
            }
        }

        int total = matchedKeyPoints.size()
                + missingKeyPoints.size();

        double coverageRate = total == 0
                ? 0.0
                : (double) matchedKeyPoints.size() / total;

        return new EvaluationResult(
                testCase.id(),
                testCase.type(),
                emptyAnswer,
                total,
                matchedKeyPoints.size(),
                coverageRate,
                matchedKeyPoints,
                missingKeyPoints
        );
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        // 第一版只统一空白字符，不做复杂的语义推断
        return text.toLowerCase()
                .replaceAll("\\s+", "");
    }


    private List<String> getAcceptedExpressions(String keyPoint) {
        return switch (keyPoint) {
            case "关键词查询超时为 2 秒" -> List.of(
                    "关键词查询超时为 2 秒",
                    "关键词查询的超时时间为 2 秒",
                    "关键词查询的超时时间是 2 秒"
            );

            case "语义查询超时为 5 秒" -> List.of(
                    "语义查询超时为 5 秒",
                    "语义查询的超时时间为 5 秒",
                    "语义查询的超时时间是 5 秒"
            );

            case "最大重试次数为 3 次" -> List.of(
                    "最大重试次数为 3 次",
                    "默认最大重试次数为 3 次",
                    "默认重试次数是 3 次"
            );

            case "重试次数不包含首次请求" -> List.of(
                    "重试次数不包含首次请求",
                    "不包含第一次请求",
                    "不包含首次请求"
            );

            case "重试间隔为 1 秒" -> List.of(
                    "重试间隔为 1 秒",
                    "两次重试之间等待 1 秒"
            );

            case "存储使用 MySQL" -> List.of(
                    "存储使用 MySQL",
                    "使用 MySQL 存储",
                    "MySQL 存储业务数据"
            );

            case "数据保留期限无法从当前资料确认" -> List.of(
                    "数据保留期限无法从当前资料确认",
                    "未提供数据保留的具体天数",
                    "未说明数据保留期限"
            );

            case "2026 年具体报名截止日期无法从当前资料确认" ->
                    List.of(
                            "2026 年具体报名截止日期无法从当前资料确认",
                            "2026 年的具体报名截止日期资料中未提供",
                            "无法确认 2026 年报名截止日期",
                            "当前资料没有提供2026年报名截止日期"
                    );

            case "现有资料未提供注册用户数量" -> List.of(
                    "现有资料未提供注册用户数量",
                    "未提供注册用户数量",
                    "无法确认注册用户数量"
            );

            case "现有资料未提供开发公司信息" -> List.of(
                    "现有资料未提供开发公司信息",
                    "未提供开发公司信息",
                    "无法确认开发公司"
            );

            default -> List.of(keyPoint);
        };
    }


    public record EvaluationResult(
            String caseId,
            String type,
            boolean emptyAnswer,
            int totalKeyPoints,
            int matchedCount,
            double coverageRate,
            List<String> matchedKeyPoints,
            List<String> missingKeyPoints
    ) {
    }

    public static void main(String[] args) {
        String answer = "当前资料明确指出“当前资料没有提供 2026 年报名截止日期”。";

        String expressionA = "2026 年具体报名截止日期无法从当前资料确认";
        String expressionB = "当前资料没有提供2026年报名截止日期";

        String normalizedAnswer = answer.toLowerCase().replaceAll("\\s+", "");
        String normalizedA = expressionA.toLowerCase().replaceAll("\\s+", "");
        String normalizedB = expressionB.toLowerCase().replaceAll("\\s+", "");

        System.out.println("A 组匹配结果: " + normalizedAnswer.contains(normalizedA));
        System.out.println("B 组匹配结果: " + normalizedAnswer.contains(normalizedB));
    }
}

