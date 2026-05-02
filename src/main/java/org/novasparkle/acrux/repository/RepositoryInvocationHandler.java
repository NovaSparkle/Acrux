package org.novasparkle.acrux.repository;

import jakarta.persistence.Query;
import lombok.SneakyThrows;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.novasparkle.acrux.repository.generation.MethodNameParser;
import org.novasparkle.acrux.util.HibernateUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public class RepositoryInvocationHandler implements InvocationHandler {
    private final Class<?> repositoryInterface;
    private final Class<?> entityType;
    private final MethodNameParser parser;

    public RepositoryInvocationHandler(Class<?> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
        ParameterizedType parameterizedType = (ParameterizedType) repositoryInterface.getGenericInterfaces()[0];
        this.entityType = (Class<?>) parameterizedType.getActualTypeArguments()[1];
        this.parser = new MethodNameParser(entityType.getSimpleName(), String.valueOf(entityType.getSimpleName().toLowerCase().charAt(0)));

    }

    @SneakyThrows
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String methodName = method.getName();

        switch (methodName) {
            case "toString" -> {
                return "Proxy instance of " + repositoryInterface.getSimpleName();
            }

            case "equals" -> {
                return proxy == args[0];
            }

            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }

            case "findById" -> {
                try (Session session = HibernateUtil.openSession()) {
                    return session.find(entityType, args[0]);
                }
            }

            case "persist" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    SessionFactory factory = session.getSessionFactory();
                    Object id = factory.getPersistenceUnitUtil().getIdentifier(args[0]);
                    if (id != null) {
                        Object entity = session.merge(args[0]);
                        tx.commit();
                        return entity;
                    }
                    session.persist(args[0]);
                    tx.commit();
                    return args[0];
                }
            }

            case "existsById" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    Object entity = session.find(entityType, args[0]);
                    boolean contains = session.contains(entity);
                    tx.commit();
                    return contains;
                }
            }

            case "removeById" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    Object entity = session.find(entityType, args[0]);
                    if (entity == null) {
                        throw new NullPointerException("Сущность типа " + entityType.getSimpleName() + " не найдена по идентификатору " + args[0].getClass().getSimpleName());
                    }
                    session.remove(entity);
                    tx.commit();
                    return null;
                }
            }

            case "remove" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    if (entityType.isAssignableFrom(args[0].getClass())) {
                        throw new IllegalArgumentException(args[0].getClass().getSimpleName() + " не является сущностью!");
                    }
                    session.remove(args[0]);
                    tx.commit();
                    return null;
                }
            }

            case "findAll" -> {
                try (Session session = HibernateUtil.openSession()) {
                    return session.createQuery("from " + entityType.getName(), entityType).list();
                }
            }

            default -> {
                MethodNameParser.ParsedQuery parsed = parser.parse(methodName);
                try (Session session = HibernateUtil.openSession()) {
                    Query query = session.createQuery(parsed.jpql(), entityType);

                    int argIdx = 0;
                    for (MethodNameParser.ParamMeta meta : parsed.params()) {
                        switch (meta.kind()) {
                            case SINGLE, BETWEEN_START, BETWEEN_END -> query.setParameter(meta.name(), args[argIdx++]);
                            case COLLECTION -> {
                                Object argument = args[argIdx++];
                                if (argument instanceof Collection<?> collection) {
                                    query.setParameter(meta.name(), collection);
                                } else {
                                    query.setParameter(meta.name(), List.of((Object[]) argument));
                                }
                            }
                        }
                    }
                    if (method.getReturnType().equals(List.class)) {
                        return query.getResultList();
                    } else {
                        return Optional.ofNullable(query.getSingleResultOrNull());
                    }
                }
            }
        }
    }
}
