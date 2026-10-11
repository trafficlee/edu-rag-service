# edu-rag-service：通用 RAG 服务项目总结

> 项目定位：基于 Java、Spring Boot、Elasticsearch 的 RAG 基础服务
>
> 当前阶段：知识入库、Embedding 生成、文档查询、OCC 并发更新
>
> 目标：逐步演进为可复用、可扩展、可独立部署的通用 RAG 服务，并开源至 GitHub。
>
> **状态说明：** 本文区分当前已经实现并实测的功能与未来规划。规划中的能力不代表已经实现。

---

## 1. 项目背景与目标

RAG（Retrieval-Augmented Generation，检索增强生成）通过检索外部知识，为大语言模型提供与问题相关的上下文，从而改善回答的准确性、时效性与可追溯性。

本项目采用 Java 技术栈，围绕 RAG 的基础数据链路逐步构建服务，避免一开始就堆叠复杂的 Agent、工作流和模型编排能力。

当前阶段优先解决以下问题：

1. 如何通过 Embedding 模型将文本转换为向量。
2. 如何将文本内容、业务元数据和向量写入 Elasticsearch。
3. 如何设计稳定的知识切片 ID 和入库模式。
4. 如何查询知识切片及其版本信息。
5. 如何避免并发更新时，旧数据覆盖新数据。
6. 如何将底层异常转换为调用方能够识别的 HTTP 状态码和业务错误码。
7. 如何在此基础上扩展成通用的知识入库和检索服务。

### 1.1 当前已实现的功能

- Spring Boot 服务基础结构。
- Ollama Embedding 接入。
- 使用 BGE-M3 生成文本向量。
- Elasticsearch 文档写入。
- `CREATE` 和 `UPSERT` 两种入库模式。
- 基于 `_seq_no` 和 `_primary_term` 的乐观并发控制（OCC）。
- OCC 冲突异常处理，返回 HTTP 409。
- OCC 专用业务错误码 `RAG_VERSION_CONFLICT`。
- 根据 `chunkId` 查询知识切片及其版本信息。
- 更新成功后返回新的 `seqNo` 和 `primaryTerm`。
- 通过实际 HTTP 请求验证正常更新、冲突拒绝、重新读取及再次更新的流程。

### 1.2 当前尚未完成的能力

以下属于后续规划：

- 文档解析、清洗、切片和批量导入。
- 完整的知识库及租户隔离模型。
- 文档删除、批量写入和异步导入任务。
- BM25 与向量检索的混合召回。
- RRF 融合、重排、邻近切片补全。
- 完整的 RAG 检索 API 和问答编排。
- 生成模型接入、Prompt 管理及 SSE 流式输出。
- 完整的鉴权、限流、监控、链路追踪和审计。
- 自动化测试、容器化部署、CI/CD 和正式发布流程。

---

## 2. 技术栈与开发环境

| 技术 | 当前使用情况 |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Elasticsearch Java API Client | 8.17.6 |
| Elasticsearch | 8.17.6 |
| Embedding 服务 | Ollama |
| Embedding 模型 | `bge-m3:latest` |
| Spring Boot 端口 | `8083` |
| Ollama 地址 | `http://localhost:11434` |
| Elasticsearch 地址 | `http://localhost:9200` |
| Elasticsearch 索引 | `rag_knowledge_vector` |
| 开发环境 | Windows 11、PowerShell |

说明：

- 当前 Elasticsearch Java API Client 版本为 8.17.6。
- 开发日志曾出现底层 `elasticsearch-rest-client` 8.13.4，需要在开源前检查 Maven 依赖树并验证实际兼容性。
- Elasticsearch 当前运行于本地 Kubernetes 学习环境。
- 上述地址是本地开发配置，不应写死为所有使用者的默认环境。
- 当前 Embedding 输出维度预期为 1024，实际值应由模型响应和索引 mapping 共同验证。

---

## 3. 系统整体架构

### 3.1 当前入库架构

