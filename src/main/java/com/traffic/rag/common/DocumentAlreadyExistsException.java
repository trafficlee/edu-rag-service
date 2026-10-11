package com.traffic.rag.common;

public class DocumentAlreadyExistsException extends RuntimeException {

    public DocumentAlreadyExistsException(String chunkId) {
        super("知识切片已存在，chunkId=" + chunkId);
    }
}