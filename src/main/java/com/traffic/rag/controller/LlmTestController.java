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

    @GetMapping("/ab-test")
    public Map<String, Object> abTest(
            @RequestParam("question") String question
    ) throws IOException {

        String context = """
            [资料1]
            文档ID：jz1_001
            标题：一级建造师考试报名条件
            内容：报考一级建造师需要满足相应的学历、专业和工作年限要求。
            考生应根据自己的学历背景、所学专业和工作经历，核对当年度官方考试报名条件。

            [资料2]
            文档ID：jz1_002
            标题：一级建造师工作年限要求
            内容：一级建造师报考需要满足相应的工作年限要求。
            不同学历和专业背景的考生，所需工作年限可能不同。

            [资料3]
            文档ID：jz1_008
            标题：一级建造师报名材料准备
            内容：报名材料可能涉及身份证明、学历证明、专业信息和工作经历等内容。
            考生应根据报名系统及当地考试机构发布的通知准备材料。

            [资料4]
            文档ID：jz1_005
            标题：一级建造师学历要求如何核对
            内容：核对一级建造师报名资格时，应先确认自己的学历层次和学历证明材料，
            再对照当年度官方报名条件判断是否符合要求。

            [资料5]
            文档ID：jz1_006
            标题：一级建造师专业要求如何确认
            内容：判断能否报考一级建造师时，需要核实本人所学专业是否符合
            当年度报考专业要求，不能仅凭专业名称相似就认定符合资格。
            """;

        // A：使用现有默认 Prompt
        String answerA = llmClient.generate(
                question, context);

        // B：结构化 Prompt
        String promptB = """
            你是一个严谨的知识库问答助手。

            请根据参考资料回答问题：
            1. 识别问题中的所有子问题，逐项回答。
            2. 归纳整合资料，不要简单重复原文。
            3. 资料只提供概括性信息时，不得擅自补充具体数字。
            4. 资料缺少答案时，明确指出缺少什么信息。
            5. 按照问题顺序组织答案，使用简体中文，表达简洁。
            """;

        String answerB = llmClient.generate(
                question, context, promptB
        );

        // C：严格证据约束 Prompt
        String promptC = """
            你是一个严格遵循证据的 RAG 问答助手。

            规则：
            1. 只能将参考资料作为事实性答案的依据。
            2. 将问题拆分为独立子问题，逐项检查资料。
            3. 只有资料明确支持的事实，才能作为确定性结论。
            4. 不得使用模型自身知识补全资料缺失的事实。
            5. 资料不足时，明确说明“现有资料无法确定”，并指出缺少什么。
            6. 不要重复与问题无关的资料。

            请按以下格式回答：
            一、问题及答案：逐项回答。
            二、资料缺失项：列出无法确定的内容。
            三、依据：说明结论对应的资料。
            """;

        String answerC = llmClient.generate(
                question, context, promptC
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("context", context);
        result.put("model", "qwen3.5:2b-q4_K_M");

        result.put("A", answerA);
        result.put("B", answerB);
        result.put("C", answerC);

        return result;
    }
}