package org.novasparkle.repository;

import java.io.Serializable;
import java.util.List;

public interface IRepository<ID extends Serializable, E> {
    E findById(ID id);
    List<E> findAll();
    boolean existsById(ID id);
    E persist(E entity);
    void remove(E entity);
    void remove(ID id);
}
