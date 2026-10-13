package com.traffic.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.rag.client.LlmClient;

public class RagEvalApplication {

    public static void main(String[] args) throws Exception {
        // 1. 创建 JSON 处理器
        ObjectMapper objectMapper = new ObjectMapper();

        // 2. 创建真实的 LlmClient
        LlmClient llmClient = new LlmClient(objectMapper);

        // 3. 创建评估执行器
        RagEvalRunner runner = new RagEvalRunner(
                llmClient,
                objectMapper
        );

        // 4. 执行真实评估
        runner.run();
    }
}