```text
                调用方
                  |
                  | HTTP JSON
                  v
        KnowledgeTestController
                  |
                  +--------------------+
                  |                    |
                  v                    v
          参数校验与 DTO         EmbeddingClient
                                       |
                                       | HTTP
                                       v
                                Ollama /api/embed
                                       |
                                       v
                                  BGE-M3
                                       |
                                       v
                               生成文本向量
                                       |
                  +--------------------+
                  |
                  v
            KnowledgeDocument
                  |
                  v
          EsDocumentRepository
                  |
                  v
          Elasticsearch Index
          rag_knowledge_vector
```

### 3.2 当前查询架构

```text
GET /api/knowledge/{chunkId}
                  |
                  v
        KnowledgeTestController
                  |
                  v
       EsDocumentRepository
                  |
                  v
        Elasticsearch Get API
                  |
                  +--> 文档内容
                  +--> _seq_no
                  +--> _primary_term
                  |
                  v
        KnowledgeQueryResponse
```

### 3.3 当前 OCC 更新架构

```text
调用方读取文档及版本
        |
        v
提交完整文档 + seqNo + primaryTerm
        |
        v
Controller 生成 Embedding
        |
        v
Repository 调用 Elasticsearch Index API
携带 if_seq_no + if_primary_term
        |
        +----------------------------+
        |                            |
        v                            v
    版本匹配                     版本不匹配
        |                            |
        v                            v
    更新成功                    HTTP 409
        |                            |
        v                            v
返回新版本信息             OptimisticLockException
                                     |
                                     v
                            GlobalExceptionHandler
                                     |
                                     v
                     RAG_VERSION_CONFLICT + HTTP 409
```

核心原则：

**版本检查与写入必须由 Elasticsearch 原子执行。**

应用层提前读取版本只能用于业务判断，不能代替最终的 OCC 校验，因为在读取、计算 Embedding 和提交写入之间，其他请求仍可能更新文档。

---

## 4. 项目目录与分层职责

当前主要类包括：

```text
edu-rag-service/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── traffic/
    │               └── rag/
    │                   ├── controller/
    │                   │   └── KnowledgeTestController.java
    │                   ├── common/
    │                   │   ├── OptimisticLockException.java
    │                   │   └── GlobalExceptionHandler.java
    │                   ├── dto/
    │                   │   ├── KnowledgeIngestRequest.java
    │                   │   ├── KnowledgeIngestResponse.java
    │                   │   ├── KnowledgeUpdateRequest.java
    │                   │   ├── KnowledgeUpdateResponse.java
    │                   │   └── KnowledgeQueryResponse.java
    │                   ├── model/
    │                   │   ├── KnowledgeDocument.java
    │                   │   └── KnowledgeUpdateResult.java
    │                   ├── repository/
    │                   │   └── EsDocumentRepository.java
    │                   └── ...
    └── test/
```

以上是主要类的示意目录，不保证包含项目中所有实际文件。

### 4.1 Controller

当前 `KnowledgeTestController` 负责：

- 接收 HTTP 请求。
- 触发请求参数校验。
- 调用 Embedding 服务。
- 构造知识文档。
- 调用 Repository 执行读写。
- 返回统一响应对象。

后续可以将测试用途的 Controller 改名为 `KnowledgeController`，或拆分为入库和查询 Controller。

### 4.2 DTO

DTO 用于定义 API 的请求和响应结构。

当前包括：

- `KnowledgeIngestRequest`
- `KnowledgeIngestResponse`
- `KnowledgeUpdateRequest`
- `KnowledgeUpdateResponse`
- `KnowledgeQueryResponse`

请求 DTO 使用 Jakarta Bean Validation 对必要字段进行校验。

### 4.3 Model

`KnowledgeDocument` 表达当前知识切片数据：

```java
public record KnowledgeDocument(
        String title,
        String content,
        String category,
        String chunkId,
        List<Double> embedding
) {}
```

