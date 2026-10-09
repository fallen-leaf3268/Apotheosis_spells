package com.example.apotheosis_spells.audit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeDamageCoverageTest {
    @Test
    void everyNativeDirectHurtSiteMustRemainClassified() throws Exception {
        var resource = getClass().getClassLoader().getResource("io/redspace/ironsspellbooks/api/registry/SpellRegistry.class");
        assertNotNull(resource);
        var connection = assertInstanceOf(JarURLConnection.class, resource.openConnection());
        connection.setUseCaches(false);
        try (var jar = connection.getJarFile();
             var fixture = getClass().getResourceAsStream("/audit/native-hurt-sites.json")) {
            assertNotNull(fixture);
            var expected = JsonParser.parseReader(new InputStreamReader(fixture, StandardCharsets.UTF_8)).getAsJsonObject();
            var actual = new ArrayList<String>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("io/redspace/ironsspellbooks/") || !entry.getName().endsWith(".class")) continue;
                try (var bytes = jar.getInputStream(entry)) {
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        private String owner;

                        @Override
                        public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                            owner = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String target, String method, String desc, boolean isInterface) {
                                    if (method.equals("hurt") && desc.endsWith("Lnet/minecraft/world/damagesource/DamageSource;F)Z")) {
                                        actual.add(owner + '#' + name + descriptor + " -> " + target + "#hurt" + desc);
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            var sorted = actual.stream().sorted().toList();
            List<List<String>> versions = new ArrayList<>();
            expected.entrySet().forEach(entry -> versions.add(entry.getValue().getAsJsonArray().asList().stream()
                    .map(value -> value.getAsString()).sorted().toList()));
            assertTrue(versions.contains(sorted), () -> "Native damage entry points changed; classify new/removed sites before updating the fixture: " + sorted);
        }
    }
}
