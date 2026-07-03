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

Выход (плюс приватная рантайм-прелюдия в том же файле):

```kotlin
private data class EmpRow(
    val id: Int?,
    val name: String?,
    val deptno: Int?,
    val salary: Double?,
)

private data class DeptRow(
    val deptno: Int?,
    val dname: String?,
)

private data class JoinedRow(
    val id: Int?,
    val name: String?,
    val deptno: Int?,
    val salary: Double?,
    val deptno0: Int?,
    val dname: String?,
)

private data class Row(
    val dname: String?,
    val salary: Double?,
)

private data class GroupedRow(
    val dname: String?,
    val cnt: Long?,
    val avgSal: Double?,
)

fun query(tables: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> {
    val emp = tables.getValue("EMP")
        .map { r ->
            EmpRow(
                r["ID"] as Int?,
                r["NAME"] as String?,
                r["DEPTNO"] as Int?,
                r["SALARY"] as Double?,
            )
        }
    val dept = tables.getValue("DEPT")
        .map { r -> DeptRow(r["DEPTNO"] as Int?, r["DNAME"] as String?) }
    val joined = innerJoin(emp, dept, on = { l, r -> truth(eq(l.deptno, r.deptno)) }) { l, r ->
        JoinedRow(l.id, l.name, l.deptno, l.salary, r.deptno, r.dname)
    }
    return joined
        .filter { row -> truth(gt(row.salary, 500.0)) }
        .map { row -> Row(dname = row.dname, salary = row.salary) }
        .groupBy { row -> row.dname }
        .map { (dname, group) ->
            GroupedRow(
                dname = dname,
                cnt = group.size.toLong(),
                avgSal = aggAvg(group.map { it.salary }),
            )
        }
        .filter { row -> truth(gte(row.cnt, 1)) }
        .sortedWith(orderBy<GroupedRow>({ it.avgSal }, asc = false, nullsFirst = true))
        .take(10)
        .map { row -> mapOf("DNAME" to row.dname, "CNT" to row.cnt, "AVG_SAL" to row.avgSal) }
}
```

Генератор выдаёт типобезопасный и читаемый код: на каждую границу оператора
из `RelDataType` (типы уже вывел валидатор Calcite) генерируется `data class`,
так что выражения читаются как `row.salary`, а не `row[3]`; линейные операторы
эмитятся fluent-цепочкой; join'ы типизированы (`innerJoin`/`leftJoin`/...);
повторяющиеся подвыражения выносятся в один `val` с именем алиаса колонки;
таблицы, сканируемые дважды, материализуются один раз. Все свойства строк
nullable — это осознанно: внешние join'ы и SQL NULL-семантика делают non-null
гарантии непрактичными.

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

Строки внутри конвейера — сгенерированные `data class` (по одному на форму
строки; одинаковые формы переиспользуют класс): `RexInputRef(i)` превращается
в `row.<имя поля>` по позициям `RelDataType`. SQL-семантика (трёхзначная
логика, null-propagating сравнения/арифметика, joins, агрегаты, LIKE,
NULLS FIRST/LAST) реализована рантайм-прелюдией, дописываемой в конец каждого
сгенерированного файла.

| RelNode | Kotlin |
|---|---|
| TableScan | `tables.getValue("T").map { r -> EmpRow(r["ID"] as Int?, ...) }` |
| Filter | `.filter { row -> truth(...) }` |
| Project | `.map { row -> Row(name = row.name, ...) }` |
| Join (inner/left/right/full) | `innerJoin(l, r, on = { l, r -> ... }) { l, r -> JoinedRow(...) }` |
| Join (semi/anti) | `semiJoin(l, r) { l, r -> ... }` — тип строки левой стороны |
| Aggregate | `.groupBy { row -> row.key }.map { (key, group) -> GroupedRow(...) }`; глобальный агрегат — одна строка даже на пустом входе |
| Sort + limit/offset | `.sortedWith(orderBy<Row>({ it.x }, ...).thenOrderBy(...))` + `.drop(n).take(m)` |
| Values / Union | `listOf(ValuesRow(...))` / конкатенация (+ `.distinct()`) |

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