`KnowledgeUpdateResult` 用于返回 Elasticsearch 更新产生的文档 ID 和版本信息。

### 4.4 Repository

`EsDocumentRepository` 封装 Elasticsearch 读写操作，包括：

- `create()`
- `upsert()`
- `save()`
- `findById()`
- `updateWithOCC()`

开源前应确认每个方法的语义边界，避免多个方法对同一文档重复写入。

### 4.5 EmbeddingClient

Embedding 客户端负责调用 Ollama，将文本转换为向量。

当前模型为 BGE-M3。生成向量后，服务将其与文本内容及业务字段一起写入 Elasticsearch。

---

## 5. Elasticsearch 索引设计

当前索引名称：

`rag_knowledge_vector`

当前已知 mapping：

| 字段 | 类型 | 用途 |
|---|---|---|
| `title` | `text` | 标题全文检索 |
| `content` | `text` | 正文全文检索 |
| `category` | `keyword` | 分类过滤与精确匹配 |
| `chunk_id` | `keyword` | 业务知识切片 ID |
| `embedding` | `dense_vector` | 语义向量检索 |

当前向量字段配置要点：

- `dims: 1024`
- `index: true`
- `similarity: cosine`
- `index_options.type: int8_hnsw`

### 5.1 字段类型说明

**text**

适合全文检索。文本会经过分析器处理，生成用于检索的词项。

**keyword**

适合精确匹配、过滤和聚合，例如分类、状态和业务 ID。

**dense_vector**

用于保存 Embedding 产生的稠密向量，支持向量相似度检索。

**HNSW**

HNSW 是近似最近邻搜索的索引结构，主要用于提高大规模向量检索的效率。

`int8_hnsw` 属于向量索引选项。实际内存、召回率和检索性能需要根据目标数据集进行评估。

### 5.2 模型维度约束

当前索引要求 1024 维向量。

更换 Embedding 模型时，需要确认：

1. 模型实际输出维度。
2. Elasticsearch mapping 的 `dims`。
3. 索引中的已有向量是否来自同一模型或兼容模型。
4. 模型升级是否需要重新生成已有文档的 Embedding。

不同模型生成的向量不能因为维度相同就默认具有可比性。

### 5.3 后续可扩展字段

为了支持通用知识库，可逐步增加：

| 字段 | 用途 |
|---|---|
| `document_id` | 原始文档 ID |
| `chunk_index` | 切片顺序 |
| `knowledge_base_id` | 知识库隔离 |
| `tenant_id` | 租户隔离 |
| `source_uri` | 来源文件或 URL |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `embedding_model` | Embedding 模型标识 |
| `metadata` | 可扩展业务元数据 |

这些是后续设计建议，不代表当前索引已经包含它们。

---

## 6. 当前 API 设计

Controller 类级别映射：

```java
@RequestMapping("/api/knowledge")
```

### 6.1 接口列表

| 方法 | 路径 | 用途 | 当前状态 |
|---|---|---|---|
| POST | `/api/knowledge/test` | 生成向量并按模式写入 | 已实现，需检查重复写入问题 |
| PUT | `/api/knowledge/update` | 使用 OCC 更新完整文档 | 已实现并验证 |
| GET | `/api/knowledge/{chunkId}` | 查询知识切片与版本 | 已实现并验证 |

开源前应以实际 Controller 源码核对路由，后续将 `/test` 改成正式入库路径。

### 6.2 CREATE 与 UPSERT

入库请求包含 `mode`，当前支持：

- `CREATE`
- `UPSERT`

**CREATE**

仅在文档 ID 不存在时创建。如果同一 ID 已存在，则应返回冲突，避免静默覆盖已有文档。

**UPSERT**

允许创建文档或替换已有文档，适用于业务明确允许无条件覆盖的场景。

**OCC 更新**

要求调用方提交当前版本信息，只有版本匹配时才执行更新。

三者不能混为一谈：

