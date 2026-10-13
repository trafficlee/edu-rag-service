package com.traffic.rag.controller;

import com.traffic.rag.client.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/llm-test")
public class LlmTestController {

    private final LlmClient llmClient;

    public LlmTestController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @GetMapping("/generate")
    public Map<String, Object> generate(
            @RequestParam("question") String question) throws IOException {

        String context = """
                [资料1]
                文档ID：jz1_001
                标题：一级建造师考试报名条件
                内容：报考一级建造师需要满足相应的学历、专业和工作年限要求。
                考生应根据自己的学历背景、所学专业和工作经历，
                核对当年度官方考试报名条件。
                """;

        String answer = llmClient.generate(question, context);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("model", "qwen3.5:2b-q4_K_M");

        return result;
    }
}