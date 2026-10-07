package com.github.shikejing.transactionfraudcontrolsjava.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the fraud engine.
 */
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    /** Directory for the local recovery journal. Empty = in-memory only. */
    private String journalDir = "";

    /** Per-account sliding window limit. */
    private long accountMaxAmountCentsPerWindow = 1_000_000L;
    private int accountMaxCountPerWindow = 20;
    private long accountWindowMillis = 60_000L;

    /** Per-device sliding window limit. */
    private long deviceMaxAmountCentsPerWindow = 2_000_000L;
    private int deviceMaxCountPerWindow = 40;
    private long deviceWindowMillis = 60_000L;

    /** Timeout (ms) waiting for an in-progress duplicate decision. */
    private long duplicateWaitMillis = 5_000L;

    public String getJournalDir() {
        return journalDir;
    }

    public void setJournalDir(String journalDir) {
        this.journalDir = journalDir;
    }

    public long getAccountMaxAmountCentsPerWindow() {
        return accountMaxAmountCentsPerWindow;
    }

    public void setAccountMaxAmountCentsPerWindow(long v) {
        this.accountMaxAmountCentsPerWindow = v;
    }

    public int getAccountMaxCountPerWindow() {
        return accountMaxCountPerWindow;
    }

    public void setAccountMaxCountPerWindow(int v) {
        this.accountMaxCountPerWindow = v;
    }

    public long getAccountWindowMillis() {
        return accountWindowMillis;
    }

    public void setAccountWindowMillis(long v) {
        this.accountWindowMillis = v;
    }

    public long getDeviceMaxAmountCentsPerWindow() {
        return deviceMaxAmountCentsPerWindow;
    }

    public void setDeviceMaxAmountCentsPerWindow(long v) {
        this.deviceMaxAmountCentsPerWindow = v;
    }

    public int getDeviceMaxCountPerWindow() {
        return deviceMaxCountPerWindow;
    }

    public void setDeviceMaxCountPerWindow(int v) {
        this.deviceMaxCountPerWindow = v;
    }

    public long getDeviceWindowMillis() {
        return deviceWindowMillis;
    }

    public void setDeviceWindowMillis(long v) {
        this.deviceWindowMillis = v;
    }

    public long getDuplicateWaitMillis() {
        return duplicateWaitMillis;
    }

    public void setDuplicateWaitMillis(long v) {
        this.duplicateWaitMillis = v;
    }
}