| 机制 | 解决的问题 |
|---|---|
| 唯一 ID | 标识一条业务记录 |
| CREATE | 避免已存在的 ID 被重复创建 |
| UPSERT | 允许创建或替换 |
| 幂等性 | 定义重复执行同一业务请求时的效果 |
| OCC | 防止基于旧版本的更新覆盖新数据 |

### 6.3 更新请求

`KnowledgeUpdateRequest` 当前包含：

- `chunkId`
- `title`
- `content`
- `category`
- `seqNo`
- `primaryTerm`

其中 `seqNo` 和 `primaryTerm` 用于乐观并发控制。

### 6.4 更新成功响应

`KnowledgeUpdateResponse` 当前包含：

- `chunkId`
- `status`
- `vectorDimensions`
- `seqNo`
- `primaryTerm`

成功响应中的版本号是本次写入后的新版本，调用方可以将它用于后续操作，但仍应确保对应的业务数据是最新的。

---

## 7. OCC 乐观并发控制

### 7.1 为什么需要 OCC

假设两个调用方同时读取了同一条知识切片，读取到的版本都是：

```text
seqNo = 40
primaryTerm = 2
```

A 先提交更新，成功后版本变为：

```text
seqNo = 41
primaryTerm = 2
```

B 仍使用旧版本 `(40, 2)` 提交更新，Elasticsearch 会拒绝此次写入并返回 HTTP 409。

这可以避免 B 无意中覆盖 A 的更新。

### 7.2 Repository 核心实现

当前实现的关键部分：

```java
IndexResponse response = client.index(request -> request
        .index(indexName)
        .id(document.chunkId())
        .ifSeqNo(seqNo)
        .ifPrimaryTerm(primaryTerm)
        .document(toSource(document)));
```

ES 会原子地检查版本条件并执行写入。

如果版本不匹配，ES 返回 409。Repository 将对应异常转换为应用层的 `OptimisticLockException`。

### 7.3 自定义异常

```java
package com.traffic.rag.common;

public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String chunkId) {
        super("知识切片版本已变化，请重新读取后再更新，chunkId=" + chunkId);
    }
}
```

### 7.4 统一异常处理

当前 OCC 冲突响应：

```json
{
  "code": "RAG_VERSION_CONFLICT",
  "message": "知识切片版本已变化，请重新读取后再更新，chunkId=rag_java_p1_001",
  "data": null
}
```

对应：

- HTTP 状态码：`409 Conflict`
- 业务错误码：`RAG_VERSION_CONFLICT`

HTTP 状态码用于表达 HTTP 层面的冲突；业务错误码用于让客户端准确识别发生了哪一种业务错误。

### 7.5 冲突后的处理策略

当前选择：

**发生 409 后返回给调用方，由调用方重新读取最新文档，再由业务逻辑决定是否重新提交。服务端不自动重试。**

处理流程：

1. 调用方查询知识切片和当前版本。
2. 调用方提交更新。
3. 服务端执行 OCC 更新。
4. 如果返回 409，调用方重新查询最新文档。
5. 对比最新内容与原始修改意图。
6. 业务确认后，基于最新文档重新构造完整数据。
7. 使用最新的 `seqNo` 和 `primaryTerm` 再次提交。
8. 如果再次冲突，则重新进入处理流程。

不能只把旧请求的版本号换成最新版本号再重放。

当前使用 `client.index()` 执行完整文档替换。如果直接重放旧文档，可能把另一个请求修改过的正文、标题或分类覆盖掉。

### 7.6 当前实现的限制

- OCC 更新是完整文档替换，不是局部字段更新。
- 当前流程先生成 Embedding，再提交给 ES；即使最终因版本冲突而失败，也可能已经消耗模型计算资源。
- 即使优化 Embedding 计算时机，也不能省略最终 OCC 校验。
- `_seq_no` 是 Elasticsearch 操作序列相关的版本元数据，不是业务全局 ID。
- `_primary_term` 与主分片任期有关，应与 `_seq_no` 一起使用。
- 409 是可预期的业务冲突，不一定代表系统故障。

