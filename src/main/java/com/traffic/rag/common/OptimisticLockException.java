package com.traffic.rag.common;

public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String chunkId) {
        super("知识切片版本已变化，请重新读取后再更新，chunkId=" + chunkId);
    }
}
