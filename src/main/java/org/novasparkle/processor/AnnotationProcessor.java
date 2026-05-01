package org.novasparkle.processor;

import org.novasparkle.Acrux;

public interface AnnotationProcessor {
    void process(Acrux acrux);
    Class<?> getAnnotationType();
}
