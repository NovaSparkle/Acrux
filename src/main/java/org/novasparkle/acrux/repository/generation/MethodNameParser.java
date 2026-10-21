package org.novasparkle.acrux.repository.generation;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Универсальный парсер имён методов репозитория в JPQL-строки.
 *
 * <p>Поддерживает:
 * <ul>
 *   <li>Префиксы: find, get, read, query, search, stream, count, exists, delete, remove
 *       (включая вариации findAllBy..., countAllBy..., findAll, count, deleteAll)</li>
 *   <li>Операторы: And, Or с правильным приоритетом (And выше Or)</li>
 *   <li>Ключевые слова условий: Equals, Is, Not, Like, NotLike,
 *       StartingWith/StartsWith, EndingWith/EndsWith, Containing/Contains,
 *       LessThan, LessThanEqual, GreaterThan, GreaterThanEqual,
 *       Between, In, NotIn, IsNull, IsNotNull, True, False,
 *       Before, After, MemberOf, NotMemberOf + суффикс IgnoreCase</li>
 *   <li>Вложенные отношения через подчёркивание: findByAddress_CityName → u.address.cityName</li>
 *   <li>IgnoreCase / AllIgnoreCase</li>
 *   <li>Distinct: findDistinctBy..., countDistinctBy...</li>
 *   <li>Top/First N: findTop3By..., findFirstBy...</li>
 *   <li>Сортировка: findByNameOrderByCreatedAtDesc, findAllByOrderByCreatedAtDesc</li>
 * </ul>
 */
public class MethodNameParser {

    // ─────────────────────────────── константы ───────────────────────────────

    /**
     * Паттерн разбирает:
     * 1: action (find|get|read|query|search|stream|count|exists|delete|remove)
     * 2: Distinct (опционально)
     * 3: All (опционально, например findAllBy...)
     * 4: Top | First (опционально)
     * 5: Число лимита после Top/First (опционально)
     * 6: Вся часть после By (если By присутствует)
     */
    private static final Pattern SUBJECT_PATTERN = Pattern.compile(
            "^(find|get|read|query|search|stream|count|exists|delete|remove)" +
                    "(Distinct)?(All)?(?:(Top|First)(\\d+)?)?(?:By(.*))?$"
    );

    /**
     * Ключевые слова условий.
     * Порядок критичен: от длинных к коротким, чтобы жадный матчинг
     * не срезал «LessThan» вместо «LessThanEqual».
     */
    private static final List<String> CONDITION_KEYWORDS = List.of(
            "IsNotNull", "IsNull",
            "NotMemberOf", "MemberOf",
            "NotLike", "NotIn", "Not",
            "LessThanEqual", "LessThan",
            "GreaterThanEqual", "GreaterThan",
            "StartingWith", "StartsWith",
            "EndingWith",   "EndsWith",
            "Containing",   "Contains",
            "Like",
            "Between",
            "In",
            "Before", "After",
            "True", "False",
            "Equals", "Is"
    );

    // ──────────────────────────────── поля ───────────────────────────────────

    private final String entityName;
    private final String alias;

    // ─────────────────────────────── публичный API ───────────────────────────

    public MethodNameParser(String entityName, String alias) {
        this.entityName = Objects.requireNonNull(entityName, "entityName");
        this.alias      = Objects.requireNonNull(alias,      "alias");
    }

