package org.novasparkle.acrux.repository.generation;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Универсальный парсер имён методов репозитория в JPQL-строки.
 *
 * <p>Поддерживает:
 * <ul>
 *   <li>Префиксы: findBy, getBy, readBy, queryBy, searchBy, streamBy,
 *       countBy, existsBy, deleteBy, removeBy</li>
 *   <li>Операторы: And, Or с правильным приоритетом (And выше Or)</li>
 *   <li>Ключевые слова условий: Equals, Is, Not, Like, NotLike,
 *       StartingWith/StartsWith, EndingWith/EndsWith, Containing/Contains,
 *       LessThan, LessThanEqual, GreaterThan, GreaterThanEqual,
 *       Between, In, NotIn, IsNull, IsNotNull, True, False,
 *       Before, After, OrderBy + Asc/Desc</li>
 *   <li>Вложенные отношения через подчёркивание: findByAddress_CityName
 *       → u.address.cityName</li>
 *   <li>IgnoreCase / AllIgnoreCase</li>
 *   <li>Distinct: findDistinctBy...</li>
 *   <li>Top/First N: findTop3By..., findFirstBy...</li>
 *   <li>Сортировка: findByNameOrderByCreatedAtDesc</li>
 * </ul>
 *
 * <p>Пример:
 * <pre>{@code
 * MethodNameParser parser = new MethodNameParser("User", "u");
 * ParsedQuery q = parser.parse("findByAddress_CityNameAndAgeBetweenOrderByLastNameAsc");
 *
 * // q.jpql()   → SELECT u FROM User u
 * //               WHERE u.address.cityName = :param0
 * //               AND u.age BETWEEN :param1 AND :param2
 * //               ORDER BY u.lastName ASC
 *
 * // q.params() → [
 * //   ParamMeta{name="param0", kind=SINGLE,         argIndex=0},
 * //   ParamMeta{name="param1", kind=BETWEEN_START,  argIndex=1},
 * //   ParamMeta{name="param2", kind=BETWEEN_END,    argIndex=2},
 * // ]
 * }</pre>
 */
public class MethodNameParser {

    // ─────────────────────────────── константы ───────────────────────────────

    private static final Pattern SUBJECT_PATTERN = Pattern.compile(
        "^(find|get|read|query|search|stream|count|exists|delete|remove)" +
        "(Distinct)?(Top|First)?(\\d+)?By(.*)$"
    );

    /**
     * Ключевые слова условий.
     * Порядок критичен: от длинных к коротким, чтобы жадный матчинг
     * не срезал «LessThan» вместо «LessThanEqual».
     */
    private static final List<String> CONDITION_KEYWORDS = List.of(
        "IsNotNull", "IsNull",
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
        "IgnoreCase",
        "Equals", "Is"      // «мягкие» — не меняют оператор сравнения
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

        String  action    = m.group(1);           // find | count | exists | delete | ...
        boolean distinct  = m.group(2) != null;   // Distinct
        String  limitStr  = m.group(4);           // число после Top/First
        String  predicate = m.group(5);           // всё после By

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

        // ── ORDER BY ──────────────────────────────────────────────────────
        String orderJpql = orderByRaw.isEmpty() ? "" : buildOrderBy(orderByRaw);

        // ── Собираем итоговый JPQL ────────────────────────────────────────
        String jpql = assembleJpql(type, distinct, whereJpql, orderJpql);

        int limit = (limitStr != null) ? Integer.parseInt(limitStr) : -1;

        return new ParsedQuery(jpql, type, Collections.unmodifiableList(params), distinct, limit);
    }

    // ─────────────────────────────── сборка JPQL ─────────────────────────────

