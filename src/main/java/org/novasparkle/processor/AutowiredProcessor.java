package org.novasparkle.processor;

import lombok.Getter;
import lombok.SneakyThrows;
import org.novasparkle.Acrux;
import org.novasparkle.agent.AgentContext;
import org.novasparkle.annotations.Agent;
import org.novasparkle.annotations.Autowired;
import org.novasparkle.annotations.Processor;
import org.novasparkle.repository.IRepository;
import org.novasparkle.repository.RepositoryFactory;
import org.novasparkle.scanner.AnnotationScanner;
import org.novasparkle.scanner.ClassEntry;
import org.novasparkle.scanner.EntryContainer;

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
