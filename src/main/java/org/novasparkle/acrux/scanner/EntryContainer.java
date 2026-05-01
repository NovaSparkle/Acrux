package org.novasparkle.acrux.scanner;

import lombok.Getter;

import java.lang.annotation.Annotation;
import java.util.HashSet;

@Getter
public class EntryContainer<A extends Annotation> extends HashSet<ClassEntry<A>> {
    private final Class<A> annotation;

    public EntryContainer(Class<A> annotation) {
        this.annotation = annotation;
    }

    public void add(Class<?> clazz) {
        super.add(new ClassEntry<>(clazz, annotation));
    }
}
