package com.github.shikejing.transactionfraudcontrolsjava.fraud.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 基于本地文件的追加式日志：每条记录一行 "type base64(payload)"，
 * 写入即 fsync，重启时按顺序重放。半行（进程中断写了一半）在重放时被忽略。
 */
public class FileJournal implements Journal {

    private final Path file;
    private final BufferedWriter writer;

    public FileJournal(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            this.file = dir.resolve(name);
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void append(String type, String payloadJson) {
        try {
            writer.append(type)
                    .append(' ')
                    .append(Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Entry> replay() {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int space = line.indexOf(' ');
                if (space <= 0) {
                    continue; // 跳过中断造成的残行
                }
                String type = line.substring(0, space);
                try {
                    String payload = new String(Base64.getDecoder().decode(line.substring(space + 1)),
                            StandardCharsets.UTF_8);
                    entries.add(new Entry(type, payload));
                } catch (IllegalArgumentException badBase64) {
                    // 残行，忽略
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