    /**
     * Разбирает имя метода репозитория в {@link ParsedQuery}.
     *
     * @param methodName имя метода без скобок и сигнатуры
     */
    public ParsedQuery parse(String methodName) {
        Matcher m = SUBJECT_PATTERN.matcher(methodName);
        if (!m.matches()) {
            throw new InvalidMethodNameException(
                    "Метод «" + methodName + "» не соответствует ни одному поддерживаемому префиксу."
            );
        }

        String  action    = m.group(1);                    // find | count | exists | delete | ...
        boolean distinct  = m.group(2) != null;            // Distinct
        String  topFirst  = m.group(4);                    // Top | First
        String  limitStr  = m.group(5);                    // число после Top/First
        String  predicate = m.group(6) != null ? m.group(6) : ""; // всё после By

        QueryType type = resolveQueryType(action);

        // ── Отделяем OrderBy ──────────────────────────────────────────────
        String orderByRaw    = "";
        String conditionPart = predicate;
        int orderByIdx = indexOfOrderBy(predicate);
        if (orderByIdx >= 0) {
            conditionPart = predicate.substring(0, orderByIdx);
            orderByRaw    = predicate.substring(orderByIdx + "OrderBy".length());
        }

        // ── AllIgnoreCase ─────────────────────────────────────────────────
        boolean allIgnoreCase = false;
        if (conditionPart.endsWith("AllIgnoreCase")) {
            allIgnoreCase = true;
            conditionPart = conditionPart.substring(
                    0, conditionPart.length() - "AllIgnoreCase".length()
            );
        }

        // ── Разбираем условия ─────────────────────────────────────────────
        List<ParamMeta> params    = new ArrayList<>();
        String          whereJpql = conditionPart.isEmpty()
                ? ""
                : buildWhereExpression(conditionPart, allIgnoreCase, params);

        // ── ORDER BY (не применяется для COUNT, EXISTS и DELETE) ──────────
        String orderJpql = (!orderByRaw.isEmpty() && type == QueryType.SELECT)
                ? buildOrderBy(orderByRaw)
                : "";

        // ── Собираем итоговый JPQL ────────────────────────────────────────
        String jpql = assembleJpql(type, distinct, whereJpql, orderJpql);

        // ── Вычисляем лимит (Top/First без числа = 1, для EXISTS всегда 1)
        int limit = -1;
        if (limitStr != null) {
            limit = Integer.parseInt(limitStr);
        } else if (topFirst != null) {
            limit = 1;
        }

        return new ParsedQuery(
                jpql,
                type,
                Collections.unmodifiableList(params),
                distinct,
                limit
        );
    }

    // ─────────────────────────────── сборка JPQL ─────────────────────────────

    private String assembleJpql(QueryType type, boolean distinct,
                                String whereJpql, String orderJpql) {
        StringBuilder sb = new StringBuilder();

        switch (type) {
            case COUNT, EXISTS -> {
                sb.append("SELECT COUNT(");
                if (distinct) sb.append("DISTINCT ");
                sb.append(alias).append(") FROM ")
                        .append(entityName).append(' ').append(alias);
            }

            case DELETE -> sb.append("DELETE FROM ")
                    .append(entityName).append(' ').append(alias);

            case SELECT -> {
                sb.append("SELECT ");
                if (distinct) sb.append("DISTINCT ");
                sb.append(alias).append(" FROM ")
                        .append(entityName).append(' ').append(alias);
            }
        }

        if (!whereJpql.isEmpty()) sb.append(" WHERE ").append(whereJpql);
        if (!orderJpql.isEmpty()) sb.append(" ORDER BY ").append(orderJpql);

        return sb.toString();
    }

    // ────────────────────── разбор предиката условий ─────────────────────────

    private String buildWhereExpression(String predicate,
                                        boolean allIgnoreCase,
                                        List<ParamMeta> params) {

        List<String> orParts = splitByOperator(predicate, "Or");
        int[] nextParam = {0};

        List<String> orClauses = orParts.stream().map(orPart -> {
            List<String> andParts   = splitByOperator(orPart, "And");
            List<String> andClauses = andParts.stream()
                    .map(segment -> buildConditionExpression(
                            parseConditionSegment(segment), allIgnoreCase, params, nextParam))
                    .collect(Collectors.toList());

            return andClauses.size() == 1
                    ? andClauses.get(0)
                    : String.join(" AND ", andClauses);

        }).toList();

        if (orClauses.size() == 1) return orClauses.get(0);

        return orClauses.stream()
                .map(c -> "(" + c + ")")
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Разбивает строку по оператору {@code And} или {@code Or},
     * если после него идёт заглавная буква (начало нового поля).
     */
    private List<String> splitByOperator(String s, String op) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i     = 0;

        while (i < s.length()) {
            if (s.startsWith(op, i)) {
                int  afterIdx = i + op.length();
                char after    = afterIdx < s.length() ? s.charAt(afterIdx) : '\0';
                if (Character.isUpperCase(after)) {
                    String chunk = s.substring(start, i);
                    // Защита от разбиения прямо внутри Between, если поле называется And...
                    if (!chunk.isEmpty()) {
                        result.add(chunk);
                        start = afterIdx;
                        i     = afterIdx;
                        continue;
                    }
                }
            }
            i++;
        }
        result.add(s.substring(start));

        return result.stream().filter(p -> !p.isEmpty()).collect(Collectors.toList());
    }

