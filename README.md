# sql-to-kotlin

Конвертер SQL-запросов в исполняемый Kotlin-код поверх стандартного collections API.

Текст SQL → Apache Calcite (parser → validator → `SqlToRelConverter`) → обход
`RelNode`-дерева → кодогенератор → самодостаточный `.kt`-файл с функцией

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
fun query(tables: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> {
    val emp = tables.getValue("EMP")
        .map { r -> listOf<Any?>(r["ID"], r["NAME"], r["DEPTNO"], r["SALARY"]) }
    val dept = tables.getValue("DEPT")
        .map { r -> listOf<Any?>(r["DEPTNO"], r["DNAME"]) }
    val joined = joinRows(emp, dept, leftArity = 4, rightArity = 2, JoinType.INNER) { row ->
        val deptno = row[2]
        val deptno2 = row[4]
        truth(eq(deptno, deptno2))
    }
    return joined
        .filter { row ->
            val salary = row[3]
            truth(gt(salary, 500.0))
        }
        .map { row ->
            val salary = row[3]
            val dname = row[5]
            listOf<Any?>(
                dname,
                salary,
            )
        }
        .groupBy { row -> listOf(row[0]) } // GROUP BY DNAME
        .map { (key, group) ->
            key + listOf<Any?>(
                group.size.toLong(), // COUNT(*)
                aggAvg(group.map { it[1] }), // AVG(SALARY)
            )
        }
        .filter { row ->
            val cnt = row[1]
            truth(gte(cnt, 1))
        }
        .sortedWith(rowComparator(listOf(SortKey(2, asc = false, nullsFirst = true)))) // ORDER BY AVG_SAL DESC
        .take(10)
        .map { row -> mapOf("DNAME" to row[0], "CNT" to row[1], "AVG_SAL" to row[2]) }
}
```

Генератор старается выдавать код, который можно читать глазами: колонки
раскрываются в именованные локальные `val`, линейные цепочки операторов
эмитятся fluent-цепочкой, повторяющиеся подвыражения выносятся в один `val`
(алиас колонки становится его именем), таблицы, сканируемые дважды,
материализуются один раз, `GROUP BY`/`ORDER BY`/агрегаты аннотируются
комментариями.

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
   `RexUtil.expandSearch`). Текст файла собирается собственным эмиттером:
   изначально использовался KotlinPoet, но его автоперенос строк ломал
   выражения посередине вызова и не поддавался настройке, поэтому ради
   читаемости вывода от него отказались.

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
коррелированные `EXISTS`/`NOT EXISTS`/скалярные), `WITH` (не рекурсивный),
даты/время: литералы `DATE`/`TIME`/`TIMESTAMP`, сравнения и сортировка,
`datetime ± INTERVAL`, разность дат (`(d1 - d2) DAY`), `EXTRACT`
(`YEAR`/`QUARTER`/`MONTH`/`DAY`/`DOW`/`DOY`/`WEEK`/`HOUR`/`MINUTE`/`SECOND`),
`CAST` строк в даты, `CURRENT_DATE`/`CURRENT_TIMESTAMP`.

Не поддержано: оконные функции, `WITH RECURSIVE`, `GROUPING SETS`/`ROLLUP`/
`CUBE`, DDL, диалектные расширения, таймзоны (`TIMESTAMP WITH TIME ZONE`).

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
- Даты/время маппятся на `java.time`: `DATE` → `LocalDate`, `TIME` → `LocalTime`,
  `TIMESTAMP` → `LocalDateTime`; интервалы: day-time → `Duration`,
  year-month → `Period`. Эти же типы ожидаются во входных данных.
- NULL при сортировке по умолчанию считается наибольшим значением (как в
  Calcite/Oracle): `DESC` ставит NULL первыми, если не указано `NULLS LAST`.
- Неквотированные идентификаторы нормализуются в UPPER CASE — ключи во входных
  и выходных `Map` верхнерегистровые.
