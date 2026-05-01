package org.novasparkle;

import lombok.Getter;
import org.novasparkle.agent.AgentContext;
import org.novasparkle.processor.ProcessorContext;

@Getter
public class Acrux {
    private final Class<?> mainClass;
    private final ProcessorContext processorContext;
    private final AgentContext agentContext;

    private Acrux(Class<?> mainClass) {
        this.mainClass = mainClass;
        this.agentContext = AgentContext.initialize();
        this.processorContext = ProcessorContext.initialize(mainClass);
        processorContext.process(this);
    }

    public static Acrux initialize(Class<?> mainClass) {
        return new Acrux(mainClass);
    }
}
