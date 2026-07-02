# sql-to-kotlin

Конвертер SQL-запросов в исполняемый Kotlin-код поверх стандартного collections API.

Текст SQL → Apache Calcite (parser → validator → `SqlToRelConverter`) → обход
`RelNode`-дерева → KotlinPoet → самодостаточный `.kt`-файл с функцией

```kotlin
fun query(tables: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>>
```

`tables` — имя таблицы (UPPER CASE) → строки; строка — имя колонки → значение.
Сгенерированному файлу для компиляции нужен только kotlin-stdlib.

## Пример

```
gradle run                                    # демо-запрос
gradle run --args="SELECT name FROM emp WHERE salary > 900"
```

Вход:

```sql
SELECT d.dname, count(*) AS cnt, avg(e.salary) AS avg_sal
FROM emp e JOIN dept d ON e.deptno = d.deptno
WHERE e.salary > 500
GROUP BY d.dname
HAVING count(*) >= 1
ORDER BY avg_sal DESC
LIMIT 10
```

Выход (тело `query`, плюс приватная рантайм-прелюдия в том же файле):

```kotlin
val v0: List<List<Any?>> = tables.getValue("EMP").map { r -> listOf(r["ID"], r["NAME"], r["DEPTNO"], r["SALARY"]) }
val v1: List<List<Any?>> = tables.getValue("DEPT").map { r -> listOf(r["DEPTNO"], r["DNAME"]) }
val v2 = joinRows(v0, v1, 4, 2, JoinType.INNER) { row -> truth(eq(row[2], row[4])) }
val v3 = v2.filter { row -> truth(gt(row[3], asDouble(500))) }
val v4 = v3.map { row -> listOf<Any?>(row[5], row[3]) }
val v5 = v4.groupBy { row -> listOf(row[0]) }.map { (key, group) -> key +
    listOf<Any?>(asLong(group.size.toLong()), asDouble(aggAvg(group.map { it[1] }))) }
val v6 = v5.filter { row -> truth(gte(row[1], 1)) }
val v7 = v6.sortedWith(rowComparator(listOf(SortKey(2, false, true))))
val v8 = v7.take(10)
return v8.map { row -> mapOf("DNAME" to row[0], "CNT" to row[1], "AVG_SAL" to row[2]) }
```

## Архитектура

1. **Парсинг** — `org.apache.calcite.sql.parser.SqlParser` (только парсер, без
   оптимизатора и исполнителя).
2. **Валидация и типизация** — `SqlValidator` поверх `CalciteCatalogReader`;
   схема таблиц описывается в коде (`SqlSchema`/`TableDef`/`Column`).
3. **Реляционная алгебра** — `SqlToRelConverter` с `expand=true` (подзапросы
   разворачиваются в Join/Aggregate) и `decorrelate` (коррелированные
   подзапросы становятся join'ами). Дальше кодоген работает с закрытым набором
   операторов `RelNode`, а не с синтаксическим разнообразием `SqlNode`.
4. **Кодогенерация** — обход дерева снизу вверх, один Kotlin-стейтмент на
   оператор (`RelToKotlin`); выражения `RexNode` транслируются в выражения
   Kotlin (`RexToKotlin`, `Sarg`/`SEARCH` раскрывается через
   `RexUtil.expandSearch`). Файл собирается KotlinPoet.

Строки внутри конвейера позиционные (`List<Any?>`) — `RexInputRef(i)` дословно
превращается в `row[i]`, разрешение имён не нужно. SQL-семантика (трёхзначная
логика, null-propagating сравнения/арифметика, joins, агрегаты, LIKE,
NULLS FIRST/LAST) реализована рантайм-прелюдией, дописываемой в конец каждого
сгенерированного файла.

| RelNode | Kotlin |
|---|---|
| TableScan | `tables.getValue("T").map { ... }` → позиционные строки |
| Filter | `.filter { row -> truth(...) }` |
| Project | `.map { row -> listOf(...) }` |
| Join (inner/left/right/full/semi/anti) | `joinRows(...)` из прелюдии |
| Aggregate | `.groupBy { ... }.map { (key, group) -> ... }`; глобальный агрегат — одна строка даже на пустом входе |
| Sort + limit/offset | `.sortedWith(rowComparator(...))` + `.drop(n).take(m)` |
| Values / Union | `listOf(...)` / конкатенация (+ `.distinct()`) |

## Что поддержано

`SELECT` (в т.ч. `DISTINCT`), `WHERE`, `JOIN` (inner/left/right/full по
equality, многотабличные), `CASE WHEN`, `IN`-списки, `BETWEEN`, `LIKE`,
арифметика и базовые функции (`UPPER`/`LOWER`/`SUBSTRING`/`CHAR_LENGTH`/`ABS`/
`COALESCE`/`||`), агрегаты `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` (+ `DISTINCT`),
`GROUP BY` (в т.ч. по выражению), `HAVING`, `ORDER BY` (`NULLS FIRST/LAST`),
`LIMIT`/`OFFSET`, `UNION`, подзапросы в `FROM`/`WHERE`/`SELECT` (включая
коррелированные `EXISTS`/`NOT EXISTS`/скалярные), `WITH` (не рекурсивный).

Не поддержано: оконные функции, `WITH RECURSIVE`, `GROUPING SETS`/`ROLLUP`/
`CUBE`, DDL, диалектные расширения, типы даты/времени.

## Тесты

```
gradle test
```

Каждый тест — конкретный SQL-запрос: конвертер генерирует `.kt`, тест компилирует
его in-process (`kotlin-compiler-embeddable`), загружает в изолированный
classloader, вызывает `query` на общих тестовых данных (`EMP`/`DEPT`/`ORDERS`,
с NULL-ами для проверки null-семантики) и сравнивает результат с эталоном.

## Замечания по семантике

- Типы результата следуют выводу Calcite: `COUNT` → `Long`, `SUM/AVG` над
  `INTEGER` → `Int` (целочисленное усечение для `AVG`), над `DOUBLE` → `Double`.
- `DECIMAL` аппроксимируется `Double`.
- NULL при сортировке по умолчанию считается наибольшим значением (как в
  Calcite/Oracle): `DESC` ставит NULL первыми, если не указано `NULLS LAST`.
- Неквотированные идентификаторы нормализуются в UPPER CASE — ключи во входных
  и выходных `Map` верхнерегистровые.
