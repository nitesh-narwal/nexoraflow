package me.niteshh.nexoraflow.common.lifecycle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal, explicit lifecycle definition: a map of allowed transitions per state.
 * Every state change in the system is checked against one of these machines, so an invalid
 * transition can never be applied silently.
 */
public final class StateMachine<S extends Enum<S>> {

    private final String name;
    private final Map<S, Set<S>> allowed;

    private StateMachine(String name, Map<S, Set<S>> allowed) {
        this.name = name;
        this.allowed = allowed;
    }

    public static <S extends Enum<S>> Builder<S> builder(String name, Class<S> type) {
        return new Builder<>(name, type);
    }

    public String name() {
        return name;
    }

    public boolean canTransition(S from, S to) {
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    public Set<S> allowedFrom(S from) {
        return allowed.getOrDefault(from, Set.of());
    }

    public boolean isTerminal(S state) {
        return allowedFrom(state).isEmpty();
    }

    public Map<S, Set<S>> transitions() {
        return allowed;
    }

    public static final class Builder<S extends Enum<S>> {
        private final String name;
        private final Class<S> type;
        private final Map<S, Set<S>> map;

        private Builder(String name, Class<S> type) {
            this.name = name;
            this.type = type;
            this.map = new EnumMap<>(type);
        }

        @SafeVarargs
        public final Builder<S> from(S from, S... to) {
            Set<S> targets = EnumSet.noneOf(type);
            targets.addAll(Set.of(to));
            map.put(from, targets);
            return this;
        }

        public StateMachine<S> build() {
            return new StateMachine<>(name, map);
        }
    }
}
