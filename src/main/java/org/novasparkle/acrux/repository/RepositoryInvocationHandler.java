package org.novasparkle.acrux.repository;

import jakarta.persistence.Query;
import lombok.SneakyThrows;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.SelectionQuery;
import org.novasparkle.acrux.repository.generation.MethodNameParser;
import org.novasparkle.acrux.util.HibernateUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class RepositoryInvocationHandler implements InvocationHandler {
    private final Class<?> repositoryInterface;
    private final Class<?> entityType;
    private final MethodNameParser parser;

    // Кэшируем распарсенные запросы, чтобы не гонять регулярки при каждом вызове метода
    private final Map<String, MethodNameParser.ParsedQuery> queryCache = new ConcurrentHashMap<>();

    public RepositoryInvocationHandler(Class<?> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
        ParameterizedType parameterizedType = (ParameterizedType) repositoryInterface.getGenericInterfaces()[0];
        this.entityType = (Class<?>) parameterizedType.getActualTypeArguments()[1];
        this.parser = new MethodNameParser(
                entityType.getSimpleName(),
                String.valueOf(entityType.getSimpleName().toLowerCase().charAt(0))
        );
    }

    @SneakyThrows
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // Поддержка default-методов внутри интерфейса репозитория
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

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
                    Object entity = session.find(entityType, args[0]);
                    if (Optional.class.isAssignableFrom(method.getReturnType())) {
                        return Optional.ofNullable(entity);
                    }
                    return entity;
                }
            }

            case "persist" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    var merged = session.merge(args[0]);
                    session.flush();
                    tx.commit();
                    return merged;
                }
            }

            case "existsById" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Object entity = session.find(entityType, args[0]);
                    return entity != null;
                }
            }

            case "removeById" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    Object entity = session.find(entityType, args[0]);
                    if (entity != null) {
                        session.remove(entity);
                    }
                    tx.commit();
                    return null;
                }
            }

            case "remove" -> {
                try (Session session = HibernateUtil.openSession()) {
                    Transaction tx = session.beginTransaction();
                    if (!entityType.isAssignableFrom(args[0].getClass())) {
                        throw new IllegalArgumentException(args[0].getClass().getSimpleName() + " не является сущностью!");
                    }
                    Object managed = session.contains(args[0]) ? args[0] : session.merge(args[0]);
                    session.remove(managed);
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
                MethodNameParser.ParsedQuery parsed = queryCache.computeIfAbsent(methodName, parser::parse);
                return executeParsedQuery(parsed, method, args);
            }
        }
    }

    private Object executeParsedQuery(MethodNameParser.ParsedQuery parsed, Method method, Object[] args) {
        Class<?> returnType = method.getReturnType();

        try (Session session = HibernateUtil.openSession()) {
            return switch (parsed.type()) {

                // 1. Обработка COUNT (countBy...)
                case COUNT -> {
                    SelectionQuery<Long> query = session.createSelectionQuery(parsed.jpql(), Long.class);
                    bindParameters(query, parsed.params(), args);
                    Long count = query.uniqueResult();
                    long result = (count != null) ? count : 0L;

                    if (returnType == int.class || returnType == Integer.class) {
                        yield (int) result;
                    }
                    yield result;
                }

                // 2. Обработка EXISTS (existsBy...)
                case EXISTS -> {
                    SelectionQuery<Long> query = session.createSelectionQuery(parsed.jpql(), Long.class);
                    bindParameters(query, parsed.params(), args);
                    Long count = query.uniqueResult();
                    yield count != null && count > 0L;
                }

                // 3. Обработка DELETE / REMOVE (deleteBy..., removeBy...)
                case DELETE -> {
                    Transaction tx = session.beginTransaction();
                    try {
                        MutationQuery query = session.createMutationQuery(parsed.jpql());
                        bindParameters(query, parsed.params(), args);
                        int deletedCount = query.executeUpdate();
                        tx.commit();

                        if (returnType == boolean.class || returnType == Boolean.class) {
                            yield deletedCount > 0;
                        }
                        if (returnType == long.class || returnType == Long.class) {
                            yield (long) deletedCount;
                        }
                        if (returnType == int.class || returnType == Integer.class) {
                            yield deletedCount;
                        }
                        yield null;
                    } catch (Exception e) {
                        if (tx.isActive()) tx.rollback();
                        throw e;
                    }
                }

                // 4. Обработка SELECT (findBy..., getBy..., findFirstBy...)
                case SELECT -> {
                    Query query = session.createQuery(parsed.jpql(), entityType);
                    bindParameters(query, parsed.params(), args);

                    if (parsed.limit() > 0) {
                        query.setMaxResults(parsed.limit());
                    }

                    if (List.class.isAssignableFrom(returnType) || Collection.class.isAssignableFrom(returnType)) {
                        List<?> list = query.getResultList();
                        if (Set.class.isAssignableFrom(returnType)) {
                            yield new LinkedHashSet<>(list);
                        }
                        yield list;
                    }

                    if (Stream.class.isAssignableFrom(returnType)) {
                        yield query.getResultList().stream();
                    }

                    // Если метод возвращает Optional<Entity> или саму Entity, ставим лимит 1 для безопасности
                    if (parsed.limit() <= 0) {
                        query.setMaxResults(1);
                    }

                    Object single = query.getSingleResultOrNull();
                    if (Optional.class.isAssignableFrom(returnType)) {
                        yield Optional.ofNullable(single);
                    }
                    yield single;
                }
            };
        }
    }

    /**
     * Универсальный биндинг параметров по argIndex из ParamMeta.
     */
    private void bindParameters(Object hibernateQuery, List<MethodNameParser.ParamMeta> params, Object[] args) {
        if (params.isEmpty() || args == null) return;

        for (MethodNameParser.ParamMeta meta : params) {
            Object rawArg = args[meta.argIndex()];
            Object valueToBind = switch (meta.kind()) {
                case SINGLE, BETWEEN_START, BETWEEN_END -> rawArg;
                case COLLECTION -> {
                    if (rawArg instanceof Collection<?> collection) {
                        yield collection;
                    } else if (rawArg instanceof Object[] array) {
                        yield Arrays.asList(array);
                    } else {
                        yield Collections.singletonList(rawArg);
                    }
                }
            };

            if (hibernateQuery instanceof Query jpaQuery) {
                jpaQuery.setParameter(meta.name(), valueToBind);
            } else if (hibernateQuery instanceof SelectionQuery<?> selQuery) {
                selQuery.setParameter(meta.name(), valueToBind);
            } else if (hibernateQuery instanceof MutationQuery mutQuery) {
                mutQuery.setParameter(meta.name(), valueToBind);
            }
        }
    }
}