---

## 8. 已完成的实操验证

本节记录的是开发过程中的实验结果。版本号为当时的实测值，不应当视为运行时固定值。

### 8.1 Elasticsearch 原生 OCC 验证

曾读取到版本：

```text
_seq_no = 37
_primary_term = 2
```

使用该版本更新成功后，版本变为：

```text
_seq_no = 38
_primary_term = 2
```

再次使用旧版本 `(37, 2)` 更新，Elasticsearch 返回 HTTP 409。

验证结论：ES 能拒绝基于过期版本的更新。

### 8.2 Java OCC 接口验证

通过 Spring Boot 接口执行更新，成功响应包含：

```json
{
  "chunkId": "rag_java_p1_001",
  "status": "SUCCESS",
  "vectorDimensions": 1024,
  "seqNo": 40,
  "primaryTerm": 2
}
```

验证结论：Java 接口能将 Elasticsearch 返回的新版本信息传递给调用方。

### 8.3 冲突业务错误码验证

使用过期版本调用：

```text
PUT /api/knowledge/update
```

得到：

```json
{
  "code": "RAG_VERSION_CONFLICT",
  "message": "知识切片版本已变化，请重新读取后再更新，chunkId=rag_java_p1_001",
  "data": null
}
```

HTTP 状态码为 `409`。

验证结论：OCC 冲突可以被转换为稳定的业务错误响应。

### 8.4 查询接口验证

通过：

```text
GET /api/knowledge/rag_java_p1_001
```

成功查询到：

- `chunkId`
- `title`
- `content`
- `category`
- `seqNo`
- `primaryTerm`

当时读取到的版本为 `(40, 2)`。

### 8.5 冲突后重新读取验证

使用过期版本 `(0, 2)` 提交更新，接口返回 HTTP 409。

再次查询文档，版本仍为 `(40, 2)`，标题和正文也没有被该失败请求覆盖。

验证结论：该失败请求被 ES 拒绝，没有产生预期之外的覆盖（以实验期间没有其他成功写入为前提）。

### 8.6 业务确认后重新提交

基于重新读取的最新版本 `(40, 2)`，业务确认修改后，再次提交更新。

成功响应：

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "chunkId": "rag_java_p1_001",
    "status": "SUCCESS",
    "vectorDimensions": 1024,
    "seqNo": 41,
    "primaryTerm": 2
  }
}
```

之后再次查询，标题、正文、分类与版本信息均与本次更新结果一致。

验证结论：查询、冲突拒绝、重新读取、业务确认和再次更新的基本流程已通过本地实操验证。

---

## 9. 已知问题与待修复项

### 9.1 入库接口可能存在重复写入

开发过程中出现过如下代码：

```java
String savedId = switch (request.mode()) {
    case CREATE -> repository.create(document);
    case UPSERT -> repository.upsert(document);
};

