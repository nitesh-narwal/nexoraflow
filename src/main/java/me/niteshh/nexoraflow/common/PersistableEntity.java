package me.niteshh.nexoraflow.common;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

/**
 * Base for entities whose primary key is an assigned business identifier (e.g. EMP-000001).
 *
 * <p>Spring Data cannot tell a new entity with an assigned ID from a detached one, and would issue a
 * SELECT before every INSERT. Implementing {@link Persistable} removes that round trip and keeps
 * batch inserts effective.
 */
@MappedSuperclass
public abstract class PersistableEntity implements Persistable<String> {

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newEntity = false;
    }
}
