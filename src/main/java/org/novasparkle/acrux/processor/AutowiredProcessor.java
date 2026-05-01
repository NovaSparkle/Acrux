package org.novasparkle.acrux.processor;

import lombok.Getter;
import lombok.SneakyThrows;
import org.novasparkle.acrux.Acrux;
import org.novasparkle.acrux.agent.AgentContext;
import org.novasparkle.acrux.annotations.Agent;
import org.novasparkle.acrux.annotations.Autowired;
import org.novasparkle.acrux.annotations.Processor;
import org.novasparkle.acrux.repository.IRepository;
import org.novasparkle.acrux.repository.RepositoryFactory;
import org.novasparkle.acrux.scanner.AnnotationScanner;
import org.novasparkle.acrux.scanner.ClassEntry;
import org.novasparkle.acrux.scanner.EntryContainer;

import java.lang.reflect.Field;


@Getter
@Processor
public class AutowiredProcessor implements AnnotationProcessor {
    private Class<Autowired> annotationType;

    @Override
    @SneakyThrows
    public void process(Acrux acrux) {
        EntryContainer<Agent> annotatedEntries = AnnotationScanner.scanPackage(acrux.getMainClass(), Agent.class);

        for (ClassEntry<Agent> classEntry : annotatedEntries) {
            Class<?> agentClass = classEntry.getClazz();
            Object agentInstance;
            if (IRepository.class.isAssignableFrom(agentClass)) {
                agentInstance = RepositoryFactory.createRepositoryInstance(agentClass);
            } else {
                agentInstance = agentClass.getDeclaredConstructor().newInstance();
            }
            acrux.getAgentContext().registerAgent(agentClass, agentInstance);
        }
        System.out.println(acrux.getAgentContext());
        for (ClassEntry<Agent> classEntry : annotatedEntries) {
            Class<?> agentClass = classEntry.getClazz();
            Object agent = acrux.getAgentContext().getAgent(agentClass);
            this.inject(agentClass, agent, acrux.getAgentContext());
        }
    }

    private void inject(Class<?> agentClass, Object agent, AgentContext agentContext) throws IllegalAccessException {
        for (Field field : agentClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                field.setAccessible(true);
                Class<?> fieldType = field.getType();
                Object dependency = agentContext.getAgent(fieldType);
                if (dependency == null) {
                    throw new NullPointerException();
                }
                field.set(agent, dependency);
            }
        }
    }
}