savedId = repository.save(document);
```

如果当前源代码仍然保留第二次赋值，那么一次请求会先执行 `CREATE` 或 `UPSERT`，随后又执行 `save()`。

这会导致同一个请求重复写入，并可能破坏 CREATE 原本应有的防重语义。

**开源前必须确认并修复。**

正确原则：根据 `mode` 只执行一次对应的写入操作，不要在 switch 后再无条件调用 `repository.save(document)`。

### 9.2 普通查询不应默认返回向量

当前 `KnowledgeQueryResponse` 曾设计为包含完整的 `embedding` 字段。

对于普通业务调用方，通常不需要返回 1024 维向量。建议默认只返回：

- `chunkId`
- `title`
- `content`
- `category`
- `seqNo`
- `primaryTerm`

如果需要调试向量，可以提供独立的内部调试能力，而不是让普通查询接口始终传输完整向量。

### 9.3 文档不存在时统一返回 404

当前查询 Controller 使用 `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` 处理不存在的文档。

Spring 默认错误响应不一定与自定义 `ApiResponse` 格式一致。

后续应统一：

- HTTP 状态码。
- 业务错误码。
- message。
- data 字段。

### 9.4 Repository 的类型安全

当前 `findById()` 使用 `GetResponse<Map>` 读取 ES 数据，并对向量字段进行类型转换。

这种写法可能产生 unchecked cast 警告或运行时类型问题。

后续可以引入明确的 ES Source DTO，并通过安全的映射方式处理字段。

### 9.5 异常处理需要进一步完善

后续应分别处理：

- 请求参数校验失败：HTTP 400。
- 文档不存在：HTTP 404。
- CREATE 重复 ID：HTTP 409，但业务码应与 OCC 冲突区分。
- OCC 版本冲突：HTTP 409 + `RAG_VERSION_CONFLICT`。
- Embedding 服务超时或不可用。
- Elasticsearch 连接失败或超时。
- 未预期的系统异常。

不要把所有底层异常都包装成无法识别的通用错误，也不要把内部堆栈、密钥或环境信息直接返回给客户端。

### 9.6 中文乱码排查

开发期间 PowerShell 曾出现中文显示乱码。

不能只凭终端显示异常，就判断 Elasticsearch 中的数据已经损坏。

应分别检查：

1. Java 请求体编码。
2. HTTP 响应编码。
3. PowerShell 控制台编码。
4. ES 中实际保存的字符串。
5. 源代码与 Markdown 文件是否采用 UTF-8 编码。

---

## 10. 从基础入库服务演进为通用 RAG 服务

建议采用分阶段推进方式。

### P0：基础服务收尾

- [x] Spring Boot 服务基础结构。
- [x] Ollama Embedding 接入。
- [x] Elasticsearch 文档写入。
- [x] CREATE / UPSERT 两种模式。
- [x] OCC 更新。
- [x] 409 冲突和业务错误码。
- [x] 知识切片查询及版本返回。
- [x] 冲突后重新读取、业务确认、再次更新的实操。
- [ ] 检查并修复潜在重复写入。
- [ ] 完善文档不存在时的 404 响应。
- [ ] 将普通查询响应中的 Embedding 移除。
- [ ] 增加自动化测试。
- [ ] 统一异常处理。
- [ ] 整理配置、日志和超时。
- [ ] 验证 Maven 依赖兼容性。

### P1：通用知识入库 API

- 将 `/test` 改为正式的入库接口。
- 明确 `knowledgeBaseId`、`documentId` 和 `chunkId` 的含义。
- 增加删除接口。
- 增加批量入库。
- 增加批量 Embedding。
- 为重复请求定义幂等策略。
- 限制请求体大小与文本长度。
- 增加请求超时、异常分类和结构化日志。
- 编写 API 文档与自动化测试。

### P2：文档解析和切片

- 支持 TXT、Markdown 等基础文本格式。
- 后续扩展 PDF、DOCX 等文档格式。
- 增加文本清洗和标准化。
- 设计固定长度、重叠窗口或语义切片策略。
- 记录文档 ID、切片序号和来源。
- 处理原始文档更新后旧切片的清理。
- 支持异步批量导入任务。

### P3：检索 API

- Elasticsearch BM25 全文检索。
- Dense Vector 向量检索。
- Top-K 召回。
- 按知识库、分类和业务元数据过滤。
- 统一检索请求与响应模型。
- 建立可复现的检索质量评估集。

### P4：混合检索与排序

- Dense + Sparse 混合召回。
- RRF 结果融合。
- 可选 Cross-Encoder 或 ColBERT 重排。
- 邻近切片补全。
- 重复结果去重。
- 评估 Recall@K、MRR、nDCG 和检索延迟。

### P5：RAG 问答编排

- 组装检索上下文。
- Prompt 模板管理。
- 上下文长度控制。
- 接入生成模型。
- 流式输出（例如 SSE）。
- 返回答案及引用来源。
- 处理低相关度、无答案和权限过滤。
- 增加提示注入防护和输出校验。

### P6：生产化与平台化

- 鉴权与租户隔离。
- 知识库级权限。
- 限流、超时、熔断。
- Metrics、日志与链路追踪。
- Docker Compose 部署示例。
- Kubernetes 部署示例。
- CI 与自动化测试。
- 索引迁移、备份恢复和模型升级策略。
- 发布版本与兼容性策略。

---

## 11. GitHub 开源准备

### 11.1 建议仓库结构

```text
edu-rag-service/
├── README.md
├── PROJECT_SUMMARY.md
├── LICENSE
├── .gitignore
├── .env.example
├── pom.xml
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── elasticsearch-mapping.md
│   ├── development-guide.md
│   └── roadmap.md
├── deploy/
│   ├── docker-compose.yml
│   └── kubernetes/
├── scripts/
└── src/
    ├── main/
    └── test/