    // ────────────────────── разбор одного сегмента условия ───────────────────

    /**
     * Выделяет имя поля, флаг IgnoreCase и ключевое слово из одного сегмента предиката.
     * Корректно обрабатывает составные окончания вроде {@code ContainingIgnoreCase}.
     */
    private ConditionSegment parseConditionSegment(String segment) {
        boolean segmentIgnoreCase = false;
        String working = segment;

        if (working.endsWith("IgnoreCase") && working.length() > "IgnoreCase".length()) {
            segmentIgnoreCase = true;
            working = working.substring(0, working.length() - "IgnoreCase".length());
        }

        for (String kw : CONDITION_KEYWORDS) {
            if (working.endsWith(kw)) {
                String fieldPart = working.substring(0, working.length() - kw.length());
                if (!fieldPart.isEmpty()) {
                    return new ConditionSegment(toJpqlPath(fieldPart), kw, segmentIgnoreCase);
                }
            }
        }

        if (working.isEmpty()) {
            throw new InvalidMethodNameException("Пустое имя поля в сегменте условия: «" + segment + "»");
        }

        // Нет явного ключевого слова → подразумевается Equals
        return new ConditionSegment(toJpqlPath(working), "Equals", segmentIgnoreCase);
    }

    private String buildConditionExpression(ConditionSegment cs,
                                            boolean allIgnoreCase,
                                            List<ParamMeta> params,
                                            int[] nextParam) {
        String path = cs.fieldPath();
        String kw   = cs.keyword();

        boolean ignoreCase = allIgnoreCase
                || cs.ignoreCase()
                || kw.equals("Containing") || kw.equals("Contains")
                || kw.equals("StartingWith") || kw.equals("StartsWith")
                || kw.equals("EndingWith")   || kw.equals("EndsWith");

        String pName  = "param" + nextParam[0];
        String param  = ":" + pName;
        String lpath  = ignoreCase ? "LOWER(" + path + ")" : path;
        String lparam = ignoreCase ? "LOWER(" + param + ")" : param;

        return switch (kw) {

            // ── без параметров ────────────────────────────────────────────
            case "IsNull"    -> path + " IS NULL";
            case "IsNotNull" -> path + " IS NOT NULL";
            case "True"      -> path + " = TRUE";
            case "False"     -> path + " = FALSE";

            // ── Between: два параметра, два аргумента метода ──────────────
            case "Between" -> {
                String p1     = "param" + nextParam[0];
                String p2     = "param" + (nextParam[0] + 1);
                int    argIdx = nextArgIndex(params);

                params.add(new ParamMeta(p1, ParamKind.BETWEEN_START, argIdx));
                params.add(new ParamMeta(p2, ParamKind.BETWEEN_END,   argIdx + 1));
                nextParam[0] += 2;

                yield path + " BETWEEN :" + p1 + " AND :" + p2;
            }

            // ── In / NotIn: коллекция ─────────────────────────────────────
            case "In" -> {
                params.add(new ParamMeta(pName, ParamKind.COLLECTION, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " IN " + param;
            }
            case "NotIn" -> {
                params.add(new ParamMeta(pName, ParamKind.COLLECTION, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " NOT IN " + param;
            }

            // ── MemberOf / NotMemberOf (для @ElementCollection / @ManyToMany)
            case "MemberOf" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield param + " MEMBER OF " + path;
            }
            case "NotMemberOf" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield param + " NOT MEMBER OF " + path;
            }

            // ── единичные параметры ───────────────────────────────────────
            case "Equals", "Is" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " = " + lparam;
            }
            case "Not" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " <> " + lparam;
            }
            case "Like" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " LIKE " + lparam;
            }
            case "NotLike" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " NOT LIKE " + lparam;
            }
            case "StartingWith", "StartsWith" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " LIKE CONCAT(" + lparam + ", '%')";
            }
            case "EndingWith", "EndsWith" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " LIKE CONCAT('%', " + lparam + ")";
            }
            case "Containing", "Contains" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " LIKE CONCAT('%', " + lparam + ", '%')";
            }
            case "LessThan", "Before" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " < " + param;
            }
            case "LessThanEqual" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " <= " + param;
            }
            case "GreaterThan", "After" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " > " + param;
            }
            case "GreaterThanEqual" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " >= " + param;
            }

            default -> throw new InvalidMethodNameException(
                    "Неизвестное ключевое слово: «" + kw + "»"
            );
        };
    }

    // ──────────────────────── ORDER BY ────────────────────────────────────────

    private String buildOrderBy(String orderBy) {
        List<String> orders = new ArrayList<>();

        Pattern p = Pattern.compile("([A-Z][a-zA-Z0-9_]*?)(Asc|Desc)(?=[A-Z]|$)");
        Matcher m = p.matcher(orderBy);
        int lastEnd = 0;
        while (m.find()) {
            orders.add(toJpqlPath(m.group(1)) + " " + m.group(2).toUpperCase());
            lastEnd = m.end();
        }

        if (orders.isEmpty() && !orderBy.isEmpty()) {
            orders.add(toJpqlPath(orderBy) + " ASC");
        } else if (lastEnd < orderBy.length()) {
            // Если последнее поле в цепочке OrderBy указано без Asc/Desc
            String remainder = orderBy.substring(lastEnd);
            if (!remainder.isEmpty()) {
                orders.add(toJpqlPath(remainder) + " ASC");
            }
        }

        return String.join(", ", orders);
    }

    // ────────────────────────── утилиты ──────────────────────────────────────

    private String toJpqlPath(String field) {
        String path = Arrays.stream(field.split("_"))
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toLowerCase(part.charAt(0)) + part.substring(1))
                .collect(Collectors.joining("."));
        return alias + "." + path;
    }

    private int nextArgIndex(List<ParamMeta> params) {
        if (params.isEmpty()) return 0;
        return params.get(params.size() - 1).argIndex() + 1;
    }

    private int indexOfOrderBy(String s) {
        int idx = s.indexOf("OrderBy");
        while (idx >= 0) {
            int afterIdx = idx + "OrderBy".length();
            if (afterIdx < s.length() && Character.isUpperCase(s.charAt(afterIdx))) {
                return idx;
            }
            idx = s.indexOf("OrderBy", idx + 1);
        }
        return -1;
    }

    private QueryType resolveQueryType(String action) {
        return switch (action) {
            case "count"            -> QueryType.COUNT;
            case "exists"           -> QueryType.EXISTS;
            case "delete", "remove" -> QueryType.DELETE;
            default                 -> QueryType.SELECT;
        };
    }

    // ───────────────────────────── публичные типы ────────────────────────────

    /** Тип генерируемого запроса. */
    public enum QueryType { SELECT, COUNT, EXISTS, DELETE }

    /** Вид параметра в JPQL. */
    public enum ParamKind {
        SINGLE,
        BETWEEN_START,
        BETWEEN_END,
        COLLECTION
    }

    /** Метаданные одного именованного параметра в сгенерированном JPQL. */
    public record ParamMeta(String name, ParamKind kind, int argIndex) {}

    /**
     * Результат разбора имени метода.
     *
     * @param jpql     готовая JPQL-строка с именованными параметрами
     * @param type     тип запроса
     * @param params   упорядоченный список метаданных параметров
     * @param distinct присутствует ли DISTINCT
     * @param limit    значение Top/First, {@code -1} если не задано
     */
    public record ParsedQuery(
            String          jpql,
            QueryType       type,
            List<ParamMeta> params,
            boolean         distinct,
            int             limit
    ) {
        /**
         * Определяет правильный класс результата для передачи в
         * {@code session.createQuery(jpql, resultClass)} в Hibernate 6.
         *
         * @param entityClass класс сущности репозитория
         * @return {@code Long.class} для COUNT/EXISTS, {@code Void.class} для DELETE,
         *         либо {@code entityClass} для SELECT.
         */
        public Class<?> resolveResultClass(Class<?> entityClass) {
            return switch (type) {
                case COUNT, EXISTS -> Long.class;
                case DELETE        -> Void.class;
                case SELECT        -> entityClass;
            };
        }

        /**
         * Показывает, требует ли запрос вызова {@code session.createMutationQuery(jpql)}
         * и {@code executeUpdate()} вместо обычного {@code createQuery}.
         */
        public boolean isMutation() {
            return type == QueryType.DELETE;
        }
    }

    private record ConditionSegment(String fieldPath, String keyword, boolean ignoreCase) {}

    public static class InvalidMethodNameException extends RuntimeException {
        public InvalidMethodNameException(String message) { super(message); }
    }
}