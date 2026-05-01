package org.novasparkle.acrux.scanner;


import lombok.experimental.UtilityClass;
import org.novasparkle.acrux.Acrux;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@UtilityClass
public class AnnotationScanner {
    public <A extends Annotation> EntryContainer<A> scanPackage(Class<?> mainClass, Class<A> annotationClass, String... packageNames) throws IOException, ClassNotFoundException {
        EntryContainer<A> entryContainer = new EntryContainer<>(annotationClass);
        JarFile jarFile = getJar(mainClass);
        if (jarFile == null) {
            throw new IllegalArgumentException("JarFile is null!");
        }
        Enumeration<JarEntry> enumeration = jarFile.entries();
        while (enumeration.hasMoreElements()) {
            JarEntry jarEntry = enumeration.nextElement();
            if (jarEntry.getName().endsWith(".class")) {
                String className = jarEntry.getName().replace(".class", "").replace('/', '.');
                if (!className.startsWith(Acrux.class.getPackage().getName())) continue;
                if (packageNames.length > 0 && Arrays.stream(packageNames).noneMatch(className::startsWith)) continue;
                try {
                    Class<?> clazz = Class.forName(className);
                    if (clazz.isAnnotationPresent(annotationClass)) {
                        ClassEntry<A> classEntry = new ClassEntry<>(clazz, annotationClass);
                        entryContainer.add(classEntry);
                    }
                } catch (NoClassDefFoundError e) {
                    System.err.println("Failed to load class: " + className);
                    throw e;
                }
            }
        }
        jarFile.close();
        return entryContainer;
    }

    private JarFile getJar(Class<?> clazz) throws IOException {
        ProtectionDomain protectionDomain = clazz.getProtectionDomain();
        if (protectionDomain == null) {
            return null;
        }
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        URL location = codeSource.getLocation();
        if (location == null) {
            return null;
        }

        File file;
        try {
            file = new File(location.toURI());
        } catch (URISyntaxException e) {
            file = new File(location.getPath());
        }

        if (file.isFile() && file.getName().endsWith(".jar")) {
            return new JarFile(file);
        }
        return null;
    }
}
