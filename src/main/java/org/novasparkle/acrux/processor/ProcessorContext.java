package org.novasparkle.acrux.processor;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.novasparkle.acrux.Acrux;
import org.novasparkle.acrux.annotations.Processor;
import org.novasparkle.acrux.scanner.AnnotationScanner;
import org.novasparkle.acrux.scanner.ClassEntry;
import org.novasparkle.acrux.scanner.EntryContainer;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;


@Getter
@ToString
@Accessors(fluent = true)
public class ProcessorContext {
    private final Set<AnnotationProcessor> processors = new HashSet<>();

    private void add(AnnotationProcessor annotationProcessor) {
        processors.add(annotationProcessor);
    }

    public void process(Acrux acrux) {
        processors.forEach(processor -> processor.process(acrux));
    }

    @SneakyThrows
    public static ProcessorContext initialize(Class<?> clazz) {
        ProcessorContext context = new ProcessorContext();

        EntryContainer<Processor> processorEntries = AnnotationScanner.scanPackage(clazz, Processor.class);
        processorEntries.addAll(AnnotationScanner.scanPackage(Acrux.class, Processor.class));

        for (ClassEntry<Processor> classEntry : processorEntries) {
            Class<?> processorClass = classEntry.getClazz();

            AnnotationProcessor annotationProcessorInstance = (AnnotationProcessor) processorClass.getDeclaredConstructor().newInstance();

            Field field = processorClass.getDeclaredField("annotationType");
            field.setAccessible(true);
            Type type = field.getGenericType();
            if (type instanceof ParameterizedType parameterizedType) {
                Type[] parameters = parameterizedType.getActualTypeArguments();
                if (parameters.length == 1 && parameters[0] instanceof Class<?> annotationType) {
                    field.set(annotationProcessorInstance, annotationType);
                }
            }
            context.add(annotationProcessorInstance);
        }
        return context;
    }
}
