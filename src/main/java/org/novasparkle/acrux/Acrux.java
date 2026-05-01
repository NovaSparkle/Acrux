package org.novasparkle.acrux;

import lombok.Getter;
import org.novasparkle.acrux.agent.AgentContext;
import org.novasparkle.acrux.processor.ProcessorContext;

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
