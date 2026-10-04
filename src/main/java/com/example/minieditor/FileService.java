package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileService {
    public void save(String text,Path path) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
    public String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}