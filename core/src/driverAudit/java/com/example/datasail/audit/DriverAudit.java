package com.example.datasail.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Diagnostic only: never edits driver bytecode or generates suppression rules. */
public final class DriverAudit {
    public static void main(String[] args) throws Exception {
        Set<String> missing = new TreeSet<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.startsWith("-dontwarn ")) missing.add(line.substring(10).replace('.', '/'));
        }
        if (missing.isEmpty()) throw new IllegalArgumentException("No missing-class evidence supplied");
        List<String> findings = new ArrayList<>();
        findings.add("Diagnostic references to classes in the supplied R8 missing-class report.");
        findings.add("This is not reachability proof or permission to remove a method. Input: " + Paths.get(args[0]).getFileName());
        for (int i = 2; i < args.length; i++) {
            Path path = Paths.get(args[i]);
            try (JarFile jar = new JarFile(path.toFile())) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    ClassNode owner = new ClassNode();
                    try (java.io.InputStream input = jar.getInputStream(entry)) {
                        new ClassReader(input).accept(owner, 0);
                    }
                    for (MethodNode method : owner.methods) {
                        // A fresh pool for each method excludes unrelated class-pool references.
                        ClassNode isolated = new ClassNode();
                        isolated.version = owner.version;
                        isolated.access = owner.access;
                        isolated.name = owner.name;
                        isolated.superName = "java/lang/Object";
                        isolated.methods.add(method);
                        ClassWriter writer = new ClassWriter(0);
                        isolated.accept(writer);
                        String pool = new String(writer.toByteArray(), StandardCharsets.ISO_8859_1);
                        Set<String> hits = new TreeSet<>();
                        for (String type : missing) if (pool.contains(type)) hits.add(type);
                        if (!hits.isEmpty()) findings.add(path.getFileName() + " | " + owner.name + "#" + method.name + method.desc + " | " + hits);
                    }
                }
            }
        }
        Collections.sort(findings.subList(2, findings.size()));
        Path output = Paths.get(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, findings, StandardCharsets.UTF_8);
        System.out.println("Driver audit: " + (findings.size() - 2) + " methods; " + output);
    }
}