    private String assembleJpql(QueryType type, boolean distinct,
                                 String whereJpql, String orderJpql) {
        StringBuilder sb = new StringBuilder();

        switch (type) {
            case COUNT, EXISTS -> sb.append("SELECT COUNT(")
                                    .append(alias).append(") FROM ")
                                    .append(entityName).append(' ').append(alias);

            case DELETE -> sb.append("DELETE FROM ")
                             .append(entityName).append(' ').append(alias);

            default -> {
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

    /**
     * Строит выражение WHERE с правильным приоритетом And {@literal >} Or.
     *
     * <ol>
     *   <li>Разбить по Or  → Or-группы</li>
     *   <li>Каждую Or-группу разбить по And → And-сегменты</li>
     *   <li>Каждый сегмент → одно условие (поле + ключевое слово)</li>
     * </ol>
     *
     * {@code params} накапливается in-place — порядок элементов совпадает
     * с порядком именованных параметров в JPQL и с порядком аргументов метода.
     */
    private String buildWhereExpression(String predicate,
                                        boolean allIgnoreCase,
                                        List<ParamMeta> params) {

        List<String> orParts = splitByOperator(predicate, "Or");

        // Счётчик JPQL-параметров — единый для всего предиката
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

        }).collect(Collectors.toList());

        if (orClauses.size() == 1) return orClauses.get(0);

        return orClauses.stream()
            .map(c -> "(" + c + ")")
            .collect(Collectors.joining(" OR "));
    }

    /**
     * Разбивает строку по оператору {@code And} или {@code Or}.
     * Условие разбиения: после оператора идёт заглавная буква или конец строки.
     * Это защищает от ложных срабатываний на поля вроде {@code orderId}.
     */
    private List<String> splitByOperator(String s, String op) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i     = 0;

        while (i < s.length()) {
            if (s.startsWith(op, i)) {
                int  afterIdx = i + op.length();
                char after    = afterIdx < s.length() ? s.charAt(afterIdx) : '\0';
                if (Character.isUpperCase(after) || after == '\0') {
                    result.add(s.substring(start, i));
                    start = afterIdx;
                    i     = afterIdx;
                    continue;
                }
            }
            i++;
        }
        result.add(s.substring(start));

        return result.stream().filter(p -> !p.isEmpty()).collect(Collectors.toList());
    }

    // ────────────────────── разбор одного сегмента условия ───────────────────

    /**
     * Выделяет имя поля и ключевое слово из одного сегмента предиката.
     * Жадный матчинг суффиксов гарантирует, что «LessThanEqual» всегда
     * победит над «LessThan».
     */
    private ConditionSegment parseConditionSegment(String segment) {
        for (String kw : CONDITION_KEYWORDS) {
            if (segment.endsWith(kw)) {
                String fieldPart = segment.substring(0, segment.length() - kw.length());
                if (!fieldPart.isEmpty()) {
                    return new ConditionSegment(toJpqlPath(fieldPart), kw);
                }
            }
        }
        // Нет явного ключевого слова → подразумевается Equals
        return new ConditionSegment(toJpqlPath(segment), "Equals");
    }

