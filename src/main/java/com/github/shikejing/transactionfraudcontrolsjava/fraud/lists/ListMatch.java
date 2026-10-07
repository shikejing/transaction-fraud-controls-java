package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

/**
 * The winning entry for one key, if any.
 */
public record ListMatch(ListKeyType keyType, String keyValue, ListEntry entry) {
}