```

这是目标结构，不表示上述所有文件已经创建。

### 11.2 开源前必须检查

- 不提交真实密码、Token、API Key 或其他敏感信息。
- 检查配置文件中是否包含个人电脑路径和本机特有地址。
- 将服务地址和敏感配置外置。
- 提供 `.env.example` 或配置模板，只使用占位符。
- 忽略 `target/`、日志、IDE 临时文件和本地数据。
- 检查 Git 历史是否曾经提交过密钥。
- 补充许可证。
- 完善 README 和本地启动指南。
- 提供可复现的 API 调用示例。
- 为关键接口添加自动化测试。
- 明确当前项目处于基础能力建设阶段。
- 不要在 README 中声称尚未实现的 RAG 检索或问答功能已经可用。

### 11.3 配置管理建议

本地开发地址应通过 Spring 配置或环境变量提供。

不要把以下内容写死为通用服务的固定配置：

- 本地 Elasticsearch 地址。
- 本地 Ollama 地址。
- Kubernetes 本地集群 IP。
- 个人电脑绝对路径。
- 本地开发专用端口。

开源用户应能够根据自己的环境配置依赖服务。

---

## 12. README 项目简介建议

可以使用以下内容作为 GitHub README 的初始简介：

> `edu-rag-service` 是一个基于 Java 21、Spring Boot 和 Elasticsearch 的 RAG 基础服务项目。
>
> 当前版本聚焦知识切片入库、Ollama Embedding 接入、Elasticsearch 向量存储、文档查询，以及基于 `_seq_no` 和 `_primary_term` 的乐观并发控制。
>
> 项目计划逐步扩展到文档解析与切片、混合检索、结果融合、重排和 RAG 应用编排。
>
> 当前项目仍处于基础能力建设阶段，路线图中的功能不代表已经实现。

---

## 13. 当前阶段总结

本阶段完成了从基础 Java 服务到具备 OCC 并发保护的知识入库服务的关键步骤：

1. 建立 Spring Boot 项目结构。
2. 接入 Ollama 和 BGE-M3。
3. 将知识文本和 Embedding 写入 Elasticsearch。
4. 区分 CREATE、UPSERT 与 OCC 更新。
5. 为更新接口增加 `_seq_no` 和 `_primary_term` 条件。
6. 将版本冲突转换为 HTTP 409 和 `RAG_VERSION_CONFLICT`。
7. 新增查询接口，返回知识切片及其版本信息。
8. 实测过期版本更新失败。
9. 实测冲突后重新读取最新数据。
10. 经过业务确认后使用最新版本再次更新，并验证最终数据。

下一阶段的重点不是继续深入 OCC，而是**先完成基础服务的工程收尾，再逐步构建通用 RAG 检索能力**。

开源之前优先处理：

1. 确认并修复潜在重复写入。
2. 统一 400、404、409 和 5xx 响应。
3. 移除普通查询接口中的向量输出。
4. 补齐测试和配置说明。
5. 检查 Git 历史和敏感配置。
6. 完善 README、许可证和本地启动文档。