    /**
     * Генерирует JPQL-выражение для одного условия и регистрирует
     * все связанные {@link ParamMeta} в списке {@code params}.
     *
     * <p>Правила регистрации:
     * <ul>
     *   <li>{@code SINGLE}       — один параметр, один аргумент метода</li>
     *   <li>{@code BETWEEN_START} + {@code BETWEEN_END} — два параметра,
     *       два последовательных аргумента</li>
     *   <li>{@code COLLECTION}   — один параметр, один аргумент-Collection/массив</li>
     *   <li>Нулевые (IsNull, True, …) — параметры не добавляются</li>
     * </ul>
     *
     * @param nextParam [0] — текущий счётчик JPQL-параметров; мутируется in-place
     */
    private String buildConditionExpression(ConditionSegment cs,
                                            boolean allIgnoreCase,
                                            List<ParamMeta> params,
                                            int[] nextParam) {
        String path = cs.fieldPath();
        String kw   = cs.keyword();

        boolean ignoreCase = allIgnoreCase
            || kw.equals("IgnoreCase")
            || kw.equals("Containing") || kw.equals("Contains")
            || kw.equals("StartingWith") || kw.equals("StartsWith")
            || kw.equals("EndingWith")   || kw.equals("EndsWith");

        // Имя и строковое представление текущего параметра
        String pName  = "param" + nextParam[0];
        String param  = ":" + pName;
        String lpath  = ignoreCase ? "LOWER(" + path + ")" : path;
        String lparam = ignoreCase ? "LOWER(" + param + ")" : param;

        return switch (kw) {

            // ── без параметров (не трогаем nextParam и params) ────────────
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

            // ── единичные параметры ───────────────────────────────────────
            case "Equals", "Is", "IgnoreCase" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " = " + lparam;
            }
            case "Not" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield lpath + " != " + lparam;
            }
            case "Like" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " LIKE " + param;
            }
            case "NotLike" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " NOT LIKE " + param;
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
            case "LessThan" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " < " + param;
            }
            case "LessThanEqual" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " <= " + param;
            }
            case "GreaterThan" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " > " + param;
            }
            case "GreaterThanEqual" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " >= " + param;
            }
            case "Before" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " < " + param;
            }
            case "After" -> {
                params.add(new ParamMeta(pName, ParamKind.SINGLE, nextArgIndex(params)));
                nextParam[0]++;
                yield path + " > " + param;
            }

            default -> throw new InvalidMethodNameException(
                "Неизвестное ключевое слово: «" + kw + "»"
            );
        };
    }

    // ──────────────────────── ORDER BY ────────────────────────────────────────

    /**
     * Строит ORDER BY из строки вида {@code LastNameAscCreatedAtDesc}.
     * Если явных Asc/Desc нет — подразумевается ASC.
     */
    private String buildOrderBy(String orderBy) {
        List<String> orders = new ArrayList<>();

        Pattern p = Pattern.compile("([A-Z][a-zA-Z_]*?)(Asc|Desc)(?=[A-Z]|$)");
        Matcher m = p.matcher(orderBy);
        while (m.find()) {
            orders.add(toJpqlPath(m.group(1)) + " " + m.group(2).toUpperCase());
        }

        if (orders.isEmpty() && !orderBy.isEmpty()) {
            orders.add(toJpqlPath(orderBy) + " ASC");
        }

        return String.join(", ", orders);
    }

    // ────────────────────────── утилиты ──────────────────────────────────────

    /**
     * Преобразует имя поля (CamelCase + подчёркивание для отношений) в JPQL-путь.
     *
     * <ul>
     *   <li>{@code Name}             → {@code u.name}</li>
     *   <li>{@code Address_CityName} → {@code u.address.cityName}</li>
     *   <li>{@code Address_City_CountryIsoCode} → {@code u.address.city.countryIsoCode}</li>
     * </ul>
     */
    private String toJpqlPath(String field) {
        String path = Arrays.stream(field.split("_"))
            .map(part -> Character.toLowerCase(part.charAt(0)) + part.substring(1))
            .collect(Collectors.joining("."));
        return alias + "." + path;
    }

    /** Индекс следующего свободного аргумента метода. */
    private int nextArgIndex(List<ParamMeta> params) {
        if (params.isEmpty()) return 0;
        return params.get(params.size() - 1).argIndex() + 1;
    }

    /** Находит позицию «OrderBy» на уровне предиката (не внутри имени поля). */
    private int indexOfOrderBy(String s) {
        int idx = s.indexOf("OrderBy");
        while (idx >= 0) {
            if (idx == 0 || Character.isUpperCase(s.charAt(idx))) return idx;
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

    /**
     * Вид параметра в JPQL — определяет как биндить значение
     * и сколько аргументов метода он потребляет.
     */
    public enum ParamKind {
        /** Обычное сравнение — один аргумент. */
        SINGLE,
        /** Первый параметр BETWEEN — один аргумент. */
        BETWEEN_START,
        /** Второй параметр BETWEEN — один аргумент. */
        BETWEEN_END,
        /** IN / NOT IN — один аргумент-коллекция или массив. */
        COLLECTION
    }

    /**
     * Метаданные одного именованного параметра в сгенерированном JPQL.
     *
     * @param name     имя параметра в JPQL без «:», напр. {@code param0}
     * @param kind     вид параметра — определяет стратегию биндинга
     * @param argIndex индекс аргумента метода-репозитория
     */
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
    ) {}

    private record ConditionSegment(String fieldPath, String keyword) {}

    public static class InvalidMethodNameException extends RuntimeException {
        public InvalidMethodNameException(String message) { super(message); }
    }
}
