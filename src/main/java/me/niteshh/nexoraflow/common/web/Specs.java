package me.niteshh.nexoraflow.common.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

/** Tiny helpers to build optional filters for list endpoints (a null value means "no filter"). */
public final class Specs {

    private Specs() {
    }

    public static <T> Specification<T> eq(String field, Object value) {
        return value == null ? null : (root, query, cb) -> cb.equal(root.get(field), value);
    }

    public static <T, C extends Comparable<? super C>> Specification<T> gte(String field, C value) {
        return value == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get(field), value);
    }

    public static <T, C extends Comparable<? super C>> Specification<T> lte(String field, C value) {
        return value == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get(field), value);
    }

    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        List<Specification<T>> present = new ArrayList<>();
        for (Specification<T> s : specs) {
            if (s != null) {
                present.add(s);
            }
        }
        return (root, query, cb) -> {
            var predicates = present.stream().map(s -> s.toPredicate(root, query, cb)).toList();
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
