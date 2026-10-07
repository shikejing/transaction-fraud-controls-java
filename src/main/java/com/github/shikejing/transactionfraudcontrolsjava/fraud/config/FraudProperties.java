package com.github.shikejing.transactionfraudcontrolsjava.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 欺诈判定相关配置项。
 */
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    /** 本地持久化目录（判定日志、额度计数）。 */
    private String dataDir = "build/fraud-data";

    /** 单个信号源超时时间（毫秒），超时不阻塞整体判定。 */
    private long signalTimeoutMs = 200;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public long getSignalTimeoutMs() {
        return signalTimeoutMs;
    }

    public void setSignalTimeoutMs(long signalTimeoutMs) {
        this.signalTimeoutMs = signalTimeoutMs;
    }
}
