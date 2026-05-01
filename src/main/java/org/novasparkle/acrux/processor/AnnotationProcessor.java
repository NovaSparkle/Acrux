package org.novasparkle.acrux.processor;

import org.novasparkle.acrux.Acrux;

public interface AnnotationProcessor {
    void process(Acrux acrux);
    Class<?> getAnnotationType();
}
