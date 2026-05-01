package org.novasparkle.agent;

import java.util.HashMap;
import java.util.Map;

public class AgentContext {
    private final Map<Class<?>, Object> agents = new HashMap<>();

    private AgentContext() {}

    public void registerAgent(Class<?> type, Object instance) {
        agents.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAgent(Class<T> type) {
        return (T) agents.get(type);
    }

    public boolean containsAgent(Class<?> type) {
        return agents.containsKey(type);
    }

    public static AgentContext initialize() {
        return new AgentContext();
    }

    @Override
    public String toString() {
        return "AgentContext{" +
                "agents=" + agents +
                '}';
    }
}
