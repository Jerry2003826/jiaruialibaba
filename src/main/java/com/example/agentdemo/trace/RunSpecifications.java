package com.example.agentdemo.trace;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class RunSpecifications {

    private RunSpecifications() {
    }

    static Specification<RunEntity> filter(RunType type, RunStatus status) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

}
